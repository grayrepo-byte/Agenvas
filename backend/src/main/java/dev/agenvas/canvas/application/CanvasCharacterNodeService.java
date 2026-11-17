package dev.agenvas.canvas.application;

import static dev.agenvas.canvas.application.CanvasCharacterNodeModels.*;
import static dev.agenvas.character.application.CharacterModels.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.canvas.infrastructure.CanvasCharacterNodeRepository;
import dev.agenvas.canvas.infrastructure.CanvasCharacterNodeRepository.NodeRow;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Independent fixed character cards. Topology is intent; only explicit apply edits a draft. */
@Service
public class CanvasCharacterNodeService {
    private static final int MAX_GROUP_MEMBERS = 100;
    private static final int MAX_TARGET_CHARACTERS = 20;
    private static final int MAX_SELECTED_VIEWS = 14;
    private final CanvasCharacterNodeRepository repository;
    private final CanvasItemRepository items;
    private final ProjectService projects;
    private final CharacterService characters;
    private final ArtifactService artifacts;
    private final ProjectEventService events;
    private final ObjectProvider<CanvasService> canvas;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CanvasCharacterNodeService(
            CanvasCharacterNodeRepository repository,
            CanvasItemRepository items,
            ProjectService projects,
            CharacterService characters,
            ArtifactService artifacts,
            ProjectEventService events,
            ObjectProvider<CanvasService> canvas,
            ObjectMapper mapper,
            Clock clock) {
        this.repository = repository;
        this.items = items;
        this.projects = projects;
        this.characters = characters;
        this.artifacts = artifacts;
        this.events = events;
        this.canvas = canvas;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public CanvasService.CanvasEntry create(
            UUID owner, UUID project, UUID itemId, CreateNode request) {
        return events.recordChange(
                owner,
                project,
                () -> {
                    projects.requireActiveProject(owner, project);
                    if (request == null || itemId == null)
                        throw invalid();
                    var snapshot = snapshot(owner, project, request.selection(), true);
                    canvas.getObject()
                            .validateGeometry(
                                    request.x(),
                                    request.y(),
                                    request.width(),
                                    request.height(),
                                    request.zIndex());
                    CanvasItem before = items.findForUpdate(owner, project, itemId).orElse(null);
                    if (before != null) {
                        if (before.subjectType() != CanvasItem.SubjectType.CHARACTER)
                            throw conflict();
                        NodeRow row = row(project, itemId);
                        if (before.subjectType() != CanvasItem.SubjectType.CHARACTER
                                || before.version() != 0
                                || row.version() != 0
                                || !row.selection().equals(request.selection())
                                || !samePlacement(before, request))
                            throw conflict();
                        return ProjectEventService.Change.unchanged(entry(owner, before));
                    }
                    validateGroup(owner, project, request.groupId());
                    var now = clock.instant();
                    CanvasItem item = new CanvasItem(
                            itemId,
                            project,
                            CanvasItem.SubjectType.CHARACTER,
                            request.selection().characterId(),
                            null,
                            snapshot.name(),
                            request.x(),
                            request.y(),
                            request.width(),
                            request.height(),
                            request.zIndex(),
                            request.groupId(),
                            request.locked(),
                            0,
                            now,
                            now);
                    if (!items.create(item))
                        throw conflict();
                    repository.insert(project, itemId, request.selection(), 0);
                    return changed(entry(owner, item), project, itemId, 0);
                })
                .value();
    }

    @Transactional
    public CharacterNode update(UUID owner, UUID project, UUID itemId, UpdateSelection request) {
        return events.recordChange(
                owner,
                project,
                () -> {
                    projects.requireActiveProject(owner, project);
                    if (request == null
                            || request.expectedItemVersion() < 0
                            || request.expectedSelectionVersion() < 0)
                        throw invalid();
                    CanvasItem item = requireNode(owner, project, itemId, true);
                    editable(item);
                    if (item.version() != request.expectedItemVersion())
                        throw conflict();
                    NodeRow before = row(project, itemId);
                    snapshot(owner, project, request.selection(), true);
                    if (!item.subjectId().equals(request.selection().characterId()))
                        throw invalid();
                    if (before.selection().equals(request.selection())
                            && (before.version() == request.expectedSelectionVersion()
                                    || before.version() == request.expectedSelectionVersion() + 1))
                        return ProjectEventService.Change.unchanged(view(owner, item));
                    if (before.version() != request.expectedSelectionVersion()
                            || !repository.update(
                                    project, itemId, request.selection(), before.version()))
                        throw conflict();
                    return changed(
                            view(owner, item), project, itemId, before.version() + 1);
                })
                .value();
    }

    @Transactional(readOnly = true)
    public CharacterNode view(UUID owner, CanvasItem item) {
        projects.get(owner, item.projectId());
        if (item.subjectType() != CanvasItem.SubjectType.CHARACTER)
            throw invalid();
        NodeRow row = row(item.projectId(), item.id());
        CharacterSnapshot fixed = snapshot(owner, item.projectId(), row.selection(), false);
        boolean newer = characters.currentProfile(owner, item.projectId(), item.subjectId()).version() > row.selection()
                .characterVersion();
        return new CharacterNode(
                item.id(),
                row.selection(),
                fixed,
                row.version(),
                newer,
                connections(owner, item.projectId()).stream()
                        .filter(edge -> edge.sourceCanvasItemId().equals(item.id()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public List<CharacterConnection> connections(UUID owner, UUID project) {
        projects.get(owner, project);
        return repository.connections(project);
    }

    /** Read-only reconciliation survives archived projects, locked nodes and deleted edges. */
    @Transactional(readOnly = true)
    public CharacterConnection connectionReceipt(UUID owner, UUID project, UUID connectionId) {
        projects.get(owner, project);
        var receipt = repository
                .receipt(project, topologyCommand("connect", connectionId))
                .orElseThrow(this::notFound);
        if (!connectionId.toString().equals(receipt.result().path("id").asText()))
            throw notFound();
        return mapper.treeToValue(receipt.result(), CharacterConnection.class);
    }

    /** A retained application receipt belongs to its original target even after target deletion. */
    @Transactional(readOnly = true)
    public CharacterBindingApplied applicationReceipt(
            UUID owner, UUID project, UUID target, UUID commandId) {
        projects.get(owner, project);
        var receipt = repository.receipt(project, commandId).orElseThrow(this::notFound);
        if (!target.toString()
                .equals(receipt.result().path("binding").path("canvasItemId").asText())) {
            throw notFound();
        }
        return mapper.treeToValue(receipt.result(), CharacterBindingApplied.class);
    }

    @Transactional
    public CharacterConnection connect(UUID owner, UUID project, Connect request) {
        return events.recordChange(
                owner,
                project,
                () -> {
                    projects.requireActiveProject(owner, project);
                    if (request == null
                            || request.connectionId() == null
                            || request.sourceCanvasItemId() == null
                            || request.targetCanvasItemId() == null
                            || request.expectedSourceItemVersion() < 0
                            || request.expectedSourceSelectionVersion() < 0
                            || request.expectedTargetItemVersion() < 0)
                        throw invalid();
                    UUID receiptId = topologyCommand("connect", request.connectionId());
                    String requestHash = digest(request);
                    var replay = repository.receipt(project, receiptId).orElse(null);
                    if (replay != null) {
                        if (!requestHash.equals(replay.requestHash()))
                            throw conflict();
                        return ProjectEventService.Change.unchanged(
                                mapper.treeToValue(
                                        replay.result(), CharacterConnection.class));
                    }
                    if (repository
                            .receipt(
                                    project,
                                    topologyCommand("disconnect", request.connectionId()))
                            .isPresent())
                        throw conflict();
                    CanvasItem source = requireNode(owner, project, request.sourceCanvasItemId(), true);
                    CanvasItem target = requireTarget(
                            owner, project, request.targetCanvasItemId(), true);
                    editable(source);
                    editable(target);
                    if (source.version() != request.expectedSourceItemVersion()
                            || target.version() != request.expectedTargetItemVersion()
                            || row(project, source.id()).version() != request.expectedSourceSelectionVersion())
                        throw conflict();
                    CharacterConnection desired = new CharacterConnection(
                            request.connectionId(), source.id(), target.id(), 0);
                    var edges = repository.connections(project);
                    CharacterConnection prior = edges.stream()
                            .filter(edge -> edge.id().equals(desired.id()))
                            .findFirst()
                            .orElse(null);
                    if (prior != null) {
                        if (!prior.equals(desired))
                            throw conflict();
                        return ProjectEventService.Change.unchanged(prior);
                    }
                    if (edges.stream()
                            .anyMatch(
                                    edge -> edge.sourceCanvasItemId()
                                            .equals(source.id())
                                            && edge.targetCanvasItemId()
                                                    .equals(target.id()))
                            || edges.stream()
                                    .filter(
                                            edge -> edge.targetCanvasItemId()
                                                    .equals(target.id()))
                                    .count() >= MAX_TARGET_CHARACTERS)
                        throw invalid();
                    if (!repository.connect(project, desired))
                        throw conflict();
                    repository.receipt(project, receiptId, requestHash, desired);
                    return changed(
                            desired,
                            project,
                            source.id(),
                            row(project, source.id()).version());
                })
                .value();
    }

    @Transactional
    public void disconnect(UUID owner, UUID project, UUID id, Disconnect request) {
        events.recordChange(
                owner,
                project,
                () -> {
                    projects.requireActiveProject(owner, project);
                    if (request == null || id == null || request.expectedVersion() < 0)
                        throw invalid();
                    UUID receiptId = topologyCommand("disconnect", id);
                    String requestHash = digest(List.of(id, request));
                    var replay = repository.receipt(project, receiptId).orElse(null);
                    if (replay != null) {
                        if (!requestHash.equals(replay.requestHash()))
                            throw conflict();
                        return ProjectEventService.Change.unchanged(null);
                    }
                    CharacterConnection edge = repository.connections(project).stream()
                            .filter(e -> e.id().equals(id))
                            .findFirst()
                            .orElse(null);
                    if (edge == null) {
                        repository.receipt(project, receiptId, requestHash, true);
                        return ProjectEventService.Change.unchanged(null);
                    }
                    editable(requireNode(owner, project, edge.sourceCanvasItemId(), true));
                    editable(requireTarget(owner, project, edge.targetCanvasItemId(), true));
                    if (!repository.disconnect(project, id, request.expectedVersion()))
                        throw conflict();
                    repository.receipt(project, receiptId, requestHash, true);
                    return changed(null, project, edge.sourceCanvasItemId(), 0);
                });
    }

    @Transactional(readOnly = true)
    public NodeBindingPreview preview(UUID owner, UUID project, UUID target, Preview request) {
        projects.requireActiveProject(owner, project);
        return prepare(owner, project, target, request).preview();
    }

    @Transactional
    public CharacterBindingApplied apply(UUID owner, UUID project, UUID target, Apply request) {
        if (request == null
                || request.commandId() == null
                || request.previewHash() == null
                || !request.previewHash().matches("[a-f0-9]{64}"))
            throw invalid();
        String hash = digest(List.of(target, request));
        return events.recordChange(
                owner,
                project,
                () -> {
                    projects.requireActiveProject(owner, project);
                    var prior = repository.receipt(project, request.commandId()).orElse(null);
                    if (prior != null) {
                        if (!hash.equals(prior.requestHash()))
                            throw conflict();
                        return ProjectEventService.Change.unchanged(
                                mapper.treeToValue(
                                        prior.result(), CharacterBindingApplied.class));
                    }
                    Prepared prepared = prepare(owner, project, target, request.previewRequest());
                    if (!prepared.preview().previewHash().equals(request.previewHash()))
                        throw conflict();
                    var result = characters.applyFixedNodeBindings(
                            owner,
                            project,
                            target,
                            request.commandId(),
                            request.expectedBindingVersion(),
                            request.expectedDraftVersion(),
                            request.clearMode(),
                            prepared.snapshots(),
                            prepared.preview().characterPreview().previewHash());
                    repository.receipt(project, request.commandId(), hash, result);
                    return ProjectEventService.Change.unchanged(result);
                })
                .value();
    }

    private record Prepared(NodeBindingPreview preview, List<CharacterSnapshot> snapshots) {
    }

    private Prepared prepare(UUID owner, UUID project, UUID targetId, Preview request) {
        if (request == null
                || request.expectedBindingVersion() < 0
                || request.expectedDraftVersion() < 0
                || request.clearMode() == null)
            throw invalid();
        CanvasItem target = requireTarget(owner, project, targetId, false);
        editable(target);
        List<CharacterConnection> incoming = repository.connections(project).stream()
                .filter(e -> e.targetCanvasItemId().equals(targetId))
                .toList();
        List<CharacterSnapshot> fixed = new ArrayList<>();
        List<Object> guards = new ArrayList<>();
        HashSet<UUID> seen = new HashSet<>();
        guards.add(repository.topologyEpoch(project, targetId));
        guards.add(target.version());
        for (CharacterConnection edge : incoming) {
            CanvasItem source = requireNode(owner, project, edge.sourceCanvasItemId(), false);
            editable(source);
            NodeRow node = row(project, source.id());
            if (!seen.add(node.selection().characterId()))
                throw invalid();
            fixed.add(snapshot(owner, project, node.selection(), true));
            guards.add(
                    List.of(
                            edge,
                            source.version(),
                            node,
                            repository.topologyEpoch(project, source.id())));
        }
        var characterPreview = characters.previewFixedNodeBindings(
                owner,
                project,
                targetId,
                request.expectedBindingVersion(),
                request.expectedDraftVersion(),
                request.clearMode(),
                fixed);
        String hash = digest(List.of(targetId, request, guards, characterPreview.previewHash()));
        return new Prepared(
                new NodeBindingPreview(hash, characterPreview, incoming), List.copyOf(fixed));
    }

    /** Installs fixed node configuration before subgraph topology is restored by the canvas editor. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void installCopy(
            UUID owner, UUID project, UUID target, Selection selection, long version) {
        snapshot(owner, project, selection, true);
        repository.insert(project, target, selection, version);
    }

    @Transactional(readOnly = true)
    public JsonNode copyGuard(UUID owner, UUID project, CanvasItem item) {
        var guard = mapper.createObjectNode();
        guard.set("node", mapper.valueToTree(row(project, item.id())));
        guard.put("topologyEpoch", repository.topologyEpoch(project, item.id()));
        guard.set(
                "connections",
                mapper.valueToTree(
                        repository.connections(project).stream()
                                .filter(
                                        e -> e.sourceCanvasItemId().equals(item.id())
                                                || e.targetCanvasItemId().equals(item.id()))
                                .toList()));
        return guard;
    }

    private CanvasService.CanvasEntry entry(UUID owner, CanvasItem item) {
        return new CanvasService.CanvasEntry(item, null, null, view(owner, item));
    }

    private NodeRow row(UUID project, UUID item) {
        return repository.find(project, item).orElseThrow(this::notFound);
    }

    private CanvasItem requireNode(UUID owner, UUID project, UUID id, boolean lock) {
        CanvasItem item = (lock ? items.findForUpdate(owner, project, id) : items.find(owner, project, id))
                .orElseThrow(this::notFound);
        if (item.subjectType() != CanvasItem.SubjectType.CHARACTER)
            throw invalid();
        return item;
    }

    private CanvasItem requireTarget(UUID owner, UUID project, UUID id, boolean lock) {
        CanvasItem item = (lock ? items.findForUpdate(owner, project, id) : items.find(owner, project, id))
                .orElseThrow(this::notFound);
        if (item.subjectType() != CanvasItem.SubjectType.ARTIFACT)
            throw invalid();
        var artifact = artifacts.get(owner, project, item.subjectId()).artifact();
        if (artifact.archivedAt() != null
                || artifact.kind() != Artifact.Kind.IMAGE && artifact.kind() != Artifact.Kind.VIDEO)
            throw invalid();
        return item;
    }

    private CharacterSnapshot snapshot(
            UUID owner, UUID project, Selection selection, boolean editable) {
        if (selection == null
                || selection.characterId() == null
                || selection.characterVersion() < 1
                || selection.viewVersionIds() == null
                || selection.viewVersionIds().size() > MAX_SELECTED_VIEWS
                || selection.viewVersionIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(selection.viewVersionIds()).size() != selection.viewVersionIds().size()
                || !selection.useAppearance() && !selection.useVoice())
            throw invalid();
        CharacterProfile revision = characters.getRevision(
                owner, project, selection.characterId(), selection.characterVersion());
        if (editable
                && (revision.archived()
                        || characters
                                .currentProfile(owner, project, selection.characterId())
                                .archived()))
            throw invalid();
        CharacterLook look = selection.lookId() == null
                ? null
                : revision.content().looks().stream()
                        .filter(l -> l.id().equals(selection.lookId()))
                        .findFirst()
                        .orElseThrow(this::invalid);
        if (selection.useAppearance() && look == null && !revision.content().looks().isEmpty()
                || selection.useVoice() && revision.content().voice() == null
                || look == null && !selection.viewVersionIds().isEmpty())
            throw invalid();
        if (look != null) {
            CharacterLook base = look;
            List<CharacterView> selected = new ArrayList<>();
            for (UUID id : selection.viewVersionIds())
                selected.add(
                        base.views().stream()
                                .filter(v -> v.versionId().equals(id))
                                .findFirst()
                                .orElseThrow(this::invalid));
            look = new CharacterLook(
                    look.id(),
                    look.name(),
                    look.clothing(),
                    look.expression(),
                    look.description(),
                    List.copyOf(selected));
        }
        if (editable && selection.useAppearance() && look != null)
            for (CharacterView view : look.views())
                artifacts.requireMediaVersionForTask(
                        owner, project, view.versionId(), Artifact.Kind.IMAGE);
        if (editable
                && selection.useVoice()
                && revision.content().voice() != null
                && revision.content().voice().versionId() != null)
            artifacts.requireMediaVersionForTask(
                    owner, project, revision.content().voice().versionId(), Artifact.Kind.AUDIO);
        return new CharacterSnapshot(
                revision.id(),
                revision.version(),
                revision.content().name(),
                revision.content().description(),
                look,
                revision.content().voice(),
                selection.useAppearance(),
                selection.useVoice());
    }

    private void validateGroup(UUID owner, UUID project, UUID groupId) {
        if (groupId == null)
            return;
        if (!items.groupExists(owner, project, groupId))
            throw conflict();
        long members = items.list(owner, project).stream()
                .filter(item -> groupId.equals(item.groupId()))
                .count();
        if (members >= MAX_GROUP_MEMBERS)
            throw conflict();
    }

    private boolean samePlacement(CanvasItem item, CreateNode r) {
        return item.x().compareTo(r.x()) == 0
                && item.y().compareTo(r.y()) == 0
                && item.width().compareTo(r.width()) == 0
                && item.height().compareTo(r.height()) == 0
                && item.zIndex() == r.zIndex()
                && Objects.equals(item.groupId(), r.groupId())
                && item.locked() == r.locked();
    }

    private void editable(CanvasItem item) {
        if (item.locked())
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "CANVAS_ITEM_LOCKED",
                    ApiMessage.of("api.creative-artifact-tool-service.card-locked"),
                    ApiMessage.of("api.canvas-edit.locked"),
                    false);
    }

    private UUID topologyCommand(String operation, UUID connectionId) {
        return UUID.nameUUIDFromBytes(
                ("character-node:" + operation + ":" + connectionId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String digest(Object value) {
        return Sha256.hex(mapper.writeValueAsString(value));
    }

    private <T> ProjectEventService.Change<T> changed(
            T value, UUID project, UUID item, long version) {
        var payload = mapper.createObjectNode();
        payload.putArray("itemIds").add(item.toString());
        return ProjectEventService.Change.changed(
                value,
                new ProjectEventService.EventDraft(
                        "canvas.items.changed", 1, project, version, payload));
    }

    private ApiProblemException conflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "CHARACTER_NODE_VERSION_CONFLICT",
                ApiMessage.of("api.canvas-service.canvas-card-updated"),
                ApiMessage.of("api.canvas-edit.conflict"),
                false);
    }

    private ApiProblemException invalid() {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "CHARACTER_NODE_INVALID",
                ApiMessage.of("api.canvas-controller.canvas-command-is-invalid"),
                ApiMessage.of("api.character.invalid"),
                false);
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                ApiMessage.of("api.creative-artifact-tool-service.canvas-card-does-not-exist"),
                ApiMessage.of("api.canvas-edit.conflict"),
                false);
    }
}
