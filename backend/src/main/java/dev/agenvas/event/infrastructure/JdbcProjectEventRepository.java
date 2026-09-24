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

/** PostgreSQL 项目事件仓储；项目行锁串行化序号，提交顺序不依赖全局自增 ID。 */
@Repository
public class JdbcProjectEventRepository implements ProjectEventRepository {

    /** 执行项目行锁、序号更新及事件日志 SQL。 */
    private final JdbcClient jdbcClient;
    /** 将事件 payload JSONB 反序列化为只读 JSON 树。 */
    private final ObjectMapper objectMapper;
    /** 将数据库事件列映射为不可变领域事件。 */
    private final RowMapper<ProjectEvent> eventMapper;

    /** 初始化复用事件映射器；时间列统一按数据库时区转换为 Instant。 */
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

    /** 在调用方事务内锁定所有者项目行，并读取该项目当前事件水位。 */
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

    /** 水位仍等于预期值时递增项目序号，提供提交顺序 CAS。 */
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

    /** 插入与水位递增同事务提交的事件行。 */
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

    /** 只返回该所有者项目中游标之后的连续有序补发页。 */
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

    /** 同时读取最新水位和最早保留事件序号；项目越权或不存在时返回 null。 */
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

    /** 跨项目按时间清理有界旧事件，不修改项目已分配水位。 */
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
