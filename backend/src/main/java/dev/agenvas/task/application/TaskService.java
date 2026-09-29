package dev.agenvas.task.application;

import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.task.domain.ProviderAttempt;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import dev.agenvas.usage.application.UsageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 创建带固定输入和依赖关系的任务，并通过租约 epoch 约束 Worker 的每次状态写入。 */
@Service
public class TaskService {

    /** 核对任务所属 Run 的可执行状态及取消边界。 */
    private final AgentRunService runs;
    /** 核对项目所有者、归档状态及导出时的项目版本。 */
    private final ProjectService projects;
    /** 校验媒体目标和固定输入版本，归档生成结果为不可变版本。 */
    private final ArtifactService artifacts;
    private final MediaDraftService mediaDrafts;
    private final CanvasService canvas;
    /** 执行任务、依赖、租约与外部提交账本的条件读写。 */
    private final TaskRepository tasks;
    /** 限定租约时长和单次认领、恢复扫描的批量大小。 */
    private final TaskProperties properties;
    /** 使任务状态事件与对应业务变化在同一项目事务提交。 */
    private final ProjectEventService events;
    /** 按任务稳定操作键预留、结算或释放媒体与导出用量。 */
    private final UsageService usage;
    /** 复制固定输入并构造不泄露私有数据的任务事件负载。 */
    private final ObjectMapper objectMapper;
    /** 为任务认领、租约到期和状态转换提供统一时间源。 */
    private final Clock clock;
    /** 停机开始后阻止新的后台任务进入执行阶段。 */
    private final ShutdownGate shutdownGate;

    /** 组装任务权限、输入快照、依赖调度、租约、账本及项目事件边界。
     * @param runs 校验所属 Run 并推进编排状态
     * @param projects 校验项目作用域和活动状态
     * @param artifacts 锁定归档时需再次核验的产物输出目标
     * @param tasks 任务和 Provider 提交账本的持久化边界
     * @param properties 控制租约、批量认领和恢复上限
     * @param events 将状态变化与项目事件原子提交
     * @param usage 对媒体尝试预留、结算和释放用量
     * @param objectMapper 固定任务输入并序列化事件内容
     * @param clock 提供数据库状态转换时间
     * @param shutdownGate 停机期间禁止进入新的外部副作用阶段
     */
    public TaskService(
            AgentRunService runs,
            ProjectService projects,
            ArtifactService artifacts,
            MediaDraftService mediaDrafts,
            CanvasService canvas,
            TaskRepository tasks,
            TaskProperties properties,
            ProjectEventService events,
            UsageService usage,
            ObjectMapper objectMapper,
            Clock clock,
            ShutdownGate shutdownGate) {
        this.runs = runs;
        this.projects = projects;
        this.artifacts = artifacts;
        this.mediaDrafts = mediaDrafts;
        this.canvas = canvas;
        this.tasks = tasks;
        this.properties = properties;
        this.events = events;
        this.usage = usage;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.shutdownGate = shutdownGate;
    }

