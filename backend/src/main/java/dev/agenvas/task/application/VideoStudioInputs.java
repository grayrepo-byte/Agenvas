package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.artifact.domain.MediaDraft.InputRole;
import dev.agenvas.artifact.domain.MediaDraft.VideoInputMode;
import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.provider.domain.ComfyUiWorkflowDefinition;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.domain.KlingProtocol;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.domain.MiniMaxH3Protocol;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.Task;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Resolves video Studio inputs without inheriting a CanvasItem, project format, or mutable asset selection. */
@Component
class VideoStudioInputs {
    static final int MAX_REFERENCES = 14;
    private static final Set<String> CREATOR_FIELD_KEYS = Set.of("key", "label", "description", "type", "required",
            "defaultValue", "minimum", "maximum", "step", "maxLength", "options", "advanced", "source", "enabledWhen",
            "group");
    private static final List<String> RATIOS = List.of("16:9", "9:16", "1:1");
    private final ProjectService projects;
    private final MediaCapabilityService capabilities;
    private final DirectMediaTaskService generation;
    private final AssetService assets;
    private final ArtifactService artifacts;
    private final ProjectEventService events;
    private final ObjectMapper mapper;

    VideoStudioInputs(ProjectService projects, MediaCapabilityService capabilities, DirectMediaTaskService generation,
            AssetService assets, ArtifactService artifacts, ProjectEventService events, ObjectMapper mapper) {
        this.projects = projects;
        this.capabilities = capabilities;
        this.generation = generation;
        this.assets = assets;
        this.artifacts = artifacts;
        this.events = events;
        this.mapper = mapper;
    }

    VideoStudioService.Context context(UUID owner) {
        UUID project = projects.ensureVideoStudioScope(owner).id();
        // A duration-filtered catalog (including duration 0) would omit otherwise valid models.
        var catalog = capabilities.publishedCandidates().stream()
                .filter(candidate -> candidate.kind() == Task.Kind.VIDEO_GENERATION)
                .map(candidate -> capability(candidate.binding(), candidate.capabilityName(),
                        candidate.connectionName(),
                        candidate.settings()))
                .filter(java.util.Objects::nonNull).toList();
        UUID preferred = capabilities.defaultCapabilityId(Task.Kind.VIDEO_GENERATION);
        UUID selected = catalog.stream().map(VideoStudioService.Capability::capabilityId)
                .filter(id -> id.equals(preferred)).findFirst()
                .orElseGet(() -> catalog.isEmpty() ? null : catalog.getFirst().capabilityId());
        return new VideoStudioService.Context(project, selected, catalog);
    }

