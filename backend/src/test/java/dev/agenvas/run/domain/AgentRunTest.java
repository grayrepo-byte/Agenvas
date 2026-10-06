package dev.agenvas.run.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentRunTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test void newNullPoliciesHaveNoModelOrToolCountCap() {
        var policy = mapper.createObjectNode().put("schemaVersion", 6);
        policy.putNull("maxModelTurns").putNull("maxToolExecutions");
        var run = run(policy);
        assertThat(run.modelTurnLimitReached(Integer.MAX_VALUE)).isFalse();
        assertThat(run.hasToolExecutionLimit()).isFalse();
        assertThat(run.toolExecutionLimitReached(Long.MAX_VALUE)).isFalse();
    }

    @Test void historicalAndExplicitNumericLimitsRetainTheirFrozenBoundaries() {
        var historical = run(mapper.createObjectNode());
        assertThat(historical.modelTurnLimitReached(11)).isFalse();
        assertThat(historical.modelTurnLimitReached(12)).isTrue();
        assertThat(historical.hasToolExecutionLimit()).isTrue();
        assertThat(historical.toolExecutionLimitReached(39)).isFalse();
        assertThat(historical.toolExecutionLimitReached(40)).isTrue();
        var custom = run(mapper.createObjectNode().put("schemaVersion", 6)
                .put("maxModelTurns", 3).put("maxToolExecutions", 7));
        assertThat(custom.modelTurnLimitReached(2)).isFalse();
        assertThat(custom.modelTurnLimitReached(3)).isTrue();
        assertThat(custom.toolExecutionLimitReached(6)).isFalse();
        assertThat(custom.toolExecutionLimitReached(7)).isTrue();
    }

    @Test void malformedLimitsCannotBeInterpretedAsUnbounded() {
        for (String json : new String[] {"{\"schemaVersion\":5,\"maxModelTurns\":null}",
                "{\"maxModelTurns\":0}", "{\"maxModelTurns\":\"12\"}"})
            assertThatThrownBy(() -> run(mapper.readTree(json)).modelTurnLimitReached(0))
                    .isInstanceOf(IllegalStateException.class);
    }

    private AgentRun run(JsonNode policy) {
        return new AgentRun(null, null, null, null, 1, null, AgentRun.Status.RUNNING, "Synthetic task",
                mapper.createObjectNode(), policy, 1, 0, 0, Instant.EPOCH, Instant.EPOCH, null);
    }
}
