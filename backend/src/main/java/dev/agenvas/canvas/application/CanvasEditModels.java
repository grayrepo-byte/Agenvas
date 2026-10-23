package dev.agenvas.canvas.application;

import dev.agenvas.canvas.domain.CanvasItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Explicit presentation edits; creative content and execution are never replayed as commands. */
public final class CanvasEditModels {
    private CanvasEditModels() {}
    public enum Operation { DISPLAY, GROUP, DUPLICATE }
    public enum GroupChangeType { UPSERT, DELETE }
    public enum ReceiptState { APPLIED, UNDONE }
    public enum Direction { UNDO, REDO }
    public record Group(UUID id, UUID projectId, String name, boolean collapsed, long version) {}
    public record GroupState(Group group, boolean deleted) {}
    public record ItemChange(UUID itemId, long expectedVersion, String title, BigDecimal x,
            BigDecimal y, BigDecimal width, BigDecimal height, int zIndex, UUID groupId, boolean locked) {}
    public record GroupChange(GroupChangeType type, UUID groupId, long expectedVersion,
            String name, Boolean collapsed) {}
    public record Copy(UUID sourceItemId, UUID targetItemId, long expectedSourceVersion,
            Long expectedSourceDraftVersion, UUID expectedSourceContentVersion,
            BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height, int zIndex, UUID groupId) {}
    public record Edit(UUID commandId, Operation operation, List<ItemChange> itemChanges,
            List<GroupChange> groupChanges, List<Copy> copies) {}
    public record Replay(UUID commandId, long expectedRevision, Direction direction) {}
    public record Receipt(UUID id, Operation operation, long revision, ReceiptState state) {}
    public record Editor(List<CanvasService.CanvasEntry> items, List<Group> groups, Receipt receipt) {}
    /** Frozen clone content is private persistence, never returned in HTTP/SSE responses. */
    public record CopySnapshot(CanvasItem item, JsonNode frozen, JsonNode guard) {}
    public record History(List<CanvasItem> beforeItems, List<CanvasItem> afterItems,
            List<GroupState> beforeGroups, List<GroupState> afterGroups, List<CopySnapshot> copies) {}
    public record ReceiptRow(UUID projectId, Receipt receipt, History history, Instant createdAt) {}
    public record Command(String requestHash, UUID receiptId) {}
}
