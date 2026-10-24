package dev.agenvas.canvas.infrastructure;

import static dev.agenvas.db.Tables.CANVAS_GROUP;
import static dev.agenvas.db.Tables.CANVAS_EDIT_RECEIPT;
import static dev.agenvas.db.Tables.CANVAS_EDIT_COMMAND;
import static dev.agenvas.canvas.application.CanvasEditModels.*;
import dev.agenvas.canvas.application.CanvasEditRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** All group, receipt and idempotency changes participate in the caller's short transaction. */
@Repository
public class JooqCanvasEditRepository implements CanvasEditRepository {
    private static final int RETAINED_RECEIPTS = 100;
    private final DSLContext db;
    private final ObjectMapper mapper;
    public JooqCanvasEditRepository(DSLContext db, ObjectMapper mapper) { this.db = db; this.mapper = mapper; }
    public List<GroupState> groups(UUID project) {
        return db.selectFrom(CANVAS_GROUP).where(CANVAS_GROUP.PROJECT_ID.eq(project))
                .orderBy(CANVAS_GROUP.CREATED_AT, CANVAS_GROUP.ID).fetch(row -> new GroupState(
                        new Group(row.getId(), project, row.getName(), row.getCollapsed(), row.getVersion()), row.getDeleted()));
    }
    public boolean createGroup(Group group, Instant now) {
        return db.insertInto(CANVAS_GROUP).set(CANVAS_GROUP.PROJECT_ID, group.projectId()).set(CANVAS_GROUP.ID, group.id())
                .set(CANVAS_GROUP.NAME, group.name()).set(CANVAS_GROUP.COLLAPSED, group.collapsed())
                .set(CANVAS_GROUP.DELETED, false).set(CANVAS_GROUP.VERSION, group.version())
                .set(CANVAS_GROUP.CREATED_AT, at(now)).set(CANVAS_GROUP.UPDATED_AT, at(now))
                .onConflict(CANVAS_GROUP.PROJECT_ID, CANVAS_GROUP.ID).doNothing().execute() == 1;
    }
    public boolean updateGroup(GroupState state, long expected, Instant now) {
        Group group = state.group();
        return db.update(CANVAS_GROUP).set(CANVAS_GROUP.NAME, group.name()).set(CANVAS_GROUP.COLLAPSED, group.collapsed())
                .set(CANVAS_GROUP.DELETED, state.deleted()).set(CANVAS_GROUP.VERSION, expected + 1)
                .set(CANVAS_GROUP.UPDATED_AT, at(now)).where(CANVAS_GROUP.PROJECT_ID.eq(group.projectId()))
                .and(CANVAS_GROUP.ID.eq(group.id())).and(CANVAS_GROUP.VERSION.eq(expected)).execute() == 1;
    }
    public Optional<ReceiptRow> receipt(UUID project, UUID id) {
        return db.selectFrom(CANVAS_EDIT_RECEIPT).where(CANVAS_EDIT_RECEIPT.PROJECT_ID.eq(project))
                .and(CANVAS_EDIT_RECEIPT.ID.eq(id)).fetchOptional(row -> new ReceiptRow(project,
                        new Receipt(id, Operation.valueOf(row.getOperation()), row.getRevision(), ReceiptState.valueOf(row.getState())),
                        mapper.treeToValue(mapper.readTree(row.getContentJson().data()).path("content"), History.class), row.getCreatedAt().toInstant()));
    }
    public void insertReceipt(ReceiptRow row) {
        if (db.insertInto(CANVAS_EDIT_RECEIPT).set(CANVAS_EDIT_RECEIPT.PROJECT_ID, row.projectId())
                .set(CANVAS_EDIT_RECEIPT.ID, row.receipt().id()).set(CANVAS_EDIT_RECEIPT.OPERATION, row.receipt().operation().name())
                .set(CANVAS_EDIT_RECEIPT.STATE, row.receipt().state().name()).set(CANVAS_EDIT_RECEIPT.REVISION, row.receipt().revision())
                .set(CANVAS_EDIT_RECEIPT.CONTENT_JSON, json(row.history())).set(CANVAS_EDIT_RECEIPT.CREATED_AT, at(row.createdAt()))
                .execute() != 1) throw new IllegalStateException("Canvas edit receipt insert failed");
    }
    public boolean updateReceipt(ReceiptRow row, long expected) {
        return db.update(CANVAS_EDIT_RECEIPT).set(CANVAS_EDIT_RECEIPT.STATE, row.receipt().state().name())
                .set(CANVAS_EDIT_RECEIPT.REVISION, row.receipt().revision()).set(CANVAS_EDIT_RECEIPT.CONTENT_JSON, json(row.history()))
                .where(CANVAS_EDIT_RECEIPT.PROJECT_ID.eq(row.projectId())).and(CANVAS_EDIT_RECEIPT.ID.eq(row.receipt().id()))
                .and(CANVAS_EDIT_RECEIPT.REVISION.eq(expected)).execute() == 1;
    }
    public Optional<Command> command(UUID project, UUID id) {
        return db.selectFrom(CANVAS_EDIT_COMMAND).where(CANVAS_EDIT_COMMAND.PROJECT_ID.eq(project))
                .and(CANVAS_EDIT_COMMAND.COMMAND_ID.eq(id)).fetchOptional(row -> new Command(row.getRequestHash(), row.getReceiptId()));
    }
    public void insertCommand(UUID project, UUID command, UUID receipt, String hash) {
        if (db.insertInto(CANVAS_EDIT_COMMAND).set(CANVAS_EDIT_COMMAND.PROJECT_ID, project)
                .set(CANVAS_EDIT_COMMAND.COMMAND_ID, command).set(CANVAS_EDIT_COMMAND.RECEIPT_ID, receipt)
                .set(CANVAS_EDIT_COMMAND.REQUEST_HASH, hash).execute() != 1)
            throw new IllegalStateException("Canvas edit command insert failed");
    }
    public void prune(UUID project) {
        var retained = db.select(CANVAS_EDIT_RECEIPT.ID).from(CANVAS_EDIT_RECEIPT)
                .where(CANVAS_EDIT_RECEIPT.PROJECT_ID.eq(project)).orderBy(CANVAS_EDIT_RECEIPT.CREATED_AT.desc(), CANVAS_EDIT_RECEIPT.ID)
                .limit(RETAINED_RECEIPTS);
        db.deleteFrom(CANVAS_EDIT_RECEIPT).where(CANVAS_EDIT_RECEIPT.PROJECT_ID.eq(project))
                .and(CANVAS_EDIT_RECEIPT.ID.notIn(retained)).execute();
        // Compact command tombstones intentionally survive snapshot pruning. A delayed
        // original copy request must return EXPIRED rather than resurrect an undone clone.
    }
    private JSONB json(Object value) {
        return JSONB.valueOf(mapper.createObjectNode().put("schemaVersion", 1).set("content", mapper.valueToTree(value)).toString());
    }
    private static OffsetDateTime at(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
