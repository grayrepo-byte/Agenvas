package dev.agenvas.character.infrastructure;

import static dev.agenvas.db.Tables.PROJECT_CHARACTER;
import static dev.agenvas.db.Tables.PROJECT_CHARACTER_REVISION;
import static dev.agenvas.db.Tables.PROJECT_CHARACTER_COMMAND;
import static dev.agenvas.db.Tables.CANVAS_CHARACTER_BINDING;
import static dev.agenvas.db.Tables.CANVAS_CHARACTER_BINDING_MEMBER;
import static dev.agenvas.character.application.CharacterModels.*;

import dev.agenvas.character.application.CharacterDraftGateway.DraftState;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Character persistence owns its tables; all calls are scoped by an authorized project. */
@Repository
public class CharacterRepository {
    private final DSLContext db;
    private final ObjectMapper mapper;
    public CharacterRepository(DSLContext db, ObjectMapper mapper) { this.db = db; this.mapper = mapper; }

    public record BindingRow(UUID projectId, UUID canvasItemId, long version, long draftVersion,
            List<CharacterSnapshot> snapshots, DraftState state, boolean needsReview,
            Long reviewedDraftVersion, UUID reviewedSelectedVersionId, Instant reviewedAt, Instant updatedAt) {}
    public record Command(String requestHash, JsonNode result) {}

