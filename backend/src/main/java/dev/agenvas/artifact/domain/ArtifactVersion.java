package dev.agenvas.artifact.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Immutable content revision plus the exact semantic version inputs it read. */
public record ArtifactVersion(
        UUID id,
        UUID projectId,
        UUID artifactId,
        int versionNo,
        int schemaVersion,
        JsonNode content,
        List<InputReference> inputReferences,
        CreatedByKind createdByKind,
        UUID runId,
        Instant createdAt) {

    /** Origin of the immutable revision. */
    public enum CreatedByKind {
        USER,
        AGENT,
        TASK
    }

    /** Typed semantic link to an exact historical input version. */
    public record InputReference(
            UUID versionId,
            String role,
            int order,
            Artifact.Kind expectedKind) {}
}
