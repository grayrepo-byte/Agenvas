package dev.agenvas.task.application;

import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 认领任务后在数据库事务外执行本地或 Provider 工作，再以租约 epoch 提交短事务结果。 */
public class TaskWorker {

    /** 负责短事务认领、提交前检查点和带 fencing 的结果写入。 */
    private final TaskService tasks;
    private final CallLogService callLogs;

    /** 注入持久化认领和带租约状态转换服务。
     * @param tasks 管理任务认领、提交检查点和完成结果
     */
    /** 注入持久化任务认领和租约状态转换服务。
     * @param tasks 负责认领任务、保存提交检查点及条件提交结果
     */
    public TaskWorker(TaskService tasks, CallLogService callLogs) {
        this.callLogs = callLogs;
        this.tasks = tasks;
    }

    /**
     * 认领到期任务后逐个交给本地处理器；媒体任务先保存提交检查点，过期输入直接阻断。
     *
     * @param workerId 后续状态写入必须匹配的租约持有者标识
     * @param limit 本次最多认领的任务数
     * @param handler 在认领事务外执行实际工作的处理器
     * @return 已认领任务数，含提交前被阻断的任务
     */
    public int runOnce(String workerId, int limit, Handler handler) {
        List<Task> claimed = tasks.claimDue(workerId, limit);
        for (Task task : claimed) {
            boolean media = task.kind() == Task.Kind.IMAGE_GENERATION
                    || task.kind() == Task.Kind.VIDEO_GENERATION;
            if (media && beginOrFailStale(task, workerId, null) == null) {
                continue;
            }
            Outcome outcome = media
                    ? callLogs.record(descriptor(task, CallLog.Operation.SUBMIT),
                            () -> handler.execute(task), this::callOutcome)
                    : handler.execute(task);
            persistOutcome(task, workerId, media, outcome);
        }
        return claimed.size();
    }

    /**
     * 只认领图片生成任务，并把预先落库的 requestKey 交给 Mock 图片适配器。
     *
     * @param workerId 本次租约持有者标识
     * @param limit 最多认领的图片任务数
     * @param handler 使用固定 requestKey 提交或生成图片的处理器
     * @return 已认领的图片任务数
     */
    public int runImagesOnce(String workerId, int limit, MediaHandler handler) {
        return runClaimedImages(tasks.claimImagesDue(workerId, limit), workerId, handler, null);
    }

    /**
     * 认领至多一个旧版 ComfyUI 图片请求；任务行租约只防止重复领取，不限制其他请求并行。
     *
     * @param workerId 本次租约持有者标识
     * @param handler 已验证 ComfyUI origin 的图片适配器
     * @return 已认领的任务数，只会是 0 或 1
     */
    public int runComfyImagesOnce(String workerId, MediaHandler handler) {
        return runClaimedImages(tasks.claimComfyImage(workerId), workerId, handler,
                handler.candidateOriginSha256());
    }

    /**
     * 图片提交前先检查适配器配置；只有通过预检和镜头输入核对的任务才写 SUBMITTING 检查点并调用外部处理器。
     *
     * @param claimed 已由数据库事务认领的图片任务
     * @param workerId 当前租约持有者
     * @param handler 图片适配器，实际调用发生在认领事务之外
     * @param candidateOriginSha256 提交前验证的 Provider origin 摘要，Mock 路径为空
     * @return 本批已认领任务数
     */
    private int runClaimedImages(List<Task> claimed, String workerId, MediaHandler handler,
            String candidateOriginSha256) {
        for (Task task : claimed) {
            String preflightFailure = handler.preflightFailure(task);
            if (preflightFailure != null) {
                tasks.fail(task, workerId, preflightFailure);
                continue;
            }
            UUID requestKey = beginOrFailStale(task, workerId, candidateOriginSha256);
            if (requestKey == null) {
                continue;
            }
            Outcome outcome = callLogs.record(descriptor(task, CallLog.Operation.SUBMIT),
                    () -> handler.execute(task, requestKey), this::callOutcome);
            persistOutcome(task, workerId, true, outcome);
        }
        return claimed.size();
    }

