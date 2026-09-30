package dev.agenvas.canvas.infrastructure;

import static dev.agenvas.db.Tables.CANVAS_ITEM;
import static dev.agenvas.db.Tables.CANVAS_ITEM_MEDIA_VERSION;
import static dev.agenvas.db.Tables.PROJECT;

import dev.agenvas.canvas.application.CanvasItemRepository;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.db.tables.records.CanvasItemRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** PostgreSQL 画布仓储；读写均核对项目所有者，内容版本不由画布布局更新。 */
@Repository
public class JooqCanvasItemRepository implements CanvasItemRepository {

    /** 执行项目范围内的画布列表、锁定和 CAS 变更。 */
    private final DSLContext dsl;

    /** 注入画布仓储使用的 jOOQ 上下文。 */
    public JooqCanvasItemRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** 按 zIndex 和 ID 稳定排序列出项目画布项。 */
    @Override
    public List<CanvasItem> list(UUID ownerId, UUID projectId) {
        return dsl.select(CANVAS_ITEM.fields())
                .from(CANVAS_ITEM)
                .join(PROJECT).on(PROJECT.ID.eq(CANVAS_ITEM.PROJECT_ID))
                .where(CANVAS_ITEM.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .orderBy(CANVAS_ITEM.Z_INDEX, CANVAS_ITEM.ID)
                .fetch(row -> map(row.into(CANVAS_ITEM)));
    }

    @Override
    public Optional<CanvasItem> find(UUID ownerId, UUID projectId, UUID itemId) {
        return dsl.select(CANVAS_ITEM.fields())
                .from(CANVAS_ITEM)
                .join(PROJECT).on(PROJECT.ID.eq(CANVAS_ITEM.PROJECT_ID))
                .where(CANVAS_ITEM.ID.eq(itemId))
                .and(CANVAS_ITEM.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .fetchOptional(row -> map(row.into(CANVAS_ITEM)));
    }

    /** 锁定单个项目画布项，供应用服务读取后校验布局变更。 */
    @Override
    public Optional<CanvasItem> findForUpdate(
            UUID ownerId, UUID projectId, UUID itemId) {
        return dsl.select(CANVAS_ITEM.fields())
                .from(CANVAS_ITEM)
                .join(PROJECT).on(PROJECT.ID.eq(CANVAS_ITEM.PROJECT_ID))
                .where(CANVAS_ITEM.ID.eq(itemId))
                .and(CANVAS_ITEM.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                // 对应原实现的 "for update of ci"：只锁定画布项行，不锁项目行。
                .forUpdate().of(CANVAS_ITEM)
                .fetchOptional(row -> map(row.into(CANVAS_ITEM)));
    }

    /** 新建画布项；重复 ID 不覆盖已有空间展示状态。 */
    @Override
    public boolean create(CanvasItem item) {
        return dsl.insertInto(CANVAS_ITEM)
                .set(CANVAS_ITEM.ID, item.id())
                .set(CANVAS_ITEM.PROJECT_ID, item.projectId())
                .set(CANVAS_ITEM.SUBJECT_TYPE, item.subjectType().name())
                .set(CANVAS_ITEM.SUBJECT_ID, item.subjectId())
                .set(CANVAS_ITEM.SELECTED_VERSION_ID, item.selectedVersionId())
                .set(CANVAS_ITEM.TITLE, item.title())
                // subject 类型决定填充哪个产物或 Agent 外键，另一个保持 NULL。
                .set(CANVAS_ITEM.ARTIFACT_ID,
                        item.subjectType() == CanvasItem.SubjectType.ARTIFACT
                                ? item.subjectId()
                                : null)
                .set(CANVAS_ITEM.AGENT_INSTANCE_ID,
                        item.subjectType() == CanvasItem.SubjectType.AGENT
                                ? item.subjectId()
                                : null)
                .set(CANVAS_ITEM.X, item.x())
                .set(CANVAS_ITEM.Y, item.y())
                .set(CANVAS_ITEM.WIDTH, item.width())
                .set(CANVAS_ITEM.HEIGHT, item.height())
                .set(CANVAS_ITEM.Z_INDEX, item.zIndex())
                .set(CANVAS_ITEM.GROUP_ID, item.groupId())
                .set(CANVAS_ITEM.LOCKED, item.locked())
                .set(CANVAS_ITEM.VERSION, item.version())
                .set(CANVAS_ITEM.CREATED_AT, atUtc(item.createdAt()))
                .set(CANVAS_ITEM.UPDATED_AT, atUtc(item.updatedAt()))
                .onConflict(CANVAS_ITEM.ID)
                .doNothing()
                .execute() == 1;
    }

    @Override
    public boolean selectVersion(UUID ownerId, UUID projectId, UUID itemId,
            long expectedVersion, UUID selectedVersionId, Instant updatedAt) {
        return dsl.update(CANVAS_ITEM)
                .set(CANVAS_ITEM.SELECTED_VERSION_ID, selectedVersionId)
                .set(CANVAS_ITEM.MEDIA_SELECTION_EPOCH, CANVAS_ITEM.MEDIA_SELECTION_EPOCH.plus(1))
                .set(CANVAS_ITEM.VERSION, CANVAS_ITEM.VERSION.plus(1))
                .set(CANVAS_ITEM.UPDATED_AT, atUtc(updatedAt))
                .where(CANVAS_ITEM.ID.eq(itemId))
                .and(CANVAS_ITEM.PROJECT_ID.eq(projectId))
                .and(CANVAS_ITEM.VERSION.eq(expectedVersion))
                .and(DSL.exists(DSL.selectOne().from(PROJECT)
                        .where(PROJECT.ID.eq(CANVAS_ITEM.PROJECT_ID))
                        .and(PROJECT.OWNER_ID.eq(ownerId))))
                .execute() == 1;
    }

    @Override
    public long mediaSelectionEpoch(UUID ownerId, UUID projectId, UUID itemId) {
        return dsl.select(CANVAS_ITEM.MEDIA_SELECTION_EPOCH).from(CANVAS_ITEM)
                .join(PROJECT).on(PROJECT.ID.eq(CANVAS_ITEM.PROJECT_ID))
                .where(CANVAS_ITEM.PROJECT_ID.eq(projectId)).and(CANVAS_ITEM.ID.eq(itemId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .fetchOptional(CANVAS_ITEM.MEDIA_SELECTION_EPOCH).orElseThrow();
    }

    @Override
    public void addMediaVersion(UUID projectId, UUID itemId, UUID versionId, Instant createdAt) {
        dsl.insertInto(CANVAS_ITEM_MEDIA_VERSION)
                .set(CANVAS_ITEM_MEDIA_VERSION.PROJECT_ID, projectId)
                .set(CANVAS_ITEM_MEDIA_VERSION.CANVAS_ITEM_ID, itemId)
                .set(CANVAS_ITEM_MEDIA_VERSION.ARTIFACT_VERSION_ID, versionId)
                .set(CANVAS_ITEM_MEDIA_VERSION.CREATED_AT, atUtc(createdAt))
                .onConflict(CANVAS_ITEM_MEDIA_VERSION.CANVAS_ITEM_ID,
                        CANVAS_ITEM_MEDIA_VERSION.ARTIFACT_VERSION_ID).doNothing().execute();
    }

    @Override
    public List<UUID> mediaVersionIds(UUID ownerId, UUID projectId, UUID itemId) {
        return dsl.select(CANVAS_ITEM_MEDIA_VERSION.ARTIFACT_VERSION_ID)
                .from(CANVAS_ITEM_MEDIA_VERSION)
                .join(PROJECT).on(PROJECT.ID.eq(CANVAS_ITEM_MEDIA_VERSION.PROJECT_ID))
                .where(CANVAS_ITEM_MEDIA_VERSION.PROJECT_ID.eq(projectId))
                .and(CANVAS_ITEM_MEDIA_VERSION.CANVAS_ITEM_ID.eq(itemId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .orderBy(CANVAS_ITEM_MEDIA_VERSION.CREATED_AT.desc(),
                        CANVAS_ITEM_MEDIA_VERSION.ARTIFACT_VERSION_ID)
                .fetch(CANVAS_ITEM_MEDIA_VERSION.ARTIFACT_VERSION_ID);
    }

    /** 以预期画布项版本更新标题、坐标、尺寸、分组或锁定状态。 */
    @Override
    public boolean update(
            UUID ownerId, CanvasItem item, long expectedVersion, Instant updatedAt) {
        return dsl.update(CANVAS_ITEM)
                .set(CANVAS_ITEM.TITLE, item.title())
                .set(CANVAS_ITEM.X, item.x())
                .set(CANVAS_ITEM.Y, item.y())
                .set(CANVAS_ITEM.WIDTH, item.width())
                .set(CANVAS_ITEM.HEIGHT, item.height())
                .set(CANVAS_ITEM.Z_INDEX, item.zIndex())
                .set(CANVAS_ITEM.GROUP_ID, item.groupId())
                .set(CANVAS_ITEM.LOCKED, item.locked())
                .set(CANVAS_ITEM.VERSION, CANVAS_ITEM.VERSION.plus(1))
                .set(CANVAS_ITEM.UPDATED_AT, atUtc(updatedAt))
                .where(CANVAS_ITEM.ID.eq(item.id()))
                .and(CANVAS_ITEM.PROJECT_ID.eq(item.projectId()))
                .and(CANVAS_ITEM.VERSION.eq(expectedVersion))
                // 原实现用 "from project p where p.id = ci.project_id and p.owner_id = ..." 做所有者校验；
                // project.id 是主键，因此至多匹配一行，等价于相关 EXISTS。
                .and(DSL.exists(DSL.selectOne()
                        .from(PROJECT)
                        .where(PROJECT.ID.eq(CANVAS_ITEM.PROJECT_ID))
                        .and(PROJECT.OWNER_ID.eq(ownerId))))
                .execute() == 1;
    }

    /** 只有项目所有者且布局版本匹配时才删除画布项。 */
    @Override
    public boolean delete(
            UUID ownerId, UUID projectId, UUID itemId, long expectedVersion) {
        return dsl.deleteFrom(CANVAS_ITEM)
                .where(CANVAS_ITEM.ID.eq(itemId))
                .and(CANVAS_ITEM.PROJECT_ID.eq(projectId))
                .and(CANVAS_ITEM.VERSION.eq(expectedVersion))
                // 原实现的 "delete ... using project p" 同样只做所有者校验；project.id 是主键，
                // 不会让同一画布项匹配多行，等价于相关 EXISTS。
                .and(DSL.exists(DSL.selectOne()
                        .from(PROJECT)
                        .where(PROJECT.ID.eq(CANVAS_ITEM.PROJECT_ID))
                        .and(PROJECT.OWNER_ID.eq(ownerId))))
                .execute() == 1;
    }

    /** 将画布行映射为展示项，subject 类型决定对应的产物或 Agent 外键。 */
    private static CanvasItem map(CanvasItemRecord row) {
        return new CanvasItem(
                row.getId(),
                row.getProjectId(),
                CanvasItem.SubjectType.valueOf(row.getSubjectType()),
                row.getSubjectId(),
                row.getSelectedVersionId(),
                row.getTitle(),
                row.getX(),
                row.getY(),
                row.getWidth(),
                row.getHeight(),
                row.getZIndex(),
                row.getGroupId(),
                row.getLocked(),
                row.getVersion(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    /** 将绝对时刻转成 PostgreSQL timestamptz 参数使用的 UTC 时间。 */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
