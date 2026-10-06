package dev.agenvas.llm.infrastructure;

import static dev.agenvas.db.Tables.TOOL_EXECUTION;

import dev.agenvas.db.tables.records.ToolExecutionRecord;
import dev.agenvas.llm.application.RunAction;
import dev.agenvas.llm.application.ToolExecution;
import dev.agenvas.llm.application.ToolExecutionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 工具执行账本；唯一调用键在多 Worker 重试时仲裁唯一副作用。 */
@Repository
public class JooqToolExecutionRepository implements ToolExecutionRepository {

    /** 执行工具执行账本的查询与条件更新。 */
    private final DSLContext dsl;
    /** 将已完成工具结果从 JSONB 还原为结果树。 */
    private final ObjectMapper mapper;

    /** 注入查询上下文与工具结果 JSON 映射器。 */
    public JooqToolExecutionRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    /** 统计 Run 已登记的工具调用数，用于执行预算限制。 */
    @Override
    public long countByRun(UUID projectId, UUID runId) {
        return dsl.fetchCount(TOOL_EXECUTION, TOOL_EXECUTION.PROJECT_ID.eq(projectId)
                .and(TOOL_EXECUTION.RUN_ID.eq(runId)));
    }

    /** JSONB 只提取服务端写入的公开摘要和业务状态，避免载入私有结果字段。 */
    @Override
    public List<RunAction> listCompletedActions(UUID projectId, UUID runId, int limit) {
        Field<String> resultStatus =
                DSL.jsonbGetAttributeAsText(TOOL_EXECUTION.RESULT_JSON, "status").as("result_status");
        Field<String> summary =
                DSL.jsonbGetAttributeAsText(TOOL_EXECUTION.RESULT_JSON, "userVisibleSummary").as("summary");
        return dsl.select(TOOL_EXECUTION.ID, TOOL_EXECUTION.STEP_INDEX, TOOL_EXECUTION.TOOL_NAME,
                        resultStatus, summary, TOOL_EXECUTION.COMPLETED_AT)
                .from(TOOL_EXECUTION)
                .where(TOOL_EXECUTION.PROJECT_ID.eq(projectId))
                .and(TOOL_EXECUTION.RUN_ID.eq(runId))
                .and(TOOL_EXECUTION.STATUS.eq(ToolExecution.Status.COMPLETED.name()))
                .orderBy(TOOL_EXECUTION.STEP_INDEX, TOOL_EXECUTION.CREATED_AT, TOOL_EXECUTION.ID)
                .limit(limit)
                .fetch(record -> new RunAction(record.get(TOOL_EXECUTION.ID),
                        record.get(TOOL_EXECUTION.STEP_INDEX),
                        record.get(TOOL_EXECUTION.TOOL_NAME),
                        RunAction.Status.valueOf(record.get(resultStatus)),
                        record.get(summary),
                        record.get(TOOL_EXECUTION.COMPLETED_AT).toInstant()));
    }

    @Override
    public List<JsonNode> skillReads(UUID projectId, UUID runId) {
        return dsl.select(TOOL_EXECUTION.RESULT_JSON).from(TOOL_EXECUTION)
                .where(TOOL_EXECUTION.PROJECT_ID.eq(projectId)).and(TOOL_EXECUTION.RUN_ID.eq(runId))
                .and(TOOL_EXECUTION.STATUS.eq(ToolExecution.Status.COMPLETED.name()))
                .and(TOOL_EXECUTION.TOOL_NAME.in("read_skill", "read_skill_resource"))
                .orderBy(TOOL_EXECUTION.STEP_INDEX, TOOL_EXECUTION.CREATED_AT, TOOL_EXECUTION.ID)
                .limit(dev.agenvas.run.domain.AgentRun.MAX_TOOL_EXECUTIONS)
                .fetch(row -> mapper.readTree(row.get(TOOL_EXECUTION.RESULT_JSON).data()).path("data"));
    }

    /** 按 Run、步骤和 toolCallId 读取去重记录及已完成结果。 */
    @Override
    public Optional<ToolExecution> find(UUID projectId, UUID runId,
            int stepIndex, String toolCallId) {
        return dsl.selectFrom(TOOL_EXECUTION)
                .where(TOOL_EXECUTION.PROJECT_ID.eq(projectId))
                .and(TOOL_EXECUTION.RUN_ID.eq(runId))
                .and(TOOL_EXECUTION.STEP_INDEX.eq(stepIndex))
                .and(TOOL_EXECUTION.TOOL_CALL_ID.eq(toolCallId))
                .fetchOptional(this::map);
    }

    /** 以唯一调用键插入 EXECUTING 记录；冲突时交由调用方读取既有结果。 */
    @Override
    public boolean insertExecuting(UUID id, UUID projectId, UUID runId, int stepIndex,
            String toolCallId, String toolName, String argumentHash, Instant now) {
        return dsl.insertInto(TOOL_EXECUTION)
                .set(TOOL_EXECUTION.ID, id)
                .set(TOOL_EXECUTION.PROJECT_ID, projectId)
                .set(TOOL_EXECUTION.RUN_ID, runId)
                .set(TOOL_EXECUTION.STEP_INDEX, stepIndex)
                .set(TOOL_EXECUTION.TOOL_CALL_ID, toolCallId)
                .set(TOOL_EXECUTION.TOOL_NAME, toolName)
                .set(TOOL_EXECUTION.ARGUMENT_HASH, argumentHash)
                .set(TOOL_EXECUTION.STATUS, ToolExecution.Status.EXECUTING.name())
                .set(TOOL_EXECUTION.CREATED_AT, utc(now))
                .onConflict(TOOL_EXECUTION.RUN_ID, TOOL_EXECUTION.STEP_INDEX, TOOL_EXECUTION.TOOL_CALL_ID)
                .doNothing()
                .execute() == 1;
    }

    /** 只允许 EXECUTING 且尚无结果的记录完成一次并保存 JSON 结果。 */
    @Override
    public boolean complete(UUID id, JsonNode result, Instant now) {
        return dsl.update(TOOL_EXECUTION)
                .set(TOOL_EXECUTION.STATUS, ToolExecution.Status.COMPLETED.name())
                .set(TOOL_EXECUTION.RESULT_JSON, JSONB.valueOf(result.toString()))
                .set(TOOL_EXECUTION.COMPLETED_AT, utc(now))
                .where(TOOL_EXECUTION.ID.eq(id))
                .and(TOOL_EXECUTION.STATUS.eq(ToolExecution.Status.EXECUTING.name()))
                .and(TOOL_EXECUTION.RESULT_JSON.isNull())
                .execute() == 1;
    }

    /** 将已完成工具结果的 JSONB 还原为结果树；EXECUTING 时结果为空。 */
    private ToolExecution map(ToolExecutionRecord row) {
        return new ToolExecution(row.getId(), row.getProjectId(), row.getRunId(),
                row.getStepIndex(), row.getToolCallId(), row.getToolName(),
                row.getArgumentHash(), ToolExecution.Status.valueOf(row.getStatus()),
                row.getResultJson() == null ? null : mapper.readTree(row.getResultJson().data()));
    }

    /** 将绝对时刻转换为 PostgreSQL timestamptz 列所需的 UTC 偏移时间。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
