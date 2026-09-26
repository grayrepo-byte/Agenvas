package dev.agenvas.audit.infrastructure;

import static dev.agenvas.db.Tables.CALL_LOG;

import dev.agenvas.audit.application.CallLogRepository;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.audit.domain.CallLogPage;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqCallLogRepository implements CallLogRepository {
    private final DSLContext dsl;

    public JooqCallLogRepository(DSLContext dsl) { this.dsl = dsl; }

    @Override
    public void start(UUID id, CallLogService.CallDescriptor value, String traceId, Instant startedAt) {
        dsl.insertInto(CALL_LOG)
                .set(CALL_LOG.ID, id)
                .set(CALL_LOG.PROJECT_ID, value.projectId())
                .set(CALL_LOG.TASK_ID, value.taskId())
                .set(CALL_LOG.RUN_ID, value.runId())
                .set(CALL_LOG.STEP_INDEX, value.stepIndex())
                .set(CALL_LOG.KIND, value.kind().name())
                .set(CALL_LOG.OPERATION, value.operation().name())
                .set(CALL_LOG.STATUS, CallLog.Status.RUNNING.name())
                .set(CALL_LOG.PROVIDER, value.provider())
                .set(CALL_LOG.MODEL, value.model())
                .set(CALL_LOG.TRACE_ID, traceId)
                .set(CALL_LOG.STARTED_AT, utc(startedAt))
                .set(CALL_LOG.MOCK, value.mock())
                .execute();
    }

    @Override
    public void finish(UUID id, CallLogService.CallOutcome value, Instant respondedAt, long durationMs) {
        int updated = dsl.update(CALL_LOG)
                .set(CALL_LOG.STATUS, value.status().name())
                .set(CALL_LOG.PROVIDER_REQUEST_ID, value.providerRequestId())
                .set(CALL_LOG.ERROR_CODE, value.errorCode())
                .set(CALL_LOG.RESPONDED_AT, utc(respondedAt))
                .set(CALL_LOG.DURATION_MS, durationMs)
                .where(CALL_LOG.ID.eq(id))
                .and(CALL_LOG.STATUS.eq(CallLog.Status.RUNNING.name()))
                .execute();
        if (updated != 1) throw new IllegalStateException("Call response already recorded or missing");
    }

    // Legacy rows are explicitly checkpoints, not fabricated network observations. The NOT EXISTS
    // guards only compare corresponding SUBMIT/CHAT operations so new polls preserve old submissions.
    // 这段 CTE 由三路 UNION ALL、jsonb 取值、md5 摘要与 uuid 转换组成，DSL 表达会明显比原 SQL 更难核对；
    // 因此按 ADR 0012 保留 SQL 文本，仍通过 jOOQ 的位置绑定占位符执行（唯一的绑定值是首行的 ownerId）。
    private static final String SOURCE = """
            WITH owned_projects AS (SELECT id, name FROM project WHERE owner_id = ?),
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
        // 位置绑定值必须与 SQL 文本中的 ? 顺序一致：CTE 的 ownerId 在前，随后是 WHERE 子句。
        List<Object> bindings = new ArrayList<>();
        bindings.add(ownerId);
        add(where, bindings, "project_id", filter.projectId());
        add(where, bindings, "kind", filter.kind() == null ? null : filter.kind().name());
        if (filter.status() == CallLog.Status.UNKNOWN) {
            where.append(" AND (status = 'UNKNOWN' OR task_status = 'UNKNOWN')");
        } else {
            add(where, bindings, "status", filter.status() == null ? null : filter.status().name());
        }
        add(where, bindings, "trace_id", filter.traceId());
        if (filter.from() != null) {
            where.append(" AND started_at >= ?"); bindings.add(utc(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND started_at <= ?"); bindings.add(utc(filter.to()));
        }
        long count = dsl.fetchSingle(SOURCE + " SELECT count(*) FROM calls" + where,
                bindings.toArray()).get(0, Long.class);
        List<Object> pageBindings = new ArrayList<>(bindings);
        pageBindings.add(filter.size());
        pageBindings.add((long) filter.page() * filter.size());
        var items = dsl.fetch(SOURCE + " SELECT * FROM calls" + where
                        + " ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?",
                pageBindings.toArray()).map(this::map);
        return new CallLogPage(items, filter.page(), filter.size(), count,
                (count + filter.size() - 1) / filter.size());
    }

    private static void add(StringBuilder where, List<Object> bindings,
            String column, Object value) {
        if (value != null) { where.append(" AND ").append(column).append(" = ?");
            bindings.add(value); }
    }
    private CallLog map(Record row) {
        String taskStatus = row.get("task_status", String.class);
        OffsetDateTime responded = row.get("responded_at", OffsetDateTime.class);
        return new CallLog(row.get("id", UUID.class), row.get("project_id", UUID.class),
                row.get("project_title", String.class), row.get("task_id", UUID.class),
                row.get("run_id", UUID.class), CallLog.Kind.valueOf(row.get("kind", String.class)),
                CallLog.Operation.valueOf(row.get("operation", String.class)), CallLog.Status.valueOf(row.get("status", String.class)),
                taskStatus == null ? null : Task.Status.valueOf(taskStatus),
                CallLogService.safeIdentifier(row.get("provider", String.class)),
                CallLogService.safeIdentifier(row.get("model", String.class)), row.get("trace_id", String.class),
                CallLogService.safeRequestId(row.get("provider_request_id", String.class)),
                CallLogService.safeErrorCode(row.get("error_code", String.class)),
                row.get("started_at", OffsetDateTime.class).toInstant(),
                responded == null ? null : responded.toInstant(), row.get("duration_ms", Long.class),
                row.get("historical", Boolean.class), row.get("mock", Boolean.class));
    }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
