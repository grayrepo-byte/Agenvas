package dev.agenvas.artifact.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.task.domain.Task;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Persists generation input separately from immutable media results. */
@Service
public class MediaDraftService {
    private static final int MAX_PROMPT_LENGTH = 20_000;
    private static final int MIN_VIDEO_SECONDS = 1;
    private static final int MAX_VIDEO_SECONDS = 30;
    private static final int MAX_IMAGE_INPUTS = 14;
    // Each marker maps positionally to one exact-version structured prompt mention.
    private static final char MENTION_MARKER = '\uFFFC';
    private static final String COLOR_PATTERN = "^#[0-9A-F]{6}$";
    private static final List<String> INPUT_COLORS = List.of(
            "#7C3AED", "#0EA5E9", "#F97316", "#10B981",
            "#EC4899", "#EAB308", "#6366F1", "#14B8A6",
            "#DC2626", "#0891B2", "#9333EA", "#65A30D",
            "#C2410C", "#4F46E5");

    private final ProjectService projects;
    private final ArtifactService artifactService;
    private final ArtifactRepository artifacts;
    private final CanvasItemQueryService canvasItems;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MediaCapabilityService capabilities;

    public MediaDraftService(ProjectService projects, ArtifactService artifactService,
            ArtifactRepository artifacts, CanvasItemQueryService canvasItems,
            ProjectEventService events, ObjectMapper mapper, Clock clock,
            @Lazy MediaCapabilityService capabilities) {
        this.projects = projects;
        this.artifactService = artifactService;
        this.artifacts = artifacts;
        this.canvasItems = canvasItems;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.capabilities = capabilities;
    }

