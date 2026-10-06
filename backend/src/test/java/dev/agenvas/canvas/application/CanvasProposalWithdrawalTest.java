package dev.agenvas.canvas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** Proposal withdrawal must preserve edited drafts and every selected or unselected result. */
class CanvasProposalWithdrawalTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final CanvasItemRepository items = mock(CanvasItemRepository.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final CanvasConnectionService connections = mock(CanvasConnectionService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final CanvasService canvas = new CanvasService(mock(ProjectService.class),
            mock(ArtifactService.class), drafts, mock(AgentInstanceService.class), items,
            connections, events, new JsonMapper(), Clock.systemUTC());

    private void current(UUID selectedVersionId, long draftVersion) {
        Instant now = Instant.now();
        when(items.findForUpdate(owner, project, itemId)).thenReturn(Optional.of(new CanvasItem(
                itemId, project, CanvasItem.SubjectType.ARTIFACT, artifactId, selectedVersionId,
                "Proposal", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300"),
                new BigDecimal("300"), 0, null, false, 0, now, now)));
        MediaDraft draft = mock(MediaDraft.class);
        when(draft.version()).thenReturn(draftVersion);
        when(drafts.get(owner, project, itemId)).thenReturn(draft);
    }

    @Test
    void unchangedEmptyCardIsRemovedWithItsConnectionsAndRefreshEvent() {
        current(null, 1);
        when(items.delete(owner, project, itemId, 0)).thenReturn(true);

        assertThat(canvas.removeUnchangedEmptyMediaItemWithinChange(owner, project,
                itemId, artifactId, 1)).isTrue();

        verify(connections).removeItemConnectionsWithinChange(owner, project, itemId);
        verify(items).delete(owner, project, itemId, 0);
        var event = ArgumentCaptor.forClass(ProjectEventService.EventDraft.class);
        verify(events).append(org.mockito.ArgumentMatchers.eq(owner),
                org.mockito.ArgumentMatchers.eq(project), event.capture());
        assertThat(event.getValue().type()).isEqualTo("canvas.items.changed");
        assertThat(event.getValue().payload().path("itemIds").get(0).asText())
                .isEqualTo(itemId.toString());
    }

    @Test
    void editedDraftSurvivesWithdrawal() {
        current(null, 2);
        assertPreserved(artifactId);
    }

    @Test
    void selectedResultSurvivesWithdrawal() {
        current(UUID.randomUUID(), 1);
        assertPreserved(artifactId);
    }

    @Test
    void unselectedArchivedResultSurvivesWithdrawal() {
        current(null, 1);
        when(items.mediaVersionIds(owner, project, itemId)).thenReturn(List.of(UUID.randomUUID()));
        assertPreserved(artifactId);
    }

    @Test
    void unrelatedArtifactIsNeverRemoved() {
        current(null, 1);
        assertPreserved(UUID.randomUUID());
    }

    @Test
    void alreadyRemovedCardIsAnIdempotentNoOp() {
        when(items.findForUpdate(owner, project, itemId)).thenReturn(Optional.empty());
        assertPreserved(artifactId);
    }

    private void assertPreserved(UUID requestedArtifactId) {
        assertThat(canvas.removeUnchangedEmptyMediaItemWithinChange(owner, project,
                itemId, requestedArtifactId, 1)).isFalse();
        verify(items, never()).delete(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(connections, events);
    }
}
