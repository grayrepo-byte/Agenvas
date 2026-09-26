package dev.agenvas.project.infrastructure;

import static dev.agenvas.db.Tables.PROJECT;

import dev.agenvas.db.tables.records.ProjectRecord;
import dev.agenvas.project.application.ProjectRepository;
import dev.agenvas.project.domain.Project;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** PostgreSQL 项目仓储；查询始终校验所有者，更新通过版本或槽位条件防止并发覆盖。 */
@Repository
public class JooqProjectRepository implements ProjectRepository {

    /** 执行项目及活动 Run 槽位的类型化 SQL。 */
    private final DSLContext dsl;

    /** 注入项目仓储使用的 jOOQ 上下文。 */
    public JooqProjectRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** 创建项目基础行；项目 ID、事件序号和初始版本由应用服务提供。 */
    @Override
    public void create(Project project) {
        dsl.insertInto(PROJECT)
                .set(PROJECT.ID, project.id())
                .set(PROJECT.OWNER_ID, project.ownerId())
                .set(PROJECT.NAME, project.name())
                .set(PROJECT.ASPECT_RATIO, project.aspectRatio().name())
                .set(PROJECT.STATUS, project.status().name())
                .set(PROJECT.EVENT_SEQ, project.eventSeq())
                .set(PROJECT.VERSION, project.version())
                .set(PROJECT.CREATED_AT, atUtc(project.createdAt()))
                .set(PROJECT.UPDATED_AT, atUtc(project.updatedAt()))
                // 新建项目必定未归档；显式写入 NULL 与原插入语句的列集合保持一致。
                .set(PROJECT.ARCHIVED_AT, (OffsetDateTime) null)
                .execute();
    }

