package dev.agenvas.task.application;

import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.ProviderAttempt;
import dev.agenvas.shared.error.ApiProblemException;
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

/** Creates dependency-aware Tasks and applies every worker mutation through a fencing token. */
@Service
public class TaskService {

    private final AgentRunService runs;
    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final CanvasService canvas;
    private final TaskRepository tasks;
    private final TaskProperties properties;
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TaskService(
            AgentRunService runs,
            ProjectService projects,
            ArtifactService artifacts,
            CanvasService canvas,
            TaskRepository tasks,
            TaskProperties properties,
            ProjectEventService events,
            UsageService usage,
            ObjectMapper objectMapper,
            Clock clock) {
        this.runs = runs;
        this.projects = projects;
        this.artifacts = artifacts;
        this.canvas = canvas;
        this.tasks = tasks;
        this.properties = properties;
        this.events = events;
        this.usage = usage;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Persists one Task with immutable input and same-Run dependency validation. */
    @Transactional
    public Task create(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            UUID planId,
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
                planId,
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

    /** Creates a media Task pinned to the selected output Artifact version at command time. */
    @Transactional
    public Task createMediaTask(UUID ownerId, UUID projectId, UUID runId, UUID planId,
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
        Task task = create(ownerId, projectId, runId, planId, stepKey, kind, input,
                providerId, attemptNo, dependencyIds);
        tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                target.id(), target.currentVersionId(), target.version(), null));
        return task;
    }

