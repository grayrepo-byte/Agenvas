package dev.agenvas.characterlibrary.infrastructure;

import static dev.agenvas.db.Tables.*;
import static dev.agenvas.characterlibrary.application.CharacterLibraryModels.*;

import dev.agenvas.asset.application.PrivateMediaArchive.Media;
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

/** Typed account-scoped persistence. No project or other-module tables are accessed here. */
@Repository
public class CharacterLibraryRepository {
    public record Job(UUID owner, UUID id, String hash, Kind kind, JsonNode input, Status status,
            long epoch, JsonNode result, String errorCode) {
        public Command response() {
            return new Command(id, status, result, errorCode, null);
        }
    }
    public record StoredLink(UUID owner, UUID project, UUID character, UUID library, long version) {
    }
    public record Cleanup(UUID owner, UUID id, UUID command, JsonNode payload) {
    }
    private final DSLContext db;
    private final ObjectMapper mapper;
    public CharacterLibraryRepository(DSLContext db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }
    /**
     * Serializes acceptance and uncertain-commit cleanup until PostgreSQL has resolved the
     * original transaction. A plain SELECT of a missing command cannot provide that barrier:
     * an unacknowledged COMMIT may still become visible after the SELECT has returned.
     */
    public void lockCommand(UUID owner, UUID id) {
        long key = owner.getMostSignificantBits() ^ Long.rotateLeft(owner.getLeastSignificantBits(), 17)
                ^ id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 33);
        db.select(org.jooq.impl.DSL.function("pg_advisory_xact_lock", Object.class,
                org.jooq.impl.DSL.val(key))).fetch();
    }
    public Optional<Profile> profile(UUID owner, UUID id, boolean lock) {
        var query = db.selectFrom(CHARACTER_LIBRARY_PROFILE)
                .where(CHARACTER_LIBRARY_PROFILE.OWNER_ID.eq(owner).and(CHARACTER_LIBRARY_PROFILE.ID.eq(id)));
        return (lock ? query.forUpdate().fetchOptional() : query.fetchOptional()).map(this::profile);
    }
    public List<Profile> profiles(UUID owner, String query, boolean archived, UUID cursor, int limit) {
        var condition = CHARACTER_LIBRARY_PROFILE.OWNER_ID.eq(owner)
                .and(CHARACTER_LIBRARY_PROFILE.ARCHIVED.eq(archived));
        if (query != null && !query.isBlank())
            condition = condition
                    .and(CHARACTER_LIBRARY_PROFILE.CONTENT_JSON.cast(String.class).containsIgnoreCase(query));
        if (cursor != null)
            condition = condition.and(CHARACTER_LIBRARY_PROFILE.ID.gt(cursor));
        return db.selectFrom(CHARACTER_LIBRARY_PROFILE).where(condition).orderBy(CHARACTER_LIBRARY_PROFILE.ID.asc())
                .limit(limit).fetch().map(this::profile);
    }
    public boolean save(UUID owner, Profile value, long expected) {
        boolean changed;
        if (expected == 0)
            changed = db.insertInto(CHARACTER_LIBRARY_PROFILE)
                    .set(CHARACTER_LIBRARY_PROFILE.OWNER_ID, owner).set(CHARACTER_LIBRARY_PROFILE.ID, value.id())
                    .set(CHARACTER_LIBRARY_PROFILE.CONTENT_JSON, envelope(value.content()))
                    .set(CHARACTER_LIBRARY_PROFILE.VERSION, value.version())
                    .set(CHARACTER_LIBRARY_PROFILE.ARCHIVED, value.archived())
                    .set(CHARACTER_LIBRARY_PROFILE.CREATED_AT, time(value.createdAt()))
                    .set(CHARACTER_LIBRARY_PROFILE.UPDATED_AT, time(value.updatedAt())).onConflictDoNothing()
                    .execute() == 1;
        else
            changed = db.update(CHARACTER_LIBRARY_PROFILE)
                    .set(CHARACTER_LIBRARY_PROFILE.CONTENT_JSON, envelope(value.content()))
                    .set(CHARACTER_LIBRARY_PROFILE.VERSION, value.version())
                    .set(CHARACTER_LIBRARY_PROFILE.ARCHIVED, value.archived())
                    .set(CHARACTER_LIBRARY_PROFILE.UPDATED_AT, time(value.updatedAt()))
                    .where(CHARACTER_LIBRARY_PROFILE.OWNER_ID.eq(owner)
                            .and(CHARACTER_LIBRARY_PROFILE.ID.eq(value.id()))
                            .and(CHARACTER_LIBRARY_PROFILE.VERSION.eq(expected)))
                    .execute() == 1;
        if (changed)
            db.insertInto(CHARACTER_LIBRARY_REVISION).set(CHARACTER_LIBRARY_REVISION.OWNER_ID, owner)
                    .set(CHARACTER_LIBRARY_REVISION.PROFILE_ID, value.id())
                    .set(CHARACTER_LIBRARY_REVISION.VERSION, value.version())
                    .set(CHARACTER_LIBRARY_REVISION.CONTENT_JSON, envelope(value.content()))
                    .set(CHARACTER_LIBRARY_REVISION.ARCHIVED, value.archived())
                    .set(CHARACTER_LIBRARY_REVISION.CREATED_AT, time(value.updatedAt())).execute();
        return changed;
    }
    public void media(UUID owner, Media media, Instant now) {
        db.insertInto(CHARACTER_LIBRARY_MEDIA).set(CHARACTER_LIBRARY_MEDIA.OWNER_ID, owner)
                .set(CHARACTER_LIBRARY_MEDIA.ID, media.id())
                .set(CHARACTER_LIBRARY_MEDIA.KIND, media.kind().name())
                .set(CHARACTER_LIBRARY_MEDIA.METADATA_JSON, json(media))
                .set(CHARACTER_LIBRARY_MEDIA.CREATED_AT, time(now)).onConflictDoNothing().execute();
    }
    public Optional<Media> media(UUID owner, UUID id) {
        return db.select(CHARACTER_LIBRARY_MEDIA.METADATA_JSON).from(CHARACTER_LIBRARY_MEDIA)
                .where(CHARACTER_LIBRARY_MEDIA.OWNER_ID.eq(owner).and(CHARACTER_LIBRARY_MEDIA.ID.eq(id)))
                .fetchOptional().map(row -> mapper.readValue(row.value1().data(), Media.class));
    }
    public boolean reserve(Job job, Instant now) {
        return db.insertInto(CHARACTER_LIBRARY_COMMAND).set(CHARACTER_LIBRARY_COMMAND.OWNER_ID, job.owner())
                .set(CHARACTER_LIBRARY_COMMAND.ID, job.id())
                .set(CHARACTER_LIBRARY_COMMAND.REQUEST_HASH, job.hash())
                .set(CHARACTER_LIBRARY_COMMAND.KIND, job.kind().name())
                .set(CHARACTER_LIBRARY_COMMAND.INPUT_JSON, json(job.input()))
                .set(CHARACTER_LIBRARY_COMMAND.STATUS, job.status().name())
                .set(CHARACTER_LIBRARY_COMMAND.RESULT_JSON, json(job.result()))
                .set(CHARACTER_LIBRARY_COMMAND.CREATED_AT, time(now))
                .set(CHARACTER_LIBRARY_COMMAND.UPDATED_AT, time(now)).onConflictDoNothing().execute() == 1;
    }
    public Optional<Job> job(UUID owner, UUID id, boolean lock) {
        var query = db.selectFrom(CHARACTER_LIBRARY_COMMAND)
                .where(CHARACTER_LIBRARY_COMMAND.OWNER_ID.eq(owner).and(CHARACTER_LIBRARY_COMMAND.ID.eq(id)));
        return (lock ? query.forUpdate().fetchOptional() : query.fetchOptional()).map(this::job);
    }
    public Optional<Job> claim(Instant now, Instant deadline) {
        var row = db
                .selectFrom(CHARACTER_LIBRARY_COMMAND).where(CHARACTER_LIBRARY_COMMAND.STATUS.eq(Status.ACCEPTED.name())
                        .or(CHARACTER_LIBRARY_COMMAND.STATUS.eq(Status.ARCHIVING.name())
                                .and(CHARACTER_LIBRARY_COMMAND.LEASE_UNTIL.lt(time(now)))))
                .orderBy(CHARACTER_LIBRARY_COMMAND.CREATED_AT.asc()).limit(1).forUpdate().skipLocked().fetchOptional();
        if (row.isEmpty())
            return Optional.empty();
        var before = job(row.get());
        db.update(CHARACTER_LIBRARY_COMMAND).set(CHARACTER_LIBRARY_COMMAND.STATUS, Status.ARCHIVING.name())
                .set(CHARACTER_LIBRARY_COMMAND.EPOCH, before.epoch() + 1)
                .set(CHARACTER_LIBRARY_COMMAND.LEASE_UNTIL, time(deadline))
                .set(CHARACTER_LIBRARY_COMMAND.UPDATED_AT, time(now)).where(CHARACTER_LIBRARY_COMMAND.OWNER_ID
                        .eq(before.owner()).and(CHARACTER_LIBRARY_COMMAND.ID.eq(before.id())))
                .execute();
        return job(before.owner(), before.id(), false);
    }
    public boolean finish(Job job, Status status, JsonNode result, String code, Instant now) {
        return db.update(CHARACTER_LIBRARY_COMMAND).set(CHARACTER_LIBRARY_COMMAND.STATUS, status.name())
                .set(CHARACTER_LIBRARY_COMMAND.RESULT_JSON, json(result))
                .set(CHARACTER_LIBRARY_COMMAND.ERROR_CODE, code)
                .setNull(CHARACTER_LIBRARY_COMMAND.LEASE_UNTIL).set(CHARACTER_LIBRARY_COMMAND.UPDATED_AT, time(now))
                .where(CHARACTER_LIBRARY_COMMAND.OWNER_ID.eq(job.owner()).and(CHARACTER_LIBRARY_COMMAND.ID.eq(job.id()))
                        .and(CHARACTER_LIBRARY_COMMAND.EPOCH.eq(job.epoch()))
                        .and(CHARACTER_LIBRARY_COMMAND.STATUS.eq(job.status().name())))
                .execute() == 1;
    }
    public boolean retry(UUID owner, UUID id, Instant now) {
        return db.update(CHARACTER_LIBRARY_COMMAND).set(CHARACTER_LIBRARY_COMMAND.STATUS, Status.ACCEPTED.name())
                .setNull(CHARACTER_LIBRARY_COMMAND.ERROR_CODE).set(CHARACTER_LIBRARY_COMMAND.UPDATED_AT, time(now))
                .where(CHARACTER_LIBRARY_COMMAND.OWNER_ID.eq(owner).and(CHARACTER_LIBRARY_COMMAND.ID.eq(id))
                        .and(CHARACTER_LIBRARY_COMMAND.STATUS.eq(Status.FAILED.name())))
                .execute() == 1;
    }
    public void link(UUID owner, UUID project, UUID character, UUID library, long version, Instant now) {
        db.insertInto(PROJECT_CHARACTER_LIBRARY_LINK).set(PROJECT_CHARACTER_LIBRARY_LINK.OWNER_ID, owner)
                .set(PROJECT_CHARACTER_LIBRARY_LINK.PROJECT_ID, project)
                .set(PROJECT_CHARACTER_LIBRARY_LINK.CHARACTER_ID, character)
                .set(PROJECT_CHARACTER_LIBRARY_LINK.LIBRARY_ID, library)
                .set(PROJECT_CHARACTER_LIBRARY_LINK.LIBRARY_VERSION, version)
                .set(PROJECT_CHARACTER_LIBRARY_LINK.UPDATED_AT, time(now))
                .onConflict(PROJECT_CHARACTER_LIBRARY_LINK.PROJECT_ID, PROJECT_CHARACTER_LIBRARY_LINK.CHARACTER_ID)
                .doUpdate()
                .set(PROJECT_CHARACTER_LIBRARY_LINK.LIBRARY_VERSION, version)
                .set(PROJECT_CHARACTER_LIBRARY_LINK.UPDATED_AT, time(now)).execute();
    }
    public List<StoredLink> links(UUID owner, UUID project) {
        return db.selectFrom(PROJECT_CHARACTER_LIBRARY_LINK)
                .where(PROJECT_CHARACTER_LIBRARY_LINK.OWNER_ID.eq(owner)
                        .and(PROJECT_CHARACTER_LIBRARY_LINK.PROJECT_ID.eq(project)))
                .fetch()
                .map(r -> new StoredLink(owner, project, r.getCharacterId(), r.getLibraryId(), r.getLibraryVersion()));
    }
    public void cleanup(Job command, JsonNode payload, Instant now) {
        db.insertInto(CHARACTER_LIBRARY_CLEANUP).set(CHARACTER_LIBRARY_CLEANUP.OWNER_ID, command.owner())
                .set(CHARACTER_LIBRARY_CLEANUP.ID, command.id())
                .set(CHARACTER_LIBRARY_CLEANUP.COMMAND_ID, command.id())
                .set(CHARACTER_LIBRARY_CLEANUP.PAYLOAD_JSON, json(payload))
                .set(CHARACTER_LIBRARY_CLEANUP.AVAILABLE_AT, time(now)).onConflictDoNothing().execute();
    }
    public Optional<Cleanup> cleanup(Instant now) {
        return db.selectFrom(CHARACTER_LIBRARY_CLEANUP).where(CHARACTER_LIBRARY_CLEANUP.AVAILABLE_AT.le(time(now)))
                .limit(1)
                .fetchOptional().map(r -> new Cleanup(r.getOwnerId(), r.getId(), r.getCommandId(),
                        mapper.readTree(r.getPayloadJson().data())));
    }
    public void cleaned(Cleanup job) {
        db.deleteFrom(CHARACTER_LIBRARY_CLEANUP).where(
                CHARACTER_LIBRARY_CLEANUP.OWNER_ID.eq(job.owner()).and(CHARACTER_LIBRARY_CLEANUP.ID.eq(job.id())))
                .execute();
    }
    public void defer(Cleanup job, Instant until) {
        db.update(CHARACTER_LIBRARY_CLEANUP).set(CHARACTER_LIBRARY_CLEANUP.AVAILABLE_AT, time(until)).where(
                CHARACTER_LIBRARY_CLEANUP.OWNER_ID.eq(job.owner()).and(CHARACTER_LIBRARY_CLEANUP.ID.eq(job.id())))
                .execute();
    }
    private Profile profile(Record r) {
        return new Profile(r.get(CHARACTER_LIBRARY_PROFILE.ID),
                mapper.treeToValue(
                        mapper.readTree(r.get(CHARACTER_LIBRARY_PROFILE.CONTENT_JSON).data()).path("content"),
                        Content.class),
                r.get(CHARACTER_LIBRARY_PROFILE.VERSION), r.get(CHARACTER_LIBRARY_PROFILE.ARCHIVED),
                r.get(CHARACTER_LIBRARY_PROFILE.CREATED_AT).toInstant(),
                r.get(CHARACTER_LIBRARY_PROFILE.UPDATED_AT).toInstant());
    }
    private Job job(Record r) {
        return new Job(r.get(CHARACTER_LIBRARY_COMMAND.OWNER_ID), r.get(CHARACTER_LIBRARY_COMMAND.ID),
                r.get(CHARACTER_LIBRARY_COMMAND.REQUEST_HASH), Kind.valueOf(r.get(CHARACTER_LIBRARY_COMMAND.KIND)),
                mapper.readTree(r.get(CHARACTER_LIBRARY_COMMAND.INPUT_JSON).data()),
                Status.valueOf(r.get(CHARACTER_LIBRARY_COMMAND.STATUS)), r.get(CHARACTER_LIBRARY_COMMAND.EPOCH),
                r.get(CHARACTER_LIBRARY_COMMAND.RESULT_JSON) == null
                        ? null
                        : mapper.readTree(r.get(CHARACTER_LIBRARY_COMMAND.RESULT_JSON).data()),
                r.get(CHARACTER_LIBRARY_COMMAND.ERROR_CODE));
    }
    private JSONB envelope(Object content) {
        return json(mapper.createObjectNode().put("schemaVersion", 1).set("content", mapper.valueToTree(content)));
    }
    private JSONB json(Object value) {
        return value == null ? null : JSONB.valueOf(mapper.writeValueAsString(value));
    }
    private OffsetDateTime time(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
