package dev.agenvas.canvas.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Persisted spatial presentation of an Artifact or AgentInstance. */
public record CanvasItem(
        UUID id,
        UUID projectId,
        SubjectType subjectType,
        UUID subjectId,
        BigDecimal x,
        BigDecimal y,
        BigDecimal width,
        BigDecimal height,
        int zIndex,
        UUID groupId,
        boolean locked,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** Business object categories that can be projected onto the canvas. */
    public enum SubjectType {
        ARTIFACT,
        AGENT
    }
}
