package dev.agenvas.run.application;

import dev.agenvas.run.domain.AgentRun;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for owner-scoped Agent Runs and command idempotency records. */
public interface AgentRunRepository {

    /** Reserves a principal/scope/key before any business side effect. */
    boolean reserveIdempotency(
            UUID principalId,
            String scope,
            String key,
            String requestHash,
            Instant expiresAt,
            Instant now);

    /** Reads a competing or completed idempotency record after uniqueness arbitration. */
    Optional<IdempotencyRecord> findIdempotency(UUID principalId, String scope, String key);

    /** Completes a reserved record with the created Run response in the same transaction. */
    boolean completeIdempotency(
            UUID principalId,
            String scope,
            String key,
            String requestHash,
            UUID resourceId,
            String responseJson,
            Instant now);

    /** Inserts one Run after its project slot has been checked. */
    void create(AgentRun run);

    /** Reads one nested Run inside the authenticated owner boundary. */
    Optional<AgentRun> find(UUID ownerId, UUID projectId, UUID runId);

    /** Lists newest Runs for one owned Agent using a stable created-time keyset. */
    List<AgentRun> list(UUID ownerId, UUID projectId, UUID agentId,
            Instant beforeCreatedAt, UUID beforeId, int limit);

    /** Locks one nested Run for a state transition. */
    Optional<AgentRun> findForUpdate(UUID ownerId, UUID projectId, UUID runId);

    /** Changes state with a version guard and optional completion timestamp. */
    boolean updateStatus(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            long expectedVersion,
            AgentRun.Status status,
            Instant updatedAt,
            Instant completedAt);

    /** Advances the durable model-step cursor without changing Run status. */
    boolean advanceStep(UUID ownerId, UUID projectId, UUID runId,
            long expectedVersion, int expectedStepIndex, Instant updatedAt);

    /** One durable idempotency arbitration result. */
    record IdempotencyRecord(
            String requestHash,
            State state,
            UUID resourceId,
            String responseJson,
            Instant expiresAt) {

        /** Reservation lifecycle. */
        public enum State {
            IN_PROGRESS,
            COMPLETED
        }
    }
}