    private VideoStudioService.Capability capability(MediaCapabilityBinding binding, String name,
            String connectionName, JsonNode settings) {
        var policy = capabilities.inputPolicy(binding);
        if (policy.platform() == MediaPlatform.LOCAL)
            return null;
        String adapter = binding.adapterId();
        List<JsonNode> fields = fields(binding, settings);
        var limits = new VideoStudioService.ReferenceLimits(policy.maxReferenceImages(), policy.maxReferenceVideos(),
                policy.maxReferenceAudios());
        boolean hasMediaSlots = fields != null && fields.stream().anyMatch(VideoStudioInputs::mediaField);
        boolean requiresMedia = hasMediaSlots && fields.stream().anyMatch(field -> mediaField(field)
                && field.path("required").asBoolean(false) && !field.hasNonNull("enabledWhen"));
        boolean requiresTail = AutoDlWorkflows.ADAPTER_ID.equals(adapter)
                && "START_END".equals(AutoDlWorkflows.require(settings).mode());
        var modes = new ArrayList<VideoInputMode>(3);
        if (policy.supportedVideoInputModes().contains(VideoInputMode.TEXT.name()) && !requiresMedia)
            modes.add(VideoInputMode.TEXT);
        boolean tail = policy.supportsEndFrame() && limits.image() >= 2;
        if (fields == null && limits.image() >= 1 && (!requiresTail || tail)
                && policy.supportedVideoInputModes().contains(VideoInputMode.START_END.name()))
            modes.add(VideoInputMode.START_END);
        if (policy.supportedVideoInputModes().contains(VideoInputMode.GENERAL_REFERENCE.name())
                && (fields == null ? limits.image() + limits.video() + limits.audio() > 0 : hasMediaSlots))
            modes.add(VideoInputMode.GENERAL_REFERENCE);
        if (modes.isEmpty())
            return null;
        VideoInputMode defaultMode = modes.stream().filter(mode -> mode.name().equals(policy.defaultVideoInputMode()))
                .findFirst().orElse(modes.getFirst());
        var duration = fields == null
                ? null
                : fields.stream()
                        .filter(field -> hasSource(field, RunningHubDefinition.Source.DURATION_SECONDS)).findFirst()
                        .orElse(null);
        boolean namedComfy = fields != null && policy.platform() == MediaPlatform.COMFYUI;
        boolean adjustableDuration = fields == null || duration != null || namedComfy;
        int min = Math.max(1, policy.minimumSeconds());
        int max = policy.maximumSeconds();
        if (duration != null) {
            min = Math.max(min, duration.path("minimum").asInt(min));
            max = Math.min(max, duration.path("maximum").asInt(max));
        }
        if (adjustableDuration && min > max)
            return null;
        int seconds = duration != null && duration.hasNonNull("defaultValue")
                ? duration.path("defaultValue").asInt()
                : settings.path("defaultDurationSeconds").asInt(min);
        if (duration != null && "SELECT".equals(duration.path("type").asText())) {
            final int lower = min;
            final int upper = max;
            var options = duration.path("options").valueStream().map(option -> option.path("value"))
                    .filter(value -> value.isIntegralNumber() && value.canConvertToInt())
                    .map(JsonNode::intValue).filter(value -> value >= lower && value <= upper).toList();
            if (options.isEmpty())
                return null;
            if (!options.contains(seconds))
                seconds = options.getFirst();
        }
        boolean adjustableRatio = fields == null || namedComfy && capabilities.comfyWorkflowDefinition(binding)
                .maps(ComfyUiWorkflowDefinition.Source.WIDTH);
        JsonNode defaults = settings.path("defaultParameters");
        var resolutions = resolutions(adapter, settings, fields);
        String defaultResolution = resolutions.isEmpty()
                ? null
                : defaults.path("videoResolution")
                        .asText(settings.path("videoResolution").asText(resolutions.getFirst()));
        ObjectNode dynamicDefaults = mapper.createObjectNode();
        if (fields != null)
            for (JsonNode field : fields) {
                String key = field.path("key").asText();
                if (scalarParameter(field) && defaults.path(RunningHubDefinition.VALUES_PROPERTY).hasNonNull(key))
                    dynamicDefaults.set(key, defaults.path(RunningHubDefinition.VALUES_PROPERTY).get(key));
            }
        return new VideoStudioService.Capability(binding.capabilityId(), binding.capabilityVersion(), name,
                connectionName, adapter, List.copyOf(modes), defaultMode,
                tail && modes.contains(VideoInputMode.START_END),
                requiresTail, adjustableDuration ? min : null, adjustableDuration ? max : null,
                adjustableDuration ? Math.clamp(seconds, min, max) : null, adjustableRatio ? RATIOS : List.of(),
                adjustableRatio ? physicalRatio(defaults.path("aspectRatio").asText("AUTO")) : null,
                resolutions, defaultResolution, limits, fields, dynamicDefaults);
    }

