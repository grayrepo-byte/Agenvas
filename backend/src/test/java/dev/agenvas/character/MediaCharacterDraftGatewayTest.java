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
import dev.agenvas.character.application.CharacterDraftGateway.DraftState;
import dev.agenvas.character.application.MediaCharacterDraftGateway;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MediaCharacterDraftGatewayTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), item = UUID.randomUUID();
    private final UUID artifact = UUID.randomUUID(), capability = UUID.randomUUID();
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final CanvasItemQueryService items = mock(CanvasItemQueryService.class);
    private final MediaCapabilityService capabilities = mock(MediaCapabilityService.class);
    private final DirectMediaTaskService tasks = mock(DirectMediaTaskService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaCharacterDraftGateway gateway = new MediaCharacterDraftGateway(drafts, artifacts, items, capabilities, tasks);
    private final Instant now = Instant.parse("2026-10-23T00:00:00Z");

    @BeforeEach
    void fixture() {
        CanvasItem target = mock(CanvasItem.class);
        when(target.subjectId()).thenReturn(artifact);
        when(items.requireArtifactItem(owner, project, item)).thenReturn(target);
        Artifact media = mock(Artifact.class);
        when(media.kind()).thenReturn(Artifact.Kind.IMAGE);
        when(artifacts.get(owner, project, artifact)).thenReturn(new ArtifactService.ArtifactView(media, null));
        when(drafts.pruneRemovedMentions(anyString(), anyList(), anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(drafts.previewSave(eq(owner), eq(project), eq(item), anyLong(), anyString(), any(), any(), any(), any(), anyList(), anyList(), any()))
                .thenAnswer(invocation -> candidate(invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(9)));
    }

    @Test
    void previewReplacesOnlyOwnedSourcesAndPreservesManualConnectedAndSharedInputs() {
        UUID owned = UUID.randomUUID(), manual = UUID.randomUUID(), wired = UUID.randomUUID(), next = UUID.randomUUID();
        var old = input(owned, MediaDraft.SourceType.MANUAL);
        var existing = input(manual, MediaDraft.SourceType.MANUAL);
        var connected = input(wired, MediaDraft.SourceType.CONNECTION);
        when(drafts.get(owner, project, item)).thenReturn(draft("My scene", List.of(old, existing, connected)));
        var state = new DraftState(Map.of(owned, List.of(old.sources().getFirst().id()),
                wired, List.of(connected.sources().getFirst().id())), "", null);
        var plan = gateway.preview(owner, project, item, 4, state,
                List.of(snapshot(next), snapshot(manual)), ClearMode.REMOVE_OWNED);
        assertThat(plan.candidateDraft().mediaInputs()).extracting(MediaDraft.MediaInput::versionId)
                .containsExactly(manual, wired, next);
        assertThat(plan.state().ownedReferenceSources()).containsOnlyKeys(next);
        assertThat(plan.candidateDraft().prompt()).contains("My scene", "Character: Alice", "Clothing: Blue coat");
        assertThat(plan.changes()).contains("REFERENCES_ADDED", "REFERENCES_REMOVED", "PROMPT_UPDATED");
        verify(drafts, never()).save(any(), any(), any(), anyLong(), anyString(), any(), any(), any(), any(), anyList(), anyList(), any());
        verify(tasks).validateLibraryReference(owner, project, Artifact.Kind.IMAGE, plan.candidateDraft());
    }

    @Test
    void removedAndManuallyReaddedReferenceIsNoLongerOwned() {
        UUID reference = UUID.randomUUID();
        var readded = input(reference, MediaDraft.SourceType.MANUAL);
        when(drafts.get(owner, project, item)).thenReturn(draft("User scene", List.of(readded)));
        var oldOwnership = new DraftState(Map.of(reference, List.of(UUID.randomUUID())), "", null);
        var plan = gateway.preview(owner, project, item, 4, oldOwnership, List.of(), ClearMode.REMOVE_OWNED);
        assertThat(plan.candidateDraft().mediaInputs()).extracting(MediaDraft.MediaInput::versionId).containsExactly(reference);
        assertThat(plan.state().ownedReferenceSources()).isEmpty();
    }

    @Test
    void connectedOwnedInputSurvivesCharacterRemoval() {
        UUID reference = UUID.randomUUID();
        var manual = input(reference, MediaDraft.SourceType.MANUAL);
        var connected = new MediaDraft.MediaInput(reference, artifact, MediaDraft.InputRole.REFERENCE, 0, "#7C3AED",
                List.of(manual.sources().getFirst(), input(reference, MediaDraft.SourceType.CONNECTION).sources().getFirst()));
        when(drafts.get(owner, project, item)).thenReturn(draft("Scene", List.of(connected)));
        var plan = gateway.preview(owner, project, item, 4,
                new DraftState(Map.of(reference, List.of(manual.sources().getFirst().id())), "", null),
                List.of(), ClearMode.REMOVE_OWNED);
        assertThat(plan.candidateDraft().mediaInputs()).hasSize(1);
        assertThat(plan.warnings()).contains("CONNECTED_REFERENCES_RETAINED");
    }

    @Test
    void independentManualSourceOnTheSameVersionIsNeitherRemovedNorClaimed() {
        UUID reference = UUID.randomUUID();
        var ownedSource = input(reference, MediaDraft.SourceType.MANUAL).sources().getFirst();
        var userSource = input(reference, MediaDraft.SourceType.MANUAL).sources().getFirst();
        var shared = new MediaDraft.MediaInput(reference, artifact, MediaDraft.InputRole.REFERENCE, 0,
                "#7C3AED", List.of(ownedSource, userSource));
        when(drafts.get(owner, project, item)).thenReturn(draft("Scene", List.of(shared)));
        var beforeState = new DraftState(Map.of(reference, List.of(ownedSource.id())), "", null);
        var removed = gateway.preview(owner, project, item, 4, beforeState, List.of(), ClearMode.REMOVE_OWNED);
        assertThat(removed.candidateDraft().mediaInputs()).hasSize(1);
        assertThat(removed.warnings()).contains("MANUAL_REFERENCES_RETAINED");

        var retained = gateway.preview(owner, project, item, 4, beforeState, List.of(snapshot(reference)), ClearMode.REMOVE_OWNED);
        when(drafts.save(eq(owner), eq(project), eq(item), eq(4L), anyString(), any(), any(), any(), any(), anyList(), anyList(), any()))
                .thenReturn(draft(retained.candidateDraft().prompt(), List.of(shared)));
        assertThat(gateway.apply(owner, project, item, 4, retained).state().ownedReferenceSources().get(reference))
                .containsExactly(ownedSource.id()).doesNotContain(userSource.id());
    }

    @Test
    void audioApplicationUsesBothExactAudioReferenceAndExplicitSpeaker() {
        when(artifacts.get(owner, project, artifact).artifact().kind()).thenReturn(Artifact.Kind.AUDIO);
        when(drafts.get(owner, project, item)).thenReturn(draft("Read the dialogue", List.of()));
        UUID voice = UUID.randomUUID();
        var spoken = new CharacterSnapshot(UUID.randomUUID(), 3, "Alice", "Clear diction", null,
                new CharacterVoice(voice, "speaker_1", "Soft voice"), false, true);
        var plan = gateway.preview(owner, project, item, 4, DraftState.empty(), List.of(spoken), ClearMode.REMOVE_OWNED);
        assertThat(plan.candidateDraft().parameters().path("speaker").asText()).isEqualTo("speaker_1");
        assertThat(plan.candidateDraft().mediaInputs()).extracting(MediaDraft.MediaInput::versionId).containsExactly(voice);
        assertThat(plan.candidateDraft().prompt()).contains("Voice: Soft voice");
        verify(artifacts).requireMediaVersionForTask(owner, project, voice, Artifact.Kind.AUDIO);
        verify(tasks).validateLibraryReference(owner, project, Artifact.Kind.AUDIO, plan.candidateDraft());
    }

    @Test
    void matchingManualSpeakerIsNotClaimedOrRemovedWhenUnbinding() {
        when(artifacts.get(owner, project, artifact).artifact().kind()).thenReturn(Artifact.Kind.AUDIO);
        var parameters = mapper.createObjectNode().put("speaker", "speaker_1");
        when(drafts.get(owner, project, item)).thenReturn(new MediaDraft(project, item, "Dialogue", parameters,
                null, capability, null, null, List.of(), List.of(), MediaDraft.DisplayMode.DRAFT, 4, now, now));
        var spoken = new CharacterSnapshot(UUID.randomUUID(), 1, "Alice", "", null,
                new CharacterVoice(null, "speaker_1", "Soft"), false, true);
        var applied = gateway.preview(owner, project, item, 4, DraftState.empty(), List.of(spoken), ClearMode.REMOVE_OWNED);
        assertThat(applied.state().speakerId()).isNull();
        when(drafts.get(owner, project, item)).thenReturn(new MediaDraft(project, item, applied.candidateDraft().prompt(), parameters,
                null, capability, null, null, List.of(), List.of(), MediaDraft.DisplayMode.DRAFT, 4, now, now));
        var cleared = gateway.preview(owner, project, item, 4, applied.state(), List.of(), ClearMode.REMOVE_OWNED);
        assertThat(cleared.candidateDraft().parameters().path("speaker").asText()).isEqualTo("speaker_1");
    }

    @Test
    void editedCharacterBlockRequiresResolutionWithoutOverwritingUserText() {
        when(drafts.get(owner, project, item)).thenReturn(draft("Scene\n[Character continuity]\nMy custom edit", List.of()));
        var state = new DraftState(Map.of(), "\n[Character continuity]\nOriginal", null);
        assertThatThrownBy(() -> gateway.preview(owner, project, item, 4, state,
                List.of(snapshot(UUID.randomUUID())), ClearMode.REMOVE_OWNED))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("CHARACTER_PROMPT_EDITED"));
        verify(drafts, never()).save(any(), any(), any(), anyLong(), anyString(), any(), any(), any(), any(), anyList(), anyList(), any());
    }

    @Test
    void clearKeepingInputsReleasesOwnershipAndRetainsUserDraft() {
        var before = draft("Keep my prompt", List.of(input(UUID.randomUUID(), MediaDraft.SourceType.MANUAL)));
        when(drafts.get(owner, project, item)).thenReturn(before);
        var plan = gateway.preview(owner, project, item, 4, DraftState.empty(), List.of(), ClearMode.KEEP_INPUTS);
        assertThat(plan.candidateDraft().prompt()).isEqualTo(before.prompt());
        assertThat(plan.candidateDraft().mediaInputs()).extracting(MediaDraft.MediaInput::versionId)
                .containsExactlyElementsOf(before.mediaInputs().stream().map(MediaDraft.MediaInput::versionId).toList());
        assertThat(plan.state()).isEqualTo(DraftState.empty());
        assertThat(plan.changes()).containsExactly("KEEP_INPUTS");
    }

    @Test
    void applyCapturesActualSavedSourceIdsForTheNextRevision() {
        UUID reference = UUID.randomUUID();
        when(drafts.get(owner, project, item)).thenReturn(draft("Scene", List.of()));
        var plan = gateway.preview(owner, project, item, 4, DraftState.empty(), List.of(snapshot(reference)), ClearMode.REMOVE_OWNED);
        var savedInput = input(reference, MediaDraft.SourceType.MANUAL);
        var saved = draft(plan.candidateDraft().prompt(), List.of(savedInput));
        when(drafts.save(eq(owner), eq(project), eq(item), eq(4L), anyString(), any(), any(), any(), any(), anyList(), anyList(), any())).thenReturn(saved);
        var result = gateway.apply(owner, project, item, 4, plan);
        assertThat(result.state().ownedReferenceSources().get(reference)).containsExactly(savedInput.sources().getFirst().id());
        assertThat(result.draft()).isSameAs(saved);
        verify(tasks, times(2)).requireUnoccupied(owner, project, artifact, item);
    }

    @Test
    void speakerIdentityIsNotSilentlyAppliedToAnImage() {
        when(drafts.get(owner, project, item)).thenReturn(draft("Scene", List.of()));
        var look = snapshot(UUID.randomUUID()).look();
        var voiceOnly = new CharacterSnapshot(UUID.randomUUID(), 1, "Alice", "", look,
                new CharacterVoice(null, "voice_1", "Soft"), false, true);
        assertThatThrownBy(() -> gateway.preview(owner, project, item, 4, DraftState.empty(), List.of(voiceOnly), ClearMode.REMOVE_OWNED))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("CHARACTER_INPUT_INCOMPATIBLE"));
    }

    private CharacterSnapshot snapshot(UUID version) {
        return new CharacterSnapshot(UUID.randomUUID(), 1, "Alice", "Curly hair",
                new CharacterLook(UUID.randomUUID(), "Travel", "Blue coat", "Calm", "", List.of(new CharacterView(version, "FRONT", ""))),
                null, true, false);
    }
    private MediaDraft.MediaInput input(UUID version, MediaDraft.SourceType type) {
        return new MediaDraft.MediaInput(version, artifact, MediaDraft.InputRole.REFERENCE, 0, "#7C3AED",
                List.of(new MediaDraft.InputSource(UUID.randomUUID(), type, type == MediaDraft.SourceType.CONNECTION ? UUID.randomUUID() : null)));
    }
    private MediaDraft draft(String prompt, List<MediaDraft.MediaInput> inputs) {
        return new MediaDraft(project, item, prompt, mapper.createObjectNode(), null, capability, null,
                null, inputs, List.of(), MediaDraft.DisplayMode.DRAFT, 4, now, now);
    }
    private MediaDraft candidate(String prompt, tools.jackson.databind.JsonNode parameters, List<MediaDraftService.SaveMediaInput> inputs) {
        return new MediaDraft(project, item, prompt, parameters, null, capability, null, null,
                inputs.stream().map(input -> input(input.versionId(), MediaDraft.SourceType.MANUAL)).toList(),
                List.of(), MediaDraft.DisplayMode.DRAFT, 5, now, now);
    }
}
