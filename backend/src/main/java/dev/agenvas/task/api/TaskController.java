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

/** 按认证所有者暴露任务状态、原请求核对和人工新尝试；Worker 租约只在服务端使用。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class TaskController {

    /** 在所有者和项目范围内读取任务及外部提交账本。 */
    private final TaskService tasks;
    /** Provider 可选的只读原请求核对器；未配置时保留 UNKNOWN。 */
    private final ObjectProvider<UnknownTaskReconciler> reconciler;
    /** 处理用户明确确认风险后的独立新尝试。 */
    private final ManualUnknownRetryService retries;

    /** 注入任务查询、可选原请求核对和人工重试服务。 */
    public TaskController(TaskService tasks, ObjectProvider<UnknownTaskReconciler> reconciler,
            ManualUnknownRetryService retries) {
        this.tasks = tasks;
        this.reconciler = reconciler;
        this.retries = retries;
    }

    /** 返回数据库任务状态与输入、输出快照；不暴露 Worker 身份和凭证。 */
    @GetMapping("/tasks/{taskId}")
    public TaskResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId) {
        return TaskResponse.from(tasks.get(principal.userId(), projectId, taskId));
    }

    /** 只向任务所有者暴露外部提交尝试摘要；Provider origin 摘要和 Worker 凭证不进入响应。 */
    @GetMapping("/tasks/{taskId}/attempts")
    public List<ProviderAttemptResponse> attempts(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId) {
        UUID replacementId = tasks.replacementTaskId(principal.userId(), projectId, taskId);
        return tasks.listProviderAttempts(principal.userId(), projectId, taskId).stream()
                .map(attempt -> ProviderAttemptResponse.from(attempt, replacementId)).toList();
    }

    /** 对 UNKNOWN 任务只查询原 Provider 请求；查询不到也不会新建或重提生成任务。 */
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

    /** 用户显式确认可能重复收费后，使用幂等键创建一次独立预留的新媒体任务。 */
    @PostMapping("/tasks/{taskId}/new-attempt")
    public TaskResponse newAttempt(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NewAttemptRequest request) {
        return TaskResponse.from(retries.create(principal.userId(), projectId, taskId,
                request.expectedTaskVersion(), request.riskAcknowledgement(), idempotencyKey));
    }

    /** 请求必须带用户核对过的任务版本和精确风险确认值。
     * @param expectedTaskVersion 用户读取到的 UNKNOWN 任务版本
     * @param riskAcknowledgement 与服务端要求值完全一致的重复费用确认
     */
    public record NewAttemptRequest(long expectedTaskVersion, String riskAcknowledgement) {}

    /** 原请求核对结果仅返回结论、任务状态及已确认的请求 ID，不返回任务输入或 Provider 历史。
     * @param outcome 原 Provider 请求核对出的受限结论
     * @param taskId 原任务 ID
     * @param taskStatus 核对后持久化的任务状态
     * @param providerRequestId 已确认的原 Provider 请求 ID；未确认时为空
     */
    public record ReconciliationResponse(UnknownTaskReconciler.Outcome outcome,
            UUID taskId, Task.Status taskStatus, String providerRequestId) {}

    /** 提交尝试的对外投影；省略私有 Provider origin 摘要及 Worker 信息。
     * @param id 提交尝试记录 ID
     * @param taskId 所属任务 ID
     * @param status 外部提交尝试状态
     * @param requestKey 本系统生成的幂等请求键
     * @param candidateRequestId 尚待核对的候选 Provider 请求 ID
     * @param reconcilable 是否具备安全执行原请求核对的证据
     * @param providerRequestId 已确认受理后的 Provider 请求 ID
     * @param replacementTaskId 用户人工重试后替代此任务的 ID
     * @param createdAt 尝试记录创建时间
     * @param updatedAt 尝试状态更新时间
     */
    public record ProviderAttemptResponse(UUID id, UUID taskId, ProviderAttempt.Status status,
            UUID requestKey, UUID candidateRequestId, boolean reconcilable,
            String providerRequestId, UUID replacementTaskId,
            Instant createdAt, Instant updatedAt) {
        /** 只有 UNKNOWN、有候选 ID、固定 origin 且尚未替换时才标记可核对。
         * @param attempt 持久化的外部提交尝试
         * @param replacementTaskId 已替代原任务的人工重试任务；没有替代时为空
         * @return 满足所有恢复条件时为 true
         */
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

    /** 返回同一 Run 的活动与已完成任务，供恢复视图和关键帧选择使用。 */
    @GetMapping("/runs/{runId}/tasks")
    public List<TaskResponse> listByRun(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID runId) {
        return tasks.listByRun(principal.userId(), projectId, runId).stream()
                .map(TaskResponse::from).toList();
    }

    /** 任务对外投影包含所属用户可见的输入与结果，省略租约 epoch、Worker 身份和输入摘要。
     * @param id 任务 ID
     * @param projectId 所属项目 ID
     * @param runId 所属 Run；项目级导出任务时为空
     * @param planId 所属执行计划；非计划任务时为空
     * @param stepKey 计划内步骤键；不属于计划时为空
     * @param kind 任务类别
     * @param status 持久化任务状态
     * @param cancelRequested 是否已记录取消意图
     * @param input 创建时固定的任务输入快照
     * @param output 成功后归档的结果摘要；未成功时为空
     * @param providerId Provider 配置身份；未提交时为空
     * @param providerRequestId Provider 已确认受理的请求 ID
     * @param attemptNo 当前生成尝试序号
     * @param nextActionAt 下次允许 Worker 执行的时间
     * @param version 任务乐观并发版本
     * @param errorCode 稳定失败或阻塞错误码
     * @param createdAt 任务创建时间
     * @param updatedAt 最近状态更新时间
     * @param completedAt 终态完成时间；未终结时为空
     */
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

        /** 从持久化任务构造投影，不发送内部租约和外部提交尝试列表。
         * @param task 已从所属用户和项目范围读取的任务
         * @return REST 响应使用的任务视图
         */
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
