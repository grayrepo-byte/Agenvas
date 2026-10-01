package dev.agenvas.library.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A recoverable local transfer; generation and external submission are deliberately absent. */
public record LibraryCommand(UUID id, UUID ownerId, String commandKey, String payloadHash,
        Kind kind, JsonNode input, Status status, long epoch, Instant leaseUntil,
        JsonNode result, String errorCode, String errorDetail, Instant createdAt, Instant updatedAt) {
    public enum Kind { SAVE, UPLOAD, IMPORT, REFERENCE }
    public enum Status { ACCEPTED, ARCHIVING, SUCCEEDED, FAILED }
}
