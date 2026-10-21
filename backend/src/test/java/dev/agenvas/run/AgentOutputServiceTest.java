package dev.agenvas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.run.application.AgentOutputService;
import dev.agenvas.run.application.AgentRunRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Attribution is selected-version provenance, independent from reusable canvas layout groups. */
class AgentOutputServiceTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), agent = UUID.randomUUID();
    private final UUID run = UUID.randomUUID(), otherRun = UUID.randomUUID();
    private final AgentInstanceService agents = mock(AgentInstanceService.class);
    private final CanvasService canvas = mock(CanvasService.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentOutputService service = new AgentOutputService(agents, canvas, runs);

    @Test void currentTextAndSelectedMediaUseTheirOwnRunsRegardlessOfResourceDefaultAndGroup() {
        var text = entry(Artifact.Kind.TEXT, run, null, ArtifactVersion.CreatedByKind.AGENT);
        var media = entry(Artifact.Kind.IMAGE, otherRun, run, ArtifactVersion.CreatedByKind.TASK);
        var other = entry(Artifact.Kind.VIDEO, run, otherRun, ArtifactVersion.CreatedByKind.TASK);
        when(canvas.list(owner, project)).thenReturn(List.of(text, media, other));
        when(runs.filterAgentRunIds(owner, project, agent, Set.of(run, otherRun))).thenReturn(Set.of(run));
        assertThat(service.listCanvasItemIds(owner, project, agent))
                .containsExactly(text.item().id(), media.item().id());
        verify(agents).get(owner, project, agent);
    }

    @Test void emptyDraftsAndHumanVersionsAreNotGeneratedOutputsEvenWithAnOldRunIdentifier() {
        var human = entry(Artifact.Kind.TEXT, run, null, ArtifactVersion.CreatedByKind.USER);
        var empty = entry(Artifact.Kind.IMAGE, run, null, ArtifactVersion.CreatedByKind.TASK);
        when(canvas.list(owner, project)).thenReturn(List.of(human, empty));
        when(runs.filterAgentRunIds(owner, project, agent, Set.of())).thenReturn(Set.of());
        assertThat(service.listCanvasItemIds(owner, project, agent)).isEmpty();
    }

    private CanvasService.CanvasEntry entry(Artifact.Kind kind, UUID defaultRun, UUID selectedRun,
            ArtifactVersion.CreatedByKind author) {
        UUID artifact = UUID.randomUUID();
        ArtifactVersion current = version(artifact, defaultRun, author);
        ArtifactVersion selected = selectedRun == null ? null : version(artifact, selectedRun, author);
        Instant now = Instant.now();
        var item = new CanvasItem(UUID.randomUUID(), project, CanvasItem.SubjectType.ARTIFACT, artifact,
                selected == null ? null : selected.id(), "Current output", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(300), BigDecimal.valueOf(240), 0, UUID.randomUUID(), false, 0, now, now);
        return new CanvasService.CanvasEntry(item, new ArtifactService.ArtifactView(new Artifact(artifact,
                project, kind, "Resource", current.id(), null, 0, now, now), current), selected);
    }

    private ArtifactVersion version(UUID artifact, UUID sourceRun, ArtifactVersion.CreatedByKind author) {
        return new ArtifactVersion(UUID.randomUUID(), project, artifact, 1, 1, null, null,
                new ObjectMapper().createObjectNode(), List.of(), author, sourceRun, Instant.now());
    }
}
