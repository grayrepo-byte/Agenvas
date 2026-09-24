package dev.agenvas.llm.application;

import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Rebuilds a bounded repair prompt from the durable failed model round. */
@Service
public class RepairModelContextService {

    private static final int MAX_REPLAY_BYTES = 64 * 1024;

    private final AgentRunRepository runs;
    private final LlmTurnRepository turns;
    private final LlmProtocolCodec codec;
    private final ObjectMapper mapper;

    public RepairModelContextService(AgentRunRepository runs, LlmTurnRepository turns,
            LlmProtocolCodec codec, ObjectMapper mapper) {
        this.runs = runs;
        this.turns = turns;
        this.codec = codec;
        this.mapper = mapper;
    }

    /** Uses only recorded model input and a server-authored validation error, not partial tool data. */
    @Transactional(readOnly = true)
    public List<Message> assemble(UUID ownerId, Task repairTask) {
        runs.find(ownerId, repairTask.projectId(), repairTask.runId())
                .orElseThrow(() -> new IllegalArgumentException("Run is not accessible"));
        int priorStep = repairTask.input().path("repairFromStep").asInt(-1);
        if (priorStep < 0 || priorStep >= 12
                || priorStep + 1 != repairTask.input().path("stepIndex").asInt(-1)) {
            throw new IllegalArgumentException("Repair Task has an invalid source step");
        }
        LlmTurn failed = turns.find(repairTask.projectId(), repairTask.runId(), priorStep)
                .orElseThrow(() -> new IllegalStateException("Failed model turn is missing"));
        if (failed.status() != LlmTurn.Status.RESPONDED) {
            throw new IllegalStateException("Failed model response is not durable");
        }
        List<Message> messages = new ArrayList<>(codec.requestMessages(failed.request()));
        String code = repairTask.input().path("repairErrorCode").asText("");
        String detail = repairTask.input().path("repairErrorDetail").asText("");
        if (messages.size() <= 77) {
            appendRejectedCalls(messages, failed.response(), code, detail);
        }
        if (messages.size() >= 80) {
            throw new IllegalStateException("Repair prompt exceeds the message limit");
        }
        messages.add(new UserMessage("The previous structured tool output was rejected ("
                + code + "): " + detail + ". Regenerate the entire response with valid "
                + "tool arguments. No tools from that response were applied. Do not claim approval."));
        return List.copyOf(messages);
    }

    /** Replays safe, bounded call IDs with synthetic failures; no business tool is invoked. */
    private void appendRejectedCalls(List<Message> messages, JsonNode response,
            String code, String detail) {
        AssistantMessage assistant;
        try {
            assistant = codec.selectedAssistant(response);
        } catch (IllegalArgumentException malformed) {
            return;
        }
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        if (calls.isEmpty() || calls.size() > 40
                || response.toString().getBytes(StandardCharsets.UTF_8).length > MAX_REPLAY_BYTES) {
            return;
        }
        Set<String> ids = new HashSet<>();
        Map<String, JsonNode> replies = new HashMap<>();
        for (AssistantMessage.ToolCall call : calls) {
            if (!"function".equals(call.type()) || call.id() == null
                    || call.id().isBlank() || !ids.add(call.id())
                    || call.name() == null || call.name().isBlank()) {
                return;
            }
            ObjectNode rejection = mapper.createObjectNode();
            rejection.put("status", "REJECTED");
            rejection.put("errorCode", code);
            rejection.put("detail", detail);
            rejection.put("businessEffect", false);
            replies.put(call.id(), rejection);
        }
        messages.add(assistant);
        messages.add(codec.toolResults(assistant, replies));
    }
}
