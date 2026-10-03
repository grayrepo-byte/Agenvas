package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Missing historical keys remain explicit even when model calls run in a Future. */
class AgentTurnFailureCodeTest {
    @Test
    void exposesNetworkAndWorkerTimeoutsThroughAsyncWrappers() {
        assertThat(AgentTurnWorker.failureCode(new ExecutionException(new SocketTimeoutException("synthetic"))))
                .isEqualTo("LLM_CALL_TIMEOUT");
        assertThat(AgentTurnWorker.failureCode(new TimeoutException()))
                .isEqualTo("LLM_CALL_TIMEOUT");
    }

    @Test
    void keepsExpectedAuthenticationBlockCodeThroughAsyncWrapper() {
        ApiProblemException missing = new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                "CREDENTIAL_KEY_VERSION_MISSING", dev.agenvas.shared.i18n.ApiMessage.of("problem.fallback"), dev.agenvas.shared.i18n.ApiMessage.of("problem.fallback"), false);
        assertThat(AgentTurnWorker.failureCode(new ExecutionException(missing)))
                .isEqualTo("CREDENTIAL_KEY_VERSION_MISSING");
        assertThat(AgentTurnWorker.failureCode(new IllegalStateException("arbitrary")))
                .isEqualTo("AGENT_TURN_FAILED");
    }
}
