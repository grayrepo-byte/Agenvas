package dev.agenvas.canvas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Layout revisions and explicit content selections have different late-result semantics. */
class CanvasTaskResultSelectionTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID artifact = UUID.randomUUID();
    private final UUID parent = UUID.randomUUID();
    private final UUID result = UUID.randomUUID();
    private final CanvasItemRepository items = mock(CanvasItemRepository.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final CanvasService canvas = new CanvasService(mock(ProjectService.class),
            mock(ArtifactService.class), drafts, mock(AgentInstanceService.class), items,
            mock(CanvasConnectionService.class), mock(ProjectEventService.class),
            new ObjectMapper(), Clock.systemUTC());

    private void current(long epoch, long draftVersion) {
        Instant now = Instant.now();
        // A high layout CAS version deliberately differs from the content selection epoch.
        when(items.findForUpdate(owner, project, itemId)).thenReturn(Optional.of(new CanvasItem(
                itemId, project, CanvasItem.SubjectType.ARTIFACT, artifact, parent,
                "Image", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("280"),
                new BigDecimal("240"), 0, null, false, 20, now, now)));
        when(items.mediaSelectionEpoch(owner, project, itemId)).thenReturn(epoch);
        MediaDraft draft = mock(MediaDraft.class);
        when(draft.version()).thenReturn(draftVersion);
        when(drafts.get(owner, project, itemId)).thenReturn(draft);
    }

    @Test
    void draggingDoesNotPreventSelectingTheGeneratedResult() {
        current(2, 4);
        when(items.selectVersion(any(), any(), any(), anyLong(), any(), any())).thenReturn(true);
        assertThat(canvas.selectTaskResultWithinChange(owner, project, itemId, artifact,
                parent, result, 2L, 4L)).isTrue();
        verify(items).selectVersion(any(), any(), any(), org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.eq(result), any());
    }

    @Test
    void switchingAwayAndBackToTheSameParentPreventsAutomaticSelection() {
        current(4, 4);
        assertThat(canvas.selectTaskResultWithinChange(owner, project, itemId, artifact,
                parent, result, 2L, 4L)).isFalse();
        verify(items, never()).selectVersion(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void editingTheDraftPreventsAutomaticSelectionEvenWithTheSameParent() {
        current(2, 5);
        assertThat(canvas.selectTaskResultWithinChange(owner, project, itemId, artifact,
                parent, result, 2L, 4L)).isFalse();
        verify(items, never()).selectVersion(any(), any(), any(), anyLong(), any(), any());
    }
}
