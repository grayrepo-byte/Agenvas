package dev.agenvas.canvas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.llm.application.AgentMediaApprovalOwnershipReadService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.storyboard.application.StoryboardSelectedMediaReadAccessService;
import dev.agenvas.task.application.TaskCanvasOwnershipReadService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CanvasCopySupportTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), source = UUID.randomUUID(), target = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID(), image = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-23T00:00:00Z");
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final CanvasItemQueryService items = mock(CanvasItemQueryService.class);
    private final CanvasConnectionService connections = mock(CanvasConnectionService.class);
    private final CharacterService characters = mock(CharacterService.class);
    private final TaskCanvasOwnershipReadService tasks = mock(TaskCanvasOwnershipReadService.class);
    private final AgentMediaApprovalOwnershipReadService approvals = mock(AgentMediaApprovalOwnershipReadService.class);
    private final StoryboardSelectedMediaReadAccessService storyboards = mock(StoryboardSelectedMediaReadAccessService.class);
    private final CanvasCopySupport service = new CanvasCopySupport(mock(ProjectService.class), artifacts, drafts, items,
            connections, characters, tasks, approvals, storyboards, mapper, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void mediaCopyPinsSavedDraftAndMakesEveryInputANewManualSource() {
        CanvasItem card = card(source, artifactId, image);
        UUID oldManual = UUID.randomUUID(), connectionId = UUID.randomUUID();
        MediaDraft original = draft(source, List.of(new MediaDraft.InputSource(oldManual, MediaDraft.SourceType.MANUAL, null),
                new MediaDraft.InputSource(UUID.randomUUID(), MediaDraft.SourceType.CONNECTION, connectionId)));
        when(items.requireArtifactItem(owner, project, source)).thenReturn(card);
        when(artifacts.get(owner, project, artifactId)).thenReturn(view(artifactId, Artifact.Kind.IMAGE, null));
        when(drafts.get(owner, project, source)).thenReturn(original);
        when(characters.prepareCanvasCopy(eq(owner), eq(project), eq(source), any())).thenReturn(mapper.createObjectNode().put("fixed", true));
        var prepared = service.prepareCloneWithinChange(owner, project, card, target);
        var copied = mapper.treeToValue(prepared.frozen().path("mediaDraft"), MediaDraft.class);
        assertThat(prepared.subjectId()).isEqualTo(artifactId);
        assertThat(prepared.selectedVersionId()).isEqualTo(image);
        assertThat(prepared.frozen().path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(copied.prompt()).isEqualTo(original.prompt());
        assertThat(copied.parameters()).isEqualTo(original.parameters());
        assertThat(copied.mediaInputs()).singleElement().satisfies(input -> {
            assertThat(input.color()).isEqualTo("#7C3AED");
            assertThat(input.sources()).singleElement().satisfies(ownership -> {
                assertThat(ownership.type()).isEqualTo(MediaDraft.SourceType.MANUAL);
                assertThat(ownership.id()).isNotEqualTo(oldManual);
                assertThat(ownership.connectionId()).isNull();
            });
        });
        assertThat(copied.canvasItemId()).isEqualTo(target);
        assertThat(copied.version()).isEqualTo(1);
    }

    @Test
    void textCopyAllocatesIndependentArtifactWithExactCurrentBody() {
        UUID copyArtifact = UUID.randomUUID();
        JsonNode body = mapper.createObjectNode().put("format", "plain").put("text", "Independent writing");
        var sourceVersion = textVersion(artifactId, body);
        var copiedVersion = textVersion(copyArtifact, body);
        CanvasItem card = card(source, artifactId, null);
        when(items.requireArtifactItem(owner, project, source)).thenReturn(card);
        when(artifacts.get(owner, project, artifactId)).thenReturn(view(artifactId, Artifact.Kind.TEXT, sourceVersion));
        when(artifacts.createTextCopyWithinChange(owner, project, "Card title", body)).thenReturn(view(copyArtifact, Artifact.Kind.TEXT, copiedVersion));
        when(artifacts.get(owner, project, copyArtifact)).thenReturn(view(copyArtifact, Artifact.Kind.TEXT, copiedVersion));
        when(artifacts.listVersions(owner, project, copyArtifact)).thenReturn(List.of(copiedVersion));
        var prepared = service.prepareCloneWithinChange(owner, project, card, target);
        assertThat(prepared.subjectId()).isEqualTo(copyArtifact).isNotEqualTo(artifactId);
        assertThat(prepared.selectedVersionId()).isNull();
        verifyNoInteractions(drafts, characters, tasks, approvals, storyboards);
    }

    @Test
    void redoRestoresFrozenSourcesAndAdvancesDraftAndBindingIncarnations() {
        CanvasItem sourceCard = card(source, artifactId, image);
        when(items.requireArtifactItem(owner, project, source)).thenReturn(sourceCard);
        when(artifacts.get(owner, project, artifactId)).thenReturn(view(artifactId, Artifact.Kind.IMAGE, null));
        when(drafts.get(owner, project, source)).thenReturn(draft(source, List.of(new MediaDraft.InputSource(UUID.randomUUID(), MediaDraft.SourceType.MANUAL, null))));
        when(characters.prepareCanvasCopy(eq(owner), eq(project), eq(source), any())).thenReturn(mapper.createObjectNode().put("version", 1));
        var frozen = service.prepareCloneWithinChange(owner, project, sourceCard, target).frozen();
        clearInvocations(drafts, characters, items);
        var previous = mapper.createObjectNode();
        previous.putObject("mediaDraft").put("version", 5);
        previous.putObject("characters").putObject("binding").put("version", 4);
        service.restoreWithinChange(owner, project, card(target, artifactId, image), frozen, previous);
        verify(drafts).installCopyWithinChange(eq(owner), eq(project), eq(target),
                argThat(value -> mapper.valueToTree(value).equals(frozen.path("mediaDraft"))), eq(6L));
        verify(characters).installCanvasCopyWithinChange(owner, project, target, frozen.path("characters"), 5);
        verify(drafts, never()).get(any(), any(), any());
        verifyNoInteractions(items);
    }

    @Test
    void captureExcludesPresentationButIncludesAllDurableUsageGuards() {
        CanvasItem card = card(target, artifactId, image);
        when(items.requireArtifactItem(owner, project, target)).thenReturn(card);
        when(items.mediaCopyGuard(owner, project, target)).thenReturn(new CanvasItemQueryService.MediaCopyGuard(7, List.of(image)));
        when(artifacts.get(owner, project, artifactId)).thenReturn(view(artifactId, Artifact.Kind.IMAGE, null));
        when(drafts.get(owner, project, target)).thenReturn(draft(target, List.of()));
        when(connections.copyGuard(owner, project, target)).thenReturn(new CanvasConnectionRepository.TopologyGuard(11, List.of()));
        when(characters.canvasCopyGuard(owner, project, target)).thenReturn(mapper.createObjectNode().putObject("binding").put("version", 3));
        when(tasks.hasOwnership(owner, project, target, null)).thenReturn(true);
        when(approvals.hasOwnership(owner, project, target)).thenReturn(true);
        when(storyboards.hasCanvasReference(owner, project, target)).thenReturn(true);
        var guard = service.capture(owner, project, card);
        assertThat(guard.path("mediaSelectionEpoch").asLong()).isEqualTo(7);
        assertThat(guard.path("topologyEpoch").asLong()).isEqualTo(11);
        assertThat(guard.path("hasTaskOwnership").asBoolean()).isTrue();
        assertThat(guard.path("hasApprovalOwnership").asBoolean()).isTrue();
        assertThat(guard.path("hasStoryboardReference").asBoolean()).isTrue();
        assertThat(guard.has("title")).isFalse();
        assertThat(guard.has("x")).isFalse();
        assertThat(guard.has("version")).isFalse();
        assertThatThrownBy(() -> service.installWithinChange(owner, project, card, mapper.createObjectNode().put("schemaVersion", 2)))
                .isInstanceOf(ApiProblemException.class);
    }

    private CanvasItem card(UUID id, UUID artifact, UUID selected) {
        return new CanvasItem(id, project, CanvasItem.SubjectType.ARTIFACT, artifact, selected, "Card title",
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"), new BigDecimal("240"), 0, null, false, 0, now, now);
    }
    private ArtifactService.ArtifactView view(UUID artifact, Artifact.Kind kind, ArtifactVersion version) {
        return new ArtifactService.ArtifactView(new Artifact(artifact, project, kind, "Artifact title",
                version == null ? null : version.id(), null, 0, now, now), version);
    }
    private MediaDraft draft(UUID item, List<MediaDraft.InputSource> sources) {
        return new MediaDraft(project, item, "Persisted prompt", mapper.createObjectNode().put("width", 1024), null, null,
                null, null, List.of(new MediaDraft.MediaInput(image, UUID.randomUUID(), MediaDraft.InputRole.REFERENCE,
                        0, "#7C3AED", sources)), List.of(), MediaDraft.DisplayMode.RESULT, 8, now, now);
    }
    private ArtifactVersion textVersion(UUID artifact, JsonNode content) {
        return new ArtifactVersion(UUID.randomUUID(), project, artifact, 1, 1, null, null, content,
                List.of(), ArtifactVersion.CreatedByKind.USER, null, now);
    }
}
