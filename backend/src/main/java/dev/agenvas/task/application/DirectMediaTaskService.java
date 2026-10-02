package dev.agenvas.task.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.AudioGenerationParameters;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.provider.application.ProviderProperties;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.usage.application.UsageService;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Accepts a user's saved media draft as one immutable Task per requested media output. */
@Service
public class DirectMediaTaskService {
    private static final int MAX_COMMAND_KEY_LENGTH = 160;
    private static final int MIN_RELIGHT_BRIGHTNESS = -100;
    private static final int MAX_RELIGHT_BRIGHTNESS = 100;
    private static final int MIN_RELIGHT_COLOR_TEMPERATURE = 2000;
    private static final int MAX_RELIGHT_COLOR_TEMPERATURE = 10000;
    private static final long MAX_OPENAI_MASK_BYTES = 4L * 1024 * 1024;
    private static final Set<String> RELIGHT_PRESETS = Set.of(
            "GOLDEN_HOUR", "BLUE_HOUR", "OVERCAST_SOFT", "MOONLIGHT",
            "SOFT_STUDIO", "NEON_NIGHT");
    private static final Map<String, String> LAYER_RESULT_LABELS = Map.of(
            "FOREGROUND", "主体图层", "BACKGROUND", "背景图层");
    private static final Map<String, String> THREE_VIEW_RESULT_LABELS = Map.of(
            "CHARACTER", "角色三视图", "FACE", "脸部三视图",
            "PROP", "道具三视图", "SCENE_GRID", "场景宫格图");
    private static final Set<String> VIEW_ANGLES = Set.of(
            "FRONT", "LEFT_THREE_QUARTER", "RIGHT_THREE_QUARTER", "LEFT_PROFILE",
            "RIGHT_PROFILE", "HIGH_ANGLE", "LOW_ANGLE", "BACK");
    private static final String COST_SOURCE = "PROVIDER_UNPRICED";
    // The saved prompt stays structural; only the immutable Task input sent to providers is rendered.
    private static final char MENTION_MARKER = '\uFFFC';
    public static final UUID LOCAL_IMAGE_CAPABILITY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000202");

    private final TaskRepository tasks;
    private final MediaDraftService drafts;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final CanvasItemQueryService canvasItems;
    private final CanvasService canvas;
    private final MediaCapabilityService capabilities;
    private final ProviderProperties provider;
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ProjectService projects;

    public DirectMediaTaskService(TaskRepository tasks, MediaDraftService drafts,
            ArtifactService artifacts, AssetService assets, CanvasItemQueryService canvasItems,
            CanvasService canvas,
            MediaCapabilityService capabilities,
            ProviderProperties provider, ProjectEventService events, UsageService usage,
            ObjectMapper mapper, Clock clock, ProjectService projects) {
        this.tasks = tasks;
        this.drafts = drafts;
        this.artifacts = artifacts;
        this.assets = assets;
        this.canvasItems = canvasItems;
        this.canvas = canvas;
        this.capabilities = capabilities;
        this.provider = provider;
        this.events = events;
        this.usage = usage;
        this.mapper = mapper;
        this.clock = clock;
        this.projects = projects;
    }

