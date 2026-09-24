package dev.agenvas.llm.application;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A durable tool result keyed by the original model-provided call ID. */
public record ToolExecution(UUID id, UUID projectId, UUID runId, int stepIndex,
        String toolCallId, String toolName, String argumentHash, Status status,
        JsonNode result) {

    /** EXECUTING is transaction-local; only COMPLETED should survive a commit. */
    public enum Status { EXECUTING, COMPLETED }
}
