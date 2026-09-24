package dev.agenvas.task.application;

import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.ProviderAttempt;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Persistence boundary for dependency-aware Task creation and fenced lease mutations. */
public interface TaskRepository {

    /** Inserts a Task and its already validated same-project dependencies. */
    void create(Task task, List<UUID> dependencyIds);

    /** Binds a media Task to the exact Artifact selection observed at creation. */
    void createArtifactTarget(ArtifactTarget target);

    /** Reads the immutable output target captured before execution. */
    Optional<ArtifactTarget> findArtifactTarget(UUID taskId);

    /** Reads one Task through its project owner boundary. */
    Optional<Task> find(UUID ownerId, UUID projectId, UUID taskId);

    /** Reads only submission identifiers for one owner-scoped media Task. */
    List<ProviderAttempt> listProviderAttempts(UUID ownerId, UUID projectId, UUID taskId);

    /** Reads the explicit-risk command that superseded one ambiguous Task, if any. */
    Optional<ManualReplacement> findManualReplacement(UUID projectId, UUID originalTaskId);

    /** Detects a reused HTTP command key before any new Task or usage reservation is written. */
    Optional<ManualReplacement> findManualReplacementByKey(UUID projectId, UUID ownerId,
            String idempotencyKey);

    /** Creates the immutable audit link after the new Task is inserted in the same transaction. */
    void createManualReplacement(ManualReplacement replacement);

    /** Copies the original Task's pinned execution dependencies to the new attempt. */
    List<UUID> dependencyIds(UUID projectId, UUID taskId);

    /** Finds every not-yet-executed consumer that must follow the replacement instead. */
    List<Task> dependentTasks(UUID projectId, UUID taskId);

    /** Rewires PENDING consumers and increments their versions for event replay. */
    List<Task> rewirePendingDependents(UUID projectId, UUID originalTaskId,
            UUID replacementTaskId, Instant now);

    /** Internal task lookup for fenced mutation classification. */
    Optional<Task> findById(UUID taskId);

    /** Resolves the authoritative project owner for internal eventful worker mutations. */
    Optional<UUID> ownerId(UUID taskId);

    /** Lists the immutable-order Task summary for one owned Run. */
    List<Task> listByRun(UUID ownerId, UUID projectId, UUID runId);

    /** Returns unresolved ambiguous submissions even after their Run released the slot. */
    List<Task> listUnknown(UUID ownerId, UUID projectId);

    /** Counts attempted generation tasks under the caller's locked Run for budget checks. */
    long countByRunAndKind(UUID projectId, UUID runId, Task.Kind kind);

    /** Atomically claims non-Agent due rows using SKIP LOCKED. */
    List<Task> claimDue(String workerId, int limit, Instant now, Instant leaseUntil);

    /** Atomically claims only due image work for the installed Mock image adapter. */
    List<Task> claimDueImages(String workerId, int limit, Instant now, Instant leaseUntil);

    /** Serializes ComfyUI claims against the global persisted one-external-job slot. */
    List<Task> claimDueComfyImage(String workerId, Instant now, Instant leaseUntil);

    /** Video shares the same ComfyUI dispatch gate with image submissions. */
    List<Task> claimDueComfyVideo(String workerId, Instant now, Instant leaseUntil);

    /** Finds an idempotent project-level export by its fixed client command key. */
    Optional<Task> findExportByStepKey(UUID ownerId, UUID projectId, String stepKey);

    /** Lists recent project exports independently of any active Agent Run. */
    List<Task> listExports(UUID ownerId, UUID projectId);

    /** Atomically marks one READY export canceled or requests cancel of its live worker. */
    boolean requestExportCancellation(UUID projectId, UUID taskId, Instant now);

    /** Claims only due project-level export work. */
    List<Task> claimDueExports(String workerId, int limit, Instant now, Instant leaseUntil);

    /** Atomically claims only approved video work for the installed video adapter. */
    List<Task> claimDueVideos(String workerId, int limit, Instant now, Instant leaseUntil);

    /** Claims only previously acknowledged external requests; never a fresh submission. */
    List<Task> claimDueProviderPolls(String workerId, int limit, Instant now, Instant leaseUntil);

    /** Limits the ComfyUI image poller to image Tasks, never video/provider peers. */
    List<Task> claimDueComfyImagePolls(String workerId, int limit, Instant now,
            Instant leaseUntil);

    /** Claims only accepted ComfyUI video requests for the video adapter. */
    List<Task> claimDueComfyVideoPolls(String workerId, int limit, Instant now,
            Instant leaseUntil);

    /** Separately claims durable model turns so media workers never execute LLM work. */
    List<Task> claimDueAgentTurns(String workerId, int limit, Instant now, Instant leaseUntil);

