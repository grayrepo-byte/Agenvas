package dev.agenvas.event.infrastructure;

import static dev.agenvas.db.Tables.PROJECT;
import static dev.agenvas.db.Tables.PROJECT_EVENT;

import dev.agenvas.db.tables.records.ProjectEventRecord;
import dev.agenvas.event.application.ProjectEventRepository;
import dev.agenvas.event.domain.ProjectEvent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 项目事件仓储；项目行锁串行化序号，提交顺序不依赖全局自增 ID。 */
@Repository
public class JooqProjectEventRepository implements ProjectEventRepository {

    /** 执行项目行锁、序号更新及事件日志查询。 */
    private final DSLContext dsl;
    /** 将事件 payload JSONB 反序列化为只读 JSON 树。 */
    private final ObjectMapper objectMapper;

    /** 注入事件查询上下文与 payload 映射器。 */
    public JooqProjectEventRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    /** 在调用方事务内锁定所有者项目行，并读取该项目当前事件水位。 */
    @Override
    public OptionalLong lockCurrentSequence(UUID ownerId, UUID projectId) {
        return dsl.select(PROJECT.EVENT_SEQ)
                .from(PROJECT)
                .where(PROJECT.ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .forUpdate()
                .fetchOptional(PROJECT.EVENT_SEQ)
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    /** 水位仍等于预期值时递增项目序号，提供提交顺序 CAS。 */
    @Override
    public boolean advanceSequence(
            UUID ownerId, UUID projectId, long expectedSequence, long nextSequence) {
        return dsl.update(PROJECT)
                .set(PROJECT.EVENT_SEQ, nextSequence)
                .where(PROJECT.ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .and(PROJECT.EVENT_SEQ.eq(expectedSequence))
                .execute() == 1;
    }

    /** 插入与水位递增同事务提交的事件行。 */
    @Override
    public void insert(ProjectEvent event) {
        dsl.insertInto(PROJECT_EVENT)
                .set(PROJECT_EVENT.PROJECT_ID, event.projectId())
                .set(PROJECT_EVENT.SEQ, event.seq())
                .set(PROJECT_EVENT.EVENT_ID, event.eventId())
                .set(PROJECT_EVENT.TYPE, event.type())
                .set(PROJECT_EVENT.SCHEMA_VERSION, event.schemaVersion())
                .set(PROJECT_EVENT.AGGREGATE_ID, event.aggregateId())
                .set(PROJECT_EVENT.AGGREGATE_VERSION, event.aggregateVersion())
                .set(PROJECT_EVENT.PAYLOAD_JSON, JSONB.valueOf(event.payload().toString()))
                .set(PROJECT_EVENT.OCCURRED_AT, atUtc(event.occurredAt()))
                .execute();
    }

    /** 只返回该所有者项目中游标之后的连续有序补发页。 */
    @Override
    public List<ProjectEvent> listAfter(
            UUID ownerId, UUID projectId, long afterSequence, int limit) {
        return dsl.select(PROJECT_EVENT.fields())
                .from(PROJECT_EVENT)
                .join(PROJECT).on(PROJECT.ID.eq(PROJECT_EVENT.PROJECT_ID))
                .where(PROJECT_EVENT.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .and(PROJECT_EVENT.SEQ.gt(afterSequence))
                .orderBy(PROJECT_EVENT.SEQ)
                .limit(limit)
                .fetch(record -> map(record.into(PROJECT_EVENT)));
    }

    /** 同时读取最新水位和最早保留事件序号；项目越权或不存在时返回 null。 */
    @Override
    public CursorBounds cursorBounds(UUID ownerId, UUID projectId) {
        Field<Long> oldestRetainedSeq = DSL.select(DSL.min(PROJECT_EVENT.SEQ))
                .from(PROJECT_EVENT)
                .where(PROJECT_EVENT.PROJECT_ID.eq(PROJECT.ID))
                .asField("oldest_retained_seq");
        return dsl.select(PROJECT.EVENT_SEQ, oldestRetainedSeq)
                .from(PROJECT)
                .where(PROJECT.ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .fetchOptional(record ->
                        new CursorBounds(record.get(PROJECT.EVENT_SEQ), record.get(oldestRetainedSeq)))
                .orElse(null);
    }

    /** 跨项目按时间清理有界旧事件，不修改项目已分配水位。 */
    @Override
    public int pruneOlderThan(Instant cutoff, int limit) {
        OffsetDateTime cutoffUtc = atUtc(cutoff);
        var expired = dsl.select(PROJECT_EVENT.PROJECT_ID, PROJECT_EVENT.SEQ)
                .from(PROJECT_EVENT)
                .where(PROJECT_EVENT.OCCURRED_AT.lt(cutoffUtc))
                .orderBy(PROJECT_EVENT.OCCURRED_AT, PROJECT_EVENT.PROJECT_ID, PROJECT_EVENT.SEQ)
                .limit(limit);
        return dsl.deleteFrom(PROJECT_EVENT)
                .where(DSL.row(PROJECT_EVENT.PROJECT_ID, PROJECT_EVENT.SEQ).in(expired))
                .execute();
    }

    /** 将数据库事件列映射为不可变领域事件。 */
    private ProjectEvent map(ProjectEventRecord row) {
        return new ProjectEvent(
                row.getProjectId(),
                row.getSeq(),
                row.getEventId(),
                row.getType(),
                row.getSchemaVersion(),
                row.getAggregateId(),
                row.getAggregateVersion(),
                objectMapper.readTree(row.getPayloadJson().data()),
                row.getOccurredAt().toInstant());
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
