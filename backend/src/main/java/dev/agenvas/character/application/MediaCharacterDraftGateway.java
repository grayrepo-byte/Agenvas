package dev.agenvas.character.application;

import static dev.agenvas.character.application.CharacterModels.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.domain.Task;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** Character application is a normal validated draft write. Ownership tracks source IDs,
 * so updating a character cannot delete references that the user added independently.
 */
@Service
public class MediaCharacterDraftGateway implements CharacterDraftGateway {
    private static final String BLOCK_START = "[Character continuity]";
    private static final String BLOCK_END = "[/Character continuity]";
    private static final String REFERENCE_COLOR = "#7C3AED";
    private final MediaDraftService drafts;
    private final ArtifactService artifacts;
    private final CanvasItemQueryService items;
    private final MediaCapabilityService capabilities;
    private final DirectMediaTaskService tasks;

    public MediaCharacterDraftGateway(MediaDraftService drafts, ArtifactService artifacts,
            CanvasItemQueryService items, MediaCapabilityService capabilities,
            @Lazy DirectMediaTaskService tasks) {
        this.drafts = drafts;
        this.artifacts = artifacts;
        this.items = items;
        this.capabilities = capabilities;
        this.tasks = tasks;
    }

    @Override
    @Transactional(readOnly = true)
    public Plan preview(UUID owner, UUID project, UUID item, long expectedDraftVersion,
            DraftState beforeState, List<CharacterSnapshot> after, ClearMode clearMode) {
        var target = items.requireArtifactItem(owner, project, item);
        var kind = artifacts.get(owner, project, target.subjectId()).artifact().kind();
        tasks.requireUnoccupied(owner, project, target.subjectId(), item);
        MediaDraft before = drafts.get(owner, project, item);
        if (before.version() != expectedDraftVersion) throw conflict();
        DraftState state = beforeState == null ? DraftState.empty() : beforeState;
        if (after.isEmpty() && clearMode == ClearMode.KEEP_INPUTS) {
            MediaDraft candidate = drafts.previewSave(owner, project, item, expectedDraftVersion,
                    before.prompt(), before.parameters(), before.durationSeconds(), before.capabilityId(),
                    before.videoInputMode(), commands(before.mediaInputs()), before.mentions(), before.styleId());
            return new Plan(candidate, DraftState.empty(), List.of(), List.of("KEEP_INPUTS"), Map.of());
        }

        List<String> warnings = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        Map<UUID, Artifact.Kind> desired = desiredReferences(after, owner, project, kind, warnings);
        Map<UUID, List<UUID>> owned = new LinkedHashMap<>();
        Map<UUID, List<UUID>> release = new LinkedHashMap<>();
        Set<UUID> removed = new LinkedHashSet<>();
        List<MediaDraftService.SaveMediaInput> inputs = new ArrayList<>();
        for (var input : before.mediaInputs()) {
            List<UUID> sourceIds = state.ownedReferenceSources().get(input.versionId());
            boolean ownsManualSource = sourceIds != null && input.sources().stream().anyMatch(source ->
                    source.type() == MediaDraft.SourceType.MANUAL && sourceIds.contains(source.id()));
            boolean connected = input.sources().stream().anyMatch(source ->
                    source.type() == MediaDraft.SourceType.CONNECTION);
            boolean hasIndependentManual = input.sources().stream().anyMatch(source ->
                    source.type() == MediaDraft.SourceType.MANUAL && (sourceIds == null || !sourceIds.contains(source.id())));
            if (ownsManualSource && !desired.containsKey(input.versionId()))
                release.put(input.versionId(), sourceIds);
            if (ownsManualSource && !desired.containsKey(input.versionId()) && !connected && !hasIndependentManual) {
                removed.add(input.versionId());
                continue;
            }
            if (ownsManualSource && desired.containsKey(input.versionId())) owned.put(input.versionId(), sourceIds);
            if (ownsManualSource && !desired.containsKey(input.versionId()) && connected)
                warnings.add("CONNECTED_REFERENCES_RETAINED");
            if (ownsManualSource && !desired.containsKey(input.versionId()) && hasIndependentManual)
                warnings.add("MANUAL_REFERENCES_RETAINED");
            inputs.add(new MediaDraftService.SaveMediaInput(input.versionId(), input.role(), input.color()));
        }
        ObjectNode parameters = (ObjectNode) before.parameters().deepCopy();
        UUID effectiveCapability = before.capabilityId() == null
                ? capabilities.defaultCapabilityId(Task.Kind.valueOf(kind.name() + "_GENERATION")) : before.capabilityId();
        pruneSlots(parameters, removed, effectiveCapability);
        String prompt = before.prompt();
        if (state.promptBlock() != null && !state.promptBlock().isEmpty()) {
            int start = prompt.indexOf(state.promptBlock());
            if (start >= 0) prompt = prompt.substring(0, start) + prompt.substring(start + state.promptBlock().length());
            else if (prompt.contains(BLOCK_START)) {
                // An edited character block is user text. Require an explicit manual resolution,
                // rather than overwriting it or appending contradictory old and new definitions.
                throw new ApiProblemException(HttpStatus.CONFLICT, "CHARACTER_PROMPT_EDITED",
                        ApiMessage.of("api.character.conflict"), ApiMessage.of("api.character.prompt-edited"), false);
            }
        }
        List<MediaDraft.PromptMention> mentions = before.mentions().stream()
                .filter(mention -> !removed.contains(mention.versionId())).toList();
        prompt = drafts.pruneRemovedMentions(prompt, before.mentions(), mentions);
        String promptBlock = describeSnapshots(after);
        if (!promptBlock.isEmpty()) prompt += promptBlock;
        if (!prompt.equals(before.prompt())) changes.add("PROMPT_UPDATED");
        if (!removed.isEmpty()) changes.add("REFERENCES_REMOVED");

        for (var reference : desired.entrySet()) {
            if (inputs.stream().anyMatch(input -> input.versionId().equals(reference.getKey()))) continue;
            var assignment = drafts.assignWorkflowSlotParameters(owner, project, item, parameters,
                    prompt, before.durationSeconds(), before.capabilityId(), inputs,
                    reference.getValue(), reference.getKey(), null);
            if (assignment != null) parameters = assignment.parameters();
            inputs.add(new MediaDraftService.SaveMediaInput(reference.getKey(),
                    reference.getValue() == Artifact.Kind.AUDIO ? MediaDraft.InputRole.AUDIO_REFERENCE
                            : MediaDraft.InputRole.REFERENCE, REFERENCE_COLOR));
            owned.put(reference.getKey(), List.of());
        }
        if (inputs.stream().anyMatch(input -> before.mediaInputs().stream()
                .noneMatch(old -> old.versionId().equals(input.versionId())))) changes.add("REFERENCES_ADDED");
        String speaker = desiredSpeaker(after, kind);
        String previousSpeaker = before.parameters().path("speaker").asText();
        boolean ownsCurrentSpeaker = state.speakerId() != null && state.speakerId().equals(previousSpeaker);
        if (ownsCurrentSpeaker)
            parameters.remove("speaker");
        if (speaker != null) parameters.put("speaker", speaker);
        // Selecting the same existing voice does not transfer ownership of a user's setting.
        String ownedSpeaker = speaker != null && (ownsCurrentSpeaker || !speaker.equals(previousSpeaker)) ? speaker : null;
        if (!parameters.path("speaker").asText().equals(before.parameters().path("speaker").asText()))
            changes.add("SPEAKER_CHANGED");
        MediaDraft.VideoInputMode mode = mode(kind, before, inputs);
        if (mode != before.videoInputMode()) {
            changes.add("INPUT_MODE_CHANGED");
            if (mode == MediaDraft.VideoInputMode.GENERAL_REFERENCE) inputs = inputs.stream()
                    .map(input -> new MediaDraftService.SaveMediaInput(input.versionId(),
                            input.role() == MediaDraft.InputRole.START_FRAME || input.role() == MediaDraft.InputRole.END_FRAME
                                    ? MediaDraft.InputRole.REFERENCE : input.role(), input.color())).toList();
            mentions = mentions.stream().map(mention -> new MediaDraft.PromptMention(mention.versionId(),
                    mode == MediaDraft.VideoInputMode.GENERAL_REFERENCE
                            && (mention.role() == MediaDraft.InputRole.START_FRAME || mention.role() == MediaDraft.InputRole.END_FRAME)
                            ? MediaDraft.InputRole.REFERENCE : mention.role())).toList();
        }
        MediaDraft candidate = drafts.previewSave(owner, project, item, expectedDraftVersion,
                prompt, parameters, before.durationSeconds(), before.capabilityId(), mode,
                inputs, mentions, before.styleId());
        tasks.validateLibraryReference(owner, project, kind, candidate);
        return new Plan(candidate, new DraftState(Map.copyOf(owned), promptBlock, ownedSpeaker),
                warnings.stream().distinct().toList(), List.copyOf(changes), Map.copyOf(release));
    }

