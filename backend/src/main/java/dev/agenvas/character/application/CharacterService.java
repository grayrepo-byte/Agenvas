package dev.agenvas.character.application;

import static dev.agenvas.character.application.CharacterModels.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.character.application.CharacterDraftGateway.DraftState;
import dev.agenvas.character.application.CharacterDraftGateway.Plan;
import dev.agenvas.character.infrastructure.CharacterRepository;
import dev.agenvas.character.infrastructure.CharacterRepository.BindingRow;
import dev.agenvas.event.application.ProjectEventRecorded;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.DirectMediaTaskService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Stable characters are applied to a shot only through a fresh, explicitly confirmed draft plan. */
@Service
public class CharacterService {
    private static final int MAX_CHARACTERS = 200;
    private static final int MAX_SHOT_CHARACTERS = 20;
    private final CharacterRepository repository;
    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final CanvasItemQueryService canvas;
    private final MediaDraftService drafts;
    private final CharacterDraftGateway gateway;
    private final ProjectEventService events;
    private final ObjectProvider<DirectMediaTaskService> tasks;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CharacterService(CharacterRepository repository, ProjectService projects, ArtifactService artifacts,
            CanvasItemQueryService canvas, MediaDraftService drafts, CharacterDraftGateway gateway,
            ProjectEventService events, ObjectProvider<DirectMediaTaskService> tasks, ObjectMapper mapper, Clock clock) {
        this.repository = repository; this.projects = projects; this.artifacts = artifacts; this.canvas = canvas;
        this.drafts = drafts; this.gateway = gateway; this.events = events; this.tasks = tasks; this.mapper = mapper; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CharacterIndex list(UUID owner, UUID project) {
        projects.get(owner, project);
        List<CharacterProfile> profiles = repository.profiles(project);
        return new CharacterIndex(profiles, repository.bindings(project).stream()
                .map(row -> response(owner, row, profiles)).toList());
    }
    /** Trusted readers receive a bounded catalog; media bytes are never loaded by this method. */
    @Transactional(readOnly = true)
    public CharacterIndex listForAgent(UUID owner, UUID project) { return list(owner, project); }
    @Transactional(readOnly = true)
    public CharacterIndex exportData(UUID owner, UUID project) { return list(owner, project); }

    @Transactional
    public CharacterProfile save(UUID owner, UUID project, UUID character, SaveCharacter request) {
        projects.requireActiveProject(owner, project);
        requireCommand(request == null ? null : request.commandId());
        String hash = digest("save", character, request);
        return events.recordChange(owner, project, () -> {
            CharacterProfile replay = replay(project, request.commandId(), hash, CharacterProfile.class);
            if (replay != null) return ProjectEventService.Change.unchanged(replay);
            projects.requireActiveProject(owner, project);
            CharacterContent content = validateContent(owner, project, request.content());
            if (request.expectedVersion() < 0) throw invalid();
            CharacterProfile before = repository.profile(project, character).orElse(null);
            if (before != null && before.archived()) throw archived();
            if ((before == null ? 0 : before.version()) != request.expectedVersion()) throw conflict();
            if (before == null && repository.profiles(project).size() >= MAX_CHARACTERS) throw invalid();
            var now = clock.instant();
            CharacterProfile after = new CharacterProfile(character, project, content, request.expectedVersion() + 1,
                    false, before == null ? now : before.createdAt(), now);
            if (!(before == null ? repository.insertProfile(after) : repository.updateProfile(after, request.expectedVersion()))) throw conflict();
            for (UUID item : repository.affected(project, character)) repository.requireReview(project, item);
            repository.command(project, request.commandId(), hash, after, now);
            return ProjectEventService.Change.changed(after, event("character.profile.changed", character, after.version()));
        }).value();
    }

    @Transactional(readOnly = true)
    public CharacterChangePreview previewChange(UUID owner, UUID project, UUID character, SaveCharacter request) {
        projects.requireActiveProject(owner, project);
        if (request == null || request.expectedVersion() < 0) throw invalid();
        validateContent(owner, project, request.content());
        CharacterProfile before = repository.profile(project, character).orElse(null);
        if (before != null && before.archived()) throw archived();
        if ((before == null ? 0 : before.version()) != request.expectedVersion()) throw conflict();
        return new CharacterChangePreview(character, request.expectedVersion(), repository.affected(project, character).stream()
                .map(item -> new AffectedItem(item, canvas.requireArtifactItem(owner, project, item).title(),
                        repository.binding(project, item).orElseThrow().version())).toList());
    }

    @Transactional
    public CharacterProfile archive(UUID owner, UUID project, UUID character, ArchiveCharacter request) {
        projects.requireActiveProject(owner, project);
        requireCommand(request == null ? null : request.commandId());
        String hash = digest("archive", character, request);
        return events.recordChange(owner, project, () -> {
            CharacterProfile replay = replay(project, request.commandId(), hash, CharacterProfile.class);
            if (replay != null) return ProjectEventService.Change.unchanged(replay);
            projects.requireActiveProject(owner, project);
            CharacterProfile before = profile(project, character);
            if (before.archived()) throw archived();
            if (request.expectedVersion() != before.version()) throw conflict();
            CharacterProfile after = new CharacterProfile(character, project, before.content(), before.version() + 1,
                    true, before.createdAt(), clock.instant());
            if (!repository.updateProfile(after, before.version())) throw conflict();
            for (UUID item : repository.affected(project, character)) repository.requireReview(project, item);
            repository.command(project, request.commandId(), hash, after, clock.instant());
            return ProjectEventService.Change.changed(after, event("character.profile.changed", character, after.version()));
        }).value();
    }

    @Transactional(readOnly = true)
    public CharacterBindingPreview preview(UUID owner, UUID project, UUID item, PreviewCharacterBindings request) {
        projects.requireActiveProject(owner, project);
        return prepare(owner, project, item, request).response();
    }

    @Transactional
    public CharacterBindingApplied apply(UUID owner, UUID project, UUID item, ApplyCharacterBindings request) {
        projects.requireActiveProject(owner, project);
        requireCommand(request == null ? null : request.commandId());
        if (request.previewHash() == null || !request.previewHash().matches("[a-f0-9]{64}")) throw invalid();
        String hash = digest("apply", item, request);
        return events.recordChange(owner, project, () -> {
            CharacterBindingApplied replay = replay(project, request.commandId(), hash, CharacterBindingApplied.class);
            if (replay != null) return ProjectEventService.Change.unchanged(replay);
            Prepared prepared = prepare(owner, project, item, request.previewRequest());
            if (!prepared.response().previewHash().equals(request.previewHash())) throw conflict();
            var applied = gateway.apply(owner, project, item, request.expectedDraftVersion(), prepared.plan());
            BindingRow row = new BindingRow(project, item, request.expectedBindingVersion() + 1,
                    applied.draft().version(), prepared.response().snapshots(), applied.state(), true,
                    null, null, null, clock.instant());
            if (!repository.saveBinding(row, request.expectedBindingVersion())) throw conflict();
            CharacterBindingApplied response = new CharacterBindingApplied(response(owner, row, repository.profiles(project)), applied.draft());
            repository.command(project, request.commandId(), hash, response, clock.instant());
            // The normal draft save already advanced the same locked project event sequence.
            // Append the binding event at that new sequence and leave the outer lock scope unchanged.
            events.append(owner, project, event("character.bindings.changed", item, row.version()));
            return ProjectEventService.Change.unchanged(response);
        }).value();
    }

    @Transactional
    public CharacterBinding review(UUID owner, UUID project, UUID item, ReviewCharacterBindings request) {
        projects.requireActiveProject(owner, project);
        if (request == null || request.expectedBindingVersion() < 1 || request.expectedDraftVersion() < 0
                || request.selectedVersionId() == null) throw invalid();
        return events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            CanvasItem card = requireEditableMedia(owner, project, item);
            tasks.getObject().requireUnoccupied(owner, project, card.subjectId(), item);
            BindingRow row = repository.binding(project, item).orElseThrow(this::notFound);
            MediaDraft draft = drafts.get(owner, project, item);
            if (row.version() != request.expectedBindingVersion() || draft.version() != request.expectedDraftVersion()
                    || !Objects.equals(card.selectedVersionId(), request.selectedVersionId()) || !applicable(owner, row, draft)) throw conflict();
            for (CharacterSnapshot snapshot : row.snapshots()) {
                CharacterProfile profile = profile(project, snapshot.characterId());
                if (profile.archived()) throw archived();
                if (profile.version() != snapshot.characterVersion()) throw conflict();
            }
            if (!repository.review(project, item, row.version(), draft.version(), card.selectedVersionId(), clock.instant())) throw conflict();
            CharacterBinding result = response(owner, repository.binding(project, item).orElseThrow(), repository.profiles(project));
            return ProjectEventService.Change.changed(result, event("character.bindings.changed", item, result.version()));
        }).value();
    }

    /** Copies fixed continuity into an already-created draft in the caller's project lock.
     * A clone owns its newly created manual sources, never source-card or connection source IDs.
     */
    @Transactional
    public void copyBindingsWithinChange(UUID owner, UUID project, UUID sourceItem, UUID targetItem) {
        if (sourceItem.equals(targetItem)) return;
        projects.get(owner, project);
        BindingRow source = repository.binding(project, sourceItem).orElse(null);
        if (source == null || source.snapshots().isEmpty() || repository.binding(project, targetItem).isPresent()) return;
        requireMedia(owner, project, targetItem);
        MediaDraft target = drafts.get(owner, project, targetItem);
        // Late extra outputs use their accepted frozen draft, independent of the source's live edits.
        if (!applicable(owner, source, target)) return;
        MediaDraft currentSource = drafts.get(owner, project, sourceItem);
        Set<UUID> transferable = currentSource.mediaInputs().stream()
                .filter(input -> whollyOwned(input, source.state().ownedReferenceSources().get(input.versionId())))
                .map(MediaDraft.MediaInput::versionId).collect(java.util.stream.Collectors.toSet());
        java.util.Map<UUID, List<UUID>> owned = targetOwnership(target, transferable);
        DraftState state = new DraftState(java.util.Map.copyOf(owned), source.state().promptBlock(), source.state().speakerId());
        BindingRow copied = new BindingRow(project, targetItem, 1, target.version(), source.snapshots(), state,
                true, null, null, null, clock.instant());
        if (!repository.saveBinding(copied, 0)) throw conflict();
        events.append(owner, project, event("character.bindings.changed", targetItem, 1));
    }

    /** Restores accepted historical continuity on a late extra output without reading a live source binding. */
    @Transactional
    public void copyFrozenBindingsWithinChange(UUID owner, UUID project, UUID targetItem, JsonNode frozenMediaInput) {
        projects.get(owner, project);
        if (frozenMediaInput == null || !frozenMediaInput.path("characterBindings").isArray()
                || frozenMediaInput.path("characterBindings").isEmpty() || repository.binding(project, targetItem).isPresent()) return;
        requireMedia(owner, project, targetItem);
        MediaDraft target = drafts.get(owner, project, targetItem);
        List<CharacterSnapshot> snapshots = List.of(mapper.treeToValue(frozenMediaInput.path("characterBindings"), CharacterSnapshot[].class));
        CharacterContribution contribution = frozenMediaInput.path("characterContribution").isObject()
                ? mapper.treeToValue(frozenMediaInput.path("characterContribution"), CharacterContribution.class) : null;
        String block = contribution == null ? gateway.describeSnapshots(snapshots) : contribution.promptBlock();
        String speaker = contribution == null ? null : contribution.speakerId();
        DraftState expected = new DraftState(java.util.Map.of(), block == null ? "" : block, speaker);
        BindingRow historical = new BindingRow(project, targetItem, 1, target.version(), snapshots, expected,
                true, null, null, null, clock.instant());
        // Missing contribution evidence never grants removal ownership over restored manual inputs.
        Set<UUID> transferable = contribution == null || contribution.versionIds() == null || !applicable(owner, historical, target)
                ? Set.of() : Set.copyOf(contribution.versionIds());
        DraftState state = new DraftState(java.util.Map.copyOf(targetOwnership(target, transferable)), expected.promptBlock(), expected.speakerId());
        BindingRow copied = new BindingRow(project, targetItem, 1, target.version(), snapshots, state,
                true, null, null, null, clock.instant());
        if (!repository.saveBinding(copied, 0)) throw conflict();
        events.append(owner, project, event("character.bindings.changed", targetItem, 1));
    }

    /** Safe immutable Task evidence includes only contributions still owned by this feature. */
    @Transactional(readOnly = true)
    public CharacterContribution frozenContribution(UUID owner, UUID project, UUID item) {
        projects.get(owner, project);
        requireMedia(owner, project, item);
        BindingRow row = repository.binding(project, item).orElse(null);
        MediaDraft draft = drafts.get(owner, project, item);
        if (row == null || row.snapshots().isEmpty() || !applicable(owner, row, draft))
            return new CharacterContribution(List.of(), "", null);
        List<UUID> owned = draft.mediaInputs().stream()
                .filter(input -> whollyOwned(input, row.state().ownedReferenceSources().get(input.versionId())))
                .map(MediaDraft.MediaInput::versionId).sorted().toList();
        return new CharacterContribution(owned, row.state().promptBlock(), row.state().speakerId());
    }

    private boolean whollyOwned(MediaDraft.MediaInput input, List<UUID> ownedSources) {
        return ownedSources != null && !ownedSources.isEmpty() && !input.sources().isEmpty()
                && input.sources().stream().allMatch(source -> source.type() == MediaDraft.SourceType.MANUAL && ownedSources.contains(source.id()));
    }
    private java.util.Map<UUID, List<UUID>> targetOwnership(MediaDraft target, Set<UUID> transferable) {
        java.util.Map<UUID, List<UUID>> owned = new java.util.LinkedHashMap<>();
        for (var input : target.mediaInputs()) {
            if (!transferable.contains(input.versionId())) continue;
            List<UUID> sources = input.sources().stream().filter(source -> source.type() == MediaDraft.SourceType.MANUAL)
                    .map(MediaDraft.InputSource::id).toList();
            if (!sources.isEmpty()) owned.put(input.versionId(), sources);
        }
        return owned;
    }

    /** Freezes only the still-applicable applied snapshots, never freshly resolved character content. */
    @Transactional(readOnly = true)
    public JsonNode frozenBindings(UUID owner, UUID project, UUID item) {
        projects.get(owner, project);
        requireMedia(owner, project, item);
        BindingRow row = repository.binding(project, item).orElse(null);
        if (row == null || row.snapshots().isEmpty() || !applicable(owner, row, drafts.get(owner, project, item))) return mapper.createArrayNode();
        return mapper.valueToTree(row.snapshots());
    }

    /** A result switch away and back must still require another explicit review. */
    @EventListener
    public void onProjectEvent(ProjectEventRecorded recorded) {
        String type = recorded.event().type();
        if (type.equals("media.draft.changed") || type.equals("canvas.item.selected_version.changed"))
            repository.requireReview(recorded.event().projectId(), recorded.event().aggregateId());
    }

    private record Prepared(CharacterBindingPreview response, Plan plan) {}
    private Prepared prepare(UUID owner, UUID project, UUID item, PreviewCharacterBindings request) {
        if (request == null || request.expectedBindingVersion() < 0 || request.expectedDraftVersion() < 0
                || request.selections() == null || request.selections().size() > MAX_SHOT_CHARACTERS || request.clearMode() == null) throw invalid();
        requireEditableMedia(owner, project, item);
        BindingRow before = repository.binding(project, item).orElse(null);
        if ((before == null ? 0 : before.version()) != request.expectedBindingVersion()) throw conflict();
        if (drafts.get(owner, project, item).version() != request.expectedDraftVersion()) throw conflict();
        List<CharacterSnapshot> snapshots = snapshots(owner, project, request.selections());
        Plan plan = gateway.preview(owner, project, item, request.expectedDraftVersion(),
                before == null ? DraftState.empty() : before.state(), snapshots, request.clearMode());
        ObjectNode semantics = mapper.createObjectNode();
        semantics.set("request", mapper.valueToTree(request));
        semantics.set("snapshots", mapper.valueToTree(snapshots));
        // Fresh source UUIDs and timestamps are deliberately excluded from the confirmation digest.
        MediaDraft candidate = plan.candidateDraft();
        semantics.put("prompt", candidate.prompt()); semantics.set("parameters", candidate.parameters());
        semantics.set("durationSeconds", mapper.valueToTree(candidate.durationSeconds()));
        semantics.set("capabilityId", mapper.valueToTree(candidate.capabilityId())); semantics.set("styleId", mapper.valueToTree(candidate.styleId()));
        semantics.set("videoInputMode", mapper.valueToTree(candidate.videoInputMode()));
        var inputs = semantics.putArray("inputs");
        for (var input : candidate.mediaInputs()) {
            var value = inputs.addObject(); value.put("versionId", input.versionId().toString());
            value.put("role", input.role().name()); value.put("color", input.color());
        }
        semantics.set("mentions", mapper.valueToTree(candidate.mentions()));
        semantics.set("beforeState", mapper.valueToTree(before == null ? DraftState.empty() : before.state()));
        semantics.set("warnings", mapper.valueToTree(plan.warnings())); semantics.set("changes", mapper.valueToTree(plan.changes()));
        String previewHash = Sha256.hex(canonical(semantics));
        return new Prepared(new CharacterBindingPreview(previewHash, request.expectedBindingVersion(), request.expectedDraftVersion(),
                snapshots, candidate, plan.warnings(), plan.changes()), plan);
    }
    private List<CharacterSnapshot> snapshots(UUID owner, UUID project, List<CharacterBindingSelection> selections) {
        Set<UUID> seen = new HashSet<>();
        List<CharacterSnapshot> result = new ArrayList<>();
        for (CharacterBindingSelection selected : selections) {
            if (selected == null || selected.characterId() == null || selected.characterVersion() < 1
                    || !seen.add(selected.characterId()) || !selected.useAppearance() && !selected.useVoice()) throw invalid();
            CharacterProfile profile = profile(project, selected.characterId());
            if (profile.archived()) throw archived();
            if (selected.characterVersion() != profile.version()) throw conflict();
            CharacterLook look = selected.lookId() == null ? null : profile.content().looks().stream()
                    .filter(candidate -> candidate.id().equals(selected.lookId())).findFirst().orElseThrow(this::invalid);
            if (selected.useAppearance() && look == null || selected.useVoice() && profile.content().voice() == null) throw invalid();
            // Revalidate each exact same-project reference on every preview and apply, including archival.
            if (selected.useAppearance()) for (CharacterView view : look.views()) validateMedia(owner, project, view.versionId(), Artifact.Kind.IMAGE);
            if (selected.useVoice() && profile.content().voice().versionId() != null)
                validateMedia(owner, project, profile.content().voice().versionId(), Artifact.Kind.AUDIO);
            result.add(new CharacterSnapshot(profile.id(), profile.version(), profile.content().name(), profile.content().description(),
                    look, profile.content().voice(), selected.useAppearance(), selected.useVoice()));
        }
        return List.copyOf(result);
    }
    private CharacterBinding response(UUID owner, BindingRow row, List<CharacterProfile> profiles) {
        CanvasItem item = canvas.requireArtifactItem(owner, row.projectId(), row.canvasItemId());
        MediaDraft draft = drafts.get(owner, row.projectId(), row.canvasItemId());
        List<ReviewReason> reasons = new ArrayList<>();
        if (row.reviewedAt() == null) reasons.add(ReviewReason.NOT_REVIEWED);
        if (!applicable(owner, row, draft) || row.reviewedDraftVersion() != null && row.reviewedDraftVersion() != draft.version()) reasons.add(ReviewReason.DRAFT_CHANGED);
        if (row.reviewedAt() != null && !Objects.equals(row.reviewedSelectedVersionId(), item.selectedVersionId())) reasons.add(ReviewReason.RESULT_CHANGED);
        for (CharacterSnapshot snapshot : row.snapshots()) {
            CharacterProfile current = profiles.stream().filter(value -> value.id().equals(snapshot.characterId())).findFirst().orElse(null);
            if (current == null || current.version() != snapshot.characterVersion()) { if (!reasons.contains(ReviewReason.PROFILE_CHANGED)) reasons.add(ReviewReason.PROFILE_CHANGED); }
            if (current != null && current.archived() && !reasons.contains(ReviewReason.CHARACTER_ARCHIVED)) reasons.add(ReviewReason.CHARACTER_ARCHIVED);
        }
        // Persistent invalidation also covers a result switch followed by selecting the old result again.
        if (row.needsReview() && reasons.isEmpty()) reasons.add(ReviewReason.RESULT_CHANGED);
        CharacterContribution contribution = new CharacterContribution(row.state().ownedReferenceSources().keySet().stream().sorted().toList(),
                row.state().promptBlock(), row.state().speakerId());
        return new CharacterBinding(row.canvasItemId(), row.version(), draft.version(), item.selectedVersionId(), row.snapshots(),
                row.needsReview() || !reasons.isEmpty(), List.copyOf(reasons), contribution, row.reviewedAt());
    }
    private boolean applicable(UUID owner, BindingRow row, MediaDraft draft) {
        DraftState state = row.state();
        if (state.promptBlock() != null && !state.promptBlock().isBlank() && !draft.prompt().contains(state.promptBlock())) return false;
        if (state.speakerId() != null && !state.speakerId().equals(draft.parameters().path("speaker").asString(""))) return false;
        Set<UUID> references = draft.mediaInputs().stream().map(MediaDraft.MediaInput::versionId).collect(java.util.stream.Collectors.toSet());
        for (CharacterSnapshot snapshot : row.snapshots()) {
            if (snapshot.useAppearance() && snapshot.look() != null)
                for (CharacterView view : snapshot.look().views()) if (!references.contains(view.versionId())) return false;
            if (snapshot.useVoice() && snapshot.voice() != null && snapshot.voice().versionId() != null && !references.contains(snapshot.voice().versionId())) return false;
        }
        List<String> speakers = row.snapshots().stream().filter(CharacterSnapshot::useVoice)
                .map(CharacterSnapshot::voice).filter(Objects::nonNull).map(CharacterVoice::speakerId)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (!speakers.isEmpty()) {
            CanvasItem target = canvas.requireArtifactItem(owner, row.projectId(), draft.canvasItemId());
            if (artifacts.get(owner, row.projectId(), target.subjectId()).artifact().kind() == Artifact.Kind.AUDIO
                    && (speakers.size() != 1 || !speakers.getFirst().equals(draft.parameters().path("speaker").asString("")))) return false;
        }
        return true;
    }
    private CharacterContent validateContent(UUID owner, UUID project, CharacterContent content) {
        if (content == null || content.looks() == null || content.looks().size() > 20) throw invalid();
        String name = text(content.name(), 160, true); String description = text(content.description(), 4000, false);
        Set<UUID> ids = new HashSet<>(); List<CharacterLook> looks = new ArrayList<>();
        for (CharacterLook look : content.looks()) {
            if (look == null || look.id() == null || !ids.add(look.id()) || look.views() == null || look.views().size() > 14) throw invalid();
            List<CharacterView> views = new ArrayList<>(); Set<UUID> media = new HashSet<>();
            for (CharacterView view : look.views()) {
                if (view == null || view.versionId() == null || !media.add(view.versionId())) throw invalid();
                validateMedia(owner, project, view.versionId(), Artifact.Kind.IMAGE);
                views.add(new CharacterView(view.versionId(), text(view.purpose(), 120, true), text(view.description(), 2000, false)));
            }
            looks.add(new CharacterLook(look.id(), text(look.name(), 160, true), text(look.clothing(), 2000, false),
                    text(look.expression(), 2000, false), text(look.description(), 4000, false), List.copyOf(views)));
        }
        CharacterVoice voice = content.voice();
        if (voice != null) {
            String speaker = voice.speakerId() == null || voice.speakerId().isBlank() ? null : voice.speakerId().trim();
            if (speaker != null && !speaker.matches("[A-Za-z0-9_-]{1,120}")) throw invalid();
            if (voice.versionId() == null && speaker == null) throw invalid();
            if (voice.versionId() != null) validateMedia(owner, project, voice.versionId(), Artifact.Kind.AUDIO);
            voice = new CharacterVoice(voice.versionId(), speaker, text(voice.description(), 2000, false));
        }
        return new CharacterContent(name, description, List.copyOf(looks), voice);
    }
    private void validateMedia(UUID owner, UUID project, UUID version, Artifact.Kind kind) {
        var exact = artifacts.requireMediaVersionForTask(owner, project, version, kind);
        if (artifacts.get(owner, project, exact.artifactId()).artifact().archivedAt() != null) throw invalid();
    }
    private CanvasItem requireEditableMedia(UUID owner, UUID project, UUID item) {
        CanvasItem card = requireMedia(owner, project, item);
        if (card.locked()) throw new ApiProblemException(HttpStatus.CONFLICT, "CANVAS_ITEM_LOCKED",
                ApiMessage.of("api.creative-artifact-tool-service.card-locked"),
                ApiMessage.of("api.creative-artifact-tool-service.locked-cards-cannot-be-arranged-by-the-agent"), false);
        if (artifacts.get(owner, project, card.subjectId()).artifact().archivedAt() != null) throw archived();
        return card;
    }
    private CanvasItem requireMedia(UUID owner, UUID project, UUID item) {
        CanvasItem card = canvas.requireArtifactItem(owner, project, item);
        if (artifacts.get(owner, project, card.subjectId()).artifact().kind() == Artifact.Kind.TEXT) throw invalid();
        return card;
    }
    private CharacterProfile profile(UUID project, UUID character) { return repository.profile(project, character).orElseThrow(this::notFound); }
    private String text(String value, int max, boolean required) {
        if (value == null || value.length() > max || required && value.isBlank() || value.indexOf('\uFFFC') >= 0 || value.indexOf('\0') >= 0) throw invalid();
        return value.trim();
    }
    private void requireCommand(UUID command) { if (command == null) throw invalid(); }
    private <T> T replay(UUID project, UUID command, String hash, Class<T> type) {
        var saved = repository.command(project, command).orElse(null);
        if (saved == null) return null;
        if (!saved.requestHash().equals(hash)) throw conflict();
        return mapper.treeToValue(saved.result(), type);
    }
    private String digest(String kind, UUID target, Object request) {
        ObjectNode value = mapper.createObjectNode(); value.put("kind", kind); value.put("target", target.toString());
        value.set("request", mapper.valueToTree(request)); return Sha256.hex(canonical(value));
    }
    /** Stable recursive ordering is shared by command replay and preview confirmation. */
    private String canonical(JsonNode value) {
        if (value.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>(); value.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            return "{" + fields.entrySet().stream().map(entry -> mapper.writeValueAsString(entry.getKey()) + ":" + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",")) + "}";
        }
        if (value.isArray()) { List<String> values = new ArrayList<>(); value.forEach(element -> values.add(canonical(element))); return "[" + String.join(",", values) + "]"; }
        return mapper.writeValueAsString(value);
    }
    private ProjectEventService.EventDraft event(String type, UUID aggregate, long version) {
        ObjectNode payload = mapper.createObjectNode(); payload.put("id", aggregate.toString()); payload.put("version", version);
        return new ProjectEventService.EventDraft(type, 1, aggregate, version, payload);
    }
    private ApiProblemException invalid() { return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.character.invalid")); }
    private ApiProblemException conflict() { return problem(HttpStatus.CONFLICT, "CHARACTER_PREVIEW_CONFLICT", ApiMessage.of("api.character.conflict")); }
    private ApiProblemException archived() { return problem(HttpStatus.CONFLICT, "CHARACTER_ARCHIVED", ApiMessage.of("api.character.archived")); }
    private ApiProblemException notFound() { return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ApiMessage.of("api.project-service.the-project-does-not-exist-or-the-current-user-does")); }
    private ApiProblemException problem(HttpStatus status, String code, ApiMessage detail) {
        return new ApiProblemException(status, code, detail, detail, false);
    }
}
