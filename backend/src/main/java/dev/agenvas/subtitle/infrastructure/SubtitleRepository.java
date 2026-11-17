package dev.agenvas.subtitle.infrastructure;

import static dev.agenvas.db.Tables.SUBTITLE_DRAFT;

import dev.agenvas.subtitle.domain.SubtitleCue;
import dev.agenvas.subtitle.domain.SubtitleDraft;
import dev.agenvas.subtitle.domain.SubtitleTrack;
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

/** Project-scoped storage; the application authorizes ownership before calling this boundary. */
@Repository
public class SubtitleRepository {
    private final DSLContext db;
    private final ObjectMapper mapper;
    public SubtitleRepository(DSLContext db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public Optional<SubtitleDraft> find(UUID project, UUID id) {
        return db.selectFrom(SUBTITLE_DRAFT).where(SUBTITLE_DRAFT.PROJECT_ID.eq(project).and(SUBTITLE_DRAFT.ID.eq(id)))
                .fetchOptional(this::map);
    }
    public List<SubtitleDraft> list(UUID project, List<UUID> sources) {
        // Evaluate totals in the same SQL statement/snapshot as the data. An over-budget
        // batch projects NULL instead of all cue JSON, so JDBC/Jackson never materialize it.
        // PostgreSQL JSONB text is conservative versus the compact API JSON; reserve 1 KiB
        // per draft for fixed-size metadata and array punctuation.
        var totalCues = org.jooq.impl.DSL.field("sum(jsonb_array_length({0}->'cues')) over ()", Long.class,
                SUBTITLE_DRAFT.CUES_JSON);
        var totalBytes = org.jooq.impl.DSL.field("sum(octet_length({0}::text) + 1024) over ()", Long.class,
                SUBTITLE_DRAFT.CUES_JSON);
        var allowed = totalCues.le(10_000L).and(totalBytes.le(8L * 1024 * 1024));
        var fields = java.util.Arrays.stream(SUBTITLE_DRAFT.fields())
                .map(field -> field.equals(SUBTITLE_DRAFT.CUES_JSON)
                        ? org.jooq.impl.DSL.when(allowed, SUBTITLE_DRAFT.CUES_JSON).otherwise((JSONB) null)
                                .as(SUBTITLE_DRAFT.CUES_JSON)
                        : field)
                .toList();
        var rows = db.select(fields).from(SUBTITLE_DRAFT).where(SUBTITLE_DRAFT.PROJECT_ID.eq(project)
                .and(SUBTITLE_DRAFT.SOURCE_VERSION_ID.in(sources))).orderBy(SUBTITLE_DRAFT.ID).limit(100).fetch();
        if (rows.stream().anyMatch(row -> row.get(SUBTITLE_DRAFT.CUES_JSON) == null))
            throw new BatchTooLargeException();
        return rows.map(row -> map(row.into(SUBTITLE_DRAFT)));
    }
    public void create(SubtitleDraft draft, Instant now) {
        if (db.insertInto(SUBTITLE_DRAFT).set(SUBTITLE_DRAFT.ID, draft.id())
                .set(SUBTITLE_DRAFT.PROJECT_ID, draft.projectId())
                .set(SUBTITLE_DRAFT.SOURCE_VERSION_ID, draft.track().sourceVersionId())
                .set(SUBTITLE_DRAFT.SOURCE_DURATION_MS, draft.track().sourceDurationMs())
                .set(SUBTITLE_DRAFT.LANGUAGE, draft.track().language())
                .set(SUBTITLE_DRAFT.CUES_JSON, json(draft.track())).set(SUBTITLE_DRAFT.VERSION, draft.version())
                .set(SUBTITLE_DRAFT.CREATED_AT, utc(now)).set(SUBTITLE_DRAFT.UPDATED_AT, utc(now)).execute() != 1)
            throw new IllegalStateException("Subtitle draft insert failed");
    }
    public boolean save(UUID project, UUID id, long expected, SubtitleTrack track, Instant now) {
        return db.update(SUBTITLE_DRAFT).set(SUBTITLE_DRAFT.CUES_JSON, json(track))
                .set(SUBTITLE_DRAFT.LANGUAGE, track.language())
                .set(SUBTITLE_DRAFT.VERSION, expected + 1).set(SUBTITLE_DRAFT.UPDATED_AT, utc(now))
                .where(SUBTITLE_DRAFT.PROJECT_ID.eq(project).and(SUBTITLE_DRAFT.ID.eq(id))
                        .and(SUBTITLE_DRAFT.VERSION.eq(expected))
                        .and(SUBTITLE_DRAFT.SOURCE_VERSION_ID.eq(track.sourceVersionId())))
                .execute() == 1;
    }
    public boolean attachTask(UUID project, UUID id, long expected, UUID task, Instant now) {
        return db.update(SUBTITLE_DRAFT).set(SUBTITLE_DRAFT.LAST_TASK_ID, task)
                .set(SUBTITLE_DRAFT.VERSION, expected + 1)
                .set(SUBTITLE_DRAFT.UPDATED_AT, utc(now)).where(SUBTITLE_DRAFT.PROJECT_ID.eq(project)
                        .and(SUBTITLE_DRAFT.ID.eq(id)).and(SUBTITLE_DRAFT.VERSION.eq(expected)))
                .execute() == 1;
    }
    public boolean applyRecognition(UUID project, UUID id, long expected, UUID task, SubtitleTrack track, Instant now) {
        return db.update(SUBTITLE_DRAFT).set(SUBTITLE_DRAFT.CUES_JSON, json(track))
                .set(SUBTITLE_DRAFT.LANGUAGE, track.language())
                .set(SUBTITLE_DRAFT.VERSION, expected + 1).set(SUBTITLE_DRAFT.UPDATED_AT, utc(now))
                .where(SUBTITLE_DRAFT.PROJECT_ID.eq(project).and(SUBTITLE_DRAFT.ID.eq(id))
                        .and(SUBTITLE_DRAFT.VERSION.eq(expected))
                        .and(SUBTITLE_DRAFT.LAST_TASK_ID.eq(task))
                        .and(SUBTITLE_DRAFT.SOURCE_VERSION_ID.eq(track.sourceVersionId())))
                .execute() == 1;
    }
    private JSONB json(SubtitleTrack track) {
        var body = mapper.createObjectNode().put("schemaVersion", 1);
        body.set("cues", mapper.valueToTree(track.cues()));
        return JSONB.valueOf(body.toString());
    }
    private SubtitleDraft map(dev.agenvas.db.tables.records.SubtitleDraftRecord row) {
        var cues = mapper.readTree(row.getCuesJson().data()).path("cues").valueStream()
                .map(cue -> new SubtitleCue(cue.path("text").asText(), cue.path("startMs").intValue(),
                        cue.path("endMs").intValue()))
                .toList();
        return new SubtitleDraft(row.getId(), row.getProjectId(),
                new SubtitleTrack(row.getSourceVersionId(), row.getSourceDurationMs(),
                        row.getLanguage(), cues),
                row.getVersion(), row.getLastTaskId(), row.getUpdatedAt().toInstant());
    }
    public static final class BatchTooLargeException extends RuntimeException {
    }

    private static OffsetDateTime utc(Instant now) {
        return now.atOffset(ZoneOffset.UTC);
    }
}
