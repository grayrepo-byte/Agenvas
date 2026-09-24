package dev.agenvas.artifact.domain;

import java.time.Instant;
import java.util.UUID;

/** Stable identity for a versioned creative result within one project. */
public record Artifact(
        UUID id,
        UUID projectId,
        Kind kind,
        String title,
        UUID currentVersionId,
        Instant archivedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** The six content families supported by the MVP. */
    public enum Kind {
        TEXT,
        IMAGE,
        VIDEO,
        CHARACTER,
        SCENE,
        SHOT
    }
}
