package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.provider.application.ProviderProperties;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Accepts a user's saved media draft as one immutable, unapproved direct Task. */
@Service
public class DirectMediaTaskService {
    private static final int MAX_COMMAND_KEY_LENGTH = 160;
    private static final String COST_SOURCE = "PROVIDER_UNPRICED";

    private final TaskRepository tasks;
    private final MediaDraftService drafts;
    private final ArtifactService artifacts;
    private final CanvasItemQueryService canvasItems;
    private final MediaCapabilityService capabilities;
    private final ProviderProperties provider;
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DirectMediaTaskService(TaskRepository tasks, MediaDraftService drafts,
            ArtifactService artifacts, CanvasItemQueryService canvasItems,
            MediaCapabilityService capabilities,
            ProviderProperties provider, ProjectEventService events, UsageService usage,
            ObjectMapper mapper, Clock clock) {
        this.tasks = tasks;
        this.drafts = drafts;
        this.artifacts = artifacts;
        this.canvasItems = canvasItems;
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
                if (!prior.input().path("artifactId").asText().equals(artifactId.toString())
                        || !prior.input().path("canvasItemId").asText().equals(canvasItemId.toString())
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
                    && (draft.inputImageVersionId() == null || draft.durationSeconds() == null)) {
                throw invalid("视频运行前需要选择输入图片版本和时长。");
            }
            int seconds = kind == Task.Kind.VIDEO_GENERATION ? draft.durationSeconds() : 0;
            MediaCapabilityBinding binding = draft.capabilityId() == null
                    ? capabilities.defaultFor(kind)
                    : capabilities.resolve(draft.capabilityId(), kind, seconds);
            // A default binding must also support the draft's exact duration.
            binding = capabilities.resolve(binding.capabilityId(), kind, seconds);
            ObjectNode input = mapper.createObjectNode();
            input.put("schemaVersion", 2);
            input.put("artifactId", artifactId.toString());
            input.put("canvasItemId", canvasItemId.toString());
            if (canvasItem.selectedVersionId() == null) input.putNull("parentVersionId");
            else input.put("parentVersionId", canvasItem.selectedVersionId().toString());
            input.put("draftVersion", draft.version());
            input.put("prompt", draft.prompt());
            input.put("providerConfigVersion", provider.configVersion());
            input.put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
            String originHash = capabilities.capabilitySnapshot(binding.capabilityId())
                    .connectionVersion().originSha256();
            if (originHash != null) input.put("providerOriginSha256", originHash);
            if (kind == Task.Kind.VIDEO_GENERATION) {
                ArtifactVersion image = artifacts.requireImageVersionForTask(ownerId, projectId,
                        draft.inputImageVersionId());
                input.put("imageArtifactId", image.artifactId().toString());
                input.put("imageVersionId", image.id().toString());
                input.put("durationSeconds", seconds);
            }
            Instant now = clock.instant();
            Task task = new Task(UUID.randomUUID(), projectId, null, commandKey, kind,
                    Task.Status.READY, false, input, hash(input.toString()), null, null, null,
                    1, now, null, null, 0, 0, null, now, now, null);
            tasks.create(task, List.of());
            tasks.bindMediaTask(task.id(), binding);
            tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                    artifactId, canvasItem.selectedVersionId(), target.version(), null,
                    canvasItemId));
            drafts.setDisplayModeWithinChange(projectId, canvasItemId,
                    MediaDraft.DisplayMode.DRAFT);
            usage.reserveMediaTask(ownerId, task, COST_SOURCE);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("taskId", task.id().toString());
            payload.put("artifactId", artifactId.toString());
            payload.put("status", task.status().name());
            events.append(ownerId, projectId,
                    new ProjectEventService.EventDraft("task.status.changed", 1, task.id(),
                            task.version(), payload));
            return ProjectEventService.Change.unchanged(task);
        }).value();
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

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "媒体任务输入无效", detail, false);
    }

    private static ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "DIRECT_MEDIA_CONFLICT",
                "媒体任务冲突", detail, true);
    }
}
