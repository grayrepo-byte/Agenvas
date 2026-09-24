package dev.agenvas.task.domain;

import java.time.Instant;
import java.util.UUID;

/** Durable pre-network submission checkpoint and its externally verifiable identifiers. */
public record ProviderAttempt(UUID id, UUID taskId, Status status, UUID requestKey,
        UUID candidateRequestId, String candidateOriginSha256, String providerRequestId,
        Instant createdAt, Instant updatedAt) {

    /** Unknown is not a definite rejection or permission to submit again. */
    public enum Status {
        SUBMITTING,
        ACCEPTED,
        UNKNOWN,
        REJECTED
    }
}
