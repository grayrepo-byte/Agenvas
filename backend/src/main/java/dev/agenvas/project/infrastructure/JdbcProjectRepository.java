package dev.agenvas.project.infrastructure;

import dev.agenvas.project.application.ProjectRepository;
import dev.agenvas.project.domain.Project;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL project repository with owner-scoped and parameterized queries. */
@Repository
public class JdbcProjectRepository implements ProjectRepository {

    private static final RowMapper<Project> PROJECT_MAPPER = (resultSet, rowNumber) -> new Project(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("owner_id", UUID.class),
            resultSet.getString("name"),
            Project.AspectRatio.valueOf(resultSet.getString("aspect_ratio")),
            Project.Status.valueOf(resultSet.getString("status")),
            resultSet.getLong("event_seq"),
            resultSet.getLong("version"),
            resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
            resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
            Optional.ofNullable(resultSet.getObject("archived_at", OffsetDateTime.class))
                    .map(OffsetDateTime::toInstant)
                    .orElse(null));

    private final JdbcClient jdbcClient;

    public JdbcProjectRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void create(Project project) {
        jdbcClient.sql("""
                        insert into project (
                            id, owner_id, name, aspect_ratio, status, event_seq, version,
                            created_at, updated_at, archived_at
                        ) values (
                            :id, :ownerId, :name, :aspectRatio, :status, :eventSeq, :version,
                            :createdAt, :updatedAt, null
                        )
                        """)
                .param("id", project.id())
                .param("ownerId", project.ownerId())
                .param("name", project.name())
                .param("aspectRatio", project.aspectRatio().name())
                .param("status", project.status().name())
                .param("eventSeq", project.eventSeq())
                .param("version", project.version())
                .param("createdAt", project.createdAt().atOffset(ZoneOffset.UTC))
                .param("updatedAt", project.updatedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<Project> findById(UUID ownerId, UUID projectId) {
        return jdbcClient.sql("""
                        select id, owner_id, name, aspect_ratio, status, event_seq, version,
                               created_at, updated_at, archived_at
                        from project
                        where id = :projectId and owner_id = :ownerId
                        """)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query(PROJECT_MAPPER)
                .optional();
    }

    @Override
    public Optional<SnapshotAnchor> findSnapshotAnchor(UUID ownerId, UUID projectId) {
        return jdbcClient.sql("""
                        select event_seq, active_run_id
                        from project
                        where id = :projectId and owner_id = :ownerId
                        """)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query((resultSet, rowNumber) -> new SnapshotAnchor(
                        resultSet.getLong("event_seq"),
                        resultSet.getObject("active_run_id", UUID.class)))
                .optional();
    }

    @Override
    public Optional<RunSlot> lockRunSlot(UUID ownerId, UUID projectId) {
        return jdbcClient.sql("""
                        select status, active_run_id
                        from project
                        where id = :projectId and owner_id = :ownerId
                        for update
                        """)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query((resultSet, rowNumber) -> new RunSlot(
                        Project.Status.valueOf(resultSet.getString("status")),
                        resultSet.getObject("active_run_id", UUID.class)))
                .optional();
    }

    @Override
    public boolean claimRunSlot(
            UUID ownerId, UUID projectId, UUID runId, Instant updatedAt) {
        return jdbcClient.sql("""
                        update project
                        set active_run_id = :runId, updated_at = :updatedAt
                        where id = :projectId and owner_id = :ownerId
                          and status = 'ACTIVE' and active_run_id is null
                        """)
                .param("runId", runId)
                .param("updatedAt", updatedAt.atOffset(ZoneOffset.UTC))
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .update() == 1;
    }

    @Override
    public boolean releaseRunSlot(
            UUID ownerId, UUID projectId, UUID runId, Instant updatedAt) {
        return jdbcClient.sql("""
                        update project
                        set active_run_id = null, updated_at = :updatedAt
                        where id = :projectId and owner_id = :ownerId
                          and active_run_id = :runId
                        """)
                .param("updatedAt", updatedAt.atOffset(ZoneOffset.UTC))
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .param("runId", runId)
                .update() == 1;
    }

    @Override
    public List<Project> list(
            UUID ownerId,
            boolean includeArchived,
            Instant beforeCreatedAt,
            UUID beforeId,
            int limit) {
        String cursorPredicate = beforeCreatedAt == null
                ? ""
                : "and (created_at, id) < (:beforeCreatedAt, :beforeId)";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        select id, owner_id, name, aspect_ratio, status, event_seq, version,
                               created_at, updated_at, archived_at
                        from project
                        where owner_id = :ownerId
                          and (:includeArchived or status = 'ACTIVE')
                        """ + cursorPredicate + """
                        order by created_at desc, id desc
                        limit :limit
                        """)
                .param("ownerId", ownerId)
                .param("includeArchived", includeArchived)
                .param("limit", limit);
        if (beforeCreatedAt != null) {
            statement = statement
                    .param("beforeCreatedAt", beforeCreatedAt.atOffset(ZoneOffset.UTC))
                    .param("beforeId", beforeId);
        }
        return statement.query(PROJECT_MAPPER).list();
    }

    @Override
    public boolean update(
            UUID ownerId,
            UUID projectId,
            long expectedVersion,
            String name,
            Project.AspectRatio aspectRatio,
            Instant updatedAt) {
        return jdbcClient.sql("""
                        update project
                        set name = :name,
                            aspect_ratio = :aspectRatio,
                            updated_at = :updatedAt,
                            version = version + 1
                        where id = :projectId and owner_id = :ownerId
                          and version = :expectedVersion and status = 'ACTIVE'
                        """)
                .param("name", name)
                .param("aspectRatio", aspectRatio.name())
                .param("updatedAt", updatedAt.atOffset(ZoneOffset.UTC))
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    @Override
    public boolean archive(
            UUID ownerId, UUID projectId, long expectedVersion, Instant archivedAt) {
        return jdbcClient.sql("""
                        update project
                        set status = 'ARCHIVED',
                            archived_at = :archivedAt,
                            updated_at = :archivedAt,
                            version = version + 1
                        where id = :projectId and owner_id = :ownerId
                          and version = :expectedVersion and status = 'ACTIVE'
                        """)
                .param("archivedAt", archivedAt.atOffset(ZoneOffset.UTC))
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }
}
