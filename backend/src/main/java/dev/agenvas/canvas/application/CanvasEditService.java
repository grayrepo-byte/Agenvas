package dev.agenvas.canvas.application;

import static dev.agenvas.canvas.application.CanvasEditModels.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;

/**
 * One project lock protects an atomic edit, its bounded receipt and its event. Receipts
 * replay presentation semantics, never media generation or user-authored content changes.
 */
@Service
public class CanvasEditService {
    public static final int MAX_ITEMS = 100;
    private static final int MAX_GROUPS = 100;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final CanvasService canvas;
    private final CanvasItemRepository items;
    private final CanvasEditRepository repository;
    private final CanvasCopySupport copies;
    private final ArtifactService artifacts;
    private final MediaDraftService drafts;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CanvasEditService(ProjectService projects, ProjectEventService events, CanvasService canvas,
            CanvasItemRepository items, CanvasEditRepository repository, CanvasCopySupport copies,
            ArtifactService artifacts, MediaDraftService drafts, ObjectMapper mapper, Clock clock) {
        this.projects = projects; this.events = events; this.canvas = canvas; this.items = items;
        this.repository = repository; this.copies = copies; this.artifacts = artifacts;
        this.drafts = drafts; this.mapper = mapper; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Editor get(UUID owner, UUID project) {
        projects.get(owner, project);
        return editor(owner, project, null);
    }

    /** Metadata-only application boundary for safe exports and other project projections. */
    @Transactional(readOnly = true)
    public List<Group> groups(UUID owner, UUID project) {
        projects.get(owner, project);
        return activeGroups(project);
    }

    @Transactional
    public Editor edit(UUID owner, UUID project, Edit request) {
        validateRequest(request);
        String hash = hash(request);
        return events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            var prior = repository.command(project, request.commandId());
            if (prior.isPresent()) return replayed(owner, project, prior.get(), hash);
            List<ItemChange> changes = list(request.itemChanges());
            List<GroupChange> groupChanges = list(request.groupChanges());
            List<Copy> copyRequests = list(request.copies());
            Map<UUID, CanvasItem> currentItems = itemMap(items.list(owner, project));
            Map<UUID, GroupState> currentGroups = groupMap(repository.groups(project));
            Map<UUID, CanvasItem> baselineItems = Map.copyOf(currentItems);
            Map<UUID, GroupState> baselineGroups = Map.copyOf(currentGroups);
            List<CanvasItem> beforeItems = new ArrayList<>();
            List<CanvasItem> afterItems = new ArrayList<>();
            List<GroupState> beforeGroups = new ArrayList<>();
            List<GroupState> afterGroups = new ArrayList<>();

            // Validate the entire batch before creating even one independent text artifact.
            for (GroupChange change : groupChanges) {
                if (change.type() == null || change.groupId() == null || change.expectedVersion() < 0) throw invalid();
                GroupState current = currentGroups.get(change.groupId());
                if (current != null && (current.deleted() || current.group().version() != change.expectedVersion())) throw conflict();
                if (current == null && (change.type() == GroupChangeType.DELETE || change.expectedVersion() != 0)) throw conflict();
                Group target = change.type() == GroupChangeType.DELETE ? current.group()
                        : new Group(change.groupId(), project, normalizeGroupName(change.name()),
                                requireCollapsed(change.collapsed()), current == null ? 0 : current.group().version());
                beforeGroups.add(current == null ? new GroupState(target, true) : current);
                GroupState after = new GroupState(target, change.type() == GroupChangeType.DELETE);
                afterGroups.add(after);
                currentGroups.put(target.id(), after);
            }
            for (ItemChange change : changes) {
                CanvasItem current = requireItem(owner, project, change.itemId());
                if (current.version() != change.expectedVersion()) throw conflict();
                canvas.validateGeometry(change.x(), change.y(), change.width(), change.height(), change.zIndex());
                CanvasItem target = presentation(current, canvas.validateTitle(change.title()), change.x(), change.y(),
                        change.width(), change.height(), change.zIndex(), change.groupId(), change.locked());
                if (current.locked() && !sameLayout(current, target)) throw locked();
                beforeItems.add(current); afterItems.add(target); currentItems.put(target.id(), target);
            }
            for (Copy copy : copyRequests) {
                CanvasItem source = requireItem(owner, project, copy.sourceItemId());
                if (source.version() != copy.expectedSourceVersion() || currentItems.containsKey(copy.targetItemId())) throw conflict();
                canvas.validateGeometry(copy.x(), copy.y(), copy.width(), copy.height(), copy.zIndex());
                var artifact = artifacts.get(owner, project, source.subjectId());
                if (artifact.artifact().kind() == Artifact.Kind.TEXT) {
                    UUID contentVersion = artifact.resourceDefaultVersion() == null ? null : artifact.resourceDefaultVersion().id();
                    if (!Objects.equals(contentVersion, copy.expectedSourceContentVersion())) throw conflict();
                } else if (copy.expectedSourceDraftVersion() == null ||
                        drafts.get(owner, project, source.id()).version() != copy.expectedSourceDraftVersion()) throw conflict();
                currentItems.put(copy.targetItemId(), new CanvasItem(copy.targetItemId(), project, source.subjectType(),
                        source.subjectId(), source.selectedVersionId(), source.title(), copy.x(), copy.y(), copy.width(),
                        copy.height(), copy.zIndex(), copy.groupId(), false, 0, clock.instant(), clock.instant()));
            }
            validateMembership(currentItems, currentGroups, baselineItems, baselineGroups);
            // Persist group rows first so the same-project FK also protects copies and moves.
            persistGroups(project, beforeGroups, afterGroups);
            for (CanvasItem target : afterItems) update(owner, target);
            List<CopySnapshot> snapshots = new ArrayList<>();
            for (Copy copy : copyRequests) {
                CanvasItem source = requireItem(owner, project, copy.sourceItemId());
                var prepared = copies.prepareCloneWithinChange(owner, project, source, copy.targetItemId());
                CanvasItem layout = currentItems.get(copy.targetItemId());
                CanvasItem target = new CanvasItem(layout.id(), project, layout.subjectType(), prepared.subjectId(),
                        prepared.selectedVersionId(), layout.title(), layout.x(), layout.y(), layout.width(), layout.height(),
                        layout.zIndex(), layout.groupId(), false, 0, clock.instant(), clock.instant());
                if (!items.create(target)) throw conflict();
                if (target.selectedVersionId() != null) items.addMediaVersion(project, target.id(), target.selectedVersionId(), clock.instant());
                copies.installWithinChange(owner, project, target, prepared.frozen());
                snapshots.add(new CopySnapshot(target, prepared.frozen(), copies.capture(owner, project, target)));
            }
            // Freeze authoritative post-write versions for receipt diagnostics; replay uses semantics.
            afterItems = afterItems.stream().map(item -> requireItem(owner, project, item.id())).toList();
            afterGroups = refreshedGroups(project, afterGroups);
            Receipt receipt = new Receipt(request.commandId(), request.operation(), 0, ReceiptState.APPLIED);
            History history = new History(List.copyOf(beforeItems), afterItems, List.copyOf(beforeGroups), afterGroups, List.copyOf(snapshots));
            repository.insertReceipt(new ReceiptRow(project, receipt, history, clock.instant()));
            repository.insertCommand(project, request.commandId(), receipt.id(), hash);
            repository.prune(project);
            return changed(owner, project, receipt);
        }).value();
    }

    @Transactional
    public Editor replay(UUID owner, UUID project, UUID receiptId, Replay request) {
        if (request == null || request.commandId() == null || request.direction() == null || request.expectedRevision() < 0) throw invalid();
        String hash = hash(List.of(receiptId, request));
        return events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            var prior = repository.command(project, request.commandId());
            if (prior.isPresent()) return replayed(owner, project, prior.get(), hash);
            ReceiptRow row = repository.receipt(project, receiptId).orElseThrow(this::expired);
            Receipt receipt = row.receipt();
            boolean undo = request.direction() == Direction.UNDO;
            if (receipt.revision() != request.expectedRevision() ||
                    receipt.state() != (undo ? ReceiptState.APPLIED : ReceiptState.UNDONE)) throw conflict();
            History history = row.history();
            List<CanvasItem> expectedItems = undo ? history.afterItems() : history.beforeItems();
            List<CanvasItem> desiredItems = undo ? history.beforeItems() : history.afterItems();
            List<GroupState> expectedGroups = undo ? history.afterGroups() : history.beforeGroups();
            List<GroupState> desiredGroups = undo ? history.beforeGroups() : history.afterGroups();
            Map<UUID, CanvasItem> currentItems = itemMap(items.list(owner, project));
            Map<UUID, GroupState> currentGroups = groupMap(repository.groups(project));
            Map<UUID, CanvasItem> baselineItems = Map.copyOf(currentItems);
            Map<UUID, GroupState> baselineGroups = Map.copyOf(currentGroups);
            for (CanvasItem expected : expectedItems) {
                CanvasItem current = requireItem(owner, project, expected.id());
                if (!samePresentation(current, expected)) throw conflict();
            }
            for (GroupState expected : expectedGroups) {
                GroupState current = currentGroups.get(expected.group().id());
                if (!sameGroup(current, expected)) throw conflict();
            }
            // Clone undo may delete only the exact untouched working branch we created.
            List<CopySnapshot> copySnapshots = new ArrayList<>();
            for (CopySnapshot copy : history.copies()) {
                if (undo) {
                    CanvasItem current = requireItem(owner, project, copy.item().id());
                    if (!samePresentation(current, copy.item()) || !sameJson(copies.capture(owner, project, current), copy.guard())) throw conflict();
                    currentItems.remove(current.id());
                    copySnapshots.add(new CopySnapshot(withVersion(copy.item(), current.version() + 1), copy.frozen(), copy.guard()));
                } else {
                    if (currentItems.containsKey(copy.item().id())) throw conflict();
                    currentItems.put(copy.item().id(), copy.item());
                    copySnapshots.add(copy);
                }
            }
            List<CanvasItem> replacements = new ArrayList<>();
            for (CanvasItem desired : desiredItems) {
                CanvasItem current = requireItem(owner, project, desired.id());
                CanvasItem target = presentation(current, desired.title(), desired.x(), desired.y(), desired.width(),
                        desired.height(), desired.zIndex(), desired.groupId(), desired.locked());
                // Explicitly replaying SET_LOCKED may unlock; other movement still respects current locks.
                if (current.locked() && !sameLayout(current, target)) throw locked();
                replacements.add(target); currentItems.put(target.id(), target);
            }
            for (GroupState desired : desiredGroups) currentGroups.put(desired.group().id(), desired);
            validateMembership(currentItems, currentGroups, baselineItems, baselineGroups);
            persistGroups(project, expectedGroups, desiredGroups);
            for (CanvasItem target : replacements) update(owner, target);
            List<CopySnapshot> nextCopies = new ArrayList<>();
            for (CopySnapshot copy : copySnapshots) {
                if (undo) {
                    CanvasItem current = requireItem(owner, project, copy.item().id());
                    // Capture proved no topology exists; do not perform destructive connection cleanup.
                    if (!items.delete(owner, project, current.id(), current.version())) throw conflict();
                    nextCopies.add(copy);
                } else {
                    if (!items.create(copy.item())) throw conflict();
                    if (copy.item().selectedVersionId() != null)
                        items.addMediaVersion(project, copy.item().id(), copy.item().selectedVersionId(), clock.instant());
                    copies.restoreWithinChange(owner, project, copy.item(), copy.frozen(), copy.guard());
                    nextCopies.add(new CopySnapshot(copy.item(), copy.frozen(), copies.capture(owner, project, copy.item())));
                }
            }
            Receipt next = new Receipt(receipt.id(), receipt.operation(), receipt.revision() + 1,
                    undo ? ReceiptState.UNDONE : ReceiptState.APPLIED);
            History nextHistory = new History(history.beforeItems(), history.afterItems(), history.beforeGroups(), history.afterGroups(), List.copyOf(nextCopies));
            if (!repository.updateReceipt(new ReceiptRow(project, next, nextHistory, row.createdAt()), receipt.revision())) throw conflict();
            repository.insertCommand(project, request.commandId(), receiptId, hash);
            repository.prune(project);
            return changed(owner, project, next);
        }).value();
    }

    private ProjectEventService.Change<Editor> replayed(UUID owner, UUID project, Command prior, String hash) {
        if (!prior.requestHash().equals(hash)) throw conflict();
        Receipt receipt = repository.receipt(project, prior.receiptId()).orElseThrow(this::expired).receipt();
        return ProjectEventService.Change.unchanged(editor(owner, project, receipt));
    }
    private ProjectEventService.Change<Editor> changed(UUID owner, UUID project, Receipt receipt) {
        var payload = mapper.createObjectNode().put("receiptId", receipt.id().toString())
                .put("operation", receipt.operation().name()).put("state", receipt.state().name()).put("revision", receipt.revision());
        return ProjectEventService.Change.changed(editor(owner, project, receipt),
                new ProjectEventService.EventDraft("canvas.edited", 1, receipt.id(), receipt.revision(), payload));
    }
    private Editor editor(UUID owner, UUID project, Receipt receipt) {
        return new Editor(items.list(owner, project).stream().map(item -> canvas.toEntry(owner, item)).toList(),
                activeGroups(project), receipt);
    }
    private List<Group> activeGroups(UUID project) {
        return repository.groups(project).stream().filter(group -> !group.deleted()).map(GroupState::group).toList();
    }
    private CanvasItem requireItem(UUID owner, UUID project, UUID id) {
        if (id == null) throw invalid();
        return items.findForUpdate(owner, project, id).orElseThrow(this::conflict);
    }
    private void update(UUID owner, CanvasItem target) {
        if (!items.update(owner, target, target.version(), clock.instant())) throw conflict();
    }
    private void persistGroups(UUID project, List<GroupState> before, List<GroupState> desired) {
        Map<UUID, GroupState> current = groupMap(repository.groups(project));
        for (GroupState value : desired) {
            GroupState existing = current.get(value.group().id());
            if (existing == null) {
                if (value.deleted() || !repository.createGroup(value.group(), clock.instant())) throw conflict();
            } else if (!repository.updateGroup(value, existing.group().version(), clock.instant())) throw conflict();
        }
    }
    private List<GroupState> refreshedGroups(UUID project, List<GroupState> values) {
        Map<UUID, GroupState> current = groupMap(repository.groups(project));
        return values.stream().map(value -> current.get(value.group().id())).toList();
    }
    private void validateMembership(Map<UUID, CanvasItem> values, Map<UUID, GroupState> groups,
            Map<UUID, CanvasItem> baselineItems, Map<UUID, GroupState> baselineGroups) {
        Map<UUID, Integer> baselineCounts = new HashMap<>();
        baselineItems.values().stream().filter(item -> item.groupId() != null)
                .forEach(item -> baselineCounts.merge(item.groupId(), 1, Integer::sum));
        Map<UUID, Integer> counts = new HashMap<>();
        for (CanvasItem item : values.values()) {
            if (item.groupId() == null) continue;
            GroupState group = groups.get(item.groupId());
            if (group == null || group.deleted() || !group.group().projectId().equals(item.projectId())) throw conflict();
            int count = counts.merge(item.groupId(), 1, Integer::sum);
            // Legacy Agent tags may already contain more than 100 nodes. Preserve them
            // without blocking unrelated editing, while rejecting any further growth.
            if (count > MAX_ITEMS && count > baselineCounts.getOrDefault(item.groupId(), 0)) throw limit();
        }
        long active = groups.values().stream().filter(group -> !group.deleted()).count();
        if (active > MAX_GROUPS && active > baselineGroups.values().stream().filter(group -> !group.deleted()).count()) throw limit();
    }
    private void validateRequest(Edit request) {
        if (request == null || request.commandId() == null || request.operation() == null) throw invalid();
        List<ItemChange> changes = list(request.itemChanges()); List<GroupChange> groups = list(request.groupChanges()); List<Copy> copy = list(request.copies());
        if (changes.size() + copy.size() > MAX_ITEMS || groups.size() > MAX_GROUPS || changes.isEmpty() && groups.isEmpty() && copy.isEmpty()) throw limit();
        if (request.operation() != Operation.DUPLICATE && !copy.isEmpty() || request.operation() == Operation.DISPLAY && !groups.isEmpty()) throw invalid();
        if (request.operation() == Operation.DUPLICATE && copy.isEmpty()) throw invalid();
        Set<UUID> itemIds = new HashSet<>(); Set<UUID> groupIds = new HashSet<>(); Set<UUID> sourceIds = new HashSet<>();
        for (ItemChange change : changes) if (change == null || change.itemId() == null || change.expectedVersion() < 0 || !itemIds.add(change.itemId())) throw invalid();
        for (Copy value : copy) if (value == null || value.sourceItemId() == null || value.targetItemId() == null || value.expectedSourceVersion() < 0 ||
                !itemIds.add(value.targetItemId()) || !sourceIds.add(value.sourceItemId())) throw invalid();
        // Source changes in the same command would make the frozen semantics ambiguous.
        for (UUID source : sourceIds) if (itemIds.contains(source)) throw invalid();
        for (GroupChange group : groups) if (group == null || group.groupId() == null || !groupIds.add(group.groupId())) throw invalid();
    }
    static String normalizeGroupName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 80) throw invalidStatic();
        return value;
    }
    private static boolean requireCollapsed(Boolean value) { if (value == null) throw invalidStatic(); return value; }
    static boolean samePresentation(CanvasItem a, CanvasItem b) {
        return a.projectId().equals(b.projectId()) && a.subjectId().equals(b.subjectId()) && a.subjectType() == b.subjectType()
                && a.title().equals(b.title())
                && sameLayout(a, b) && a.locked() == b.locked();
    }
    static boolean sameLayout(CanvasItem a, CanvasItem b) {
        return a.x().compareTo(b.x()) == 0 && a.y().compareTo(b.y()) == 0 && a.width().compareTo(b.width()) == 0
                && a.height().compareTo(b.height()) == 0 && a.zIndex() == b.zIndex() && Objects.equals(a.groupId(), b.groupId());
    }
    private static boolean sameGroup(GroupState a, GroupState b) {
        if (a == null) return b.deleted();
        return a.deleted() == b.deleted() && (a.deleted() || a.group().name().equals(b.group().name()) && a.group().collapsed() == b.group().collapsed());
    }
    private boolean sameJson(tools.jackson.databind.JsonNode left, tools.jackson.databind.JsonNode right) {
        return mapper.readTree(left.toString()).equals(mapper.readTree(right.toString()));
    }
    private static CanvasItem presentation(CanvasItem c, String title, java.math.BigDecimal x, java.math.BigDecimal y,
            java.math.BigDecimal width, java.math.BigDecimal height, int z, UUID group, boolean locked) {
        return new CanvasItem(c.id(), c.projectId(), c.subjectType(), c.subjectId(), c.selectedVersionId(), title,
                x, y, width, height, z, group, locked, c.version(), c.createdAt(), c.updatedAt());
    }
    private static CanvasItem withVersion(CanvasItem c, long version) {
        return new CanvasItem(c.id(), c.projectId(), c.subjectType(), c.subjectId(), c.selectedVersionId(), c.title(),
                c.x(), c.y(), c.width(), c.height(), c.zIndex(), c.groupId(), c.locked(), version, c.createdAt(), c.updatedAt());
    }
    private static Map<UUID, CanvasItem> itemMap(List<CanvasItem> values) { Map<UUID, CanvasItem> result = new LinkedHashMap<>(); values.forEach(value -> result.put(value.id(), value)); return result; }
    private static Map<UUID, GroupState> groupMap(List<GroupState> values) { Map<UUID, GroupState> result = new LinkedHashMap<>(); values.forEach(value -> result.put(value.group().id(), value)); return result; }
    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private ApiProblemException conflict() { return new ApiProblemException(HttpStatus.CONFLICT, "CANVAS_EDIT_CONFLICT", ApiMessage.of("api.canvas-service.canvas-card-updated"), ApiMessage.of("api.canvas-edit.conflict"), false); }
    private ApiProblemException locked() { return new ApiProblemException(HttpStatus.CONFLICT, "CANVAS_ITEM_LOCKED", ApiMessage.of("api.creative-artifact-tool-service.card-locked"), ApiMessage.of("api.canvas-edit.locked"), false); }
    private ApiProblemException expired() { return new ApiProblemException(HttpStatus.CONFLICT, "CANVAS_EDIT_EXPIRED", ApiMessage.of("api.canvas-service.canvas-card-updated"), ApiMessage.of("api.canvas-edit.expired"), false); }
    private ApiProblemException invalid() { return invalidStatic(); }
    private static ApiProblemException invalidStatic() { return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.canvas-controller.canvas-command-is-invalid"), ApiMessage.of("api.canvas-edit.invalid"), false); }
    private ApiProblemException limit() { return new ApiProblemException(HttpStatus.BAD_REQUEST, "CANVAS_EDIT_LIMIT", ApiMessage.of("api.canvas-controller.canvas-command-is-invalid"), ApiMessage.of("api.canvas-edit.limit"), false); }
}
