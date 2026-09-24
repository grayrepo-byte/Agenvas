package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.ToolExecution;
import dev.agenvas.llm.application.ToolExecutionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL tool ledger; unique call keys arbitrate retries across workers. */
@Repository
public class JdbcToolExecutionRepository implements ToolExecutionRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcToolExecutionRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public long countByRun(UUID projectId, UUID runId) {
        return jdbc.sql("select count(*) from tool_execution where project_id = :projectId and run_id = :runId")
                .param("projectId", projectId).param("runId", runId)
                .query(Long.class).single();
    }

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

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
