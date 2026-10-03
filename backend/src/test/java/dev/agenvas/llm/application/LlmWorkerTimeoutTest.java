package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Workers must not cancel a model before its configured ten-minute network deadline. */
class LlmWorkerTimeoutTest {
    private static final Duration EXPECTED_TIMEOUT = Duration.ofMinutes(10);

    @Test
    void agentAndDirectTextWorkersAllowTheSameTenMinuteModelRequest() {
        assertThat(ReflectionTestUtils.getField(AgentTurnWorker.class, "MODEL_TIMEOUT"))
                .isEqualTo(EXPECTED_TIMEOUT);
        assertThat(ReflectionTestUtils.getField(DirectTextGenerationWorker.class, "MODEL_TIMEOUT"))
                .isEqualTo(EXPECTED_TIMEOUT);
    }
}
