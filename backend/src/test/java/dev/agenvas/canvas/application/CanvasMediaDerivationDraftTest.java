package dev.agenvas.canvas.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CanvasMediaDerivationDraftTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final UUID parent = UUID.randomUUID();
    private final CanvasItemRepository items = mock(CanvasItemRepository.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final CanvasConnectionService connections = mock(CanvasConnectionService.class);
    private final CanvasService canvas = new CanvasService(mock(ProjectService.class), artifacts,
            drafts, mock(AgentInstanceService.class), items, connections,
            mock(ProjectEventService.class), new ObjectMapper(), Clock.systemUTC());

    @BeforeEach
    void source() {
        Instant now = Instant.now();
        CanvasItem item = new CanvasItem(source, project, CanvasItem.SubjectType.ARTIFACT,
                artifactId, parent, "Source", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("280"), new BigDecimal("240"), 0, null, false, 0, now, now);
        when(items.findForUpdate(owner, project, source)).thenReturn(Optional.of(item));
        when(items.list(owner, project)).thenReturn(List.of(item));
        when(items.create(any())).thenReturn(true);
        ArtifactService.ArtifactView view = mock(ArtifactService.ArtifactView.class);
        Artifact artifact = mock(Artifact.class);
        when(artifact.kind()).thenReturn(Artifact.Kind.IMAGE);
        when(view.artifact()).thenReturn(artifact);
        when(artifacts.get(owner, project, artifactId)).thenReturn(view);
        MediaDraft draft = mock(MediaDraft.class);
        when(draft.version()).thenReturn(5L);
        when(drafts.get(owner, project, source)).thenReturn(draft);
    }

    @Test
    void editingCreatesAFreshDraftAndKeepsOnlyTheLineageReference() {
        canvas.forkMediaDerivationWithinChange(owner, project, source, target, 5, 0);
        verify(drafts).initializeWithinChange(project, target, false);
        verify(drafts, never()).duplicateWithinChange(any(), any(), any(), any(), anyLong());
        verify(connections).createMediaDerivationWithinChange(owner, project, source, target, parent);
    }

    @Test
    void additionalBatchOutputsStillCopyTheDraftThatTheirGenerationUses() {
        canvas.forkMediaOutputWithinChange(owner, project, source, target, 5, 1);
        verify(drafts).duplicateWithinChange(owner, project, source, target, 5);
        verify(drafts, never()).initializeWithinChange(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void staleSourceDraftDoesNotCreateAPartialDerivation() {
        assertThatThrownBy(() -> canvas.forkMediaDerivationWithinChange(owner, project,
                source, target, 4, 0)).isInstanceOf(ApiProblemException.class);
        verify(items, never()).create(any());
        verify(drafts, never()).initializeWithinChange(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
