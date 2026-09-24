package dev.agenvas.run.application;

import java.time.Instant;
import java.util.UUID;

/** Run-owned boundary for atomically creating its first durable model-turn Task. */
public interface RunTaskCreation {

    /** Creates the unique step-zero Task inside the Run creation transaction. */
    UUID createInitialTurn(UUID projectId, UUID runId, Instant now);
}
