package dev.agenvas.audit.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.audit.domain.DebugSettings;
import dev.agenvas.audit.domain.CallDebug;
import dev.agenvas.shared.http.DebugHttpCapture;
import dev.agenvas.audit.domain.CallLogPage;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable metadata around one adapter call; never encloses network traffic in a transaction. */
@Service
public class CallLogService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    private static final long NANOS_PER_MILLISECOND = 1_000_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(CallLogService.class);
    private final CallLogRepository repository;
    private final Clock clock;

    public CallLogService(CallLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public record CallDescriptor(UUID projectId, UUID taskId, UUID runId, Integer stepIndex,
            CallLog.Kind kind, CallLog.Operation operation, String provider, String model,
            boolean mock) {}
    public record CallOutcome(CallLog.Status status, String providerRequestId, String errorCode) {
        public static CallOutcome succeeded(String requestId) {
            return new CallOutcome(CallLog.Status.SUCCEEDED, requestId, null);
        }
    }
    public record Filter(UUID projectId, CallLog.Kind kind, CallLog.Status status, String traceId,
            Instant from, Instant to, int page, int size) {}

    /** A new trace belongs to this network call, while a caller's HTTP trace is restored afterward. */
    public <T> T record(CallDescriptor descriptor, Supplier<T> invocation,
            Function<T, CallOutcome> outcome) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provider calls must not hold a database transaction");
        }
        UUID id = UUID.randomUUID();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        CallDescriptor safe = new CallDescriptor(descriptor.projectId(), descriptor.taskId(),
                descriptor.runId(), descriptor.stepIndex(), descriptor.kind(), descriptor.operation(),
                safeIdentifier(descriptor.provider()), safeIdentifier(descriptor.model()), descriptor.mock());
        repository.start(id, safe, traceId, clock.instant());
        // Pin the setting at invocation start. Each request checkpoint precedes network I/O.
        DebugHttpCapture capture = repository.isDebugEnabled() ? DebugHttpCapture.open(exchanges -> {
            try { repository.saveDebug(id, exchanges); }
            catch (RuntimeException failure) {
                LOGGER.error("Debug call capture write failed callId={} code=CALL_DEBUG_WRITE_FAILED", id);
            }
        }) : null;
        String previousTrace = MDC.get("traceId");
        MDC.put("traceId", traceId);
        long started = System.nanoTime();
        try {
            T result;
            try {
                result = invocation.get();
            } catch (RuntimeException | Error failure) {
                CallLog.Status status = descriptor.operation() == CallLog.Operation.SUBMIT
                        ? CallLog.Status.UNKNOWN : CallLog.Status.FAILED;
                String code = failure instanceof ApiProblemException problem
                        ? safeErrorCode(problem.code()) : "CALL_TECHNICAL_FAILURE";
                finish(id, new CallOutcome(status, null, code), clock.instant(), elapsed(started));
                throw failure;
            }
            // Capture the response boundary before serialization, archival or task state commits.
            Instant respondedAt = clock.instant();
            long durationMs = elapsed(started);
            finish(id, outcome.apply(result), respondedAt, durationMs);
            return result;
        } finally {
            if (capture != null) capture.close();
            if (previousTrace == null) MDC.remove("traceId");
            else MDC.put("traceId", previousTrace);
        }
    }

    public DebugSettings settings() { return repository.settings(); }

    public DebugSettings updateSettings(boolean enabled, int expectedVersion) {
        return repository.updateSettings(enabled, expectedVersion).orElseThrow(() ->
                new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT", ApiMessage.of("api.call-log-service.settings-changed"),
                        ApiMessage.of("api.call-log-service.please-re-read-the-debug-mode-settings-before-saving"), false));
    }

    public CallDebug debug(UUID ownerId, UUID id) {
        return repository.debug(ownerId, id).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "CALL_LOG_NOT_FOUND", ApiMessage.of("api.call-log-service.call-record-does-not-exist"),
                        ApiMessage.of("api.call-log-service.the-call-record-does-not-exist-or-does-not-belong"), false));
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / NANOS_PER_MILLISECOND);
    }

    private void finish(UUID id, CallOutcome outcome, Instant respondedAt, long durationMs) {
        CallOutcome safe = new CallOutcome(outcome.status(), safeRequestId(outcome.providerRequestId()),
                safeErrorCode(outcome.errorCode()));
        try {
            repository.finish(id, safe, respondedAt, durationMs);
        } catch (RuntimeException persistenceFailure) {
            // A response already exists: keep its original task/checkpoint path and never resubmit
            // because an independent audit write failed. The incomplete row retains null timings.
            LOGGER.error("Provider call audit response write failed callId={} code=CALL_AUDIT_WRITE_FAILED", id);
        }
        LOGGER.info("Provider call finished callId={} status={} durationMs={}", id, safe.status(), durationMs);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CallLogPage list(UUID ownerId, Filter filter) {
        if (filter.page() < 0 || filter.size() < 1 || filter.size() > MAX_PAGE_SIZE
                || filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())
                || filter.traceId() != null && !filter.traceId().matches("[0-9a-f]{32}")) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.system-log-buffer.log-filtering-is-invalid"), ApiMessage.of("api.call-log-service.please-check-the-page-number-quantity-per-page-time-range"), false);
        }
        return repository.list(ownerId, filter);
    }

    public static String safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")
                && !value.contains("://") ? value : null;
    }
    public static String safeRequestId(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,240}") ? value : null;
    }
    public static String safeErrorCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,119}") ? value : null;
    }
}
