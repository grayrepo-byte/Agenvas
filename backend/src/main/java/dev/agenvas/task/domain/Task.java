package dev.agenvas.task.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One durable execution unit with an optional short-lived fenced worker lease. */
public record Task(
        UUID id,
        UUID projectId,
        UUID runId,
        UUID planId,
        String stepKey,
        Kind kind,
        Status status,
        boolean cancelRequested,
        JsonNode input,
        String inputHash,
        JsonNode output,
        UUID providerId,
        String providerRequestId,
        int attemptNo,
        Instant nextActionAt,
        String leaseOwner,
        Instant leaseUntil,
        long leaseEpoch,
        long version,
        String errorCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    /** Bounded P0 work categories. */
    public enum Kind {
        AGENT_TURN,
        IMAGE_GENERATION,
        VIDEO_GENERATION,
        MEDIA_EXPORT,
        ASSET_INGEST
    }

    /** Durable scheduling and external-submission states. */
    public enum Status {
        PENDING,
        READY,
        RUNNING,
        SUBMITTING,
        WAITING_PROVIDER,
        UNKNOWN,
        BLOCKED,
        SUCCEEDED,
        FAILED,
        CANCELED
    }
}
