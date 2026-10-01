package dev.agenvas.audit.application;

import dev.agenvas.audit.domain.CallLogPage;
import java.time.Instant;
import dev.agenvas.audit.domain.DebugSettings;
import dev.agenvas.audit.domain.CallLogRetentionSettings;
import dev.agenvas.audit.domain.CallDebug;
import dev.agenvas.shared.http.DebugHttpCapture.Exchange;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Metadata stays separate from opt-in, sanitized debug bodies. */
public interface CallLogRepository {
    void start(UUID id, CallLogService.CallDescriptor descriptor, String traceId, Instant startedAt);
    void finish(UUID id, CallLogService.CallOutcome outcome, Instant respondedAt, long durationMs);
    CallLogRetentionSettings retentionSettings();
    Optional<CallLogRetentionSettings> updateRetentionSettings(Integer days, int expectedVersion);
    /** Atomically cleans at most batchSize expired execution units and returns their count. */
    int purgeExpired(Instant now, int batchSize, int expectedVersion);
    boolean isDebugEnabled();
    DebugSettings settings();
    Optional<DebugSettings> updateSettings(boolean enabled, int expectedVersion);
    void saveDebug(UUID id, List<Exchange> exchanges);
    Optional<CallDebug> debug(UUID ownerId, UUID id);
    CallLogPage list(UUID ownerId, CallLogService.Filter filter);
}
