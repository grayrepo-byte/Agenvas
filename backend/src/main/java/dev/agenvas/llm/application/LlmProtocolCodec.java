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

/** Versioned application-level protocol codec; never relies on framework class-name JSON tags. */
@Component
public class LlmProtocolCodec {

    private static final int MAX_REQUEST_BYTES = 512 * 1024;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final ObjectMapper mapper;

    public LlmProtocolCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Snapshots exactly the text messages and tool definitions made visible to the model. */
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

    /** Saves all returned generations, tool IDs and provider protocol metadata before execution. */
    public ObjectNode response(ChatResponse response) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("schemaVersion", 1);
        ObjectNode metadata = envelope.putObject("metadata");
        metadata.put("id", response.getMetadata().getId());
        metadata.put("model", response.getMetadata().getModel());
        // Spring AI's EmptyUsage reports synthetic zeroes, not provider-supplied tokens.
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

    /** Restores the exact bounded message sequence saved before a model request. */
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

    /** Restores the selected assistant generation including opaque provider metadata and IDs. */
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

    /** Builds one tool reply in the assistant's original call order and rejects missing results. */
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
                yield AssistantMessage.builder().content(content).properties(metadata)
                        .toolCalls(List.copyOf(calls)).build();
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

    private Map<String, Object> metadata(JsonNode value) {
        if (!value.isObject()) {
            throw new IllegalArgumentException("Saved model metadata is malformed");
        }
        return mapper.convertValue(value, new tools.jackson.core.type.TypeReference<>() {});
    }

    private String required(JsonNode value, String field) {
        JsonNode item = value.path(field);
        if (!item.isTextual()) {
            throw new IllegalArgumentException("Saved model field is malformed: " + field);
        }
        return item.asText();
    }

    private void requireSchema(JsonNode value, int maximumBytes) {
        if (value == null || !value.isObject() || value.path("schemaVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported model checkpoint schema");
        }
        requireBounded(value, maximumBytes);
    }

    private ObjectNode encodeMessage(Message message) {
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

    private void requireBounded(JsonNode value, int maximumBytes) {
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException("Model protocol checkpoint exceeds size limit");
        }
    }
}
