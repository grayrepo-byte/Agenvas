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

/** Rehydrates a continuation only from committed model and tool checkpoints. */
@Service
public class LlmConversationService {

    private final AgentRunRepository runs;
    private final LlmTurnRepository turns;
    private final ToolExecutionRepository tools;
    private final LlmProtocolCodec codec;

    public LlmConversationService(AgentRunRepository runs, LlmTurnRepository turns,
            ToolExecutionRepository tools, LlmProtocolCodec codec) {
        this.runs = runs;
        this.turns = turns;
        this.tools = tools;
        this.codec = codec;
    }

    /** Returns the saved prompt plus the selected response and every durable tool result. */
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
