package dev.agenvas.plan.infrastructure;

import dev.agenvas.plan.application.ShotKeyframeSelection;
import dev.agenvas.plan.application.ShotKeyframeSelectionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL CAS implementation of one human keyframe selection per shot. */
@Repository
public class JdbcShotKeyframeSelectionRepository implements ShotKeyframeSelectionRepository {

    private final JdbcClient jdbc;

    public JdbcShotKeyframeSelectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

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