    private List<JsonNode> fields(MediaCapabilityBinding binding, JsonNode settings) {
        JsonNode fields = settings.path("runningHub").path("fields");
        if (!fields.isArray())
            fields = settings.path("nativeInputs");
        if (!fields.isArray())
            fields = settings.path("klingInputs");
        if (!fields.isArray())
            fields = settings.path(ComfyUiWorkflowDefinition.PUBLIC_INPUTS_KEY);
        if (!fields.isArray() && NativeMediaProfiles.supports(binding.adapterId()))
            fields = mapper.valueToTree(capabilities.nativeProfile(binding).inputs());
        if (!fields.isArray() && KlingModels.adapter(binding.adapterId()))
            fields = mapper.valueToTree(KlingModels.inputs(capabilities.klingProfile(binding)));
        if (!fields.isArray() && ComfyUiWorkflowDefinition.configured(settings))
            fields = mapper.valueToTree(capabilities.comfyWorkflowDefinition(binding).inputs());
        return fields.isArray() ? fields.valueStream().map(this::creatorField).toList() : null;
    }

    private JsonNode creatorField(JsonNode definition) {
        ObjectNode publicField = mapper.createObjectNode();
        definition.properties().forEach(entry -> {
            if (CREATOR_FIELD_KEYS.contains(entry.getKey()))
                publicField.set(entry.getKey(), entry.getValue());
        });
        if (!publicField.hasNonNull("source"))
            publicField.put("source", RunningHubDefinition.Source.PARAMETER.name());
        return publicField;
    }

    private static List<String> resolutions(String adapter, JsonNode settings, List<JsonNode> fields) {
        if (fields != null)
            return List.of();
        return switch (adapter) {
            case AutoDlWorkflows.ADAPTER_ID -> settings.path("videoResolutions").isArray()
                    ? settings.path("videoResolutions").valueStream().map(JsonNode::asText).toList()
                    : List.of(AutoDlWorkflows.selectedResolution(settings, null));
            case MiniMaxH3Protocol.ADAPTER_ID -> List.of(MiniMaxH3Protocol.DEFAULT_RESOLUTION, "1440p");
            case KlingProtocol.VIDEO_ADAPTER_ID -> List.of(KlingProtocol.DEFAULT_RESOLUTION, "720p", "2160p");
            default -> List.of();
        };
    }

