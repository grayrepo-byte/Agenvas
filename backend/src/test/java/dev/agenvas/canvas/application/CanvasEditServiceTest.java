package dev.agenvas.canvas.application;

import static dev.agenvas.canvas.application.CanvasEditModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Exercises history semantics rather than mirroring SQL: sequential edits, conflicts and bounded preflight. */
class CanvasEditServiceTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-23T00:00:00Z");
    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final CanvasService canvas = mock(CanvasService.class);
    private final CanvasItemRepository items = mock(CanvasItemRepository.class);
    private final CanvasEditRepository repository = mock(CanvasEditRepository.class);
    private final CanvasCopySupport copies = mock(CanvasCopySupport.class);
    private final Map<UUID, CanvasItem> rows = new LinkedHashMap<>();
    private final Map<UUID, GroupState> groups = new LinkedHashMap<>();
    private final Map<UUID, ReceiptRow> receipts = new LinkedHashMap<>();
    private final Map<UUID, Command> commands = new LinkedHashMap<>();
    private final CanvasEditService service = new CanvasEditService(projects, events, canvas, items, repository,
            copies, mock(ArtifactService.class), mock(MediaDraftService.class), new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach void setup() {
        rows.put(id, item(0, false));
        when(events.recordChange(any(), any(), any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") Supplier<ProjectEventService.Change<Object>> mutation = call.getArgument(2);
            var change = mutation.get(); return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        when(items.list(owner, project)).thenAnswer(call -> List.copyOf(rows.values()));
        when(items.findForUpdate(any(), any(), any())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(2))));
        when(items.update(any(), any(), anyLong(), any())).thenAnswer(call -> {
            CanvasItem value = call.getArgument(1); long expected = call.getArgument(2);
            if (rows.get(value.id()).version() != expected) return false;
            rows.put(value.id(), version(value, expected + 1)); return true;
        });
        when(canvas.validateTitle(any())).thenAnswer(call -> ((String) call.getArgument(0)).trim());
        when(canvas.toEntry(any(), any())).thenAnswer(call -> new CanvasService.CanvasEntry(call.getArgument(1), null, null));
        when(repository.groups(project)).thenAnswer(call -> List.copyOf(groups.values()));
        when(repository.command(any(), any())).thenAnswer(call -> Optional.ofNullable(commands.get(call.getArgument(1))));
        when(repository.receipt(any(), any())).thenAnswer(call -> Optional.ofNullable(receipts.get(call.getArgument(1))));
        doAnswer(call -> { ReceiptRow value = call.getArgument(0); receipts.put(value.receipt().id(), value); return null; }).when(repository).insertReceipt(any());
        when(repository.updateReceipt(any(), anyLong())).thenAnswer(call -> { ReceiptRow value = call.getArgument(0); receipts.put(value.receipt().id(), value); return true; });
        doAnswer(call -> { commands.put(call.getArgument(1), new Command(call.getArgument(3), call.getArgument(2))); return null; }).when(repository).insertCommand(any(), any(), any(), any());
        when(repository.createGroup(any(), any())).thenAnswer(call -> { Group value = call.getArgument(0); return groups.putIfAbsent(value.id(), new GroupState(value, false)) == null; });
        when(repository.updateGroup(any(), anyLong(), any())).thenAnswer(call -> {
            GroupState value = call.getArgument(0); long expected = call.getArgument(1); Group g = value.group();
            groups.put(g.id(), new GroupState(new Group(g.id(), g.projectId(), g.name(), g.collapsed(), expected + 1), value.deleted())); return true;
        });
    }
    @Test void sequentialUndoAndRedoUsesPresentationSemanticsAcrossMonotonicCasVersions() {
        var first = service.edit(owner, project, display("First", 0));
        var second = service.edit(owner, project, display("Second", 1));
        service.replay(owner, project, second.receipt().id(), replay(0, Direction.UNDO));
        assertThat(rows.get(id).title()).isEqualTo("First");
        assertThat(rows.get(id).version()).isEqualTo(3);
        service.replay(owner, project, first.receipt().id(), replay(0, Direction.UNDO));
        assertThat(rows.get(id).title()).isEqualTo("Original");
        service.replay(owner, project, first.receipt().id(), replay(1, Direction.REDO));
        service.replay(owner, project, second.receipt().id(), replay(1, Direction.REDO));
        assertThat(rows.get(id).title()).isEqualTo("Second");
        assertThat(rows.get(id).version()).isEqualTo(6);
    }
    @Test void commandRetryReturnsCurrentStateWithoutRepeatingPresentationWrites() {
        Edit request = display("First", 0);
        var first = service.edit(owner, project, request);
        assertThat(service.edit(owner, project, request).receipt()).isEqualTo(first.receipt());
        assertThat(rows.get(id).version()).isEqualTo(1);
        assertThatThrownBy(() -> service.edit(owner, project, new Edit(request.commandId(), Operation.DISPLAY,
                List.of(change(rows.get(id), "Other", null)), List.of(), List.of())))
                .isInstanceOf(ApiProblemException.class);
    }
    @Test void changedPresentationRefusesUndoWithoutWriting() {
        var edit = service.edit(owner, project, display("First", 0));
        rows.put(id, rows.get(id).withTitle("External edit"));
        assertThatThrownBy(() -> service.replay(owner, project, edit.receipt().id(), replay(0, Direction.UNDO)))
                .isInstanceOf(ApiProblemException.class);
        assertThat(rows.get(id).title()).isEqualTo("External edit");
        assertThat(receipts.get(edit.receipt().id()).receipt().revision()).isZero();
    }
    @Test void groupCreateAndUngroupCanBeUndoneWithoutRestoringAnOldVersionNumber() {
        UUID group = UUID.randomUUID();
        var edit = service.edit(owner, project, new Edit(UUID.randomUUID(), Operation.GROUP,
                List.of(change(rows.get(id), "Original", group)),
                List.of(new GroupChange(GroupChangeType.UPSERT, group, 0, "  场景 一  ", true)), List.of()));
        assertThat(groups.get(group).group().name()).isEqualTo("场景 一");
        assertThat(rows.get(id).groupId()).isEqualTo(group);
        service.replay(owner, project, edit.receipt().id(), replay(0, Direction.UNDO));
        assertThat(rows.get(id).groupId()).isNull(); assertThat(groups.get(group).deleted()).isTrue();
        service.replay(owner, project, edit.receipt().id(), replay(1, Direction.REDO));
        assertThat(groups.get(group).group().version()).isEqualTo(2);
        assertThat(groups.get(group).deleted()).isFalse(); assertThat(rows.get(id).groupId()).isEqualTo(group);
    }
    @Test void lockedMemberRejectsWholeBatchBeforeWritingGroupsOrCallingCopySupport() {
        rows.put(id, item(0, true)); UUID group = UUID.randomUUID();
        assertThatThrownBy(() -> service.edit(owner, project, new Edit(UUID.randomUUID(), Operation.GROUP,
                List.of(change(rows.get(id), "Original", group)), List.of(new GroupChange(GroupChangeType.UPSERT, group, 0, "Group", false)), List.of())))
                .isInstanceOf(ApiProblemException.class);
        verify(repository, never()).createGroup(any(), any()); verifyNoInteractions(copies);
    }
    @Test void groupNameCountsUnicodeCodePointsAndRejectsBlankOrExcess() {
        assertThat(CanvasEditService.normalizeGroupName(" 😀 ")).isEqualTo("😀");
        assertThat(CanvasEditService.normalizeGroupName("😀".repeat(80))).hasSize(160);
        assertThatThrownBy(() -> CanvasEditService.normalizeGroupName("😀".repeat(81))).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> CanvasEditService.normalizeGroupName(" \u2003 ")).isInstanceOf(ApiProblemException.class);
    }
    @Test void oversizedBatchAndRepeatedTargetsFailBeforeAcquiringProjectLock() {
        List<ItemChange> tooMany = IntStream.range(0, 101).mapToObj(i -> change(item(0, false), "T", null)).toList();
        assertThatThrownBy(() -> service.edit(owner, project, new Edit(UUID.randomUUID(), Operation.DISPLAY, tooMany, List.of(), List.of())))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.edit(owner, project, new Edit(UUID.randomUUID(), Operation.DISPLAY,
                List.of(change(rows.get(id), "One", null), change(rows.get(id), "Two", null)), List.of(), List.of())))
                .isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(events);
    }
    @Test void presentationComparisonIgnoresNumericScaleAndCasButProtectsContentIdentity() {
        CanvasItem one = item(0, false), same = version(one, 99);
        assertThat(CanvasEditService.samePresentation(one, same)).isTrue();
        CanvasItem laterSelection = new CanvasItem(one.id(), one.projectId(), one.subjectType(), one.subjectId(), UUID.randomUUID(),
                one.title(), one.x(), one.y(), one.width(), one.height(), one.zIndex(), null, false, 1, now, now);
        assertThat(CanvasEditService.samePresentation(one, laterSelection)).isTrue();
        CanvasItem changed = new CanvasItem(one.id(), one.projectId(), one.subjectType(), UUID.randomUUID(), null,
                one.title(), one.x(), one.y(), one.width(), one.height(), one.zIndex(), null, false, 0, now, now);
        assertThat(CanvasEditService.samePresentation(one, changed)).isFalse();
    }
    private Edit display(String title, long version) { return new Edit(UUID.randomUUID(), Operation.DISPLAY,
            List.of(change(version(rows.get(id), version), title, null)), List.of(), List.of()); }
    private Replay replay(long version, Direction direction) { return new Replay(UUID.randomUUID(), version, direction); }
    private ItemChange change(CanvasItem item, String title, UUID group) { return new ItemChange(item.id(), item.version(), title,
            item.x(), item.y(), item.width(), item.height(), item.zIndex(), group, item.locked()); }
    private CanvasItem item(long version, boolean locked) { return new CanvasItem(id, project, CanvasItem.SubjectType.ARTIFACT,
            UUID.randomUUID(), null, "Original", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300"), new BigDecimal("200"), 0, null, locked, version, now, now); }
    private CanvasItem version(CanvasItem c, long version) { return new CanvasItem(c.id(), c.projectId(), c.subjectType(), c.subjectId(), c.selectedVersionId(),
            c.title(), c.x(), c.y(), c.width(), c.height(), c.zIndex(), c.groupId(), c.locked(), version, c.createdAt(), c.updatedAt()); }
}
