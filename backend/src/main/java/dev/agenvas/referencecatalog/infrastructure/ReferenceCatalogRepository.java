package dev.agenvas.referencecatalog.infrastructure;

import static dev.agenvas.db.Tables.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/** PostgreSQL is the truth for settings, last-ready metadata and exact import replay. */
@Repository
public class ReferenceCatalogRepository {
    private final DSLContext db;
    private final ObjectMapper mapper;
    public ReferenceCatalogRepository(DSLContext db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }
    public List<Source> sources() {
        return db.selectFrom(REFERENCE_CATALOG_SOURCE).orderBy(REFERENCE_CATALOG_SOURCE.ID).fetch(this::source);
    }
    public Optional<Source> source(String id) {
        return db.selectFrom(REFERENCE_CATALOG_SOURCE).where(REFERENCE_CATALOG_SOURCE.ID.eq(id))
                .fetchOptional(this::source);
    }
    private Source source(dev.agenvas.db.tables.records.ReferenceCatalogSourceRecord r) {
        return new Source(r.getId(), r.getName(), r.getOwner(), r.getRepository(), r.getRef(), r.getManifestPath(),
                Format.valueOf(r.getFormat()), r.getEnabled(),
                r.getImageDistributionApproved(), r.getVersion(), r.getCurrentSnapshotId(), r.getLastSyncStatus(),
                r.getLastSyncError(), r.getSyncedAt() == null ? null : r.getSyncedAt().toInstant(),
                r.getResolvedCommit(), SourceType.valueOf(r.getSourceType()));
    }
    public boolean insert(Source s, Instant now) {
        return db.insertInto(REFERENCE_CATALOG_SOURCE).set(REFERENCE_CATALOG_SOURCE.ID, s.id())
                .set(REFERENCE_CATALOG_SOURCE.NAME, s.name()).set(REFERENCE_CATALOG_SOURCE.OWNER, s.owner())
                .set(REFERENCE_CATALOG_SOURCE.REPOSITORY, s.repository())
                .set(REFERENCE_CATALOG_SOURCE.REF, s.ref())
                .set(REFERENCE_CATALOG_SOURCE.MANIFEST_PATH, s.manifestPath())
                .set(REFERENCE_CATALOG_SOURCE.FORMAT, s.format().name())
                .set(REFERENCE_CATALOG_SOURCE.SOURCE_TYPE, s.sourceType().name())
                .set(REFERENCE_CATALOG_SOURCE.IMAGE_DISTRIBUTION_APPROVED, s.imageDistributionApproved())
                .set(REFERENCE_CATALOG_SOURCE.CREATED_AT, time(now))
                .onConflict(REFERENCE_CATALOG_SOURCE.ID).doNothing().execute() == 1;
    }
    public boolean update(String id, long expected, boolean enabled, boolean approved) {
        return db.update(REFERENCE_CATALOG_SOURCE)
                .set(REFERENCE_CATALOG_SOURCE.ENABLED, enabled)
                .set(REFERENCE_CATALOG_SOURCE.IMAGE_DISTRIBUTION_APPROVED, approved)
                .set(REFERENCE_CATALOG_SOURCE.VERSION, REFERENCE_CATALOG_SOURCE.VERSION.plus(1))
                .set(REFERENCE_CATALOG_SOURCE.LAST_SYNC_STATUS,
                        enabled
                                ? org.jooq.impl.DSL.when(REFERENCE_CATALOG_SOURCE.CURRENT_SNAPSHOT_ID.isNull(), "NEVER")
                                        .otherwise("SUCCESS")
                                : org.jooq.impl.DSL.val("DISABLED"))
                .setNull(REFERENCE_CATALOG_SOURCE.LAST_SYNC_ERROR).setNull(REFERENCE_CATALOG_SOURCE.LEASE_TOKEN)
                .setNull(REFERENCE_CATALOG_SOURCE.LEASE_UNTIL)
                .where(REFERENCE_CATALOG_SOURCE.ID.eq(id).and(REFERENCE_CATALOG_SOURCE.VERSION.eq(expected)))
                .execute() == 1;
    }
    public boolean claim(String id, long expected, UUID token, Instant now) {
        return db.update(REFERENCE_CATALOG_SOURCE)
                .set(REFERENCE_CATALOG_SOURCE.LEASE_TOKEN, token)
                .set(REFERENCE_CATALOG_SOURCE.LEASE_UNTIL, time(now.plusSeconds(180)))
                .set(REFERENCE_CATALOG_SOURCE.LAST_SYNC_STATUS, "RUNNING")
                .setNull(REFERENCE_CATALOG_SOURCE.LAST_SYNC_ERROR)
                .where(REFERENCE_CATALOG_SOURCE.ID.eq(id).and(REFERENCE_CATALOG_SOURCE.VERSION.eq(expected))
                        .and(REFERENCE_CATALOG_SOURCE.ENABLED.isTrue())
                        .and(REFERENCE_CATALOG_SOURCE.LEASE_UNTIL.isNull()
                                .or(REFERENCE_CATALOG_SOURCE.LEASE_UNTIL.le(time(now)))))
                .execute() == 1;
    }
    public boolean publish(String id, long version, UUID token, Snapshot snapshot, Instant now) {
        int changed = db.update(REFERENCE_CATALOG_SOURCE)
                .set(REFERENCE_CATALOG_SOURCE.CURRENT_SNAPSHOT_ID, snapshot.id())
                .set(REFERENCE_CATALOG_SOURCE.RESOLVED_COMMIT, snapshot.commit())
                .set(REFERENCE_CATALOG_SOURCE.SYNCED_AT, time(now))
                .set(REFERENCE_CATALOG_SOURCE.LAST_SYNC_STATUS, "SUCCESS")
                .setNull(REFERENCE_CATALOG_SOURCE.LAST_SYNC_ERROR)
                .set(REFERENCE_CATALOG_SOURCE.VERSION, REFERENCE_CATALOG_SOURCE.VERSION.plus(1))
                .setNull(REFERENCE_CATALOG_SOURCE.LEASE_TOKEN).setNull(REFERENCE_CATALOG_SOURCE.LEASE_UNTIL)
                .where(REFERENCE_CATALOG_SOURCE.ID.eq(id).and(REFERENCE_CATALOG_SOURCE.VERSION.eq(version))
                        .and(REFERENCE_CATALOG_SOURCE.LEASE_TOKEN.eq(token))
                        .and(REFERENCE_CATALOG_SOURCE.ENABLED.isTrue())
                        .and(REFERENCE_CATALOG_SOURCE.LEASE_UNTIL.gt(time(now))))
                .execute();
        if (changed != 1)
            return false;
        if (!db.fetchExists(REFERENCE_CATALOG_SNAPSHOT,
                REFERENCE_CATALOG_SNAPSHOT.ID.eq(snapshot.id()).and(REFERENCE_CATALOG_SNAPSHOT.SOURCE_ID.eq(id))))
            db.insertInto(REFERENCE_CATALOG_SNAPSHOT).set(REFERENCE_CATALOG_SNAPSHOT.ID, snapshot.id())
                    .set(REFERENCE_CATALOG_SNAPSHOT.SOURCE_ID, id)
                    .set(REFERENCE_CATALOG_SNAPSHOT.COMMIT_SHA, snapshot.commit())
                    .set(REFERENCE_CATALOG_SNAPSHOT.MANIFEST_HASH, snapshot.manifestHash())
                    .set(REFERENCE_CATALOG_SNAPSHOT.LICENSE, snapshot.license())
                    .set(REFERENCE_CATALOG_SNAPSHOT.ENTRIES_JSON, json(new Entries(1, snapshot.entries())))
                    .set(REFERENCE_CATALOG_SNAPSHOT.CREATED_AT, time(now)).execute();
        return true;
    }
    public void fail(String id, UUID token, String code) {
        db.update(REFERENCE_CATALOG_SOURCE).set(REFERENCE_CATALOG_SOURCE.LAST_SYNC_STATUS, "FAILED")
                .set(REFERENCE_CATALOG_SOURCE.LAST_SYNC_ERROR, code).setNull(REFERENCE_CATALOG_SOURCE.LEASE_TOKEN)
                .setNull(REFERENCE_CATALOG_SOURCE.LEASE_UNTIL)
                .where(REFERENCE_CATALOG_SOURCE.ID.eq(id).and(REFERENCE_CATALOG_SOURCE.LEASE_TOKEN.eq(token)))
                .execute();
    }
    public record Entries(int schemaVersion, List<Entry> entries) {
    }
    public Optional<Snapshot> snapshot(String source, UUID id) {
        return db.selectFrom(REFERENCE_CATALOG_SNAPSHOT)
                .where(REFERENCE_CATALOG_SNAPSHOT.ID.eq(id).and(REFERENCE_CATALOG_SNAPSHOT.SOURCE_ID.eq(source)))
                .fetchOptional(r -> new Snapshot(r.getId(), r.getSourceId(), r.getCommitSha(),
                        r.getManifestHash(), r.getLicense(),
                        mapper.readValue(r.getEntriesJson().data(), Entries.class).entries()));
    }
    public record Command(UUID id, String payloadHash, JsonNode input, JsonNode result) {
    }
    public Optional<Command> command(UUID owner, UUID project, String key, boolean lock) {
        var q = db.selectFrom(REFERENCE_CATALOG_IMPORT)
                .where(REFERENCE_CATALOG_IMPORT.OWNER_ID.eq(owner).and(REFERENCE_CATALOG_IMPORT.PROJECT_ID.eq(project))
                        .and(REFERENCE_CATALOG_IMPORT.COMMAND_KEY.eq(key)));
        return (lock ? q.forUpdate().fetchOptional() : q.fetchOptional())
                .map(r -> new Command(r.getId(), r.getPayloadHash(), mapper.readTree(r.getInputJson().data()),
                        r.getResultJson() == null ? null : mapper.readTree(r.getResultJson().data())));
    }
    public void insertCommand(Command c, UUID owner, UUID project, String key, Instant now) {
        db.insertInto(REFERENCE_CATALOG_IMPORT).set(REFERENCE_CATALOG_IMPORT.ID, c.id())
                .set(REFERENCE_CATALOG_IMPORT.OWNER_ID, owner).set(REFERENCE_CATALOG_IMPORT.PROJECT_ID, project)
                .set(REFERENCE_CATALOG_IMPORT.COMMAND_KEY, key)
                .set(REFERENCE_CATALOG_IMPORT.PAYLOAD_HASH, c.payloadHash())
                .set(REFERENCE_CATALOG_IMPORT.INPUT_JSON, json(c.input()))
                .set(REFERENCE_CATALOG_IMPORT.CREATED_AT, time(now)).execute();
    }
    public void complete(UUID id, JsonNode result) {
        if (db.update(REFERENCE_CATALOG_IMPORT).set(REFERENCE_CATALOG_IMPORT.RESULT_JSON, json(result))
                .where(REFERENCE_CATALOG_IMPORT.ID.eq(id).and(REFERENCE_CATALOG_IMPORT.RESULT_JSON.isNull()))
                .execute() != 1)
            throw new IllegalStateException("Locked catalog import changed");
    }
    private JSONB json(Object value) {
        return JSONB.valueOf(mapper.writeValueAsString(value));
    }
    private static OffsetDateTime time(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
