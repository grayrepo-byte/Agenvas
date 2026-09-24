package dev.agenvas.plan.application;

import java.time.Instant;
import java.util.UUID;

/** Human-selected immutable keyframe input for one current shot. */
public record ShotKeyframeSelection(UUID projectId, UUID shotArtifactId,
        UUID shotVersionId, UUID imageArtifactId, UUID imageVersionId,
        UUID sourceTaskId, UUID selectedByUserId, long version,
        Instant createdAt, Instant updatedAt) {}
