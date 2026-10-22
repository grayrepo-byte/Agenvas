package dev.agenvas.mcp.application;

import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The reviewed contract is both the discovery catalog and the input validation authority. */
@Component
public class McpToolPolicy {
    private static final int MAX_ARGUMENT_BYTES = 262_144;
    private final List<JsonNode> definitions;

    public McpToolPolicy(ObjectMapper mapper) {
        try (var input = new ClassPathResource("mcp/tools.json").getInputStream()) {
            definitions = mapper.readTree(input).path("tools").valueStream().toList();
        } catch (IOException failure) { throw new IllegalStateException("MCP tool contract is missing", failure); }
        if (definitions.size() != 13 || definitions.stream().map(n -> n.path("name").asText()).distinct().count() != 13)
            throw new IllegalStateException("MCP tool catalog must contain the thirteen reviewed tools");
    }
    public List<JsonNode> definitions() { return definitions.stream().map(JsonNode::deepCopy).toList(); }
    public JsonNode definition(String name) {
        return definitions.stream().filter(d -> name != null && name.equals(d.path("name").asText())).findFirst()
                .orElseThrow(McpToolPolicy::invalid);
    }
    public McpScope requiredScope(String name) { return McpScope.valueOf(definition(name).path("requiredScope").asText()); }
    public void validate(String name, JsonNode arguments) {
        if (arguments == null || arguments.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES
                || !matches(definition(name).path("inputSchema"), arguments, 0)) throw invalid();
        if (arguments.has("commandId") && arguments.path("commandId").asText().isBlank()) throw invalid();
        if ("read_canvas".equals(name) && arguments.path("includeDrafts").asBoolean()
                && arguments.path("limit").asInt(20) > 6) throw invalid();
        if ("update_media_draft".equals(name) && arguments.path("patch").isEmpty()) throw invalid();
        if ("manage_canvas_node".equals(name)) {
            List<String> required = switch (arguments.path("action").asText()) {
                case "RENAME" -> List.of("title"); case "MOVE" -> List.of("x", "y");
                case "RESIZE" -> List.of("width", "height"); default -> throw invalid();
            };
            if (required.stream().anyMatch(field -> !arguments.has(field))) throw invalid();
            for (String field : List.of("title", "x", "y", "width", "height"))
                if (arguments.has(field) && !required.contains(field)) throw invalid();
        }
    }
    /** Contract checks can exercise the same safe projection returned to an actual client. */
    public void validateOutput(String name, JsonNode output) {
        if (output == null || !matches(definition(name).path("outputSchema"), output, 0))
            throw new IllegalStateException("MCP tool response does not match its public contract: " + name);
    }

    /** Validates the deliberately small JSON Schema vocabulary used by our static contract. */
    private boolean matches(JsonNode schema, JsonNode value, int depth) {
        if (depth > 32) return false;
        if (schema.has("anyOf")) return schema.path("anyOf").valueStream().anyMatch(s -> matches(s, value, depth + 1));
        String type = schema.path("type").asText();
        boolean typed = switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isString(); case "integer" -> value.isIntegralNumber() && value.canConvertToLong();
            case "number" -> value.isNumber(); case "boolean" -> value.isBoolean(); case "null" -> value.isNull();
            default -> false;
        };
        if (!typed) return false;
        if (schema.has("enum") && schema.path("enum").valueStream().noneMatch(value::equals)) return false;
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) if (!value.has(required.asText())) return false;
            JsonNode props = schema.path("properties");
            for (String key : value.propertyNames()) {
                if (props.has(key)) { if (!matches(props.path(key), value.path(key), depth + 1)) return false; }
                else if (schema.has("additionalProperties") && !schema.path("additionalProperties").asBoolean()) return false;
            }
        } else if (value.isArray()) {
            if (value.size() < schema.path("minItems").asInt(0) || value.size() > schema.path("maxItems").asInt(Integer.MAX_VALUE)) return false;
            if (schema.path("uniqueItems").asBoolean() && value.valueStream().distinct().count() != value.size()) return false;
            for (JsonNode item : value) if (!matches(schema.path("items"), item, depth + 1)) return false;
        } else if (value.isString()) {
            String text = value.asText();
            if (text.length() < schema.path("minLength").asInt(0) || text.length() > schema.path("maxLength").asInt(Integer.MAX_VALUE)) return false;
            if (schema.has("pattern") && !text.matches(schema.path("pattern").asText())) return false;
            if ("uuid".equals(schema.path("format").asText())) {
                try { if (!UUID.fromString(text).toString().equalsIgnoreCase(text)) return false; }
                catch (IllegalArgumentException invalid) { return false; }
            }
        } else if (value.isNumber()) {
            if (schema.has("minimum") && value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0) return false;
            if (schema.has("maximum") && value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0) return false;
        }
        return true;
    }
    static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"),
                ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"), false);
    }
}
