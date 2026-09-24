package dev.agenvas.llm.application;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One durable model round whose full response precedes every tool execution. */
public record LlmTurn(UUID projectId, UUID runId, int stepIndex, Status status,
        int modelConfigVersion, JsonNode request, JsonNode response,
        Instant createdAt, Instant respondedAt) {

    /** REQUESTED may be reissued after a crash; RESPONDED is never reissued. */
    public enum Status { REQUESTED, RESPONDED }
}