    Prepared prepare(UUID owner, UUID project, VideoStudioService.Command command) {
        MediaCapabilityBinding binding = capabilities.forDraft(command.capabilityId(), Task.Kind.VIDEO_GENERATION);
        if (binding.capabilityVersion() != command.capabilityVersion())
            throw VideoStudioService.conflict("MEDIA_CAPABILITY_CHANGED");
        var controls = capability(binding, null, null, capabilities.settings(binding));
        if (controls == null || !controls.modes().contains(command.mode()))
            throw invalid();
        Integer duration = command.durationSeconds();
        if (controls.minDurationSeconds() == null && duration != null
                || duration == null && controls.minDurationSeconds() != null && controls.fields() == null
                || duration != null && (duration < controls.minDurationSeconds()
                        || duration > controls.maxDurationSeconds()))
            throw invalid();
        if (duration == null && controls.fields() != null)
            duration = controls.defaultDurationSeconds();
        UUID start = command.startFrameVersionId();
        UUID end = command.endFrameVersionId();
        var requestedRefs = command.references();
        switch (command.mode()) {
            case TEXT -> {
                if (start != null || end != null || !requestedRefs.isEmpty())
                    throw invalid();
            }
            case START_END -> {
                if (start == null || (end == null ? controls.requiresEndFrame() : !controls.supportsEndFrame())
                        || !requestedRefs.isEmpty())
                    throw invalid();
            }
            case GENERAL_REFERENCE -> {
                if (start != null || end != null || controls.fields() == null && requestedRefs.isEmpty())
                    throw invalid();
            }
        }
        String ratio = command.aspectRatio() == null
                ? controls.defaultAspectRatio()
                : physicalRatio(command.aspectRatio());
        String resolution = command.videoResolution() == null
                ? controls.defaultVideoResolution()
                : command.videoResolution();
        if (ratio != null && !controls.aspectRatios().contains(ratio)
                || resolution != null && !controls.videoResolutions().contains(resolution))
            throw invalid();
        ObjectNode parameters = mapper.createObjectNode();
        if (ratio != null)
            parameters.put("aspectRatio", ratio);
        if (resolution != null)
            parameters.put("videoResolution", resolution);
        ObjectNode values = mapper.createObjectNode();
        var fieldMap = new LinkedHashMap<String, JsonNode>();
        if (controls.fields() != null)
            for (JsonNode field : controls.fields())
                fieldMap.put(field.path("key").asText(), field);
        for (var entry : command.dynamicValues().properties()) {
            JsonNode field = fieldMap.get(entry.getKey());
            if (field == null || !scalarParameter(field) || !scalar(entry.getValue()))
                throw invalid();
            values.set(entry.getKey(), entry.getValue());
        }
        if (controls.fields() != null && !command.prompt().isBlank()
                && fieldMap.values().stream().noneMatch(field -> hasSource(field, RunningHubDefinition.Source.PROMPT)))
            throw invalid();
        var first = start == null ? null : frame(owner, project, start);
        var last = end == null ? null : frame(owner, project, end);
        List<MediaDraft.MediaInput> refs = new ArrayList<>();
        if (first != null)
            refs.add(mediaInput(first.versionId(), first.artifactId(), InputRole.START_FRAME, refs.size()));
        if (last != null)
            refs.add(mediaInput(last.versionId(), last.artifactId(), InputRole.END_FRAME, refs.size()));
        var references = new ArrayList<VideoStudioService.ReferenceAsset>();
        var usedFields = new HashSet<String>();
        var byVersion = new LinkedHashMap<UUID, VideoStudioService.ReferenceAsset>();
        var counts = new EnumMap<Asset.MediaKind, Integer>(Asset.MediaKind.class);
        for (var input : requestedRefs) {
            if (input == null || input.kind() == null || input.versionId() == null)
                throw invalid();
            if (controls.fields() != null) {
                JsonNode field = fieldMap.get(input.fieldKey());
                if (field == null || !input.kind().name().equals(field.path("type").asText())
                        || !usedFields.add(input.fieldKey()))
                    throw invalid();
                values.put(input.fieldKey(), input.versionId().toString());
            } else if (input.fieldKey() != null || byVersion.containsKey(input.versionId()))
                throw invalid();
            var version = byVersion.get(input.versionId());
            if (version == null) {
                if (counts.merge(input.kind(), 1, Integer::sum) > controls.referenceLimits().forKind(input.kind()))
                    throw invalid();
                version = reference(owner, project, input.kind(), input.versionId(), input.fieldKey());
                byVersion.put(version.versionId(), version);
                refs.add(mediaInput(version.versionId(), version.artifactId(), role(input.kind()), refs.size()));
            } else {
                if (version.kind() != input.kind())
                    throw invalid();
                version = version.inField(input.fieldKey());
            }
            references.add(version);
        }
        if (!values.isEmpty())
            parameters.set(RunningHubDefinition.VALUES_PROPERTY, values);
        var draft = new MediaDraft(project, null, command.prompt().strip(), parameters, duration,
                binding.capabilityId(),
                null, command.mode(), refs, List.of(), MediaDraft.DisplayMode.DRAFT, 0, Instant.EPOCH, Instant.EPOCH);
        var compiled = generation.prepareStudioVideo(owner, project, draft, binding);
        return new Prepared(binding, compiled, ratio, first, last, List.copyOf(references));
    }

    private static boolean scalar(JsonNode value) {
        return value.isTextual() || value.isBoolean() || value.isNumber();
    }

    private static boolean scalarParameter(JsonNode field) {
        return !mediaField(field)
                && (!field.hasNonNull("source") || hasSource(field, RunningHubDefinition.Source.PARAMETER));
    }

