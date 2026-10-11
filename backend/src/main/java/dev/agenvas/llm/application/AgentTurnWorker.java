package dev.agenvas.llm.application;

import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 处理已持久化的模型回合任务。一次只认领一个回合，模型调用期间维持任务租约，工具执行交给独立的事务边界。
 */
@Service
public class AgentTurnWorker {

    /** 模型调用或任务失败时只记录安全摘要，不输出提示词、模型响应和工具参数。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTurnWorker.class);
    /** 单次模型请求的等待上限；超时会中断调用并把任务交给持久化失败处理。 */
    private static final Duration MODEL_TIMEOUT = LlmCallTimeouts.REQUEST;

    /** 认领模型回合任务并续租的应用服务。 */
    private final TaskService tasks;
    private final Clock clock;
    /** 提供任务租约时长，用于计算心跳间隔。 */
    private final TaskProperties taskProperties;
    /** 在短事务中推进 Run、回合任务和下一步调度。 */
    private final AgentTurnCommitService commits;
    /** 组装首轮模型可见的项目和 Agent 上下文。 */
    private final InitialModelContextService initialContext;
    /** 从已保存的工具结果组装后续模型回合。 */
    private final LlmConversationService conversation;
    /** 在模型输出无效时构造有次数限制的修复回合。 */
    private final RepairModelContextService repairContext;
    /** 完成模型请求前后的持久化检查点，返回已落库的完整响应。 */
    private final LlmRoundService rounds;
    /** 查询等待态回合先前保存的响应，避免重复请求模型。 */
    private final LlmTurnRepository turns;
    /** 从持久化响应恢复被选中的 Assistant 消息及原始工具调用。 */
    private final LlmProtocolCodec codec;
    /** 在同一事务内顺序执行一批工具；后续调用失败时整批回滚。 */
    private final ToolBatchExecutionService executor;
    /** 根据本次 Run 的策略选择可暴露给模型的工具定义。 */
    private final ToolRegistry registry;
    /** 核验本次 Run 钉住的模型配置具有工具调用能力。 */
    private final ChatGateway gateway;
    /** 独立于直连文本的有界模型调用与租约心跳生命周期。 */
    private final LeasedModelExecutor modelExecutor;

    /** 组装持久化回合认领、上下文恢复、模型调用及工具执行流程。
     * @param tasks 认领 Agent 回合任务并续租
     * @param taskProperties 提供租约期限和续租配置
     * @param commits 以短事务提交回合结果和状态变化
     * @param initialContext 构建 Run 首轮固定输入
     * @param conversation 从已保存响应组装后续消息
     * @param repairContext 构建次数受限的模型修复回合
     * @param rounds 持久化模型请求和响应检查点
     * @param turns 读取已保存的模型响应
     * @param codec 解码持久化模型协议
     * @param executor 顺序执行已保存响应中的工具调用
     * @param registry 按 Run 策略选择可用工具
     * @param gateway 调用 Run 固定的模型配置
     */
    @Autowired
    public AgentTurnWorker(TaskService tasks,
            TaskProperties taskProperties, AgentTurnCommitService commits,
            InitialModelContextService initialContext, LlmConversationService conversation,
            RepairModelContextService repairContext,
            LlmRoundService rounds, LlmTurnRepository turns, LlmProtocolCodec codec,
            ToolBatchExecutionService executor, ToolRegistry registry, ChatGateway gateway, Clock clock) {
        this(tasks, taskProperties, commits, initialContext, conversation, repairContext, rounds,
                turns, codec, executor, registry, gateway, clock,
                new LeasedModelExecutor("agent-turn", tasks, taskProperties));
    }

    AgentTurnWorker(TaskService tasks,
            TaskProperties taskProperties, AgentTurnCommitService commits,
            InitialModelContextService initialContext, LlmConversationService conversation,
            RepairModelContextService repairContext,
            LlmRoundService rounds, LlmTurnRepository turns, LlmProtocolCodec codec,
            ToolBatchExecutionService executor, ToolRegistry registry, ChatGateway gateway, Clock clock,
            LeasedModelExecutor modelExecutor) {
        this.clock = clock;
        this.tasks = tasks;
        this.taskProperties = taskProperties;
        this.commits = commits;
        this.initialContext = initialContext;
        this.conversation = conversation;
        this.repairContext = repairContext;
        this.rounds = rounds;
        this.turns = turns;
        this.codec = codec;
        this.executor = executor;
        this.registry = registry;
        this.gateway = gateway;
        this.modelExecutor = modelExecutor;
    }

    /**
     * 每次最多认领一个模型回合任务；认领事务结束后才进入可能耗时的模型调用。
     *
     * @param workerId 当前 Worker 的租约持有者标识，续租和完成时必须使用同一值
     * @return 本次成功认领的任务数，只会是 0 或 1
     */
    public synchronized int runOnce(String workerId) {
        commits.finishBlockedFailures(taskProperties.maxClaimBatch());
        List<Task> claimed = tasks.claimAgentTurns(workerId, 1);
        for (Task lease : claimed) {
            runClaimed(lease, workerId);
        }
        return claimed.size();
    }

