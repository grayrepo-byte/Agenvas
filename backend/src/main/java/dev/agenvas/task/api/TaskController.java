package dev.agenvas.task.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.ManualUnknownRetryService;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 按认证所有者暴露任务状态和人工重试；Worker 租约只在服务端使用。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class TaskController {

    /** 在所有者和项目范围内读取任务。 */
    private final TaskService tasks;
    /** 为用户发起的独立新尝试创建替代任务。 */
    private final ManualUnknownRetryService retries;

    /** 注入任务查询和人工重试服务。 */
    public TaskController(TaskService tasks, ManualUnknownRetryService retries) {
        this.tasks = tasks;
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

    /** 为 UNKNOWN 任务创建一次独立预留的新媒体任务；同幂等键重放返回原新任务。 */
    @PostMapping("/tasks/{taskId}/new-attempt")
    public TaskResponse newAttempt(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NewAttemptRequest request) {
        return TaskResponse.from(retries.create(principal.userId(), projectId, taskId,
                request.expectedTaskVersion(), idempotencyKey));
    }

    /** 请求必须带用户读取到的任务版本，防止对已变化的 UNKNOWN 任务重复重试。
     * @param expectedTaskVersion 用户读取到的 UNKNOWN 任务版本
     */
    public record NewAttemptRequest(long expectedTaskVersion) {}

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
            JsonNode input = task.input();
            JsonNode output = task.output();
            if (task.kind() == Task.Kind.TEXT_GENERATION) {
                ObjectNode safeInput = (ObjectNode) task.input().deepCopy();
                safeInput.remove("currentText");
                input = safeInput;
                // A RUNNING Task may contain the private full-response checkpoint. Only the
                // terminal public artifact summary is returned to the browser.
                output = task.status() == Task.Status.SUCCEEDED
                        ? task.output().path("result") : null;
            }
            return new TaskResponse(
                    task.id(),
                    task.projectId(),
                    task.runId(),
                    task.planId(),
                    task.stepKey(),
                    task.kind(),
                    task.status(),
                    task.cancelRequested(),
                    input,
                    output,
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