    private static boolean hasSource(JsonNode field, RunningHubDefinition.Source source) {
        return source.name().equals(field.path("source").asText());
    }

    private static boolean mediaField(JsonNode field) {
        return switch (field.path("type").asText()) {
            case "IMAGE", "AUDIO", "VIDEO" -> true;
            default -> false;
        };
    }

    private static InputRole role(Asset.MediaKind kind) {
        return switch (kind) {
            case IMAGE -> InputRole.REFERENCE;
            case VIDEO -> InputRole.VIDEO_REFERENCE;
            case AUDIO -> InputRole.AUDIO_REFERENCE;
        };
    }

    private static MediaDraft.MediaInput mediaInput(UUID version, UUID artifact, InputRole role, int order) {
        return new MediaDraft.MediaInput(version, artifact, role, order, null, List.of());
    }

    VideoStudioService.ReferenceAsset upload(UUID owner, Asset.MediaKind kind, InputStream input) {
        UUID project = projects.ensureVideoStudioScope(owner).id();
        // Decode and install outside the DB transaction; rejected uploads leave no registered asset or artifact.
        java.util.function.Function<Asset, VideoStudioService.ReferenceAsset> publish = asset -> events
                .recordChange(owner, project, () -> {
                    assets.registerLibraryImport(owner, asset);
                    var artifact = artifacts.create(owner, project, Artifact.Kind.valueOf(kind.name()),
                            "Video Studio reference", mapper.createObjectNode()
                                    .put("sourceType", ArtifactVersion.MediaSourceType.UPLOAD.name())
                                    .put("assetId", asset.id().toString()));
                    return ProjectEventService.Change.unchanged(
                            toReference(project, artifact.resourceDefaultVersion(), asset, null));
                }).value();
        return kind == Asset.MediaKind.IMAGE
                ? assets.withPreparedStaticImageUpload(owner, project, input, publish)
                : assets.withPreparedUpload(owner, project, UUID.randomUUID(), kind, () -> input, publish);
    }

    private VideoStudioService.Frame frame(UUID owner, UUID project, UUID versionId) {
        return reference(owner, project, Asset.MediaKind.IMAGE, versionId, null).asFrame();
    }

    private VideoStudioService.ReferenceAsset reference(UUID owner, UUID project, Asset.MediaKind kind, UUID versionId,
            String fieldKey) {
        ArtifactVersion version = artifacts.requireMediaVersionForTask(owner, project, versionId,
                Artifact.Kind.valueOf(kind.name()));
        if (artifacts.get(owner, project, version.artifactId()).artifact().archivedAt() != null)
            throw invalid();
        Asset asset = assets.requireReadyMedia(owner, project,
                UUID.fromString(version.content().path("assetId").asText()),
                kind);
        if (kind == Asset.MediaKind.IMAGE)
            ImageGenerationPreparation.requireStaticImage(asset);
        return toReference(project, version, asset, fieldKey);
    }

    private static VideoStudioService.ReferenceAsset toReference(UUID project, ArtifactVersion version, Asset asset,
            String fieldKey) {
        return new VideoStudioService.ReferenceAsset(version.artifactId(), version.id(), asset.id(), project,
                asset.mediaKind(), fieldKey, asset.width(), asset.height(), asset.durationMs(), asset.contentType());
    }

    private static String physicalRatio(String ratio) {
        if (VideoGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio))
            return VideoGenerationParameters.LANDSCAPE_ASPECT_RATIO;
        if (!RATIOS.contains(ratio))
            throw invalid();
        return ratio;
    }

    static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.video-studio.title"),
                ApiMessage.of("api.video-studio.invalid-request"), false);
    }

    record Prepared(MediaCapabilityBinding binding, DirectMediaTaskService.StudioPreparation compiled, String ratio,
            VideoStudioService.Frame start, VideoStudioService.Frame end,
            List<VideoStudioService.ReferenceAsset> references) {
    }
}
