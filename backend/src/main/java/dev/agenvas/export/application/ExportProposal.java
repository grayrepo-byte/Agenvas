package dev.agenvas.export.application;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Immutable Agent export input with a separate human decision lifecycle. */
public record ExportProposal(UUID id, UUID projectId, UUID runId, Status status,
        JsonNode input, JsonNode inputPins, String proposalHash, long projectVersion,
        UUID approvedTaskId, UUID decidedByUserId, Instant createdAt, Instant decidedAt) {

    /** Only authenticated approval moves a proposal into an executable state. */
    public enum Status { PENDING, APPROVED, REJECTED }
}
