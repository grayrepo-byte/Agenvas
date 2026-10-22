package dev.agenvas.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import dev.agenvas.mcp.domain.McpScope;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class McpCommandSchemaTest {
    @Test void schemasRequireStableCommandsAndNeverAcceptServerAuthority() {
        McpToolPolicy policy = new McpToolPolicy(new ObjectMapper());
        for (var definition : policy.definitions()) {
            var input = definition.path("inputSchema");
            assertThat(input.path("additionalProperties").asBoolean()).isFalse();
            assertThat(definition.path("outputSchema").path("type").asText()).isEqualTo("object");
            if (!"list_projects".equals(definition.path("name").asText()))
                assertThat(input.path("required").valueStream().map(n -> n.asText()).toList()).contains("projectId");
            if (policy.requiredScope(definition.path("name").asText()) != McpScope.READ)
                assertThat(input.path("required").valueStream().map(n -> n.asText()).toList()).contains("commandId");
            for (String forbidden : Set.of("userId", "ownerId", "tokenId", "runId", "operationId", "approved", "endpoint", "apiKey"))
                assertThat(input.path("properties").has(forbidden)).isFalse();
        }
    }
}
