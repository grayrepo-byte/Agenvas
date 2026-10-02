package dev.agenvas.mediatemplate.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A prompt preset with ordered independently archived image snapshots. */
public record MediaTemplate(UUID id, UUID ownerId, Scope scope, TargetKind targetKind,
        String name, String prompt, List<UUID> imageIds, long version, Instant createdAt, Instant updatedAt) {
    public enum Scope { PERSONAL, SYSTEM }
    public enum TargetKind { IMAGE, VIDEO }
    public MediaTemplate { imageIds = List.copyOf(imageIds); }
}
