package dev.agenvas.canvas.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read boundary for modules that need a trusted CanvasItem identity without using its repository. */
@Service
public class CanvasItemQueryService {
    private final CanvasItemRepository canvasItems;

    public CanvasItemQueryService(CanvasItemRepository canvasItems) {
        this.canvasItems = canvasItems;
    }

    /** Resolves one owned Artifact card while hiding the canvas persistence boundary. */
    @Transactional(readOnly = true)
    public CanvasItem requireArtifactItem(UUID ownerId, UUID projectId, UUID canvasItemId) {
        CanvasItem item = canvasItems.find(ownerId, projectId, canvasItemId)
                .orElseThrow(this::notFound);
        if (item.subjectType() != CanvasItem.SubjectType.ARTIFACT) throw notFound();
        return item;
    }

    /** Resolves the sole canvas representation of one owned Agent instance. */
    @Transactional(readOnly = true)
    public CanvasItem requireAgentItem(UUID ownerId, UUID projectId, UUID canvasItemId) {
        CanvasItem item = canvasItems.find(ownerId, projectId, canvasItemId)
                .orElseThrow(this::notFound);
        if (item.subjectType() != CanvasItem.SubjectType.AGENT) throw notFound();
        return item;
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                ApiMessage.of("api.creative-artifact-tool-service.canvas-card-does-not-exist"), ApiMessage.of("api.canvas-service.the-canvas-card-does-not-exist-or-the-current-user"), false);
    }
}
