package dev.agenvas.audit.application;

import dev.agenvas.llm.application.ExecutionLedgerRetention;
import dev.agenvas.run.application.RunHistoryCleanupService;
import dev.agenvas.task.application.ProviderAttemptRetention;
import dev.agenvas.task.application.TaskHistoryCleanupService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CallLogRetentionBatchService {
    private final CallLogRepository repository;
    private final RunHistoryCleanupService runs;
    private final TaskHistoryCleanupService tasks;
    private final ExecutionLedgerRetention turns;
    private final ProviderAttemptRetention attempts;

    public CallLogRetentionBatchService(CallLogRepository repository, RunHistoryCleanupService runs,
            TaskHistoryCleanupService tasks, ExecutionLedgerRetention turns, ProviderAttemptRetention attempts) {
        this.repository = repository;
        this.runs = runs;
        this.tasks = tasks;
        this.turns = turns;
        this.attempts = attempts;
    }

    /** Fence local workers before erasing their history. Project events and ledger deletions
     * commit together, independently of earlier batches in the same administrator request. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired(Instant now, int batchSize, int expectedVersion) {
        var policy = repository.lockRetentionSettings();
        if (policy.version() != expectedVersion)
            throw CallLogRetentionService.policyConflict();
        if (policy.retentionDays() == null)
            return 0;
        Instant cutoff = now.minus(policy.retentionDays(), ChronoUnit.DAYS);
        var executions = repository.lockExpiredExecutions(cutoff, batchSize);
        runs.stopFor(executions.runIds(), now);
        tasks.stopFor(executions.taskIds(), now);
        repository.deleteExecutionLogs(executions);
        turns.deleteFor(executions.runIds());
        attempts.deleteFor(executions.taskIds());
        return executions.unitCount() + repository.deleteExpiredDiagnostics(cutoff, batchSize - executions.unitCount());
    }
}
