package dev.agenvas.mcp.application;

import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Explicitly compiled MCP routes. Discovery and execution must agree before accepting requests. */
final class McpHandlers {
    sealed interface Route {
        String name();
        McpScope scope();
    }

    record Read(String name, BiFunction<McpPrincipal, JsonNode, McpToolResponse> action) implements Route {
        public McpScope scope() {
            return McpScope.READ;
        }
    }

    record Prepare(String name, Function<JsonNode, ObjectNode> action) implements Route {
        public McpScope scope() {
            return McpScope.CONTENT_WRITE;
        }
    }

    @FunctionalInterface
    interface CommandAction {
        ObjectNode execute(McpPrincipal principal, UUID project, UUID operation, JsonNode arguments);
    }

    record Command(String name, McpScope scope, CommandAction action) implements Route {
    }

    private final Map<String, Route> routes;

    McpHandlers(List<JsonNode> definitions, List<Route> compiled) {
        Map<String, Route> index = new HashMap<>();
        for (var route : compiled)
            if (index.putIfAbsent(route.name(), route) != null)
                throw new IllegalStateException("Duplicate MCP handler: " + route.name());
        var names = new HashSet<String>();
        for (var definition : definitions) {
            String name = definition.path("name").asText();
            var route = index.get(name);
            if (!names.add(name))
                throw new IllegalStateException("Duplicate MCP contract: " + name);
            if (route == null)
                throw new IllegalStateException("Missing MCP handler: " + name);
            var scope = McpScope.valueOf(definition.path("requiredScope").asText());
            if (route.scope() != scope || (route instanceof Read) != (scope == McpScope.READ)
                    || (route instanceof Prepare) != name.equals("prepare_media_upload"))
                throw new IllegalStateException("MCP handler authorization or dispatch mismatch: " + name);
        }
        for (String name : index.keySet())
            if (!names.contains(name))
                throw new IllegalStateException("Undeclared MCP handler: " + name);
        routes = Map.copyOf(index);
    }

    Route require(String name) {
        var route = name == null ? null : routes.get(name);
        if (route == null)
            throw McpToolPolicy.invalid();
        return route;
    }
}
