package dev.agenvas.project.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable project state returned by the project application boundary. */
public record Project(
        UUID id,
        UUID ownerId,
        String name,
        AspectRatio aspectRatio,
        Status status,
        long eventSeq,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {

    /** Supported P0 canvas and export aspect ratios. */
    public enum AspectRatio {
        LANDSCAPE_16_9,
        PORTRAIT_9_16,
        SQUARE_1_1
    }

    /** Project lifecycle states. */
    public enum Status {
        ACTIVE,
        ARCHIVED
    }
}
