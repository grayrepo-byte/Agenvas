package dev.agenvas.llm.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 将 Spring AI 消息转换为版本化的应用协议，恢复时只接受本应用明确支持的消息结构。 */
@Component
public class LlmProtocolCodec {

    /** 单轮请求检查点的 UTF-8 字节上限，防止无界上下文进入数据库。 */
    private static final int MAX_REQUEST_BYTES = 512 * 1024;
    /** 单轮响应检查点的 UTF-8 字节上限，包含所有 generation 与工具调用。 */
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    /** 在应用协议与 Jackson JSON 树之间转换消息和供应商元数据。 */
    private final ObjectMapper mapper;

    /** 注入 JSON 映射器以序列化和恢复版本化模型协议。
     * @param mapper 项目配置的 Jackson 映射器
     */
    public LlmProtocolCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 保存文本消息、精确图片版本引用与工具定义，不把图片字节或未注册工具写入请求检查点。
     *
     * @param messages 本次有序消息；图片引用在派发阶段重新鉴权并加载预览
     * @param tools 本次按 Run 策略选出的工具定义
     * @return 包含 Schema 版本且受大小上限约束的请求快照
     */
    public ObjectNode request(List<Message> messages, List<ToolCallback> tools) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("schemaVersion", 1);
        ArrayNode serialized = envelope.putArray("messages");
        for (Message message : messages) {
            serialized.add(encodeMessage(message));
        }
        ArrayNode definitions = envelope.putArray("tools");
        for (ToolCallback callback : tools) {
            ObjectNode definition = definitions.addObject();
            definition.put("name", callback.getToolDefinition().name());
            definition.put("description", callback.getToolDefinition().description());
            definition.put("inputSchema", callback.getToolDefinition().inputSchema());
        }
        requireBounded(envelope, MAX_REQUEST_BYTES);
        return envelope;
    }

    /**
     * 保存完整响应、全部 generation、工具调用 ID 与恢复协议所需元数据；未提供的用量保留为空。
     *
     * @param response 模型网关返回的原始 Spring AI 响应
     * @return 受大小上限约束的响应检查点，工具执行必须晚于此值落库
     */
    public ObjectNode response(ChatResponse response) {
        response = PublicAssistantResponse.sanitize(response);
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("schemaVersion", 1);
        ObjectNode metadata = envelope.putObject("metadata");
        metadata.put("id", response.getMetadata().getId());
        metadata.put("model", response.getMetadata().getModel());
        // EmptyUsage 的零值由框架补出，不能当作供应商实际报告的 Token 用量。
        if (response.getMetadata().getUsage() instanceof EmptyUsage) {
            metadata.putNull("usage");
        } else {
            metadata.set("usage", mapper.valueToTree(response.getMetadata().getUsage()));
        }
        metadata.set("attributes", mapper.valueToTree(response.getMetadata().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey, java.util.Map.Entry::getValue))));
        ArrayNode generations = envelope.putArray("generations");
        response.getResults().forEach(generation -> {
            ObjectNode item = generations.addObject();
            item.set("assistant", encodeMessage(generation.getOutput()));
            item.set("metadata", mapper.valueToTree(generation.getMetadata()));
        });
        requireBounded(envelope, MAX_RESPONSE_BYTES);
        return envelope;
    }

    /**
     * 从版本化请求快照恢复原消息顺序；拒绝空消息集、超过 80 条或不支持的消息结构。
     *
     * @param request 先前保存的模型请求 JSON
     * @return 可用于同一协议回合的消息序列
     */
    public List<Message> requestMessages(JsonNode request) {
        requireSchema(request, MAX_REQUEST_BYTES);
        JsonNode values = request.path("messages");
        if (!values.isArray() || values.isEmpty() || values.size() > 80) {
            throw new IllegalArgumentException("Saved model request has invalid messages");
        }
        List<Message> messages = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            messages.add(decodeMessage(value));
        }
        return List.copyOf(messages);
    }

    /**
     * 从已保存响应的第一个 generation 恢复 Assistant 消息及原始工具调用标识。
     *
     * @param response 已持久化且带受支持 Schema 版本的模型响应
     * @return 被选中 generation 的 Assistant 消息
     */
    public AssistantMessage selectedAssistant(JsonNode response) {
        requireSchema(response, MAX_RESPONSE_BYTES);
        JsonNode generations = response.path("generations");
        if (!generations.isArray() || generations.isEmpty()) {
            throw new IllegalArgumentException("Saved model response has no generation");
        }
        Message message = decodeMessage(generations.get(0).path("assistant"));
        if (!(message instanceof AssistantMessage assistant)) {
            throw new IllegalArgumentException("Saved model generation is not an assistant message");
        }
        return assistant;
    }

    /**
     * 按 Assistant 原始调用顺序构建工具回复；调用数、ID 或结果缺失时拒绝继续模型对话。
     *
     * @param assistant 已保存的 Assistant 消息
     * @param results 以原始 tool_call_id 为键的持久化工具结果
     * @return 与原调用一一对应的工具回复消息
     */
    public ToolResponseMessage toolResults(AssistantMessage assistant,
            Map<String, JsonNode> results) {
        if (assistant == null || results == null || assistant.getToolCalls().isEmpty()
                || assistant.getToolCalls().size() != results.size()) {
            throw new IllegalArgumentException("Tool results do not match the selected generation");
        }
        List<ToolResponseMessage.ToolResponse> replies = new ArrayList<>();
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            JsonNode result = results.get(call.id());
            if (result == null || !"function".equals(call.type())) {
                throw new IllegalArgumentException("A model tool call has no matching result");
            }
            replies.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                    result.toString()));
        }
        return ToolResponseMessage.builder().responses(List.copyOf(replies)).build();
    }

    /**
     * 只恢复 SYSTEM、USER、ASSISTANT 与 TOOL 四种文本协议消息，拒绝缺失调用字段及其他角色。
     *
     * @param value 单条已保存消息的 JSON 对象
     * @return 带原有元数据和工具调用 ID 的 Spring AI 消息
     */
    private Message decodeMessage(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Saved model message is malformed");
        }
        JsonNode textValue = value.path("text");
        if (!textValue.isNull() && !textValue.isTextual()) {
            throw new IllegalArgumentException("Saved model message text is malformed");
        }
        String content = textValue.isNull() ? null : textValue.asText();
        Map<String, Object> metadata = metadata(value.path("metadata"));
        return switch (value.path("role").asText()) {
            case "SYSTEM" -> SystemMessage.builder().text(content).metadata(metadata).build();
            case "USER" -> UserMessage.builder().text(content).metadata(metadata).build();
            case "ASSISTANT" -> {
                List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                JsonNode savedCalls = value.path("toolCalls");
                if (!savedCalls.isArray()) {
                    throw new IllegalArgumentException("Saved assistant calls are malformed");
                }
                for (JsonNode call : savedCalls) {
                    calls.add(new AssistantMessage.ToolCall(required(call, "id"),
                            required(call, "type"), required(call, "name"),
                            required(call, "arguments")));
                }
                yield PublicAssistantResponse.sanitize(AssistantMessage.builder().content(content).properties(metadata)
                        .toolCalls(List.copyOf(calls)).build());
            }
            case "TOOL" -> {
                List<ToolResponseMessage.ToolResponse> replies = new ArrayList<>();
                JsonNode savedReplies = value.path("toolResponses");
                if (!savedReplies.isArray()) {
                    throw new IllegalArgumentException("Saved tool replies are malformed");
                }
                for (JsonNode reply : savedReplies) {
                    replies.add(new ToolResponseMessage.ToolResponse(required(reply, "id"),
                            required(reply, "name"), required(reply, "responseData")));
                }
                yield ToolResponseMessage.builder().responses(List.copyOf(replies))
                        .metadata(metadata).build();
            }
            default -> throw new IllegalArgumentException("Saved model message role is unknown");
        };
    }

    /**
     * 检查持久化元数据仍为对象，再恢复供应商续接协议需要的键值。
     *
     * @param value 消息中保存的元数据节点
     * @return 保持键值结构的元数据映射
     */
    private Map<String, Object> metadata(JsonNode value) {
        if (!value.isObject()) {
            throw new IllegalArgumentException("Saved model metadata is malformed");
        }
        return mapper.convertValue(value, new tools.jackson.core.type.TypeReference<>() {});
    }

    /**
     * 读取工具调用和回复中的必需文本字段，防止结构不完整的检查点被用于续接。
     *
     * @param value 调用或回复的 JSON 对象
     * @param field 协议要求存在的字段名
     * @return 原样保存的文本值
     */
    private String required(JsonNode value, String field) {
        JsonNode item = value.path(field);
        if (!item.isTextual()) {
            throw new IllegalArgumentException("Saved model field is malformed: " + field);
        }
        return item.asText();
    }

    /**
     * 只接受版本 1 的应用协议，并再次按 UTF-8 字节数校验读取的检查点。
     *
     * @param value 要恢复的请求或响应 JSON
     * @param maximumBytes 对应方向允许的字节上限
     */
    private void requireSchema(JsonNode value, int maximumBytes) {
        if (value == null || !value.isObject() || value.path("schemaVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported model checkpoint schema");
        }
        requireBounded(value, maximumBytes);
    }

    /**
     * 显式编码受支持的文本消息；用户视觉输入和 Assistant 媒体输出均在此路径拒绝。
     *
     * @param message 模型将看到或已返回的 Spring AI 消息
     * @return 含角色、文本、元数据及必要工具关联字段的协议对象
     */
    private ObjectNode encodeMessage(Message message) {
        if (message instanceof AssistantMessage assistant) message = PublicAssistantResponse.sanitize(assistant);
        ObjectNode value = mapper.createObjectNode();
        value.put("role", message.getMessageType().name());
        if (message.getText() == null) {
            value.putNull("text");
        } else {
            value.put("text", message.getText());
        }
        value.set("metadata", mapper.valueToTree(message.getMetadata()));
        if (message instanceof UserMessage user && !user.getMedia().isEmpty()) {
            throw new IllegalArgumentException("Vision input is not enabled for this model path");
        }
        if (message instanceof AssistantMessage assistant) {
            if (!assistant.getMedia().isEmpty()) {
                throw new IllegalArgumentException("Assistant media output is not enabled");
            }
            ArrayNode calls = value.putArray("toolCalls");
            assistant.getToolCalls().forEach(call -> {
                ObjectNode encoded = calls.addObject();
                encoded.put("id", call.id());
                encoded.put("type", call.type());
                encoded.put("name", call.name());
                encoded.put("arguments", call.arguments());
            });
        } else if (message instanceof ToolResponseMessage tool) {
            ArrayNode replies = value.putArray("toolResponses");
            tool.getResponses().forEach(reply -> {
                ObjectNode encoded = replies.addObject();
                encoded.put("id", reply.id());
                encoded.put("name", reply.name());
                encoded.put("responseData", reply.responseData());
            });
        } else if (!(message instanceof UserMessage) && !(message instanceof SystemMessage)) {
            throw new IllegalArgumentException("Unsupported model message role");
        }
        return value;
    }

    /**
     * 按实际 UTF-8 序列化字节数限制检查点，避免只检查字符数导致越界。
     *
     * @param value 待保存或读取的协议 JSON
     * @param maximumBytes 当前方向允许的最大字节数
     */
    private void requireBounded(JsonNode value, int maximumBytes) {
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException("Model protocol checkpoint exceeds size limit");
        }
    }
}
