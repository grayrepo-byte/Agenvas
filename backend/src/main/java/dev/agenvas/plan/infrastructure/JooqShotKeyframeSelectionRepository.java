package dev.agenvas.plan.infrastructure;

import static dev.agenvas.db.Tables.SHOT_KEYFRAME_SELECTION;

import dev.agenvas.db.tables.records.ShotKeyframeSelectionRecord;
import dev.agenvas.plan.application.ShotKeyframeSelection;
import dev.agenvas.plan.application.ShotKeyframeSelectionRepository;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** PostgreSQL 关键帧选择实现；唯一约束和版本条件保证每镜头只有一条可并发更新的选择。 */
@Repository
public class JooqShotKeyframeSelectionRepository implements ShotKeyframeSelectionRepository {

    /** 执行按项目和镜头范围的查询与条件更新。 */
    private final DSLContext dsl;

    /** 注入查询上下文。
     * @param dsl jOOQ 查询上下文
     */
    public JooqShotKeyframeSelectionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** 按项目和镜头读取当前选择，避免只凭镜头 UUID 跨项目命中。
     * @param projectId 选择所属项目
     * @param shotArtifactId 镜头产物 UUID
     * @return 已保存选择；镜头尚未选择关键帧时为空
     */
    @Override
    public Optional<ShotKeyframeSelection> find(UUID projectId, UUID shotArtifactId) {
        return dsl.selectFrom(SHOT_KEYFRAME_SELECTION)
                .where(SHOT_KEYFRAME_SELECTION.PROJECT_ID.eq(projectId))
                .and(SHOT_KEYFRAME_SELECTION.SHOT_ARTIFACT_ID.eq(shotArtifactId))
                .fetchOptional(this::map);
    }

    /** 首次创建选择；并发首次写入时由唯一键让其中一个插入失败。
     * @param selection 镜头、图片版本和用户组成的选择关系
     * @return 本次是否成功创建选择
     */
    @Override
    public boolean insert(ShotKeyframeSelection selection) {
        return dsl.insertInto(SHOT_KEYFRAME_SELECTION)
                .set(SHOT_KEYFRAME_SELECTION.PROJECT_ID, selection.projectId())
                .set(SHOT_KEYFRAME_SELECTION.SHOT_ARTIFACT_ID, selection.shotArtifactId())
                .set(SHOT_KEYFRAME_SELECTION.SHOT_VERSION_ID, selection.shotVersionId())
                .set(SHOT_KEYFRAME_SELECTION.IMAGE_ARTIFACT_ID, selection.imageArtifactId())
                .set(SHOT_KEYFRAME_SELECTION.IMAGE_VERSION_ID, selection.imageVersionId())
                .set(SHOT_KEYFRAME_SELECTION.SOURCE_TASK_ID, selection.sourceTaskId())
                .set(SHOT_KEYFRAME_SELECTION.SELECTED_BY_USER_ID, selection.selectedByUserId())
                .set(SHOT_KEYFRAME_SELECTION.VERSION, 0L)
                .set(SHOT_KEYFRAME_SELECTION.CREATED_AT, selection.createdAt().atOffset(ZoneOffset.UTC))
                .set(SHOT_KEYFRAME_SELECTION.UPDATED_AT, selection.updatedAt().atOffset(ZoneOffset.UTC))
                .onConflict(SHOT_KEYFRAME_SELECTION.PROJECT_ID, SHOT_KEYFRAME_SELECTION.SHOT_ARTIFACT_ID)
                .doNothing()
                .execute() == 1;
    }

    /** 仅当现有版本等于 expectedVersion 时替换选择并递增版本。
     * @param selection 新的镜头与图片固定版本
     * @param expectedVersion 客户端读取到的旧选择版本
     * @return 是否恰好更新一行；false 表示版本冲突或关系不存在
     */
    @Override
    public boolean update(ShotKeyframeSelection selection, long expectedVersion) {
        return dsl.update(SHOT_KEYFRAME_SELECTION)
                .set(SHOT_KEYFRAME_SELECTION.SHOT_VERSION_ID, selection.shotVersionId())
                .set(SHOT_KEYFRAME_SELECTION.IMAGE_ARTIFACT_ID, selection.imageArtifactId())
                .set(SHOT_KEYFRAME_SELECTION.IMAGE_VERSION_ID, selection.imageVersionId())
                .set(SHOT_KEYFRAME_SELECTION.SOURCE_TASK_ID, selection.sourceTaskId())
                .set(SHOT_KEYFRAME_SELECTION.SELECTED_BY_USER_ID, selection.selectedByUserId())
                .set(SHOT_KEYFRAME_SELECTION.VERSION, SHOT_KEYFRAME_SELECTION.VERSION.add(1))
                .set(SHOT_KEYFRAME_SELECTION.UPDATED_AT, selection.updatedAt().atOffset(ZoneOffset.UTC))
                .where(SHOT_KEYFRAME_SELECTION.PROJECT_ID.eq(selection.projectId()))
                .and(SHOT_KEYFRAME_SELECTION.SHOT_ARTIFACT_ID.eq(selection.shotArtifactId()))
                .and(SHOT_KEYFRAME_SELECTION.VERSION.eq(expectedVersion))
                .execute() == 1;
    }

    /** 将选择行还原为不可变选择关系；时间列统一按 UTC 还原。 */
    private ShotKeyframeSelection map(ShotKeyframeSelectionRecord row) {
        return new ShotKeyframeSelection(row.getProjectId(), row.getShotArtifactId(),
                row.getShotVersionId(), row.getImageArtifactId(), row.getImageVersionId(),
                row.getSourceTaskId(), row.getSelectedByUserId(), row.getVersion(),
                row.getCreatedAt().toInstant(), row.getUpdatedAt().toInstant());
    }
}