    @Override
    @Transactional
    public Applied apply(UUID owner, UUID project, UUID item, long expectedDraftVersion, Plan plan) {
        var target = items.requireArtifactItem(owner, project, item);
        tasks.requireUnoccupied(owner, project, target.subjectId(), item);
        MediaDraft candidate = plan.candidateDraft();
        long version = expectedDraftVersion;
        if (!plan.sourceIdsToRelease().isEmpty()) version = drafts.releaseInputSourcesWithinChange(
                owner, project, item, expectedDraftVersion, plan.sourceIdsToRelease()).version();
        MediaDraft saved = drafts.save(owner, project, item, version,
                candidate.prompt(), candidate.parameters(), candidate.durationSeconds(), candidate.capabilityId(),
                candidate.videoInputMode(), commands(candidate.mediaInputs()), candidate.mentions(), candidate.styleId());
        Map<UUID, List<UUID>> owned = new LinkedHashMap<>();
        for (var input : saved.mediaInputs()) if (plan.state().ownedReferenceSources().containsKey(input.versionId())) {
            List<UUID> previousOwnership = plan.state().ownedReferenceSources().get(input.versionId());
            List<UUID> sourceIds = input.sources().stream()
                    .filter(source -> source.type() == MediaDraft.SourceType.MANUAL
                            && (previousOwnership.isEmpty() || previousOwnership.contains(source.id())))
                    .map(MediaDraft.InputSource::id).toList();
            if (!sourceIds.isEmpty()) owned.put(input.versionId(), sourceIds);
        }
        return new Applied(saved, new DraftState(Map.copyOf(owned), plan.state().promptBlock(), plan.state().speakerId()));
    }

