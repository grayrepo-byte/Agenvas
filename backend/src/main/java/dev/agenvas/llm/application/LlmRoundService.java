package dev.agenvas.llm.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/** 模型单回合调用入口：在网络请求前后分别保存检查点，工具执行由后续 Runtime 处理。 */
@Service
public class LlmRoundService {

    /** 将检查点中的精确图片引用转换为发送时的受控预览，不持久化图片字节。 */
    private final AgentImageInputService images;
    private final TaskService tasks;
    /** 使用 Run 钉住的配置调用模型，且关闭自动工具执行。 */
    private final ChatGateway gateway;
    private final CallLogService callLogs;
    /** 将模型可见消息与完整响应转换为版本化持久化协议。 */
    private final LlmProtocolCodec codec;
    /** 保存请求与响应检查点，并支持已完成回合的重放。 */
    private final LlmTurnCheckpointService checkpoints;
    /** 从可信项目作用域读取本次 Run 固定的模型配置版本。 */
    private final AgentRunRepository runs;

    /** 组装模型调用、协议编解码、回合检查点和 Run 配置固定读取服务。
     * @param gateway 调用配置固定模型并返回完整响应
     * @param codec 保存实际请求和响应的应用协议格式
     * @param checkpoints 在网络调用前后持久化幂等回合检查点
     * @param runs 读取服务端固定的 Run 模型身份
     */
    public LlmRoundService(ChatGateway gateway,
            LlmProtocolCodec codec, LlmTurnCheckpointService checkpoints,
            AgentRunRepository runs, CallLogService callLogs, TaskService tasks, AgentImageInputService images) {
        this.images = images;
        this.tasks = tasks;
        this.callLogs = callLogs;
        this.gateway = gateway;
        this.codec = codec;
        this.checkpoints = checkpoints;
        this.runs = runs;
    }

    /**
     * 先持久化本轮请求；如响应已保存则直接返回，否则调用钉住的模型配置并持久化完整响应。
     * 网络调用前主动检查没有活动数据库事务，返回值可安全交给后续工具执行阶段。
     *
     * @param ownerId 服务端认证得到的项目所有者 ID
     * @param projectId 本次 Run 所属项目 ID
     * @param runId 本次 Run ID，用于读取固定模型配置
     * @param stepIndex 本轮模型步骤序号，也是检查点去重键的一部分
     * @param messages 本轮实际发送给模型的消息序列
     * @param tools 本轮按策略允许模型看到的工具定义
     * @param trustedContext 服务端生成的模型调用上下文，不接受模型自报身份
     * @return 已提交到回合检查点的完整模型响应
     */
    public JsonNode call(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            List<Message> messages, List<ToolCallback> tools, Map<String, Object> trustedContext) {
        return callInternal(ownerId, projectId, runId, stepIndex, messages, tools, trustedContext, null, null);
    }

    /** Worker path: incremental public text and the final checkpoint use the same task fencing epoch. */
    public JsonNode callLeased(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            List<Message> messages, List<ToolCallback> tools, Map<String, Object> trustedContext,
            Task lease, String workerId) {
        if (lease == null) throw new IllegalArgumentException("Missing model task lease");
        return callInternal(ownerId, projectId, runId, stepIndex, messages, tools, trustedContext, lease, workerId);
    }

    private JsonNode callInternal(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            List<Message> messages, List<ToolCallback> tools, Map<String, Object> trustedContext,
            Task lease, String workerId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Model calls must not hold a database transaction");
        }
        JsonNode request = codec.request(messages, tools);
        AgentRun run = runs.find(ownerId, projectId, runId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                        "RUN_NOT_FOUND", ApiMessage.of("api.llm-round-service.run-does-not-exist"), ApiMessage.of("api.llm-round-service.unable-to-access-the-project-s-runtime"), false));
        ChatGateway.ConfigIdentity selected = new ChatGateway.ConfigIdentity(
                run.policySnapshot().path("modelConfigSource").asText(""),
                run.policySnapshot().path("modelConfigVersion").asInt(-1));
        gateway.requireToolCalling(selected);
        int configVersion = selected.version();
        String configSource = selected.source();
        LlmTurn turn = lease == null
                ? checkpoints.reserve(ownerId, projectId, runId, stepIndex, configVersion, configSource, request)
                : checkpoints.reserveLeased(ownerId, projectId, runId, stepIndex, configVersion, configSource,
                        request, lease, workerId);
        // 响应已落库时不再发起第二次模型调用，避免恢复路径重复消耗用量。
        if (turn.status() == LlmTurn.Status.RESPONDED) {
            return turn.response();
        }
        List<Message> dispatched = images.hydrate(ownerId, projectId, runId, run.contextSnapshot(), messages);
        ChatGateway.ModelDetails model = gateway.modelDetailsFor(selected);
        boolean mock = "mock".equals(selected.source());
        CallLogService.CallDescriptor descriptor = new CallLogService.CallDescriptor(
                projectId, null, runId, stepIndex, CallLog.Kind.LLM, CallLog.Operation.CHAT,
                mock ? "mock" : model.providerAdapter(), model.modelId(), mock);
        PublicStreamSink stream = lease == null ? null : new PublicStreamSink(lease, workerId);
        if (lease != null) tasks.startAgentStream(lease, workerId);
        ChatGateway.Exchange exchange = stream == null
                ? callLogs.record(descriptor, () -> modelCall(() -> gateway.call(dispatched, tools, trustedContext, selected)),
                        value -> CallLogService.CallOutcome.succeeded(value.response().getMetadata().getId()))
                : callLogs.recordStream(descriptor, (captureContent, log) -> modelCall(() -> gateway.callStreaming(
                        dispatched, tools, trustedContext, selected, stream, captureContent, log)),
                        value -> CallLogService.CallOutcome.succeeded(value.response().getMetadata().getId()));
        if (exchange.configVersion() != turn.modelConfigVersion()) {
            throw new IllegalStateException("ChatGateway configuration changed during model call");
        }
        JsonNode response = codec.response(exchange.response());
        return (lease == null
                ? checkpoints.saveResponse(ownerId, projectId, runId, stepIndex, exchange.configVersion(), response)
                : checkpoints.saveResponseLeased(ownerId, projectId, runId, stepIndex, exchange.configVersion(),
                        response, lease, workerId)).response();
    }
    private ChatGateway.Exchange modelCall(java.util.function.Supplier<ChatGateway.Exchange> call) {
        try { return call.get(); }
        catch (RuntimeException failure) {
            ModelCallFailure gatewayFailure = new ModelCallFailure(failure);
            if (AgentModelRetryPolicy.retryableCode(gatewayFailure) != null) throw gatewayFailure;
            throw failure;
        }
    }

    /** The gateway delivers timed public batches; each callback completes one fenced short transaction. */
    private final class PublicStreamSink implements Consumer<String> {
        private final Task lease;
        private final String workerId;
        private long chunkIndex;

        private PublicStreamSink(Task lease, String workerId) {
            this.lease = lease;
            this.workerId = workerId;
        }

        @Override
        public void accept(String delta) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Model stream was interrupted");
            if (delta == null || delta.isEmpty()) return;
            chunkIndex = tasks.appendAgentStream(lease, workerId, chunkIndex, delta);
        }
    }
}