    /**
     * 处理带 fencing epoch 的任务租约；失败在有效租约下自动收尾，临时模型故障先退避重试。
     *
     * @param lease 本次认领返回的任务快照，包含必须匹配的租约 epoch
     * @param workerId 本次租约的持有者标识
     */
    private void runClaimed(Task lease, String workerId) {
        try (var execution = modelExecutor.open(lease, workerId)) {
            try {
                execution.checkActive();
                AgentRun run = commits.start(lease, workerId);
                ChatGateway.ConfigIdentity pinned = new ChatGateway.ConfigIdentity(
                        run.policySnapshot().path("modelConfigSource").asText(""),
                        run.policySnapshot().path("modelConfigVersion").asInt(-1));
                gateway.requireToolCalling(pinned);
                UUID ownerId = tasks.ownerForWorker(lease);
                int stepIndex = lease.input().path("stepIndex").asInt(-1);
                continueTurn(lease, workerId, ownerId, stepIndex, run, execution);
            } catch (LeasedModelExecutor.Stopped | InterruptedException stopped) {
                throw stopped;
            } catch (Exception failure) {
                execution.checkActive();
                recordFailure(lease, workerId, failure, execution);
            }
        } catch (LeasedModelExecutor.Stopped stopped) {
            // Renewal uncertainty is not a model failure. Durable checkpoints and fencing own recovery.
            LOGGER.warn("Agent-turn task {} stopped locally; awaiting durable recovery", lease.id());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.info("Agent-turn task {} interrupted; awaiting durable recovery", lease.id());
        }
    }

    /** Resolve durable responses before considering another request or an expired retry window. */
    private void continueTurn(Task lease, String workerId, UUID ownerId, int stepIndex, AgentRun run,
            LeasedModelExecutor.Execution execution)
            throws Exception {
        // 等待态已有完整模型响应，恢复时只读取检查点，不再次调用模型。
        if (run.status() == AgentRun.Status.WAITING_TASKS || run.nextStepIndex() > stepIndex) {
            LlmTurn turn = turns.find(lease.projectId(), lease.runId(), stepIndex)
                    .orElseThrow(() -> new IllegalStateException("Waiting Run has no model turn"));
            if (turn.status() != LlmTurn.Status.RESPONDED) {
                throw new IllegalStateException("Waiting Run has no recorded model response");
            }
            applyResponse(lease, workerId, ownerId, stepIndex, turn.response(), execution);
            return;
        }
        LlmTurn saved = turns.find(lease.projectId(), lease.runId(), stepIndex).orElse(null);
        Duration remaining = AgentModelRetryPolicy.remaining(lease, clock.instant());
        if ((saved == null || saved.status() != LlmTurn.Status.RESPONDED)
                && (remaining.isNegative() || remaining.isZero())) {
            execution.checkActive();
            JsonNode recorded = commits.modelFailed(lease, workerId, AgentModelRetryPolicy.progress(lease)
                    .path("lastErrorCode").asText());
            if (recorded != null) applyResponse(lease, workerId, ownerId, stepIndex, recorded, execution);
            return;
        }
        List<Message> messages = frozenMessages(lease, ownerId, stepIndex, saved);
        JsonNode response = awaitModelResponse(lease, workerId, ownerId, stepIndex, run,
                messages, remaining, saved != null && saved.status() == LlmTurn.Status.RESPONDED, execution);
        applyResponse(lease, workerId, ownerId, stepIndex, response, execution);
    }

    /** Retry and restart replay the saved request instead of assembling changed user content. */
    private List<Message> frozenMessages(Task lease, UUID ownerId, int stepIndex, LlmTurn saved) {
        if (saved != null) return codec.requestMessages(saved.request());
        if (lease.input().has("repairFromStep")) return repairContext.assemble(ownerId, lease);
        if (stepIndex == 0) return initialContext.assemble(ownerId, lease.projectId(), lease.runId());
        return conversation.afterToolRound(ownerId, lease.projectId(), lease.runId(), stepIndex - 1);
    }

    private JsonNode awaitModelResponse(Task lease, String workerId, UUID ownerId, int stepIndex,
            AgentRun run, List<Message> messages, Duration remaining, boolean recorded,
            LeasedModelExecutor.Execution execution) throws Exception {
        // rounds.callLeased 在网络请求两侧提交带租约校验的检查点；这里不持有数据库事务。
        Duration wait = remaining.compareTo(MODEL_TIMEOUT) < 0 ? remaining : MODEL_TIMEOUT;
        // Replaying a saved response is allowed after the retry deadline, without a request.
        Duration timeout = recorded ? MODEL_TIMEOUT : Duration.ofMillis(Math.max(1, wait.toMillis()));
        return execution.call(() -> rounds.callLeased(ownerId,
                lease.projectId(), lease.runId(), stepIndex, messages,
                registry.modelDefinitions(run.policySnapshot()),
                Map.of("projectId", lease.projectId().toString(),
                        "runId", lease.runId().toString()), lease, workerId), timeout);
    }