    /** Locks and verifies one live Agent-turn lease before a business side effect. */
    boolean lockActiveAgentTurnLease(UUID projectId, UUID runId, UUID taskId,
            String workerId, long leaseEpoch, Instant now);

    /** Extends only the current unexpired fenced lease. */
    boolean heartbeat(
            UUID taskId,
            String workerId,
            long leaseEpoch,
            Instant now,
            Instant leaseUntil);

    /** Completes only the current unexpired fenced lease. */
    boolean finish(
            UUID taskId,
            String workerId,
            long leaseEpoch,
            Task.Status terminalStatus,
            JsonNode output,
            String errorCode,
            Instant now);

    /** Blocks a stale, unsubmitted media lease without recording a terminal failure. */
    boolean blockStaleInput(UUID taskId, String workerId, long leaseEpoch,
            String errorCode, Instant now);

    /** Promotes PENDING tasks only when every predecessor has succeeded. */
    int promoteReady(UUID projectId, UUID runId, Instant now);

    /** Writes a pre-network submission checkpoint only for the current uncanceled lease. */
    boolean beginSubmission(UUID taskId, String workerId, long leaseEpoch, UUID attemptId,
            UUID requestKey, String candidateOriginSha256, Instant now);

    /** Saves a known external request id without ever resubmitting the original request. */
    boolean acknowledgeSubmission(UUID taskId, String workerId, long leaseEpoch,
            String providerRequestId, Instant nextActionAt, Instant now);

    /** Attaches only a provider-verified original ID to an unchanged UNKNOWN checkpoint. */
    boolean recoverUnknownSubmission(UUID projectId, UUID taskId, long expectedVersion,
            UUID attemptId, UUID candidateRequestId, String candidateOriginSha256, Instant now);

    /** Releases a polling lease while preserving the original provider request id. */
    boolean deferProviderPoll(UUID taskId, String workerId, long leaseEpoch,
            Instant nextActionAt, Instant now);

    /** Reads the consecutive technical failures of this original accepted request. */
    int providerPollFailureCount(UUID taskId);

    /** Updates retry diagnostics in the same transaction as the fenced Task transition. */
    void recordProviderPollFailure(UUID taskId, int count, String errorCode, Instant now);

    /** A successful provider query or final archive resets consecutive failure history. */
    void clearProviderPollFailures(UUID taskId);

    /** Preserves an accepted request but blocks unsafe polling after configuration drift. */
    boolean blockProviderPoll(UUID taskId, String workerId, long leaseEpoch,
            String errorCode, Instant now);

    /** Finds expired checkpoints without locking, for per-project eventful recovery. */
    List<ExpiredSubmission> findExpiredSubmissions(Instant now, int limit);

    /** Conditionally classifies one still-expired checkpoint as UNKNOWN. */
    boolean recoverExpiredSubmission(UUID taskId, Instant now);

    /** Finds canceled local work whose worker disappeared before acknowledging cancellation. */
    List<ExpiredSubmission> findExpiredCanceledRunning(Instant now, int limit);

    /** Closes one expired pre-submission lease after a durable cancel request. */
    boolean finishExpiredCanceledRunning(UUID taskId, Instant now);

    /** Saves a result that arrived after cancellation without changing the current Task output. */
    boolean recordLateResult(Task lease, JsonNode output, Instant now);

    /** Marks an in-flight canceled Task terminal after preserving its late result. */
    boolean finishCanceled(Task lease, String workerId, Instant now);

    /** Fenced success transition from a synchronous media submission. */
    boolean finishSubmitting(Task lease, String workerId, JsonNode output, Instant now);

    /** Fenced completion of a previously acknowledged request after polling its original id. */
    boolean finishProviderResult(Task lease, String workerId, JsonNode output, Instant now);

    /** Fenced terminal failure only after an explicit, unambiguous Provider rejection. */
    boolean rejectSubmission(Task lease, String workerId, String errorCode, Instant now);

    /** Immutable content selection precondition for one Task. */
    record ArtifactTarget(UUID taskId, UUID projectId, UUID artifactId,
            UUID expectedCurrentVersionId, long expectedArtifactVersion,
            String outputSlotKey) {}

    /** Immutable proof that a user accepted duplicate cost for exactly one replacement Task. */
    record ManualReplacement(UUID projectId, UUID originalTaskId, UUID replacementTaskId,
            UUID approvedByUserId, long originalTaskVersion, String idempotencyKey,
            Instant createdAt) {}

    /** Owner and project scope for one expired submission. */
    record ExpiredSubmission(UUID taskId, UUID projectId, UUID ownerId) {}
}
