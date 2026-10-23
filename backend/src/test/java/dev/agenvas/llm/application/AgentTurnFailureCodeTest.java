package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.errors.OpenAIServiceException;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Missing historical keys remain explicit even when model calls run in a Future. */
class AgentTurnFailureCodeTest {
    @Test
    void doesNotRetryStructuredQuotaExhaustionOrTreatToolMessagesAsBilling() {
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(429);
        when(failure.code()).thenReturn(java.util.Optional.of("insufficient_quota"));
        when(failure.getSuppressed()).thenReturn(new Throwable[0]);
        assertThat(AgentTurnWorker.failureCode(new ModelCallFailure(failure)))
                .isEqualTo("LLM_INSUFFICIENT_CREDITS");
        assertThat(AgentModelRetryPolicy.retryableCode(new ModelCallFailure(failure))).isNull();
        assertThat(AgentTurnWorker.failureCode(failure)).isEqualTo("AGENT_TURN_FAILED");
        assertThat(AgentTurnWorker.failureCode(new IllegalStateException("insufficient credits")))
                .isEqualTo("AGENT_TURN_FAILED");
    }

    @Test
    void reportsUnsupportedAudioInputAtTheModelBoundary() {
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(422);
        when(failure.getSuppressed()).thenReturn(new Throwable[0]);
        when(failure.getMessage()).thenReturn("unknown variant `input_audio`, expected one of `text`, `image_url`, `file`");
        var wrapped = new ExecutionException(new ModelCallFailure(failure));
        assertThat(AgentTurnWorker.failureCode(wrapped)).isEqualTo("LLM_MEDIA_INPUT_UNSUPPORTED");
        assertThat(AgentModelRetryPolicy.retryableCode(wrapped)).isNull();
        assertThat(AgentTurnWorker.failureCode(failure)).isEqualTo("AGENT_TURN_FAILED");
    }

    @Test
    void ordinaryBadRequestRemainsGeneric() {
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(400);
        when(failure.getMessage()).thenReturn("invalid model");
        when(failure.getSuppressed()).thenReturn(new Throwable[0]);
        assertThat(AgentTurnWorker.failureCode(new ModelCallFailure(failure))).isEqualTo("AGENT_TURN_FAILED");
    }

    @Test
    void invalidAudioEncodingDoesNotImplyUnsupportedInput() {
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(400);
        when(failure.getSuppressed()).thenReturn(new Throwable[0]);
        when(failure.getMessage()).thenReturn("invalid base64 encoding for input_audio");
        assertThat(AgentTurnWorker.failureCode(new ModelCallFailure(failure))).isEqualTo("AGENT_TURN_FAILED");
    }

    @Test
    void reportsInsufficientCreditsWithoutPublishingProviderText() {
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(400);
        when(failure.getSuppressed()).thenReturn(new Throwable[0]);
        when(failure.getMessage()).thenReturn("400: You have insufficient credits to make this request. private-account-detail");
        var wrapped = new ExecutionException(new ModelCallFailure(failure));
        assertThat(AgentTurnWorker.failureCode(wrapped)).isEqualTo("LLM_INSUFFICIENT_CREDITS");
        assertThat(AgentModelRetryPolicy.retryableCode(wrapped)).isNull();
    }

    @Test
    void keepsVersionConflictsThroughWrappersWithoutPublishingOtherBusinessErrors() {
        var safeMessage = dev.agenvas.shared.i18n.ApiMessage.of("problem.fallback");
        assertThat(AgentTurnWorker.failureCode(new ExecutionException(new ApiProblemException(
                HttpStatus.CONFLICT, "ARTIFACT_VERSION_CONFLICT", safeMessage, safeMessage, false))))
                .isEqualTo("ARTIFACT_VERSION_CONFLICT");
        assertThat(AgentTurnWorker.failureCode(new ApiProblemException(
                HttpStatus.INTERNAL_SERVER_ERROR, "SYNTHETIC_INTERNAL_CODE", safeMessage, safeMessage, false)))
                .isEqualTo("AGENT_TURN_FAILED");
    }

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
