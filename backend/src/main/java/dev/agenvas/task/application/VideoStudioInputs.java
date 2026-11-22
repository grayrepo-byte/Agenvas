package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft.InputRole;
import dev.agenvas.artifact.domain.MediaDraft.VideoInputMode;
import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.Task;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Resolves video Studio inputs without inheriting a CanvasItem, project format, or mutable asset selection. */
@Component
class VideoStudioInputs {
    private static final Set<String> ADAPTERS = Set.of("MOCK_VIDEO", MediaAdapterRegistry.SEEDANCE_2);
    private static final List<String> RATIOS = List.of("16:9", "9:16", "1:1");
    private final ProjectService projects;
    private final MediaCapabilityService capabilities;
    private final AssetService assets;
    private final ArtifactService artifacts;
    private final ProjectEventService events;
    private final ObjectMapper mapper;

    VideoStudioInputs(ProjectService projects, MediaCapabilityService capabilities, AssetService assets,
            ArtifactService artifacts, ProjectEventService events, ObjectMapper mapper) {
        this.projects = projects;
        this.capabilities = capabilities;
        this.assets = assets;
        this.artifacts = artifacts;
        this.events = events;
        this.mapper = mapper;
    }

    VideoStudioService.Context context(UUID owner) {
        UUID project = projects.ensureVideoStudioScope(owner).id();
        // A duration-filtered catalog (including duration 0) would omit otherwise valid models.
        var catalog = capabilities.publishedCandidates().stream()
                .filter(candidate -> ADAPTERS.contains(candidate.binding().adapterId()))
                .map(candidate -> capability(candidate.binding(), candidate.capabilityName(), candidate.connectionName(),
                        candidate.settings())).toList();
        UUID preferred = capabilities.defaultCapabilityId(Task.Kind.VIDEO_GENERATION);
        UUID selected = catalog.stream().map(VideoStudioService.Capability::capabilityId)
                .filter(id -> id.equals(preferred)).findFirst()
                .orElseGet(() -> catalog.isEmpty() ? null : catalog.getFirst().capabilityId());
        return new VideoStudioService.Context(project, selected, catalog);
    }

    private VideoStudioService.Capability capability(MediaCapabilityBinding binding, String name,
            String connectionName, JsonNode settings) {
        var policy = capabilities.inputPolicy(binding);
        var modes = new ArrayList<VideoInputMode>(2);
        if (policy.supportedVideoInputModes().contains(VideoInputMode.TEXT.name()))
            modes.add(VideoInputMode.TEXT);
        if (policy.maxReferenceImages() >= 1
                && policy.supportedVideoInputModes().contains(VideoInputMode.START_END.name()))
            modes.add(VideoInputMode.START_END);
        if (modes.isEmpty())
            throw invalid();
        VideoInputMode defaultMode = modes.stream().filter(mode -> mode.name().equals(policy.defaultVideoInputMode()))
                .findFirst().orElse(modes.getFirst());
        int seconds = settings.path("defaultDurationSeconds").asInt(policy.minimumSeconds());
        String ratio = settings.path("defaultParameters").path("aspectRatio").asText("AUTO");
        return new VideoStudioService.Capability(binding.capabilityId(), binding.capabilityVersion(), name,
                connectionName, binding.adapterId(), List.copyOf(modes), defaultMode,
                modes.contains(VideoInputMode.START_END) && policy.supportsEndFrame() && policy.maxReferenceImages() >= 2,
                policy.minimumSeconds(), policy.maximumSeconds(), seconds, RATIOS, physicalRatio(ratio));
    }

    Prepared prepare(UUID owner, UUID project, VideoStudioService.Command command) {
        MediaCapabilityBinding binding = capabilities.forDraft(command.capabilityId(), Task.Kind.VIDEO_GENERATION);
        if (binding.capabilityVersion() != command.capabilityVersion())
            throw VideoStudioService.conflict("MEDIA_CAPABILITY_CHANGED");
        if (!ADAPTERS.contains(binding.adapterId()))
            throw invalid();
        JsonNode settings = capabilities.settings(binding);
        var controls = capability(binding, null, null, settings);
        if (!controls.modes().contains(command.mode()) || command.durationSeconds() < controls.minDurationSeconds()
                || command.durationSeconds() > controls.maxDurationSeconds())
            throw invalid();
        var start = command.startFrameVersionId();
        var end = command.endFrameVersionId();
        if (command.mode() == VideoInputMode.TEXT ? start != null || end != null
                : start == null || end != null && !controls.supportsEndFrame())
            throw invalid();
        String ratio = command.aspectRatio() == null ? controls.defaultAspectRatio() : physicalRatio(command.aspectRatio());
        return new Prepared(binding, settings, ratio, start == null ? null : frame(owner, project, start),
                end == null ? null : frame(owner, project, end));
    }

    VideoStudioService.Frame upload(UUID owner, InputStream input) {
        UUID project = projects.ensureVideoStudioScope(owner).id();
        // Decode and install outside the DB transaction; rejected uploads leave no registered asset or artifact.
        return assets.withPreparedStaticImageUpload(owner, project, input, asset ->
            events.recordChange(owner, project, () -> {
                assets.registerLibraryImport(owner, asset);
                var artifact = artifacts.create(owner, project, Artifact.Kind.IMAGE, "Video Studio frame",
                        mapper.createObjectNode().put("sourceType", ArtifactVersion.MediaSourceType.UPLOAD.name())
                                .put("assetId", asset.id().toString()));
                return ProjectEventService.Change.unchanged(toFrame(project, artifact.resourceDefaultVersion(), asset));
            }).value());
    }

    VideoStudioService.Frame frame(UUID owner, UUID project, UUID versionId) {
        ArtifactVersion version = artifacts.requireImageVersionForTask(owner, project, versionId);
        if (artifacts.get(owner, project, version.artifactId()).artifact().archivedAt() != null)
            throw invalid();
        Asset asset = assets.requireReadyMedia(owner, project, UUID.fromString(version.content().path("assetId").asText()),
                Asset.MediaKind.IMAGE);
        ImageGenerationPreparation.requireStaticImage(asset);
        return toFrame(project, version, asset);
    }

    private static VideoStudioService.Frame toFrame(UUID project, ArtifactVersion version, Asset asset) {
        return new VideoStudioService.Frame(version.artifactId(), version.id(), asset.id(), project, asset.width(),
                asset.height());
    }

    private static String physicalRatio(String ratio) {
        if (VideoGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio))
            return VideoGenerationParameters.LANDSCAPE_ASPECT_RATIO;
        if (!RATIOS.contains(ratio))
            throw invalid();
        return ratio;
    }

    static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.video-studio.title"),
                ApiMessage.of("api.video-studio.invalid-request"), false);
    }

    record Prepared(MediaCapabilityBinding binding, JsonNode settings, String ratio, VideoStudioService.Frame start,
            VideoStudioService.Frame end) {
        void writeFrames(tools.jackson.databind.node.ArrayNode images) {
            if (start != null)
                write(images, start, InputRole.START_FRAME);
            if (end != null)
                write(images, end, InputRole.END_FRAME);
        }

        private static void write(tools.jackson.databind.node.ArrayNode images, VideoStudioService.Frame frame,
                InputRole role) {
            int order = images.size();
            images.addObject().put("artifactId", frame.artifactId().toString()).put("versionId", frame.versionId().toString())
                    .put("assetId", frame.assetId().toString()).put("role", role.name()).put("order", order);
        }
    }
}
