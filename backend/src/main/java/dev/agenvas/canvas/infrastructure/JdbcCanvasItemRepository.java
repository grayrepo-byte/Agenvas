package dev.agenvas.canvas.infrastructure;

import dev.agenvas.canvas.application.CanvasItemRepository;
import dev.agenvas.canvas.domain.CanvasItem;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL CanvasItem repository; every read and write carries the project owner boundary. */
@Repository
public class JdbcCanvasItemRepository implements CanvasItemRepository {

    private static final RowMapper<CanvasItem> ITEM_MAPPER = (resultSet, rowNumber) ->
            new CanvasItem(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("project_id", UUID.class),
                    CanvasItem.SubjectType.valueOf(resultSet.getString("subject_type")),
                    resultSet.getObject("subject_id", UUID.class),
                    resultSet.getBigDecimal("x"),
                    resultSet.getBigDecimal("y"),
                    resultSet.getBigDecimal("width"),
                    resultSet.getBigDecimal("height"),
                    resultSet.getInt("z_index"),
                    resultSet.getObject("group_id", UUID.class),
                    resultSet.getBoolean("locked"),
                    resultSet.getLong("version"),
                    resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                    resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbcClient;

    public JdbcCanvasItemRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<CanvasItem> list(UUID ownerId, UUID projectId) {
        return jdbcClient.sql("""
                        select ci.id, ci.project_id, ci.subject_type, ci.subject_id,
                               ci.x, ci.y, ci.width, ci.height, ci.z_index, ci.group_id,
                               ci.locked, ci.version, ci.created_at, ci.updated_at
                        from canvas_item ci
                        join project p on p.id = ci.project_id
                        where ci.project_id = :projectId and p.owner_id = :ownerId
                        order by ci.z_index, ci.id
                        """)
                .param("ownerId", ownerId)
                .param("projectId", projectId)
                .query(ITEM_MAPPER)
                .list();
    }

    @Override
    public Optional<CanvasItem> findForUpdate(
            UUID ownerId, UUID projectId, UUID itemId) {
        return jdbcClient.sql("""
                        select ci.id, ci.project_id, ci.subject_type, ci.subject_id,
                               ci.x, ci.y, ci.width, ci.height, ci.z_index, ci.group_id,
                               ci.locked, ci.version, ci.created_at, ci.updated_at
                        from canvas_item ci
                        join project p on p.id = ci.project_id
                        where ci.id = :itemId and ci.project_id = :projectId
                          and p.owner_id = :ownerId
                        for update of ci
                        """)
                .param("itemId", itemId)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query(ITEM_MAPPER)
                .optional();
    }

    @Override
    public boolean create(CanvasItem item) {
        return jdbcClient.sql("""
                        insert into canvas_item (
                            id, project_id, subject_type, subject_id, artifact_id, agent_instance_id,
                            x, y, width, height, z_index, group_id, locked, version,
                            created_at, updated_at
                        ) values (
                            :id, :projectId, :subjectType, :subjectId, :artifactId, :agentInstanceId,
                            :x, :y, :width, :height, :zIndex, :groupId, :locked, :version,
                            :createdAt, :updatedAt
                        )
                        on conflict (id) do nothing
                        """)
                .param("id", item.id())
                .param("projectId", item.projectId())
                .param("subjectType", item.subjectType().name())
                .param("subjectId", item.subjectId())
                .param(
                        "artifactId",
                        item.subjectType() == CanvasItem.SubjectType.ARTIFACT
                                ? item.subjectId()
                                : null,
                        java.sql.Types.OTHER)
                .param(
                        "agentInstanceId",
                        item.subjectType() == CanvasItem.SubjectType.AGENT
                                ? item.subjectId()
                                : null,
                        java.sql.Types.OTHER)
                .param("x", item.x())
                .param("y", item.y())
                .param("width", item.width())
                .param("height", item.height())
                .param("zIndex", item.zIndex())
                .param("groupId", item.groupId(), java.sql.Types.OTHER)
                .param("locked", item.locked())
                .param("version", item.version())
                .param("createdAt", utc(item.createdAt()))
                .param("updatedAt", utc(item.updatedAt()))
                .update() == 1;
    }

    @Override
    public boolean update(
            UUID ownerId, CanvasItem item, long expectedVersion, Instant updatedAt) {
        return jdbcClient.sql("""
                        update canvas_item ci
                        set x = :x,
                            y = :y,
                            width = :width,
                            height = :height,
                            z_index = :zIndex,
                            group_id = :groupId,
                            locked = :locked,
                            version = ci.version + 1,
                            updated_at = :updatedAt
                        from project p
                        where ci.id = :itemId and ci.project_id = :projectId
                          and p.id = ci.project_id and p.owner_id = :ownerId
                          and ci.version = :expectedVersion
                        """)
                .param("x", item.x())
                .param("y", item.y())
                .param("width", item.width())
                .param("height", item.height())
                .param("zIndex", item.zIndex())
                .param("groupId", item.groupId(), java.sql.Types.OTHER)
                .param("locked", item.locked())
                .param("updatedAt", utc(updatedAt))
                .param("itemId", item.id())
                .param("projectId", item.projectId())
                .param("ownerId", ownerId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    @Override
    public boolean delete(
            UUID ownerId, UUID projectId, UUID itemId, long expectedVersion) {
        return jdbcClient.sql("""
                        delete from canvas_item ci
                        using project p
                        where ci.id = :itemId and ci.project_id = :projectId
                          and p.id = ci.project_id and p.owner_id = :ownerId
                          and ci.version = :expectedVersion
                        """)
                .param("itemId", itemId)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
