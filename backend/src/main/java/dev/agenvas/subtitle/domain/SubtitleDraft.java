package dev.agenvas.subtitle.domain;

import java.time.Instant;
import java.util.UUID;

/** Mutable editing draft with CAS; source versions and completed recognition Task outputs stay immutable. */
public record SubtitleDraft(UUID id, UUID projectId, SubtitleTrack track, long version, UUID taskId, Instant updatedAt) {}