    /**
     * 只认领视频生成任务；每次外部提交前均保存带租约的 SUBMITTING 检查点。
     *
     * @param workerId 本次租约持有者标识
     * @param limit 最多认领的视频任务数
     * @param handler 使用已保存 requestKey 的视频适配器
     * @return 已认领的视频任务数
     */
    public int runVideosOnce(String workerId, int limit, MediaHandler handler) {
        return runClaimedVideos(tasks.claimVideosDue(workerId, limit), workerId, handler, null);
    }

    /**
     * 认领至多一个旧版 ComfyUI 视频请求，不再与图片任务共享容量门禁。
     *
     * @param workerId 本次租约持有者标识
     * @param handler 已验证 ComfyUI origin 的视频适配器
     * @return 已认领的任务数，只会是 0 或 1
     */
    public int runComfyVideosOnce(String workerId, MediaHandler handler) {
        return runClaimedVideos(tasks.claimComfyVideo(workerId), workerId, handler,
                handler.candidateOriginSha256());
    }

    /**
     * 对每个视频任务执行配置预检、输入新鲜度检查及提交前检查点，拒绝后不再联系 Provider。
     *
     * @param claimed 已由数据库事务认领的视频任务
     * @param workerId 当前租约持有者
     * @param handler 视频适配器
     * @param candidateOriginSha256 提交前验证的 Provider origin 摘要，Mock 路径为空
     * @return 本批已认领任务数
     */
    private int runClaimedVideos(List<Task> claimed, String workerId, MediaHandler handler,
            String candidateOriginSha256) {
        for (Task task : claimed) {
            String preflightFailure = handler.preflightFailure(task);
            if (preflightFailure != null) {
                tasks.fail(task, workerId, preflightFailure);
                continue;
            }
            UUID requestKey = beginOrFailStale(task, workerId, candidateOriginSha256);
            if (requestKey == null) {
                continue;
            }
            Outcome outcome = callLogs.record(descriptor(task, CallLog.Operation.SUBMIT),
                    () -> handler.execute(task, requestKey), this::callOutcome);
            persistOutcome(task, workerId, true, outcome);
        }
        return claimed.size();
    }

    /**
     * 只轮询已保存外部请求 ID 的任务；该路径没有重新提交生成请求的入口。
     *
     * @param workerId 轮询任务的租约持有者
     * @param limit 本次最多查询的已受理任务数
     * @param handler 按原请求 ID 查询 Provider 状态的只读处理器
     * @return 已认领轮询任务数
     */
    public int runProviderPollsOnce(String workerId, int limit, ProviderPollHandler handler) {
        return runClaimedProviderPolls(tasks.claimProviderPolls(workerId, limit),
                workerId, handler);
    }

    /**
     * ComfyUI 图片轮询仅认领图片适配器已受理的原请求，不消费其他媒体任务。
     *
     * @param workerId 轮询任务的租约持有者
     * @param handler 按原图片请求 ID 查询的适配器
     * @return 已认领的任务数，只会是 0 或 1
     */
    public int runComfyImagePollsOnce(String workerId, ProviderPollHandler handler) {
        return runClaimedProviderPolls(tasks.claimComfyImagePolls(workerId, 1),
                workerId, handler);
    }

    /**
     * ComfyUI 视频轮询仅查询此前已确认保存的 prompt_id。
     *
     * @param workerId 轮询任务的租约持有者
     * @param handler 按原视频 prompt_id 查询的适配器
     * @return 已认领的任务数，只会是 0 或 1
     */
    public int runComfyVideoPollsOnce(String workerId, ProviderPollHandler handler) {
        return runClaimedProviderPolls(tasks.claimComfyVideoPolls(workerId, 1),
                workerId, handler);
    }

