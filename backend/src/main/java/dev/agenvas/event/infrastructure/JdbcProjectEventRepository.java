package dev.agenvas.event.infrastructure;

import dev.agenvas.event.application.ProjectEventRepository;
import dev.agenvas.event.domain.ProjectEvent;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL event repository using the project row as the commit-order serialization point. */
@Repository
public class JdbcProjectEventRepository implements ProjectEventRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final RowMapper<ProjectEvent> eventMapper;

    public JdbcProjectEventRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.eventMapper = (resultSet, rowNumber) -> new ProjectEvent(
                resultSet.getObject("project_id", UUID.class),
                resultSet.getLong("seq"),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("type"),
                resultSet.getInt("schema_version"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getLong("aggregate_version"),
                this.objectMapper.readTree(resultSet.getString("payload_json")),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }

    @Override
    public OptionalLong lockCurrentSequence(UUID ownerId, UUID projectId) {
        return jdbcClient.sql("""
                        select event_seq
                        from project
                        where id = :projectId and owner_id = :ownerId
                        for update
                        """)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    @Override
    public boolean advanceSequence(
            UUID ownerId, UUID projectId, long expectedSequence, long nextSequence) {
        return jdbcClient.sql("""
                        update project
                        set event_seq = :nextSequence
                        where id = :projectId and owner_id = :ownerId
                          and event_seq = :expectedSequence
                        """)
                .param("nextSequence", nextSequence)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .param("expectedSequence", expectedSequence)
                .update() == 1;
    }

    @Override
    public void insert(ProjectEvent event) {
        jdbcClient.sql("""
                        insert into project_event (
                            project_id, seq, event_id, type, schema_version,
                            aggregate_id, aggregate_version, payload_json, occurred_at
                        ) values (
                            :projectId, :seq, :eventId, :type, :schemaVersion,
                            :aggregateId, :aggregateVersion, cast(:payloadJson as jsonb), :occurredAt
                        )
                        """)
                .param("projectId", event.projectId())
                .param("seq", event.seq())
                .param("eventId", event.eventId())
                .param("type", event.type())
                .param("schemaVersion", event.schemaVersion())
                .param("aggregateId", event.aggregateId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("payloadJson", event.payload().toString())
                .param("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public List<ProjectEvent> listAfter(
            UUID ownerId, UUID projectId, long afterSequence, int limit) {
        return jdbcClient.sql("""
                        select e.project_id, e.seq, e.event_id, e.type, e.schema_version,
                               e.aggregate_id, e.aggregate_version, e.payload_json, e.occurred_at
                        from project_event e
                        join project p on p.id = e.project_id
                        where e.project_id = :projectId and p.owner_id = :ownerId
                          and e.seq > :afterSequence
                        order by e.seq
                        limit :limit
                        """)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .param("afterSequence", afterSequence)
                .param("limit", limit)
                .query(eventMapper)
                .list();
    }

    @Override
    public CursorBounds cursorBounds(UUID ownerId, UUID projectId) {
        return jdbcClient.sql("""
                        select p.event_seq,
                               (select min(e.seq) from project_event e
                                where e.project_id = p.id) as oldest_retained_seq
                        from project p
                        where p.id = :projectId and p.owner_id = :ownerId
                        """)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query((resultSet, rowNumber) -> new CursorBounds(
                        resultSet.getLong("event_seq"),
                        resultSet.getObject("oldest_retained_seq", Long.class)))
                .optional()
                .orElse(null);
    }

    @Override
    public int pruneOlderThan(Instant cutoff, int limit) {
        return jdbcClient.sql("""
                        with expired as (
                            select project_id, seq
                            from project_event
                            where occurred_at < :cutoff
                            order by occurred_at, project_id, seq
                            limit :limit
                        )
                        delete from project_event e
                        using expired x
                        where e.project_id = x.project_id and e.seq = x.seq
                        """)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .update();
    }
}
