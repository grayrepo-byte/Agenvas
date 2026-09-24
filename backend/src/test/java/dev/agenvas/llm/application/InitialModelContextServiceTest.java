package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Verifies that pending Runs retain historical prompt semantics across a deployment. */
class InitialModelContextServiceTest {

    private final JsonMapper mapper = new JsonMapper();

    @Test
    void explicitV1UsesHistoricalRulesAndV2AddsMediaDisclosure() {
        ObjectNode policy = mapper.createObjectNode();
        policy.put("systemPromptVersion", 1);
        assertThat(InitialModelContextService.systemRules(policy))
                .doesNotContain("no image pixels");
        policy.put("systemPromptVersion", 2);
        assertThat(InitialModelContextService.systemRules(policy))
                .contains("no image pixels").contains("verified archived result");
    }

    @Test
    void unknownOrMalformedPromptVersionsFailClosed() {
        ObjectNode policy = mapper.createObjectNode();
        assertThatThrownBy(() -> InitialModelContextService.systemRules(policy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
        policy.put("systemPromptVersion", 3);
        assertThatThrownBy(() -> InitialModelContextService.systemRules(policy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported");
        policy.put("systemPromptVersion", "2");
        assertThatThrownBy(() -> InitialModelContextService.systemRules(policy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
    }
}
