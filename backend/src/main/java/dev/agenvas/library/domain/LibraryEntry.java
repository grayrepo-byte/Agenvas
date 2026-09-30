package dev.agenvas.library.domain;

import dev.agenvas.artifact.domain.Artifact;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Immutable content with independently editable, account-owned catalogue metadata. */
public record LibraryEntry(UUID id, UUID ownerId, String name, Category category, Artifact.Kind kind,
        JsonNode textContent, UUID fileId, UUID sourceVersionId, JsonNode source, boolean favorite,
        Instant trashedAt, long version, Instant createdAt, Instant updatedAt) {
    public enum Category { CHARACTER, SCENE, PROP, OTHER }
    public enum Sort { SAVED, NAME, UPDATED }
}
