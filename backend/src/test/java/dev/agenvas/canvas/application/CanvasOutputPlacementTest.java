package dev.agenvas.canvas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Exercises output placement through business entry points, including established failure precedence. */
class CanvasOutputPlacementTest {
    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final UUID parent = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final CanvasItemRepository items = mock(CanvasItemRepository.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final AgentInstanceService agents = mock(AgentInstanceService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CanvasService canvas = new CanvasService(mock(ProjectService.class), artifacts,
            drafts, agents, items, mock(CanvasConnectionService.class),
            mock(ProjectEventService.class), mapper, Clock.fixed(NOW, java.time.ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        when(items.create(any())).thenReturn(true);
        when(artifacts.get(owner, project, artifactId)).thenReturn(artifactView(artifactId, Artifact.Kind.IMAGE));
        when(agents.get(owner, project, agentId)).thenReturn(new AgentInstance(agentId, project,
                "test", 1, "Agent", "", groupId, 0, NOW, NOW, List.of()));
    }

    @Test
    void mediaOutputsKeepTheirSourceSizeAndEstablishedCollisionFootprint() {
        CanvasItem source = source("0", "600", "200");
        // The existing media placement rule reserves 300x300, even for a wider source card.
        withSource(source, List.of(source, artifactCard("950", "0", "120", "80", 0)));

        CanvasItem output = canvas.forkMediaOutputWithinChange(owner, project, sourceId,
                UUID.randomUUID(), 0, 0);

        assertThat(output.x()).isEqualByComparingTo("624");
        assertThat(output.y()).isEqualByComparingTo("0");
        assertThat(output.width()).isEqualByComparingTo("600");
        assertThat(output.height()).isEqualByComparingTo("200");
    }

    @Test
    void mediaOutputsMoveBelowCollisionsAndSaturateTheirLayer() {
        CanvasItem source = source("0", "600", "200");
        withSource(source, List.of(source, artifactCard("624", "0", "300", "80", 1000)));

        CanvasItem output = canvas.forkMediaOutputWithinChange(owner, project, sourceId,
                UUID.randomUUID(), 0, 0);

        assertThat(output.x()).isEqualByComparingTo("624");
        assertThat(output.y()).isEqualByComparingTo("224");
        assertThat(output.zIndex()).isEqualTo(1000);
    }

    @Test
    void audioOutputsUseTheirOwnCollisionSizeAndVerticalStep() {
        CanvasItem source = source("0", "600", "200");
        withSource(source, List.of(source, artifactCard("974", "0", "120", "80", 0)));
        when(artifacts.get(owner, project, artifactId)).thenReturn(artifactView(artifactId, Artifact.Kind.VIDEO));
        UUID audioArtifactId = UUID.randomUUID();
        when(artifacts.create(owner, project, Artifact.Kind.AUDIO, "Source · 音频", null))
                .thenReturn(artifactView(audioArtifactId, Artifact.Kind.AUDIO));

        CanvasItem output = canvas.forkAudioDerivationWithinChange(owner, project, sourceId, "音频");

        assertThat(output.x()).isEqualByComparingTo("624");
        assertThat(output.y()).isEqualByComparingTo("264");
        assertThat(output.width()).isEqualByComparingTo("430");
        assertThat(output.height()).isEqualByComparingTo("240");
    }

    @Test
    void agentOutputsFallBackFromTheUpperBoundaryAndKeepTheOutputGroup() {
        CanvasItem agent = new CanvasItem(UUID.randomUUID(), project, CanvasItem.SubjectType.AGENT,
                agentId, null, "Agent", decimal("999800"), decimal("999800"),
                decimal("300"), decimal("300"), 1000, null, true, 0, NOW, NOW);
        when(items.list(owner, project)).thenReturn(List.of(agent));

        CanvasItem output = canvas.placeGeneratedArtifactWithinChange(owner, project, agentId, artifactId);

        assertThat(output.x()).isEqualByComparingTo("80");
        assertThat(output.y()).isEqualByComparingTo("80");
        assertThat(output.zIndex()).isEqualTo(1000);
        assertThat(output.groupId()).isEqualTo(groupId);
    }

    @Test
    void agentOutputsSearchColumnsBeforeAdvancingToTheNextRow() {
        when(items.list(owner, project)).thenReturn(List.of(
                artifactCard("80", "80", "300", "300", 0),
                artifactCard("404", "80", "300", "300", 0),
                artifactCard("728", "80", "300", "300", 0)));

        CanvasItem output = canvas.placeGeneratedArtifactWithinChange(owner, project, agentId, artifactId);

        assertThat(output.x()).isEqualByComparingTo("80");
        assertThat(output.y()).isEqualByComparingTo("404");
    }

    @Test
    void agentOutputsRejectAnExhaustedGridWithoutCreatingAPartialCard() {
        List<CanvasItem> existing = new ArrayList<>();
        for (int row = 0; row < 100; row++) {
            for (int column = 0; column < 3; column++) {
                existing.add(artifactCard(Integer.toString(80 + 324 * column),
                        Integer.toString(80 + 324 * row), "300", "300", 0));
            }
        }
        when(items.list(owner, project)).thenReturn(existing);

        assertThatThrownBy(() -> canvas.placeGeneratedArtifactWithinChange(owner, project, agentId, artifactId))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.detail().key())
                        .isEqualTo("api.canvas-service.there-is-no-canvas-location-available-in-the-agent-output"));
        verify(items, never()).create(any());
    }

    @Test
    void taskAndBranchOutputsPreserveTheirDifferentFailurePrecedence() {
        CanvasItem source = source("989680", "160", "80");
        List<CanvasItem> existing = new ArrayList<>(List.of(source));
        for (int offset = 0; offset <= 10_000; offset += 2000) {
            existing.add(artifactCard("184", Integer.toString(989680 + offset), "300", "2000", 0));
        }
        withSource(source, existing);

        assertThatThrownBy(() -> canvas.forkMediaOutputWithinChange(owner, project, sourceId,
                UUID.randomUUID(), 0, 0))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.detail().key())
                        .isEqualTo("api.canvas-service.there-is-no-available-location-near-the-new-image-node"));
        assertThatThrownBy(() -> canvas.placeTaskMediaOutputWithinChange(owner, project, sourceId,
                artifactId, 1, mapper.createObjectNode()))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.detail().key())
                        .isEqualTo("api.canvas-service.canvas-coordinates-dimensions-or-levels-are-outside-the-allowed-range"));
        verify(items, never()).create(any());
    }

    @Test
    void nativeSeventeenthResultKeepsRefactoredPlacementWhileTheNextIndexIsRejected() {
        CanvasItem source = source("0", "600", "200");
        withSource(source, List.of(source));

        CanvasItem output = canvas.placeTaskMediaOutputWithinChange(owner, project, sourceId,
                artifactId, 16, mapper.createObjectNode());

        assertThat(output.title()).isEqualTo("Source · 17");
        assertThat(output.x()).isEqualByComparingTo("624");
        assertThat(output.y()).isEqualByComparingTo("0");
        assertThat(output.width()).isEqualByComparingTo("600");
        assertThat(output.height()).isEqualByComparingTo("200");
        assertThatThrownBy(() -> canvas.placeTaskMediaOutputWithinChange(owner, project, sourceId,
                artifactId, 17, mapper.createObjectNode()))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
        verify(items, times(1)).create(any());
    }

    private void withSource(CanvasItem source, List<CanvasItem> existing) {
        when(items.findForUpdate(owner, project, sourceId)).thenReturn(Optional.of(source));
        when(items.list(owner, project)).thenReturn(existing);
    }

    private CanvasItem source(String y, String width, String height) {
        return new CanvasItem(sourceId, project, CanvasItem.SubjectType.ARTIFACT, artifactId,
                parent, "Source", BigDecimal.ZERO, decimal(y), decimal(width), decimal(height),
                0, null, false, 0, NOW, NOW);
    }

    private CanvasItem artifactCard(String x, String y, String width, String height, int zIndex) {
        return new CanvasItem(UUID.randomUUID(), project, CanvasItem.SubjectType.ARTIFACT,
                UUID.randomUUID(), null, "Existing", decimal(x), decimal(y), decimal(width),
                decimal(height), zIndex, null, false, 0, NOW, NOW);
    }

    private ArtifactService.ArtifactView artifactView(UUID id, Artifact.Kind kind) {
        return new ArtifactService.ArtifactView(new Artifact(id, project, kind, "Result",
                null, null, 0, NOW, NOW), null);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
