package dev.agenvas.task.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 为结果未知的直接媒体任务建立可审计的新替代尝试，原任务与提交记录始终保留。 */
@Service
public class ManualUnknownRetryService {

    /** 读取原任务，并通过统一任务规则创建替代媒体任务。 */
    private final TaskService tasks;
    /** 检查原请求与替代关系。 */
    private final TaskRepository repository;
    private final MediaCapabilityService mediaCapabilities;
    /** 确认项目仍可执行新尝试。 */
    private final ProjectService projects;
    /** 对已有输出目标再次比较产物版本。 */
    private final ArtifactService artifacts;
    /** 新尝试独立预留媒体用量，原 UNKNOWN 费用记录不消失。 */
    private final UsageService usage;
    /** 与替代关系同事务写入项目事件。 */
    private final ProjectEventService events;
    /** 构造不包含任务输入的状态事件负载。 */
    private final ObjectMapper mapper;
    /** 给不可变替代关系记录创建时间。 */
    private final Clock clock;

    /** 组装 UNKNOWN 替代任务创建所需的风险确认与独立用量预留能力。
     * @param tasks 读取和条件推进持久化任务
     * @param repository 读取原提交尝试与替代关系
     * @param mediaCapabilities 确认原媒体能力仍可用
     * @param projects 校验项目仍处于活动状态
     * @param artifacts 确认结果归档目标未被用户更新
     * @param usage 为新尝试单独预留用量
     * @param events 持久化替代关系和任务事件
     * @param mapper 构造安全事件负载
     * @param clock 为替代关系提供创建时间
     */
    public ManualUnknownRetryService(TaskService tasks, TaskRepository repository,
            MediaCapabilityService mediaCapabilities, ProjectService projects,
            ArtifactService artifacts, UsageService usage, ProjectEventService events,
            ObjectMapper mapper, Clock clock) {
        this.tasks = tasks;
        this.repository = repository;
        this.mediaCapabilities = mediaCapabilities;
        this.projects = projects;
        this.artifacts = artifacts;
        this.usage = usage;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 用户发起重试后，在同一项目事务中创建一次新的媒体任务与独立用量预留。
     * 必须重新核验原 UNKNOWN 任务、媒体能力与固定输出目标；原 UNKNOWN 记录始终保留。
     *
     * @param ownerId 经认证的用户 ID
     * @param projectId 原任务所属项目
     * @param originalTaskId 状态仍为 UNKNOWN 的直接媒体任务
     * @param expectedTaskVersion 用户读取到的原任务版本
     * @param idempotencyKey 同一重试命令的客户端键；相同键不能用于别的任务或版本
     * @return 新建或同键重放得到的替代任务
     */
    @Transactional
    public Task create(UUID ownerId, UUID projectId, UUID originalTaskId,
            long expectedTaskVersion, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 120 || expectedTaskVersion < 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.manual-unknown-retry-service.invalid-retry-request"), ApiMessage.of("api.manual-unknown-retry-service.please-provide-a-valid-task-version-and-idempotent-key"), false);
        }
        return events.recordChange(ownerId, projectId, () -> {
            Task original = tasks.get(ownerId, projectId, originalTaskId);
            TaskRepository.ManualReplacement replay = repository.findManualReplacementByKey(
                    projectId, ownerId, idempotencyKey).orElse(null);
            if (replay != null) {
                if (!replay.originalTaskId().equals(originalTaskId)
                        || replay.originalTaskVersion() != expectedTaskVersion) {
                    throw conflict(ApiMessage.of("api.manual-unknown-retry-service.the-same-idempotent-key-has-been-used-in-different-tasks"));
                }
                return ProjectEventService.Change.unchanged(tasks.get(ownerId, projectId,
                        replay.replacementTaskId()));
            }
            if (repository.findManualReplacement(projectId, originalTaskId).isPresent()) {
                throw conflict(ApiMessage.of("api.manual-unknown-retry-service.there-is-a-new-attempt-for-this-unknown-task-please"));
            }
            if (original.runId() != null) {
                throw conflict(ApiMessage.of("api.manual-unknown-retry-service.only-media-tasks-to-which-the-user-is-directly-connected"));
            }
            return ProjectEventService.Change.unchanged(retryDirect(ownerId, projectId,
                    original, expectedTaskVersion, idempotencyKey));
        }).value();
    }

