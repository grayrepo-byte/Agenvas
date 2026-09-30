package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
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

    public DirectMediaTaskService(TaskRepository tasks, MediaDraftService drafts,
            ArtifactService artifacts, AssetService assets, CanvasItemQueryService canvasItems,
            CanvasService canvas,
            MediaCapabilityService capabilities,
            ProviderProperties provider, ProjectEventService events, UsageService usage,
            ObjectMapper mapper, Clock clock) {
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
    }

    @Transactional
    public Task run(UUID ownerId, UUID projectId, UUID artifactId, UUID canvasItemId,
            long expectedDraftVersion, String commandKey) {
        if (canvasItemId == null || commandKey == null || commandKey.isBlank()
                || commandKey.length() > MAX_COMMAND_KEY_LENGTH || expectedDraftVersion < 0) {
            throw invalid("需要有效的 Idempotency-Key 和草稿版本。");
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
                    throw conflict("相同幂等键已用于不同卡片或草稿版本。");
                }
                return ProjectEventService.Change.unchanged(prior);
            }
            Artifact target = artifacts.get(ownerId, projectId, artifactId).artifact();
            CanvasItem canvasItem = canvasItems.requireArtifactItem(ownerId, projectId,
                    canvasItemId);
            if (!canvasItem.subjectId().equals(artifactId)) {
                throw invalid("运行目标必须是该媒体产物的画布卡片。");
            }
            Task occupying = tasks.findOccupyingDirectMediaTask(projectId, canvasItemId)
                    .orElse(null);
            if (occupying != null) return ProjectEventService.Change.unchanged(occupying);
            if (target.archivedAt() != null) throw conflict("已归档的卡片不能运行。");
            Task.Kind kind = switch (target.kind()) {
                case IMAGE -> Task.Kind.IMAGE_GENERATION;
                case VIDEO -> Task.Kind.VIDEO_GENERATION;
                default -> throw invalid("只能直接运行图片或视频卡片。");
            };
            MediaDraft draft = drafts.get(ownerId, projectId, canvasItem.id());
            if (draft.version() != expectedDraftVersion) throw conflict("草稿已变化，请检查保存状态后重试。");
            if (draft.prompt().isBlank()) throw invalid("运行前需要填写提示词。");
            if (kind == Task.Kind.VIDEO_GENERATION
                    && draft.videoInputMode() == MediaDraft.VideoInputMode.START_END
                    && (draft.imageInputs().isEmpty()
                            || draft.imageInputs().getFirst().role()
                                    != MediaDraft.InputRole.START_FRAME)) {
                throw invalid("首尾帧视频运行前需要选择首帧。");
            }
            if (kind == Task.Kind.VIDEO_GENERATION
                    && draft.videoInputMode() == MediaDraft.VideoInputMode.GENERAL_REFERENCE
                    && draft.imageInputs().isEmpty()) {
                throw invalid("全能参考视频运行前至少需要一张图片。");
            }
            MediaCapabilityBinding selected = capabilities.forDraft(draft.capabilityId(), kind);
            JsonNode configuredSettings = capabilities.settings(selected);
            Integer duration = draft.durationSeconds();
            if (kind == Task.Kind.VIDEO_GENERATION && duration == null
                    && configuredSettings.has("defaultDurationSeconds")) {
                duration = configuredSettings.path("defaultDurationSeconds").intValue();
            }
            if (kind == Task.Kind.VIDEO_GENERATION && duration == null) {
                throw invalid("视频运行前需要选择时长。");
            }
            int seconds = kind == Task.Kind.VIDEO_GENERATION ? duration : 0;
            MediaCapabilityBinding binding = capabilities.resolve(selected.capabilityId(), kind, seconds);
            if (!selected.equals(binding)) throw conflict("媒体配置已变化，请刷新后重试。");
            if (MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(binding.adapterId())) {
                throw invalid("本地图片处理能力只能从图片后处理入口使用。");
            }
            validateCapabilityInputs(kind, draft, capabilities.inputPolicy(binding));
            ImageGenerationParameters imageParameters = kind == Task.Kind.IMAGE_GENERATION
                    ? ImageGenerationParameters.parse(capabilities.parameters(binding, draft.parameters())) : null;
            VideoGenerationParameters videoParameters = kind == Task.Kind.VIDEO_GENERATION
                    ? VideoGenerationParameters.parse(capabilities.parameters(binding, draft.parameters())) : null;
            if (imageParameters != null) {
                var policy = capabilities.inputPolicy(binding);
                imageParameters.requireSupported(policy.supportedImageAspectRatios(),
                        policy.supportedImageResolutions(), policy.supportedImageQualities(),
                        policy.supportsTransparentBackground());
            }
            String renderedPrompt = renderPrompt(draft);
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
                if (configuredSettings.has("pricing")) {
                    input.set("mediaPricing", configuredSettings.get("pricing"));
                }
                input.put("providerConfigVersion", provider.configVersion());
                input.put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
                if (originHash != null) input.put("providerOriginSha256", originHash);
                if (kind == Task.Kind.VIDEO_GENERATION) input.put("durationSeconds", seconds);
                ObjectNode frozen = input.putObject("mediaInput");
                if (outputCard.selectedVersionId() == null) frozen.putNull("parentVersionId");
                else frozen.put("parentVersionId", outputCard.selectedVersionId().toString());
                frozen.put("mode", kind == Task.Kind.IMAGE_GENERATION
                        ? MediaDraft.VideoInputMode.GENERAL_REFERENCE.name()
                        : draft.videoInputMode().name());
                frozen.put("prompt", draft.prompt());
                frozen.put("renderedPrompt", renderedPrompt);
                frozen.set("parameters", imageParameters != null
                        ? imageParameters.toJson(mapper) : videoParameters.toJson(mapper));
                frozen.put("capabilityId", binding.capabilityId().toString());
                frozen.put("capabilityVersion", binding.capabilityVersion());
                if (kind == Task.Kind.VIDEO_GENERATION) frozen.put("durationSeconds", seconds);
                ArrayNode images = frozen.putArray("images");
                for (MediaDraft.ImageInput imageInput : draft.imageInputs()) {
                    ArtifactVersion image = artifacts.requireImageVersionForTask(ownerId, projectId,
                            imageInput.versionId());
                    ObjectNode imageNode = images.addObject();
                    imageNode.put("artifactId", image.artifactId().toString());
                    imageNode.put("versionId", image.id().toString());
                    imageNode.put("role", imageInput.role().name());
                    imageNode.put("order", imageInput.order());
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
    @Transactional
    public Task runImageOperation(UUID ownerId, UUID projectId, UUID artifactId,
            UUID canvasItemId, UUID sourceVersionId, long expectedCanvasItemVersion,
            ImageOperation operation, String instruction, UUID capabilityId,
            List<UUID> referenceVersionIds, UUID maskAssetId, JsonNode requestedParameters,
            String commandKey) {
        if (canvasItemId == null || sourceVersionId == null || operation == null
                || expectedCanvasItemVersion < 0 || commandKey == null || commandKey.isBlank()
                || commandKey.length() > MAX_COMMAND_KEY_LENGTH) {
            throw invalid("需要有效的图片、操作、卡片版本和 Idempotency-Key。");
        }
        ObjectNode operationParameters = normalizeOperationParameters(operation,
                requestedParameters);
        List<UUID> normalizedReferenceIds = referenceVersionIds == null
                ? List.of() : List.copyOf(referenceVersionIds);
        if (new HashSet<>(normalizedReferenceIds).size() != normalizedReferenceIds.size()) {
            throw invalid("智能编辑参考图不能重复。");
        }
        if (operation != ImageOperation.SMART_EDIT
                && (!normalizedReferenceIds.isEmpty() || maskAssetId != null)) {
            throw invalid("只有智能编辑支持额外参考图和蒙版。");
        }
        ArrayNode requestedReferenceIds = mapper.createArrayNode();
        normalizedReferenceIds.forEach(id -> requestedReferenceIds.add(id.toString()));
        String normalizedInstruction = instruction == null ? "" : instruction.trim();
        if (normalizedInstruction.length() > 4000) throw invalid("编辑说明不能超过 4000 字符。");
        if (operation.instructionRequired() && normalizedInstruction.isBlank()) {
            throw invalid("此 AI 图片处理需要填写处理说明。");
        }
        if (operation.cloud() && capabilityId == null) {
            throw invalid("AI 图片处理需要选择 OpenAI 或 Google 图片能力。");
        }
        if (!operation.cloud() && capabilityId != null) {
            throw invalid("本地图片处理不能指定云端图片能力。");
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
                    throw conflict("相同幂等键已用于不同的图片处理命令。");
                }
                return ProjectEventService.Change.unchanged(prior);
            }
            Artifact target = artifacts.get(ownerId, projectId, artifactId).artifact();
            if (target.kind() != Artifact.Kind.IMAGE || target.archivedAt() != null) {
                throw invalid("图片处理只能用于未归档的图片卡片。");
            }
            CanvasItem card = canvasItems.requireArtifactItem(ownerId, projectId, canvasItemId);
            if (!card.subjectId().equals(artifactId)
                    || !sourceVersionId.equals(card.selectedVersionId())) {
                throw conflict("卡片当前图片已变化，请基于最新图片重新处理。");
            }
            if (card.version() != expectedCanvasItemVersion) {
                throw conflict("卡片已变化，请刷新后重试。");
            }
            ArtifactVersion source = artifacts.requireImageVersionForTask(ownerId, projectId,
                    sourceVersionId);
            if (!source.artifactId().equals(artifactId)) {
                throw invalid("处理来源必须是当前卡片所属图片的版本。");
            }
            MediaCapabilityBinding binding = operation.cloud()
                    ? cloudImageBinding(capabilityId) : capabilities.resolve(
                            LOCAL_IMAGE_CAPABILITY_ID, Task.Kind.IMAGE_GENERATION, 0);
            var inputPolicy = capabilities.inputPolicy(binding);
            if (1 + normalizedReferenceIds.size() > inputPolicy.maxReferenceImages()) {
                throw invalid("所选 AI 图片能力无法接收这么多参考图。");
            }
            List<ArtifactVersion> referenceVersions = new ArrayList<>(
                    normalizedReferenceIds.size());
            for (UUID referenceVersionId : normalizedReferenceIds) {
                if (referenceVersionId.equals(sourceVersionId)) {
                    throw invalid("来源图片已经是智能编辑的第一张输入，无需重复引用。");
                }
                referenceVersions.add(artifacts.requireImageVersionForTask(ownerId, projectId,
                        referenceVersionId));
            }
            if (maskAssetId != null) {
                if (!inputPolicy.supportsImageMask()) {
                    throw invalid("所选 AI 图片能力不支持显式蒙版编辑。");
                }
                validateImageMask(ownerId, projectId, maskAssetId);
            }
            boolean transparentOutput = requiresTransparentOutput(operation,
                    operationParameters);
            if (transparentOutput && !inputPolicy.supportsTransparentBackground()) {
                throw invalid("所选 AI 图片能力不支持透明背景输出。");
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
        if (capabilityId == null) throw invalid("AI 图片处理需要选择 OpenAI 或 Google 图片能力。");
        MediaCapabilityBinding binding = capabilities.resolve(capabilityId,
                Task.Kind.IMAGE_GENERATION, 0);
        if (!MediaAdapterRegistry.OPENAI_GPT_IMAGE_2.equals(binding.adapterId())
                && !MediaAdapterRegistry.GOOGLE_NANO_BANANA_2.equals(binding.adapterId())) {
            throw invalid("AI 图片处理仅支持 OpenAI 或 Google 图片能力。");
        }
        if (capabilities.inputPolicy(binding).maxReferenceImages() < 1) {
            throw invalid("所选 AI 图片能力不支持参考图编辑。");
        }
        return binding;
    }

    ObjectNode normalizeOperationParameters(ImageOperation operation, JsonNode supplied) {
        JsonNode source = supplied == null || supplied.isNull()
                ? mapper.createObjectNode() : supplied;
        if (!source.isObject()) throw invalid("图片处理参数必须为对象。");
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
                    throw invalid("打光预设不受支持。");
                }
                if (brightness < MIN_RELIGHT_BRIGHTNESS
                        || brightness > MAX_RELIGHT_BRIGHTNESS) {
                    throw invalid("打光亮度必须位于 -100 到 100 之间。");
                }
                if (colorTemperature < MIN_RELIGHT_COLOR_TEMPERATURE
                        || colorTemperature > MAX_RELIGHT_COLOR_TEMPERATURE) {
                    throw invalid("色温必须位于 2000K 到 10000K 之间。");
                }
                if (lightX < 0 || lightX > 1 || lightY < 0 || lightY > 1) {
                    throw invalid("光源位置必须位于图片范围内。");
                }
                result.put("lightingPreset", preset);
                result.put("brightness", brightness);
                result.put("colorTemperature", colorTemperature);
                result.put("lightX", lightX);
                result.put("lightY", lightY);
            }
            case UPSCALE -> {
                int scale = source.path("scale").asInt(2);
                if (scale != 2 && scale != 4) throw invalid("放大倍数只能为 2 或 4。");
                result.put("scale", scale);
            }
            case CROP -> {
                double x = source.path("x").asDouble(0);
                double y = source.path("y").asDouble(0);
                double width = source.path("width").asDouble(1);
                double height = source.path("height").asDouble(1);
                if (x < 0 || y < 0 || width <= 0 || height <= 0
                        || x + width > 1.000001 || y + height > 1.000001) {
                    throw invalid("裁剪区域必须位于图片范围内。");
                }
                result.put("x", x); result.put("y", y);
                result.put("width", width); result.put("height", height);
            }
            case ROTATE -> {
                int turns = source.path("quarterTurns").asInt(1);
                if (turns < 1 || turns > 3) throw invalid("旋转只支持 90、180 或 270 度。");
                result.put("quarterTurns", turns);
            }
            case OUTPAINT -> {
                String ratio = source.path("aspectRatio").asText("");
                if (!ImageGenerationParameters.ASPECT_RATIOS.contains(ratio)
                        || ImageGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio)) {
                    throw invalid("扩图需要选择明确的目标画幅。");
                }
                result.put("aspectRatio", ratio);
            }
            case THREE_VIEW -> {
                String ratio = source.path("aspectRatio").asText("");
                if (!ImageGenerationParameters.ASPECT_RATIOS.contains(ratio)
                        || ImageGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio)) {
                    throw invalid("三视图需要选择明确的输出画幅。");
                }
                String type = source.path("threeViewType").asText("");
                if (!THREE_VIEW_RESULT_LABELS.containsKey(type)) {
                    throw invalid("三视图类型不受支持。");
                }
                result.put("aspectRatio", ratio);
                result.put("threeViewType", type);
            }
            case LAYER_SPLIT -> {
                String target = source.path("layerTarget").asText("FOREGROUND");
                if (!LAYER_RESULT_LABELS.containsKey(target)) throw invalid("图层输出类型不受支持。");
                result.put("layerTarget", target);
            }
            case VIEW_ANGLE -> {
                String angle = source.path("viewAngle").asText("FRONT");
                if (!VIEW_ANGLES.contains(angle)) throw invalid("目标视角不受支持。");
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
            default -> throw invalid("目标视角不受支持。");
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
            default -> throw invalid("三视图类型不受支持。");
        };
    }

    /** A provider mask is an immutable, project-scoped PNG with a real alpha channel. */
    private void validateImageMask(UUID ownerId, UUID projectId, UUID maskAssetId) {
        AssetService.AssetFile file = assets.get(ownerId, projectId, maskAssetId);
        Asset mask = file.asset();
        if (mask.mediaKind() != Asset.MediaKind.IMAGE
                || !"image/png".equals(mask.contentType())
                || mask.byteSize() < 1 || mask.byteSize() > MAX_OPENAI_MASK_BYTES) {
            throw invalid("智能编辑蒙版必须是小于 4 MiB 的 PNG 图片。");
        }
        try {
            BufferedImage decoded = ImageIO.read(file.path().toFile());
            if (decoded == null || !decoded.getColorModel().hasAlpha()) {
                throw invalid("智能编辑蒙版必须包含透明通道。");
            }
        } catch (IOException unreadable) {
            throw invalid("智能编辑蒙版无法读取。");
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
            default -> throw invalid("打光预设不受支持。");
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
                throw invalid("提示词图片标签已损坏，请重新保存草稿。");
            }
            MediaDraft.PromptMention mention = draft.mentions().get(mentionIndex++);
            rendered.append('@').append(switch (mention.role()) {
                case START_FRAME -> "Start Frame";
                case END_FRAME -> "End Frame";
                case REFERENCE -> "Image " + referenceNumber(draft, mention);
            });
        }
        if (mentionIndex != draft.mentions().size()) {
            throw invalid("提示词图片标签已损坏，请重新保存草稿。");
        }
        return rendered.toString();
    }

    private int referenceNumber(MediaDraft draft, MediaDraft.PromptMention mention) {
        for (int index = 0; index < draft.imageInputs().size(); index++) {
            MediaDraft.ImageInput input = draft.imageInputs().get(index);
            if (input.versionId().equals(mention.versionId()) && input.role() == mention.role()) {
                return index + 1;
            }
        }
        throw invalid("提示词图片标签没有对应的图片输入。");
    }

    /** Only queued direct work is guaranteed never to have reached the provider. */
    @Transactional
    public Task cancelQueued(UUID ownerId, UUID projectId, UUID taskId) {
        return events.recordChange(ownerId, projectId, () -> {
            Task current = tasks.find(ownerId, projectId, taskId)
                    .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                            "RESOURCE_NOT_FOUND", "任务不存在", "找不到该任务。", false));
            if (current.runId() != null) {
                throw invalid("只能通过此入口取消直接媒体任务。");
            }
            if (current.status() == Task.Status.CANCELED) {
                return ProjectEventService.Change.unchanged(current);
            }
            if (!tasks.cancelQueuedDirect(projectId, taskId, clock.instant())) {
                throw conflict("任务已开始提交，请从任务状态查看取消选项。");
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
            throw invalid("任务列表必须属于该媒体产物的画布卡片。");
        }
        return tasks.listDirectForCanvasItem(ownerId, projectId, canvasItemId);
    }

    @Transactional(readOnly = true)
    public TaskRepository.QueueStatus queueStatus(UUID ownerId, UUID projectId, UUID taskId) {
        Task task = tasks.find(ownerId, projectId, taskId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND", "任务不存在", "找不到该任务。", false));
        if (task.runId() != null) {
            throw invalid("此任务不是直接媒体任务。");
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

    private void validateCapabilityInputs(Task.Kind kind, MediaDraft draft,
            dev.agenvas.provider.domain.MediaAdapterRegistry.Declaration policy) {
        if (kind == Task.Kind.IMAGE_GENERATION) {
            if (draft.imageInputs().size() > policy.maxReferenceImages()) {
                throw invalid("所选图片能力最多接受 " + policy.maxReferenceImages()
                        + " 张参考图。");
            }
            return;
        }
        String mode = draft.videoInputMode().name();
        if (!policy.supportedVideoInputModes().contains(mode)) {
            throw invalid("所选视频能力不支持当前图片输入模式。");
        }
        if (!policy.supportsEndFrame() && draft.imageInputs().stream().anyMatch(input ->
                input.role() == MediaDraft.InputRole.END_FRAME)) {
            throw invalid("所选视频能力不支持尾帧。");
        }
        if (draft.imageInputs().size() > policy.maxReferenceImages()) {
            throw invalid("所选视频能力最多接受 " + policy.maxReferenceImages()
                    + " 张图片输入。");
        }
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "媒体任务输入无效", detail, false);
    }

    private static ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "DIRECT_MEDIA_CONFLICT",
                "媒体任务冲突", detail, true);
    }
}