    /**
     * 创建带不可变输入快照的任务。依赖最多 100 个，必须互不重复且属于同一 Run；无依赖时直接进入 READY。
     * 事务内再次检查 Run 状态，避免校验之后的取消与任务创建竞态。
     *
     * @param ownerId 经认证的项目所有者 ID
     * @param projectId 任务归属项目 ID
     * @param runId 任务归属 Run ID，必须仍允许创建任务
     * @param requestedStepKey 同 Run 内稳定步骤键，长度为 1 至 160 字符
     * @param kind 决定 Worker 认领路径的任务类别
     * @param input 创建时复制并固定的任务 JSON 输入
     * @param providerId 已选 Provider 配置 ID；本地任务可为空
     * @param attemptNo 同一步骤的正整数尝试序号
     * @param dependencyIds 必须先完成的同 Run 任务 ID，最多 100 个
     * @return 已落库的任务及其初始状态
     */
    @Transactional
    public Task create(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            String requestedStepKey,
            Task.Kind kind,
            JsonNode input,
            UUID providerId,
            int attemptNo,
            List<UUID> dependencyIds) {
        AgentRun run = runs.get(ownerId, projectId, runId);
        if (run.status().terminal() || run.status() == AgentRun.Status.CANCEL_REQUESTED) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "TASK_CANCELED",
                    "Run 已停止", "已取消或结束的 Run 不能创建新任务。", false);
        }
        String stepKey = validateStepKey(requestedStepKey);
        if (kind == null || input == null || attemptNo < 1) {
            throw validation("Task kind、input 与正数 attemptNo 都是必填项。");
        }
        List<UUID> dependencies = dependencyIds == null ? List.of() : List.copyOf(dependencyIds);
        if (dependencies.size() > 100 || dependencies.stream().distinct().count() != dependencies.size()) {
            throw validation("Task 依赖最多 100 个且不能重复。");
        }
        for (UUID dependencyId : dependencies) {
            Task dependency = get(ownerId, projectId, dependencyId);
            if (!dependency.runId().equals(runId)) {
                throw validation("Task 依赖必须属于同一个 Run。");
            }
        }
        Instant now = clock.instant();
        Task task = new Task(
                UUID.randomUUID(),
                projectId,
                runId,
                stepKey,
                kind,
                dependencies.isEmpty() ? Task.Status.READY : Task.Status.PENDING,
                false,
                input.deepCopy(),
                sha256(input.toString()),
                null,
                providerId,
                null,
                attemptNo,
                now,
                null,
                null,
                0,
                0,
                null,
                now,
                now,
                null);
        return events.recordChange(ownerId, projectId, () -> {
            AgentRun current = runs.get(ownerId, projectId, runId);
            if (current.status().terminal()
                    || current.status() == AgentRun.Status.CANCEL_REQUESTED) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "TASK_CANCELED",
                        "Run 已停止", "已取消或结束的 Run 不能创建新任务。", false);
            }
            tasks.create(task, dependencies);
            return ProjectEventService.Change.changed(task, taskEvent(task, false));
        }).value();
    }

    /** 创建写入既有产物的媒体任务，并固定创建时的产物版本；完成时不得覆盖之后的用户编辑。 */
    @Transactional
    public Task createMediaTask(UUID ownerId, UUID projectId, UUID runId,
            String stepKey, Task.Kind kind, JsonNode input, UUID providerId, int attemptNo,
            List<UUID> dependencyIds, UUID targetArtifactId) {
        Artifact.Kind expectedKind = switch (kind) {
            case IMAGE_GENERATION -> Artifact.Kind.IMAGE;
            case VIDEO_GENERATION -> Artifact.Kind.VIDEO;
            default -> throw validation("只有图片和视频生成 Task 可绑定媒体产物目标。");
        };
        Artifact target = artifacts.get(ownerId, projectId, targetArtifactId).artifact();
        if (target.kind() != expectedKind || target.archivedAt() != null) {
            throw validation("媒体任务目标必须是同项目、未归档且类型匹配的 Artifact。");
        }
        Task task = create(ownerId, projectId, runId, stepKey, kind, input,
                providerId, attemptNo, dependencyIds);
        Task occupying = tasks.findOccupyingMediaTask(projectId, target.id()).orElse(null);
        if (occupying != null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "MEDIA_CARD_BUSY",
                    "卡片任务占用", "这张媒体卡片已有排队、执行或待核对任务。", true);
        }
        tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                target.id(), target.resourceDefaultVersionId(), target.version(), null, null));
        return task;
    }

    /** 按项目所有者读取任务；不存在与无权访问使用相同的 404 响应。 */
    @Transactional(readOnly = true)
    public Task get(UUID ownerId, UUID projectId, UUID taskId) {
        return tasks.find(ownerId, projectId, taskId).orElseThrow(this::notFound);
    }

    /** 先核验 Run 的项目归属，再读取该 Run 的全部任务以构造恢复快照。 */
    @Transactional(readOnly = true)
    public List<Task> listByRun(UUID ownerId, UUID projectId, UUID runId) {
        runs.get(ownerId, projectId, runId);
        return tasks.listByRun(ownerId, projectId, runId);
    }

    @Transactional(readOnly = true)
    public List<Task> listActiveDirect(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return tasks.listActiveDirect(ownerId, projectId);
    }

    /** 对任务重新鉴权后读取外部提交尝试历史，用于验证提交不重复与诊断。 */
    @Transactional(readOnly = true)
    public List<ProviderAttempt> listProviderAttempts(UUID ownerId, UUID projectId, UUID taskId) {
        get(ownerId, projectId, taskId);
        return tasks.listProviderAttempts(ownerId, projectId, taskId);
    }

    /** 仅当唯一已受理尝试同时匹配任务的原请求 ID 时，返回该尝试保存的 Provider origin 摘要。 */
    @Transactional(readOnly = true)
    public Optional<String> acceptedProviderOrigin(Task task) {
        if (task.providerRequestId() == null) return Optional.empty();
        UUID ownerId = ownerForWorker(task);
        List<String> matching = tasks.listProviderAttempts(ownerId, task.projectId(), task.id())
                .stream()
                .filter(attempt -> attempt.status() == ProviderAttempt.Status.ACCEPTED
                        && task.providerRequestId().equals(attempt.providerRequestId())
                        && attempt.candidateOriginSha256() != null)
                .map(ProviderAttempt::candidateOriginSha256).toList();
        return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
    }

    /** 查询人工重试创建的替代任务；原 UNKNOWN 任务仍保留在历史中。 */
    @Transactional(readOnly = true)
    public UUID replacementTaskId(UUID ownerId, UUID projectId, UUID taskId) {
        get(ownerId, projectId, taskId);
        return tasks.findManualReplacement(projectId, taskId)
                .map(TaskRepository.ManualReplacement::replacementTaskId).orElse(null);
    }

    /** 读取近期尚未核对的 UNKNOWN 任务，不依赖 Run 的活动槽位。 */
    @Transactional(readOnly = true)
    public List<Task> listUnknown(UUID ownerId, UUID projectId) {
        return tasks.listUnknown(ownerId, projectId);
    }

    /** 在短事务中认领有限批次；事务返回后调用方再执行耗时工作。 */
    @Transactional
    public List<Task> claimDue(String requestedWorkerId, int requestedLimit) {
        if (shutdownGate.isClosing()) return List.of();
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("claim limit 必须为正数。");
        }
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDue(
                workerId, limit, now, now.plus(properties.leaseDuration())), List.of());
    }

    /** Claims only version-pinned media work for the unified execution kernel. */
    @Transactional
    public List<Task> claimBoundMedia(String requestedWorkerId, int requestedLimit) {
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) throw validation("claim limit 必须为正数。");
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueBoundMedia(workerId, limit,
                now, now.plus(properties.leaseDuration())), List.of());
    }

    /** Polls only requests previously accepted for version-pinned media work. */
    @Transactional
    public List<Task> claimBoundMediaPolls(String requestedWorkerId, int requestedLimit) {
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) throw validation("claim limit 必须为正数。");
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueBoundMediaPolls(workerId, limit,
                now, now.plus(properties.leaseDuration())), List.of());
    }

    public Optional<MediaCapabilityBinding> mediaBinding(Task task) {
        return tasks.mediaBinding(task.id());
    }

    /** 只认领图片任务，防止 Mock 图片适配器消费尚未实现的视频工作。 */
    @Transactional
    public List<Task> claimImagesDue(String requestedWorkerId, int requestedLimit) {
        if (shutdownGate.isClosing()) return List.of();
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("claim limit 必须为正数。");
        }
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueImages(workerId, limit, now,
                now.plus(properties.leaseDuration())), List.of());
    }

    /** 认领至多一个旧版 ComfyUI 图片任务；跨实例仅靠行租约防止重复领取。 */
    @Transactional
    public List<Task> claimComfyImage(String requestedWorkerId) {
        if (shutdownGate.isClosing()) return List.of();
        String workerId = validateWorkerId(requestedWorkerId);
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueComfyImage(workerId, now,
                now.plus(properties.leaseDuration())), List.of());
    }

    /** 认领至多一个旧版 ComfyUI 视频任务；不再检查共享提交槽。 */
    @Transactional
    public List<Task> claimComfyVideo(String requestedWorkerId) {
        if (shutdownGate.isClosing()) return List.of();
        String workerId = validateWorkerId(requestedWorkerId);
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueComfyVideo(workerId, now,
                now.plus(properties.leaseDuration())), List.of());
    }

    /** 视频任务使用独立认领条件，不会被图片或模型回合 Worker 取走。 */
    @Transactional
    public List<Task> claimVideosDue(String requestedWorkerId, int requestedLimit) {
        if (shutdownGate.isClosing()) return List.of();
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("claim limit 必须为正数。");
        }
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueVideos(workerId, limit, now,
                now.plus(properties.leaseDuration())), List.of());
    }

    /** 只认领已保存原外部请求 ID 的轮询任务；取消后的请求仍可查询并归档晚到结果。 */
    @Transactional
    public List<Task> claimProviderPolls(String requestedWorkerId, int requestedLimit) {
        if (shutdownGate.isClosing()) return List.of();
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) throw validation("claim limit 必须为正数。");
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueProviderPolls(workerId, limit, now,
                now.plus(properties.leaseDuration())), List.of());
    }

    /** ComfyUI 图片轮询只取图片任务，不会接管已受理的视频请求。 */
    @Transactional
    public List<Task> claimComfyImagePolls(String requestedWorkerId, int requestedLimit) {
        if (shutdownGate.isClosing()) return List.of();
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) throw validation("claim limit 必须为正数。");
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueComfyImagePolls(workerId, limit, now,
                now.plus(properties.leaseDuration())), List.of());
    }

    /** ComfyUI 视频轮询只取视频任务，不会接管已受理的图片请求。 */
    @Transactional
    public List<Task> claimComfyVideoPolls(String requestedWorkerId, int requestedLimit) {
        if (shutdownGate.isClosing()) return List.of();
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) throw validation("claim limit 必须为正数。");
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueComfyVideoPolls(workerId, limit, now,
                now.plus(properties.leaseDuration())), List.of());
    }

    /** 从已认领任务的数据库记录反查所有者；不接受模型或任务输入中的身份字段。 */
    @Transactional(readOnly = true)
    public UUID ownerForWorker(Task task) {
        Task current = tasks.findById(task.id()).orElseThrow(this::notFound);
        if (!current.projectId().equals(task.projectId())
                || !java.util.Objects.equals(current.runId(), task.runId())) {
            throw leaseLost();
        }
        return tasks.ownerId(task.id()).orElseThrow(this::notFound);
    }

    /** 只认领 AGENT_TURN 任务，供有回合上限的 Run 调度器处理。 */
    @Transactional
    public List<Task> claimAgentTurns(String requestedWorkerId, int requestedLimit) {
        if (shutdownGate.isClosing()) return List.of();
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("claim limit 必须为正数。");
        }
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueAgentTurns(workerId, limit, now,
                now.plus(properties.leaseDuration())), List.of());
    }

    /** Claims only direct text-card model work; other workers never consume this kind. */
    @Transactional
    public List<Task> claimTextGenerations(String requestedWorkerId, int requestedLimit) {
        if (shutdownGate.isClosing()) return List.of();
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) throw validation("claim limit 必须为正数。");
        Instant now = clock.instant();
        return shutdownGate.claimOrEmpty(() -> tasks.claimDueTextGenerations(workerId, limit,
                now, now.plus(properties.leaseDuration())), List.of());
    }

    /** Saves the complete direct model response before any Artifact version is appended. */
    @Transactional
    public JsonNode checkpointTextResponse(Task lease, String workerId, JsonNode response) {
        if (lease.kind() != Task.Kind.TEXT_GENERATION || response == null
                || !response.isObject()) {
            throw validation("文字模型响应检查点无效。");
        }
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        Instant now = clock.instant();
        return events.recordChange(ownerId, lease.projectId(), () -> {
            Task current = tasks.findById(lease.id()).orElseThrow(this::notFound);
            if (current.output() != null) {
                if (!current.output().equals(response)) throw leaseLost();
                return ProjectEventService.Change.unchanged(current.output());
            }
            if (!tasks.checkpointTextResponse(lease.id(), validateWorkerId(workerId),
                    lease.leaseEpoch(), response, now)) {
                throw leaseLost();
            }
            usage.settleDirectTextTask(ownerId, lease, response);
            Task saved = tasks.findById(lease.id()).orElseThrow(this::notFound);
            events.append(ownerId, lease.projectId(), taskEvent(saved, false));
            return ProjectEventService.Change.unchanged(saved.output());
        }).value();
    }

    /** Appends generated text as an immutable version and selects it only if the pinned input is current. */
    @Transactional
    public ArtifactService.TaskVersionResult succeedWithTextArtifact(
            Task lease, String workerId, JsonNode content) {
        if (lease.kind() != Task.Kind.TEXT_GENERATION) {
            throw validation("任务不是文字生成任务。");
        }
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        Instant now = clock.instant();
        return events.recordChange(ownerId, lease.projectId(), () -> {
            Task current = tasks.findById(lease.id()).orElseThrow(this::notFound);
            TaskRepository.ArtifactTarget target = tasks.findArtifactTarget(lease.id())
                    .orElseThrow(() -> validation("文字生成任务缺少目标快照。"));
            boolean liveLease = current.status() == Task.Status.RUNNING
                    && workerId.equals(current.leaseOwner())
                    && current.leaseEpoch() == lease.leaseEpoch()
                    && current.leaseUntil() != null && current.leaseUntil().isAfter(now)
                    && !current.cancelRequested() && current.output() != null;
            if (!liveLease) throw leaseLost();
            ArtifactService.TaskVersionResult result = artifacts.appendTaskVersionWithinChange(
                    ownerId, lease.projectId(), target.artifactId(), null,
                    target.expectedCurrentVersionId(), target.expectedArtifactVersion(),
                    content, null, true);
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("artifactId", target.artifactId().toString());
            summary.put("artifactVersionId", result.versionId().toString());
            summary.put("selected", result.selected());
            ObjectNode completedOutput = (ObjectNode) current.output().deepCopy();
            completedOutput.set("result", summary);
            if (!tasks.finish(lease.id(), validateWorkerId(workerId), lease.leaseEpoch(),
                    Task.Status.SUCCEEDED, completedOutput, null, now)) {
                throw leaseLost();
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            events.append(ownerId, lease.projectId(), taskEvent(updated, false));
            return ProjectEventService.Change.unchanged(result);
        }).value();
    }

    /** 仅当前 Worker 和 epoch 仍匹配且租约未过期时续租；长任务执行线程无需持有数据库事务。 */
    @Transactional
    public void heartbeat(UUID taskId, String workerId, long leaseEpoch) {
        Instant now = clock.instant();
        if (!tasks.heartbeat(
                taskId,
                validateWorkerId(workerId),
                leaseEpoch,
                now,
                now.plus(properties.leaseDuration()))) {
            throw leaseLost();
        }
    }

    /** 仅当前租约 epoch 可提交成功结果；确认不是取消后晚到结果时才推进依赖任务。 */
    @Transactional
    public void succeed(Task lease, String workerId, JsonNode output) {
        Instant now = clock.instant();
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        events.recordChange(ownerId, lease.projectId(), () -> {
            Task current = tasks.findById(lease.id()).orElseThrow(this::notFound);
            if (current.cancelRequested()) {
                preserveCanceledResult(lease, workerId, output, now);
            } else if (tasks.finish(lease.id(), validateWorkerId(workerId),
                    lease.leaseEpoch(), Task.Status.SUCCEEDED, output, null, now)) {
                if (lease.runId() != null) {
                    tasks.promoteReady(lease.projectId(), lease.runId(), now);
                }
            } else if (tasks.findById(lease.id()).filter(Task::cancelRequested).isPresent()) {
                preserveCanceledResult(lease, workerId, output, now);
            } else {
                throw leaseLost();
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            events.append(ownerId, lease.projectId(), taskEvent(updated, false));
            return ProjectEventService.Change.unchanged(null);
        });
    }

    /**
     * 将同步媒体结果归档为不可变产物版本。只有 Run 仍活动且目标产物未被用户改动时才自动选用新版本。
     * 取消后晚到或固定输入已过期的结果保留在历史中，不推进下游依赖。
     */
    @Transactional
    public ArtifactService.TaskVersionResult succeedWithArtifact(
            Task lease, String workerId, JsonNode content) {
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        Instant now = clock.instant();
        return events.recordChange(ownerId, lease.projectId(), () -> {
            Task current = tasks.findById(lease.id()).orElseThrow(this::notFound);
            TaskRepository.ArtifactTarget target = tasks.findArtifactTarget(lease.id())
                    .orElseThrow(() -> validation("媒体任务缺少创建时的产物目标快照。"));
            AgentRun run = lease.runId() == null ? null
                    : runs.get(ownerId, lease.projectId(), lease.runId());
            boolean canceled = current.cancelRequested()
                    || (run != null && (run.status() == AgentRun.Status.CANCEL_REQUESTED
                            || run.status() == AgentRun.Status.CANCELED));
            boolean projectArchived = projects.get(ownerId, lease.projectId()).status()
                    == Project.Status.ARCHIVED;
            UUID canvasItemId = target.canvasItemId();
            if (canvasItemId != null
                    && !canvasItemId.toString().equals(
                            lease.input().path("canvasItemId").asText())) {
                throw validation("媒体任务的卡片目标与固定输入不一致。");
            }
            boolean selectResult = !canceled && !projectArchived
                    && pinnedMediaInputsCurrent(ownerId, lease)
                    && (lease.runId() != null || canvasItemId != null);
            boolean activeOrUnknown = current.status() == Task.Status.RUNNING
                    || current.status() == Task.Status.SUBMITTING
                    || current.status() == Task.Status.WAITING_PROVIDER
                    || current.status() == Task.Status.UNKNOWN;
            boolean acknowledgedPoll = current.status() == Task.Status.RUNNING
                    && current.providerRequestId() != null
                    && current.providerRequestId().equals(lease.providerRequestId());
            boolean liveLease = workerId.equals(current.leaseOwner())
                    && current.leaseUntil() != null
                    && current.leaseUntil().isAfter(now);
            if (current.leaseEpoch() != lease.leaseEpoch()
                    || !activeOrUnknown
                    || (!canceled && (!acknowledgedPoll
                            && current.status() != Task.Status.SUBMITTING || !liveLease))) {
                throw leaseLost();
            }
            if (content == null || !content.path("sourceTaskId").asText("")
                    .equals(lease.id().toString())) {
                throw validation("生成结果必须标识匹配的 sourceTaskId。");
            }
            UUID artifactId = target.artifactId();
            ArtifactService.TaskVersionResult result = artifacts.appendTaskVersionWithinChange(
                    ownerId, lease.projectId(), artifactId, lease.runId(),
                    target.expectedCurrentVersionId(), target.expectedArtifactVersion(), content,
                    lease.input().has("mediaInput") ? lease.input().path("mediaInput") : null,
                    selectResult && canvasItemId == null);
            if (canvasItemId != null) {
                boolean cardSelected = selectResult && canvas.selectTaskResultWithinChange(
                        ownerId, lease.projectId(), canvasItemId, artifactId,
                        target.expectedCurrentVersionId(), result.versionId());
                result = new ArtifactService.TaskVersionResult(result.versionId(), cardSelected);
            }
            ObjectNode output = objectMapper.createObjectNode();
            output.put("artifactId", artifactId.toString());
            output.put("artifactVersionId", result.versionId().toString());
            output.put("selected", result.selected());
            if (canceled) {
                preserveCanceledResult(lease, workerId, output, now);
            } else if (!(acknowledgedPoll
                    ? tasks.finishProviderResult(lease, validateWorkerId(workerId), output, now)
                    : tasks.finishSubmitting(lease, validateWorkerId(workerId), output, now))) {
                throw leaseLost();
            } else if (selectResult && run != null) {
                tasks.promoteReady(lease.projectId(), lease.runId(), now);
            } else if (run != null) {
                AgentRun latest = runs.get(ownerId, lease.projectId(), lease.runId());
                if (latest.status() == AgentRun.Status.WAITING_TASKS) {
                    runs.transition(ownerId, lease.projectId(), lease.runId(),
                            latest.version(), AgentRun.Status.BLOCKED);
                }
            }
            tasks.clearProviderPollFailures(lease.id());
            // Run-scoped media tasks carry no usage reservation; only direct tasks do.
            if (lease.runId() == null) {
                usage.settleMediaTask(ownerId, lease);
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("taskId", lease.id().toString());
            payload.put("status", updated.status().name());
            payload.put("artifactId", artifactId.toString());
            payload.put("artifactVersionId", result.versionId().toString());
            payload.put("selected", result.selected());
            payload.put("possibleExternalCost", !MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(
                    tasks.mediaBinding(lease.id()).map(binding -> binding.adapterId()).orElse(null)));
            events.append(ownerId, lease.projectId(),
                    new ProjectEventService.EventDraft("task.status.changed", 1,
                            lease.id(), updated.version(), payload));
            return ProjectEventService.Change.unchanged(result);
        }).value();
    }

    /** 当前租约确认本地失败后写入错误码；若外部请求状态仍不明，不得使用此路径判定失败。 */
    @Transactional
    public void fail(Task lease, String workerId, String errorCode) {
        String normalizedCode = validateErrorCode(errorCode);
        Instant now = clock.instant();
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        events.recordChange(ownerId, lease.projectId(), () -> {
            Task before = tasks.findById(lease.id()).orElseThrow(this::notFound);
            if (before.cancelRequested()) {
                if (!tasks.finishCanceled(lease, validateWorkerId(workerId), now)) {
                    throw leaseLost();
                }
            } else if (!tasks.finish(lease.id(), validateWorkerId(workerId),
                    lease.leaseEpoch(), Task.Status.FAILED, null, normalizedCode, now)) {
                if (tasks.findById(lease.id()).filter(Task::cancelRequested).isEmpty()
                        || !tasks.finishCanceled(lease, validateWorkerId(workerId), now)) {
                    throw leaseLost();
                }
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            if (updated.runId() == null
                    && (updated.kind() == Task.Kind.IMAGE_GENERATION
                            || updated.kind() == Task.Kind.VIDEO_GENERATION)
                    && before.status() == Task.Status.RUNNING
                    && before.providerRequestId() == null
                    && (updated.status() == Task.Status.FAILED
                            || updated.status() == Task.Status.CANCELED)) {
                usage.releaseUnsubmittedMediaTask(ownerId, updated);
            }
            if (updated.kind() == Task.Kind.TEXT_GENERATION && before.output() == null
                    && (updated.status() == Task.Status.FAILED
                            || updated.status() == Task.Status.CANCELED)) {
                usage.releaseDirectTextTask(ownerId, updated);
            }
            events.append(ownerId, lease.projectId(), taskEvent(updated, false));
            blockWaitingRunForMedia(ownerId, updated);
            return ProjectEventService.Change.unchanged(null);
        });
    }

    /** 提交前发现媒体输入已过期时阻断任务，等待用户基于新版本重新审批计划。 */
    @Transactional
    public void blockStaleInput(Task lease, String workerId) {
        blockPreSubmission(lease, workerId, "TASK_INPUT_STALE");
    }

    /** 在保存外部提交检查点前阻断配置或输入无效的任务，避免形成无法核对的半提交。 */
    @Transactional
    public void blockPreSubmission(Task lease, String workerId, String errorCode) {
        if (!"TASK_INPUT_STALE".equals(errorCode)
                && !"TASK_PROJECT_ARCHIVED".equals(errorCode)
                && !"MEDIA_CAPABILITY_CHANGED".equals(errorCode)
                && !"PROVIDER_UNSUPPORTED_CAPABILITY".equals(errorCode)
                && !"PROVIDER_UNSUPPORTED_INPUT".equals(errorCode)
                && !"MEDIA_CREDENTIAL_UNAVAILABLE".equals(errorCode)
                && !"LOCAL_DEPTH_MODEL_UNAVAILABLE".equals(errorCode)) {
            throw validation("不支持的提交前阻断原因。");
        }
        Instant now = clock.instant();
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        events.recordChange(ownerId, lease.projectId(), () -> {
            Task before = tasks.findById(lease.id()).orElseThrow(this::notFound);
            if (before.cancelRequested()) {
                if (!tasks.finishCanceled(lease, validateWorkerId(workerId), now)) {
                    throw leaseLost();
                }
            } else if (!tasks.blockStaleInput(lease.id(), validateWorkerId(workerId),
                    lease.leaseEpoch(), errorCode, now)) {
                if (tasks.findById(lease.id()).filter(Task::cancelRequested).isEmpty()
                        || !tasks.finishCanceled(lease, validateWorkerId(workerId), now)) {
                    throw leaseLost();
                }
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            if (updated.runId() == null) {
                usage.releaseUnsubmittedMediaTask(ownerId, updated);
            }
            events.append(ownerId, lease.projectId(), taskEvent(updated, false));
            blockWaitingRunForMedia(ownerId, updated);
            return ProjectEventService.Change.unchanged(null);
        });
    }

    /** 仅将 Provider 明确拒绝记为提交失败；超时或断线属于待核对，不能调用此方法。 */
    @Transactional
    public void rejectSubmission(Task lease, String workerId, String errorCode) {
        String normalizedCode = validateErrorCode(errorCode);
        Instant now = clock.instant();
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        events.recordChange(ownerId, lease.projectId(), () -> {
            if (!tasks.rejectSubmission(lease, validateWorkerId(workerId), normalizedCode, now)) {
                throw leaseLost();
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            events.append(ownerId, lease.projectId(), taskEvent(updated, true));
            blockWaitingRunForMedia(ownerId, updated);
            return ProjectEventService.Change.unchanged(null);
        });
    }

    /**
     * 无法确认外部是否受理或完成时，立即按原因码转 UNKNOWN，不再等租约到期。
     *
     * <p>CAS 失败不抛异常：租约若在调用期间过期（例如租约被配得短于客户端超时），
     * {@link #recoverExpiredSubmissions} 会接手同一条记录。若这里把 {@code leaseLost()}
     * 抛出去，调度器只会记一条错误日志，任务照样停在 SUBMITTING 等满租约，且只剩笼统的
     * 兜底原因码——正是本方法与原因码改造要消除的现象。
     *
     * @return 是否由本次调用写入；{@code false} 表示租约已失效，交由兜底扫描处理
     */
    @Transactional
    public boolean markSubmissionUnknown(Task lease, String workerId, String errorCode) {
        String normalizedCode = validateErrorCode(errorCode);
        Instant now = clock.instant();
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        return events.recordChange(ownerId, lease.projectId(), () -> {
            if (!tasks.markSubmissionUnknown(lease, validateWorkerId(workerId), normalizedCode,
                    now)) {
                return ProjectEventService.Change.unchanged(false);
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            events.append(ownerId, lease.projectId(), taskEvent(updated, true));
            // 必须转过等待中的 Run：人工重试要求 Run 处于 BLOCKED/WAITING_TASKS。
            blockWaitingRunForMedia(ownerId, updated);
            return ProjectEventService.Change.unchanged(true);
        }).value();
    }

    /** 外部请求受理且原请求 ID 已落库后释放 Worker 租约，后续由轮询任务接续。 */
    @Transactional
    public void waitForProvider(
            Task lease,
            String workerId,
            String providerRequestId,
            Instant nextActionAt) {
        String requestId = providerRequestId == null ? "" : providerRequestId.trim();
        Instant now = clock.instant();
        if (requestId.isEmpty()
                || requestId.length() > 240
                || nextActionAt == null
                || nextActionAt.isBefore(now)) {
            throw validation("Provider requestId 或下次核对时间无效。");
        }
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        events.recordChange(ownerId, lease.projectId(), () -> {
            if (!tasks.acknowledgeSubmission(lease.id(), validateWorkerId(workerId),
                    lease.leaseEpoch(), requestId, nextActionAt, now)) {
                throw leaseLost();
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            return ProjectEventService.Change.changed(null, taskEvent(updated, false));
        });
    }

    /** 原请求仍在执行时只更新下一次查询时间，不重新提交生成请求。 */
    @Transactional
    public void deferProviderPoll(Task lease, String workerId, Instant nextActionAt) {
        Instant now = clock.instant();
        if (lease.providerRequestId() == null || nextActionAt == null
                || !nextActionAt.isAfter(now)) {
            throw validation("原 Provider requestId 与未来核对时间必填。");
        }
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        events.recordChange(ownerId, lease.projectId(), () -> {
            if (!tasks.deferProviderPoll(lease.id(), validateWorkerId(workerId),
                    lease.leaseEpoch(), nextActionAt, now)) {
                throw leaseLost();
            }
            tasks.clearProviderPollFailures(lease.id());
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            return ProjectEventService.Change.changed(null, taskEvent(updated, true));
        });
    }

    /** 已受理请求查询、下载或归档失败时只重试该阶段，不重试外部生成提交。 */
    @Transactional
    public void retryProviderPoll(Task lease, String workerId, String errorCode) {
        String code = validateErrorCode(errorCode);
        int failures = tasks.providerPollFailureCount(lease.id()) + 1;
        if (failures > 5) {
            blockProviderPoll(lease, workerId, "PROVIDER_POLL_RETRY_EXHAUSTED");
        } else {
            long baseSeconds = 5L << (failures - 1);
            long jitter = ThreadLocalRandom.current().nextLong(-baseSeconds / 5,
                    baseSeconds / 5 + 1);
            deferProviderPoll(lease, workerId,
                    clock.instant().plusSeconds(baseSeconds + jitter));
        }
        tasks.recordProviderPollFailure(lease.id(), failures, code, clock.instant());
    }

    /** 原配置不再可安全查询时阻断轮询并保留已受理请求记录，避免隐去可能发生的外部费用。 */
    @Transactional
    public void blockProviderPoll(Task lease, String workerId, String errorCode) {
        String code = validateErrorCode(errorCode);
        Instant now = clock.instant();
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        events.recordChange(ownerId, lease.projectId(), () -> {
            if (!tasks.blockProviderPoll(lease.id(), validateWorkerId(workerId),
                    lease.leaseEpoch(), code, now)) {
                throw leaseLost();
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            events.append(ownerId, lease.projectId(), taskEvent(updated, true));
            blockWaitingRunForMedia(ownerId, updated);
            return ProjectEventService.Change.unchanged(null);
        });
    }

    /** 在网络调用前以当前租约写入 SUBMITTING 和固定 requestKey，这是识别不确定提交的检查点。 */
    @Transactional
    public UUID beginSubmission(Task lease, String workerId) {
        return beginSubmission(lease, workerId, null);
    }

    /** 仅当适配器确实把固定 requestKey 作为外部请求 ID 发送时，记录已校验的 Provider origin 摘要。 */
    @Transactional
    public UUID beginSubmission(Task lease, String workerId, String candidateOriginSha256) {
        return beginSubmission(lease, workerId, candidateOriginSha256, null);
    }

    /** Version-pinned tasks recheck and lock both catalog rows in the checkpoint transaction. */
    @Transactional
    public UUID beginSubmission(Task lease, String workerId, String candidateOriginSha256,
            MediaCapabilityBinding binding) {
        if (candidateOriginSha256 != null
                && !candidateOriginSha256.matches("[0-9a-f]{64}")) {
            throw validation("Provider origin 指纹无效。");
        }
        Instant now = clock.instant();
        UUID requestKey = UUID.randomUUID();
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        return events.recordChange(ownerId, lease.projectId(), () -> {
            // The project-row lock also serializes draft and card changes, closing the
            // preflight-to-submission race before any provider request is sent.
            if (projects.get(ownerId, lease.projectId()).status() == Project.Status.ARCHIVED) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "TASK_PROJECT_ARCHIVED",
                        "项目已归档", "项目归档后不会提交新的媒体生成请求。", false);
            }
            if (binding != null && !tasks.lockCurrentMediaBinding(binding)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "MEDIA_CAPABILITY_CHANGED",
                        "媒体能力已变化", "连接或能力已停用、修改，请重新检查草稿。", false);
            }
            if (!pinnedMediaInputsCurrent(ownerId, lease)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "TASK_INPUT_STALE",
                        "任务输入已过期", "输入图片或草稿已修改，旧媒体任务不会提交生成请求。", false);
            }
            if (!tasks.beginSubmission(lease.id(), validateWorkerId(workerId), lease.leaseEpoch(),
                    UUID.randomUUID(), requestKey, candidateOriginSha256, now)) {
                throw leaseLost();
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            return ProjectEventService.Change.changed(requestKey, taskEvent(updated, false));
        }).value();
    }

    /**
     * 将租约已过期且仍处于提交中的任务转为待核对状态，并阻断依赖它的等待中 Run。
     * 该恢复路径只更新原任务，不会再次向 Provider 提交生成请求。
     *
     * @param requestedLimit 本轮最多扫描的记录数，实际还受配置上限约束
     * @return 本轮成功重新分类的任务数
     */
    public int recoverExpiredSubmissions(int requestedLimit) {
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("恢复扫描 limit 必须为正数。");
        }
        Instant now = clock.instant();
        int recovered = 0;
        for (TaskRepository.ExpiredSubmission candidate : tasks.findExpiredSubmissions(now, limit)) {
            if (events.recordChange(candidate.ownerId(), candidate.projectId(), () -> {
                        if (!tasks.recoverExpiredSubmission(candidate.taskId(), now)) {
                            return ProjectEventService.Change.unchanged(false);
                        }
                        Task task = tasks.findById(candidate.taskId()).orElseThrow();
                        events.append(candidate.ownerId(), candidate.projectId(),
                                taskEvent(task, true));
                        blockWaitingRunForMedia(candidate.ownerId(), task);
                        return ProjectEventService.Change.unchanged(true);
                    }).value()) {
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * 关闭取消后仍占有过期租约的本地任务，释放未提交媒体或导出的用量预留。
     * 不恢复旧 Run，也不把本地关闭解释为 Provider 已取消。
     *
     * @param requestedLimit 本轮最多扫描的记录数，实际还受配置上限约束
     * @return 本轮成功关闭的任务数
     */
    public int recoverExpiredCancellations(int requestedLimit) {
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("恢复扫描 limit 必须为正数。");
        }
        Instant now = clock.instant();
        int recovered = 0;
        for (TaskRepository.ExpiredSubmission candidate : tasks.findExpiredCanceledRunning(now, limit)) {
            if (events.recordChange(candidate.ownerId(), candidate.projectId(), () -> {
                        if (!tasks.finishExpiredCanceledRunning(candidate.taskId(), now)) {
                            return ProjectEventService.Change.unchanged(false);
                        }
                        Task task = tasks.findById(candidate.taskId()).orElseThrow();
                        if (task.runId() == null
                                && (task.kind() == Task.Kind.IMAGE_GENERATION
                                        || task.kind() == Task.Kind.VIDEO_GENERATION)
                                && task.providerRequestId() == null) {
                            usage.releaseUnsubmittedMediaTask(candidate.ownerId(), task);
                        }
                        events.append(candidate.ownerId(), candidate.projectId(),
                                taskEvent(task, false));
                        return ProjectEventService.Change.unchanged(true);
                    }).value()) {
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * 将取消后晚到的结果写入原任务历史，再以同一租约完成本地取消状态；重复完成只允许同 epoch 重放。
     *
     * @param lease 原任务的租约快照
     * @param workerId 写回时必须匹配的 Worker 标识
     * @param output 晚到结果，供历史核查而不触发下游
     * @param now 本次记录结果的时间
     */
    private void preserveCanceledResult(Task lease, String workerId, JsonNode output, Instant now) {
        if (!tasks.recordLateResult(lease, output, now)) {
            throw leaseLost();
        }
        if (!tasks.finishCanceled(lease, validateWorkerId(workerId), now)) {
            Task current = tasks.findById(lease.id()).orElseThrow(this::notFound);
            if (current.status() != Task.Status.CANCELED
                    || current.leaseEpoch() != lease.leaseEpoch()) {
                throw leaseLost();
            }
        }
    }

    /**
     * 同 Run 的媒体任务失败、阻断或外部结果未知时，阻断仍在等待任务的 Run。
     *
     * @param ownerId 服务端确定的项目所有者 ID
     * @param task 发生终态失败或待核对状态的媒体任务
     */
    private void blockWaitingRunForMedia(UUID ownerId, Task task) {
        if (task.runId() == null
                || (task.kind() != Task.Kind.IMAGE_GENERATION
                        && task.kind() != Task.Kind.VIDEO_GENERATION)
                || (task.status() != Task.Status.FAILED
                        && task.status() != Task.Status.UNKNOWN
                        && task.status() != Task.Status.BLOCKED)) {
            return;
        }
        AgentRun run = runs.get(ownerId, task.projectId(), task.runId());
        if (run.status() == AgentRun.Status.WAITING_TASKS) {
            runs.transition(ownerId, task.projectId(), task.runId(), run.version(),
                    AgentRun.Status.BLOCKED);
        }
    }

    /**
     * 生成任务状态事件。只暴露任务 ID、状态、取消标记及潜在外部费用提示，不包含任务输入或密钥。
     *
     * @param task 状态已变化的持久化任务
     * @param possibleExternalCost 调用方已知可能发生外部费用时强制标记
     * @return 可与任务状态同事务写入的事件草稿
     */
    private ProjectEventService.EventDraft taskEvent(Task task, boolean possibleExternalCost) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", task.id().toString());
        payload.put("status", task.status().name());
        payload.put("cancelRequested", task.cancelRequested());
        payload.put("possibleExternalCost", possibleExternalCost
                || task.providerRequestId() != null
                || ((task.kind() == Task.Kind.IMAGE_GENERATION
                        || task.kind() == Task.Kind.VIDEO_GENERATION)
                        && (task.status() == Task.Status.SUBMITTING
                                || task.status() == Task.Status.WAITING_PROVIDER
                                || task.status() == Task.Status.UNKNOWN
                                || task.status() == Task.Status.CANCELED)));
        return new ProjectEventService.EventDraft("task.status.changed", 1,
                task.id(), task.version(), payload);
    }

    /**
     * 图片任务核验参考图仍为原版本；视频任务还核验输入图片固定的精确版本未变。
     * 任一资源不可访问或输入结构损坏时返回 false，使旧结果只能保留在历史。
     *
     * @param ownerId 用于重新鉴权固定媒体输入的项目所有者 ID
     * @param task 创建时钉住输入图片的媒体任务
     * @return 自动选用生成结果的输入前提仍成立时为 true
     */
    private boolean pinnedMediaInputsCurrent(UUID ownerId, Task task) {
        JsonNode input = task.input();
        if (input.has("imageOperation")) {
            try {
                artifacts.requireImageVersionForTask(ownerId, task.projectId(), UUID.fromString(
                        input.path("imageOperation").path("sourceVersionId").asText()));
                return true;
            } catch (ApiProblemException | IllegalArgumentException unavailable) {
                return false;
            }
        }
        boolean cardOwnedDirectTask = input.hasNonNull("canvasItemId");
        if (cardOwnedDirectTask && input.has("mediaInput")) {
            try {
                UUID canvasItemId = UUID.fromString(input.path("canvasItemId").asText());
                MediaDraft current = mediaDrafts.get(ownerId, task.projectId(), canvasItemId);
                JsonNode frozen = input.path("mediaInput");
                String currentMode = task.kind() == Task.Kind.IMAGE_GENERATION
                        ? MediaDraft.VideoInputMode.GENERAL_REFERENCE.name()
                        : current.videoInputMode().name();
                if (!currentMode.equals(frozen.path("mode").asText())
                        || current.imageInputs().size() != frozen.path("images").size()) {
                    return false;
                }
                for (int index = 0; index < current.imageInputs().size(); index++) {
                    MediaDraft.ImageInput currentImage = current.imageInputs().get(index);
                    JsonNode frozenImage = frozen.path("images").get(index);
                    if (frozenImage == null
                            || !currentImage.versionId().toString().equals(
                                    frozenImage.path("versionId").asText())
                            || !currentImage.role().name().equals(
                                    frozenImage.path("role").asText())
                            || currentImage.order() != frozenImage.path("order").asInt(-1)) {
                        return false;
                    }
                    artifacts.requireImageVersionForTask(ownerId, task.projectId(),
                            currentImage.versionId());
                }
                return true;
            } catch (ApiProblemException | IllegalArgumentException unavailable) {
                return false;
            }
        }
        return true;
    }

    /** 校验并去除步骤键首尾空白，保证持久化幂等键长度为 1 至 160 字符。 */
    private String validateStepKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw validation("stepKey 必须为 1 至 160 个字符。");
        }
        return normalized;
    }

    /** 校验并去除 Worker 标识首尾空白，避免空标识参与租约条件更新。 */
    private String validateWorkerId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw validation("workerId 必须为 1 至 160 个字符。");
        }
        return normalized;
    }

    /** 将缺省失败归为 TASK_FAILED，并限制外部错误码进入持久化记录的长度。 */
    private String validateErrorCode(String errorCode) {
        String normalized = errorCode == null ? "TASK_FAILED" : errorCode.trim();
        if (normalized.isEmpty() || normalized.length() > 120) {
            throw validation("Task errorCode 必须为 1 至 120 个字符。");
        }
        return normalized;
    }

    /** 对当前 JSON 文本计算 SHA-256，用于比较同一步骤或命令的原始输入载荷。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /** 将任务不存在与越权读取统一映射为不会泄露资源存在性的 404 错误。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Task 不存在",
                "Task 不存在或当前用户无权访问。",
                false);
    }

    /** 当前 Worker 或租约 epoch 已失效时返回 409，拒绝旧结果写回。 */
    private ApiProblemException leaseLost() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "TASK_LEASE_LOST",
                "Task 租约已失效",
                "当前 Worker 或 leaseEpoch 已过期，结果未写入。",
                false);
    }

    /** 将任务命令的输入校验失败映射为稳定的 HTTP 400 错误。 */
    private ApiProblemException validation(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Task 请求无效",
                detail,
                false);
    }
}
