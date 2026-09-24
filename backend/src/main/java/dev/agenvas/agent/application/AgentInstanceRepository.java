package dev.agenvas.agent.application;

import dev.agenvas.agent.domain.AgentInstance;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for owner-scoped Agent card configuration and explicit bindings. */
public interface AgentInstanceRepository {

    /** Inserts one configuration without any implicit project-wide input access. */
    void create(AgentInstance instance);

    /** Lists instances and their bindings in deterministic creation order. */
    List<AgentInstance> list(UUID ownerId, UUID projectId);

    /** Reads one owner-scoped instance. */
    Optional<AgentInstance> find(UUID ownerId, UUID projectId, UUID agentId);

    /** Locks one instance for a complete optimistic configuration update. */
    Optional<AgentInstance> findForUpdate(UUID ownerId, UUID projectId, UUID agentId);

    /** Updates mutable configuration fields when the expected version matches. */
    boolean update(
            UUID ownerId,
            AgentInstance instance,
            long expectedVersion,
            Instant updatedAt);

    /** Replaces the explicit input set within the caller's transaction. */
    void replaceBindings(UUID projectId, UUID agentId, List<AgentInstance.Binding> bindings);
}
