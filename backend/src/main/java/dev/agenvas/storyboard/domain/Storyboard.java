package dev.agenvas.storyboard.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Immutable board projection. Server-owned shot revisions protect review provenance. */
public record Storyboard(UUID projectId, long version, List<Shot> shots, Composition composition) {
    public static Storyboard empty(UUID projectId) { return new Storyboard(projectId, 0, List.of(), null); }

    public enum Role { KEYFRAME, VIDEO, AUDIO }

    public record Shot(UUID id, String code, String scene, String title, String description,
            String dialogue, BigDecimal durationSeconds, UUID sourceVersionId,
            List<UUID> referenceVersionIds, int revision, List<Production> productions) {}

    /** A selected result stays pinned when creative inputs change. Review never starts generation. */
    public record Production(Role role, UUID canvasItemId, UUID selectedVersionId,
            int reviewedRevision, boolean needsReview) {}
    public record Composition(UUID canvasItemId, UUID selectedVersionId, boolean needsReview) {}
}