    private Map<UUID, Artifact.Kind> desiredReferences(List<CharacterSnapshot> snapshots,
            UUID owner, UUID project, Artifact.Kind targetKind, List<String> warnings) {
        Map<UUID, Artifact.Kind> references = new LinkedHashMap<>();
        for (var snapshot : snapshots) {
            if (snapshot.useAppearance()) for (var view : snapshot.look().views()) {
                artifacts.requireMediaVersionForTask(owner, project, view.versionId(), Artifact.Kind.IMAGE);
                references.put(view.versionId(), Artifact.Kind.IMAGE);
            }
            if (!snapshot.useVoice()) continue;
            var voice = snapshot.voice();
            if (voice == null || voice.versionId() == null && (voice.speakerId() == null || voice.speakerId().isBlank()))
                throw invalid();
            if (voice.versionId() != null) {
                artifacts.requireMediaVersionForTask(owner, project, voice.versionId(), Artifact.Kind.AUDIO);
                references.put(voice.versionId(), Artifact.Kind.AUDIO);
            }
            if (targetKind != Artifact.Kind.AUDIO && voice.speakerId() != null && !voice.speakerId().isBlank()) {
                if (voice.versionId() == null) throw invalid();
                warnings.add("SPEAKER_NOT_APPLIED");
            }
        }
        return references;
    }