    public List<CharacterProfile> profiles(UUID project) {
        return db.selectFrom(PROJECT_CHARACTER).where(PROJECT_CHARACTER.PROJECT_ID.eq(project))
                .orderBy(PROJECT_CHARACTER.CREATED_AT.asc(), PROJECT_CHARACTER.ID.asc()).fetch().map(this::profile);
    }
    public Optional<CharacterProfile> profile(UUID project, UUID character) {
        return db.selectFrom(PROJECT_CHARACTER).where(PROJECT_CHARACTER.PROJECT_ID.eq(project)
                .and(PROJECT_CHARACTER.ID.eq(character))).fetchOptional().map(this::profile);
    }
    public boolean insertProfile(CharacterProfile value) {
        boolean inserted = db.insertInto(PROJECT_CHARACTER).set(PROJECT_CHARACTER.ID, value.id())
                .set(PROJECT_CHARACTER.PROJECT_ID, value.projectId()).set(PROJECT_CHARACTER.CONTENT_JSON, json(value.content()))
                .set(PROJECT_CHARACTER.VERSION, value.version()).set(PROJECT_CHARACTER.ARCHIVED, value.archived())
                .set(PROJECT_CHARACTER.CREATED_AT, time(value.createdAt())).set(PROJECT_CHARACTER.UPDATED_AT, time(value.updatedAt()))
                .onConflict(PROJECT_CHARACTER.ID).doNothing().execute() == 1;
        if (inserted) revision(value);
        return inserted;
    }
    public boolean updateProfile(CharacterProfile value, long expected) {
        boolean updated = db.update(PROJECT_CHARACTER).set(PROJECT_CHARACTER.CONTENT_JSON, json(value.content()))
                .set(PROJECT_CHARACTER.VERSION, value.version()).set(PROJECT_CHARACTER.ARCHIVED, value.archived())
                .set(PROJECT_CHARACTER.UPDATED_AT, time(value.updatedAt()))
                .where(PROJECT_CHARACTER.PROJECT_ID.eq(value.projectId()).and(PROJECT_CHARACTER.ID.eq(value.id()))
                        .and(PROJECT_CHARACTER.VERSION.eq(expected))).execute() == 1;
        if (updated) revision(value);
        return updated;
    }
    private void revision(CharacterProfile value) {
        if (db.insertInto(PROJECT_CHARACTER_REVISION).set(PROJECT_CHARACTER_REVISION.PROJECT_ID, value.projectId())
                .set(PROJECT_CHARACTER_REVISION.CHARACTER_ID, value.id()).set(PROJECT_CHARACTER_REVISION.VERSION, value.version())
                .set(PROJECT_CHARACTER_REVISION.CONTENT_JSON, json(value.content())).set(PROJECT_CHARACTER_REVISION.ARCHIVED, value.archived())
                .set(PROJECT_CHARACTER_REVISION.CREATED_AT, time(value.updatedAt())).execute() != 1)
            throw new IllegalStateException("Character revision insert failed");
    }
    public List<BindingRow> bindings(UUID project) {
        return db.selectFrom(CANVAS_CHARACTER_BINDING).where(CANVAS_CHARACTER_BINDING.PROJECT_ID.eq(project))
                .orderBy(CANVAS_CHARACTER_BINDING.CANVAS_ITEM_ID.asc()).fetch().map(this::binding);
    }
    public Optional<BindingRow> binding(UUID project, UUID item) {
        return db.selectFrom(CANVAS_CHARACTER_BINDING).where(CANVAS_CHARACTER_BINDING.PROJECT_ID.eq(project)
                .and(CANVAS_CHARACTER_BINDING.CANVAS_ITEM_ID.eq(item))).fetchOptional().map(this::binding);
    }
    public List<UUID> affected(UUID project, UUID character) {
        return db.select(CANVAS_CHARACTER_BINDING_MEMBER.CANVAS_ITEM_ID).from(CANVAS_CHARACTER_BINDING_MEMBER)
                .where(CANVAS_CHARACTER_BINDING_MEMBER.PROJECT_ID.eq(project)
                        .and(CANVAS_CHARACTER_BINDING_MEMBER.CHARACTER_ID.eq(character)))
                .orderBy(CANVAS_CHARACTER_BINDING_MEMBER.CANVAS_ITEM_ID.asc()).fetch(CANVAS_CHARACTER_BINDING_MEMBER.CANVAS_ITEM_ID);
    }
    public boolean saveBinding(BindingRow value, long expected) {
        boolean changed;
        if (expected == 0) changed = db.insertInto(CANVAS_CHARACTER_BINDING)
                .set(CANVAS_CHARACTER_BINDING.PROJECT_ID, value.projectId()).set(CANVAS_CHARACTER_BINDING.CANVAS_ITEM_ID, value.canvasItemId())
                .set(CANVAS_CHARACTER_BINDING.VERSION, value.version()).set(CANVAS_CHARACTER_BINDING.DRAFT_VERSION, value.draftVersion())
                .set(CANVAS_CHARACTER_BINDING.SNAPSHOTS_JSON, json(value.snapshots())).set(CANVAS_CHARACTER_BINDING.DRAFT_STATE_JSON, json(value.state()))
                .set(CANVAS_CHARACTER_BINDING.NEEDS_REVIEW, true).set(CANVAS_CHARACTER_BINDING.UPDATED_AT, time(value.updatedAt()))
                .onConflict(CANVAS_CHARACTER_BINDING.PROJECT_ID, CANVAS_CHARACTER_BINDING.CANVAS_ITEM_ID).doNothing().execute() == 1;
        else changed = db.update(CANVAS_CHARACTER_BINDING).set(CANVAS_CHARACTER_BINDING.VERSION, value.version())
                .set(CANVAS_CHARACTER_BINDING.DRAFT_VERSION, value.draftVersion())
                .set(CANVAS_CHARACTER_BINDING.SNAPSHOTS_JSON, json(value.snapshots())).set(CANVAS_CHARACTER_BINDING.DRAFT_STATE_JSON, json(value.state()))
                .set(CANVAS_CHARACTER_BINDING.NEEDS_REVIEW, true).setNull(CANVAS_CHARACTER_BINDING.REVIEWED_DRAFT_VERSION)
                .setNull(CANVAS_CHARACTER_BINDING.REVIEWED_SELECTED_VERSION_ID).setNull(CANVAS_CHARACTER_BINDING.REVIEWED_AT)
                .set(CANVAS_CHARACTER_BINDING.UPDATED_AT, time(value.updatedAt()))
                .where(CANVAS_CHARACTER_BINDING.PROJECT_ID.eq(value.projectId()).and(CANVAS_CHARACTER_BINDING.CANVAS_ITEM_ID.eq(value.canvasItemId()))
                        .and(CANVAS_CHARACTER_BINDING.VERSION.eq(expected))).execute() == 1;
        if (changed) {
            db.deleteFrom(CANVAS_CHARACTER_BINDING_MEMBER).where(CANVAS_CHARACTER_BINDING_MEMBER.PROJECT_ID.eq(value.projectId())
                    .and(CANVAS_CHARACTER_BINDING_MEMBER.CANVAS_ITEM_ID.eq(value.canvasItemId()))).execute();
            for (CharacterSnapshot snapshot : value.snapshots()) {
                if (db.insertInto(CANVAS_CHARACTER_BINDING_MEMBER).set(CANVAS_CHARACTER_BINDING_MEMBER.PROJECT_ID, value.projectId())
                        .set(CANVAS_CHARACTER_BINDING_MEMBER.CANVAS_ITEM_ID, value.canvasItemId())
                        .set(CANVAS_CHARACTER_BINDING_MEMBER.CHARACTER_ID, snapshot.characterId())
                        .set(CANVAS_CHARACTER_BINDING_MEMBER.CHARACTER_VERSION, snapshot.characterVersion()).execute() != 1)
                    throw new IllegalStateException("Character binding member insert failed");
            }
        }
        return changed;
    }
    public boolean review(UUID project, UUID item, long expected, long draftVersion, UUID selected, Instant now) {
        return db.update(CANVAS_CHARACTER_BINDING).set(CANVAS_CHARACTER_BINDING.VERSION, expected + 1)
                .set(CANVAS_CHARACTER_BINDING.NEEDS_REVIEW, false).set(CANVAS_CHARACTER_BINDING.REVIEWED_DRAFT_VERSION, draftVersion)
                .set(CANVAS_CHARACTER_BINDING.REVIEWED_SELECTED_VERSION_ID, selected).set(CANVAS_CHARACTER_BINDING.REVIEWED_AT, time(now))
                .set(CANVAS_CHARACTER_BINDING.UPDATED_AT, time(now))
                .where(CANVAS_CHARACTER_BINDING.PROJECT_ID.eq(project).and(CANVAS_CHARACTER_BINDING.CANVAS_ITEM_ID.eq(item))
                        .and(CANVAS_CHARACTER_BINDING.VERSION.eq(expected))).execute() == 1;
    }
    /** Review invalidation shares the caller's project event lock and never mutates applied snapshots. */
    public void requireReview(UUID project, UUID item) {
        db.update(CANVAS_CHARACTER_BINDING).set(CANVAS_CHARACTER_BINDING.NEEDS_REVIEW, true)
                .where(CANVAS_CHARACTER_BINDING.PROJECT_ID.eq(project).and(CANVAS_CHARACTER_BINDING.CANVAS_ITEM_ID.eq(item))).execute();
    }
    public Optional<Command> command(UUID project, UUID command) {
        return db.selectFrom(PROJECT_CHARACTER_COMMAND).where(PROJECT_CHARACTER_COMMAND.PROJECT_ID.eq(project)
                .and(PROJECT_CHARACTER_COMMAND.COMMAND_ID.eq(command))).fetchOptional()
                .map(row -> new Command(row.getRequestHash(), body(row.getResultJson())));
    }
    public void command(UUID project, UUID command, String hash, Object result, Instant now) {
        if (db.insertInto(PROJECT_CHARACTER_COMMAND).set(PROJECT_CHARACTER_COMMAND.PROJECT_ID, project)
                .set(PROJECT_CHARACTER_COMMAND.COMMAND_ID, command).set(PROJECT_CHARACTER_COMMAND.REQUEST_HASH, hash)
                .set(PROJECT_CHARACTER_COMMAND.RESULT_JSON, json(result)).set(PROJECT_CHARACTER_COMMAND.CREATED_AT, time(now)).execute() != 1)
            throw new IllegalStateException("Character command insert failed");
    }
    private CharacterProfile profile(Record row) {
        return new CharacterProfile(row.get(PROJECT_CHARACTER.ID), row.get(PROJECT_CHARACTER.PROJECT_ID),
                mapper.treeToValue(body(row.get(PROJECT_CHARACTER.CONTENT_JSON)), CharacterContent.class), row.get(PROJECT_CHARACTER.VERSION),
                row.get(PROJECT_CHARACTER.ARCHIVED), instant(row.get(PROJECT_CHARACTER.CREATED_AT)), instant(row.get(PROJECT_CHARACTER.UPDATED_AT)));
    }
    private BindingRow binding(Record row) {
        return new BindingRow(row.get(CANVAS_CHARACTER_BINDING.PROJECT_ID), row.get(CANVAS_CHARACTER_BINDING.CANVAS_ITEM_ID),
                row.get(CANVAS_CHARACTER_BINDING.VERSION), row.get(CANVAS_CHARACTER_BINDING.DRAFT_VERSION),
                List.of(mapper.treeToValue(body(row.get(CANVAS_CHARACTER_BINDING.SNAPSHOTS_JSON)), CharacterSnapshot[].class)),
                mapper.treeToValue(body(row.get(CANVAS_CHARACTER_BINDING.DRAFT_STATE_JSON)), DraftState.class),
                row.get(CANVAS_CHARACTER_BINDING.NEEDS_REVIEW), row.get(CANVAS_CHARACTER_BINDING.REVIEWED_DRAFT_VERSION),
                row.get(CANVAS_CHARACTER_BINDING.REVIEWED_SELECTED_VERSION_ID), instant(row.get(CANVAS_CHARACTER_BINDING.REVIEWED_AT)),
                instant(row.get(CANVAS_CHARACTER_BINDING.UPDATED_AT)));
    }
    private JSONB json(Object value) {
        var document = mapper.createObjectNode().put("schemaVersion", 1);
        document.set("content", mapper.valueToTree(value));
        return JSONB.valueOf(mapper.writeValueAsString(document));
    }
    private JsonNode body(JSONB value) {
        JsonNode document = mapper.readTree(value.data());
        if (document.path("schemaVersion").asInt() != 1 || !document.has("content"))
            throw new IllegalStateException("Unsupported persisted character document schema");
        return document.path("content");
    }
    private OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}
