package dev.agenvas.llm.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Pending input is revisioned; a user decision freezes the batch for execution and continuation. */
public record AgentMediaApproval(UUID id, UUID ownerId, UUID projectId, UUID runId, int stepIndex,
        String toolCallId, UUID operationId, JsonNode request, JsonNode targets,
        List<UUID> taskIds, JsonNode result, Status status, long version,
        Instant createdAt, Instant expiresAt, Instant executionDeadline,
        String decisionKey, String decisionHash) {

    public AgentMediaApproval {
        taskIds = List.copyOf(taskIds);
    }

    /** UNKNOWN tasks finish the batch as FAILED; approving again never resubmits them. */
    public enum Status {
        PENDING, APPROVED, SUCCEEDED, FAILED, REJECTED, EXPIRED, CANCELED;

        private static final Set<Status> TERMINAL =
                Set.of(SUCCEEDED, FAILED, REJECTED, EXPIRED, CANCELED);

        public boolean terminal() { return TERMINAL.contains(this); }
    }

    /** A versioned state replacement preserves the original approved payload and targets. */
    public AgentMediaApproval transition(Status nextStatus, List<UUID> newTaskIds,
            JsonNode newResult, Instant deadline, String key, String hash) {
        return new AgentMediaApproval(id, ownerId, projectId, runId, stepIndex, toolCallId,
                operationId, request, targets, newTaskIds, newResult, nextStatus,
                version + 1, createdAt, expiresAt, deadline, key, hash);
    }

    public AgentMediaApproval revise(JsonNode revisedRequest, JsonNode revisedTargets) {
        if (status != Status.PENDING) throw new IllegalStateException("Only pending approvals can be revised");
        return new AgentMediaApproval(id, ownerId, projectId, runId, stepIndex, toolCallId,
                operationId, revisedRequest, revisedTargets, taskIds, result, status,
                version + 1, createdAt, expiresAt, executionDeadline, decisionKey, decisionHash);
    }
}
