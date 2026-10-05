package dev.agenvas.llm.infrastructure;

import static dev.agenvas.db.Tables.LLM_TURN;

import dev.agenvas.db.tables.records.LlmTurnRecord;
import dev.agenvas.llm.application.LlmTurn;
import dev.agenvas.llm.application.LlmTurnRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 模型回合账本；Run/步骤唯一，响应仅能从 REQUESTED 条件更新一次。 */
@Repository
public class JooqLlmTurnRepository implements LlmTurnRepository {

    /** 执行模型请求与响应检查点查询。 */
    private final DSLContext dsl;
    /** 将完整模型请求和响应 JSON 还原为持久化回合数据。 */
    private final ObjectMapper mapper;

    /** 注入模型回合账本查询上下文与 JSON 映射器。 */
    public JooqLlmTurnRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    /** 在模型网络调用前创建 REQUESTED 检查点；每个 Run 步骤最多一条记录。 */
    @Override
    public boolean insertRequested(UUID projectId, UUID runId, int stepIndex,
            int modelConfigVersion, JsonNode request, Instant now) {
        return dsl.insertInto(LLM_TURN)
                .set(LLM_TURN.PROJECT_ID, projectId)
                .set(LLM_TURN.RUN_ID, runId)
                .set(LLM_TURN.STEP_INDEX, stepIndex)
                .set(LLM_TURN.STATUS, LlmTurn.Status.REQUESTED.name())
                .set(LLM_TURN.MODEL_CONFIG_VERSION, modelConfigVersion)
                .set(LLM_TURN.REQUEST_JSON, JSONB.valueOf(request.toString()))
                .set(LLM_TURN.CREATED_AT, utc(now))
                .onConflict(LLM_TURN.RUN_ID, LLM_TURN.STEP_INDEX)
                .doNothing()
                .execute() == 1;
    }

    /** 读取完整保存的请求、响应及模型配置版本。 */
    @Override
    public Optional<LlmTurn> find(UUID projectId, UUID runId, int stepIndex) {
        return dsl.selectFrom(LLM_TURN)
                .where(LLM_TURN.PROJECT_ID.eq(projectId))
                .and(LLM_TURN.RUN_ID.eq(runId))
                .and(LLM_TURN.STEP_INDEX.eq(stepIndex))
                .fetchOptional(this::map);
    }

    /** 仅在响应为空且状态仍为 REQUESTED 时保存完整模型响应。 */
    @Override
    public boolean saveResponse(UUID projectId, UUID runId, int stepIndex,
            JsonNode response, Instant now) {
        return dsl.update(LLM_TURN)
                .set(LLM_TURN.STATUS, LlmTurn.Status.RESPONDED.name())
                .set(LLM_TURN.RESPONSE_JSON, JSONB.valueOf(response.toString()))
                .set(LLM_TURN.RESPONDED_AT, utc(now))
                .where(LLM_TURN.PROJECT_ID.eq(projectId))
                .and(LLM_TURN.RUN_ID.eq(runId))
                .and(LLM_TURN.STEP_INDEX.eq(stepIndex))
                .and(LLM_TURN.STATUS.eq(LlmTurn.Status.REQUESTED.name()))
                .and(LLM_TURN.RESPONSE_JSON.isNull())
                .execute() == 1;
    }

    /** 将数据库行还原为不可变回合实体；REQUESTED 时响应与响应时间仍为空。 */
    private LlmTurn map(LlmTurnRecord row) {
        return new LlmTurn(row.getProjectId(), row.getRunId(), row.getStepIndex(),
                LlmTurn.Status.valueOf(row.getStatus()),
                row.getModelConfigVersion(),
                mapper.readTree(row.getRequestJson().data()),
                row.getResponseJson() == null
                        ? null : mapper.readTree(row.getResponseJson().data()),
                row.getCreatedAt().toInstant(),
                Optional.ofNullable(row.getRespondedAt())
                        .map(OffsetDateTime::toInstant).orElse(null));
    }

    /** 将 Instant 转为数据库 timestamptz 列要求的 UTC 偏移时间。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