    /** Creates an approved media Task whose new Artifact identity is materialized on completion. */
    @Transactional
    public Task createMediaTaskForNewOutput(UUID ownerId, UUID projectId, UUID runId,
            UUID planId, String stepKey, Task.Kind kind, JsonNode input, UUID providerId,
            int attemptNo, List<UUID> dependencyIds, String requestedOutputSlotKey) {
        if (kind != Task.Kind.IMAGE_GENERATION && kind != Task.Kind.VIDEO_GENERATION) {
            throw validation("只有图片或视频生成任务可以声明新媒体输出槽位。");
        }
        String outputSlotKey = validateStepKey(requestedOutputSlotKey);
        Task task = create(ownerId, projectId, runId, planId, stepKey, kind, input,
                providerId, attemptNo, dependencyIds);
        tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                null, null, 0, outputSlotKey));
        return task;
    }

    /** Reads one Task through its project owner boundary. */
    @Transactional(readOnly = true)
    public Task get(UUID ownerId, UUID projectId, UUID taskId) {
        return tasks.find(ownerId, projectId, taskId).orElseThrow(this::notFound);
    }

    /** Persists one project-level export with an immutable snapshot and replay-safe command key. */
    @Transactional
    public Task createProjectExport(UUID ownerId, UUID projectId, String requestedStepKey,
            JsonNode input, long expectedProjectVersion) {
        String stepKey = validateStepKey(requestedStepKey);
        if (input == null || !input.isObject()) {
            throw validation("导出输入快照必须是对象。");
        }
        String inputHash = sha256(input.toString());
        return events.recordChange(ownerId, projectId, () -> {
            Task existing = tasks.findExportByStepKey(ownerId, projectId, stepKey).orElse(null);
            if (existing != null) {
                if (!existing.inputHash().equals(inputHash)) {
                    throw new ApiProblemException(HttpStatus.CONFLICT,
                            "IDEMPOTENCY_CONFLICT", "导出请求冲突",
                            "相同幂等键已用于不同的导出输入。", false);
                }
                return ProjectEventService.Change.unchanged(existing);
            }
            if (projects.requireActiveProject(ownerId, projectId).version()
                    != expectedProjectVersion) {
                throw new ApiProblemException(HttpStatus.CONFLICT,
                        "PROJECT_VERSION_CONFLICT", "项目设置已变化",
                        "请重新检查画幅后再创建导出。", false);
            }
            Instant now = clock.instant();
            Task export = new Task(UUID.randomUUID(), projectId, null, null, stepKey,
                    Task.Kind.MEDIA_EXPORT, Task.Status.READY, false, input.deepCopy(),
                    inputHash, null, null, null, 1, now, null, null, 0, 0,
                    null, now, now, null);
            tasks.create(export, List.of());
            usage.reserveExportTask(ownerId, export);
            events.append(ownerId, projectId, taskEvent(export, false));
            return ProjectEventService.Change.unchanged(export);
        }).value();
    }

    /** Returns recent project exports even after their Agent Run is no longer active. */
    @Transactional(readOnly = true)
    public List<Task> listProjectExports(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return tasks.listExports(ownerId, projectId);
    }

    /** Finds an already committed export for exact command replay after project edits. */
    @Transactional(readOnly = true)
    public Task findProjectExportByStepKey(UUID ownerId, UUID projectId, String stepKey) {
        return tasks.findExportByStepKey(ownerId, projectId, validateStepKey(stepKey))
                .orElse(null);
    }

    /** Stops a queued export immediately or flags its live local process for cancellation. */
    @Transactional
    public Task cancelProjectExport(UUID ownerId, UUID projectId, UUID taskId) {
        return events.recordChange(ownerId, projectId, () -> {
            Task current = get(ownerId, projectId, taskId);
            if (current.kind() != Task.Kind.MEDIA_EXPORT) {
                throw validation("只能取消项目导出任务。");
            }
            if (current.status() == Task.Status.CANCELED || current.cancelRequested()
                    || current.status() == Task.Status.SUCCEEDED
                    || current.status() == Task.Status.FAILED) {
                return ProjectEventService.Change.unchanged(current);
            }
            if (!tasks.requestExportCancellation(projectId, taskId, clock.instant())) {
                throw leaseLost();
            }
            Task updated = get(ownerId, projectId, taskId);
            if (updated.status() == Task.Status.CANCELED) {
                usage.releaseExportTask(ownerId, updated);
            }
            events.append(ownerId, projectId, taskEvent(updated, false));
            return ProjectEventService.Change.unchanged(updated);
        }).value();
    }

    /** True for cancellation or replacement of the fenced export worker. */
    @Transactional(readOnly = true)
    public boolean exportShouldStop(Task lease) {
        Task current = tasks.findById(lease.id()).orElseThrow(this::notFound);
        return current.kind() != Task.Kind.MEDIA_EXPORT || current.cancelRequested()
                || current.leaseEpoch() != lease.leaseEpoch()
                || current.status() != Task.Status.RUNNING;
    }

    /** Lists all Tasks belonging to one owner-scoped Run for snapshot recovery. */
    @Transactional(readOnly = true)
    public List<Task> listByRun(UUID ownerId, UUID projectId, UUID runId) {
        runs.get(ownerId, projectId, runId);
        return tasks.listByRun(ownerId, projectId, runId);
    }

    /** Shows durable submission identifiers after independently authorizing the Task. */
    @Transactional(readOnly = true)
    public List<ProviderAttempt> listProviderAttempts(UUID ownerId, UUID projectId, UUID taskId) {
        get(ownerId, projectId, taskId);
        return tasks.listProviderAttempts(ownerId, projectId, taskId);
    }

    /** Polling may use only the origin recorded by the accepted submission attempt. */
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

    /** Shows an explicit-risk successor without hiding the old UNKNOWN Task. */
    @Transactional(readOnly = true)
    public UUID replacementTaskId(UUID ownerId, UUID projectId, UUID taskId) {
        get(ownerId, projectId, taskId);
        return tasks.findManualReplacement(projectId, taskId)
                .map(TaskRepository.ManualReplacement::replacementTaskId).orElse(null);
    }

    /** Authorizes one unresolved attempt that actually carries a provider lookup ID. */
    @Transactional(readOnly = true)
    public ReconciliationCandidate reconciliationCandidate(UUID ownerId, UUID projectId,
            UUID taskId) {
        Task task = get(ownerId, projectId, taskId);
        if (task.status() != Task.Status.UNKNOWN || task.cancelRequested()
                || task.providerRequestId() != null
                || tasks.findManualReplacement(projectId, taskId).isPresent()
                || (task.kind() != Task.Kind.IMAGE_GENERATION
                        && task.kind() != Task.Kind.VIDEO_GENERATION)) {
            throw reconciliationConflict();
        }
        List<ProviderAttempt> candidates = tasks.listProviderAttempts(ownerId, projectId, taskId)
                .stream().filter(attempt -> attempt.status() == ProviderAttempt.Status.UNKNOWN
                        && attempt.candidateRequestId() != null
                        && attempt.candidateOriginSha256() != null
                        && attempt.candidateRequestId().equals(attempt.requestKey()))
                .toList();
        if (candidates.size() != 1) throw reconciliationConflict();
        return new ReconciliationCandidate(task, candidates.getFirst());
    }

    /** A read-only provider proof may attach the original ID, but never creates a new attempt. */
    @Transactional
    public Task resumeVerifiedOriginal(UUID ownerId, UUID projectId, UUID taskId,
            long expectedVersion, UUID attemptId, UUID candidateRequestId,
            String candidateOriginSha256) {
        Instant now = clock.instant();
        return events.recordChange(ownerId, projectId, () -> {
            Task current = get(ownerId, projectId, taskId);
            if (current.status() == Task.Status.WAITING_PROVIDER
                    && candidateRequestId.toString().equals(current.providerRequestId())) {
                return ProjectEventService.Change.unchanged(current);
            }
            if (!tasks.recoverUnknownSubmission(projectId, taskId, expectedVersion,
                    attemptId, candidateRequestId, candidateOriginSha256, now)) {
                throw reconciliationConflict();
            }
            Task updated = tasks.findById(taskId).orElseThrow(this::notFound);
            AgentRun run = runs.get(ownerId, projectId, updated.runId());
            if (run.status() == AgentRun.Status.BLOCKED
                    && pinnedShotIsCurrent(ownerId, updated)
                    && tasks.listByRun(ownerId, projectId, run.id()).stream()
                            .noneMatch(item -> item.status() == Task.Status.UNKNOWN
                                    || item.status() == Task.Status.BLOCKED
                                    || item.status() == Task.Status.FAILED
                                    || item.status() == Task.Status.CANCELED)) {
                AgentRun running = runs.transition(ownerId, projectId, run.id(),
                        run.version(), AgentRun.Status.RUNNING);
                runs.transition(ownerId, projectId, run.id(), running.version(),
                        AgentRun.Status.WAITING_TASKS);
            }
            events.append(ownerId, projectId, taskEvent(updated, true));
            return ProjectEventService.Change.unchanged(updated);
        }).value();
    }

    /** Snapshot used across the external read and the fenced recovery transaction. */
    public record ReconciliationCandidate(Task task, ProviderAttempt attempt) {}

    /** Reads recent unresolved UNKNOWN work without depending on the active Run slot. */
    @Transactional(readOnly = true)
    public List<Task> listUnknown(UUID ownerId, UUID projectId) {
        return tasks.listUnknown(ownerId, projectId);
    }

    /** Claims a bounded batch in a short transaction; caller executes work after return. */
    @Transactional
    public List<Task> claimDue(String requestedWorkerId, int requestedLimit) {
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("claim limit 必须为正数。");
        }
        Instant now = clock.instant();
        return tasks.claimDue(
                workerId, limit, now, now.plus(properties.leaseDuration()));
    }

    /** Claims only image tasks so a Mock image adapter never consumes unimplemented video work. */
    @Transactional
    public List<Task> claimImagesDue(String requestedWorkerId, int requestedLimit) {
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("claim limit 必须为正数。");
        }
        Instant now = clock.instant();
        return tasks.claimDueImages(workerId, limit, now,
                now.plus(properties.leaseDuration()));
    }

    /** Claims at most one ComfyUI image under the persisted cross-instance dispatch gate. */
    @Transactional
    public List<Task> claimComfyImage(String requestedWorkerId) {
        String workerId = validateWorkerId(requestedWorkerId);
        Instant now = clock.instant();
        return tasks.claimDueComfyImage(workerId, now,
                now.plus(properties.leaseDuration()));
    }

    /** Claims one video submission only while the shared ComfyUI slot is free. */
    @Transactional
    public List<Task> claimComfyVideo(String requestedWorkerId) {
        String workerId = validateWorkerId(requestedWorkerId);
        Instant now = clock.instant();
        return tasks.claimDueComfyVideo(workerId, now,
                now.plus(properties.leaseDuration()));
    }

    /** Keeps video claims separate from image and Agent worker domains. */
    @Transactional
    public List<Task> claimVideosDue(String requestedWorkerId, int requestedLimit) {
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("claim limit 必须为正数。");
        }
        Instant now = clock.instant();
        return tasks.claimDueVideos(workerId, limit, now,
                now.plus(properties.leaseDuration()));
    }

    /** Claims only saved external request ids; canceled Runs remain queryable for late archival. */
    @Transactional
    public List<Task> claimProviderPolls(String requestedWorkerId, int requestedLimit) {
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) throw validation("claim limit 必须为正数。");
        Instant now = clock.instant();
        return tasks.claimDueProviderPolls(workerId, limit, now,
                now.plus(properties.leaseDuration()));
    }

    /** ComfyUI image worker cannot steal an accepted video request. */
    @Transactional
    public List<Task> claimComfyImagePolls(String requestedWorkerId, int requestedLimit) {
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) throw validation("claim limit 必须为正数。");
        Instant now = clock.instant();
        return tasks.claimDueComfyImagePolls(workerId, limit, now,
                now.plus(properties.leaseDuration()));
    }

    /** Video pollers can never consume a saved image request. */
    @Transactional
    public List<Task> claimComfyVideoPolls(String requestedWorkerId, int requestedLimit) {
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) throw validation("claim limit 必须为正数。");
        Instant now = clock.instant();
        return tasks.claimDueComfyVideoPolls(workerId, limit, now,
                now.plus(properties.leaseDuration()));
    }

    /** Claims only project-level exports with their own bounded lease. */
    @Transactional
    public List<Task> claimExportsDue(String requestedWorkerId, int requestedLimit) {
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("claim limit 必须为正数。");
        }
        Instant now = clock.instant();
        return tasks.claimDueExports(workerId, limit, now,
                now.plus(properties.leaseDuration()));
    }

    /** Resolves a claimed task's trusted owner without accepting an owner id from the model. */
    @Transactional(readOnly = true)
    public UUID ownerForWorker(Task task) {
        Task current = tasks.findById(task.id()).orElseThrow(this::notFound);
        if (!current.projectId().equals(task.projectId())
                || !java.util.Objects.equals(current.runId(), task.runId())) {
            throw leaseLost();
        }
        return tasks.ownerId(task.id()).orElseThrow(this::notFound);
    }

    /** Claims only model-turn work for the bounded Run scheduler. */
    @Transactional
    public List<Task> claimAgentTurns(String requestedWorkerId, int requestedLimit) {
        String workerId = validateWorkerId(requestedWorkerId);
        int limit = Math.min(requestedLimit, properties.maxClaimBatch());
        if (limit < 1) {
            throw validation("claim limit 必须为正数。");
        }
        Instant now = clock.instant();
        return tasks.claimDueAgentTurns(workerId, limit, now,
                now.plus(properties.leaseDuration()));
    }

    /** Extends an unexpired lease without sharing the long-work execution thread. */
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

    /** Commits a successful result only for the current lease epoch, then promotes dependents. */
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
                if (lease.kind() == Task.Kind.MEDIA_EXPORT) {
                    usage.settleExportTask(ownerId, lease);
                }
                if (lease.runId() != null) {
                    tasks.promoteReady(lease.projectId(), lease.runId(), now);
                }
            } else if (tasks.findById(lease.id()).filter(Task::cancelRequested).isPresent()) {
                preserveCanceledResult(lease, workerId, output, now);
            } else {
                throw leaseLost();
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            if (updated.kind() == Task.Kind.MEDIA_EXPORT
                    && updated.status() == Task.Status.CANCELED) {
                usage.releaseExportTask(ownerId, updated);
            }
            events.append(ownerId, lease.projectId(), taskEvent(updated, false));
            return ProjectEventService.Change.unchanged(null);
        });
    }

    /** Rechecks the durable image-result gate after a human selects a keyframe. */
    @Transactional
    public void promoteAfterKeyframeSelection(UUID projectId, UUID runId) {
        tasks.promoteReady(projectId, runId, clock.instant());
    }

    /**
     * Archives a synchronous media result as a new immutable ArtifactVersion. The Task's pinned
     * selection is applied only when its Run remains active and no user edit has changed the
     * current Artifact; canceled late results remain historical and never promote dependents.
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
            AgentRun run = runs.get(ownerId, lease.projectId(), lease.runId());
            boolean canceled = current.cancelRequested()
                    || run.status() == AgentRun.Status.CANCEL_REQUESTED
                    || run.status() == AgentRun.Status.CANCELED;
            boolean projectArchived = projects.get(ownerId, lease.projectId()).status()
                    == Project.Status.ARCHIVED;
            boolean selectResult = !canceled && !projectArchived
                    && pinnedShotIsCurrent(ownerId, lease);
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
            if (lease.kind() == Task.Kind.VIDEO_GENERATION
                    && lease.input().has("imageVersionId")
                    && !content.path("keyframeVersionId").asText("")
                            .equals(lease.input().path("imageVersionId").asText())) {
                throw validation("视频结果必须引用任务钉住的关键帧版本。");
            }
            UUID artifactId;
            ArtifactService.TaskVersionResult result;
            if (target.artifactId() == null) {
                Artifact.Kind kind = lease.kind() == Task.Kind.IMAGE_GENERATION
                        ? Artifact.Kind.IMAGE : Artifact.Kind.VIDEO;
                ArtifactService.ArtifactView created = artifacts.createFromTaskWithinChange(ownerId,
                        lease.projectId(), lease.runId(), kind,
                        target.outputSlotKey(), content);
                artifactId = created.artifact().id();
                result = new ArtifactService.TaskVersionResult(
                        created.currentVersion().id(), selectResult);
                if (selectResult) {
                    canvas.placeGeneratedArtifactWithinChange(ownerId, lease.projectId(),
                            run.agentInstanceId(), artifactId);
                }
            } else {
                artifactId = target.artifactId();
                result = artifacts.appendTaskVersionWithinChange(ownerId,
                        lease.projectId(), artifactId, lease.runId(),
                        target.expectedCurrentVersionId(), target.expectedArtifactVersion(),
                        content, selectResult);
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
            } else if (selectResult) {
                tasks.promoteReady(lease.projectId(), lease.runId(), now);
            } else {
                AgentRun latest = runs.get(ownerId, lease.projectId(), lease.runId());
                if (latest.status() == AgentRun.Status.WAITING_TASKS) {
                    runs.transition(ownerId, lease.projectId(), lease.runId(),
                            latest.version(), AgentRun.Status.BLOCKED);
                }
            }
            tasks.clearProviderPollFailures(lease.id());
            usage.settleMediaTask(ownerId, lease);
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("taskId", lease.id().toString());
            payload.put("status", updated.status().name());
            payload.put("artifactId", artifactId.toString());
            payload.put("artifactVersionId", result.versionId().toString());
            payload.put("selected", result.selected());
            payload.put("possibleExternalCost", true);
            events.append(ownerId, lease.projectId(),
                    new ProjectEventService.EventDraft("task.status.changed", 1,
                            lease.id(), updated.version(), payload));
            return ProjectEventService.Change.unchanged(result);
        }).value();
    }

    /** Commits a confirmed local failure only for the current lease epoch. */
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
            if (updated.planId() != null
                    && (updated.kind() == Task.Kind.IMAGE_GENERATION
                            || updated.kind() == Task.Kind.VIDEO_GENERATION)
                    && before.status() == Task.Status.RUNNING
                    && before.providerRequestId() == null
                    && (updated.status() == Task.Status.FAILED
                            || updated.status() == Task.Status.CANCELED)) {
                usage.releaseUnsubmittedMediaTask(ownerId, updated);
            }
            if (updated.kind() == Task.Kind.MEDIA_EXPORT
                    && (updated.status() == Task.Status.FAILED
                            || updated.status() == Task.Status.CANCELED)) {
                usage.releaseExportTask(ownerId, updated);
            }
            events.append(ownerId, lease.projectId(), taskEvent(updated, false));
            blockWaitingRunForMedia(ownerId, updated);
            return ProjectEventService.Change.unchanged(null);
        });
    }

    /** Holds an obsolete pre-submission media task for a fresh user-approved plan. */
    @Transactional
    public void blockStaleInput(Task lease, String workerId) {
        blockPreSubmission(lease, workerId, "TASK_INPUT_STALE");
    }

    /** Fences work that became invalid before any external submission checkpoint. */
    @Transactional
    public void blockPreSubmission(Task lease, String workerId, String errorCode) {
        if (!"TASK_INPUT_STALE".equals(errorCode)
                && !"TASK_PROJECT_ARCHIVED".equals(errorCode)) {
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
            if (updated.planId() != null) {
                usage.releaseUnsubmittedMediaTask(ownerId, updated);
            }
            events.append(ownerId, lease.projectId(), taskEvent(updated, false));
            blockWaitingRunForMedia(ownerId, updated);
            return ProjectEventService.Change.unchanged(null);
        });
    }

    /** Records a Provider's explicit rejection after the SUBMITTING checkpoint, not a timeout. */
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

    /** Releases the worker immediately after an external request has a durable request id. */
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

    /** A pending query schedules another query of the same request, never a new submission. */
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

    /** Retries only query/download/archive of an accepted request, never its submission. */
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

    /** Keeps a billed/accepted external request visible when the old config cannot query it. */
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

    /** Persists the only safe pre-network checkpoint for one fenced provider attempt. */
    @Transactional
    public UUID beginSubmission(Task lease, String workerId) {
        return beginSubmission(lease, workerId, null);
    }

    /** Marks only adapters that actually send the committed key to a pinned Provider origin. */
    @Transactional
    public UUID beginSubmission(Task lease, String workerId, String candidateOriginSha256) {
        if (candidateOriginSha256 != null
                && !candidateOriginSha256.matches("[0-9a-f]{64}")) {
            throw validation("Provider origin 指纹无效。");
        }
        Instant now = clock.instant();
        UUID requestKey = UUID.randomUUID();
        UUID ownerId = tasks.ownerId(lease.id()).orElseThrow(this::notFound);
        return events.recordChange(ownerId, lease.projectId(), () -> {
            // This project-row lock also serializes shot revisions, closing the
            // preflight-to-submission race before any provider request is sent.
            if (projects.get(ownerId, lease.projectId()).status() == Project.Status.ARCHIVED) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "TASK_PROJECT_ARCHIVED",
                        "项目已归档", "项目归档后不会提交新的媒体生成请求。", false);
            }
            if (!pinnedShotIsCurrent(ownerId, lease)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "TASK_INPUT_STALE",
                        "任务输入已过期", "镜头已修改，旧媒体任务不会提交生成请求。", false);
            }
            if (!tasks.beginSubmission(lease.id(), validateWorkerId(workerId), lease.leaseEpoch(),
                    UUID.randomUUID(), requestKey, candidateOriginSha256, now)) {
                throw leaseLost();
            }
            Task updated = tasks.findById(lease.id()).orElseThrow(this::notFound);
            return ProjectEventService.Change.changed(requestKey, taskEvent(updated, false));
        }).value();
    }

    /** Reclassifies abandoned submissions without enqueueing a second external request. */
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

    /** Closes expired canceled local leases without ever resurrecting the old Run. */
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
                        if (task.planId() != null
                                && (task.kind() == Task.Kind.IMAGE_GENERATION
                                        || task.kind() == Task.Kind.VIDEO_GENERATION)
                                && task.providerRequestId() == null) {
                            usage.releaseUnsubmittedMediaTask(candidate.ownerId(), task);
                        }
                        if (task.kind() == Task.Kind.MEDIA_EXPORT) {
                            usage.releaseExportTask(candidate.ownerId(), task);
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

    /** An approved DAG cannot keep waiting after a terminal failure or ambiguous submission. */
    private void blockWaitingRunForMedia(UUID ownerId, Task task) {
        if (task.planId() == null
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

    /** Planned media stays tied to its exact shot version; legacy internal fixtures have no shot pin. */
    private boolean pinnedShotIsCurrent(UUID ownerId, Task task) {
        JsonNode input = task.input();
        if (!input.has("shotArtifactId") && !input.has("shotVersionId")) {
            return true;
        }
        try {
            UUID shotId = UUID.fromString(input.path("shotArtifactId").asText());
            UUID versionId = UUID.fromString(input.path("shotVersionId").asText());
            ArtifactService.ArtifactView shot = artifacts.get(ownerId, task.projectId(), shotId);
            return shot.artifact().kind() == Artifact.Kind.SHOT
                    && shot.artifact().archivedAt() == null
                    && versionId.equals(shot.currentVersion().id());
        } catch (ApiProblemException | IllegalArgumentException invalidOrInaccessible) {
            return false;
        }
    }

    private String validateStepKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw validation("stepKey 必须为 1 至 160 个字符。");
        }
        return normalized;
    }

    private String validateWorkerId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw validation("workerId 必须为 1 至 160 个字符。");
        }
        return normalized;
    }

    private String validateErrorCode(String errorCode) {
        String normalized = errorCode == null ? "TASK_FAILED" : errorCode.trim();
        if (normalized.isEmpty() || normalized.length() > 120) {
            throw validation("Task errorCode 必须为 1 至 120 个字符。");
        }
        return normalized;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Task 不存在",
                "Task 不存在或当前用户无权访问。",
                false);
    }

    private ApiProblemException leaseLost() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "TASK_LEASE_LOST",
                "Task 租约已失效",
                "当前 Worker 或 leaseEpoch 已过期，结果未写入。",
                false);
    }

    private ApiProblemException reconciliationConflict() {
        return new ApiProblemException(HttpStatus.CONFLICT, "TASK_RECONCILIATION_CONFLICT",
                "无法恢复原 Provider 请求", "任务或提交记录已变化，或没有可核对的原请求 ID。", false);
    }

    private ApiProblemException validation(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Task 请求无效",
                detail,
                false);
    }
}
