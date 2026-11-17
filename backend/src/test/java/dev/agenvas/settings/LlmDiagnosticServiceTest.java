package dev.agenvas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import dev.agenvas.audit.application.AsyncCallLogWriter;
import dev.agenvas.audit.application.CallLogRepository;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmStreamIncompleteException;
import dev.agenvas.settings.application.*;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import tools.jackson.databind.ObjectMapper;

/** Synthetic replies exercise the actual diagnostic protocol and durable metadata boundary. */
class LlmDiagnosticServiceTest {
    private final LlmProviderConfigRepository configs = mock(LlmProviderConfigRepository.class);
    private final LlmProviderConfigService settings = mock(LlmProviderConfigService.class);
    private final LlmDiagnosticGateway factory = mock(LlmDiagnosticGateway.class);
    private final CallLogRepository logs = mock(CallLogRepository.class);
    private final CallLogService callLogs = new CallLogService(logs, Clock.systemUTC(), mock(AsyncCallLogWriter.class));
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatGateway gateway = mock(ChatGateway.class);
    private final LlmDiagnosticService service = new LlmDiagnosticService(configs, settings, factory, mapper, callLogs);

    private void configure() {
        var value = new LlmProviderConfig(UUID.randomUUID(), 1, "https://synthetic.invalid/v1", "probe-model",
                new byte[0], new byte[0], 1, "masked", false, true, Instant.EPOCH);
        when(configs.active()).thenReturn(java.util.Optional.of(value));
        when(configs.markToolCallingVerified(1)).thenReturn(true);
        when(factory.open(value)).thenReturn(gateway);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void incompleteDiagnosticStreamNamesTheRoundLogsFailureAndCannotVerify(int round) {
        configure();
        when(gateway.call(any(), any(), any())).thenAnswer(invocation -> {
            List<org.springframework.ai.chat.messages.Message> messages = invocation.getArgument(0);
            if (round == 1 || messages.size() > 2)
                throw LlmStreamIncompleteException.problem();
            return toolResponse(messages);
        });
        assertThatThrownBy(() -> service.diagnose(1, true)).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> {
                    assertThat(problem.code()).isEqualTo("LLM_STREAM_INCOMPLETE");
                    assertThat(problem.detail().key()).isEqualTo("api.llm-diagnostic-service.incomplete-stream-stage");
                    assertThat(problem.detail().arguments()).containsExactly(Integer.toString(round));
                    assertThat(problem.retryable()).isFalse();
                });
        if (round == 1)
            assertLogOutcomes(CallLog.Status.FAILED);
        else
            assertLogOutcomes(CallLog.Status.SUCCEEDED, CallLog.Status.FAILED);
        var outcome = ArgumentCaptor.forClass(CallLogService.CallOutcome.class);
        verify(logs, times(round)).finish(any(), outcome.capture(), any(), anyLong());
        assertThat(outcome.getAllValues().getLast().errorCode()).isEqualTo("LLM_STREAM_INCOMPLETE");
        verify(configs, never()).markToolCallingVerified(anyInt());
    }

