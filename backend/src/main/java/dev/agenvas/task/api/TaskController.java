package dev.agenvas.task.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.ManualUnknownRetryService;
import dev.agenvas.task.application.UnknownTaskReconciler;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.ProviderAttempt;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Owner-scoped Task status and original-request reconciliation; worker leases stay internal. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class TaskController {

    private final TaskService tasks;
    private final ObjectProvider<UnknownTaskReconciler> reconciler;
    private final ManualUnknownRetryService retries;

    public TaskController(TaskService tasks, ObjectProvider<UnknownTaskReconciler> reconciler,
            ManualUnknownRetryService retries) {
        this.tasks = tasks;
        this.reconciler = reconciler;
        this.retries = retries;
    }

    /** Returns durable status without exposing worker identity or credentials. */
    @GetMapping("/tasks/{taskId}")
    public TaskResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId) {
        return TaskResponse.from(tasks.get(principal.userId(), projectId, taskId));
    }

    /** Exposes only the owner-scoped external submission ledger, never worker credentials. */
    @GetMapping("/tasks/{taskId}/attempts")
    public List<ProviderAttemptResponse> attempts(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId) {
        UUID replacementId = tasks.replacementTaskId(principal.userId(), projectId, taskId);
        return tasks.listProviderAttempts(principal.userId(), projectId, taskId).stream()
                .map(attempt -> ProviderAttemptResponse.from(attempt, replacementId)).toList();
    }

    /** Queries the original provider request; absence never creates or retries a submission. */
    @PostMapping("/tasks/{taskId}/reconcile")
    public ReconciliationResponse reconcile(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID taskId) {
        UnknownTaskReconciler available = reconciler.getIfAvailable();
        if (available == null) {
            throw new ApiProblemException(HttpStatus.CONFLICT,
                    "PROVIDER_RECONCILIATION_UNAVAILABLE", "当前 Provider 不支持核对",
                    "请保留 UNKNOWN 并人工核对，系统不会重新提交生成请求。", false);
        }
        UnknownTaskReconciler.Result result = available.reconcile(principal.userId(),
                projectId, taskId);
        return new ReconciliationResponse(result.outcome(), result.task().id(),
                result.task().status(), result.task().providerRequestId());
    }

    /** Explicitly accepts duplicate-cost risk and creates one independently reserved attempt. */
    @PostMapping("/tasks/{taskId}/new-attempt")
    public TaskResponse newAttempt(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NewAttemptRequest request) {
        return TaskResponse.from(retries.create(principal.userId(), projectId, taskId,
                request.expectedTaskVersion(), request.riskAcknowledgement(), idempotencyKey));
    }

    /** Payload deliberately requires the reviewed Task version and exact risk phrase. */
    public record NewAttemptRequest(long expectedTaskVersion, String riskAcknowledgement) {}

    /** Only safe status fields are returned, never task input or provider history. */
    public record ReconciliationResponse(UnknownTaskReconciler.Outcome outcome,
            UUID taskId, Task.Status taskStatus, String providerRequestId) {}

    /** Public ledger projection intentionally omits the private endpoint fingerprint. */
    public record ProviderAttemptResponse(UUID id, UUID taskId, ProviderAttempt.Status status,
            UUID requestKey, UUID candidateRequestId, boolean reconcilable,
            String providerRequestId, UUID replacementTaskId,
            Instant createdAt, Instant updatedAt) {
        public static ProviderAttemptResponse from(ProviderAttempt attempt, UUID replacementTaskId) {
            return new ProviderAttemptResponse(attempt.id(), attempt.taskId(), attempt.status(),
                    attempt.requestKey(), attempt.candidateRequestId(),
                    attempt.status() == ProviderAttempt.Status.UNKNOWN
                            && attempt.candidateRequestId() != null
                            && attempt.candidateOriginSha256() != null
                            && replacementTaskId == null,
                    attempt.providerRequestId(), replacementTaskId,
                    attempt.createdAt(), attempt.updatedAt());
        }
    }

    /** Lists completed outputs as well as active work for recovery and keyframe choice. */
    @GetMapping("/runs/{runId}/tasks")
    public List<TaskResponse> listByRun(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID runId) {
        return tasks.listByRun(principal.userId(), projectId, runId).stream()
                .map(TaskResponse::from).toList();
    }

    /** Public task status and normalized result summary. */
    public record TaskResponse(
            UUID id,
            UUID projectId,
            UUID runId,
            UUID planId,
            String stepKey,
            Task.Kind kind,
            Task.Status status,
            boolean cancelRequested,
            JsonNode input,
            JsonNode output,
            UUID providerId,
            String providerRequestId,
            int attemptNo,
            Instant nextActionAt,
            long version,
            String errorCode,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {

        public static TaskResponse from(Task task) {
            return new TaskResponse(
                    task.id(),
                    task.projectId(),
                    task.runId(),
                    task.planId(),
                    task.stepKey(),
                    task.kind(),
                    task.status(),
                    task.cancelRequested(),
                    task.input(),
                    task.output(),
                    task.providerId(),
                    task.providerRequestId(),
                    task.attemptNo(),
                    task.nextActionAt(),
                    task.version(),
                    task.errorCode(),
                    task.createdAt(),
                    task.updatedAt(),
                    task.completedAt());
        }
    }
}
