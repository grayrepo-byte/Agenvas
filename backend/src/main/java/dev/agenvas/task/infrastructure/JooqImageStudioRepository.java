package dev.agenvas.task.infrastructure;

import static dev.agenvas.db.Tables.IMAGE_STUDIO_BATCH;
import static dev.agenvas.db.Tables.IMAGE_STUDIO_BATCH_TASK;
import static dev.agenvas.db.Tables.TASK;

import dev.agenvas.db.tables.records.ImageStudioBatchRecord;
import dev.agenvas.task.application.ImageStudioRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Owner-key lookup and ordered membership share the acceptance transaction and project row lock. */
@Repository
public class JooqImageStudioRepository implements ImageStudioRepository {
    private final DSLContext dsl;
    private final ObjectMapper mapper;

    public JooqImageStudioRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    @Override
    public Optional<Batch> findByCommand(UUID ownerId, String commandKey) {
        return dsl.selectFrom(IMAGE_STUDIO_BATCH).where(IMAGE_STUDIO_BATCH.OWNER_ID.eq(ownerId))
                .and(IMAGE_STUDIO_BATCH.COMMAND_KEY.eq(commandKey)).fetchOptional(this::map);
    }

    @Override
    public Optional<Batch> find(UUID ownerId, UUID batchId) {
        return dsl.selectFrom(IMAGE_STUDIO_BATCH).where(IMAGE_STUDIO_BATCH.OWNER_ID.eq(ownerId))
                .and(IMAGE_STUDIO_BATCH.ID.eq(batchId)).fetchOptional(this::map);
    }

    @Override
    public List<Batch> list(UUID ownerId, Batch before, int limit) {
        var query = dsl.selectFrom(IMAGE_STUDIO_BATCH).where(IMAGE_STUDIO_BATCH.OWNER_ID.eq(ownerId))
                .and(IMAGE_STUDIO_BATCH.DELETED_AT.isNull());
        if (before != null) {
            var timestamp = before.createdAt().atOffset(ZoneOffset.UTC);
            query = query.and(IMAGE_STUDIO_BATCH.CREATED_AT.lt(timestamp)
                    .or(IMAGE_STUDIO_BATCH.CREATED_AT.eq(timestamp).and(IMAGE_STUDIO_BATCH.ID.lt(before.id()))));
        }
        return query.orderBy(IMAGE_STUDIO_BATCH.CREATED_AT.desc(), IMAGE_STUDIO_BATCH.ID.desc())
                .limit(limit).fetch(this::map);
    }

    @Override
    public void create(Batch batch) {
        dsl.insertInto(IMAGE_STUDIO_BATCH).set(IMAGE_STUDIO_BATCH.ID, batch.id())
                .set(IMAGE_STUDIO_BATCH.OWNER_ID, batch.ownerId()).set(IMAGE_STUDIO_BATCH.PROJECT_ID, batch.projectId())
                .set(IMAGE_STUDIO_BATCH.COMMAND_KEY, batch.commandKey())
                .set(IMAGE_STUDIO_BATCH.REQUEST_HASH, batch.requestHash())
                .set(IMAGE_STUDIO_BATCH.REQUEST_JSON, JSONB.valueOf(batch.request().toString()))
                .set(IMAGE_STUDIO_BATCH.CREATED_AT, batch.createdAt().atOffset(ZoneOffset.UTC)).execute();
    }

    @Override
    public void addTask(UUID projectId, UUID batchId, int ordinal, UUID taskId) {
        dsl.insertInto(IMAGE_STUDIO_BATCH_TASK).set(IMAGE_STUDIO_BATCH_TASK.BATCH_ID, batchId)
                .set(IMAGE_STUDIO_BATCH_TASK.PROJECT_ID, projectId).set(IMAGE_STUDIO_BATCH_TASK.ORDINAL, ordinal)
                .set(IMAGE_STUDIO_BATCH_TASK.TASK_ID, taskId).execute();
    }

    @Override
    public List<UUID> taskIds(UUID batchId) {
        return dsl.select(IMAGE_STUDIO_BATCH_TASK.TASK_ID).from(IMAGE_STUDIO_BATCH_TASK)
                .where(IMAGE_STUDIO_BATCH_TASK.BATCH_ID.eq(batchId)).orderBy(IMAGE_STUDIO_BATCH_TASK.ORDINAL)
                .fetch(IMAGE_STUDIO_BATCH_TASK.TASK_ID);
    }

    @Override
    public boolean otherBatchReferencesAssets(UUID ownerId, UUID projectId, UUID batchId, Set<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return false;
        }
        // Every task in a batch freezes the same references, including the underlying asset of an alias.
        var references = DSL.table("jsonb_array_elements({0} #> '{mediaInput,images}') as ref(image)",
                TASK.INPUT_JSON);
        var referenceAsset = DSL.field("{0}->>'assetId'", String.class, DSL.name("ref", "image"));
        return dsl.fetchExists(dsl.selectOne().from(IMAGE_STUDIO_BATCH)
                .join(IMAGE_STUDIO_BATCH_TASK).on(IMAGE_STUDIO_BATCH_TASK.BATCH_ID.eq(IMAGE_STUDIO_BATCH.ID)
                        .and(IMAGE_STUDIO_BATCH_TASK.ORDINAL.eq(0)))
                .join(TASK).on(TASK.ID.eq(IMAGE_STUDIO_BATCH_TASK.TASK_ID)
                        .and(TASK.PROJECT_ID.eq(IMAGE_STUDIO_BATCH.PROJECT_ID)))
                .crossJoin(references)
                .where(IMAGE_STUDIO_BATCH.OWNER_ID.eq(ownerId))
                .and(IMAGE_STUDIO_BATCH.PROJECT_ID.eq(projectId))
                .and(IMAGE_STUDIO_BATCH.ID.ne(batchId))
                .and(referenceAsset.in(assetIds.stream().map(UUID::toString).toList())));
    }

    @Override
    public void markDeleted(UUID ownerId, UUID batchId, Instant deletedAt, boolean deleteFiles) {
        int updated = dsl.update(IMAGE_STUDIO_BATCH)
                .set(IMAGE_STUDIO_BATCH.DELETED_AT, deletedAt.atOffset(ZoneOffset.UTC))
                .set(IMAGE_STUDIO_BATCH.DELETE_FILES, deleteFiles)
                .where(IMAGE_STUDIO_BATCH.OWNER_ID.eq(ownerId).and(IMAGE_STUDIO_BATCH.ID.eq(batchId)))
                .and(IMAGE_STUDIO_BATCH.DELETED_AT.isNull())
                .execute();
        if (updated != 1)
            throw new IllegalStateException("Studio batch changed under the project lock");
    }

    private Batch map(ImageStudioBatchRecord row) {
        return new Batch(row.getId(), row.getOwnerId(), row.getProjectId(), row.getCommandKey(),
                row.getRequestHash(), mapper.readTree(row.getRequestJson().data()), row.getCreatedAt().toInstant(),
                row.getDeleteFiles());
    }
}
