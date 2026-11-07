package dev.agenvas.canvas.application;

import static dev.agenvas.canvas.application.CanvasEditModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.canvas.infrastructure.CanvasCharacterNodeRepository;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanvasSubgraphCopySupportTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-07T00:00:00Z");
    private final CanvasConnectionRepository edges = mock(CanvasConnectionRepository.class);
    private final CanvasCharacterNodeRepository characters = mock(CanvasCharacterNodeRepository.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final CanvasSubgraphCopySupport support = new CanvasSubgraphCopySupport(edges, characters,
            mock(ProjectService.class), artifacts, Clock.fixed(now, ZoneOffset.UTC));

    @Test void selectionCopiesOnlyInternalRelationshipsAndRejectsStaleOrForeignTopology() {
        var internal = edge(a, b, CanvasConnection.RelationType.PROMPT_INPUT);
        var external = edge(a, UUID.randomUUID(), CanvasConnection.RelationType.PROMPT_INPUT);
        when(edges.list(owner, project)).thenReturn(List.of(internal, external));
        when(characters.connections(project)).thenReturn(List.of());
        UUID target = UUID.randomUUID();
        var request = new ConnectionCopy(ConnectionKind.ARTIFACT, internal.id(), target, 0);
        var prepared = support.prepare(owner, project, List.of(copy(a), copy(b)), List.of(request));
        assertThat(prepared.source().connections()).containsExactly(internal);
        assertThat(prepared.edgeIds()).containsExactlyEntriesOf(Map.of(internal.id(), target));
        for (var invalid : List.of(new ConnectionCopy(ConnectionKind.ARTIFACT, external.id(), target, 0),
                new ConnectionCopy(ConnectionKind.ARTIFACT, internal.id(), target, 1),
                new ConnectionCopy(ConnectionKind.CHARACTER, internal.id(), target, 0),
                new ConnectionCopy(ConnectionKind.ARTIFACT, internal.id(), external.id(), 0))) {
            assertThatThrownBy(() -> support.prepare(owner, project, List.of(copy(a), copy(b)), List.of(invalid)))
                    .isInstanceOf(ApiProblemException.class);
        }
        assertThatThrownBy(() -> support.prepare(owner, project, List.of(copy(a), copy(b)), List.of()))
                .isInstanceOf(ApiProblemException.class);
        assertThat(support.prepare(owner, project, List.of(copy(a)), List.of()).source().connections()).isEmpty();
    }

    @Test void promptInputsUseIndependentTextVersionsWhileMediaLineageKeepsExactVersionsAndOrder() {
        var prompt = edge(a, b, CanvasConnection.RelationType.PROMPT_INPUT);
        var media = edge(b, a, CanvasConnection.RelationType.MEDIA_DERIVATION);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), newVersion = UUID.randomUUID();
        CanvasItem source = item(first), target = item(second);
        ArtifactVersion version = mock(ArtifactVersion.class);
        when(version.id()).thenReturn(newVersion);
        when(artifacts.get(owner, project, source.subjectId())).thenReturn(new ArtifactService.ArtifactView(mock(Artifact.class), version));
        var prepared = new CanvasSubgraphCopySupport.Prepared(new CanvasSubgraphCopySupport.Graph(List.of(prompt, media), List.of()),
                Map.of(prompt.id(), UUID.randomUUID(), media.id(), UUID.randomUUID()));
        var graph = support.remap(owner, project, prepared, Map.of(a, source, b, target));
        assertThat(graph.connections()).hasSize(2);
        assertThat(graph.connections().getFirst().sourceCanvasItemId()).isEqualTo(first);
        assertThat(graph.connections().getFirst().targetCanvasItemId()).isEqualTo(second);
        assertThat(graph.connections().getFirst().sourceArtifactVersionId()).isEqualTo(newVersion);
        assertThat(graph.connections().getLast().sourceArtifactVersionId()).isEqualTo(media.sourceArtifactVersionId());
        assertThat(graph.connections().getFirst().createdAt()).isBefore(graph.connections().getLast().createdAt());
        assertThat(support.nextIncarnation(graph).connections()).allSatisfy(edge -> assertThat(edge.version()).isEqualTo(1));
    }

    private Copy copy(UUID id) { return new Copy(id, UUID.randomUUID(), 0, null, null, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.valueOf(320), BigDecimal.valueOf(240), 0, null); }
    private CanvasItem item(UUID id) { return new CanvasItem(id, project, CanvasItem.SubjectType.ARTIFACT, UUID.randomUUID(), null,
            "Synthetic", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(320), BigDecimal.valueOf(240), 0, null, false, 0, now, now); }
    private CanvasConnection edge(UUID source, UUID target, CanvasConnection.RelationType kind) {
        return new CanvasConnection(UUID.randomUUID(), project, source, target, kind, UUID.randomUUID(), 0, now, now);
    }
}
