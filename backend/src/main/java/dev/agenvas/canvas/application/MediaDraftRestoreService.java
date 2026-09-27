package dev.agenvas.canvas.application;

import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.MediaDraft;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates topology cleanup and full draft replacement in one transaction. */
@Service
public class MediaDraftRestoreService {
    private final CanvasConnectionService connections;
    private final MediaDraftService drafts;

    public MediaDraftRestoreService(CanvasConnectionService connections,
            MediaDraftService drafts) {
        this.connections = connections;
        this.drafts = drafts;
    }

    @Transactional
    public MediaDraft restore(UUID ownerId, UUID projectId, UUID canvasItemId,
            UUID versionId, long expectedDraftVersion) {
        long versionAfterCleanup = connections.clearTargetMediaConnectionsWithinChange(
                ownerId, projectId, canvasItemId, expectedDraftVersion);
        return drafts.restoreVersionInputs(ownerId, projectId, canvasItemId, versionId,
                versionAfterCleanup);
    }
}
