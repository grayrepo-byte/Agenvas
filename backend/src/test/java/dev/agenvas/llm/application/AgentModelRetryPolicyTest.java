package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.errors.OpenAIServiceException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.time.Duration;
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
    @Test void exponentialBackoffIsCappedAndTenFastRetriesFitTheWindow() {
        Duration total = Duration.ZERO;
        for (int retry = 1; retry <= AgentModelRetryPolicy.MAX_RETRIES; retry++) total = total.plus(AgentModelRetryPolicy.delay(retry));
        assertThat(AgentModelRetryPolicy.delay(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(AgentModelRetryPolicy.delay(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(AgentModelRetryPolicy.delay(10)).isEqualTo(Duration.ofSeconds(30));
        assertThat(total).isLessThan(AgentModelRetryPolicy.WINDOW);
    }
}
