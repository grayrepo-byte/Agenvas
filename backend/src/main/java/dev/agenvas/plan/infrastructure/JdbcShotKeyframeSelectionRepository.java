package dev.agenvas.plan.infrastructure;

import dev.agenvas.plan.application.ShotKeyframeSelection;
import dev.agenvas.plan.application.ShotKeyframeSelectionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL 关键帧选择实现；唯一约束和版本条件保证每镜头只有一条可并发更新的选择。 */
@Repository
public class JdbcShotKeyframeSelectionRepository implements ShotKeyframeSelectionRepository {

    /** 执行带参数绑定的 SQL，避免将资源标识拼入查询文本。 */
    private final JdbcClient jdbc;

    /** 注入 JDBC 查询执行器。
     * @param jdbc Spring JDBC 客户端
     */
    public JdbcShotKeyframeSelectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 按项目和镜头读取当前选择，避免只凭镜头 UUID 跨项目命中。
     * @param projectId 选择所属项目
     * @param shotArtifactId 镜头产物 UUID
     * @return 已保存选择；镜头尚未选择关键帧时为空
     */
    @Override
    public Optional<ShotKeyframeSelection> find(UUID projectId, UUID shotArtifactId) {
        return jdbc.sql("""
                        select project_id, shot_artifact_id, shot_version_id,
                            image_artifact_id, image_version_id, source_task_id,
                            selected_by_user_id, version, created_at, updated_at
                        from shot_keyframe_selection
                        where project_id = :projectId and shot_artifact_id = :shotId
                        """)
                .param("projectId", projectId).param("shotId", shotArtifactId)
                .query((rs, row) -> new ShotKeyframeSelection(
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("shot_artifact_id", UUID.class),
                        rs.getObject("shot_version_id", UUID.class),
                        rs.getObject("image_artifact_id", UUID.class),
                        rs.getObject("image_version_id", UUID.class),
                        rs.getObject("source_task_id", UUID.class),
                        rs.getObject("selected_by_user_id", UUID.class),
                        rs.getLong("version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** 首次创建选择；并发首次写入时由唯一键让其中一个插入失败。
     * @param selection 镜头、图片版本和用户组成的选择关系
     * @return 本次是否成功创建选择
     */
    @Override
    public boolean insert(ShotKeyframeSelection selection) {
        return jdbc.sql("""
                        insert into shot_keyframe_selection (project_id, shot_artifact_id,
                            shot_version_id, image_artifact_id, image_version_id,
                            source_task_id, selected_by_user_id, version, created_at, updated_at)
                        values (:projectId, :shotId, :shotVersionId, :imageId, :imageVersionId,
                            :taskId, :userId, 0, :createdAt, :updatedAt)
                        on conflict (project_id, shot_artifact_id) do nothing
                        """)
                .param("projectId", selection.projectId())
                .param("shotId", selection.shotArtifactId())
                .param("shotVersionId", selection.shotVersionId())
                .param("imageId", selection.imageArtifactId())
                .param("imageVersionId", selection.imageVersionId())
                .param("taskId", selection.sourceTaskId())
                .param("userId", selection.selectedByUserId())
                .param("createdAt", selection.createdAt().atOffset(ZoneOffset.UTC))
                .param("updatedAt", selection.updatedAt().atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    /** 仅当现有版本等于 expectedVersion 时替换选择并递增版本。
     * @param selection 新的镜头与图片固定版本
     * @param expectedVersion 客户端读取到的旧选择版本
     * @return 是否恰好更新一行；false 表示版本冲突或关系不存在
     */
    @Override
    public boolean update(ShotKeyframeSelection selection, long expectedVersion) {
        return jdbc.sql("""
                        update shot_keyframe_selection set
                            shot_version_id = :shotVersionId,
                            image_artifact_id = :imageId,
                            image_version_id = :imageVersionId,
                            source_task_id = :taskId,
                            selected_by_user_id = :userId,
                            version = version + 1,
                            updated_at = :updatedAt
                        where project_id = :projectId and shot_artifact_id = :shotId
                            and version = :expectedVersion
                        """)
                .param("projectId", selection.projectId())
                .param("shotId", selection.shotArtifactId())
                .param("shotVersionId", selection.shotVersionId())
                .param("imageId", selection.imageArtifactId())
                .param("imageVersionId", selection.imageVersionId())
                .param("taskId", selection.sourceTaskId())
                .param("userId", selection.selectedByUserId())
                .param("updatedAt", selection.updatedAt().atOffset(ZoneOffset.UTC))
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }
}
