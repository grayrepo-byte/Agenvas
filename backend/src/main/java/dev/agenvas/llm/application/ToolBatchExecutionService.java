package dev.agenvas.llm.application;

import dev.agenvas.task.domain.Task;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes one model response as a unit so a later invalid call cannot leave partial artifacts. */
@Service
public class ToolBatchExecutionService {

    private final ToolExecutionService tools;

    public ToolBatchExecutionService(ToolExecutionService tools) {
        this.tools = tools;
    }

    /** Rolls back all business results and tool ledgers if any call fails validation. */
    @Transactional
    public void executeLeased(TrustedToolContext context, int stepIndex,
            List<AssistantMessage.ToolCall> calls, Task lease, String workerId) {
        for (AssistantMessage.ToolCall call : calls) {
            tools.executeLeased(context, stepIndex, call.id(), lease, workerId);
        }
    }
}
