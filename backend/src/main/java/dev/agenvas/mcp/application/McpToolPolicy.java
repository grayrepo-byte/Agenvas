package dev.agenvas.mcp.application;

import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        } catch (IOException failure) {
            throw new IllegalStateException("MCP tool contract is missing", failure);
        }
        for (JsonNode definition : definitions) {
            String name = definition.path("name").asText();
            McpSchemaValidator.requireSupported(definition.path("inputSchema"), name + "/inputSchema");
            McpSchemaValidator.requireSupported(definition.path("outputSchema"), name + "/outputSchema");
        }
    }
    public List<JsonNode> definitions() {
        return definitions.stream().map(JsonNode::deepCopy).toList();
    }
    public JsonNode definition(String name) {
        return registeredDefinition(name).deepCopy();
    }
    private JsonNode registeredDefinition(String name) {
        return definitions.stream().filter(d -> name != null && name.equals(d.path("name").asText())).findFirst()
                .orElseThrow(McpToolPolicy::invalid);
    }
    public McpScope requiredScope(String name) {
        return McpScope.valueOf(registeredDefinition(name).path("requiredScope").asText());
    }
    public void validate(String name, JsonNode arguments) {
        if (arguments == null
                || arguments.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES
                || !McpSchemaValidator.matches(registeredDefinition(name).path("inputSchema"), arguments))
            throw invalid();
        validateToolRules(name, arguments);
    }
    private void validateToolRules(String name, JsonNode arguments) {
        if (arguments.has("commandId") && arguments.path("commandId").asText().isBlank())
            throw invalid();
        if ("prepare_media_upload".equals(name) && arguments.path("title").asText().isBlank())
            throw invalid();
        if ("read_canvas".equals(name) && arguments.path("includeDrafts").asBoolean()
                && arguments.path("limit").asInt(20) > 6)
            throw invalid();
        if ("update_media_draft".equals(name) && arguments.path("patch").isEmpty())
            throw invalid();
        if ("read_artifacts".equals(name) && (arguments.has("textOffset") || arguments.has("textLimit"))
                && arguments.path("versionIds").size() != 1)
            throw invalid();
        if ("manage_canvas_node".equals(name)) {
            List<String> required = switch (arguments.path("action").asText()) {
                case "RENAME" -> List.of("title");
                case "MOVE" -> List.of("x", "y");
                case "RESIZE" -> List.of("width", "height");
                default -> throw invalid();
            };
            if (required.stream().anyMatch(field -> !arguments.has(field)))
                throw invalid();
            for (String field : List.of("title", "x", "y", "width", "height"))
                if (arguments.has(field) && !required.contains(field))
                    throw invalid();
        }
    }
    /** Contract checks can exercise the same safe projection returned to an actual client. */
    public void validateOutput(String name, JsonNode output) {
        if (output == null || !McpSchemaValidator.matches(registeredDefinition(name).path("outputSchema"), output))
            throw new IllegalStateException("MCP tool response does not match its public contract: " + name);
    }

    static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"),
                ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"), false);
    }
}
