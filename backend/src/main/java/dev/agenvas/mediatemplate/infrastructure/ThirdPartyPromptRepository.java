package dev.agenvas.mediatemplate.infrastructure;

import static dev.agenvas.db.Tables.THIRD_PARTY_PROMPT;
import static dev.agenvas.db.Tables.THIRD_PARTY_PROMPT_SOURCE;
import static dev.agenvas.db.Tables.THIRD_PARTY_PROMPT_IMPORT;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptSource;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Format;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/** Sync writes only upsert; disabled or missing upstream entries remain readable. */
@Repository
public class ThirdPartyPromptRepository {
    private final DSLContext db;
    private final ObjectMapper mapper;
    public ThirdPartyPromptRepository(DSLContext db, ObjectMapper mapper) { this.db = db; this.mapper = mapper; }
    public record Envelope(int schemaVersion, ThirdPartyPrompt.Image image, ThirdPartyPrompt.Video video) {}
    public record Entry(String id, String sourceId, TargetKind targetKind, ThirdPartyPrompt.Image image,
            ThirdPartyPrompt.Video video, long version, Instant cachedAt, Instant updatedAt) {
        public ThirdPartyPrompt data() { return image == null ? video : image; }
    }
    public record Page(List<Entry> items, long total, int offset, int limit) {}
    public record Counts(int inserted, int updated) {}
    public record ImportCommand(UUID id, UUID ownerId, String payloadHash, Entry snapshot, JsonNode result) {}
    public Optional<ImportCommand> command(UUID owner, UUID project, String key, boolean lock) {
        var query = db.selectFrom(THIRD_PARTY_PROMPT_IMPORT).where(THIRD_PARTY_PROMPT_IMPORT.OWNER_ID.eq(owner)
                .and(THIRD_PARTY_PROMPT_IMPORT.PROJECT_ID.eq(project)).and(THIRD_PARTY_PROMPT_IMPORT.COMMAND_KEY.eq(key)));
        return (lock ? query.forUpdate().fetchOptional() : query.fetchOptional()).map(row -> new ImportCommand(row.getId(), row.getOwnerId(),
                row.getPayloadHash(), mapper.readValue(row.getInputJson().data(), Entry.class),
                row.getResultJson() == null ? null : mapper.readTree(row.getResultJson().data())));
    }
    public void insertCommand(ImportCommand command, UUID project, String key, Instant now) {
        db.insertInto(THIRD_PARTY_PROMPT_IMPORT).set(THIRD_PARTY_PROMPT_IMPORT.ID, command.id())
                .set(THIRD_PARTY_PROMPT_IMPORT.OWNER_ID, command.ownerId()).set(THIRD_PARTY_PROMPT_IMPORT.PROJECT_ID, project)
                .set(THIRD_PARTY_PROMPT_IMPORT.COMMAND_KEY, key).set(THIRD_PARTY_PROMPT_IMPORT.PAYLOAD_HASH, command.payloadHash())
                .set(THIRD_PARTY_PROMPT_IMPORT.INPUT_JSON, JSONB.valueOf(mapper.writeValueAsString(command.snapshot())))
                .set(THIRD_PARTY_PROMPT_IMPORT.CREATED_AT, time(now)).execute();
    }
    public void complete(UUID command, JsonNode result) {
        if (db.update(THIRD_PARTY_PROMPT_IMPORT).set(THIRD_PARTY_PROMPT_IMPORT.RESULT_JSON, JSONB.valueOf(mapper.writeValueAsString(result)))
                .where(THIRD_PARTY_PROMPT_IMPORT.ID.eq(command).and(THIRD_PARTY_PROMPT_IMPORT.RESULT_JSON.isNull())).execute() != 1)
            throw new IllegalStateException("Locked third-party import changed unexpectedly");
    }
    public List<ThirdPartyPromptSource> sources(Instant now) {
        return db.selectFrom(THIRD_PARTY_PROMPT_SOURCE).orderBy(THIRD_PARTY_PROMPT_SOURCE.ID).fetch(r -> source(r, now));
    }
    public Optional<ThirdPartyPromptSource> source(String id, Instant now) {
        return db.selectFrom(THIRD_PARTY_PROMPT_SOURCE).where(THIRD_PARTY_PROMPT_SOURCE.ID.eq(id)).fetchOptional(r -> source(r, now));
    }
    public boolean insertSource(ThirdPartyPromptSource source, Instant now) {
        return db.insertInto(THIRD_PARTY_PROMPT_SOURCE).set(THIRD_PARTY_PROMPT_SOURCE.ID, source.id())
                .set(THIRD_PARTY_PROMPT_SOURCE.NAME, source.name()).set(THIRD_PARTY_PROMPT_SOURCE.TARGET_KIND, source.targetKind().name())
                .set(THIRD_PARTY_PROMPT_SOURCE.FORMAT, source.format().name()).set(THIRD_PARTY_PROMPT_SOURCE.URL, source.url())
                .set(THIRD_PARTY_PROMPT_SOURCE.MODEL, source.model()).set(THIRD_PARTY_PROMPT_SOURCE.NEXT_SYNC_AT, time(now))
                .onConflict(THIRD_PARTY_PROMPT_SOURCE.ID).doNothing().execute() == 1;
    }
    public boolean enabled(String id, boolean enabled, long expected, Instant now) {
        return db.update(THIRD_PARTY_PROMPT_SOURCE).set(THIRD_PARTY_PROMPT_SOURCE.ENABLED, enabled)
                .set(THIRD_PARTY_PROMPT_SOURCE.VERSION, THIRD_PARTY_PROMPT_SOURCE.VERSION.plus(1))
                .set(THIRD_PARTY_PROMPT_SOURCE.NEXT_SYNC_AT, time(now))
                .setNull(THIRD_PARTY_PROMPT_SOURCE.LEASE_TOKEN).setNull(THIRD_PARTY_PROMPT_SOURCE.LEASE_UNTIL)
                .where(THIRD_PARTY_PROMPT_SOURCE.ID.eq(id).and(THIRD_PARTY_PROMPT_SOURCE.VERSION.eq(expected))).execute() == 1;
    }
    public List<String> due(Instant now) {
        return db.select(THIRD_PARTY_PROMPT_SOURCE.ID).from(THIRD_PARTY_PROMPT_SOURCE)
                .where(THIRD_PARTY_PROMPT_SOURCE.ENABLED.isTrue().and(THIRD_PARTY_PROMPT_SOURCE.NEXT_SYNC_AT.le(time(now)))
                        .and(THIRD_PARTY_PROMPT_SOURCE.LEASE_UNTIL.isNull().or(THIRD_PARTY_PROMPT_SOURCE.LEASE_UNTIL.le(time(now)))))
                .orderBy(THIRD_PARTY_PROMPT_SOURCE.NEXT_SYNC_AT).limit(10).fetch(THIRD_PARTY_PROMPT_SOURCE.ID);
    }
    public Optional<ThirdPartyPromptSource> claim(String id, UUID token, Instant now) {
        int changed = db.update(THIRD_PARTY_PROMPT_SOURCE).set(THIRD_PARTY_PROMPT_SOURCE.LEASE_TOKEN, token)
                .set(THIRD_PARTY_PROMPT_SOURCE.LEASE_UNTIL, time(now.plusSeconds(600)))
                .where(THIRD_PARTY_PROMPT_SOURCE.ID.eq(id).and(THIRD_PARTY_PROMPT_SOURCE.ENABLED.isTrue())
                        .and(THIRD_PARTY_PROMPT_SOURCE.LEASE_UNTIL.isNull().or(THIRD_PARTY_PROMPT_SOURCE.LEASE_UNTIL.le(time(now)))))
                .execute();
        return changed == 1 ? source(id, now) : Optional.empty();
    }
    public boolean ownsLease(String id, UUID token, Instant now) {
        // Lock before cache writes so disabling the source cannot race with publication.
        return db.selectFrom(THIRD_PARTY_PROMPT_SOURCE).where(THIRD_PARTY_PROMPT_SOURCE.ID.eq(id)
                .and(THIRD_PARTY_PROMPT_SOURCE.LEASE_TOKEN.eq(token)).and(THIRD_PARTY_PROMPT_SOURCE.ENABLED.isTrue())
                .and(THIRD_PARTY_PROMPT_SOURCE.LEASE_UNTIL.gt(time(now)))).forUpdate().fetchOptional().isPresent();
    }
    public void finish(String id, UUID token, Instant now, String error) {
        var update = db.update(THIRD_PARTY_PROMPT_SOURCE).setNull(THIRD_PARTY_PROMPT_SOURCE.LEASE_TOKEN)
                .setNull(THIRD_PARTY_PROMPT_SOURCE.LEASE_UNTIL).set(THIRD_PARTY_PROMPT_SOURCE.LAST_ERROR, error)
                .set(THIRD_PARTY_PROMPT_SOURCE.NEXT_SYNC_AT, time(now.plusSeconds(error == null ? 86_400 : 3600)));
        if (error == null) update.set(THIRD_PARTY_PROMPT_SOURCE.LAST_SYNCED_AT, time(now));
        update.where(THIRD_PARTY_PROMPT_SOURCE.ID.eq(id).and(THIRD_PARTY_PROMPT_SOURCE.LEASE_TOKEN.eq(token))).execute();
    }
    public Counts upsert(ThirdPartyPromptSource source, List<ThirdPartyPrompt> prompts, Instant now) {
        int inserted = 0; int updated = 0;
        for (ThirdPartyPrompt p : prompts) {
            Envelope envelope = p instanceof ThirdPartyPrompt.Image image ? new Envelope(1, image, null)
                    : new Envelope(1, null, (ThirdPartyPrompt.Video) p);
            JSONB data = JSONB.valueOf(mapper.writeValueAsString(envelope));
            var result = db.insertInto(THIRD_PARTY_PROMPT).set(THIRD_PARTY_PROMPT.ID, p.id())
                    .set(THIRD_PARTY_PROMPT.SOURCE_ID, p.sourceId()).set(THIRD_PARTY_PROMPT.TARGET_KIND, source.targetKind().name())
                    .set(THIRD_PARTY_PROMPT.TITLE, p.title()).set(THIRD_PARTY_PROMPT.PROMPT, p.prompt()).set(THIRD_PARTY_PROMPT.DATA_JSON, data)
                    .set(THIRD_PARTY_PROMPT.CACHED_AT, time(now)).set(THIRD_PARTY_PROMPT.UPDATED_AT, time(now))
                    .onConflict(THIRD_PARTY_PROMPT.ID).doUpdate().set(THIRD_PARTY_PROMPT.TITLE, p.title())
                    .set(THIRD_PARTY_PROMPT.PROMPT, p.prompt()).set(THIRD_PARTY_PROMPT.DATA_JSON, data)
                    .set(THIRD_PARTY_PROMPT.UPDATED_AT, time(now)).set(THIRD_PARTY_PROMPT.VERSION, THIRD_PARTY_PROMPT.VERSION.plus(1))
                    .where(THIRD_PARTY_PROMPT.DATA_JSON.ne(data)).returning(THIRD_PARTY_PROMPT.VERSION).fetchOne();
            if (result != null) { if (result.getVersion() == 0) inserted++; else updated++; }
        }
        return new Counts(inserted, updated);
    }
    public Page list(TargetKind kind, String source, String query, int offset, int limit) {
        var where = THIRD_PARTY_PROMPT.TARGET_KIND.eq(kind.name());
        if (source != null && !source.isBlank()) where = where.and(THIRD_PARTY_PROMPT.SOURCE_ID.eq(source));
        if (!query.isBlank()) where = where.and(THIRD_PARTY_PROMPT.TITLE.containsIgnoreCase(query)
                .or(THIRD_PARTY_PROMPT.PROMPT.containsIgnoreCase(query)).or(THIRD_PARTY_PROMPT.DATA_JSON.cast(String.class).containsIgnoreCase(query)));
        long total = db.fetchCount(db.selectOne().from(THIRD_PARTY_PROMPT).where(where));
        var entries = db.selectFrom(THIRD_PARTY_PROMPT).where(where).orderBy(THIRD_PARTY_PROMPT.ID).offset(offset).limit(limit).fetch(this::entry);
        return new Page(entries, total, offset, limit);
    }
    public Optional<Entry> entry(String id) {
        return db.selectFrom(THIRD_PARTY_PROMPT).where(THIRD_PARTY_PROMPT.ID.eq(id)).fetchOptional(this::entry);
    }
    private Entry entry(Record row) {
        Envelope envelope = mapper.readValue(row.get(THIRD_PARTY_PROMPT.DATA_JSON).data(), Envelope.class);
        return new Entry(row.get(THIRD_PARTY_PROMPT.ID), row.get(THIRD_PARTY_PROMPT.SOURCE_ID),
                TargetKind.valueOf(row.get(THIRD_PARTY_PROMPT.TARGET_KIND)), envelope.image(), envelope.video(),
                row.get(THIRD_PARTY_PROMPT.VERSION), row.get(THIRD_PARTY_PROMPT.CACHED_AT).toInstant(), row.get(THIRD_PARTY_PROMPT.UPDATED_AT).toInstant());
    }
    private ThirdPartyPromptSource source(Record row, Instant now) {
        var last = row.get(THIRD_PARTY_PROMPT_SOURCE.LAST_SYNCED_AT); var lease = row.get(THIRD_PARTY_PROMPT_SOURCE.LEASE_UNTIL);
        String id = row.get(THIRD_PARTY_PROMPT_SOURCE.ID);
        return new ThirdPartyPromptSource(id, row.get(THIRD_PARTY_PROMPT_SOURCE.NAME), TargetKind.valueOf(row.get(THIRD_PARTY_PROMPT_SOURCE.TARGET_KIND)),
                Format.valueOf(row.get(THIRD_PARTY_PROMPT_SOURCE.FORMAT)), row.get(THIRD_PARTY_PROMPT_SOURCE.URL), row.get(THIRD_PARTY_PROMPT_SOURCE.MODEL),
                row.get(THIRD_PARTY_PROMPT_SOURCE.ENABLED), row.get(THIRD_PARTY_PROMPT_SOURCE.VERSION), row.get(THIRD_PARTY_PROMPT_SOURCE.NEXT_SYNC_AT).toInstant(),
                last == null ? null : last.toInstant(), row.get(THIRD_PARTY_PROMPT_SOURCE.LAST_ERROR),
                db.fetchCount(THIRD_PARTY_PROMPT, THIRD_PARTY_PROMPT.SOURCE_ID.eq(id)), lease != null && lease.toInstant().isAfter(now));
    }
    private static OffsetDateTime time(Instant now) { return now.atOffset(ZoneOffset.UTC); }
}