    @Test
    void failedFirstProbeRecordsMetadataAndExplainsMissingToolWithoutSavingBodies() {
        configure();
        when(gateway.call(any(), any(), any())).thenReturn(response(new AssistantMessage("No tool call")));
        assertThatThrownBy(() -> service.diagnose(1, true)).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> {
                    assertThat(problem.code()).isEqualTo("PROVIDER_TOOL_PROTOCOL_UNVERIFIED");
                    assertThat(problem.detail().key()).isEqualTo("api.llm-diagnostic-service.first-response-no-tool");
                });
        assertLogOutcomes(CallLog.Status.FAILED);
        var outcome = ArgumentCaptor.forClass(CallLogService.CallOutcome.class);
        verify(logs).finish(any(), outcome.capture(), any(), anyLong());
        assertThat(outcome.getValue().errorCode()).isEqualTo("PROVIDER_DIAGNOSTIC_NO_TOOL");
        verify(logs, never()).saveDebug(any(), any());
        verify(configs, never()).markToolCallingVerified(anyInt());
    }

    @Test
    void successfulRoundTripRecordsTwoCallsAndNeverInvokesBusinessTools() {
        configure();
        answerProbe(false);
        service.diagnose(1, true);
        assertLogOutcomes(CallLog.Status.SUCCEEDED, CallLog.Status.SUCCEEDED);
        verify(configs).markToolCallingVerified(1);
        var descriptors = ArgumentCaptor.forClass(CallLogService.CallDescriptor.class);
        verify(logs, times(2)).start(any(), descriptors.capture(), any(), any());
        assertThat(descriptors.getAllValues()).allSatisfy(value -> {
            assertThat(value.projectId()).isNull();
            assertThat(value.taskId()).isNull();
            assertThat(value.runId()).isNull();
            assertThat(value.operation().name()).isEqualTo("DIAGNOSTIC");
            assertThat(value.provider()).isEqualTo("openai-compatible");
            assertThat(value.model()).isEqualTo("probe-model");
        });
    }

    @Test
    void proofWithExtraUnrequestedTextFailsAtSecondRoundAndIsLogged() {
        configure();
        answerProbe(true);
        assertThatThrownBy(() -> service.diagnose(1, true)).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> assertThat(problem.detail().key())
                        .isEqualTo("api.llm-diagnostic-service.second-response-invalid-proof"));
        assertLogOutcomes(CallLog.Status.SUCCEEDED, CallLog.Status.FAILED);
        verify(configs, never()).markToolCallingVerified(anyInt());
    }

    @Test
    void unavailableSecondCallExplainsWhichRoundAndDoesNotLeakRemoteMessage() {
        configure();
        when(gateway.call(any(), any(), any())).thenAnswer(invocation -> {
            List<org.springframework.ai.chat.messages.Message> messages = invocation.getArgument(0);
            if (messages.size() > 2)
                throw new IllegalStateException("sk-private SECRET remote body");
            return toolResponse(messages);
        });
        assertThatThrownBy(() -> service.diagnose(1, true)).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> {
                    assertThat(problem.code()).isEqualTo("PROVIDER_DIAGNOSTIC_UNAVAILABLE");
                    assertThat(problem.detail().arguments()).containsExactly("2");
                    assertThat(problem.getMessage()).doesNotContain("SECRET", "sk-private");
                });
        assertLogOutcomes(CallLog.Status.SUCCEEDED, CallLog.Status.FAILED);
    }

    @Test
    void tokenLimitedProbeCannotVerifyToolSupport() {
        configure();
        when(gateway.call(any(), any(), any())).thenAnswer(invocation -> {
            ChatGateway.Exchange good = toolResponse(invocation.getArgument(0));
            return new ChatGateway.Exchange(1, new ChatResponse(List.of(new Generation(
                    good.response().getResult().getOutput(),
                    ChatGenerationMetadata.builder().finishReason("length").build()))));
        });
        assertThatThrownBy(() -> service.diagnose(1, true)).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> assertThat(problem.code()).isEqualTo("LLM_OUTPUT_TRUNCATED"));
        assertLogOutcomes(CallLog.Status.FAILED);
        verify(configs, never()).markToolCallingVerified(anyInt());
    }

    @Test
    void invalidToolTypeFailsBeforeSecondCallAndNeverVerifies() {
        configure();
        when(gateway.call(any(), any(), any())).thenAnswer(invocation -> {
            var good = toolResponse(invocation.getArgument(0));
            var call = good.response().getResult().getOutput().getToolCalls().getFirst();
            return response(AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall(call.id(), "other", call.name(), call.arguments()))).build());
        });
        assertThatThrownBy(() -> service.diagnose(1, true)).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> assertThat(problem.detail().key())
                        .isEqualTo("api.llm-diagnostic-service.first-response-invalid-tool"));
        assertLogOutcomes(CallLog.Status.FAILED);
        verify(gateway).call(any(), any(), any());
        verify(configs, never()).markToolCallingVerified(anyInt());
    }

    private void answerProbe(boolean extraText) {
        when(gateway.call(any(), any(), any())).thenAnswer(invocation -> {
            List<org.springframework.ai.chat.messages.Message> messages = invocation.getArgument(0);
            if (messages.size() == 2)
                return toolResponse(messages);
            var result = (ToolResponseMessage) messages.getLast();
            String proof = mapper.readTree(result.getResponses().getFirst().responseData()).path("proof").asText();
            return response(new AssistantMessage(extraText ? "The proof is " + proof : proof));
        });
    }

    private ChatGateway.Exchange toolResponse(List<org.springframework.ai.chat.messages.Message> messages) {
        String nonce = messages.getLast().getText().substring("Call agenvas_connection_probe with nonce ".length());
        return response(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                "probe-1", "function", "agenvas_connection_probe", "{\"nonce\":\"" + nonce + "\"}"))).build());
    }

    private static ChatGateway.Exchange response(AssistantMessage message) {
        return new ChatGateway.Exchange(1, new ChatResponse(List.of(new Generation(message))));
    }

    private void assertLogOutcomes(CallLog.Status... statuses) {
        var outcome = ArgumentCaptor.forClass(CallLogService.CallOutcome.class);
        verify(logs, times(statuses.length)).finish(any(), outcome.capture(), any(), anyLong());
        assertThat(outcome.getAllValues()).extracting(CallLogService.CallOutcome::status).containsExactly(statuses);
    }
}
