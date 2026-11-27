package dev.agenvas.task.infrastructure;

import static dev.agenvas.db.Tables.VIDEO_STUDIO_GENERATION;

import dev.agenvas.db.tables.records.VideoStudioGenerationRecord;
import dev.agenvas.task.application.VideoStudioRepository;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
class JooqVideoStudioRepository implements VideoStudioRepository {
    private final DSLContext dsl;
    private final ObjectMapper mapper;

    JooqVideoStudioRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    @Override
    public void create(Generation row) {
        var t = VIDEO_STUDIO_GENERATION;
        dsl.insertInto(t).set(t.ID, row.id()).set(t.OWNER_ID, row.ownerId()).set(t.PROJECT_ID, row.projectId())
                .set(t.TASK_ID, row.taskId()).set(t.COMMAND_KEY, row.commandKey())
                .set(t.REQUEST_HASH, row.requestHash())
                .set(t.REQUEST_JSON, JSONB.valueOf(row.request().toString()))
                .set(t.CREATED_AT, row.createdAt().atOffset(ZoneOffset.UTC)).execute();
    }

    @Override
    public Optional<Generation> find(UUID owner, UUID id) {
        var t = VIDEO_STUDIO_GENERATION;
        return dsl.selectFrom(t).where(t.OWNER_ID.eq(owner).and(t.ID.eq(id))).fetchOptional(this::map);
    }

    @Override
    public Optional<Generation> findByCommand(UUID owner, String commandKey) {
        var t = VIDEO_STUDIO_GENERATION;
        return dsl.selectFrom(t).where(t.OWNER_ID.eq(owner).and(t.COMMAND_KEY.eq(commandKey))).fetchOptional(this::map);
    }

    @Override
    public List<Generation> list(UUID owner, Generation before, int limit) {
        var t = VIDEO_STUDIO_GENERATION;
        var selected = dsl.selectFrom(t).where(t.OWNER_ID.eq(owner)).orderBy(t.CREATED_AT.desc(), t.ID.desc());
        return (before == null
                ? selected.limit(limit)
                : selected.seek(before.createdAt().atOffset(ZoneOffset.UTC), before.id()).limit(limit))
                .fetch(this::map);
    }

    private Generation map(VideoStudioGenerationRecord row) {
        return new Generation(row.getId(), row.getOwnerId(), row.getProjectId(), row.getTaskId(), row.getCommandKey(),
                row.getRequestHash(), mapper.readTree(row.getRequestJson().data()), row.getCreatedAt().toInstant());
    }
}
