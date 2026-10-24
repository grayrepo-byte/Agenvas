package dev.agenvas.character;

import static dev.agenvas.character.application.CharacterModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.character.application.CharacterDraftGateway;
import dev.agenvas.character.application.CharacterDraftGateway.DraftState;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.character.infrastructure.CharacterRepository;
import dev.agenvas.character.infrastructure.CharacterRepository.BindingRow;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Character revisions and confirmation digest behavior independently of persistence mapping. */
class CharacterServiceTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), item = UUID.randomUUID(), artifact = UUID.randomUUID();
    private final UUID character = UUID.randomUUID(), look = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-23T00:00:00Z");
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final CharacterRepository repository = mock(CharacterRepository.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final CanvasItemQueryService canvas = mock(CanvasItemQueryService.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final CharacterDraftGateway gateway = mock(CharacterDraftGateway.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final DirectMediaTaskService tasks = mock(DirectMediaTaskService.class);
    private CharacterService service;

    @BeforeEach
    void setup() {
        ObjectProvider<DirectMediaTaskService> provider = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                Map.of("tasks", tasks)).getBeanProvider(DirectMediaTaskService.class);
        service = new CharacterService(repository, projects, artifacts, canvas, drafts, gateway, events,
                provider, mapper, Clock.fixed(now, ZoneOffset.UTC));
        when(canvas.requireArtifactItem(owner, project, item)).thenReturn(card(null, false));
        when(artifacts.get(owner, project, artifact)).thenReturn(new ArtifactService.ArtifactView(
                new Artifact(artifact, project, Artifact.Kind.IMAGE, "Shot", null, null, 0, now, now), null));
        when(repository.binding(project, item)).thenReturn(Optional.empty());
        when(repository.command(eq(project), any())).thenReturn(Optional.empty());
        when(drafts.get(owner, project, item)).thenReturn(draft("Shot", now));
        doAnswer(invocation -> {
            Supplier<?> mutation = invocation.getArgument(2, Supplier.class);
            ProjectEventService.Change<?> change = (ProjectEventService.Change<?>) mutation.get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        }).when(events).recordChange(eq(owner), eq(project), any());
    }

    @Test
    void confirmationDigestIgnoresPreviewOnlyTimestampsAndNewSourceIds() {
        when(repository.profile(project, character)).thenReturn(Optional.of(profile(1, "Stable")));
        when(gateway.preview(eq(owner), eq(project), eq(item), eq(0L), any(), anyList(), any()))
                .thenAnswer(ignored -> new CharacterDraftGateway.Plan(draft("Shot with continuity", Instant.now()),
                        DraftState.empty(), List.of(), List.of("PROMPT_UPDATED"), Map.of()));
        var request = request(1);
        var first = service.preview(owner, project, item, request);
        var second = service.preview(owner, project, item, request);
        assertThat(first.previewHash()).isEqualTo(second.previewHash());
        assertThat(first.snapshots()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.characterVersion()).isEqualTo(1);
            assertThat(snapshot.name()).isEqualTo("Stable");
        });
        verify(gateway, never()).apply(any(), any(), any(), anyLong(), any());
    }

    @Test
    void characterChangedAfterPreviewCannotApplyOldDraftPlan() {
        when(repository.profile(project, character)).thenReturn(Optional.of(profile(1, "Stable")));
        when(gateway.preview(eq(owner), eq(project), eq(item), eq(0L), any(), anyList(), any()))
                .thenReturn(new CharacterDraftGateway.Plan(draft("Continuity", now), DraftState.empty(), List.of(), List.of(), Map.of()));
        var preview = service.preview(owner, project, item, request(1));
        when(repository.profile(project, character)).thenReturn(Optional.of(profile(2, "Revised")));
        var apply = new ApplyCharacterBindings(UUID.randomUUID(), 0, 0, request(1).selections(), ClearMode.REMOVE_OWNED, preview.previewHash());
        assertThatThrownBy(() -> service.apply(owner, project, item, apply)).isInstanceOfSatisfying(ApiProblemException.class,
                error -> assertThat(error.code()).isEqualTo("CHARACTER_PREVIEW_CONFLICT"));
        verify(gateway, never()).apply(any(), any(), any(), anyLong(), any());
    }

    @Test
    void frozenBindingsKeepAppliedRevisionAfterProfileChangesButRejectEditedContribution() {
        var fixed = new CharacterSnapshot(character, 1, "Old fixed name", "Identity", profile(1, "Old fixed name").content().looks().getFirst(), null, true, false);
        BindingRow row = new BindingRow(project, item, 1, 0, List.of(fixed),
                new DraftState(Map.of(), "continuity", null), false, 0L, null, now, now);
        when(repository.binding(project, item)).thenReturn(Optional.of(row));
        when(repository.bindings(project)).thenReturn(List.of(row));
        when(repository.profiles(project)).thenReturn(List.of(profile(2, "New live name")));
        when(drafts.get(owner, project, item)).thenReturn(draft("Shot continuity with an unrelated addition", now));
        assertThat(service.frozenBindings(owner, project, item).get(0).path("name").asString()).isEqualTo("Old fixed name");
        assertThat(service.list(owner, project).bindings().getFirst().reviewReasons()).contains(ReviewReason.PROFILE_CHANGED);
        when(drafts.get(owner, project, item)).thenReturn(draft("User replaced the description", now));
        assertThat(service.frozenBindings(owner, project, item).isEmpty()).isTrue();
        assertThat(service.list(owner, project).bindings().getFirst().reviewReasons()).contains(ReviewReason.DRAFT_CHANGED);
    }

    @Test
    void saveReplaysOriginalResponseAndNeverCreatesAnotherRevision() {
        AtomicReference<CharacterProfile> savedProfile = new AtomicReference<>();
        AtomicReference<CharacterRepository.Command> savedCommand = new AtomicReference<>();
        when(repository.profile(project, character)).thenAnswer(ignored -> Optional.ofNullable(savedProfile.get()));
        when(repository.profiles(project)).thenReturn(List.of());
        when(repository.command(eq(project), any())).thenAnswer(ignored -> Optional.ofNullable(savedCommand.get()));
        when(repository.insertProfile(any())).thenAnswer(invocation -> { savedProfile.set(invocation.getArgument(0)); return true; });
        doAnswer(invocation -> { savedCommand.set(new CharacterRepository.Command(invocation.getArgument(2),
                mapper.valueToTree(invocation.getArgument(3)))); return null; })
                .when(repository).command(eq(project), any(), anyString(), any(), any());
        SaveCharacter request = new SaveCharacter(UUID.randomUUID(), 0, profile(1, "Stable").content());
        CharacterProfile first = service.save(owner, project, character, request);
        assertThat(service.save(owner, project, character, request)).isEqualTo(first);
        assertThat(first.version()).isOne();
        verify(repository, times(1)).insertProfile(any());
    }

    @Test
    void emptyResultAndLockedShotsCannotBeReviewedOrApplied() {
        assertThatThrownBy(() -> service.review(owner, project, item, new ReviewCharacterBindings(1, 0, null)))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
        when(canvas.requireArtifactItem(owner, project, item)).thenReturn(card(UUID.randomUUID(), true));
        assertThatThrownBy(() -> service.preview(owner, project, item, request(1)))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("CANVAS_ITEM_LOCKED"));
        verifyNoInteractions(gateway);
    }

    @Test
    void copyUsesFixedSourceSnapshotsAndRemapsOnlyOwnedManualSources() {
        UUID target = UUID.randomUUID(), reference = UUID.randomUUID(), oldSource = UUID.randomUUID(), newSource = UUID.randomUUID();
        var fixedLook = new CharacterLook(look, "Old look", "Blue", "Calm", "Old details",
                List.of(new CharacterView(reference, "Front", "Face")));
        var fixed = new CharacterSnapshot(character, 1, "Old fixed name", "Identity", fixedLook, null, true, false);
        BindingRow source = new BindingRow(project, item, 3, 2, List.of(fixed),
                new DraftState(Map.of(reference, List.of(oldSource)), "continuity", null), true, null, null, null, now);
        when(repository.binding(project, item)).thenReturn(Optional.of(source));
        when(drafts.get(owner, project, item)).thenReturn(new MediaDraft(project, item, "Shot continuity", mapper.createObjectNode(),
                null, null, null, null, List.of(new MediaDraft.MediaInput(reference, artifact, MediaDraft.InputRole.REFERENCE, 0,
                        "#7C3AED", List.of(new MediaDraft.InputSource(oldSource, MediaDraft.SourceType.MANUAL, null)))),
                List.of(), MediaDraft.DisplayMode.DRAFT, 2, now, now));
        when(repository.binding(project, target)).thenReturn(Optional.empty());
        when(canvas.requireArtifactItem(owner, project, target)).thenReturn(card(null, false));
        when(drafts.get(owner, project, target)).thenReturn(new MediaDraft(project, target, "Shot continuity", mapper.createObjectNode(),
                null, null, null, null, List.of(new MediaDraft.MediaInput(reference, artifact, MediaDraft.InputRole.REFERENCE, 0,
                        "#7C3AED", List.of(new MediaDraft.InputSource(newSource, MediaDraft.SourceType.MANUAL, null),
                                new MediaDraft.InputSource(UUID.randomUUID(), MediaDraft.SourceType.CONNECTION, UUID.randomUUID())))),
                List.of(), MediaDraft.DisplayMode.DRAFT, 0, now, now));
        when(repository.saveBinding(any(), eq(0L))).thenReturn(true);
        service.copyBindingsWithinChange(owner, project, item, target);
        var copied = org.mockito.ArgumentCaptor.forClass(BindingRow.class);
        verify(repository).saveBinding(copied.capture(), eq(0L));
        assertThat(copied.getValue().snapshots()).containsExactly(fixed);
        assertThat(copied.getValue().state().ownedReferenceSources()).containsEntry(reference, List.of(newSource));
        assertThat(copied.getValue().needsReview()).isTrue();
        verify(repository, never()).profile(any(), any());
        verify(events).append(eq(owner), eq(project), any());
    }

    @Test
    void lateExtraUsesAcceptedSnapshotsEvenWhenLiveSourceChanged() {
        UUID target = UUID.randomUUID(), reference = UUID.randomUUID(), targetSource = UUID.randomUUID();
        var oldLook = new CharacterLook(look, "Old look", "Blue coat", "Calm", "Old details",
                List.of(new CharacterView(reference, "Front", "Face")));
        var oldSnapshot = new CharacterSnapshot(character, 1, "Old name", "Identity", oldLook, null, true, false);
        var frozen = mapper.createObjectNode();
        frozen.set("characterBindings", mapper.valueToTree(List.of(oldSnapshot)));
        frozen.set("characterContribution", mapper.valueToTree(new CharacterContribution(List.of(reference), "old continuity", null)));
        when(repository.binding(project, target)).thenReturn(Optional.empty());
        when(canvas.requireArtifactItem(owner, project, target)).thenReturn(card(null, false));
        when(drafts.get(owner, project, target)).thenReturn(new MediaDraft(project, target, "Scene old continuity", mapper.createObjectNode(),
                null, null, null, null, List.of(new MediaDraft.MediaInput(reference, artifact, MediaDraft.InputRole.REFERENCE, 0,
                        "#7C3AED", List.of(new MediaDraft.InputSource(targetSource, MediaDraft.SourceType.MANUAL, null)))),
                List.of(), MediaDraft.DisplayMode.DRAFT, 0, now, now));
        when(repository.saveBinding(any(), eq(0L))).thenReturn(true);
        service.copyFrozenBindingsWithinChange(owner, project, target, frozen);
        var copied = org.mockito.ArgumentCaptor.forClass(BindingRow.class);
        verify(repository).saveBinding(copied.capture(), eq(0L));
        assertThat(copied.getValue().snapshots()).containsExactly(oldSnapshot);
        assertThat(copied.getValue().state().ownedReferenceSources()).containsEntry(reference, List.of(targetSource));
        verify(repository, never()).profile(any(), any());
        verify(repository, never()).binding(project, item);
        verify(drafts, never()).get(owner, project, item);
    }

    @Test
    void cloneDoesNotClaimAReferenceSharedWithAConnection() {
        UUID target = UUID.randomUUID(), reference = UUID.randomUUID(), oldSource = UUID.randomUUID(), targetSource = UUID.randomUUID();
        var oldLook = new CharacterLook(look, "Old look", "Blue", "Calm", "Old details",
                List.of(new CharacterView(reference, "Front", "Face")));
        var snapshot = new CharacterSnapshot(character, 1, "Old", "Identity", oldLook, null, true, false);
        BindingRow source = new BindingRow(project, item, 1, 0, List.of(snapshot),
                new DraftState(Map.of(reference, List.of(oldSource)), "continuity", null), true, null, null, null, now);
        when(repository.binding(project, item)).thenReturn(Optional.of(source));
        when(repository.binding(project, target)).thenReturn(Optional.empty());
        when(canvas.requireArtifactItem(owner, project, target)).thenReturn(card(null, false));
        when(drafts.get(owner, project, item)).thenReturn(new MediaDraft(project, item, "continuity", mapper.createObjectNode(),
                null, null, null, null, List.of(new MediaDraft.MediaInput(reference, artifact, MediaDraft.InputRole.REFERENCE, 0,
                        "#7C3AED", List.of(new MediaDraft.InputSource(oldSource, MediaDraft.SourceType.MANUAL, null),
                                new MediaDraft.InputSource(UUID.randomUUID(), MediaDraft.SourceType.CONNECTION, UUID.randomUUID())))),
                List.of(), MediaDraft.DisplayMode.DRAFT, 0, now, now));
        when(drafts.get(owner, project, target)).thenReturn(new MediaDraft(project, target, "continuity", mapper.createObjectNode(),
                null, null, null, null, List.of(new MediaDraft.MediaInput(reference, artifact, MediaDraft.InputRole.REFERENCE, 0,
                        "#7C3AED", List.of(new MediaDraft.InputSource(targetSource, MediaDraft.SourceType.MANUAL, null)))),
                List.of(), MediaDraft.DisplayMode.DRAFT, 0, now, now));
        when(repository.saveBinding(any(), eq(0L))).thenReturn(true);
        service.copyBindingsWithinChange(owner, project, item, target);
        var copied = org.mockito.ArgumentCaptor.forClass(BindingRow.class);
        verify(repository).saveBinding(copied.capture(), eq(0L));
        assertThat(copied.getValue().snapshots()).containsExactly(snapshot);
        assertThat(copied.getValue().state().ownedReferenceSources()).isEmpty();
    }

    @Test
    void manualSpeakerStillMustMatchAppliedAudioSnapshot() {
        var voice = new CharacterVoice(null, "voice_1", "Soft voice");
        var snapshot = new CharacterSnapshot(character, 1, "Aster", "Identity", null, voice, false, true);
        BindingRow row = new BindingRow(project, item, 1, 0, List.of(snapshot),
                new DraftState(Map.of(), "voice block", null), true, null, null, null, now);
        when(repository.binding(project, item)).thenReturn(Optional.of(row));
        when(artifacts.get(owner, project, artifact)).thenReturn(new ArtifactService.ArtifactView(
                new Artifact(artifact, project, Artifact.Kind.AUDIO, "Voice shot", null, null, 0, now, now), null));
        MediaDraft withSpeaker = new MediaDraft(project, item, "voice block", mapper.createObjectNode().put("speaker", "voice_1"),
                null, null, null, null, List.of(), List.of(), MediaDraft.DisplayMode.DRAFT, 0, now, now);
        when(drafts.get(owner, project, item)).thenReturn(withSpeaker);
        assertThat(service.frozenBindings(owner, project, item)).hasSize(1);
        when(drafts.get(owner, project, item)).thenReturn(draft("voice block", now));
        assertThat(service.frozenBindings(owner, project, item)).isEmpty();
    }

    @Test
    void restoredLegacySnapshotsWithoutContributionNeverClaimManualReferences() {
        UUID target = UUID.randomUUID(), reference = UUID.randomUUID();
        var oldLook = new CharacterLook(look, "Old look", "Blue", "Calm", "Old details",
                List.of(new CharacterView(reference, "Front", "Face")));
        var snapshot = new CharacterSnapshot(character, 1, "Old", "Identity", oldLook, null, true, false);
        var frozen = mapper.createObjectNode(); frozen.set("characterBindings", mapper.valueToTree(List.of(snapshot)));
        when(repository.binding(project, target)).thenReturn(Optional.empty());
        when(canvas.requireArtifactItem(owner, project, target)).thenReturn(card(null, false));
        when(drafts.get(owner, project, target)).thenReturn(new MediaDraft(project, target, "old continuity", mapper.createObjectNode(),
                null, null, null, null, List.of(new MediaDraft.MediaInput(reference, artifact, MediaDraft.InputRole.REFERENCE, 0,
                        "#7C3AED", List.of(new MediaDraft.InputSource(UUID.randomUUID(), MediaDraft.SourceType.MANUAL, null)))),
                List.of(), MediaDraft.DisplayMode.DRAFT, 0, now, now));
        when(gateway.describeSnapshots(List.of(snapshot))).thenReturn("old continuity");
        when(repository.saveBinding(any(), eq(0L))).thenReturn(true);
        service.copyFrozenBindingsWithinChange(owner, project, target, frozen);
        var copied = org.mockito.ArgumentCaptor.forClass(BindingRow.class);
        verify(repository).saveBinding(copied.capture(), eq(0L));
        assertThat(copied.getValue().snapshots()).containsExactly(snapshot);
        assertThat(copied.getValue().state().ownedReferenceSources()).isEmpty();
    }

    private PreviewCharacterBindings request(long version) {
        return new PreviewCharacterBindings(0, 0,
                List.of(new CharacterBindingSelection(character, version, look, true, false)), ClearMode.REMOVE_OWNED);
    }
    private CharacterProfile profile(long version, String name) {
        return new CharacterProfile(character, project,
                new CharacterContent(name, "Identity", List.of(new CharacterLook(look, "Main", "Coat", "Calm", "Details", List.of())), null),
                version, false, now, now);
    }
    private CanvasItem card(UUID selected, boolean locked) {
        return new CanvasItem(item, project, CanvasItem.SubjectType.ARTIFACT, artifact, selected, "Shot", BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.valueOf(360), BigDecimal.valueOf(260), 0, null, locked, 0, now, now);
    }
    private MediaDraft draft(String prompt, Instant updated) {
        return new MediaDraft(project, item, prompt, mapper.createObjectNode(), null, null, null, null,
                List.of(), List.of(), MediaDraft.DisplayMode.DRAFT, 0, now, updated);
    }
}
