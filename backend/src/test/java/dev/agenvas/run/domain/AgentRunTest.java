package dev.agenvas.run.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentRunTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test void modelTurnsStartThenWaitForMediaOrFinishWithoutSkippingExecution() {
        assertThat(AgentRun.Status.QUEUED.canTransitionTo(AgentRun.Status.RUNNING)).isTrue();
        assertThat(AgentRun.Status.QUEUED.canTransitionTo(AgentRun.Status.SUCCEEDED)).isFalse();
        assertThat(AgentRun.Status.RUNNING.canTransitionTo(AgentRun.Status.WAITING_TASKS)).isTrue();
        assertThat(AgentRun.Status.WAITING_TASKS.canTransitionTo(AgentRun.Status.RUNNING)).isTrue();
        assertThat(AgentRun.Status.WAITING_TASKS.canTransitionTo(AgentRun.Status.SUCCEEDED)).isFalse();
        assertThat(AgentRun.Status.RUNNING.canTransitionTo(AgentRun.Status.SUCCEEDED)).isTrue();
        assertThat(AgentRun.Status.WAITING_TASKS.terminal()).isFalse();
    }

    @Test void failuresCanBlockOrEndButBlockedRunsMustResumeBeforeSucceeding() {
        for (var active : new AgentRun.Status[] { AgentRun.Status.QUEUED,
                AgentRun.Status.RUNNING, AgentRun.Status.WAITING_TASKS }) {
            assertThat(active.canTransitionTo(AgentRun.Status.BLOCKED)).isTrue();
            assertThat(active.canTransitionTo(AgentRun.Status.FAILED)).isTrue();
        }
        assertThat(AgentRun.Status.BLOCKED.canTransitionTo(AgentRun.Status.RUNNING)).isTrue();
        assertThat(AgentRun.Status.BLOCKED.canTransitionTo(AgentRun.Status.FAILED)).isTrue();
        assertThat(AgentRun.Status.BLOCKED.canTransitionTo(AgentRun.Status.SUCCEEDED)).isFalse();
        assertThat(AgentRun.Status.BLOCKED.canTransitionTo(AgentRun.Status.QUEUED)).isFalse();
        assertThat(AgentRun.Status.BLOCKED.terminal()).isFalse();
    }

    @Test void cancellationStopsEveryActivePhaseAndCannotResumeExecution() {
        for (var active : new AgentRun.Status[] { AgentRun.Status.QUEUED,
                AgentRun.Status.RUNNING, AgentRun.Status.WAITING_TASKS, AgentRun.Status.BLOCKED }) {
            assertThat(active.canTransitionTo(AgentRun.Status.CANCEL_REQUESTED)).isTrue();
            assertThat(active.canTransitionTo(AgentRun.Status.CANCELED)).isFalse();
        }
        assertThat(AgentRun.Status.CANCEL_REQUESTED.canTransitionTo(AgentRun.Status.CANCELED)).isTrue();
        assertThat(AgentRun.Status.CANCEL_REQUESTED.canTransitionTo(AgentRun.Status.RUNNING)).isFalse();
        assertThat(AgentRun.Status.CANCEL_REQUESTED.canTransitionTo(AgentRun.Status.FAILED)).isFalse();
        assertThat(AgentRun.Status.CANCEL_REQUESTED.terminal()).isFalse();
    }

    @Test void terminalRunsCannotBeReopenedOrChangedToAnotherOutcome() {
        for (var terminal : new AgentRun.Status[] { AgentRun.Status.CANCELED,
                AgentRun.Status.FAILED, AgentRun.Status.SUCCEEDED }) {
            assertThat(terminal.terminal()).isTrue();
            for (var target : AgentRun.Status.values()) {
                assertThat(terminal.canTransitionTo(target)).as("%s to %s", terminal, target).isFalse();
            }
        }
    }

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