    @Transactional
    public Task run(UUID ownerId, UUID projectId, UUID artifactId, UUID canvasItemId,
            long expectedDraftVersion, String commandKey) {
        if (canvasItemId == null || commandKey == null || commandKey.isBlank()
                || commandKey.length() > MAX_COMMAND_KEY_LENGTH || expectedDraftVersion < 0) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.requires-a-valid-idempotency-key-and-draft-version"));
        }
        return events.recordChange(ownerId, projectId, () -> {
            // The project event row lock serializes acceptance with all other card commands.
            Task prior = tasks.findDirectByStepKey(ownerId, projectId, commandKey).orElse(null);
            if (prior != null) {
                String priorSourceCanvasItemId = prior.input().path("sourceCanvasItemId")
                        .asText(prior.input().path("canvasItemId").asText());
                if (!prior.input().path("artifactId").asText().equals(artifactId.toString())
                        || !priorSourceCanvasItemId.equals(canvasItemId.toString())
                        || prior.input().path("draftVersion").asLong(-1) != expectedDraftVersion) {
                    throw conflict(ApiMessage.of("api.direct-media-task-service.the-same-idempotent-key-has-been-used-in-different-card"));
                }
                return ProjectEventService.Change.unchanged(prior);
            }
            Artifact target = artifacts.get(ownerId, projectId, artifactId).artifact();
            CanvasItem canvasItem = canvasItems.requireArtifactItem(ownerId, projectId,
                    canvasItemId);
            if (!canvasItem.subjectId().equals(artifactId)) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.the-run-target-must-be-the-canvas-card-of-the"));
            }
            Task occupying = tasks.findOccupyingDirectMediaTask(projectId, canvasItemId)
                    .orElse(null);
            if (occupying != null) return ProjectEventService.Change.unchanged(occupying);
            if (target.archivedAt() != null) throw conflict(ApiMessage.of("api.direct-media-task-service.archived-cards-cannot-be-run"));
            Task.Kind kind = switch (target.kind()) {
                case IMAGE -> Task.Kind.IMAGE_GENERATION;
                case VIDEO -> Task.Kind.VIDEO_GENERATION;
                case AUDIO -> Task.Kind.AUDIO_GENERATION;
                default -> throw invalid(ApiMessage.of("api.direct-media-task-service.only-picture-or-video-cards-can-be-run-directly"));
            };
            MediaDraft draft = drafts.get(ownerId, projectId, canvasItem.id());
            if (draft.version() != expectedDraftVersion) throw conflict(ApiMessage.of("api.direct-media-task-service.the-draft-has-changed-please-check-the-save-status-and"));
            MediaCapabilityBinding selected = capabilities.forDraft(draft.capabilityId(), kind);
            var definition = capabilities.runningHubDefinition(selected);
            boolean dynamic = definition != null;
            if (!dynamic && draft.prompt().isBlank()) throw invalid(ApiMessage.of("api.direct-media-task-service.prompt-words-need-to-be-filled-in-before-running"));
            if (!dynamic && kind == Task.Kind.VIDEO_GENERATION
                    && draft.videoInputMode() == MediaDraft.VideoInputMode.START_END
                    && (draft.mediaInputs().isEmpty()
                            || draft.mediaInputs().getFirst().role()
                                    != MediaDraft.InputRole.START_FRAME)) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.before-running-the-first-and-last-frame-video-you-need"));
            }
            if (!dynamic && kind == Task.Kind.VIDEO_GENERATION
                    && draft.videoInputMode() == MediaDraft.VideoInputMode.GENERAL_REFERENCE
                    && draft.mediaInputs().isEmpty()) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.the-all-in-one-reference-video-requires-at-least-one"));
            }
            JsonNode configuredSettings = capabilities.settings(selected);
            Integer duration = draft.durationSeconds();
            if (kind == Task.Kind.VIDEO_GENERATION && duration == null
                    && configuredSettings.has("defaultDurationSeconds")) {
                duration = configuredSettings.path("defaultDurationSeconds").intValue();
            }
            if (dynamic && duration == null) {
                var durationField = definition.fields().stream().filter(field -> field.effectiveSource() == dev.agenvas.provider.domain.RunningHubDefinition.Source.DURATION_SECONDS).findFirst().orElse(null);
                if (durationField != null && durationField.defaultValue() != null && !durationField.defaultValue().isNull()) duration = durationField.defaultValue().asInt();
            }
            if (!dynamic && kind == Task.Kind.VIDEO_GENERATION && duration == null) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.you-need-to-select-the-duration-before-running-the-video"));
            }
            int seconds = kind == Task.Kind.VIDEO_GENERATION && duration != null ? duration : 0;
            MediaCapabilityBinding binding = capabilities.resolve(selected.capabilityId(), kind, seconds);
            if (!selected.equals(binding)) throw conflict(ApiMessage.of("api.direct-media-task-service.the-media-configuration-has-changed-please-refresh-and-try-again"));
            if (MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(binding.adapterId())) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.local-image-processing-capabilities-can-only-be-used-from-the"));
            }
            ObjectNode dynamicParameters = null;
            if (dynamic) {
                dynamicParameters = mapper.createObjectNode();
                dynamicParameters.set(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY,
                        definition.values(mapper, draft.parameters(), renderPrompt(draft), duration, true));
                dev.agenvas.artifact.application.MediaDraftService.validateSlots(definition, dynamicParameters, draft.mediaInputs(), true);
            } else validateCapabilityInputs(kind, draft, capabilities.inputPolicy(binding), capabilities.parameters(binding, draft.parameters()));
            validateReferenceAssets(ownerId, projectId, draft, binding);
            ImageGenerationParameters imageParameters = !dynamic && kind == Task.Kind.IMAGE_GENERATION
                    ? ImageGenerationParameters.parse(capabilities.parameters(binding, draft.parameters())) : null;
            VideoGenerationParameters videoParameters = !dynamic && kind == Task.Kind.VIDEO_GENERATION
                    ? VideoGenerationParameters.parse(capabilities.parameters(binding, draft.parameters())) : null;
            if (imageParameters != null) {
                var policy = capabilities.inputPolicy(binding);
                imageParameters.requireSupported(policy.supportedImageAspectRatios(),
                        policy.supportedImageResolutions(), policy.supportedImageQualities(),
                        policy.supportsTransparentBackground());
            }
            String renderedPrompt = renderPrompt(draft);
            String autodlResolution = null;
            String resolutionTier = null;
            if (videoParameters != null && videoParameters.videoResolution() != null
                    && !AutoDlWorkflows.ADAPTER_ID.equals(binding.adapterId()))
                throw invalid(ApiMessage.of("api.auto-dl-workflows.this-workflow-does-not-support-this-resolution"));
            if (AutoDlWorkflows.ADAPTER_ID.equals(binding.adapterId())) {
                var workflow = AutoDlWorkflows.require(configuredSettings);
                int audios = (int) draft.mediaInputs().stream().filter(reference ->
                        reference.role() == MediaDraft.InputRole.AUDIO_REFERENCE).count();
                workflow.validate(renderedPrompt, seconds, draft.videoInputMode().name(),
                        draft.mediaInputs().size() - audios, audios);
                if ("START_END".equals(workflow.mode()) && draft.mediaInputs().stream().noneMatch(reference ->
                        reference.role() == MediaDraft.InputRole.END_FRAME)) throw invalid(ApiMessage.of("api.direct-media-task-service.this-autodl-workflow-requires-first-and-last-frames"));
                String ratio = videoParameters.aspectRatio();
                if (VideoGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio)) {
                    ratio = switch (projects.get(ownerId, projectId).aspectRatio()) {
                        case LANDSCAPE_16_9 -> VideoGenerationParameters.LANDSCAPE_ASPECT_RATIO;
                        case PORTRAIT_9_16 -> VideoGenerationParameters.PORTRAIT_ASPECT_RATIO;
                        case SQUARE_1_1 -> VideoGenerationParameters.SQUARE_ASPECT_RATIO;
                    };
                }
                resolutionTier = AutoDlWorkflows.selectedResolution(configuredSettings, videoParameters.videoResolution());
                autodlResolution = workflow.resolution(resolutionTier, ratio);
            }
            String originHash = capabilities.capabilitySnapshot(binding.capabilityId())
                    .connectionVersion().originSha256();
            Instant now = clock.instant();
            int outputCount = imageParameters == null ? 1 : imageParameters.generationCount();
            Task primary = null;
            for (int outputIndex = 0; outputIndex < outputCount; outputIndex++) {
                // Regeneration adds a version to the current node. Additional batch outputs
                // keep independent nodes; only image edits create derivation relationships.
                CanvasItem outputCard = outputIndex == 0
                        ? canvasItem
                        : canvas.forkMediaOutputWithinChange(ownerId, projectId, canvasItemId,
                                UUID.randomUUID(), draft.version(), outputIndex);
                ObjectNode input = mapper.createObjectNode();
                input.put("schemaVersion", 3);
                if (dynamic) input.put("providerProtocol", "RUNNINGHUB_V2");
                input.put("artifactId", artifactId.toString());
                input.put("sourceCanvasItemId", canvasItemId.toString());
                input.put("canvasItemId", outputCard.id().toString());
                input.put("resultSelectionEpoch", canvas.mediaSelectionEpoch(ownerId,
                        projectId, outputCard.id()));
                input.put("resultDraftVersion", drafts.get(ownerId, projectId, outputCard.id()).version());
                if (outputCard.selectedVersionId() == null) input.putNull("parentVersionId");
                else input.put("parentVersionId", outputCard.selectedVersionId().toString());
                input.put("draftVersion", draft.version());
                input.put("generationIndex", outputIndex);
                input.put("generationCount", outputCount);
                input.put("prompt", renderedPrompt);
                JsonNode selectedPrice = dev.agenvas.provider.domain.MediaCapabilityConfiguration.price(configuredSettings, resolutionTier);
                if (selectedPrice != null) input.set("mediaPricing", selectedPrice);
                input.put("providerConfigVersion", provider.configVersion());
                input.put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
                if (originHash != null) input.put("providerOriginSha256", originHash);
                if (kind == Task.Kind.VIDEO_GENERATION && duration != null) input.put("durationSeconds", seconds);
                ObjectNode frozen = input.putObject("mediaInput");
                if (autodlResolution != null) {
                    ObjectNode providerParameters = frozen.putObject("providerParameters");
                    providerParameters.put("workflowId", configuredSettings.path("workflowId").asText());
                    providerParameters.put("resolution", autodlResolution);
                    if (configuredSettings.has("seed")) providerParameters.set("seed", configuredSettings.get("seed"));
                }
                if (outputCard.selectedVersionId() == null) frozen.putNull("parentVersionId");
                else frozen.put("parentVersionId", outputCard.selectedVersionId().toString());
                frozen.put("mode", kind == Task.Kind.IMAGE_GENERATION
                        ? MediaDraft.VideoInputMode.GENERAL_REFERENCE.name()
                        : kind == Task.Kind.AUDIO_GENERATION ? "TEXT" : draft.videoInputMode().name());
                if (dynamic) frozen.set("runningHubContract", mapper.valueToTree(definition));
                frozen.put("prompt", draft.prompt());
                frozen.put("renderedPrompt", renderedPrompt);
                frozen.set("parameters", dynamicParameters != null ? dynamicParameters : imageParameters != null
                        ? imageParameters.toJson(mapper) : videoParameters != null ? videoParameters.toJson(mapper)
                        : dev.agenvas.artifact.domain.AudioGenerationParameters.parse(
                                capabilities.parameters(binding, draft.parameters())).toJson(mapper));
                frozen.put("capabilityId", binding.capabilityId().toString());
                frozen.put("capabilityVersion", binding.capabilityVersion());
                if (kind == Task.Kind.VIDEO_GENERATION && duration != null) frozen.put("durationSeconds", seconds);
                frozen.putArray("images");
                frozen.putArray("audios");
                frozen.putArray("videos");
                for (MediaDraft.MediaInput imageInput : draft.mediaInputs()) {
                    ArtifactVersion image = artifacts.requireMediaVersionForTask(ownerId, projectId,
                            imageInput.versionId(), dev.agenvas.artifact.application.MediaDraftService.mediaKind(imageInput.role()));
                    String mediaArray = imageInput.role() == MediaDraft.InputRole.AUDIO_REFERENCE ? "audios"
                            : imageInput.role() == MediaDraft.InputRole.VIDEO_REFERENCE ? "videos" : "images";
                    ArrayNode group = (ArrayNode) frozen.get(mediaArray);
                    ObjectNode imageNode = group.addObject();
                    imageNode.put("artifactId", image.artifactId().toString());
                    imageNode.put("versionId", image.id().toString());
                    imageNode.put("role", imageInput.role().name());
                    imageNode.put("order", group.size() - 1);
                }
                frozen.set("mentions", mapper.valueToTree(draft.mentions()));
                String stepKey = outputIndex == 0 ? commandKey
                        : "image-batch:" + hash(commandKey).substring(0, 32) + ":" + outputIndex;
                Task task = new Task(UUID.randomUUID(), projectId, null, stepKey, kind,
                        Task.Status.READY, false, input, hash(input.toString()), null, null, null,
                        1, now, null, null, 0, 0, null, now, now, null);
                tasks.create(task, List.of());
                tasks.bindMediaTask(task.id(), binding);
                tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                        artifactId, outputCard.selectedVersionId(), target.version(), null,
                        outputCard.id()));
                drafts.setDisplayModeWithinChange(projectId, outputCard.id(),
                        MediaDraft.DisplayMode.DRAFT);
                usage.reserveMediaTask(ownerId, task, COST_SOURCE);
                ObjectNode payload = mapper.createObjectNode();
                payload.put("taskId", task.id().toString());
                payload.put("artifactId", artifactId.toString());
                payload.put("canvasItemId", outputCard.id().toString());
                payload.put("status", task.status().name());
                events.append(ownerId, projectId,
                        new ProjectEventService.EventDraft("task.status.changed", 1, task.id(),
                                task.version(), payload));
                if (primary == null) primary = task;
            }
            return ProjectEventService.Change.unchanged(java.util.Objects.requireNonNull(primary));
        }).value();
    }

    /** Accepts one image post-processing command while pinning the exact visible source version. */
    public Task runImageOperation(UUID ownerId, UUID projectId, UUID artifactId,
            UUID canvasItemId, UUID sourceVersionId, long expectedCanvasItemVersion,
            ImageOperation operation, String instruction, UUID capabilityId,
            List<UUID> referenceVersionIds, UUID maskAssetId, JsonNode requestedParameters,
            String commandKey) {
        if (canvasItemId == null || sourceVersionId == null || operation == null
                || expectedCanvasItemVersion < 0 || commandKey == null || commandKey.isBlank()
                || commandKey.length() > MAX_COMMAND_KEY_LENGTH) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.requires-a-valid-image-action-card-version-and-idempotency-key"));
        }
        ObjectNode operationParameters = normalizeOperationParameters(operation,
                requestedParameters);
        List<UUID> normalizedReferenceIds = referenceVersionIds == null
                ? List.of() : List.copyOf(referenceVersionIds);
        if (new HashSet<>(normalizedReferenceIds).size() != normalizedReferenceIds.size()) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.intelligent-editing-reference-pictures-cannot-be-repeated"));
        }
        if (operation != ImageOperation.SMART_EDIT
                && (!normalizedReferenceIds.isEmpty() || maskAssetId != null)) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.only-smart-editing-supports-additional-reference-images-and-masks"));
        }
        ArrayNode requestedReferenceIds = mapper.createArrayNode();
        normalizedReferenceIds.forEach(id -> requestedReferenceIds.add(id.toString()));
        String normalizedInstruction = instruction == null ? "" : instruction.trim();
        if (normalizedInstruction.length() > 4000) throw invalid(ApiMessage.of("api.direct-media-task-service.edit-description-cannot-exceed-4000-characters"));
        if (operation.instructionRequired() && normalizedInstruction.isBlank()) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.this-ai-image-processing-requires-filling-in-processing-instructions"));
        }
        if (operation.cloud() && capabilityId == null) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.ai-image-processing-requires-selecting-openai-or-google-image-capabilities"));
        }
        if (!operation.cloud() && capabilityId != null) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.local-image-processing-cannot-specify-cloud-image-capabilities"));
        }
        // Remote masks are materialized before the event transaction acquires project locks.
        // Immutable READY bytes allow the acceptance transaction to recheck only identity and capability.
        if (maskAssetId != null && tasks.findDirectByStepKey(ownerId, projectId, commandKey).isEmpty()) {
            validateImageMask(ownerId, projectId, maskAssetId);
        }
        UUID requestedCapabilityId = operation.cloud()
                ? capabilityId : LOCAL_IMAGE_CAPABILITY_ID;
        return events.recordChange(ownerId, projectId, () -> {
            Task prior = tasks.findDirectByStepKey(ownerId, projectId, commandKey).orElse(null);
            if (prior != null) {
                JsonNode saved = prior.input().path("imageOperation");
                if (!prior.input().path("artifactId").asText().equals(artifactId.toString())
                        || !prior.input().path("sourceCanvasItemId").asText()
                                .equals(canvasItemId.toString())
                        || !saved.path("sourceVersionId").asText()
                                .equals(sourceVersionId.toString())
                        || !saved.path("name").asText().equals(operation.name())
                        || !saved.path("capabilityId").asText()
                                .equals(requestedCapabilityId.toString())
                        || !saved.path("instruction").asText().equals(normalizedInstruction)
                        || !saved.path("referenceVersionIds").equals(requestedReferenceIds)
                        || !saved.path("maskAssetId").asText("")
                                .equals(maskAssetId == null ? "" : maskAssetId.toString())
                        || prior.input().path("sourceCanvasItemVersion").asLong(-1)
                                != expectedCanvasItemVersion
                        || !saved.path("parameters").equals(operationParameters)) {
                    throw conflict(ApiMessage.of("api.direct-media-task-service.the-same-idempotent-keys-have-been-used-for-different-image"));
                }
                return ProjectEventService.Change.unchanged(prior);
            }
            Artifact target = artifacts.get(ownerId, projectId, artifactId).artifact();
            if (target.kind() != Artifact.Kind.IMAGE || target.archivedAt() != null) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.image-processing-can-only-be-used-on-unarchived-image-cards"));
            }
            CanvasItem card = canvasItems.requireArtifactItem(ownerId, projectId, canvasItemId);
            if (!card.subjectId().equals(artifactId)
                    || !sourceVersionId.equals(card.selectedVersionId())) {
                throw conflict(ApiMessage.of("api.direct-media-task-service.the-current-image-of-the-card-has-changed-please-reprocess"));
            }
            if (card.version() != expectedCanvasItemVersion) {
                throw conflict(ApiMessage.of("api.direct-media-task-service.the-card-has-changed-please-refresh-and-try-again"));
            }
            ArtifactVersion source = artifacts.requireImageVersionForTask(ownerId, projectId,
                    sourceVersionId);
            if (!source.artifactId().equals(artifactId)) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.the-processing-source-must-be-the-version-of-the-image"));
            }
            MediaCapabilityBinding binding = operation.cloud()
                    ? cloudImageBinding(capabilityId) : capabilities.resolve(
                            LOCAL_IMAGE_CAPABILITY_ID, Task.Kind.IMAGE_GENERATION, 0);
            var inputPolicy = capabilities.inputPolicy(binding);
            if (1 + normalizedReferenceIds.size() > inputPolicy.maxReferenceImages()) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.the-selected-ai-image-capability-cannot-accept-this-many-reference"));
            }
            List<ArtifactVersion> referenceVersions = new ArrayList<>(
                    normalizedReferenceIds.size());
            for (UUID referenceVersionId : normalizedReferenceIds) {
                if (referenceVersionId.equals(sourceVersionId)) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.the-source-image-is-already-the-first-input-for-smart"));
                }
                referenceVersions.add(artifacts.requireImageVersionForTask(ownerId, projectId,
                        referenceVersionId));
            }
            if (maskAssetId != null) {
                if (!inputPolicy.supportsImageMask()) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.the-selected-ai-picture-capability-does-not-support-explicit-mask"));
                }
                assets.requireReadyMedia(ownerId, projectId, maskAssetId, Asset.MediaKind.IMAGE);
            }
            boolean transparentOutput = requiresTransparentOutput(operation,
                    operationParameters);
            if (transparentOutput && !inputPolicy.supportsTransparentBackground()) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.the-selected-ai-picture-capability-does-not-support-transparent-background"));
            }
            String prompt = operationPrompt(operation, normalizedInstruction,
                    operationParameters);
            if (!referenceVersions.isEmpty()) {
                prompt += " Image 1 is the source to edit. Images 2 through "
                        + (referenceVersions.size() + 1)
                        + " are additional visual references; use only the traits explicitly requested "
                        + "and keep unrelated source content unchanged.";
            }
            if (maskAssetId != null) {
                prompt += " Apply the requested change only in the transparent mask-guided area and "
                        + "preserve every unmasked pixel as closely as possible.";
            }
            MediaDraft sourceDraft = drafts.get(ownerId, projectId, canvasItemId);
            CanvasItem outputCard = canvas.forkMediaDerivationWithinChange(ownerId, projectId,
                    canvasItemId, UUID.randomUUID(), sourceDraft.version(), 0,
                    operationResultLabel(operation, operationParameters));
            Instant now = clock.instant();
            ObjectNode input = mapper.createObjectNode();
            input.put("schemaVersion", 6);
            input.put("artifactId", artifactId.toString());
            input.put("sourceCanvasItemId", canvasItemId.toString());
            input.put("sourceCanvasItemVersion", expectedCanvasItemVersion);
            input.put("canvasItemId", outputCard.id().toString());
            input.put("canvasItemVersion", outputCard.version());
            input.put("resultSelectionEpoch", canvas.mediaSelectionEpoch(ownerId,
                    projectId, outputCard.id()));
            input.put("resultDraftVersion", drafts.get(ownerId, projectId, outputCard.id()).version());
            input.put("parentVersionId", sourceVersionId.toString());
            input.put("prompt", prompt);
            JsonNode operationSettings = capabilities.settings(binding);
            if (operationSettings.has("pricing")
                    && !MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(binding.adapterId())) {
                input.set("mediaPricing", operationSettings.get("pricing"));
            }
            input.put("providerConfigVersion", provider.configVersion());
            input.put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
            String originHash = capabilities.capabilitySnapshot(binding.capabilityId())
                    .connectionVersion().originSha256();
            if (originHash != null) input.put("providerOriginSha256", originHash);
            ObjectNode frozenOperation = input.putObject("imageOperation");
            frozenOperation.put("name", operation.name());
            frozenOperation.put("sourceVersionId", sourceVersionId.toString());
            frozenOperation.put("capabilityId", requestedCapabilityId.toString());
            frozenOperation.put("instruction", normalizedInstruction);
            frozenOperation.set("referenceVersionIds", requestedReferenceIds.deepCopy());
            if (maskAssetId == null) frozenOperation.putNull("maskAssetId");
            else frozenOperation.put("maskAssetId", maskAssetId.toString());
            frozenOperation.set("parameters", operationParameters.deepCopy());
            ObjectNode frozen = input.putObject("mediaInput");
            frozen.put("parentVersionId", sourceVersionId.toString());
            frozen.put("mode", MediaDraft.VideoInputMode.GENERAL_REFERENCE.name());
            frozen.put("prompt", prompt);
            frozen.put("renderedPrompt", prompt);
            ObjectNode generationParameters = frozen.putObject("parameters");
            generationParameters.put("aspectRatio", operation == ImageOperation.OUTPAINT
                            || operation == ImageOperation.THREE_VIEW
                    ? operationParameters.path("aspectRatio").asText()
                    : sourceAspectRatio(ownerId, projectId, source));
            generationParameters.put("resolution", "1K");
            generationParameters.put("quality", "high");
            generationParameters.put("transparentBackground", transparentOutput);
            generationParameters.put("generationCount", 1);
            frozen.put("capabilityId", binding.capabilityId().toString());
            frozen.put("capabilityVersion", binding.capabilityVersion());
            ArrayNode images = frozen.putArray("images");
                frozen.putArray("audios");
            ObjectNode image = images.addObject();
            image.put("artifactId", artifactId.toString());
            image.put("versionId", sourceVersionId.toString());
            image.put("role", MediaDraft.InputRole.REFERENCE.name());
            image.put("order", 0);
            int referenceOrder = 1;
            for (ArtifactVersion reference : referenceVersions) {
                ObjectNode referenceImage = images.addObject();
                referenceImage.put("artifactId", reference.artifactId().toString());
                referenceImage.put("versionId", reference.id().toString());
                referenceImage.put("role", MediaDraft.InputRole.REFERENCE.name());
                referenceImage.put("order", referenceOrder++);
            }
            frozen.putArray("mentions");
            Task task = new Task(UUID.randomUUID(), projectId, null, commandKey,
                    Task.Kind.IMAGE_GENERATION, Task.Status.READY, false, input,
                    hash(input.toString()), null, null, null, 1, now, null, null, 0, 0,
                    null, now, now, null);
            tasks.create(task, List.of());
            tasks.bindMediaTask(task.id(), binding);
            tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                    artifactId, sourceVersionId, target.version(), null, outputCard.id()));
            usage.reserveMediaTask(ownerId, task,
                    operation.cloud() ? COST_SOURCE : "LOCAL_NO_COST");
            ObjectNode payload = mapper.createObjectNode();
            payload.put("taskId", task.id().toString());
            payload.put("artifactId", artifactId.toString());
            payload.put("canvasItemId", outputCard.id().toString());
            payload.put("sourceCanvasItemId", canvasItemId.toString());
            payload.put("status", task.status().name());
            payload.put("imageOperation", operation.name());
            events.append(ownerId, projectId, new ProjectEventService.EventDraft(
                    "task.status.changed", 1, task.id(), task.version(), payload));
            return ProjectEventService.Change.unchanged(task);
        }).value();
    }

    private MediaCapabilityBinding cloudImageBinding(UUID capabilityId) {
        if (capabilityId == null) throw invalid(ApiMessage.of("api.direct-media-task-service.ai-image-processing-requires-selecting-openai-or-google-image-capabilities"));
        MediaCapabilityBinding binding = capabilities.resolve(capabilityId,
                Task.Kind.IMAGE_GENERATION, 0);
        if (!MediaAdapterRegistry.OPENAI_GPT_IMAGE_2.equals(binding.adapterId())
                && !MediaAdapterRegistry.GOOGLE_NANO_BANANA_2.equals(binding.adapterId())) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.ai-image-processing-only-supports-openai-or-google-image-capabilities"));
        }
        if (capabilities.inputPolicy(binding).maxReferenceImages() < 1) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.the-selected-ai-picture-capability-does-not-support-reference-picture"));
        }
        return binding;
    }

    ObjectNode normalizeOperationParameters(ImageOperation operation, JsonNode supplied) {
        JsonNode source = supplied == null || supplied.isNull()
                ? mapper.createObjectNode() : supplied;
        if (!source.isObject()) throw invalid(ApiMessage.of("api.direct-media-task-service.image-processing-parameters-must-be-objects"));
        ObjectNode result = mapper.createObjectNode();
        switch (operation) {
            case DEPTH_MAP, SMART_EDIT, EXPRESSION_EDIT,
                    REMOVE_BACKGROUND, OBJECT_REMOVE, FLIP_HORIZONTAL, FLIP_VERTICAL -> { }
            case RELIGHT -> {
                String preset = source.path("lightingPreset").asText("GOLDEN_HOUR");
                int brightness = source.path("brightness").asInt(10);
                int colorTemperature = source.path("colorTemperature").asInt(3200);
                double lightX = source.path("lightX").asDouble(0.15);
                double lightY = source.path("lightY").asDouble(0.75);
                if (!RELIGHT_PRESETS.contains(preset)) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.lighting-presets-are-not-supported"));
                }
                if (brightness < MIN_RELIGHT_BRIGHTNESS
                        || brightness > MAX_RELIGHT_BRIGHTNESS) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.the-lighting-brightness-must-be-between-100-and-100"));
                }
                if (colorTemperature < MIN_RELIGHT_COLOR_TEMPERATURE
                        || colorTemperature > MAX_RELIGHT_COLOR_TEMPERATURE) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.color-temperature-must-be-between-2000k-and-10000k"));
                }
                if (lightX < 0 || lightX > 1 || lightY < 0 || lightY > 1) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.the-light-source-position-must-be-within-the-image-range"));
                }
                result.put("lightingPreset", preset);
                result.put("brightness", brightness);
                result.put("colorTemperature", colorTemperature);
                result.put("lightX", lightX);
                result.put("lightY", lightY);
            }
            case UPSCALE -> {
                int scale = source.path("scale").asInt(2);
                if (scale != 2 && scale != 4) throw invalid(ApiMessage.of("api.direct-media-task-service.magnification-can-only-be-2-or-4"));
                result.put("scale", scale);
            }
            case CROP -> {
                double x = source.path("x").asDouble(0);
                double y = source.path("y").asDouble(0);
                double width = source.path("width").asDouble(1);
                double height = source.path("height").asDouble(1);
                if (x < 0 || y < 0 || width <= 0 || height <= 0
                        || x + width > 1.000001 || y + height > 1.000001) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.the-cropped-area-must-be-within-the-image"));
                }
                result.put("x", x); result.put("y", y);
                result.put("width", width); result.put("height", height);
            }
            case ROTATE -> {
                int turns = source.path("quarterTurns").asInt(1);
                if (turns < 1 || turns > 3) throw invalid(ApiMessage.of("api.direct-media-task-service.rotation-only-supports-90-180-or-270-degrees"));
                result.put("quarterTurns", turns);
            }
            case OUTPAINT -> {
                String ratio = source.path("aspectRatio").asText("");
                if (!ImageGenerationParameters.ASPECT_RATIOS.contains(ratio)
                        || ImageGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio)) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.expanding-images-requires-choosing-a-clear-target-frame"));
                }
                result.put("aspectRatio", ratio);
            }
            case THREE_VIEW -> {
                String ratio = source.path("aspectRatio").asText("");
                if (!ImageGenerationParameters.ASPECT_RATIOS.contains(ratio)
                        || ImageGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio)) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.three-views-require-a-clear-output-frame-to-be-selected"));
                }
                String type = source.path("threeViewType").asText("");
                if (!THREE_VIEW_RESULT_LABELS.containsKey(type)) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.three-view-types-are-not-supported"));
                }
                result.put("aspectRatio", ratio);
                result.put("threeViewType", type);
            }
            case LAYER_SPLIT -> {
                String target = source.path("layerTarget").asText("FOREGROUND");
                if (!LAYER_RESULT_LABELS.containsKey(target)) throw invalid(ApiMessage.of("api.direct-media-task-service.layer-output-type-is-not-supported"));
                result.put("layerTarget", target);
            }
            case VIEW_ANGLE -> {
                String angle = source.path("viewAngle").asText("FRONT");
                if (!VIEW_ANGLES.contains(angle)) throw invalid(ApiMessage.of("api.direct-media-task-service.target-perspective-is-not-supported"));
                result.put("viewAngle", angle);
            }
        }
        return result;
    }

    /** Parameters have already been normalized and validated before naming the result node. */
    String operationResultLabel(ImageOperation operation, ObjectNode parameters) {
        return switch (operation) {
            case THREE_VIEW -> THREE_VIEW_RESULT_LABELS.get(parameters.path("threeViewType").asText());
            case LAYER_SPLIT -> LAYER_RESULT_LABELS.get(parameters.path("layerTarget").asText());
            default -> operation.resultLabel();
        };
    }

    String operationPrompt(ImageOperation operation, String instruction,
            ObjectNode parameters) {
        return switch (operation) {
            case SMART_EDIT -> "Edit the provided image according to this instruction. Preserve all "
                    + "unmentioned subjects, identity, composition, and visual style. Instruction: "
                    + instruction;
            case RELIGHT -> "Relight the provided image realistically while preserving subject identity, "
                    + "geometry, materials, composition, and camera view. Use a "
                    + relightPresetPrompt(parameters.path("lightingPreset").asText())
                    + " lighting style, brightness adjustment "
                    + parameters.path("brightness").asInt() + " on a -100 to 100 scale, color "
                    + "temperature " + parameters.path("colorTemperature").asInt()
                    + "K, and a normalized light source position of ("
                    + parameters.path("lightX").asDouble() + ", "
                    + parameters.path("lightY").asDouble()
                    + "), where (0, 0) is top-left and (1, 1) is bottom-right."
                    + (instruction.isBlank() ? "" : " Additional lighting direction: " + instruction);
            case OUTPAINT -> "Extend the provided image naturally to "
                    + parameters.path("aspectRatio").asText() + ". Preserve the original image exactly "
                    + "inside the expanded canvas and continue its scene, perspective, lighting, and style."
                    + (instruction.isBlank() ? "" : " Additional instruction: " + instruction);
            case THREE_VIEW -> threeViewPrompt(parameters.path("threeViewType").asText())
                    + (instruction.isBlank() ? "" : " Subject guidance: " + instruction);
            case LAYER_SPLIT -> "FOREGROUND".equals(parameters.path("layerTarget").asText())
                    ? "Extract the primary foreground subject from the provided image as a clean isolated "
                            + "layer on a fully transparent background. Preserve fine edges, hair, materials, "
                            + "colors, and all subject details."
                            + (instruction.isBlank() ? "" : " Subject guidance: " + instruction)
                    : "Reconstruct a clean background layer from the provided image with all foreground "
                            + "subjects removed. Fill occluded regions seamlessly while preserving the scene, "
                            + "perspective, lighting, and visual style."
                            + (instruction.isBlank() ? "" : " Background guidance: " + instruction);
            case EXPRESSION_EDIT -> "Change only the subject's facial expression according to the instruction. "
                    + "Preserve identity, face shape, hair, pose, clothing, composition, lighting, and style. "
                    + "Instruction: " + instruction;
            case REMOVE_BACKGROUND -> "Remove the entire background from the provided image and return the "
                    + "primary subject on a fully transparent background. Preserve fine edges, hair, shadows "
                    + "belonging to the subject, original colors, and full subject detail."
                    + (instruction.isBlank() ? "" : " Subject guidance: " + instruction);
            case OBJECT_REMOVE -> "Remove only the described object or region from the provided image and "
                    + "reconstruct the newly exposed background seamlessly. Preserve all other pixels, "
                    + "subjects, perspective, lighting, and style. Target: " + instruction;
            case VIEW_ANGLE -> "Re-render the same subject from "
                    + viewAnglePrompt(parameters.path("viewAngle").asText())
                    + " while preserving identity, proportions, clothing, materials, environment, lighting, "
                    + "and visual style."
                    + (instruction.isBlank() ? "" : " Additional guidance: " + instruction);
            case DEPTH_MAP -> "Local monocular depth map";
            case UPSCALE -> "Local " + parameters.path("scale").asInt() + "x upscale";
            case CROP -> "Local crop";
            case ROTATE -> "Local rotation";
            case FLIP_HORIZONTAL -> "Local horizontal mirror";
            case FLIP_VERTICAL -> "Local vertical mirror";
        };
    }

    boolean requiresTransparentOutput(ImageOperation operation, ObjectNode parameters) {
        return operation == ImageOperation.REMOVE_BACKGROUND
                || operation == ImageOperation.LAYER_SPLIT
                        && "FOREGROUND".equals(parameters.path("layerTarget").asText());
    }

    private String viewAnglePrompt(String angle) {
        return switch (angle) {
            case "FRONT" -> "a straight-on front view";
            case "LEFT_THREE_QUARTER" -> "a left three-quarter view";
            case "RIGHT_THREE_QUARTER" -> "a right three-quarter view";
            case "LEFT_PROFILE" -> "a left profile view";
            case "RIGHT_PROFILE" -> "a right profile view";
            case "HIGH_ANGLE" -> "a high-angle view looking downward";
            case "LOW_ANGLE" -> "a low-angle view looking upward";
            case "BACK" -> "a straight-on back view";
            default -> throw invalid(ApiMessage.of("api.direct-media-task-service.target-perspective-is-not-supported"));
        };
    }

    private String threeViewPrompt(String type) {
        return switch (type) {
            case "CHARACTER" -> "Create one clean professional full-body character turnaround sheet from "
                    + "the provided image. Show the same character at equal scale in straight front, exact "
                    + "side profile, and straight back orthographic views. Keep a neutral standing pose and "
                    + "preserve identity, body proportions, hairstyle, clothing construction, accessories, "
                    + "materials, and colors. Use a simple neutral background, even lighting, clear separation "
                    + "between views, and no labels or unrelated objects.";
            case "FACE" -> "Create one clean professional facial turnaround sheet from the provided image. "
                    + "Show the same head and shoulders at equal scale in straight front, three-quarter, and "
                    + "exact side profile views. Preserve facial identity, skull and face proportions, skin "
                    + "tone, hairstyle, makeup, expression, and accessories. Use a simple neutral background, "
                    + "even lighting, aligned eye level, clear separation between views, and no labels.";
            case "PROP" -> "Create one clean professional prop turnaround sheet from the provided image. "
                    + "Show the exact same object at equal scale in straight front, exact side, and straight "
                    + "back orthographic views. Preserve geometry, construction, materials, textures, colors, "
                    + "wear, and functional details. Use a simple neutral background, even lighting, clear "
                    + "separation between views, and do not add hands, people, labels, or unrelated objects.";
            case "SCENE_GRID" -> "Create one coherent 2 by 2 environment reference grid from the provided "
                    + "scene. The four panels must show the same location as a wide establishing view, a "
                    + "reverse view, a medium view, and a key-detail view. Preserve the spatial layout, "
                    + "architecture, landmarks, materials, colors, time of day, weather, and lighting across "
                    + "all panels. Use clean equal gutters and do not add labels, characters, or unrelated "
                    + "objects unless they are already essential to the source scene.";
            default -> throw invalid(ApiMessage.of("api.direct-media-task-service.three-view-types-are-not-supported"));
        };
    }

    /** A provider mask is an immutable, project-scoped PNG with a real alpha channel. */
    private void validateImageMask(UUID ownerId, UUID projectId, UUID maskAssetId) {
        AssetService.AssetFile file = assets.get(ownerId, projectId, maskAssetId);
        Asset mask = file.asset();
        if (mask.mediaKind() != Asset.MediaKind.IMAGE
                || !"image/png".equals(mask.contentType())
                || mask.byteSize() < 1 || mask.byteSize() > MAX_OPENAI_MASK_BYTES) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.smart-editing-masks-must-be-png-images-smaller-than-4"));
        }
        try {
            BufferedImage decoded = ImageIO.read(file.path().toFile());
            if (decoded == null || !decoded.getColorModel().hasAlpha()) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.smart-editing-masks-must-contain-a-transparency-channel"));
            }
        } catch (IOException unreadable) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.smart-editing-masks-cannot-be-read"));
        }
    }

    private String relightPresetPrompt(String preset) {
        return switch (preset) {
            case "GOLDEN_HOUR" -> "warm golden-hour";
            case "BLUE_HOUR" -> "cool blue-hour";
            case "OVERCAST_SOFT" -> "soft overcast daylight";
            case "MOONLIGHT" -> "cool moonlight";
            case "SOFT_STUDIO" -> "soft studio";
            case "NEON_NIGHT" -> "colorful neon-night";
            default -> throw invalid(ApiMessage.of("api.direct-media-task-service.lighting-presets-are-not-supported"));
        };
    }

    private String sourceAspectRatio(UUID ownerId, UUID projectId, ArtifactVersion source) {
        UUID assetId = UUID.fromString(source.content().path("assetId").asText());
        Asset asset = assets.requireReadyMedia(ownerId, projectId, assetId,
                Asset.MediaKind.IMAGE);
        double actual = (double) asset.width() / asset.height();
        return ImageGenerationParameters.ASPECT_RATIOS.stream()
                .filter(ratio -> !ImageGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio))
                .min(java.util.Comparator.comparingDouble(ratio -> {
                    String[] parts = ratio.split(":", 2);
                    double candidate = Double.parseDouble(parts[0])
                            / Double.parseDouble(parts[1]);
                    return Math.abs(Math.log(actual / candidate));
                })).orElse(ImageGenerationParameters.AUTO_ASPECT_RATIO);
    }

    private String renderPrompt(MediaDraft draft) {
        StringBuilder rendered = new StringBuilder(draft.prompt().length());
        int mentionIndex = 0;
        for (int index = 0; index < draft.prompt().length(); index++) {
            char character = draft.prompt().charAt(index);
            if (character != MENTION_MARKER) {
                rendered.append(character);
                continue;
            }
            if (mentionIndex >= draft.mentions().size()) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.the-word-picture-label-is-damaged-please-save-the-draft"));
            }
            MediaDraft.PromptMention mention = draft.mentions().get(mentionIndex++);
            rendered.append('@').append(switch (mention.role()) {
                case START_FRAME -> "Start Frame";
                case END_FRAME -> "End Frame";
                case REFERENCE -> "Image " + referenceNumber(draft, mention);
                case VIDEO_REFERENCE -> "Video " + referenceNumber(draft, mention);
                case AUDIO_REFERENCE -> (draft.videoInputMode() == null ? "音频" : "Audio ") + referenceNumber(draft, mention);
            });
        }
        if (mentionIndex != draft.mentions().size()) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.the-word-picture-label-is-damaged-please-save-the-draft"));
        }
        return rendered.toString();
    }

    private int referenceNumber(MediaDraft draft, MediaDraft.PromptMention mention) {
        int number = 0;
        for (MediaDraft.MediaInput input : draft.mediaInputs()) {
            if (input.role() == mention.role()) number++;
            if (input.versionId().equals(mention.versionId()) && input.role() == mention.role()) {
                return number;
            }
        }
        throw invalid(ApiMessage.of("api.direct-media-task-service.there-is-no-corresponding-image-input-for-the-prompt-word"));
    }

    /** Only queued direct work is guaranteed never to have reached the provider. */
    @Transactional
    public Task cancelQueued(UUID ownerId, UUID projectId, UUID taskId) {
        return events.recordChange(ownerId, projectId, () -> {
            Task current = tasks.find(ownerId, projectId, taskId)
                    .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                            "RESOURCE_NOT_FOUND", ApiMessage.of("api.read-tool-service.task-does-not-exist"), ApiMessage.of("api.direct-media-task-service.the-task-cannot-be-found"), false));
            if (current.runId() != null) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.direct-media-tasks-can-only-be-canceled-via-this-portal"));
            }
            if (current.status() == Task.Status.CANCELED) {
                return ProjectEventService.Change.unchanged(current);
            }
            if (!tasks.cancelQueuedDirect(projectId, taskId, clock.instant())) {
                throw conflict(ApiMessage.of("api.direct-media-task-service.the-task-submission-has-started-please-check-the-cancellation-option"));
            }
            Task canceled = tasks.find(ownerId, projectId, taskId).orElseThrow();
            usage.releaseUnsubmittedMediaTask(ownerId, canceled);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("taskId", taskId.toString());
            payload.put("status", canceled.status().name());
            events.append(ownerId, projectId,
                    new ProjectEventService.EventDraft("task.status.changed", 1,
                            taskId, canceled.version(), payload));
            return ProjectEventService.Change.unchanged(canceled);
        }).value();
    }

    @Transactional(readOnly = true)
    public List<Task> list(UUID ownerId, UUID projectId, UUID artifactId, UUID canvasItemId) {
        artifacts.get(ownerId, projectId, artifactId);
        CanvasItem canvasItem = canvasItems.requireArtifactItem(ownerId, projectId, canvasItemId);
        if (!canvasItem.subjectId().equals(artifactId)) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.the-task-list-must-belong-to-the-canvas-card-of"));
        }
        return tasks.listDirectForCanvasItem(ownerId, projectId, canvasItemId);
    }

    @Transactional(readOnly = true)
    public TaskRepository.QueueStatus queueStatus(UUID ownerId, UUID projectId, UUID taskId) {
        Task task = tasks.find(ownerId, projectId, taskId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND", ApiMessage.of("api.read-tool-service.task-does-not-exist"), ApiMessage.of("api.direct-media-task-service.the-task-cannot-be-found"), false));
        if (task.runId() != null) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.this-task-is-not-a-direct-media-task"));
        }
        return tasks.queueStatus(taskId);
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Checks library reference reuse with the same protocol rules as direct generation, without a task or provider call. */
    public void validateLibraryReference(UUID owner, UUID project, Artifact.Kind artifactKind, MediaDraft draft) {
        Task.Kind kind = switch (artifactKind) {
            case IMAGE -> Task.Kind.IMAGE_GENERATION;
            case VIDEO -> Task.Kind.VIDEO_GENERATION;
            case AUDIO -> Task.Kind.AUDIO_GENERATION;
            default -> throw invalid(ApiMessage.of("api.direct-media-task-service.media-references-cannot-be-added-to-text-nodes"));
        };
        MediaCapabilityBinding binding = capabilities.forDraft(draft.capabilityId(), kind);
        validateCapabilityInputs(kind, draft, capabilities.inputPolicy(binding), capabilities.parameters(binding, draft.parameters()));
        validateReferenceAssets(owner, project, draft, binding);
    }

    private void validateCapabilityInputs(Task.Kind kind, MediaDraft draft,
            dev.agenvas.provider.domain.MediaAdapterRegistry.Declaration policy, JsonNode parametersJson) {
        long audioCount = draft.mediaInputs().stream().filter(input ->
                input.role() == MediaDraft.InputRole.AUDIO_REFERENCE).count();
        if (audioCount > policy.maxReferenceAudios()) throw invalid(ApiMessage.of("api.direct-media-task-service.the-selected-capability-does-not-support-this-number-of-audio"));
        if (kind == Task.Kind.AUDIO_GENERATION) {
            if (draft.prompt().codePointCount(0, draft.prompt().length()) > AudioGenerationParameters.MAX_PROMPT_LENGTH)
                throw invalid(ApiMessage.of("api.direct-media-task-service.seed-audio-prompt-words-can-be-up-to-3000-characters"));
            long images = draft.mediaInputs().size() - audioCount;
            var parameters = dev.agenvas.artifact.domain.AudioGenerationParameters.parse(parametersJson);
            if (images > policy.maxReferenceImages() || images > 0 && (audioCount > 0 || !parameters.speaker().isEmpty()))
                throw invalid(ApiMessage.of("api.direct-media-task-service.audio-generation-can-refer-to-at-most-one-picture-and"));
            if (audioCount + (parameters.speaker().isEmpty() ? 0 : 1) > policy.maxReferenceAudios())
                throw invalid(ApiMessage.of("api.direct-media-task-service.the-total-number-of-timbres-and-audio-references-can-be"));
            return;
        }
        if (kind == Task.Kind.IMAGE_GENERATION) {
            if (draft.mediaInputs().size() > policy.maxReferenceImages()) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.the-selected-image-capability-accepts-at-most-reference-images", policy.maxReferenceImages()));
            }
            return;
        }
        String mode = draft.videoInputMode().name();
        if (!policy.supportedVideoInputModes().contains(mode)) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.the-selected-video-capability-does-not-support-the-current-picture"));
        }
        if (!policy.supportsEndFrame() && draft.mediaInputs().stream().anyMatch(input ->
                input.role() == MediaDraft.InputRole.END_FRAME)) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.the-selected-video-capability-does-not-support-end-frames"));
        }
        if (draft.mediaInputs().size() - audioCount > policy.maxReferenceImages()) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.the-selected-video-capability-accepts-at-most-image-inputs", policy.maxReferenceImages()));
        }
    }

    /** Reject known protocol limits before creating a task or reserving usage. */
    private void validateReferenceAssets(UUID ownerId, UUID projectId, MediaDraft draft,
            MediaCapabilityBinding binding) {
        boolean seed = MediaAdapterRegistry.SEED_AUDIO_1.equals(binding.adapterId());
        boolean ark = MediaAdapterRegistry.SEEDANCE_2.equals(binding.adapterId());
        if (MediaAdapterRegistry.RUNNINGHUB_ADAPTERS.contains(binding.adapterId())) {
            for (var input : draft.mediaInputs()) {
                var version = artifacts.requireMediaVersionForTask(ownerId, projectId, input.versionId(), dev.agenvas.artifact.application.MediaDraftService.mediaKind(input.role()));
                Asset asset = assets.requireReadyMedia(ownerId, projectId, UUID.fromString(version.content().path("assetId").asText()),
                        Asset.MediaKind.valueOf(dev.agenvas.artifact.application.MediaDraftService.mediaKind(input.role()).name()));
                if (asset.byteSize() > dev.agenvas.provider.infrastructure.RunningHubClient.MAX_UPLOAD_BYTES) throw invalid(ApiMessage.of("api.direct-media-task-service.a-single-piece-of-runninghub-material-cannot-exceed-30-mb"));
            }
            return;
        }
        boolean autodl = AutoDlWorkflows.ADAPTER_ID.equals(binding.adapterId());
        if (!seed && !ark && !autodl) return;
        long autodlTotalBytes = 0;
        long audioDuration = 0;
        int imageCount = 0;
        int audioCount = 0;
        for (var reference : draft.mediaInputs()) {
            boolean audio = reference.role() == MediaDraft.InputRole.AUDIO_REFERENCE;
            var version = artifacts.requireMediaVersionForTask(ownerId, projectId, reference.versionId(),
                    audio ? Artifact.Kind.AUDIO : Artifact.Kind.IMAGE);
            Asset asset = assets.requireReadyMedia(ownerId, projectId,
                    UUID.fromString(version.content().path("assetId").asText()),
                    audio ? Asset.MediaKind.AUDIO : Asset.MediaKind.IMAGE);
            if (autodl) {
                autodlTotalBytes += asset.byteSize();
                if (asset.byteSize() > AutoDlWorkflows.MAX_REFERENCE_BYTES
                        || autodlTotalBytes > AutoDlWorkflows.MAX_TOTAL_REFERENCE_BYTES
                        || audio && !Set.of("audio/mpeg", "audio/wav", "audio/flac").contains(asset.contentType()))
                    throw invalid(ApiMessage.of("api.direct-media-task-service.autodl-reference-assets-are-limited-to-15-mib-each-and"));
            }
            if (!audio) {
                imageCount++;
                if (seed && asset.byteSize() > AudioGenerationParameters.MAX_REFERENCE_BYTES)
                    throw invalid(ApiMessage.of("api.direct-media-task-service.seed-audio-reference-image-size-is-10-mib-maximum"));
                continue;
            }
            audioCount++;
            audioDuration += asset.durationMs();
            if (seed && (asset.byteSize() > AudioGenerationParameters.MAX_REFERENCE_BYTES
                    || asset.durationMs() > AudioGenerationParameters.MAX_REFERENCE_DURATION_MS))
                throw invalid(ApiMessage.of("api.direct-media-task-service.seed-audio-the-maximum-size-of-a-single-reference-audio"));
            if (ark && (asset.byteSize() > MediaAdapterRegistry.SEEDANCE_MAX_AUDIO_BYTES
                    || asset.durationMs() < MediaAdapterRegistry.SEEDANCE_MIN_AUDIO_DURATION_MS
                    || asset.durationMs() > MediaAdapterRegistry.SEEDANCE_MAX_AUDIO_DURATION_MS
                    || !Set.of("audio/mpeg", "audio/wav").contains(asset.contentType())))
                throw invalid(ApiMessage.of("api.direct-media-task-service.seedance-audio-reference-supports-mp3-wav-only-2-15-seconds"));
        }
        if (ark && audioCount > 0 && (imageCount == 0
                || draft.videoInputMode() != MediaDraft.VideoInputMode.GENERAL_REFERENCE
                || audioDuration > MediaAdapterRegistry.SEEDANCE_MAX_AUDIO_DURATION_MS))
            throw invalid(ApiMessage.of("api.direct-media-task-service.seedance-audio-reference-must-be-in-full-reference-mode-with"));
    }

    private static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.direct-media-task-service.invalid-media-task-input"), detail, false);
    }

    private static ApiProblemException conflict(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "DIRECT_MEDIA_CONFLICT",
                ApiMessage.of("api.direct-media-task-service.conflicting-media-assignments"), detail, true);
    }
}
