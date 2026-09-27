package dev.agenvas.support;

import dev.agenvas.canvas.application.CanvasService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Places one media resource through the production canvas application service for integration tests. */
public final class CanvasMediaFixture {
    private CanvasMediaFixture() {}

    public static UUID place(CanvasService canvas, UUID ownerId, UUID projectId,
            UUID artifactId) {
        UUID itemId = UUID.randomUUID();
        canvas.apply(ownerId, projectId, List.of(new CanvasService.PlaceArtifact(
                itemId, artifactId, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("280"), new BigDecimal("240"), 0, null, false)));
        return itemId;
    }
}
