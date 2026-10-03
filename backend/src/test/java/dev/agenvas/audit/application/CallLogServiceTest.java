package dev.agenvas.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.agenvas.audit.domain.CallLog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CallLogServiceTest {
    private static final Instant START = Instant.parse("2026-09-26T10:00:00Z");
    private final CallLogRepository repository = mock(CallLogRepository.class);
    private final MutableClock clock = new MutableClock();
    private final CallLogService service = new CallLogService(repository, clock, mock(AsyncCallLogWriter.class));

    @AfterEach void clearThread() { MDC.clear(); TransactionSynchronizationManager.clear(); }

    @Test void restoresParentTraceAndCapturesResponseBeforeAuditPersistence() {
        MDC.put("traceId", "parent-http-trace");
        AtomicReference<String> trace = new AtomicReference<>();
        doAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo(trace.get());
            clock.now = START.plusSeconds(100);
            return null;
        }).when(repository).finish(any(), any(), any(), anyLong());
        String result = service.record(descriptor(CallLog.Operation.CHAT), () -> {
            trace.set(MDC.get("traceId"));
            assertThat(trace.get()).matches("[0-9a-f]{32}");
            clock.now = START.plusMillis(42);
            return "private response";
        }, ignored -> CallLogService.CallOutcome.succeeded(null));
        assertThat(result).isEqualTo("private response");
        assertThat(MDC.get("traceId")).isEqualTo("parent-http-trace");
        ArgumentCaptor<Instant> responseAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).finish(any(), any(), responseAt.capture(), anyLong());
        assertThat(responseAt.getValue()).isEqualTo(START.plusMillis(42));
        ArgumentCaptor<String> savedTrace = ArgumentCaptor.forClass(String.class);
        verify(repository).start(any(), any(), savedTrace.capture(), any());
        assertThat(savedTrace.getValue()).isEqualTo(trace.get());
    }

    @Test void uncertainSubmissionIsRecordedWithoutExceptionMessageAndTraceIsRemoved() {
        RuntimeException failure = new IllegalStateException("Bearer secret https://provider.invalid/private");
        assertThatThrownBy(() -> service.record(descriptor(CallLog.Operation.SUBMIT),
                () -> { throw failure; }, ignored -> CallLogService.CallOutcome.succeeded(null)))
                .isSameAs(failure);
        ArgumentCaptor<CallLogService.CallOutcome> outcome = ArgumentCaptor.forClass(CallLogService.CallOutcome.class);
        verify(repository).finish(any(), outcome.capture(), any(), anyLong());
        assertThat(outcome.getValue()).isEqualTo(new CallLogService.CallOutcome(
                CallLog.Status.UNKNOWN, null, "CALL_TECHNICAL_FAILURE"));
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test void failedPollIsNotAnUncertainSubmissionAndParentTraceSurvives() {
        MDC.put("traceId", "parent");
        assertThatThrownBy(() -> service.record(descriptor(CallLog.Operation.POLL),
                () -> { throw new IllegalStateException("secret"); }, ignored -> CallLogService.CallOutcome.succeeded(null)))
                .isInstanceOf(IllegalStateException.class);
        ArgumentCaptor<CallLogService.CallOutcome> outcome = ArgumentCaptor.forClass(CallLogService.CallOutcome.class);
        verify(repository).finish(any(), outcome.capture(), any(), anyLong());
        assertThat(outcome.getValue().status()).isEqualTo(CallLog.Status.FAILED);
        assertThat(MDC.get("traceId")).isEqualTo("parent");
    }

    @Test void auditCompletionFailureDoesNotDiscardAProviderResponseOrRetryIt() {
        doThrow(new IllegalStateException("database unavailable")).when(repository)
                .finish(any(), any(), any(), anyLong());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThat(service.record(descriptor(CallLog.Operation.SUBMIT), () -> {
            calls.incrementAndGet(); return "already accepted";
        }, ignored -> CallLogService.CallOutcome.succeeded("original-request")))
                .isEqualTo("already accepted");
        assertThat(calls).hasValue(1);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test void rejectsTransactionBeforeCallingProviderOrCreatingAuditRow() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> service.record(descriptor(CallLog.Operation.CHAT),
                () -> "unexpected", ignored -> CallLogService.CallOutcome.succeeded(null)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test void metadataSanitizationRejectsEndpointsBodiesAndInvalidProviderCodes() {
        assertThat(CallLogService.safeIdentifier("https://provider.invalid/v1")).isNull();
        assertThat(CallLogService.safeIdentifier("model-v2.1/image")).isEqualTo("model-v2.1/image");
        assertThat(CallLogService.safeRequestId("Bearer secret")).isNull();
        assertThat(CallLogService.safeErrorCode("provider failure with private details")).isNull();
        assertThat(CallLogService.safeErrorCode("PROVIDER_TIMEOUT")).isEqualTo("PROVIDER_TIMEOUT");
    }

    private static CallLogService.CallDescriptor descriptor(CallLog.Operation operation) {
        return new CallLogService.CallDescriptor(UUID.randomUUID(),
                operation == CallLog.Operation.CHAT ? null : UUID.randomUUID(), UUID.randomUUID(),
                operation == CallLog.Operation.CHAT ? 0 : null,
                operation == CallLog.Operation.CHAT ? CallLog.Kind.LLM : CallLog.Kind.IMAGE,
                operation, "MOCK", "mock-model", true);
    }

    private static class MutableClock extends Clock {
        Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