    @Transactional(readOnly = true)
    public MediaDraft get(UUID ownerId, UUID projectId, UUID canvasItemId) {
        requireMediaCanvas(ownerId, projectId, canvasItemId);
        return artifacts.findMediaDraft(projectId, canvasItemId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND", "草稿不存在", "该媒体产物没有工作草稿。", false));
    }

    @Transactional
    public MediaDraft save(UUID ownerId, UUID projectId, UUID canvasItemId,
            long expectedVersion, String prompt, JsonNode parameters,
            Integer durationSeconds, UUID capabilityId,
            MediaDraft.VideoInputMode requestedMode, List<SaveMediaInput> requestedInputs,
            List<MediaDraft.PromptMention> requestedMentions) {
        projects.requireActiveProject(ownerId, projectId);
        Artifact.Kind kind = requireMediaCanvas(ownerId, projectId, canvasItemId).kind();
        if (expectedVersion < 0 || prompt == null || prompt.length() > MAX_PROMPT_LENGTH) {
            throw invalid("草稿版本或提示词无效。");
        }
        JsonNode normalizedParameters = parameters == null
                ? mapper.createObjectNode() : parameters;
        if (!normalizedParameters.isObject()) {
            throw invalid("媒体参数必须是对象。");
        }
        Task.Kind taskKind = Task.Kind.valueOf(kind.name() + "_GENERATION");
        var definition = capabilityId == null ? null : capabilities.runningHubDefinition(capabilities.forDraft(capabilityId, taskKind));
        boolean dynamic = definition != null;
        if (dynamic) definition.values(mapper, normalizedParameters, prompt, durationSeconds, false);
        else if (kind == Artifact.Kind.IMAGE) ImageGenerationParameters.parse(normalizedParameters);
        else if (kind == Artifact.Kind.VIDEO) VideoGenerationParameters.parse(normalizedParameters);
        else dev.agenvas.artifact.domain.AudioGenerationParameters.parse(normalizedParameters);
        List<SaveMediaInput> inputCommands = requestedInputs == null
                ? List.of() : List.copyOf(requestedInputs);
        List<MediaDraft.PromptMention> mentions = requestedMentions == null
                ? List.of() : List.copyOf(requestedMentions);
        if (prompt.chars().filter(character -> character == MENTION_MARKER).count()
                != mentions.size()) {
            throw invalid("提示词中的图片标签与结构化引用不一致。");
        }
        MediaDraft persisted = artifacts.findMediaDraft(projectId, canvasItemId)
                .orElseThrow(() -> new IllegalStateException("Media draft missing"));
        if (inputCommands.size() > MAX_IMAGE_INPUTS) {
            throw invalid("单张卡片最多保存 14 个媒体输入。");
        }
        MediaDraft.VideoInputMode mode = kind != Artifact.Kind.VIDEO ? null
                : requestedMode == null
                        ? inputCommands.isEmpty() ? MediaDraft.VideoInputMode.TEXT
                                : MediaDraft.VideoInputMode.GENERAL_REFERENCE
                        : requestedMode;
        if (kind != Artifact.Kind.VIDEO && durationSeconds != null) {
            throw invalid("图片草稿不能指定视频时长。");
        }
        if (kind == Artifact.Kind.VIDEO && durationSeconds != null &&
                (durationSeconds < MIN_VIDEO_SECONDS || durationSeconds > (dynamic ? MediaAdapterRegistry.RUNNINGHUB_MAX_VIDEO_SECONDS : MAX_VIDEO_SECONDS))) {
            throw invalid(dynamic ? "视频时长必须为 1–60 秒的整数。" : "视频时长必须为 1–30 秒的整数。");
        }
        if (!dynamic && mode == MediaDraft.VideoInputMode.TEXT && !inputCommands.isEmpty()) {
            throw invalid("纯文本视频模式不能保存图片输入。");
        }
        Set<UUID> seen = new HashSet<>();
        List<MediaDraft.MediaInput> inputs = new ArrayList<>();
        for (int order = 0; order < inputCommands.size(); order++) {
            SaveMediaInput command = inputCommands.get(order);
            if (command == null || command.versionId() == null || command.role() == null
                    || command.color() == null || !command.color().matches(COLOR_PATTERN)
                    || !seen.add(command.versionId())) {
                throw invalid("图片输入必须完整、颜色有效并按精确版本去重。");
            }
            ArtifactRepository.VersionTarget target = artifacts
                    .findVersionTarget(projectId, command.versionId())
                    .orElseThrow(() -> invalid("输入图片版本不存在于本项目。"));
            boolean alreadyReferenced = persisted.mediaInputs().stream()
                    .anyMatch(input -> input.versionId().equals(command.versionId()));
            if (target.kind() != (mediaKind(command.role()))
                    || artifactService.get(ownerId, projectId, target.artifactId())
                            .artifact().archivedAt() != null && !alreadyReferenced) {
                throw invalid("图片输入必须是本项目中未归档图片的精确版本。");
            }
            if (dynamic) {
                if (!Set.of(MediaDraft.InputRole.REFERENCE, MediaDraft.InputRole.AUDIO_REFERENCE, MediaDraft.InputRole.VIDEO_REFERENCE).contains(command.role())) throw invalid("动态素材只能使用图片、音频或视频参考角色。");
            } else validateRole(kind, mode, command.role(), order, inputCommands.size());
            inputs.add(new MediaDraft.MediaInput(command.versionId(), target.artifactId(),
                    command.role(), order, command.color(), List.of(
                            new MediaDraft.InputSource(UUID.randomUUID(),
                                    MediaDraft.SourceType.MANUAL, null))));
        }
        if (dynamic) validateSlots(definition, normalizedParameters, inputs, false);
        for (MediaDraft.PromptMention mention : mentions) {
            if (mention == null || mention.versionId() == null || mention.role() == null
                    || inputs.stream().noneMatch(input -> input.versionId().equals(
                                    mention.versionId())
                            && input.role() == mention.role())) {
                throw invalid("图片标签只能引用当前图片栏中的精确版本和角色。");
            }
        }
        return events.recordChange(ownerId, projectId, () -> {
            MediaDraft before = artifacts.findMediaDraft(projectId, canvasItemId)
                    .orElseThrow(() -> new IllegalStateException("Media draft missing"));
            List<MediaDraft.MediaInput> effectiveInputs = mergeInputs(
                    before.mediaInputs(), inputs);
            MediaDraft update = new MediaDraft(projectId, canvasItemId, prompt,
                    normalizedParameters.deepCopy(), durationSeconds, capabilityId,
                    mode, effectiveInputs, mentions,
                    before.displayMode(),
                    expectedVersion + 1, before.createdAt(), clock.instant());
            if (!artifacts.updateMediaDraft(update, expectedVersion)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "草稿版本冲突", "草稿已被其他操作修改；请保留本地输入并重新核对。", true);
            }
            artifacts.replaceMediaInputs(projectId, canvasItemId, effectiveInputs,
                    update.updatedAt());
            ObjectNode payload = mapper.createObjectNode();
            payload.put("canvasItemId", canvasItemId.toString());
            payload.put("draftVersion", update.version());
            return ProjectEventService.Change.changed(update,
                    new ProjectEventService.EventDraft("media.draft.changed", 1,
                            canvasItemId, update.version(), payload));
        }).value();
    }

