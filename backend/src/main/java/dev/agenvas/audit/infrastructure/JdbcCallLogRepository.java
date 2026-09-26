package dev.agenvas.audit.infrastructure;

import dev.agenvas.audit.application.CallLogRepository;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.audit.domain.CallLogPage;
import dev.agenvas.task.domain.Task;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCallLogRepository implements CallLogRepository {
    private final JdbcClient jdbc;

    public JdbcCallLogRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public void start(UUID id, CallLogService.CallDescriptor value, String traceId, Instant startedAt) {
        jdbc.sql("""
                INSERT INTO call_log (id, project_id, task_id, run_id, step_index, kind, operation,
                    status, provider, model, trace_id, started_at, mock)
                VALUES (:id, :projectId, :taskId, :runId, :stepIndex, :kind, :operation,
                    'RUNNING', :provider, :model, :traceId, :startedAt, :mock)
                """)
                .param("id", id).param("projectId", value.projectId())
                .param("taskId", value.taskId(), Types.OTHER).param("runId", value.runId(), Types.OTHER)
                .param("stepIndex", value.stepIndex(), Types.INTEGER).param("kind", value.kind().name())
                .param("operation", value.operation().name()).param("provider", value.provider(), Types.VARCHAR)
                .param("model", value.model(), Types.VARCHAR).param("traceId", traceId)
                .param("startedAt", utc(startedAt)).param("mock", value.mock()).update();
    }

    @Override
    public void finish(UUID id, CallLogService.CallOutcome value, Instant respondedAt, long durationMs) {
        int updated = jdbc.sql("""
                UPDATE call_log SET status = :status, provider_request_id = :requestId,
                    error_code = :errorCode, responded_at = :respondedAt, duration_ms = :durationMs
                WHERE id = :id AND status = 'RUNNING'
                """)
                .param("id", id).param("status", value.status().name())
                .param("requestId", value.providerRequestId(), Types.VARCHAR)
                .param("errorCode", value.errorCode(), Types.VARCHAR)
                .param("respondedAt", utc(respondedAt)).param("durationMs", durationMs).update();
        if (updated != 1) throw new IllegalStateException("Call response already recorded or missing");
    }

    // Legacy rows are explicitly checkpoints, not fabricated network observations. The NOT EXISTS
    // guards only compare corresponding SUBMIT/CHAT operations so new polls preserve old submissions.
    private static final String SOURCE = """
            WITH owned_projects AS (SELECT id, name FROM project WHERE owner_id = :ownerId),
            calls AS (
              SELECT c.id, c.project_id, p.name AS project_title, c.task_id, c.run_id, c.kind,
                c.operation,
                CASE WHEN c.status = 'RUNNING' AND
                  ((c.task_id IS NOT NULL AND (t.lease_until IS NULL OR t.lease_until <= now()
                    OR t.status NOT IN ('RUNNING', 'SUBMITTING')))
                   OR (c.task_id IS NULL AND NOT EXISTS (SELECT 1 FROM task active
                    WHERE active.run_id = c.run_id AND active.kind = 'AGENT_TURN'
                      AND active.status = 'RUNNING' AND active.lease_until > now())))
                  THEN 'UNKNOWN' ELSE c.status END AS status,
                t.status AS task_status, c.provider, c.model, c.trace_id, c.provider_request_id,
                c.error_code, c.started_at, c.responded_at, c.duration_ms, false AS historical, c.mock
              FROM call_log c JOIN owned_projects p ON p.id = c.project_id
              LEFT JOIN task t ON t.id = c.task_id
              UNION ALL
              SELECT t.id, t.project_id, p.name, t.id, t.run_id,
                CASE t.kind WHEN 'IMAGE_GENERATION' THEN 'IMAGE' ELSE 'VIDEO' END,
                'LEGACY', CASE WHEN t.status = 'SUCCEEDED' THEN 'SUCCEEDED'
                  WHEN t.status IN ('FAILED', 'CANCELED', 'BLOCKED') THEN 'FAILED'
                  WHEN t.status IN ('RUNNING', 'SUBMITTING', 'WAITING_PROVIDER') THEN 'RUNNING'
                  ELSE 'UNKNOWN' END,
                t.status, null, null, null, t.provider_request_id, t.error_code,
                (SELECT min(a.created_at) FROM provider_attempt a WHERE a.task_id = t.id),
                null::timestamptz, null::bigint, true,
                COALESCE(t.input_json->>'workflowVersion', '') LIKE 'mock-%'
              FROM task t JOIN owned_projects p ON p.id = t.project_id
              WHERE t.kind IN ('IMAGE_GENERATION', 'VIDEO_GENERATION')
                AND EXISTS (SELECT 1 FROM provider_attempt a WHERE a.task_id = t.id)
                AND NOT EXISTS (SELECT 1 FROM call_log c WHERE c.task_id = t.id AND c.operation = 'SUBMIT')
              UNION ALL
              SELECT md5(l.run_id::text || ':' || l.step_index::text)::uuid, l.project_id,
                p.name, null::uuid, l.run_id, 'LLM', 'LEGACY',
                CASE WHEN l.status = 'RESPONDED' THEN 'SUCCEEDED' ELSE 'UNKNOWN' END,
                null, null, null, null, null, null, l.created_at, null::timestamptz, null::bigint,
                true, COALESCE(r.policy_snapshot_json->>'modelConfigSource', '') = 'mock'
              FROM llm_turn l JOIN owned_projects p ON p.id = l.project_id
              JOIN agent_run r ON r.id = l.run_id
              WHERE NOT EXISTS (SELECT 1 FROM call_log c WHERE c.run_id = l.run_id
                AND c.step_index = l.step_index AND c.operation = 'CHAT')
            )
            """;

    @Override
    public CallLogPage list(UUID ownerId, CallLogService.Filter filter) {
        StringBuilder where = new StringBuilder(" WHERE true");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("ownerId", ownerId);
        add(where, parameters, "project_id", "projectId", filter.projectId());
        add(where, parameters, "kind", "kind", filter.kind() == null ? null : filter.kind().name());
        if (filter.status() == CallLog.Status.UNKNOWN) {
            where.append(" AND (status = 'UNKNOWN' OR task_status = 'UNKNOWN')");
        } else {
            add(where, parameters, "status", "status", filter.status() == null ? null : filter.status().name());
        }
        add(where, parameters, "trace_id", "traceId", filter.traceId());
        if (filter.from() != null) {
            where.append(" AND started_at >= :from"); parameters.put("from", utc(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND started_at <= :to"); parameters.put("to", utc(filter.to()));
        }
        long count = jdbc.sql(SOURCE + " SELECT count(*) FROM calls" + where)
                .params(parameters).query(Long.class).single();
        parameters.put("limit", filter.size());
        parameters.put("offset", (long) filter.page() * filter.size());
        var items = jdbc.sql(SOURCE + " SELECT * FROM calls" + where
                        + " ORDER BY started_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .params(parameters).query(this::map).list();
        return new CallLogPage(items, filter.page(), filter.size(), count,
                (count + filter.size() - 1) / filter.size());
    }

    private static void add(StringBuilder where, Map<String, Object> parameters,
            String column, String parameter, Object value) {
        if (value != null) { where.append(" AND ").append(column).append(" = :").append(parameter);
            parameters.put(parameter, value); }
    }
    private CallLog map(ResultSet rs, int row) throws SQLException {
        String taskStatus = rs.getString("task_status");
        OffsetDateTime responded = rs.getObject("responded_at", OffsetDateTime.class);
        return new CallLog(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_title"), rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class), CallLog.Kind.valueOf(rs.getString("kind")),
                CallLog.Operation.valueOf(rs.getString("operation")), CallLog.Status.valueOf(rs.getString("status")),
                taskStatus == null ? null : Task.Status.valueOf(taskStatus),
                CallLogService.safeIdentifier(rs.getString("provider")),
                CallLogService.safeIdentifier(rs.getString("model")), rs.getString("trace_id"),
                CallLogService.safeRequestId(rs.getString("provider_request_id")),
                CallLogService.safeErrorCode(rs.getString("error_code")),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                responded == null ? null : responded.toInstant(), rs.getObject("duration_ms", Long.class),
                rs.getBoolean("historical"), rs.getBoolean("mock"));
    }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