    private String desiredSpeaker(List<CharacterSnapshot> snapshots, Artifact.Kind kind) {
        if (kind != Artifact.Kind.AUDIO) return null;
        List<String> speakers = snapshots.stream().filter(CharacterSnapshot::useVoice)
                .map(CharacterSnapshot::voice).filter(java.util.Objects::nonNull)
                .map(CharacterVoice::speakerId).filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (speakers.size() > 1) throw invalid();
        return speakers.isEmpty() ? null : speakers.getFirst();
    }

    private MediaDraft.VideoInputMode mode(Artifact.Kind kind, MediaDraft before,
            List<MediaDraftService.SaveMediaInput> inputs) {
        if (kind != Artifact.Kind.VIDEO || inputs.isEmpty()) return before.videoInputMode();
        boolean requiresGeneral = before.videoInputMode() != MediaDraft.VideoInputMode.START_END
                || inputs.stream().anyMatch(input -> input.role() != MediaDraft.InputRole.START_FRAME
                        && input.role() != MediaDraft.InputRole.END_FRAME);
        if (!requiresGeneral) return before.videoInputMode();
        var binding = capabilities.forDraft(before.capabilityId(), Task.Kind.VIDEO_GENERATION);
        if (!capabilities.inputPolicy(binding).supportedVideoInputModes().contains("GENERAL_REFERENCE")) throw invalid();
        return MediaDraft.VideoInputMode.GENERAL_REFERENCE;
    }

    private void pruneSlots(ObjectNode parameters, Set<UUID> removed, UUID capabilityId) {
        if (removed.isEmpty() || !(parameters.path(RunningHubDefinition.VALUES_PROPERTY) instanceof ObjectNode values)) return;
        // Only declared media slots may be changed. A user scalar that resembles a UUID is not a reference.
        Set<String> slots = capabilityId == null ? Set.of() : capabilities.declaredMediaSlotKeys(capabilityId);
        for (String slot : slots) if (removed.stream().anyMatch(id -> id.toString().equals(values.path(slot).asText())))
            values.remove(slot);
    }

    @Override
    public String describeSnapshots(List<CharacterSnapshot> snapshots) {
        if (snapshots.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\n\n").append(BLOCK_START);
        for (var snapshot : snapshots) {
            text.append("\nCharacter: ").append(snapshot.name());
            line(text, "Identity", snapshot.description());
            if (snapshot.useAppearance()) {
                line(text, "Look", snapshot.look().name());
                line(text, "Clothing", snapshot.look().clothing());
                line(text, "Expression", snapshot.look().expression());
                line(text, "Appearance", snapshot.look().description());
                for (var view : snapshot.look().views()) line(text, view.purpose(), view.description());
            }
            if (snapshot.useVoice() && snapshot.voice() != null) line(text, "Voice", snapshot.voice().description());
        }
        return text.append('\n').append(BLOCK_END).toString();
    }

    private void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) text.append('\n').append(label).append(": ").append(value);
    }

    private List<MediaDraftService.SaveMediaInput> commands(List<MediaDraft.MediaInput> inputs) {
        return inputs.stream().map(input -> new MediaDraftService.SaveMediaInput(input.versionId(), input.role(), input.color())).toList();
    }
    private ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "CHARACTER_INPUT_INCOMPATIBLE",
                ApiMessage.of("api.character.invalid"), ApiMessage.of("api.character.incompatible"), false);
    }
    private ApiProblemException conflict() {
        return new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                ApiMessage.of("api.character.conflict"), ApiMessage.of("api.character.conflict"), true);
    }
}
