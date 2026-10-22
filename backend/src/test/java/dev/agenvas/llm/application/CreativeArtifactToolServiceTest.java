package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Media tools use each card's exact selected result independently of the library default. */
class CreativeArtifactToolServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
    private static final long ITEM_VERSION = 7L;
    private static final BigDecimal WIDTH = new BigDecimal("320");
    private static final BigDecimal HEIGHT = new BigDecimal("200");
    private final TrustedToolContext context = new TrustedToolContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final UUID agentId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final CanvasService canvas = mock(CanvasService.class);
    private final JsonMapper mapper = new JsonMapper();
    private final CreativeArtifactToolService service = new CreativeArtifactToolService(
            artifacts, canvas, mapper);
    private final AgentRun run = new AgentRun(context.runId(), context.projectId(), agentId,
            UUID.randomUUID(), 1L, context.ownerId(), AgentRun.Status.RUNNING, "Arrange the result",
            mapper.createObjectNode(),
            mapper.createObjectNode(), 1, 1, 1L, NOW, NOW, null);

    @ParameterizedTest
    @EnumSource(value = Artifact.Kind.class, names = {"IMAGE", "VIDEO", "AUDIO"})
    void arrangesSelectedMediaOutputWithoutALibraryDefault(Artifact.Kind kind) {
        ArtifactService.ArtifactView view = visibleArtifact(kind, null);
        CanvasItem before = item(versionId, groupId, false);
        CanvasItem after = new CanvasItem(itemId, context.projectId(), before.subjectType(),
                artifactId, versionId, before.title(), new BigDecimal("80"), BigDecimal.ZERO,
                WIDTH, HEIGHT, before.zIndex(), groupId, false, ITEM_VERSION + 1, NOW, NOW);
        when(canvas.list(context.ownerId(), context.projectId()))
                .thenReturn(List.of(entry(before, view)));
        when(canvas.apply(eq(context.ownerId()), eq(context.projectId()), any()))
                .thenReturn(List.of(entry(after, view)));

        JsonNode result = arrange();

        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.at("/data/0/itemVersion").asLong()).isEqualTo(ITEM_VERSION + 1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CanvasService.CanvasCommand>> saved = ArgumentCaptor.forClass(List.class);
        verify(canvas).apply(eq(context.ownerId()), eq(context.projectId()), saved.capture());
        CanvasService.UpdateLayout command = (CanvasService.UpdateLayout) saved.getValue().getFirst();
        assertThat(command.expectedVersion()).isEqualTo(ITEM_VERSION);
        assertThat(command.groupId()).isEqualTo(groupId);
    }

    @Test
    void arrangementRejectsAStaleMediaSelectionEvenWhenTheLibraryDefaultMatches() {
        assertArrangementSelectionConflict(UUID.randomUUID());
    }

    @Test
    void arrangementRejectsAnEmptyMediaSelectionEvenWhenTheLibraryDefaultMatches() {
        assertArrangementSelectionConflict(null);
    }

    @Test
    void textArrangementUsesTheCurrentResourceDefault() {
        ArtifactService.ArtifactView view = visibleArtifact(Artifact.Kind.TEXT, version(versionId));
        CanvasItem item = item(null, groupId, false);
        when(canvas.list(context.ownerId(), context.projectId()))
                .thenReturn(List.of(entry(item, view)));

        when(canvas.apply(eq(context.ownerId()), eq(context.projectId()), any()))
                .thenReturn(List.of(entry(item, view)));
        assertThat(arrange().path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void textRejectsAStaleVersionDespiteACardSelectionMatchingIt() {
        ArtifactService.ArtifactView view = visibleArtifact(Artifact.Kind.TEXT,
                version(UUID.randomUUID()));
        when(canvas.list(context.ownerId(), context.projectId()))
                .thenReturn(List.of(entry(item(versionId, groupId, false), view)));

        assertVersionConflict(this::arrange);

        verify(canvas, never()).apply(any(), any(), any());
    }

    private void assertArrangementSelectionConflict(UUID selectedVersionId) {
        ArtifactService.ArtifactView view = visibleArtifact(Artifact.Kind.IMAGE, version(versionId));
        CanvasItem item = item(selectedVersionId, groupId, false);
        when(canvas.list(context.ownerId(), context.projectId()))
                .thenReturn(List.of(entry(item, view)));
        when(canvas.apply(eq(context.ownerId()), eq(context.projectId()), any()))
                .thenReturn(List.of(entry(item, view)));

        assertVersionConflict(this::arrange);

        verify(canvas, never()).apply(any(), any(), any());
    }

    private void assertVersionConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("ARTIFACT_VERSION_CONFLICT");
    }

    private JsonNode arrange() {
        var input = mapper.createObjectNode().put("layout", "HORIZONTAL");
        input.putArray("items").addObject().put("itemId", itemId.toString())
                .put("versionId", versionId.toString()).put("expectedVersion", ITEM_VERSION);
        return service.arrangeItems(context, run, UUID.randomUUID(), input.toString());
    }

    private ArtifactService.ArtifactView visibleArtifact(Artifact.Kind kind,
            ArtifactVersion resourceDefaultVersion) {
        ArtifactService.ArtifactView view = new ArtifactService.ArtifactView(new Artifact(
                artifactId, context.projectId(), kind, "Synthetic output",
                resourceDefaultVersion == null ? null : resourceDefaultVersion.id(),
                null, 1L, NOW, NOW), resourceDefaultVersion);
        when(artifacts.requireCanvasVersion(context.ownerId(), context.projectId(), versionId)).thenReturn(version(versionId));
        when(artifacts.get(context.ownerId(), context.projectId(), artifactId)).thenReturn(view);
        return view;
    }

    private ArtifactVersion version(UUID id) {
        return new ArtifactVersion(id, context.projectId(), artifactId, 1, 1,
                null, null, mapper.createObjectNode(), List.of(),
                ArtifactVersion.CreatedByKind.TASK, context.runId(), NOW);
    }

    private CanvasItem item(UUID selectedVersionId, UUID groupId, boolean locked) {
        return new CanvasItem(itemId, context.projectId(), CanvasItem.SubjectType.ARTIFACT,
                artifactId, selectedVersionId, "Synthetic card", BigDecimal.ZERO, BigDecimal.ZERO,
                WIDTH, HEIGHT, 0, groupId, locked, ITEM_VERSION, NOW, NOW);
    }

    private CanvasService.CanvasEntry entry(CanvasItem item, ArtifactService.ArtifactView view) {
        return new CanvasService.CanvasEntry(item, view,
                item.selectedVersionId() == null ? null : version(item.selectedVersionId()));
    }
}
