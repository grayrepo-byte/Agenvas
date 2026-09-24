package dev.agenvas.llm.application;

import dev.agenvas.run.application.AgentRunRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 只从已提交的模型检查点和工具账本重建后续对话，避免以内存状态作为恢复依据。 */
@Service
public class LlmConversationService {

    /** 先验证调用者对 Run 的项目作用域。 */
    private final AgentRunRepository runs;
    /** 读取上一回合的原始请求和完整响应。 */
    private final LlmTurnRepository turns;
    /** 逐项核对工具调用对应的已完成结果。 */
    private final ToolExecutionRepository tools;
    /** 恢复版本化请求消息和按原顺序排列的工具回复。 */
    private final LlmProtocolCodec codec;

    /** 注入 Run 权限、完整模型回合和工具账本读取边界。
     * @param runs 验证调用者可访问该 Run
     * @param turns 读取持久化模型请求与响应
     * @param tools 读取响应中各工具调用的已提交结果
     * @param codec 将应用协议消息还原为 Spring AI 消息
     */
    public LlmConversationService(AgentRunRepository runs, LlmTurnRepository turns,
            ToolExecutionRepository tools, LlmProtocolCodec codec) {
        this.runs = runs;
        this.turns = turns;
        this.tools = tools;
        this.codec = codec;
    }

    /**
     * 以已保存请求为起点，追加被选中的 Assistant 响应及每个已完成工具调用的回复。
     * 工具 ID、名称或结果缺失时拒绝继续；最终消息不产生后续回合，并限制历史为 80 条。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId 本次 Run 所属项目
     * @param runId 需要续接的 Run
     * @param priorStepIndex 上一回合序号，必须处于 Run 的回合上限内
     * @return 按原调用顺序重建的只读消息序列
     */
    @Transactional(readOnly = true)
    public List<Message> afterToolRound(UUID ownerId, UUID projectId, UUID runId,
            int priorStepIndex) {
        if (priorStepIndex < 0 || priorStepIndex >= 12) {
            throw new IllegalArgumentException("Model step is outside the Run limit");
        }
        runs.find(ownerId, projectId, runId)
                .orElseThrow(() -> new IllegalArgumentException("Run is not accessible"));
        LlmTurn turn = turns.find(projectId, runId, priorStepIndex)
                .orElseThrow(() -> new IllegalStateException("Model turn checkpoint is missing"));
        if (turn.status() != LlmTurn.Status.RESPONDED) {
            throw new IllegalStateException("Model response has not been committed");
        }
        List<Message> history = new ArrayList<>(codec.requestMessages(turn.request()));
        AssistantMessage assistant = codec.selectedAssistant(turn.response());
        if (assistant.getToolCalls().isEmpty()) {
            throw new IllegalStateException("A final assistant response has no tool continuation");
        }
        Map<String, JsonNode> results = new HashMap<>();
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            ToolExecution execution = tools.find(projectId, runId, priorStepIndex, call.id())
                    .orElseThrow(() -> new IllegalStateException("Tool result is not committed"));
            if (execution.status() != ToolExecution.Status.COMPLETED
                    || execution.result() == null
                    || !execution.toolName().equals(call.name())
                    || results.putIfAbsent(call.id(), execution.result()) != null) {
                throw new IllegalStateException("Tool result does not match model call");
            }
        }
        history.add(assistant);
        history.add(codec.toolResults(assistant, results));
        if (history.size() > 80) {
            throw new IllegalStateException("Model history exceeds the message limit");
        }
        return List.copyOf(history);
    }
}
