package dev.agenvas.audit.application;

import dev.agenvas.audit.domain.CallLogPage;
import java.time.Instant;
import dev.agenvas.audit.domain.DebugSettings;
import dev.agenvas.audit.domain.CallLogRetentionSettings;
import dev.agenvas.audit.domain.CallDebug;
import dev.agenvas.audit.domain.LlmStreamLog;
import dev.agenvas.shared.http.DebugHttpCapture.Exchange;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Metadata stays separate from opt-in debug bodies; HTTP headers are never stored. */
public interface CallLogRepository {
    void start(UUID id, CallLogService.CallDescriptor descriptor, String traceId, Instant startedAt);
    void finish(UUID id, CallLogService.CallOutcome outcome, Instant respondedAt, long durationMs);
    /** One short transaction completes an existing stream row and its opt-in semantic/debug bodies. */
    void finishStream(UUID id, CallLogService.CallOutcome outcome, Instant respondedAt, long durationMs,
            LlmStreamLog log, List<Exchange> exchanges, boolean captured);
    CallLogRetentionSettings retentionSettings();
    Optional<CallLogRetentionSettings> updateRetentionSettings(Integer days, int expectedVersion);
    CallLogRetentionSettings lockRetentionSettings();
    /** The caller must hold the policy lock and keep the transaction open until cleanup completes. */
    LockedExecutions lockExpiredExecutions(Instant cutoff, int batchSize);
    void deleteExecutionLogs(LockedExecutions executions);
    int deleteExpiredDiagnostics(Instant cutoff, int limit);

    /** Tasks belonging to a run are locked with it and do not consume additional batch slots. */
    record LockedExecutions(List<UUID> runIds, List<UUID> taskIds, int standaloneTaskCount) {
        public LockedExecutions {
            runIds = List.copyOf(runIds);
            taskIds = List.copyOf(taskIds);
        }

        public int unitCount() {
            return runIds.size() + standaloneTaskCount;
        }
    }

    boolean isDebugEnabled();
    DebugSettings settings();
    Optional<DebugSettings> updateSettings(boolean enabled, int expectedVersion);
    void saveDebug(UUID id, List<Exchange> exchanges);
    Optional<CallDebug> debug(UUID ownerId, UUID id);
    CallLogPage list(UUID ownerId, CallLogService.Filter filter);
}
