package dev.agenvas.task.application;

import dev.agenvas.shared.crypto.Sha256;
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
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.ComfyUiWorkflowDefinition;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.OpenAiImage2Client;
import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.settings.application.MediaStyleService;
import dev.agenvas.settings.application.PromptService;
import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.task.domain.ImageOperationSpec;
import dev.agenvas.usage.application.UsageService;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Accepts a user's saved media draft as one immutable Task per requested media output. */
@Service
public class DirectMediaTaskService {
    private static final int MAX_COMMAND_KEY_LENGTH = 160;
    private static final int MAX_IMAGE_OPERATION_INSTRUCTION_LENGTH = 4000;
    private static final int MEDIA_TASK_INPUT_SCHEMA_VERSION = 6;
    private static final int IMAGE_OPERATION_INPUT_SCHEMA_VERSION = 9;
    private static final int TASK_EVENT_SCHEMA_VERSION = 1;
    private static final int BATCH_KEY_DIGEST_LENGTH = 32;
    private static final String LOCAL_COST_SOURCE = "LOCAL_NO_COST";
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
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ProjectService projects;
    private final AgentRunService runs;
    private final MediaStyleService styles;
    private final PromptService prompts;
    private final dev.agenvas.provider.application.MediaFunctionService functions;
    private final dev.agenvas.asset.storage.MediaRelayService relay;

    public DirectMediaTaskService(TaskRepository tasks, MediaDraftService drafts,
            ArtifactService artifacts, AssetService assets, CanvasItemQueryService canvasItems,
            CanvasService canvas,
            MediaCapabilityService capabilities,
            ProjectEventService events, UsageService usage,
            ObjectMapper mapper, Clock clock, ProjectService projects, AgentRunService runs, MediaStyleService styles, dev.agenvas.asset.storage.MediaRelayService relay,
            PromptService prompts, dev.agenvas.provider.application.MediaFunctionService functions) {
        this.tasks = tasks;
        this.drafts = drafts;
        this.artifacts = artifacts;
        this.assets = assets;
        this.canvasItems = canvasItems;
        this.canvas = canvas;
        this.capabilities = capabilities;
        this.events = events;
        this.usage = usage;
        this.mapper = mapper;
        this.clock = clock;
        this.projects = projects;
        this.runs = runs;
        this.styles = styles;
        this.relay = relay;
        this.prompts = prompts;
        this.functions = functions;
    }

    @Transactional
    public Task run(UUID ownerId, UUID projectId, UUID artifactId, UUID canvasItemId,
            long expectedDraftVersion, String commandKey) {
        return runInternal(ownerId, projectId, null, null, artifactId, canvasItemId,
                expectedDraftVersion, commandKey, null);
    }

