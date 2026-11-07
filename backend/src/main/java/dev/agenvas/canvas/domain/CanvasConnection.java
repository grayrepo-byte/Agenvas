package dev.agenvas.canvas.domain;

import java.time.Instant;
import java.util.UUID;

/** Persistent topology edge with the source version captured at creation; prompt inputs resolve saved text at acceptance. */
public record CanvasConnection(UUID id, UUID projectId, UUID sourceCanvasItemId,
        UUID targetCanvasItemId, RelationType relationType, UUID sourceArtifactVersionId,
        long version, Instant createdAt, Instant updatedAt) {
    public enum RelationType { MEDIA_INPUT, MEDIA_DERIVATION, PROMPT_INPUT }
}