    private void recordFailure(Task lease, String workerId, Exception failure,
            LeasedModelExecutor.Execution execution) {
        LOGGER.error("Agent-turn task {} could not continue: {} code={}", lease.id(),
                failure.getClass().getSimpleName(), failureCode(failure));
        try {
            String retryCode = AgentModelRetryPolicy.retryableCode(failure);
            if (retryCode == null) {
                commits.finishFailure(lease, workerId, failureCode(failure));
                return;
            }
            JsonNode recorded = commits.modelFailed(lease, workerId, retryCode);
            if (recorded == null) return;
            // A complete checkpoint can win the timeout race. Continue it immediately,
            // without another model request or waiting for this lease to expire.
            try {
                applyResponse(lease, workerId, tasks.ownerForWorker(lease),
                        lease.input().path("stepIndex").asInt(-1), recorded, execution);
            } catch (LeasedModelExecutor.Stopped stopped) {
                throw stopped;
            } catch (RuntimeException processingFailure) {
                execution.checkActive();
                commits.finishFailure(lease, workerId, failureCode(processingFailure));
            }
        } catch (LeasedModelExecutor.Stopped stopped) {
            throw stopped;
        } catch (RuntimeException changed) {
            LOGGER.warn("Agent-turn task {} changed before failure could be recorded: {}",
                    lease.id(), changed.getClass().getSimpleName());
        }
    }

    /** Only a committed response can enter the tool transaction; replay is idempotent. */
    private void applyResponse(Task lease, String workerId, UUID ownerId, int stepIndex, JsonNode response,
            LeasedModelExecutor.Execution execution) {
        execution.checkActive();
        try {
            AssistantMessage assistant = codec.selectedAssistant(response);
            validateCalls(assistant);
            TrustedToolContext context = new TrustedToolContext(ownerId,
                    lease.projectId(), lease.runId());
            executor.executeLeased(context, stepIndex, assistant.getToolCalls(),
                    lease, workerId);
        } catch (ApiProblemException invalid) {
            if (!"TOOL_ARGUMENT_INVALID".equals(invalid.code())) {
                throw invalid;
            }
            execution.checkActive();
            commits.scheduleRepair(lease, workerId, invalid.code(), invalid.getMessage());
            return;
        } catch (IllegalArgumentException invalid) {
            execution.checkActive();
            commits.scheduleRepair(lease, workerId, "MODEL_OUTPUT_INVALID",
                    "Model response or tool call structure is malformed");
            return;
        }
        execution.checkActive();
        commits.complete(lease, workerId);
    }

    /**
     * 穿透 Future 包装异常，保留安全配置错误码并区分网络与 Worker 等待超时。
     *
     * @param failure 模型调用或回合处理抛出的异常链
     * @return 明确的版本冲突/凭证/配置/超时错误码，或通用的 {@code AGENT_TURN_FAILED}
     */
    static String failureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiProblemException problem
                    && ("CREDENTIAL_KEY_VERSION_MISSING".equals(problem.code())
                            || "LLM_CONFIG_UNAVAILABLE".equals(problem.code())
                            // Archived media may be unselected after a draft edit. Preserve
                            // the conflict without exposing exception text or overriding the card.
                            || "ARTIFACT_VERSION_CONFLICT".equals(problem.code()))) {
                return problem.code();
            }
        }
        String retryCode = AgentModelRetryPolicy.retryableCode(failure);
        if (retryCode != null) return retryCode;
        return LlmCallTimeouts.isTimeout(failure) ? LlmCallTimeouts.ERROR_CODE : "AGENT_TURN_FAILED";
    }

    /**
     * 校验被选中响应的工具调用结构与数量，拒绝无法持久化执行的畸形调用。
     *
     * @param assistant 从已保存模型响应中恢复的 Assistant 消息
     */
    private void validateCalls(AssistantMessage assistant) {
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        if (calls.size() > 40) {
            throw new IllegalArgumentException("Model emitted too many tool calls");
        }
        for (AssistantMessage.ToolCall call : calls) {
            if (!"function".equals(call.type()) || call.id() == null || call.id().isBlank()
                    || call.name() == null || call.arguments() == null) {
                throw new IllegalArgumentException("Model emitted a malformed tool call");
            }
        }
    }

    /** 关闭租约心跳和模型调用线程池，避免应用停机后仍继续调度后台工作。 */
    @PreDestroy
    public void shutdown() {
        modelExecutor.close();
    }
}