    /**
     * 查询失败仅安排原请求的再次查询；完成时归档结果，确认失败时结束任务，配置漂移时保留待处理状态。
     *
     * @param claimed 已认领且持有原 Provider 请求 ID 的任务
     * @param workerId 当前轮询租约持有者
     * @param handler 只读查询原请求状态的适配器
     * @return 本批已认领任务数
     */
    private int runClaimedProviderPolls(List<Task> claimed, String workerId,
            ProviderPollHandler handler) {
        for (Task task : claimed) {
            PollResult outcome;
            try {
                outcome = callLogs.record(descriptor(task, CallLog.Operation.POLL),
                        () -> handler.query(task), result -> pollOutcome(result, task.providerRequestId()));
            } catch (RuntimeException failure) {
                tasks.retryProviderPoll(task, workerId, "PROVIDER_POLL_TECHNICAL_FAILURE");
                continue;
            }
            switch (outcome) {
                case PollPending pending ->
                    tasks.deferProviderPoll(task, workerId, pending.nextActionAt());
                case PollGenerated generated ->
                    tasks.succeedWithArtifact(task, workerId, generated.content());
                case PollFailed failed -> tasks.fail(task, workerId, failed.errorCode());
                case PollBlocked blocked ->
                    tasks.blockProviderPoll(task, workerId, blocked.errorCode());
            }
        }
        return claimed.size();
    }

    private CallLogService.CallDescriptor descriptor(Task task, CallLog.Operation operation) {
        String workflow = task.input().path("workflowVersion").asText("");
        boolean mock = workflow.startsWith("mock-");
        String provider = mock ? "mock" : workflow.startsWith("comfyui-") ? "comfyui" : null;
        return new CallLogService.CallDescriptor(task.projectId(), task.id(), task.runId(), null,
                task.kind() == Task.Kind.IMAGE_GENERATION ? CallLog.Kind.IMAGE : CallLog.Kind.VIDEO,
                operation, provider, null, mock);
    }

    private CallLogService.CallOutcome callOutcome(Outcome result) {
        return switch (result) {
            case Failed failed -> new CallLogService.CallOutcome(CallLog.Status.FAILED, null, failed.errorCode());
            case WaitingProvider waiting -> CallLogService.CallOutcome.succeeded(waiting.providerRequestId());
            default -> CallLogService.CallOutcome.succeeded(null);
        };
    }

    private CallLogService.CallOutcome pollOutcome(PollResult result, String requestId) {
        return switch (result) {
            case PollFailed failed -> new CallLogService.CallOutcome(CallLog.Status.FAILED, requestId, failed.errorCode());
            case PollBlocked blocked -> new CallLogService.CallOutcome(CallLog.Status.FAILED, requestId, blocked.errorCode());
            default -> CallLogService.CallOutcome.succeeded(requestId);
        };
    }

    /**
     * 在外部提交前创建持久化请求键；镜头输入已变或项目已归档时阻断旧计划，且不联系 Provider。
     *
     * @param task 已认领的媒体任务及固定输入快照
     * @param workerId 当前租约持有者
     * @param candidateOriginSha256 已预检的 Provider origin 摘要，Mock 路径为空
     * @return 已提交到数据库的 requestKey；输入过期或项目归档时为空
     */
    private UUID beginOrFailStale(Task task, String workerId, String candidateOriginSha256) {
        try {
            return tasks.beginSubmission(task, workerId, candidateOriginSha256);
        } catch (ApiProblemException problem) {
            if (!"TASK_INPUT_STALE".equals(problem.code())
                    && !"TASK_PROJECT_ARCHIVED".equals(problem.code())) {
                throw problem;
            }
            tasks.blockPreSubmission(task, workerId, problem.code());
            return null;
        }
    }

