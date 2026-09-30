package dev.agenvas.library.infrastructure;

import static dev.agenvas.db.Tables.LIBRARY_COMMAND;
import static dev.agenvas.db.Tables.LIBRARY_CLEANUP;
import static dev.agenvas.db.Tables.LIBRARY_ENTRY;
import static dev.agenvas.db.Tables.LIBRARY_FILE;
import static dev.agenvas.db.Tables.LIBRARY_IMPORT;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.PrivateMediaArchive.Media;
import dev.agenvas.library.domain.LibraryCommand;
import dev.agenvas.library.domain.LibraryEntry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** All catalogue SQL remains behind the account scope and typed jOOQ schema. */
@Repository
public class LibraryRepository {
    private static final int SCHEMA_VERSION = 1;
    private final DSLContext db;
    private final ObjectMapper mapper;
    public LibraryRepository(DSLContext db, ObjectMapper mapper) { this.db = db; this.mapper = mapper; }

    public Optional<LibraryEntry> entry(UUID owner, UUID id) {
        return db.selectFrom(LIBRARY_ENTRY).where(LIBRARY_ENTRY.OWNER_ID.eq(owner).and(LIBRARY_ENTRY.ID.eq(id)))
                .fetchOptional().map(this::entry);
    }
    public Optional<LibraryEntry> entryForUpdate(UUID owner, UUID id) {
        return db.selectFrom(LIBRARY_ENTRY).where(LIBRARY_ENTRY.OWNER_ID.eq(owner).and(LIBRARY_ENTRY.ID.eq(id)))
                .forUpdate().fetchOptional().map(this::entry);
    }
    public Optional<LibraryEntry> source(UUID owner, UUID versionId) {
        return db.selectFrom(LIBRARY_ENTRY).where(LIBRARY_ENTRY.OWNER_ID.eq(owner)
                .and(LIBRARY_ENTRY.SOURCE_VERSION_ID.eq(versionId))).fetchOptional().map(this::entry);
    }
    public void insertFile(Media media, Instant now) {
        db.insertInto(LIBRARY_FILE).set(LIBRARY_FILE.ID, media.id()).set(LIBRARY_FILE.OWNER_ID, media.ownerId())
                .set(LIBRARY_FILE.KIND, media.kind().name())
                .set(LIBRARY_FILE.METADATA_JSON, json(mapper.valueToTree(media)))
                .set(LIBRARY_FILE.CREATED_AT, time(now)).onConflict(LIBRARY_FILE.ID).doNothing().execute();
    }
    public Media file(UUID owner, UUID fileId) {
        var row = db.selectFrom(LIBRARY_FILE).where(LIBRARY_FILE.OWNER_ID.eq(owner)
                .and(LIBRARY_FILE.ID.eq(fileId))).fetchOne();
        if (row == null) throw new IllegalStateException("Library file metadata is missing");
        return mapper.readValue(row.getMetadataJson().data(), Media.class);
    }
    public boolean insert(LibraryEntry value) {
        return db.insertInto(LIBRARY_ENTRY).set(LIBRARY_ENTRY.ID, value.id())
                .set(LIBRARY_ENTRY.OWNER_ID, value.ownerId()).set(LIBRARY_ENTRY.NAME, value.name())
                .set(LIBRARY_ENTRY.CATEGORY, value.category().name()).set(LIBRARY_ENTRY.KIND, value.kind().name())
                .set(LIBRARY_ENTRY.TEXT_CONTENT, json(value.textContent())).set(LIBRARY_ENTRY.FILE_ID, value.fileId())
                .set(LIBRARY_ENTRY.SOURCE_VERSION_ID, value.sourceVersionId()).set(LIBRARY_ENTRY.SOURCE_JSON, json(value.source()))
                .set(LIBRARY_ENTRY.CREATED_AT, time(value.createdAt())).set(LIBRARY_ENTRY.UPDATED_AT, time(value.updatedAt()))
                .onConflictDoNothing().execute() == 1;
    }
    public boolean update(UUID owner, UUID id, long expected, String name,
            LibraryEntry.Category category, boolean favorite, Instant trashed, Instant now) {
        return db.update(LIBRARY_ENTRY).set(LIBRARY_ENTRY.NAME, name).set(LIBRARY_ENTRY.CATEGORY, category.name())
                .set(LIBRARY_ENTRY.FAVORITE, favorite).set(LIBRARY_ENTRY.TRASHED_AT, time(trashed))
                .set(LIBRARY_ENTRY.VERSION, expected + 1).set(LIBRARY_ENTRY.UPDATED_AT, time(now))
                .where(LIBRARY_ENTRY.OWNER_ID.eq(owner).and(LIBRARY_ENTRY.ID.eq(id))
                        .and(LIBRARY_ENTRY.VERSION.eq(expected))).execute() == 1;
    }
    public boolean delete(UUID owner, UUID id, long expected) {
        return db.deleteFrom(LIBRARY_ENTRY).where(LIBRARY_ENTRY.OWNER_ID.eq(owner)
                .and(LIBRARY_ENTRY.ID.eq(id)).and(LIBRARY_ENTRY.VERSION.eq(expected))
                .and(LIBRARY_ENTRY.TRASHED_AT.isNotNull())).execute() == 1;
    }
    public void deleteFile(UUID owner, UUID id) {
        db.deleteFrom(LIBRARY_FILE).where(LIBRARY_FILE.OWNER_ID.eq(owner).and(LIBRARY_FILE.ID.eq(id))).execute();
    }

