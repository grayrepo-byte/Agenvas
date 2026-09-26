package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.RunAction;
import dev.agenvas.llm.application.ToolExecution;
import dev.agenvas.llm.application.ToolExecutionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 工具执行账本；唯一调用键在多 Worker 重试时仲裁唯一副作用。 */
@Repository
public class JdbcToolExecutionRepository implements ToolExecutionRepository {

    /** 执行工具执行记录的参数化 SQL。 */
    private final JdbcClient jdbc;
    /** 将已完成工具结果从 JSONB 还原为结果树。 */
    private final ObjectMapper mapper;

    /** 注入 SQL 执行器与工具结果 JSON 映射器。 */
    public JdbcToolExecutionRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 统计 Run 已登记的工具调用数，用于执行预算限制。 */
    @Override
    public long countByRun(UUID projectId, UUID runId) {
        return jdbc.sql("select count(*) from tool_execution where project_id = :projectId and run_id = :runId")
                .param("projectId", projectId).param("runId", runId)
                .query(Long.class).single();
    }

    /** JSONB 只提取服务端写入的公开摘要和业务状态，避免载入私有结果字段。 */
    @Override
    public List<RunAction> listCompletedActions(UUID projectId, UUID runId, int limit) {
        return jdbc.sql("""
                        select id, step_index, tool_name,
                               result_json ->> 'status' as result_status,
                               result_json ->> 'userVisibleSummary' as summary, completed_at
                        from tool_execution
                        where project_id = :projectId and run_id = :runId and status = 'COMPLETED'
                        order by step_index, created_at, id
                        limit :limit
                        """)
                .param("projectId", projectId).param("runId", runId).param("limit", limit)
                .query((rs, row) -> new RunAction(rs.getObject("id", UUID.class),
                        rs.getInt("step_index"), rs.getString("tool_name"),
                        RunAction.Status.valueOf(rs.getString("result_status")),
                        rs.getString("summary"),
                        rs.getObject("completed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** 按 Run、步骤和 toolCallId 读取去重记录及已完成结果。 */
    @Override
    public Optional<ToolExecution> find(UUID projectId, UUID runId,
            int stepIndex, String toolCallId) {
        return jdbc.sql("""
                        select id, project_id, run_id, step_index, tool_call_id, tool_name,
                               argument_hash, status, result_json::text as result_json
                        from tool_execution where project_id = :projectId and run_id = :runId
                          and step_index = :stepIndex and tool_call_id = :toolCallId
                        """)
                .param("projectId", projectId).param("runId", runId)
                .param("stepIndex", stepIndex).param("toolCallId", toolCallId)
                .query((rs, row) -> new ToolExecution(rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getInt("step_index"), rs.getString("tool_call_id"),
                        rs.getString("tool_name"), rs.getString("argument_hash"),
                        ToolExecution.Status.valueOf(rs.getString("status")),
                        rs.getString("result_json") == null ? null
                                : mapper.readTree(rs.getString("result_json"))))
                .optional();
    }

    /** 以唯一调用键插入 EXECUTING 记录；冲突时交由调用方读取既有结果。 */
    @Override
    public boolean insertExecuting(UUID id, UUID projectId, UUID runId, int stepIndex,
            String toolCallId, String toolName, String argumentHash, Instant now) {
        return jdbc.sql("""
                        insert into tool_execution (id, project_id, run_id, step_index,
                            tool_call_id, tool_name, argument_hash, status, created_at)
                        values (:id, :projectId, :runId, :stepIndex, :toolCallId,
                            :toolName, :argumentHash, 'EXECUTING', :now)
                        on conflict (run_id, step_index, tool_call_id) do nothing
                        """)
                .param("id", id).param("projectId", projectId).param("runId", runId)
                .param("stepIndex", stepIndex).param("toolCallId", toolCallId)
                .param("toolName", toolName).param("argumentHash", argumentHash)
                .param("now", utc(now)).update() == 1;
    }

    /** 只允许 EXECUTING 且尚无结果的记录完成一次并保存 JSON 结果。 */
    @Override
    public boolean complete(UUID id, JsonNode result, Instant now) {
        return jdbc.sql("""
                        update tool_execution set status = 'COMPLETED',
                            result_json = cast(:result as jsonb), completed_at = :now
                        where id = :id and status = 'EXECUTING' and result_json is null
                        """)
                .param("id", id).param("result", result.toString())
                .param("now", utc(now)).update() == 1;
    }

    /** 将绝对时刻转换为 PostgreSQL 参数所需的 UTC 偏移时间。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
