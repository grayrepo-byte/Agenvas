package dev.agenvas.shared.http;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Converts captured OpenAI-compatible SSE data into one debug response, independently of the SDK. */
final class LlmSseResponse {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DATA_PREFIX = "data:";
    private static final String DONE = "[DONE]";
    private static final String CHOICES = "choices";
    private static final String INDEX = "index";
    private static final String DELTA = "delta";
    private static final String MESSAGE = "message";
    private static final String TOOL_CALLS = "tool_calls";
    private static final Set<String> TEXT_DELTAS = Set.of("content", "reasoning_content", "reasoning", "refusal", "arguments", "name");
    private final ObjectNode response = MAPPER.createObjectNode();
    private final Map<Integer, ObjectNode> choices = new TreeMap<>();
    private boolean partial;

    record Result(String content, boolean partial) {}

    static Result aggregate(String events) {
        var result = new LlmSseResponse();
        var data = new StringBuilder();
        events.lines().forEach(line -> {
            if (line.isEmpty()) {
                result.frame(data.toString());
                data.setLength(0);
            } else if (line.startsWith(DATA_PREFIX)) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(DATA_PREFIX.length()).stripLeading());
            }
        });
        result.frame(data.toString());
        if (!result.choices.isEmpty()) {
            ArrayNode array = result.response.putArray(CHOICES);
            result.choices.values().forEach(array::add);
        }
        if ("chat.completion.chunk".equals(result.response.path("object").asText())) {
            result.response.put("object", "chat.completion");
        }
        return new Result(result.response.toString(), result.partial);
    }

    private void frame(String data) {
        if (data.isBlank() || DONE.equals(data)) return;
        try {
            JsonNode event = MAPPER.readTree(data);
            if (!event.isObject()) { partial = true; return; }
            for (String name : event.propertyNames()) {
                if (!CHOICES.equals(name)) mergeValue(response, name, event.get(name), false);
            }
            int position = 0;
            for (JsonNode choice : event.path(CHOICES)) {
                if (!choice.isObject()) { partial = true; continue; }
                int index = choice.path(INDEX).asInt(position++);
                ObjectNode saved = choices.computeIfAbsent(index, ignored -> MAPPER.createObjectNode().put(INDEX, index));
                for (String name : choice.propertyNames()) {
                    if (DELTA.equals(name)) {
                        ObjectNode message = saved.path(MESSAGE).isObject()
                                ? (ObjectNode) saved.get(MESSAGE) : saved.putObject(MESSAGE);
                        merge(message, choice.get(name), true);
                    } else mergeValue(saved, name, choice.get(name), false);
                }
            }
        } catch (RuntimeException invalidEvent) {
            // Keep already parsed content on disconnect; an incomplete event cannot be reconstructed.
            partial = true;
        }
    }

    private void merge(ObjectNode target, JsonNode patch, boolean delta) {
        if (!patch.isObject()) { partial = true; return; }
        for (String name : patch.propertyNames()) mergeValue(target, name, patch.get(name), delta);
    }

    private void mergeValue(ObjectNode target, String name, JsonNode value, boolean delta) {
        JsonNode old = target.get(name);
        if (value.isNull() && old != null && !old.isNull()) return;
        if (delta && TOOL_CALLS.equals(name) && value.isArray()) {
            mergeTools(target, value);
        } else if (value.isObject()) {
            ObjectNode object = old != null && old.isObject() ? (ObjectNode) old : target.putObject(name);
            merge(object, value, delta);
        } else if (delta && TEXT_DELTAS.contains(name) && value.isTextual() && old != null && old.isTextual()) {
            target.put(name, "name".equals(name) && old.equals(value) ? old.asText() : old.asText() + value.asText());
        } else target.set(name, value.deepCopy());
    }

    private void mergeTools(ObjectNode target, JsonNode patch) {
        Map<Integer, ObjectNode> tools = new TreeMap<>();
        int position = 0;
        for (JsonNode tool : target.path(TOOL_CALLS)) {
            tools.put(tool.path(INDEX).asInt(position++), (ObjectNode) tool);
        }
        position = 0;
        for (JsonNode tool : patch) {
            if (!tool.isObject()) { partial = true; continue; }
            int index = tool.path(INDEX).asInt(position++);
            ObjectNode saved = tools.computeIfAbsent(index, ignored -> MAPPER.createObjectNode().put(INDEX, index));
            merge(saved, tool, true);
        }
        ArrayNode array = target.putArray(TOOL_CALLS);
        tools.values().forEach(array::add);
    }
}
