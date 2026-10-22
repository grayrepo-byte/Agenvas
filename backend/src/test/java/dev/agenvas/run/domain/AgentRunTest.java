package dev.agenvas.run.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class AgentRunTest {
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

}