    /** 仅按项目 ID 与所有者 ID 读取项目，避免跨用户查询。 */
    @Override
    public Optional<Project> findById(UUID ownerId, UUID projectId) {
        return dsl.selectFrom(PROJECT)
                .where(PROJECT.ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .fetchOptional(this::map);
    }

    /** 读取项目快照所需事件水位和活动 Run 指针，使用一次一致的 SQL 视图。 */
    @Override
    public Optional<SnapshotAnchor> findSnapshotAnchor(UUID ownerId, UUID projectId) {
        return dsl.select(PROJECT.EVENT_SEQ, PROJECT.ACTIVE_RUN_ID)
                .from(PROJECT)
                .where(PROJECT.ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .fetchOptional(row -> new SnapshotAnchor(
                        row.get(PROJECT.EVENT_SEQ),
                        row.get(PROJECT.ACTIVE_RUN_ID)));
    }

    /** 锁定项目行并读取活动 Run 槽位，供创建 Run 的事务串行化。 */
    @Override
    public Optional<RunSlot> lockRunSlot(UUID ownerId, UUID projectId) {
        return dsl.select(PROJECT.STATUS, PROJECT.ACTIVE_RUN_ID)
                .from(PROJECT)
                .where(PROJECT.ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .forUpdate()
                .fetchOptional(row -> new RunSlot(
                        Project.Status.valueOf(row.get(PROJECT.STATUS)),
                        row.get(PROJECT.ACTIVE_RUN_ID)));
    }

    /** 仅当项目仍活动且槽位为空时占用活动 Run 槽位。 */
    @Override
    public boolean claimRunSlot(
            UUID ownerId, UUID projectId, UUID runId, Instant updatedAt) {
        return dsl.update(PROJECT)
                .set(PROJECT.ACTIVE_RUN_ID, runId)
                .set(PROJECT.UPDATED_AT, atUtc(updatedAt))
                .where(PROJECT.ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .and(PROJECT.STATUS.eq(Project.Status.ACTIVE.name()))
                .and(PROJECT.ACTIVE_RUN_ID.isNull())
                .execute() == 1;
    }

    /** 仅由占用该槽位的同一 Run 释放项目活动槽位。 */
    @Override
    public boolean releaseRunSlot(
            UUID ownerId, UUID projectId, UUID runId, Instant updatedAt) {
        return dsl.update(PROJECT)
                .set(PROJECT.ACTIVE_RUN_ID, (UUID) null)
                .set(PROJECT.UPDATED_AT, atUtc(updatedAt))
                .where(PROJECT.ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .and(PROJECT.ACTIVE_RUN_ID.eq(runId))
                .execute() == 1;
    }

    /** 按创建时间和 ID 稳定倒序游标分页，可按需包含已归档项目。 */
    @Override
    public List<Project> list(
            UUID ownerId,
            boolean includeArchived,
            Instant beforeCreatedAt,
            UUID beforeId,
            int limit) {
        var query = dsl.selectFrom(PROJECT)
                .where(PROJECT.OWNER_ID.eq(ownerId));
        // 原查询写作 (:includeArchived or status = 'ACTIVE')；为真时该谓词恒真，
        // 因此等价于仅在排除归档时附加活动状态条件。
        if (!includeArchived) {
            query = query.and(PROJECT.STATUS.eq(Project.Status.ACTIVE.name()));
        }
        if (beforeCreatedAt != null) {
            // 键集游标 (created_at, id) < (beforeCreatedAt, beforeId) 的展开式；
            // 两列均为 NOT NULL，与 PostgreSQL 行值比较完全等价。
            OffsetDateTime cursor = atUtc(beforeCreatedAt);
            query = query.and(PROJECT.CREATED_AT.lt(cursor)
                    .or(PROJECT.CREATED_AT.eq(cursor).and(PROJECT.ID.lt(beforeId))));
        }
        return query
                .orderBy(PROJECT.CREATED_AT.desc(), PROJECT.ID.desc())
                .limit(limit)
                .fetch(this::map);
    }

    /** 以 expectedVersion 更新活动项目名称和画幅，成功时递增项目版本。 */
    @Override
    public boolean update(
            UUID ownerId,
            UUID projectId,
            long expectedVersion,
            String name,
            Project.AspectRatio aspectRatio,
            Instant updatedAt) {
        return dsl.update(PROJECT)
                .set(PROJECT.NAME, name)
                .set(PROJECT.ASPECT_RATIO, aspectRatio.name())
                .set(PROJECT.UPDATED_AT, atUtc(updatedAt))
                .set(PROJECT.VERSION, PROJECT.VERSION.plus(1))
                .where(PROJECT.ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .and(PROJECT.VERSION.eq(expectedVersion))
                .and(PROJECT.STATUS.eq(Project.Status.ACTIVE.name()))
                .execute() == 1;
    }

    /** 仅在所有者、活动状态和预期版本匹配时归档项目并递增版本。 */
    @Override
    public boolean archive(
            UUID ownerId, UUID projectId, long expectedVersion, Instant archivedAt) {
        return dsl.update(PROJECT)
                .set(PROJECT.STATUS, Project.Status.ARCHIVED.name())
                .set(PROJECT.ARCHIVED_AT, atUtc(archivedAt))
                .set(PROJECT.UPDATED_AT, atUtc(archivedAt))
                .set(PROJECT.VERSION, PROJECT.VERSION.plus(1))
                .where(PROJECT.ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .and(PROJECT.VERSION.eq(expectedVersion))
                .and(PROJECT.STATUS.eq(Project.Status.ACTIVE.name()))
                .execute() == 1;
    }

    /** 将数据库项目行还原为领域对象，并把所有时间统一读取为 Instant。 */
    private Project map(ProjectRecord row) {
        return new Project(
                row.getId(),
                row.getOwnerId(),
                row.getName(),
                Project.AspectRatio.valueOf(row.getAspectRatio()),
                Project.Status.valueOf(row.getStatus()),
                row.getEventSeq(),
                row.getVersion(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant(),
                Optional.ofNullable(row.getArchivedAt())
                        .map(OffsetDateTime::toInstant)
                        .orElse(null));
    }

    /** 将 Instant 转成 PostgreSQL timestamptz 参数所需的 UTC 时间。 */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
