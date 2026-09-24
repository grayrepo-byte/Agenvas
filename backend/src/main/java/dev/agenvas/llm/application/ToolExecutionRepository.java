package dev.agenvas.llm.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Persistence boundary for Run/step/tool-call idempotency and the per-Run tool budget. */
public interface ToolExecutionRepository {

    /** Counts already completed calls while the Run row lock serializes new executions. */
    long countByRun(UUID projectId, UUID runId);

    /** Returns an exact call only within the scoped project. */
    Optional<ToolExecution> find(UUID projectId, UUID runId, int stepIndex, String toolCallId);

    /** Reserves the single invocation in the same transaction as its business mutation. */
    boolean insertExecuting(UUID id, UUID projectId, UUID runId, int stepIndex,
            String toolCallId, String toolName, String argumentHash, Instant now);

    /** Stores a result only once; a failed business transaction rolls this row back too. */
    boolean complete(UUID id, JsonNode result, Instant now);
}
