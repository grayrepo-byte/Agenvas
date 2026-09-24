package dev.agenvas.run.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One persisted Agent instruction lifecycle with immutable creation-time snapshots. */
public record AgentRun(
        UUID id,
        UUID projectId,
        UUID agentInstanceId,
        UUID userId,
        Status status,
        String instruction,
        JsonNode contextSnapshot,
        JsonNode policySnapshot,
        int profileVersion,
        int nextStepIndex,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    /** Durable runtime states; waiting and blocked states deliberately remain active. */
    public enum Status {
        QUEUED,
        RUNNING,
        WAITING_APPROVAL,
        WAITING_TASKS,
        BLOCKED,
        CANCEL_REQUESTED,
        CANCELED,
        FAILED,
        SUCCEEDED;

        private static final Set<Status> TERMINAL = Set.of(CANCELED, FAILED, SUCCEEDED);

        /** Returns true only when no future orchestration may be scheduled for this Run. */
        public boolean terminal() {
            return TERMINAL.contains(this);
        }
    }
}
