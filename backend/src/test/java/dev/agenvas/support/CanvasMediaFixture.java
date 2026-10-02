package dev.agenvas.support;

import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.JsonNodeFactory;

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

    /** Saves the authoritative ordered-input contract from legacy provider integration fixtures. */
    public static MediaDraft save(MediaDraftService drafts, UUID ownerId, UUID projectId,
            UUID canvasItemId, long expectedVersion, String prompt, UUID imageVersionId,
            Integer durationSeconds, UUID capabilityId) {
        boolean video = durationSeconds != null;
        List<MediaDraftService.SaveMediaInput> inputs = imageVersionId == null ? List.of()
                : List.of(new MediaDraftService.SaveMediaInput(imageVersionId,
                        video ? MediaDraft.InputRole.START_FRAME
                                : MediaDraft.InputRole.REFERENCE,
                        "#7C3AED"));
        return drafts.save(ownerId, projectId, canvasItemId, expectedVersion, prompt,
                JsonNodeFactory.instance.objectNode(), durationSeconds, capabilityId,
                video ? MediaDraft.VideoInputMode.START_END : null, inputs, List.of(), null);
    }
}