    /** Replaces the complete working draft from one immutable result's frozen input. */
    @Transactional
    public MediaDraft restoreVersionInputs(UUID ownerId, UUID projectId, UUID canvasItemId,
            UUID versionId, long expectedDraftVersion) {
        Artifact artifact = requireMediaCanvas(ownerId, projectId, canvasItemId);
        var version = artifactService.requireVersion(ownerId, projectId, artifact.id(), versionId);
        JsonNode frozen = version.frozenInput();
        if (frozen == null || !frozen.isObject()) {
            throw invalid("该版本没有可恢复的冻结生成输入。");
        }
        List<SaveMediaInput> inputs = new ArrayList<>();
        JsonNode frozenImages = frozen.path("images");
        if (!frozenImages.isArray()) throw invalid("该版本的冻结图片输入无效。");
        int order = 0;
        for (JsonNode image : frozenImages) {
            try {
                inputs.add(new SaveMediaInput(UUID.fromString(image.path("versionId").asText()),
                        MediaDraft.InputRole.valueOf(image.path("role").asText()),
                        INPUT_COLORS.get(order % INPUT_COLORS.size())));
            } catch (IllegalArgumentException exception) {
                throw invalid("该版本的冻结图片输入无效。");
            }
            order++;
        }
        for (JsonNode audio : frozen.path("audios")) {
            try {
                inputs.add(new SaveMediaInput(UUID.fromString(audio.path("versionId").asText()),
                        MediaDraft.InputRole.AUDIO_REFERENCE, INPUT_COLORS.get(order++ % INPUT_COLORS.size())));
            } catch (IllegalArgumentException exception) { throw invalid("冻结音频输入无效。"); }
        }
        for (JsonNode video : frozen.path("videos")) {
            try { inputs.add(new SaveMediaInput(UUID.fromString(video.path("versionId").asText()),
                    MediaDraft.InputRole.VIDEO_REFERENCE, INPUT_COLORS.get(order++ % INPUT_COLORS.size()))); }
            catch (IllegalArgumentException exception) { throw invalid("冻结视频输入无效。"); }
        }
        List<MediaDraft.PromptMention> mentions = new ArrayList<>();
        JsonNode frozenMentions = frozen.path("mentions");
        if (frozenMentions.isArray()) {
            for (JsonNode mention : frozenMentions) {
                try {
                    mentions.add(new MediaDraft.PromptMention(
                            UUID.fromString(mention.path("versionId").asText()),
                            MediaDraft.InputRole.valueOf(mention.path("role").asText())));
                } catch (IllegalArgumentException exception) {
                    throw invalid("该版本的冻结图片标签无效。");
                }
            }
        }
        MediaDraft.VideoInputMode mode = artifact.kind() != Artifact.Kind.VIDEO ? null
                : parseMode(frozen.path("mode").asText());
        UUID capabilityId;
        try {
            capabilityId = UUID.fromString(frozen.path("capabilityId").asText());
        } catch (IllegalArgumentException exception) {
            throw invalid("该版本的冻结能力输入无效。");
        }
        Integer durationSeconds = artifact.kind() == Artifact.Kind.VIDEO
                && frozen.path("durationSeconds").canConvertToInt()
                ? frozen.path("durationSeconds").intValue() : null;
        return save(ownerId, projectId, canvasItemId, expectedDraftVersion,
                frozen.path("prompt").asText(""), frozen.path("parameters").deepCopy(),
                durationSeconds, capabilityId, mode, inputs, mentions);
    }