    /** Only the approval application service calls this after recording the user's decision. */
    @Transactional
    public Task runApproved(UUID ownerId, UUID projectId, UUID runId, UUID approvalId,
            UUID artifactId, UUID canvasItemId, long expectedDraftVersion, String commandKey) {
        if (runId == null || approvalId == null) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.requires-a-valid-idempotency-key-and-draft-version"));
        }
        return runApproved(ownerId, projectId, runId, approvalId, artifactId, canvasItemId, expectedDraftVersion, commandKey, null);
    }

    @Transactional
    public Task runApproved(UUID ownerId, UUID projectId, UUID runId, UUID approvalId,
            UUID artifactId, UUID canvasItemId, long expectedDraftVersion, String commandKey, JsonNode creativeSkill) {
        if (runId == null || approvalId == null) throw invalid(ApiMessage.of("api.direct-media-task-service.requires-a-valid-idempotency-key-and-draft-version"));
        return runInternal(ownerId, projectId, runId, approvalId, artifactId, canvasItemId,
                expectedDraftVersion, commandKey, creativeSkill);
    }

    private Task runInternal(UUID ownerId, UUID projectId, UUID runId, UUID approvalId,
            UUID artifactId, UUID canvasItemId, long expectedDraftVersion, String commandKey, JsonNode creativeSkill) {
        if (canvasItemId == null || commandKey == null || commandKey.isBlank()
                || commandKey.length() > MAX_COMMAND_KEY_LENGTH || expectedDraftVersion < 0) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.requires-a-valid-idempotency-key-and-draft-version"));
        }
        return events.recordChange(ownerId, projectId, () -> {
            // The project event row lock serializes acceptance with all other card commands.
            AgentRun run = runId == null ? null : runs.get(ownerId, projectId, runId);
            if (run != null && (run.status().terminal() || run.status() == AgentRun.Status.CANCEL_REQUESTED)) {
                throw conflict(ApiMessage.of("api.task-service.a-canceled-or-ended-run-cannot-create-new-tasks"));
            }
            Task prior = (runId == null ? tasks.findDirectByStepKey(ownerId, projectId, commandKey)
                    : tasks.findAgentByStepKey(ownerId, projectId, runId, commandKey)).orElse(null);
            if (prior != null) {
                String priorSourceCanvasItemId = prior.input().path("sourceCanvasItemId")
                        .asText(prior.input().path("canvasItemId").asText());
                if (!prior.input().path("artifactId").asText().equals(artifactId.toString())
                        || !priorSourceCanvasItemId.equals(canvasItemId.toString())
                        || prior.input().path("draftVersion").asLong(-1) != expectedDraftVersion
                        || !prior.input().path(Task.APPROVAL_INPUT_PROPERTY).asText("")
                                .equals(approvalId == null ? "" : approvalId.toString())) {
                    throw conflict(ApiMessage.of("api.direct-media-task-service.the-same-idempotent-key-has-been-used-in-different-card"));
                }
                return ProjectEventService.Change.unchanged(prior);
            }
            // Occupancy lookup is internal: authorize the target before returning another command's task.
            Artifact authorizedTarget = artifacts.get(ownerId, projectId, artifactId).artifact();
            CanvasItem authorizedCard = canvasItems.requireArtifactItem(ownerId, projectId, canvasItemId);
            requireTargetCard(authorizedTarget, authorizedCard);
            Task occupying = tasks.findOccupyingDirectMediaTask(projectId, canvasItemId)
                    .orElse(null);
            if (occupying != null) {
                if (runId != null) throw conflict(ApiMessage.of("api.task-service.this-media-card-already-has-tasks-queued-executed-or-pending"));
                return ProjectEventService.Change.unchanged(occupying);
            }
            PreparedMedia prepared = prepare(ownerId, projectId, authorizedTarget, authorizedCard, expectedDraftVersion, true);
            Artifact target = prepared.target();
            CanvasItem canvasItem = prepared.canvasItem();
            MediaDraft draft = prepared.draft();
            Task.Kind kind = prepared.kind();
            MediaCapabilityBinding binding = prepared.binding();
            var definition = prepared.definition();
            boolean dynamic = definition != null;
            ObjectNode dynamicParameters = prepared.dynamicParameters();
            ImageGenerationParameters imageParameters = prepared.imageParameters();
            VideoGenerationParameters videoParameters = prepared.videoParameters();
            Integer duration = prepared.duration();
            int seconds = kind == Task.Kind.VIDEO_GENERATION && duration != null ? duration : 0;
            JsonNode configuredSettings = prepared.configuredSettings();
            String renderedPrompt = prepared.renderedPrompt();
            String autodlResolution = prepared.autodlResolution();
            String resolutionTier = prepared.resolutionTier();
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
                input.put("schemaVersion", MEDIA_TASK_INPUT_SCHEMA_VERSION);
                if (approvalId != null) input.put(Task.APPROVAL_INPUT_PROPERTY, approvalId.toString());
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
                input.put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
                if (kind == Task.Kind.VIDEO_GENERATION && duration != null) input.put("durationSeconds", seconds);
                ObjectNode frozen = input.putObject("mediaInput");
                var comfyDimensions = comfyDimensions(ownerId, projectId, prepared);
                if (comfyDimensions != null) frozen.set("providerParameters", comfyProviderParameters(prepared, comfyDimensions));
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
                if (dynamic) frozen.set("runningHubContract", mapper.valueToTree(definition.executionContract()));
                frozen.put("prompt", draft.prompt());
                frozen.put("userRenderedPrompt", renderPrompt(draft));
                frozen.put("renderedPrompt", renderedPrompt);
                if (prepared.style() == null) frozen.putNull("style");
                else frozen.set("style", mapper.valueToTree(prepared.style()));
                frozen.set("parameters", effectiveParameters(prepared));
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
                    if (MediaAdapterRegistry.SEEDANCE_2.equals(binding.adapterId())
                            && imageInput.role() == MediaDraft.InputRole.VIDEO_REFERENCE) {
                        Asset asset = assets.requireReadyMedia(ownerId, projectId,
                                UUID.fromString(image.content().path("assetId").asText()), Asset.MediaKind.VIDEO);
                        UUID relayProfile = relay.pinProfile(asset);
                        if (relayProfile == null) imageNode.putNull("relayProfileId");
                        else imageNode.put("relayProfileId", relayProfile.toString());
                    }
                }
                pinImageRelay(input, binding);
                frozen.set("mentions", mapper.valueToTree(draft.mentions()));
                if (creativeSkill != null) frozen.set("creativeSkill", creativeSkill.deepCopy());
                String stepKey = outputIndex == 0 ? commandKey
                        : "image-batch:" + Sha256.hex(commandKey).substring(0, BATCH_KEY_DIGEST_LENGTH) + ":" + outputIndex;
                Task task = new Task(UUID.randomUUID(), projectId, runId, stepKey, kind,
                        Task.Status.READY, false, input, Sha256.hex(input.toString()), null, null,
                        1, now, null, null, 0, 0, null, now, now, null);
                tasks.create(task);
                tasks.bindMediaTask(task.id(), binding);
                tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                        artifactId, outputCard.selectedVersionId(), target.version(),
                        outputCard.id()));
                drafts.setDisplayModeWithinChange(projectId, outputCard.id(),
                        MediaDraft.DisplayMode.DRAFT);
                usage.reserveMediaTask(ownerId, task, COST_SOURCE, binding.connectionVersion());
                ObjectNode payload = mapper.createObjectNode();
                payload.put("taskId", task.id().toString());
                payload.put("artifactId", artifactId.toString());
                payload.put("canvasItemId", outputCard.id().toString());
                payload.put("status", task.status().name());
                events.append(ownerId, projectId,
                        new ProjectEventService.EventDraft("task.status.changed", TASK_EVENT_SCHEMA_VERSION, task.id(),
                                task.version(), payload));
                if (primary == null) primary = task;
            }
            return ProjectEventService.Change.unchanged(java.util.Objects.requireNonNull(primary));
        }).value();
    }


    private void requireTargetCard(Artifact target, CanvasItem canvasItem) {
        if (!canvasItem.subjectId().equals(target.id())) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.the-run-target-must-be-the-canvas-card-of-the"));
        }
    }

    private PreparedMedia prepare(UUID ownerId, UUID projectId, UUID artifactId,
            UUID canvasItemId, long expectedDraftVersion) {
        Artifact target = artifacts.get(ownerId, projectId, artifactId).artifact();
        CanvasItem canvasItem = canvasItems.requireArtifactItem(ownerId, projectId, canvasItemId);
        return prepare(ownerId, projectId, target, canvasItem, expectedDraftVersion, false);
    }

    private PreparedMedia prepare(UUID ownerId, UUID projectId, Artifact target,
            CanvasItem canvasItem, long expectedDraftVersion, boolean lockStyle) {
        requireTargetCard(target, canvasItem);
        if (target.archivedAt() != null) throw conflict(ApiMessage.of("api.direct-media-task-service.archived-cards-cannot-be-run"));
        Task.Kind kind = switch (target.kind()) {
            case IMAGE -> Task.Kind.IMAGE_GENERATION;
            case VIDEO -> Task.Kind.VIDEO_GENERATION;
            case AUDIO -> Task.Kind.AUDIO_GENERATION;
            default -> throw invalid(ApiMessage.of("api.direct-media-task-service.only-picture-or-video-cards-can-be-run-directly"));
        };
        MediaDraft draft = drafts.get(ownerId, projectId, canvasItem.id());
        if (draft.version() != expectedDraftVersion) throw conflict(ApiMessage.of("api.direct-media-task-service.the-draft-has-changed-please-check-the-save-status-and"));
        if (lockStyle) styles.lockForGeneration(draft.styleId());
        MediaStyleService.Snapshot style = styles.forGeneration(draft.styleId(), target.kind());
        String renderedPrompt = MediaStyleService.compose(renderPrompt(draft), style);
        MediaCapabilityBinding selected = capabilities.forDraft(draft.capabilityId(), kind);
        var definition = capabilities.runningHubDefinition(selected);
        JsonNode configuredSettings = capabilities.settings(selected);
        var comfy = ComfyUiWorkflowDefinition.configured(configuredSettings)
                ? ComfyUiWorkflowDefinition.parse(mapper, configuredSettings.get(ComfyUiWorkflowDefinition.SETTINGS_KEY), kind) : null;
        boolean dynamic = definition != null;
        if (style != null && (dynamic && definition.fields().stream().noneMatch(field ->
                field.effectiveSource() == dev.agenvas.provider.domain.RunningHubDefinition.Source.PROMPT)
                || comfy != null && !comfy.maps(ComfyUiWorkflowDefinition.Source.PROMPT))) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MEDIA_STYLE_PROMPT_UNSUPPORTED",
                    ApiMessage.of("api.media-style.title"), ApiMessage.of("api.media-style.prompt-unsupported"), false);
        }
        if (comfy != null && !comfy.maps(ComfyUiWorkflowDefinition.Source.PROMPT)) renderedPrompt = "";
        if (!dynamic && (comfy == null || comfy.maps(ComfyUiWorkflowDefinition.Source.PROMPT))
                && draft.prompt().isBlank()) throw invalid(ApiMessage.of("api.direct-media-task-service.prompt-words-need-to-be-filled-in-before-running"));
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
        if (MediaAdapterRegistry.localProcessor(binding.adapterId())) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.local-image-processing-capabilities-can-only-be-used-from-the"));
        }
        ObjectNode dynamicParameters = null;
        if (dynamic) {
            dynamicParameters = mapper.createObjectNode();
            ObjectNode dynamicValues = definition.values(mapper, draft.parameters(), renderPrompt(draft), duration, true);
            if (style != null) {
                dynamicValues = definition.transformPromptValues(mapper, dynamicValues,
                        prompt -> MediaStyleService.compose(prompt, style));
                for (var field : definition.fields()) {
                    if (field.effectiveSource() == dev.agenvas.provider.domain.RunningHubDefinition.Source.PROMPT
                            && dynamicValues.hasNonNull(field.key())) {
                        renderedPrompt = dynamicValues.path(field.key()).asText();
                        break;
                    }
                }
            }
            dynamicParameters.set(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY, dynamicValues);
            dev.agenvas.artifact.application.MediaDraftService.validateSlots(definition, dynamicParameters, draft.mediaInputs(), true);
        } else validateCapabilityInputs(kind, draft, capabilities.inputPolicy(binding), capabilities.parameters(binding,
                comfy == null ? draft.parameters() : ComfyUiWorkflowDefinition.standardParameters(draft.parameters())));
        if (capabilities.inputPolicy(binding).platform() == dev.agenvas.provider.domain.MediaPlatform.COMFYUI
                && renderedPrompt.length() > MediaAdapterRegistry.COMFY_MAX_PROMPT_LENGTH) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MEDIA_STYLE_PROMPT_TOO_LONG",
                    ApiMessage.of("api.media-style.title"), ApiMessage.of("api.media-style.prompt-too-long",
                            MediaAdapterRegistry.COMFY_MAX_PROMPT_LENGTH), false);
        }
        ObjectNode comfyValues = null;
        if (comfy != null) {
            comfyValues = comfy.values(mapper, draft.parameters(), renderedPrompt, duration,
                    draft.mediaInputs().stream().map(MediaDraft.MediaInput::versionId).toList(), true);
            ObjectNode slotParameters = mapper.createObjectNode();
            slotParameters.set(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY, comfyValues);
            MediaDraftService.validateSlots(comfy.inputs(), slotParameters, draft.mediaInputs(), true);
        }
        validateReferenceAssets(ownerId, projectId, draft, binding);
        JsonNode parametersForControls = capabilities.parameters(binding, comfy == null ? draft.parameters()
                : ComfyUiWorkflowDefinition.standardParameters(draft.parameters()));
        ImageGenerationParameters imageParameters = !dynamic && kind == Task.Kind.IMAGE_GENERATION
                ? ImageGenerationParameters.parse(parametersForControls) : null;
        VideoGenerationParameters videoParameters = !dynamic && kind == Task.Kind.VIDEO_GENERATION
                ? VideoGenerationParameters.parse(parametersForControls) : null;
        if (imageParameters != null) {
            var policy = capabilities.inputPolicy(binding);
            imageParameters.requireSupported(policy.supportedImageAspectRatios(),
                    policy.supportedImageResolutions(), policy.supportedImageQualities(),
                    policy.supportsTransparentBackground());
        }
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
        return new PreparedMedia(target, canvasItem, draft, kind, binding, definition,
                dynamicParameters, imageParameters, videoParameters, duration, configuredSettings,
                renderedPrompt, autodlResolution, resolutionTier, style, comfyValues);
    }

    private ComfyUiWorkflowDefinition.Dimensions comfyDimensions(UUID ownerId, UUID projectId, PreparedMedia prepared) {
        if (!ComfyUiWorkflowDefinition.configured(prepared.configuredSettings())) return null;
        var workflow = ComfyUiWorkflowDefinition.parse(mapper, prepared.configuredSettings().get(ComfyUiWorkflowDefinition.SETTINGS_KEY), prepared.kind());
        String ratio = prepared.imageParameters() != null ? prepared.imageParameters().aspectRatio() : prepared.videoParameters().aspectRatio();
        if ("AUTO".equals(ratio) && workflow.maps(ComfyUiWorkflowDefinition.Source.WIDTH)) ratio = switch (projects.get(ownerId, projectId).aspectRatio()) {
            case LANDSCAPE_16_9 -> "16:9";
            case PORTRAIT_9_16 -> "9:16";
            case SQUARE_1_1 -> "1:1";
        };
        if (!workflow.maps(ComfyUiWorkflowDefinition.Source.WIDTH) && !"AUTO".equals(ratio))
            throw invalid(ApiMessage.of("api.comfy-workflow.invalid", "dimensions"));
        return workflow.dimensions(ratio);
    }

    private record PreparedMedia(Artifact target, CanvasItem canvasItem, MediaDraft draft,
            Task.Kind kind, MediaCapabilityBinding binding,
            dev.agenvas.provider.domain.RunningHubDefinition definition,
            ObjectNode dynamicParameters, ImageGenerationParameters imageParameters,
            VideoGenerationParameters videoParameters, Integer duration, JsonNode configuredSettings,
            String renderedPrompt, String autodlResolution, String resolutionTier,
            MediaStyleService.Snapshot style, ObjectNode comfyValues) {}

    private ObjectNode effectiveParameters(PreparedMedia prepared) {
        ObjectNode effective = prepared.dynamicParameters() != null ? prepared.dynamicParameters().deepCopy()
                : prepared.imageParameters() != null ? prepared.imageParameters().toJson(mapper)
                : prepared.videoParameters() != null ? prepared.videoParameters().toJson(mapper)
                : AudioGenerationParameters.parse(capabilities.parameters(prepared.binding(), prepared.draft().parameters())).toJson(mapper);
        if (prepared.comfyValues() != null)
            effective.set(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY, prepared.comfyValues());
        return effective;
    }

    private ObjectNode comfyProviderParameters(PreparedMedia prepared, ComfyUiWorkflowDefinition.Dimensions dimensions) {
        ObjectNode frozen = (ObjectNode) mapper.valueToTree(dimensions);
        frozen.set(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY, prepared.comfyValues());
        return frozen;
    }

    /** Only trusted Agent approval sources can add provenance to a preflight hash. */
    public MediaPreflight preflightApproved(UUID ownerId, UUID projectId, UUID artifactId,
            UUID canvasItemId, long expectedDraftVersion, JsonNode creativeSkill) {
        MediaPreflight result = preflight(ownerId, projectId, artifactId, canvasItemId, expectedDraftVersion);
        if (creativeSkill == null || creativeSkill.isNull()) return result;
        ObjectNode summary = (ObjectNode) result.safeSummary().deepCopy();
        summary.set("creativeSkill", creativeSkill.deepCopy());
        return new MediaPreflight(result.kind(), result.binding(),
                Sha256.hex(result.frozenInputHash() + "\n" + canonicalSkillSource(creativeSkill)), result.outputCount(), summary);
    }

    /** JSONB can reorder object fields between proposal and approval; array order remains semantic. */
    private JsonNode canonicalSkillSource(JsonNode source) {
        if (source.isObject()) {
            ObjectNode ordered = mapper.createObjectNode();
            source.propertyStream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(field -> ordered.set(field.getKey(), canonicalSkillSource(field.getValue())));
            return ordered;
        }
        if (source.isArray()) {
            ArrayNode ordered = mapper.createArrayNode();
            source.forEach(value -> ordered.add(canonicalSkillSource(value)));
            return ordered;
        }
        return source;
    }

    /** Validates the exact proposal without creating cards, tasks, reservations or provider requests. */
    @Transactional(readOnly = true)
    public MediaPreflight preflight(UUID ownerId, UUID projectId, UUID artifactId,
            UUID canvasItemId, long expectedDraftVersion) {
        if (canvasItemId == null || expectedDraftVersion < 0) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.requires-a-valid-idempotency-key-and-draft-version"));
        }
        PreparedMedia prepared = prepare(ownerId, projectId, artifactId, canvasItemId, expectedDraftVersion);
        if (tasks.findOccupyingDirectMediaTask(projectId, canvasItemId).isPresent()) {
            throw conflict(ApiMessage.of("api.task-service.this-media-card-already-has-tasks-queued-executed-or-pending"));
        }
        int outputCount = prepared.imageParameters() == null ? 1 : prepared.imageParameters().generationCount();
        JsonNode effectiveParameters = effectiveParameters(prepared);
        JsonNode pricing = dev.agenvas.provider.domain.MediaCapabilityConfiguration.price(
                prepared.configuredSettings(), prepared.resolutionTier());
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("schemaVersion", MEDIA_TASK_INPUT_SCHEMA_VERSION);
        snapshot.put("artifactId", artifactId.toString());
        snapshot.put("artifactVersion", prepared.target().version());
        snapshot.put("canvasItemId", canvasItemId.toString());
        if (prepared.canvasItem().selectedVersionId() == null) snapshot.putNull("parentVersionId");
        else snapshot.put("parentVersionId", prepared.canvasItem().selectedVersionId().toString());
        snapshot.put("resultSelectionEpoch", canvas.mediaSelectionEpoch(ownerId, projectId, canvasItemId));
        snapshot.put("draftVersion", expectedDraftVersion);
        snapshot.put("prompt", prepared.renderedPrompt());
        snapshot.put("structuralPrompt", prepared.draft().prompt());
        snapshot.set("style", mapper.valueToTree(prepared.style()));
        snapshot.set("parameters", effectiveParameters);
        var comfyDimensions = comfyDimensions(ownerId, projectId, prepared);
        if (comfyDimensions != null) snapshot.set("providerParameters", comfyProviderParameters(prepared, comfyDimensions));
        snapshot.set("mediaInputs", mapper.valueToTree(prepared.draft().mediaInputs()));
        snapshot.set("mentions", mapper.valueToTree(prepared.draft().mentions()));
        snapshot.set("binding", mapper.valueToTree(prepared.binding()));
        snapshot.put("kind", prepared.kind().name());
        snapshot.put("mode", prepared.kind() == Task.Kind.IMAGE_GENERATION ? "GENERAL_REFERENCE"
                : prepared.kind() == Task.Kind.AUDIO_GENERATION ? "TEXT" : prepared.draft().videoInputMode().name());
        if (prepared.duration() != null) snapshot.put("durationSeconds", prepared.duration());
        if (prepared.autodlResolution() != null) snapshot.put("providerResolution", prepared.autodlResolution());
        if (pricing != null) snapshot.set("mediaPricing", pricing);
        ObjectNode summary = mapper.createObjectNode();
        summary.put("kind", prepared.kind().name());
        summary.put("prompt", renderPrompt(prepared.draft()));
        if (prepared.style() != null) {
            summary.put("styleId", prepared.style().id().toString());
            summary.put("styleName", prepared.style().name());
            summary.put("styleVersion", prepared.style().version());
        }
        summary.set("parameters", effectiveParameters.deepCopy());
        summary.put("capabilityId", prepared.binding().capabilityId().toString());
        summary.put("adapterId", prepared.binding().adapterId());
        summary.put("outputCount", outputCount);
        // Approval clients must see exactly which immutable material versions will be sent.
        summary.set("mediaInputs", mapper.valueToTree(prepared.draft().mediaInputs()));
        if (prepared.kind() == Task.Kind.VIDEO_GENERATION) {
            summary.put("videoInputMode", prepared.draft().videoInputMode().name());
        }
        if (prepared.duration() != null) summary.put("durationSeconds", prepared.duration());
        summary.put("priceUnknown", pricing == null);
        if (pricing != null) summary.set("mediaPricing", pricing.deepCopy());
        return new MediaPreflight(prepared.kind(), prepared.binding(), Sha256.hex(snapshot.toString()),
                outputCount, summary);
    }

    public record MediaPreflight(Task.Kind kind, MediaCapabilityBinding binding,
            String frozenInputHash, int outputCount, JsonNode safeSummary) {}

    /** Holds catalog rows throughout approval validation and task creation, closing configuration races. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockApprovalBinding(MediaCapabilityBinding binding) {
        return binding != null && tasks.lockCurrentMediaBinding(binding);
    }

    /** Approval locks the selected visual preset before hashing and accepting its fixed output. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockApprovalStyle(UUID ownerId, UUID projectId, UUID canvasItemId) {
        styles.lockForGeneration(drafts.get(ownerId, projectId, canvasItemId).styleId());
    }

    /** Accepts one image post-processing command while pinning the exact visible source version. */
    public Task runImageOperation(UUID ownerId, UUID projectId, UUID artifactId,
            UUID canvasItemId, UUID sourceVersionId, long expectedCanvasItemVersion,
            ImageOperation operation, String instruction, long expectedFunctionVersion, int expectedCapabilityVersion,
            List<UUID> referenceVersionIds, UUID maskAssetId, JsonNode requestedParameters,
            String commandKey) {
        if (canvasItemId == null || sourceVersionId == null || operation == null
                || expectedCanvasItemVersion < 0 || commandKey == null || commandKey.isBlank()
                || commandKey.length() > MAX_COMMAND_KEY_LENGTH) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.requires-a-valid-image-action-card-version-and-idempotency-key"));
        }
        ImageOperationSpec operationSpec = ImageOperationSpec.parse(mapper, operation, requestedParameters);
        ObjectNode operationParameters = operationSpec.parameters();
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
        if (normalizedInstruction.length() > MAX_IMAGE_OPERATION_INSTRUCTION_LENGTH) throw invalid(ApiMessage.of("api.direct-media-task-service.edit-description-cannot-exceed-4000-characters"));
        if (operation.instructionRequired() && normalizedInstruction.isBlank()) {
            throw invalid(ApiMessage.of("api.direct-media-task-service.this-ai-image-processing-requires-filling-in-processing-instructions"));
        }
        if (expectedFunctionVersion < 0 || expectedCapabilityVersion < 1) {
            throw invalid(ApiMessage.of("api.media-function.invalid-request"));
        }
        ObjectNode request = mapper.createObjectNode().put("artifactId", artifactId.toString())
                .put("canvasItemId", canvasItemId.toString()).put("sourceVersionId", sourceVersionId.toString())
                .put("expectedCanvasItemVersion", expectedCanvasItemVersion).put("operation", operation.name())
                .put("instruction", normalizedInstruction).put("expectedFunctionVersion", expectedFunctionVersion)
                .put("expectedCapabilityVersion", expectedCapabilityVersion);
        request.set("referenceVersionIds", requestedReferenceIds);
        if (maskAssetId == null) request.putNull("maskAssetId");
        else request.put("maskAssetId", maskAssetId.toString());
        request.set("parameters", requestedParameters);
        // Compare parsed numbers with the JSONB replay representation (for example 2 and 2.0).
        JsonNode normalizedRequest = mapper.readTree(request.toString());
        // Remote masks are materialized before the event transaction acquires project locks.
        // Immutable READY bytes allow the acceptance transaction to recheck only identity and capability.
        if (maskAssetId != null && tasks.findDirectByStepKey(ownerId, projectId, commandKey).isEmpty()) {
            validateImageMask(ownerId, projectId, maskAssetId);
        }
        return events.recordChange(ownerId, projectId, () -> {
            Task prior = tasks.findDirectByStepKey(ownerId, projectId, commandKey).orElse(null);
            if (prior != null) {
                if (!prior.input().path("imageOperationRequest").equals(normalizedRequest)) {
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
            if (operation == ImageOperation.RESIZE) {
                var sourceAsset = assets.requireReadyMedia(ownerId, projectId,
                        UUID.fromString(source.content().path("assetId").asText()), Asset.MediaKind.IMAGE);
                dev.agenvas.task.domain.ImageResizeSpec.parse(operationParameters)
                        .dimensions(sourceAsset.width(), sourceAsset.height());
            }
            MediaCapabilityBinding binding = functions.resolve(
                    dev.agenvas.provider.domain.MediaFunction.forImage(operation), expectedFunctionVersion);
            if (binding.capabilityVersion() != expectedCapabilityVersion) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "MEDIA_CAPABILITY_CHANGED",
                        ApiMessage.of("api.media-function.title"), ApiMessage.of("api.media-function.conflict"), false);
            }
            UUID requestedCapabilityId = binding.capabilityId();
            var definition = capabilities.runningHubDefinition(binding);
            // Workflow fields own their scale; do not record an unseen local interpolation default.
            if (definition != null && operation == ImageOperation.UPSCALE) operationParameters.remove("scale");
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
            boolean transparentOutput = operationSpec.requiresTransparentOutput();
            if (transparentOutput && !inputPolicy.supportsTransparentBackground()) {
                throw invalid(ApiMessage.of("api.direct-media-task-service.the-selected-ai-picture-capability-does-not-support-transparent-background"));
            }
            // Read only on first acceptance: replay and Workers keep the original frozen content.
            String promptKey = operationSpec.promptKey();
            PromptService.Prompt functionPrompt = promptKey == null ? null
                    : prompts.require(promptKey, PromptService.Kind.FUNCTION);
            String prompt = operationSpec.prompt(normalizedInstruction,
                    functionPrompt == null ? null : functionPrompt.content());
            if (definition != null && (operation == ImageOperation.UPSCALE || operation == ImageOperation.DEPTH_MAP)) {
                prompt = operation.resultLabel() + (normalizedInstruction.isBlank() ? "" : ": " + normalizedInstruction);
            }
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
            ObjectNode workflowParameters = mapper.createObjectNode();
            if (definition != null) {
                Asset sourceAsset = assets.requireReadyMedia(ownerId, projectId,
                        UUID.fromString(source.content().path("assetId").asText()), Asset.MediaKind.IMAGE);
                if (sourceAsset.byteSize() > dev.agenvas.provider.infrastructure.RunningHubClient.MAX_UPLOAD_BYTES) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.a-single-piece-of-runninghub-material-cannot-exceed-30-mb"));
                }
                JsonNode dynamic = requestedParameters.path(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY);
                if (!dynamic.isMissingNode() && !dynamic.isObject()) {
                    throw invalid(ApiMessage.of("api.media-function.invalid-request"));
                }
                workflowParameters.set(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY,
                        dynamic.isMissingNode() ? mapper.createObjectNode() : dynamic.deepCopy());
                var sourceField = definition.fields().stream().filter(dev.agenvas.provider.domain.RunningHubDefinition.Field::media)
                        .findFirst().orElseThrow();
                workflowParameters.withObject(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY)
                        .put(sourceField.key(), sourceVersionId.toString());
                workflowParameters.set(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY,
                        definition.values(mapper, workflowParameters, prompt, null, true));
            } else if (requestedParameters.has(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY)) {
                throw invalid(ApiMessage.of("api.media-function.invalid-request"));
            }
            String outputRatio = operation == ImageOperation.OUTPAINT || operation == ImageOperation.THREE_VIEW
                    ? operationParameters.path("aspectRatio").asText()
                    : sourceAspectRatio(ownerId, projectId, source, inputPolicy.supportedImageAspectRatios());
            if (definition == null && !MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(binding.adapterId())
                    && !inputPolicy.supportedImageAspectRatios().contains(outputRatio)) {
                throw invalid(ApiMessage.of("api.image-generation-parameters.the-selected-image-capability-does-not-support-aspect-ratio", outputRatio));
            }
            MediaDraft sourceDraft = drafts.get(ownerId, projectId, canvasItemId);
            CanvasItem outputCard = canvas.forkMediaDerivationWithinChange(ownerId, projectId,
                    canvasItemId, UUID.randomUUID(), sourceDraft.version(), 0,
                    operationSpec.resultLabel());
            Instant now = clock.instant();
            ObjectNode input = mapper.createObjectNode();
            input.put("schemaVersion", IMAGE_OPERATION_INPUT_SCHEMA_VERSION);
            input.set("imageOperationRequest", normalizedRequest);
            input.put("functionVersion", expectedFunctionVersion);
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
            if (functionPrompt != null) {
                input.put("promptKey", functionPrompt.key());
                input.put("promptVersion", functionPrompt.version());
            }
            JsonNode operationSettings = capabilities.settings(binding);
            if (operationSettings.has("pricing")
                    && !MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(binding.adapterId())) {
                input.set("mediaPricing", operationSettings.get("pricing"));
            }
            input.put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
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
            if (ComfyUiWorkflowDefinition.configured(operationSettings)) {
                var workflow = ComfyUiWorkflowDefinition.parse(mapper, operationSettings.get(ComfyUiWorkflowDefinition.SETTINGS_KEY), Task.Kind.IMAGE_GENERATION);
                workflow.requireReferences(1 + normalizedReferenceIds.size());
                frozen.set("providerParameters", mapper.valueToTree(workflow.dimensions(outputRatio)));
            }
            frozen.put("parentVersionId", sourceVersionId.toString());
            frozen.put("mode", MediaDraft.VideoInputMode.GENERAL_REFERENCE.name());
            frozen.put("prompt", prompt);
            frozen.put("renderedPrompt", prompt);
            ObjectNode generationParameters = frozen.putObject("parameters");
            generationParameters.put("aspectRatio", outputRatio);
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
            pinImageRelay(input, binding);
            frozen.putArray("mentions");
            if (definition != null) {
                input.put("providerProtocol", "RUNNINGHUB_V2");
                frozen.set("runningHubContract", mapper.valueToTree(definition.executionContract()));
                frozen.set("parameters", workflowParameters);
            }
            Task task = new Task(UUID.randomUUID(), projectId, null, commandKey,
                    Task.Kind.IMAGE_GENERATION, Task.Status.READY, false, input,
                    Sha256.hex(input.toString()), null, null, 1, now, null, null, 0, 0,
                    null, now, now, null);
            tasks.create(task);
            tasks.bindMediaTask(task.id(), binding);
            tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                    artifactId, sourceVersionId, target.version(), outputCard.id()));
            usage.reserveMediaTask(ownerId, task,
                    MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(binding.adapterId())
                            ? LOCAL_COST_SOURCE : COST_SOURCE, binding.connectionVersion());
            ObjectNode payload = mapper.createObjectNode();
            payload.put("taskId", task.id().toString());
            payload.put("artifactId", artifactId.toString());
            payload.put("canvasItemId", outputCard.id().toString());
            payload.put("sourceCanvasItemId", canvasItemId.toString());
            payload.put("status", task.status().name());
            payload.put("imageOperation", operation.name());
            events.append(ownerId, projectId, new ProjectEventService.EventDraft(
                    "task.status.changed", TASK_EVENT_SCHEMA_VERSION, task.id(), task.version(), payload));
            return ProjectEventService.Change.unchanged(task);
        }).value();
    }

    /** Only the fixed OpenAI Images protocol currently declares URL reference support. */
    private void pinImageRelay(ObjectNode input, MediaCapabilityBinding binding) {
        if (!MediaAdapterRegistry.OPENAI_GPT_IMAGE_2.equals(binding.adapterId())
                || input.path("mediaInput").path("images").isEmpty()) return;
        UUID profile = relay.pinImageProfile();
        if (profile == null) input.putNull("imageRelayProfileId");
        else input.put("imageRelayProfileId", profile.toString());
    }

    /** A provider mask is an immutable, project-scoped PNG with a real alpha channel. */
    private void validateImageMask(UUID ownerId, UUID projectId, UUID maskAssetId) {
        AssetService.AssetFile file = assets.get(ownerId, projectId, maskAssetId);
        Asset mask = file.asset();
        if (mask.mediaKind() != Asset.MediaKind.IMAGE
                || !"image/png".equals(mask.contentType())
                || mask.byteSize() < 1 || mask.byteSize() > OpenAiImage2Client.MAX_MASK_BYTES) {
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

    private String sourceAspectRatio(UUID ownerId, UUID projectId, ArtifactVersion source, Set<String> supported) {
        UUID assetId = UUID.fromString(source.content().path("assetId").asText());
        Asset asset = assets.requireReadyMedia(ownerId, projectId, assetId,
                Asset.MediaKind.IMAGE);
        double actual = (double) asset.width() / asset.height();
        return (supported.isEmpty() ? ImageGenerationParameters.ASPECT_RATIOS : supported).stream()
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
        return tasks.listMediaForCanvasItem(ownerId, projectId, canvasItemId);
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

    /** Checks library reference reuse with the same protocol rules as direct generation, without a task or provider call. */
    public void validateLibraryReference(UUID owner, UUID project, Artifact.Kind artifactKind, MediaDraft draft) {
        Task.Kind kind = switch (artifactKind) {
            case IMAGE -> Task.Kind.IMAGE_GENERATION;
            case VIDEO -> Task.Kind.VIDEO_GENERATION;
            case AUDIO -> Task.Kind.AUDIO_GENERATION;
            default -> throw invalid(ApiMessage.of("api.direct-media-task-service.media-references-cannot-be-added-to-text-nodes"));
        };
        MediaCapabilityBinding binding = capabilities.forDraft(draft.capabilityId(), kind);
        var definition = capabilities.runningHubDefinition(binding);
        var comfy = capabilities.comfyWorkflowDefinition(binding);
        if (definition != null) {
            definition.values(mapper, draft.parameters(), draft.prompt(), draft.durationSeconds(), false);
            MediaDraftService.validateSlots(definition, draft.parameters(), draft.mediaInputs(), false);
        } else if (comfy != null) {
            ObjectNode values = mapper.createObjectNode();
            values.set(RunningHubDefinition.VALUES_PROPERTY, comfy.values(mapper, draft.parameters(),
                    draft.prompt(), draft.durationSeconds(), draft.mediaInputs().stream()
                            .map(MediaDraft.MediaInput::versionId).toList(), false));
            MediaDraftService.validateSlots(comfy.inputs(), values, draft.mediaInputs(), false);
        } else validateCapabilityInputs(kind, draft, capabilities.inputPolicy(binding), capabilities.parameters(binding, draft.parameters()));
        validateReferenceAssets(owner, project, draft, binding);
    }

    private void validateCapabilityInputs(Task.Kind kind, MediaDraft draft,
            dev.agenvas.provider.domain.MediaAdapterRegistry.Declaration policy, JsonNode parametersJson) {
        long audioCount = draft.mediaInputs().stream().filter(input ->
                input.role() == MediaDraft.InputRole.AUDIO_REFERENCE).count();
        long videoCount = draft.mediaInputs().stream().filter(input ->
                input.role() == MediaDraft.InputRole.VIDEO_REFERENCE).count();
        if (videoCount > policy.maxReferenceVideos() || videoCount > 0 && (kind != Task.Kind.VIDEO_GENERATION
                || draft.videoInputMode() != MediaDraft.VideoInputMode.GENERAL_REFERENCE))
            throw invalid(ApiMessage.of("api.media-relay.video-input-limit", policy.maxReferenceVideos()));
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
        if (draft.mediaInputs().size() - audioCount - videoCount > policy.maxReferenceImages()) {
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
        long videoDuration = 0;
        int videoCount = 0;
        int imageCount = 0;
        int audioCount = 0;
        for (var reference : draft.mediaInputs()) {
            boolean audio = reference.role() == MediaDraft.InputRole.AUDIO_REFERENCE;
            boolean video = reference.role() == MediaDraft.InputRole.VIDEO_REFERENCE;
            var version = artifacts.requireMediaVersionForTask(ownerId, projectId, reference.versionId(),
                    video ? Artifact.Kind.VIDEO : audio ? Artifact.Kind.AUDIO : Artifact.Kind.IMAGE);
            Asset asset = assets.requireReadyMedia(ownerId, projectId,
                    UUID.fromString(version.content().path("assetId").asText()),
                    video ? Asset.MediaKind.VIDEO : audio ? Asset.MediaKind.AUDIO : Asset.MediaKind.IMAGE);
            if (autodl) {
                autodlTotalBytes += asset.byteSize();
                if (asset.byteSize() > AutoDlWorkflows.MAX_REFERENCE_BYTES
                        || autodlTotalBytes > AutoDlWorkflows.MAX_TOTAL_REFERENCE_BYTES
                        || audio && !Set.of("audio/mpeg", "audio/wav", "audio/flac").contains(asset.contentType()))
                    throw invalid(ApiMessage.of("api.direct-media-task-service.autodl-reference-assets-are-limited-to-15-mib-each-and"));
            }
            if (video) {
                videoCount++;
                videoDuration += asset.durationMs();
                dev.agenvas.provider.domain.SeedanceVideoReferences.validateMetadata(asset);
                continue;
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
        if (ark && videoDuration > MediaAdapterRegistry.SEEDANCE_MAX_VIDEO_DURATION_MS)
            throw invalid(ApiMessage.of("api.media-relay.video-duration-limit"));
        if (ark && audioCount > 0 && (imageCount + videoCount == 0
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
