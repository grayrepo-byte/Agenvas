package dev.agenvas.audit.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.audit.domain.LlmStreamLog;
import dev.agenvas.llm.application.LlmStreamLogCollector;
import dev.agenvas.shared.http.DebugHttpCapture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class CallLogStreamServiceTest {
    private final CallLogRepository repository = mock(CallLogRepository.class);
    private final AsyncCallLogWriter writer = mock(AsyncCallLogWriter.class);
    private final CallLogService service = new CallLogService(repository, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), writer);

    @Test void durableStartPrecedesStreamAndOnlySanitizedFinalSnapshotIsEnqueued() {
        when(repository.isDebugEnabled()).thenReturn(true);
        MDC.put("traceId", "parent");
        try {
            var result = service.recordStream(descriptor(), (capture, listener) -> {
                verify(repository).start(any(), any(), anyString(), any());
                verify(repository, never()).saveDebug(any(), anyList());
                verifyNoInteractions(writer);
                assertThat(capture).isTrue();
                assertThat(DebugHttpCapture.enabled()).isTrue();
                listener.accept(log("public sk-synthetic-unusable-secret <think>private</think>"));
                return "model result";
            }, ignored -> CallLogService.CallOutcome.succeeded("synthetic-response"));
            assertThat(result).isEqualTo("model result");
            assertThat(MDC.get("traceId")).isEqualTo("parent");
            assertThat(DebugHttpCapture.enabled()).isFalse();
            var captured = ArgumentCaptor.forClass(LlmStreamLog.class);
            verify(writer).submit(any(), any(), any(), anyLong(), captured.capture(), anyList(), eq(true));
            assertThat(captured.getValue().content().response()).doesNotContain("sk-synthetic-unusable-secret", "private");
            verify(repository, never()).finish(any(), any(), any(), anyLong());
        } finally { MDC.clear(); }
    }

    @Test void disabledBodiesAreDroppedEvenIfAdapterAccidentallySuppliesContent() {
        service.recordStream(descriptor(), (capture, listener) -> {
            assertThat(capture).isFalse(); listener.accept(log("synthetic private content")); return "ok";
        }, ignored -> CallLogService.CallOutcome.succeeded(null));
        var captured = ArgumentCaptor.forClass(LlmStreamLog.class);
        verify(writer).submit(any(), any(), any(), anyLong(), captured.capture(), anyList(), eq(false));
        assertThat(captured.getValue().content()).isNull();
        assertThat(captured.getValue().metrics().firstTextMs()).isNotNull();
    }

    @Test void originalFailureAndPartialLogSurviveWithoutModelRetry() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException failure = new IllegalStateException("synthetic private failure");
        assertThatThrownBy(() -> service.recordStream(descriptor(), (capture, listener) -> {
            calls.incrementAndGet(); listener.accept(log("partial")); throw failure;
        }, ignored -> CallLogService.CallOutcome.succeeded(null))).isSameAs(failure);
        assertThat(calls).hasValue(1);
        var outcome = ArgumentCaptor.forClass(CallLogService.CallOutcome.class);
        verify(writer).submit(any(), outcome.capture(), any(), anyLong(), any(), anyList(), anyBoolean());
        assertThat(outcome.getValue().status()).isEqualTo(CallLog.Status.FAILED);
        assertThat(outcome.getValue().errorCode()).isEqualTo("CALL_TECHNICAL_FAILURE");
        assertThat(MDC.get("traceId")).isNull();
    }

    private LlmStreamLog log(String text) {
        var collector = new LlmStreamLogCollector(true);
        collector.chunk(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
        return collector.snapshot(LlmStreamLog.EndStatus.COMPLETED, null);
    }
    private CallLogService.CallDescriptor descriptor() {
        return new CallLogService.CallDescriptor(UUID.randomUUID(), null, UUID.randomUUID(), 0,
                CallLog.Kind.LLM, CallLog.Operation.CHAT, "synthetic", "synthetic-model", false);
    }
}
