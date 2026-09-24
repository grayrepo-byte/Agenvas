package dev.agenvas.task.application;

import dev.agenvas.task.domain.Task;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fences Agent business mutations using the same database row as Task claim/reclaim. */
@Service
public class AgentTurnLeaseGuard {

    private final TaskRepository tasks;
    private final Clock clock;

    public AgentTurnLeaseGuard(TaskRepository tasks, Clock clock) {
        this.tasks = tasks;
        this.clock = clock;
    }

    /** Locks the active lease until the caller's transaction commits or rolls back. */
    @Transactional
    public void requireActive(Task lease, String workerId) {
        if (lease == null || lease.kind() != Task.Kind.AGENT_TURN
                || workerId == null || workerId.isBlank()
                || !tasks.lockActiveAgentTurnLease(lease.projectId(), lease.runId(),
                        lease.id(), workerId, lease.leaseEpoch(), clock.instant())) {
            throw new IllegalStateException("Agent turn lease was lost or canceled");
        }
    }
}