    /**
     * 将适配器结果转换为带租约检查的任务状态写入；媒体明确拒绝与普通本地失败采用不同转换。
     *
     * @param task 已认领任务快照
     * @param workerId 当前租约持有者
     * @param media 是否存在外部媒体提交检查点
     * @param outcome 适配器给出的成功、失败或等待 Provider 结果
     */
    private void persistOutcome(Task task, String workerId, boolean media, Outcome outcome) {
        switch (outcome) {
                case Succeeded succeeded -> tasks.succeed(task, workerId, succeeded.output());
                case GeneratedArtifact generated ->
                    tasks.succeedWithArtifact(task, workerId, generated.content());
                case Failed failed -> {
                    if (media) {
                        tasks.rejectSubmission(task, workerId, failed.errorCode());
                    } else {
                        tasks.fail(task, workerId, failed.errorCode());
                    }
                }
                case WaitingProvider waiting -> tasks.waitForProvider(
                        task,
                        workerId,
                        waiting.providerRequestId(),
                        waiting.nextActionAt());
        }
    }

    /** 在数据库事务外执行本地或外部工作；媒体请求在调用前已保存 SUBMITTING 检查点。 */
    @FunctionalInterface
    public interface Handler {
        /**
         * @param task 已认领的任务快照
         * @return 供应用服务带租约提交的标准化结果
         */
        Outcome execute(Task task);
    }

    /** 以持久化 requestKey 提交媒体生成请求的适配器边界。 */
    @FunctionalInterface
    public interface MediaHandler {
        /** 仅在 Provider 会原样使用本地请求键时返回已验证 origin 摘要；其他适配器返回空值。 */
        default String candidateOriginSha256() {
            return null;
        }

        /**
         * 在保存提交检查点前拒绝失效配置，避免占用请求键后才发现本地不可执行。
         *
         * @param task 将要提交的媒体任务
         * @return 稳定错误码；配置可用时为空
         */
        default String preflightFailure(Task task) {
            return null;
        }

        /**
         * @param task 已通过预检并保存提交检查点的媒体任务
         * @param requestKey 数据库预先保存的原请求键
         * @return 适配器确认的提交或生成结果
         */
        Outcome execute(Task task, UUID requestKey);
    }

    /** 只查询任务中保存的原 providerRequestId，不提供重新提交生成请求的方法。 */
    @FunctionalInterface
    public interface ProviderPollHandler {
        /**
         * @param task 包含原 providerRequestId 的已认领任务
         * @return 等待、已生成、明确失败或需人工处理的查询结果
         */
        PollResult query(Task task);
    }

    /** 一次只读状态查询的封闭结果类型。 */
    public sealed interface PollResult permits PollPending, PollGenerated, PollFailed,
            PollBlocked {}

    /** @param nextActionAt 原请求下一次允许查询的时间，当前轮询租约会被释放 */
    public record PollPending(Instant nextActionAt) implements PollResult {}

    /** @param content 从原请求取得、准备归档到任务固定目标的媒体内容 */
    public record PollGenerated(JsonNode content) implements PollResult {}

    /** @param errorCode Provider 明确报告的终态失败代码，不代表查询超时 */
    public record PollFailed(String errorCode) implements PollResult {}

    /** @param errorCode 阻断原请求继续轮询的配置或安全错误代码 */
    public record PollBlocked(String errorCode) implements PollResult {}

    /** 外部提交或本地执行返回的封闭结果类型。 */
    public sealed interface Outcome permits Succeeded, Failed, WaitingProvider,
            GeneratedArtifact {}

    /** @param output 本地任务已确认成功的结构化输出 */
    public record Succeeded(JsonNode output) implements Outcome {}

    /** @param content 同步得到的媒体内容，归档时仍须校验任务固定版本与选择条件 */
    public record GeneratedArtifact(JsonNode content) implements Outcome {}

    /** @param errorCode Provider 明确拒绝或本地处理确认失败的稳定错误码 */
    public record Failed(String errorCode) implements Outcome {}

    /**
     * @param providerRequestId Provider 已受理请求的原始 ID，后续轮询只能查询此 ID
     * @param nextActionAt 首次轮询原请求的计划时间
     */
    public record WaitingProvider(String providerRequestId, Instant nextActionAt)
            implements Outcome {}
}
