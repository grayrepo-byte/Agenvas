package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.LlmTurn;
import dev.agenvas.llm.application.LlmTurnRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL response ledger with a unique Run/step key and compare-and-set writes. */
@Repository
public class JdbcLlmTurnRepository implements LlmTurnRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcLlmTurnRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public boolean insertRequested(UUID projectId, UUID runId, int stepIndex,
            int modelConfigVersion, JsonNode request, Instant now) {
        return jdbc.sql("""
                        insert into llm_turn (project_id, run_id, step_index, status,
                            model_config_version, request_json, created_at)
                        values (:projectId, :runId, :stepIndex, 'REQUESTED',
                            :modelConfigVersion, cast(:request as jsonb), :now)
                        on conflict (run_id, step_index) do nothing
                        """)
                .param("projectId", projectId)
                .param("runId", runId)
                .param("stepIndex", stepIndex)
                .param("modelConfigVersion", modelConfigVersion)
                .param("request", request.toString())
                .param("now", utc(now))
                .update() == 1;
    }

    @Override
    public Optional<LlmTurn> find(UUID projectId, UUID runId, int stepIndex) {
        return jdbc.sql("""
                        select project_id, run_id, step_index, status, model_config_version,
                               request_json::text as request_json,
                               response_json::text as response_json, created_at, responded_at
                        from llm_turn where project_id = :projectId and run_id = :runId
                          and step_index = :stepIndex
                        """)
                .param("projectId", projectId)
                .param("runId", runId)
                .param("stepIndex", stepIndex)
                .query((rs, row) -> new LlmTurn(
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getInt("step_index"),
                        LlmTurn.Status.valueOf(rs.getString("status")),
                        rs.getInt("model_config_version"),
                        mapper.readTree(rs.getString("request_json")),
                        rs.getString("response_json") == null
                                ? null : mapper.readTree(rs.getString("response_json")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        Optional.ofNullable(rs.getObject("responded_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant).orElse(null)))
                .optional();
    }

    @Override
    public boolean saveResponse(UUID projectId, UUID runId, int stepIndex,
            JsonNode response, Instant now) {
        return jdbc.sql("""
                        update llm_turn set status = 'RESPONDED',
                            response_json = cast(:response as jsonb), responded_at = :now
                        where project_id = :projectId and run_id = :runId
                          and step_index = :stepIndex and status = 'REQUESTED'
                          and response_json is null
                        """)
                .param("projectId", projectId)
                .param("runId", runId)
                .param("stepIndex", stepIndex)
                .param("response", response.toString())
                .param("now", utc(now))
                .update() == 1;
    }

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