    /** 直接媒体任务的替代尝试保留原固定输入，并单独预留用量。 */
    private Task retryDirect(UUID ownerId, UUID projectId, Task original,
            long expectedTaskVersion, String idempotencyKey) {
        if (original.version() != expectedTaskVersion
                || original.status() != Task.Status.UNKNOWN || original.cancelRequested()
                || original.providerRequestId() != null
                || (original.kind() != Task.Kind.IMAGE_GENERATION
                        && original.kind() != Task.Kind.VIDEO_GENERATION
                        && original.kind() != Task.Kind.AUDIO_GENERATION)) {
            throw conflict(ApiMessage.of("api.manual-unknown-retry-service.the-original-task-status-or-version-has-changed-please-refresh"));
        }
        projects.requireActiveProject(ownerId, projectId);
        MediaCapabilityBinding binding = repository.mediaBinding(original.id())
                .orElseThrow(() -> conflict(ApiMessage.of("api.manual-unknown-retry-service.the-original-media-capabilities-are-unavailable-and-new-attempts-cannot")));
        int seconds = original.kind() == Task.Kind.VIDEO_GENERATION
                ? original.input().path("durationSeconds").asInt(-1) : 0;
        if (!mediaCapabilities.isCurrentBinding(binding, original.kind(), seconds)) {
            throw conflict(ApiMessage.of("api.manual-unknown-retry-service.media-capabilities-have-changed-please-create-a-new-draft-task"));
        }
        TaskRepository.ArtifactTarget target = repository.findArtifactTarget(original.id())
                .orElseThrow(() -> conflict(ApiMessage.of("api.manual-unknown-retry-service.the-original-task-lacks-a-fixed-output-target")));
        if (target.artifactId() == null) {
            throw conflict(ApiMessage.of("api.manual-unknown-retry-service.direct-tasks-must-be-bound-to-a-media-card"));
        }
        Artifact card = artifacts.get(ownerId, projectId, target.artifactId()).artifact();
        if (card.archivedAt() != null) throw conflict(ApiMessage.of("api.manual-unknown-retry-service.the-card-has-been-archived-and-cannot-be-tried-again"));
        Instant now = clock.instant();
        Task replacement = new Task(UUID.randomUUID(), projectId, null,
                "retry." + original.id() + "." + (original.attemptNo() + 1),
                original.kind(), Task.Status.READY, false, original.input().deepCopy(),
                original.inputHash(), null, null,
                original.attemptNo() + 1, now, null, null, 0, 0, null, now, now, null);
        repository.create(replacement);
        repository.bindMediaTask(replacement.id(), binding);
        repository.createArtifactTarget(new TaskRepository.ArtifactTarget(replacement.id(),
                projectId, target.artifactId(), target.expectedCurrentVersionId(),
                target.expectedArtifactVersion(), target.canvasItemId()));
        usage.reserveMediaTask(ownerId, replacement, "PROVIDER_UNPRICED", binding.connectionVersion());
        repository.createManualReplacement(new TaskRepository.ManualReplacement(projectId,
                original.id(), replacement.id(), ownerId, expectedTaskVersion,
                idempotencyKey, now));
        events.append(ownerId, projectId, statusEvent(original, replacement.id()));
        events.append(ownerId, projectId, statusEvent(replacement, null));
        return replacement;
    }

    /** 对原任务与替代任务沿用状态事件，促使客户端刷新两者。 */
    private ProjectEventService.EventDraft statusEvent(Task task, UUID replacementId) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("taskId", task.id().toString());
        payload.put("status", task.status().name());
        payload.put("cancelRequested", task.cancelRequested());
        payload.put("possibleExternalCost", task.status() == Task.Status.UNKNOWN);
        if (replacementId != null) payload.put("replacementTaskId", replacementId.toString());
        return new ProjectEventService.EventDraft("task.status.changed", 1,
                task.id(), task.version(), payload);
    }

    /** 原任务状态或前提变化时返回稳定冲突码，不隐式创建新的外部请求。 */
    private ApiProblemException conflict(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "UNKNOWN_RETRY_CONFLICT",
                ApiMessage.of("api.manual-unknown-retry-service.cannot-create-new-attempt"), detail, false);
    }
}
