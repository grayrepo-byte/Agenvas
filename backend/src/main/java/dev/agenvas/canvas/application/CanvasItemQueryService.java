package dev.agenvas.canvas.application;

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

    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "画布卡片不存在", "画布卡片不存在或当前用户无权访问。", false);
    }
}
