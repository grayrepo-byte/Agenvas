package dev.agenvas.storyboard.application;

import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.storyboard.domain.Storyboard;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only saved, explicit media selections grant read access; creative source/reference IDs do not. */
@Service
public class StoryboardSelectedMediaReadAccessService {
    private final StoryboardRepository boards;
    private final CanvasItemQueryService items;

    public StoryboardSelectedMediaReadAccessService(StoryboardRepository boards, CanvasItemQueryService items) {
        this.boards = boards;
        this.items = items;
    }

    /** The caller has resolved the owned immutable version; its exact target must still exist on this project's canvas. */
    @Transactional(readOnly = true)
    public boolean allows(UUID ownerId, UUID projectId, UUID artifactId, UUID versionId) {
        Storyboard board = boards.current(projectId).orElse(null);
        if (board == null) return false;
        List<UUID> currentItems = items.artifactItems(ownerId, projectId, artifactId).stream().map(CanvasItem::id).toList();
        if (board.composition() != null && versionId.equals(board.composition().selectedVersionId())
                && currentItems.contains(board.composition().canvasItemId())) return true;
        return board.shots().stream().flatMap(shot -> shot.productions().stream())
                .anyMatch(production -> versionId.equals(production.selectedVersionId())
                        && currentItems.contains(production.canvasItemId()));
    }

    /** Undo must preserve a card used by any saved board, even with no selected result yet. */
    @Transactional(readOnly = true)
    public boolean hasCanvasReference(UUID ownerId, UUID projectId, UUID canvasItemId) {
        items.requireArtifactItem(ownerId, projectId, canvasItemId);
        return boards.hasCanvasReference(projectId, canvasItemId);
    }
}
