package dev.agenvas.audit.application;

import dev.agenvas.audit.domain.CallLogPage;
import java.time.Instant;
import java.util.UUID;

/** Only stores allowlisted identifiers and timings; request/response content has no field here. */
public interface CallLogRepository {
    void start(UUID id, CallLogService.CallDescriptor descriptor, String traceId, Instant startedAt);
    void finish(UUID id, CallLogService.CallOutcome outcome, Instant respondedAt, long durationMs);
    CallLogPage list(UUID ownerId, CallLogService.Filter filter);
}
