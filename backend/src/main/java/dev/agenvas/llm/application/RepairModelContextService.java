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

/** 从持久化失败回合重建有界修复提示，只加入服务端校验错误而不执行残留工具调用。 */
@Service
public class RepairModelContextService {

    /** 回放模型拒绝响应中的工具调用前允许读取的最大 JSON 字节数。 */
    private static final int MAX_REPLAY_BYTES = 64 * 1024;

    /** 确认修复任务所属 Run 对当前用户可见。 */
    private final AgentRunRepository runs;
    /** 读取已持久化的原始模型请求和失败响应。 */
    private final LlmTurnRepository turns;
    /** 将模型回合 JSON 还原为 Spring AI 消息。 */
    private final LlmProtocolCodec codec;
    /** 构造模拟拒绝结果，不调用任何业务工具。 */
    private final ObjectMapper mapper;

    /** 注入 Run 鉴权、模型回合账本与协议编解码能力。 */
    public RepairModelContextService(AgentRunRepository runs, LlmTurnRepository turns,
            LlmProtocolCodec codec, ObjectMapper mapper) {
        this.runs = runs;
        this.turns = turns;
        this.codec = codec;
        this.mapper = mapper;
    }

    /** 只使用记录的请求和服务端生成的校验错误，不把部分工具结果当作成功业务数据。 */
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

    /** 对安全且有界的调用 ID 回放合成拒绝结果；不会再次调用任何业务工具。 */
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
