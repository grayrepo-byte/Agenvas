package dev.agenvas.canvas.domain;

import java.time.Instant;
import java.util.UUID;

/** Persistent topology edge with the exact source image version captured when connected. */
public record CanvasConnection(UUID id, UUID projectId, UUID sourceCanvasItemId,
        UUID targetCanvasItemId, RelationType relationType, UUID sourceArtifactVersionId,
        long version, Instant createdAt, Instant updatedAt) {
    public enum RelationType { MEDIA_INPUT, AGENT_IMAGE_INPUT }
}
