package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.errors.OpenAIServiceException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import dev.agenvas.task.domain.Task;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentModelRetryPolicyTest {
    @ParameterizedTest @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void retriesTransientHttpFailures(int status) {
        OpenAIServiceException failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(status);
        assertThat(AgentModelRetryPolicy.retryableCode(new ModelCallFailure(failure)))
                .isEqualTo(status == 429 ? "LLM_RATE_LIMITED" : "LLM_SERVICE_UNAVAILABLE");
    }
    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 413, 422})
    void rejectsPermanentHttpFailuresEvenWithNestedTimeout(int status) {
        OpenAIServiceException failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(status);
        when(failure.getCause()).thenReturn(new SocketTimeoutException());
        assertThat(AgentModelRetryPolicy.retryableCode(new ModelCallFailure(failure))).isNull();
    }
    @Test void onlyGatewayIoOrWorkerWaitTimeoutIsRetryable() {
        var timeout = new UncheckedIOException(new SocketTimeoutException());
        assertThat(AgentModelRetryPolicy.retryableCode(timeout)).isNull();
        assertThat(AgentModelRetryPolicy.retryableCode(new ModelCallFailure(timeout))).isEqualTo("LLM_CALL_TIMEOUT");
        assertThat(AgentModelRetryPolicy.retryableCode(new TimeoutException())).isEqualTo("LLM_CALL_TIMEOUT");
        assertThat(AgentModelRetryPolicy.retryableCode(new ModelCallFailure(
                new UncheckedIOException(new ConnectException())))).isEqualTo("LLM_CONNECTION_FAILED");
        assertThat(AgentModelRetryPolicy.retryableCode(new ModelCallFailure(new IllegalArgumentException()))).isNull();
    }
    @Test void exponentialBackoffIsCappedAndDoesNotOverflowDuringLongRecovery() {
        Duration total = Duration.ZERO;
        for (int retry = 1; retry <= AgentModelRetryPolicy.MAX_RETRIES; retry++) total = total.plus(AgentModelRetryPolicy.delay(retry));
        assertThat(AgentModelRetryPolicy.delay(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(AgentModelRetryPolicy.delay(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(AgentModelRetryPolicy.delay(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(AgentModelRetryPolicy.delay(288)).isEqualTo(Duration.ofMinutes(5));
        assertThat(total).isLessThan(AgentModelRetryPolicy.WINDOW);
    }

    @Test void longRecoverySurvivesHoursAndRetainsItsOriginalDeadlineAndAttemptLimitAfterConfigurationChanges() {
        var mapper = new ObjectMapper();
        var task = mock(Task.class);
        Instant first = Instant.parse("2026-10-20T00:00:00Z");
        var decision = AgentModelRetryPolicy.afterFailure(task, "LLM_CALL_TIMEOUT", first, mapper);
        assertThat(decision.progress().path("maxRetries").asInt()).isEqualTo(288);
        assertThat(decision.progress().path("deadlineAt").asText()).isEqualTo(first.plus(Duration.ofHours(24)).toString());
        when(task.output()).thenReturn(mapper.createObjectNode().set("modelRetry", decision.progress()));
        var changed = new AgentRuntimeProperties(32768, 50, 8192, 2048, 4, Duration.ofMinutes(1), 1, Duration.ofSeconds(2), 4);
        var resumed = AgentModelRetryPolicy.afterFailure(task, "LLM_CONNECTION_FAILED", first.plus(Duration.ofHours(16)), mapper, changed);
        assertThat(resumed.exhausted()).isFalse();
        assertThat(resumed.progress().path("maxRetries").asInt()).isEqualTo(288);
        assertThat(resumed.progress().path("deadlineAt")).isEqualTo(decision.progress().path("deadlineAt"));
        assertThat(AgentModelRetryPolicy.afterFailure(task, "LLM_CALL_TIMEOUT", first.plus(Duration.ofHours(24)), mapper).exhausted()).isTrue();
    }
}
