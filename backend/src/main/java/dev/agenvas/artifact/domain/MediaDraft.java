package dev.agenvas.artifact.domain;

import java.time.Instant;
import java.util.UUID;

/** A persisted working draft, independent of the selected immutable media result. */
public record MediaDraft(
        UUID projectId,
        UUID artifactId,
        String prompt,
        UUID inputImageVersionId,
        Integer durationSeconds,
        UUID capabilityId,
        DisplayMode displayMode,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public enum DisplayMode { DRAFT, RESULT }
}
