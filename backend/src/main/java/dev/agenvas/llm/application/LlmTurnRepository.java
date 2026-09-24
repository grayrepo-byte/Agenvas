package dev.agenvas.llm.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Persistence boundary for unique Run/step model request and response checkpoints. */
public interface LlmTurnRepository {

    /** Inserts a request once, returning false when this step already exists. */
    boolean insertRequested(UUID projectId, UUID runId, int stepIndex,
            int modelConfigVersion, JsonNode request, Instant now);

    /** Reads one step from the authenticated Run's project. */
    Optional<LlmTurn> find(UUID projectId, UUID runId, int stepIndex);

    /** Stores the full response once and returns false when another response already won. */
    boolean saveResponse(UUID projectId, UUID runId, int stepIndex,
            JsonNode response, Instant now);
}