    private MediaDraft.VideoInputMode parseMode(String value) {
        try {
            return MediaDraft.VideoInputMode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("该版本的冻结视频模式无效。");
        }
    }

    /** The enclosing project event transaction records why the visible card face changed. */
    public void setDisplayModeWithinChange(UUID projectId, UUID canvasItemId,
            MediaDraft.DisplayMode mode) {
        artifacts.setMediaDraftDisplayMode(projectId, canvasItemId, mode, clock.instant());
    }

    /** Called from the Canvas placement transaction after the item row is inserted. */
    public void initializeWithinChange(UUID projectId, UUID canvasItemId, boolean hasResult) {
        artifacts.createMediaDraft(projectId, canvasItemId, "",
                hasResult ? MediaDraft.DisplayMode.RESULT : MediaDraft.DisplayMode.DRAFT,
                clock.instant());
    }

    /** Copies one persisted branch into a new card; every copied input becomes manual-only. */
    public MediaDraft duplicateWithinChange(UUID ownerId, UUID projectId,
            UUID sourceCanvasItemId, UUID targetCanvasItemId,
            long expectedSourceDraftVersion) {
        MediaDraft source = get(ownerId, projectId, sourceCanvasItemId);
        if (source.version() != expectedSourceDraftVersion) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "草稿版本冲突", "复制前来源草稿已变化；请先保存并重新核对。", true);
        }
        Instant now = clock.instant();
        artifacts.createMediaDraft(projectId, targetCanvasItemId, "",
                source.displayMode(), now);
        List<MediaDraft.MediaInput> inputs = source.mediaInputs().stream()
                .map(input -> new MediaDraft.MediaInput(input.versionId(), input.artifactId(),
                        input.role(), input.order(), input.color(), List.of(
                                new MediaDraft.InputSource(UUID.randomUUID(),
                                        MediaDraft.SourceType.MANUAL, null))))
                .toList();
        MediaDraft duplicate = new MediaDraft(projectId, targetCanvasItemId,
                source.prompt(), source.parameters().deepCopy(), source.durationSeconds(),
                source.capabilityId(), source.videoInputMode(), inputs,
                List.copyOf(source.mentions()), source.displayMode(), 1, now, now);
        if (!artifacts.updateMediaDraft(duplicate, 0)) {
            throw new IllegalStateException("New duplicate media draft update failed");
        }
        artifacts.replaceMediaInputs(projectId, targetCanvasItemId, inputs, now);
        return duplicate;
    }

    /** A batch result copies the accepted draft, never the user's newer working draft. */
    public void initializeFrozenWithinChange(UUID projectId, UUID canvasItemId, Artifact.Kind kind, JsonNode frozen) {
        initializeWithinChange(projectId, canvasItemId, false);
        Instant now = clock.instant();
        List<MediaDraft.MediaInput> inputs = new ArrayList<>();
        for (String group : List.of("images", "audios", "videos")) for (JsonNode input : frozen.path(group)) {
            int order = inputs.size();
            inputs.add(new MediaDraft.MediaInput(UUID.fromString(input.path("versionId").asText()),
                    UUID.fromString(input.path("artifactId").asText()), MediaDraft.InputRole.valueOf(input.path("role").asText()),
                    order, INPUT_COLORS.get(order % INPUT_COLORS.size()), List.of(new MediaDraft.InputSource(UUID.randomUUID(), MediaDraft.SourceType.MANUAL, null))));
        }
        List<MediaDraft.PromptMention> mentions = new ArrayList<>();
        for (JsonNode mention : frozen.path("mentions")) mentions.add(new MediaDraft.PromptMention(UUID.fromString(mention.path("versionId").asText()), MediaDraft.InputRole.valueOf(mention.path("role").asText())));
        MediaDraft draft = new MediaDraft(projectId, canvasItemId, frozen.path("prompt").asText(""),
                frozen.path("parameters").deepCopy(), kind == Artifact.Kind.VIDEO && frozen.path("durationSeconds").asInt() > 0 ? frozen.path("durationSeconds").asInt() : null,
                UUID.fromString(frozen.path("capabilityId").asText()), kind == Artifact.Kind.VIDEO ? parseMode(frozen.path("mode").asText()) : null,
                List.copyOf(inputs), List.copyOf(mentions), MediaDraft.DisplayMode.DRAFT, 1, now, now);
        if (!artifacts.updateMediaDraft(draft, 0)) throw new IllegalStateException("New batch draft update failed");
        artifacts.replaceMediaInputs(projectId, canvasItemId, inputs, now);
    }

    /** Adds one connection source without duplicating an already selected exact version. */
    public MediaDraft addConnectionInputWithinChange(UUID ownerId, UUID projectId,
            UUID canvasItemId, long expectedVersion, UUID imageVersionId, UUID connectionId) {
        Artifact.Kind kind = requireMediaCanvas(ownerId, projectId, canvasItemId).kind();
        ArtifactRepository.VersionTarget target = artifacts
                .findVersionTarget(projectId, imageVersionId)
                .orElseThrow(() -> invalid("连线固定的图片版本不存在于本项目。"));
        if (target.kind() != Artifact.Kind.IMAGE && target.kind() != Artifact.Kind.AUDIO
                || artifactService.get(ownerId, projectId, target.artifactId())
                        .artifact().archivedAt() != null) {
            throw invalid("连线来源必须是未归档图片的精确版本。");
        }
        MediaDraft before = get(ownerId, projectId, canvasItemId);
        if (before.version() != expectedVersion) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "草稿版本冲突", "建立连线前目标草稿已变化。", true);
        }
        List<MediaDraft.MediaInput> inputs = new ArrayList<>(before.mediaInputs());
        int existingIndex = java.util.stream.IntStream.range(0, inputs.size())
                .filter(index -> inputs.get(index).versionId().equals(imageVersionId))
                .findFirst().orElse(-1);
        if (existingIndex >= 0) {
            MediaDraft.MediaInput existing = inputs.get(existingIndex);
            List<MediaDraft.InputSource> sources = new ArrayList<>(existing.sources());
            sources.add(new MediaDraft.InputSource(UUID.randomUUID(),
                    MediaDraft.SourceType.CONNECTION, connectionId));
            inputs.set(existingIndex, new MediaDraft.MediaInput(existing.versionId(),
                    existing.artifactId(), existing.role(), existing.order(), existing.color(),
                    List.copyOf(sources)));
        } else {
            if (inputs.size() >= MAX_IMAGE_INPUTS) {
                throw invalid("图片输入已达到当前卡片上限。");
            }
            if (target.kind() == Artifact.Kind.AUDIO && kind != Artifact.Kind.AUDIO && (kind != Artifact.Kind.VIDEO
                    || before.videoInputMode() == MediaDraft.VideoInputMode.START_END))
                throw invalid("音频只能连入视频全能参考模式。");
            MediaDraft.InputRole role = target.kind() == Artifact.Kind.AUDIO
                    ? MediaDraft.InputRole.AUDIO_REFERENCE : nextConnectionRole(kind, before, inputs);
            String color = INPUT_COLORS.stream()
                    .filter(candidate -> inputs.stream().noneMatch(input ->
                            input.color().equals(candidate)))
                    .findFirst().orElse(INPUT_COLORS.get(inputs.size() % INPUT_COLORS.size()));
            inputs.add(new MediaDraft.MediaInput(imageVersionId, target.artifactId(), role,
                    inputs.size(), color, List.of(new MediaDraft.InputSource(UUID.randomUUID(),
                            MediaDraft.SourceType.CONNECTION, connectionId))));
        }
        return replaceInputsWithinChange(ownerId, before, inputs, before.mentions());
    }

    /** Removes only one connection reason; manual or other connection sources keep the input. */
    public MediaDraft removeConnectionInputWithinChange(UUID ownerId, UUID projectId,
            UUID canvasItemId, long expectedVersion, UUID connectionId) {
        MediaDraft before = get(ownerId, projectId, canvasItemId);
        if (before.version() != expectedVersion) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "草稿版本冲突", "断开连线前目标草稿已变化。", true);
        }
        List<MediaDraft.MediaInput> inputs = new ArrayList<>();
        Set<UUID> removedVersions = new HashSet<>();
        boolean found = false;
        for (MediaDraft.MediaInput input : before.mediaInputs()) {
            List<MediaDraft.InputSource> sources = input.sources().stream()
                    .filter(source -> !connectionId.equals(source.connectionId()))
                    .toList();
            if (sources.size() != input.sources().size()) found = true;
            if (sources.isEmpty()) {
                removedVersions.add(input.versionId());
            } else {
                inputs.add(new MediaDraft.MediaInput(input.versionId(), input.artifactId(),
                        input.role(), inputs.size(), input.color(), sources));
            }
        }
        if (!found) throw invalid("连线没有对应的媒体输入来源。");
        List<MediaDraft.PromptMention> mentions = before.mentions().stream()
                .filter(mention -> !removedVersions.contains(mention.versionId()))
                .toList();
        return replaceInputsWithinChange(ownerId, before, inputs, mentions);
    }

    /** Removes one exact-version input and every structured mention bound to it. */
    public MediaDraft removeMediaInputWithinChange(UUID ownerId, UUID projectId,
            UUID canvasItemId, long expectedVersion, UUID imageVersionId) {
        MediaDraft before = get(ownerId, projectId, canvasItemId);
        if (before.version() != expectedVersion) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "草稿版本冲突", "移除图片前目标草稿已变化。", true);
        }
        if (before.mediaInputs().stream().noneMatch(input ->
                input.versionId().equals(imageVersionId))) {
            throw invalid("媒体草稿中没有该图片输入。");
        }
        List<MediaDraft.MediaInput> inputs = new ArrayList<>();
        for (MediaDraft.MediaInput input : before.mediaInputs()) {
            if (input.versionId().equals(imageVersionId)) continue;
            inputs.add(new MediaDraft.MediaInput(input.versionId(), input.artifactId(),
                    input.role(), inputs.size(), input.color(), input.sources()));
        }
        List<MediaDraft.PromptMention> mentions = before.mentions().stream()
                .filter(mention -> !mention.versionId().equals(imageVersionId))
                .toList();
        return replaceInputsWithinChange(ownerId, before, inputs, mentions);
    }

    private MediaDraft replaceInputsWithinChange(UUID ownerId, MediaDraft before,
            List<MediaDraft.MediaInput> inputs, List<MediaDraft.PromptMention> mentions) {
        Instant now = clock.instant();
        String prompt = pruneRemovedMentions(before.prompt(), before.mentions(), mentions);
        MediaDraft.VideoInputMode mode = before.videoInputMode();
        if (mode != null && inputs.isEmpty()) mode = MediaDraft.VideoInputMode.TEXT;
        else if (mode == MediaDraft.VideoInputMode.TEXT && !inputs.isEmpty()) {
            mode = MediaDraft.VideoInputMode.GENERAL_REFERENCE;
        }
        MediaDraft update = new MediaDraft(before.projectId(), before.canvasItemId(),
                prompt, before.parameters(), before.durationSeconds(),
                before.capabilityId(), mode, List.copyOf(inputs),
                List.copyOf(mentions), before.displayMode(), before.version() + 1,
                before.createdAt(), now);
        if (!artifacts.updateMediaDraft(update, before.version())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "草稿版本冲突", "媒体输入已被其他操作修改。", true);
        }
        artifacts.replaceMediaInputs(update.projectId(), update.canvasItemId(), inputs, now);
        return update;
    }

    private String pruneRemovedMentions(String prompt,
            List<MediaDraft.PromptMention> beforeMentions,
            List<MediaDraft.PromptMention> remainingMentions) {
        StringBuilder result = new StringBuilder(prompt.length());
        int mentionIndex = 0;
        List<MediaDraft.PromptMention> unmatched = new ArrayList<>(remainingMentions);
        for (int index = 0; index < prompt.length(); index++) {
            char character = prompt.charAt(index);
            if (character != MENTION_MARKER) {
                result.append(character);
                continue;
            }
            MediaDraft.PromptMention mention = mentionIndex < beforeMentions.size()
                    ? beforeMentions.get(mentionIndex) : null;
            mentionIndex++;
            if (mention != null && unmatched.remove(mention)) result.append(MENTION_MARKER);
        }
        return result.toString();
    }

    private MediaDraft.InputRole nextConnectionRole(Artifact.Kind kind, MediaDraft draft,
            List<MediaDraft.MediaInput> inputs) {
        if (kind == Artifact.Kind.IMAGE || kind == Artifact.Kind.AUDIO
                || draft.videoInputMode() == MediaDraft.VideoInputMode.GENERAL_REFERENCE) {
            return MediaDraft.InputRole.REFERENCE;
        }
        if (draft.videoInputMode() == MediaDraft.VideoInputMode.START_END) {
            if (inputs.stream().noneMatch(input -> input.role()
                    == MediaDraft.InputRole.START_FRAME)) {
                return MediaDraft.InputRole.START_FRAME;
            }
            if (inputs.stream().noneMatch(input -> input.role()
                    == MediaDraft.InputRole.END_FRAME)) {
                return MediaDraft.InputRole.END_FRAME;
            }
            throw invalid("首尾帧模式已经包含首帧和尾帧。");
        }
        if (draft.videoInputMode() == MediaDraft.VideoInputMode.TEXT && inputs.isEmpty()) {
            return MediaDraft.InputRole.REFERENCE;
        }
        throw invalid("当前视频模式不能建立图片输入连线。");
    }

    private Artifact requireMediaCanvas(UUID ownerId, UUID projectId, UUID canvasItemId) {
        CanvasItem item = canvasItems.requireArtifactItem(ownerId, projectId, canvasItemId);
        Artifact artifact = artifactService.get(ownerId, projectId, item.subjectId()).artifact();
        Artifact.Kind kind = artifact.kind();
        if (kind == Artifact.Kind.TEXT) {
            throw invalid("只有图片和视频产物有媒体草稿。");
        }
        return artifact;
    }

    public static Artifact.Kind mediaKind(MediaDraft.InputRole role) {
        return switch (role) { case AUDIO_REFERENCE -> Artifact.Kind.AUDIO; case VIDEO_REFERENCE -> Artifact.Kind.VIDEO; default -> Artifact.Kind.IMAGE; };
    }

    /** Named slots and the deduplicated exact-version rows must agree on identity and media kind. */
    public static void validateSlots(RunningHubDefinition definition,
            JsonNode parameters, List<MediaDraft.MediaInput> inputs, boolean executing) {
        JsonNode values = parameters.path(RunningHubDefinition.VALUES_PROPERTY);
        Set<UUID> used = new HashSet<>();
        for (var field : definition.fields()) if (field.media() && values.hasNonNull(field.key())) {
            UUID id = UUID.fromString(values.path(field.key()).asText());
            if (inputs.stream().noneMatch(input -> input.versionId().equals(id)
                    && mediaKind(input.role()).name().equals(field.type().name())))
                throw RunningHubDefinition.invalid("“" + field.label() + "”需要匹配的项目内精确素材引用");
            used.add(id);
        }
        if (executing && inputs.stream().anyMatch(input -> !used.contains(input.versionId())))
            throw RunningHubDefinition.invalid("有素材尚未分配到具名输入槽位");
    }

    private void validateRole(Artifact.Kind kind, MediaDraft.VideoInputMode mode,
            MediaDraft.InputRole role, int order, int inputCount) {
        if (kind == Artifact.Kind.AUDIO) {
            if (role != MediaDraft.InputRole.REFERENCE && role != MediaDraft.InputRole.AUDIO_REFERENCE)
                throw invalid("音频生成只接受图片或音频参考角色。");
            return;
        }
        if (kind == Artifact.Kind.IMAGE) {
            if (role != MediaDraft.InputRole.REFERENCE) {
                throw invalid("图片生成输入只能使用 REFERENCE 角色。");
            }
            return;
        }
        if (mode == MediaDraft.VideoInputMode.GENERAL_REFERENCE) {
            if (role != MediaDraft.InputRole.REFERENCE && role != MediaDraft.InputRole.AUDIO_REFERENCE) {
                throw invalid("全能参考模式只能使用图片或音频参考角色。");
            }
            return;
        }
        if (mode == MediaDraft.VideoInputMode.START_END) {
            boolean valid = order == 0 && role == MediaDraft.InputRole.START_FRAME
                    || order == 1 && role == MediaDraft.InputRole.END_FRAME
                    && inputCount == 2;
            if (!valid || inputCount > 2) {
                throw invalid("首尾帧模式必须按首帧、可选尾帧顺序保存。");
            }
            return;
        }
        throw invalid("纯文本视频模式不能保存图片输入。");
    }

    /** Ordinary saves edit manual choices without silently dropping connection-backed inputs. */
    private List<MediaDraft.MediaInput> mergeInputs(List<MediaDraft.MediaInput> before,
            List<MediaDraft.MediaInput> requested) {
        List<MediaDraft.MediaInput> result = new ArrayList<>();
        for (MediaDraft.MediaInput requestedInput : requested) {
            MediaDraft.MediaInput existing = before.stream()
                    .filter(input -> input.versionId().equals(requestedInput.versionId()))
                    .findFirst().orElse(null);
            List<MediaDraft.InputSource> sources;
            if (existing != null) {
                // The save payload describes the ordered input projection, not a new source.
                // Preserve its exact source set so autosave cannot turn a connection-only input
                // into a manual selection that survives disconnecting the line.
                sources = new ArrayList<>(existing.sources());
            } else {
                sources = new ArrayList<>(requestedInput.sources());
            }
            result.add(new MediaDraft.MediaInput(requestedInput.versionId(),
                    requestedInput.artifactId(), requestedInput.role(), result.size(),
                    requestedInput.color(), List.copyOf(sources)));
        }
        for (MediaDraft.MediaInput existing : before) {
            if (requested.stream().anyMatch(input -> input.versionId().equals(
                    existing.versionId()))) continue;
            List<MediaDraft.InputSource> connectionSources = existing.sources().stream()
                    .filter(source -> source.type() == MediaDraft.SourceType.CONNECTION)
                    .toList();
            if (!connectionSources.isEmpty()) {
                result.add(new MediaDraft.MediaInput(existing.versionId(),
                        existing.artifactId(), existing.role(), result.size(), existing.color(),
                        connectionSources));
            }
        }
        return List.copyOf(result);
    }

    public record SaveMediaInput(UUID versionId, MediaDraft.InputRole role, String color) {}

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "媒体草稿无效", detail, false);
    }
}