    public record Cleanup(int schemaVersion, UUID id, UUID owner, String objectKey, String thumbnailKey, dev.agenvas.asset.domain.Asset preparedImport) {}
    public void enqueueCleanup(Media media, Instant now) {
        enqueueCleanup(new Cleanup(SCHEMA_VERSION, media.id(), media.ownerId(), media.objectKey(), media.thumbnailKey(), null), now);
    }
    public void enqueuePinCleanup(LibraryCommand command, Instant now) {
        String pin = command.input().path("pin").asText(null);
        if (pin == null) return;
        UUID id = UUID.nameUUIDFromBytes(("agenvas:library-pin:v1:" + command.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        enqueueCleanup(new Cleanup(SCHEMA_VERSION, id, command.ownerId(), pin, null, null), now);
    }
    public void enqueueRejectedImport(UUID owner, dev.agenvas.asset.domain.Asset prepared, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("agenvas:library-rejected-import:v1:" + prepared.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        enqueueCleanup(new Cleanup(SCHEMA_VERSION, id, owner, null, null, prepared), now);
    }
    private void enqueueCleanup(Cleanup cleanup, Instant now) {
        db.insertInto(LIBRARY_CLEANUP).set(LIBRARY_CLEANUP.ID, cleanup.id()).set(LIBRARY_CLEANUP.OWNER_ID, cleanup.owner())
                .set(LIBRARY_CLEANUP.METADATA_JSON, json(mapper.valueToTree(cleanup))).set(LIBRARY_CLEANUP.CREATED_AT, time(now))
                .onConflictDoNothing().execute();
    }
    public Optional<Cleanup> cleanup(Instant now) {
        return db.selectFrom(LIBRARY_CLEANUP).where(LIBRARY_CLEANUP.NEXT_ATTEMPT_AT.le(time(now)))
                .orderBy(LIBRARY_CLEANUP.NEXT_ATTEMPT_AT, LIBRARY_CLEANUP.CREATED_AT, LIBRARY_CLEANUP.ID).limit(1)
                .fetchOptional().map(row -> mapper.readValue(row.getMetadataJson().data(), Cleanup.class));
    }
    public void deferCleanup(UUID id, Instant nextAttempt) {
        db.update(LIBRARY_CLEANUP).set(LIBRARY_CLEANUP.NEXT_ATTEMPT_AT, time(nextAttempt))
                .where(LIBRARY_CLEANUP.ID.eq(id)).execute();
    }
    public void cleaned(UUID id) { db.deleteFrom(LIBRARY_CLEANUP).where(LIBRARY_CLEANUP.ID.eq(id)).execute(); }

    public record Cursor(String value, UUID id) {}
    private Condition scope(UUID owner, LibraryEntry.Category category, Artifact.Kind kind,
            String query, boolean favorite, boolean trash) {
        Condition condition = LIBRARY_ENTRY.OWNER_ID.eq(owner).and(trash
                ? LIBRARY_ENTRY.TRASHED_AT.isNotNull() : LIBRARY_ENTRY.TRASHED_AT.isNull());
        if (category != null) condition = condition.and(LIBRARY_ENTRY.CATEGORY.eq(category.name()));
        if (kind != null) condition = condition.and(LIBRARY_ENTRY.KIND.eq(kind.name()));
        if (favorite) condition = condition.and(LIBRARY_ENTRY.FAVORITE.isTrue());
        if (query != null && !query.isBlank()) condition = condition.and(LIBRARY_ENTRY.NAME.containsIgnoreCase(query));
        return condition;
    }
    public int count(UUID owner, LibraryEntry.Category category, Artifact.Kind kind, String query, boolean favorite, boolean trash) {
        return db.fetchCount(db.selectFrom(LIBRARY_ENTRY).where(scope(owner, category, kind, query, favorite, trash)));
    }
    public Map<String, Integer> counts(UUID owner, boolean trash) {
        return db.select(LIBRARY_ENTRY.CATEGORY, org.jooq.impl.DSL.count()).from(LIBRARY_ENTRY)
                .where(scope(owner, null, null, null, false, trash)).groupBy(LIBRARY_ENTRY.CATEGORY)
                .fetchMap(LIBRARY_ENTRY.CATEGORY, org.jooq.impl.DSL.count());
    }
    public List<LibraryEntry> list(UUID owner, LibraryEntry.Category category, Artifact.Kind kind,
            String query, boolean favorite, boolean trash, LibraryEntry.Sort sort, Cursor cursor, int limit) {
        Condition condition = scope(owner, category, kind, query, favorite, trash);
        if (cursor != null) {
            if (sort == LibraryEntry.Sort.NAME) condition = condition.and(LIBRARY_ENTRY.NAME.gt(cursor.value())
                    .or(LIBRARY_ENTRY.NAME.eq(cursor.value()).and(LIBRARY_ENTRY.ID.lt(cursor.id()))));
            else {
                var field = sort == LibraryEntry.Sort.UPDATED ? LIBRARY_ENTRY.UPDATED_AT : LIBRARY_ENTRY.CREATED_AT;
                var value = OffsetDateTime.parse(cursor.value());
                condition = condition.and(field.lt(value).or(field.eq(value).and(LIBRARY_ENTRY.ID.lt(cursor.id()))));
            }
        }
        var order = sort == LibraryEntry.Sort.NAME ? LIBRARY_ENTRY.NAME.asc()
                : sort == LibraryEntry.Sort.UPDATED ? LIBRARY_ENTRY.UPDATED_AT.desc() : LIBRARY_ENTRY.CREATED_AT.desc();
        return db.selectFrom(LIBRARY_ENTRY).where(condition).orderBy(order, LIBRARY_ENTRY.ID.desc()).limit(limit)
                .fetch().map(this::entry);
    }

    public boolean reserve(LibraryCommand command) {
        return db.insertInto(LIBRARY_COMMAND).set(LIBRARY_COMMAND.ID, command.id())
                .set(LIBRARY_COMMAND.OWNER_ID, command.ownerId()).set(LIBRARY_COMMAND.COMMAND_KEY, command.commandKey())
                .set(LIBRARY_COMMAND.PAYLOAD_HASH, command.payloadHash()).set(LIBRARY_COMMAND.KIND, command.kind().name())
                .set(LIBRARY_COMMAND.INPUT_JSON, json(command.input())).set(LIBRARY_COMMAND.STATUS, command.status().name())
                .set(LIBRARY_COMMAND.CREATED_AT, time(command.createdAt())).set(LIBRARY_COMMAND.UPDATED_AT, time(command.updatedAt()))
                .onConflict(LIBRARY_COMMAND.OWNER_ID, LIBRARY_COMMAND.COMMAND_KEY).doNothing().execute() == 1;
    }
    public Optional<LibraryCommand> command(UUID owner, UUID id) {
        return db.selectFrom(LIBRARY_COMMAND).where(LIBRARY_COMMAND.OWNER_ID.eq(owner)
                .and(LIBRARY_COMMAND.ID.eq(id))).fetchOptional().map(this::command);
    }
    public Optional<LibraryCommand> key(UUID owner, String key) {
        return db.selectFrom(LIBRARY_COMMAND).where(LIBRARY_COMMAND.OWNER_ID.eq(owner)
                .and(LIBRARY_COMMAND.COMMAND_KEY.eq(key))).fetchOptional().map(this::command);
    }
    public Optional<LibraryCommand> claim(Instant now, Instant until) {
        var row = db.selectFrom(LIBRARY_COMMAND).where(LIBRARY_COMMAND.STATUS.eq(LibraryCommand.Status.ACCEPTED.name())
                .or(LIBRARY_COMMAND.STATUS.eq(LibraryCommand.Status.ARCHIVING.name()).and(LIBRARY_COMMAND.LEASE_UNTIL.le(time(now)))))
                .orderBy(LIBRARY_COMMAND.CREATED_AT, LIBRARY_COMMAND.ID).limit(1).forUpdate().skipLocked().fetchOne();
        if (row == null) return Optional.empty();
        int updated = db.update(LIBRARY_COMMAND).set(LIBRARY_COMMAND.STATUS, LibraryCommand.Status.ARCHIVING.name())
                .set(LIBRARY_COMMAND.EPOCH, row.getEpoch() + 1).set(LIBRARY_COMMAND.LEASE_UNTIL, time(until))
                .set(LIBRARY_COMMAND.UPDATED_AT, time(now)).where(LIBRARY_COMMAND.ID.eq(row.getId())
                        .and(LIBRARY_COMMAND.EPOCH.eq(row.getEpoch())).and(LIBRARY_COMMAND.STATUS.eq(row.getStatus()))).execute();
        if (updated != 1) return Optional.empty();
        return command(row.getOwnerId(), row.getId());
    }
    /** All final writes are fenced; callers roll back business changes if a lease was superseded. */
    public boolean finish(LibraryCommand command, LibraryCommand.Status status, JsonNode result,
            String code, String detail, Instant now) {
        return db.update(LIBRARY_COMMAND).set(LIBRARY_COMMAND.STATUS, status.name()).set(LIBRARY_COMMAND.LEASE_UNTIL, (OffsetDateTime) null)
                .set(LIBRARY_COMMAND.RESULT_JSON, json(result)).set(LIBRARY_COMMAND.ERROR_CODE, code)
                .set(LIBRARY_COMMAND.ERROR_DETAIL, detail).set(LIBRARY_COMMAND.UPDATED_AT, time(now))
                .where(LIBRARY_COMMAND.ID.eq(command.id()).and(LIBRARY_COMMAND.OWNER_ID.eq(command.ownerId()))
                        .and(LIBRARY_COMMAND.STATUS.eq(LibraryCommand.Status.ARCHIVING.name())).and(LIBRARY_COMMAND.EPOCH.eq(command.epoch()))).execute() == 1;
    }
    public boolean retry(UUID owner, UUID id, Instant now) {
        return db.update(LIBRARY_COMMAND).set(LIBRARY_COMMAND.STATUS, LibraryCommand.Status.ACCEPTED.name())
                .set(LIBRARY_COMMAND.ERROR_CODE, (String) null).set(LIBRARY_COMMAND.ERROR_DETAIL, (String) null)
                .set(LIBRARY_COMMAND.UPDATED_AT, time(now)).where(LIBRARY_COMMAND.OWNER_ID.eq(owner)
                        .and(LIBRARY_COMMAND.ID.eq(id)).and(LIBRARY_COMMAND.STATUS.eq(LibraryCommand.Status.FAILED.name()))).execute() == 1;
    }
    public void recordImport(UUID projectId, UUID versionId, UUID entryId, JsonNode source) {
        db.insertInto(LIBRARY_IMPORT).set(LIBRARY_IMPORT.PROJECT_ID, projectId).set(LIBRARY_IMPORT.VERSION_ID, versionId)
                .set(LIBRARY_IMPORT.ENTRY_ID, entryId).set(LIBRARY_IMPORT.SOURCE_JSON, json(source)).execute();
    }
    private LibraryEntry entry(Record row) {
        return new LibraryEntry(row.get(LIBRARY_ENTRY.ID), row.get(LIBRARY_ENTRY.OWNER_ID), row.get(LIBRARY_ENTRY.NAME),
                LibraryEntry.Category.valueOf(row.get(LIBRARY_ENTRY.CATEGORY)), Artifact.Kind.valueOf(row.get(LIBRARY_ENTRY.KIND)),
                node(row.get(LIBRARY_ENTRY.TEXT_CONTENT)), row.get(LIBRARY_ENTRY.FILE_ID), row.get(LIBRARY_ENTRY.SOURCE_VERSION_ID),
                node(row.get(LIBRARY_ENTRY.SOURCE_JSON)), row.get(LIBRARY_ENTRY.FAVORITE), instant(row.get(LIBRARY_ENTRY.TRASHED_AT)),
                row.get(LIBRARY_ENTRY.VERSION), instant(row.get(LIBRARY_ENTRY.CREATED_AT)), instant(row.get(LIBRARY_ENTRY.UPDATED_AT)));
    }
    private LibraryCommand command(Record row) {
        return new LibraryCommand(row.get(LIBRARY_COMMAND.ID), row.get(LIBRARY_COMMAND.OWNER_ID), row.get(LIBRARY_COMMAND.COMMAND_KEY),
                row.get(LIBRARY_COMMAND.PAYLOAD_HASH), LibraryCommand.Kind.valueOf(row.get(LIBRARY_COMMAND.KIND)),
                node(row.get(LIBRARY_COMMAND.INPUT_JSON)), LibraryCommand.Status.valueOf(row.get(LIBRARY_COMMAND.STATUS)),
                row.get(LIBRARY_COMMAND.EPOCH), instant(row.get(LIBRARY_COMMAND.LEASE_UNTIL)), node(row.get(LIBRARY_COMMAND.RESULT_JSON)),
                row.get(LIBRARY_COMMAND.ERROR_CODE), row.get(LIBRARY_COMMAND.ERROR_DETAIL),
                instant(row.get(LIBRARY_COMMAND.CREATED_AT)), instant(row.get(LIBRARY_COMMAND.UPDATED_AT)));
    }
    private JSONB json(JsonNode node) { return node == null ? null : JSONB.valueOf(node.toString()); }
    private JsonNode node(JSONB value) { return value == null ? null : mapper.readTree(value.data()); }
    private OffsetDateTime time(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}
