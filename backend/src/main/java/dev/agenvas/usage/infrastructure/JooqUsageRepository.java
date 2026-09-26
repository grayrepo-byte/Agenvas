package dev.agenvas.usage.infrastructure;

import static dev.agenvas.db.Tables.USAGE_LEDGER;

import dev.agenvas.db.tables.records.UsageLedgerRecord;
import dev.agenvas.usage.application.UsageRepository;
import dev.agenvas.usage.domain.UsageEntry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 只追加用量账本；唯一 operationKey 防止任务或模型回合重复计量。 */
@Repository
public class JooqUsageRepository implements UsageRepository {

    /** 执行账本插入、幂等查询和项目历史读取。 */
    private final DSLContext dsl;
    /** 将数量 JSONB 还原为账本领域值。 */
    private final ObjectMapper mapper;

    /** 注入用量账本查询上下文与 JSON 映射器。 */
    public JooqUsageRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    /** 以唯一操作键插入账目；相同操作再次写入时返回 false，不覆盖原记录。 */
    @Override
    public boolean insertOnce(UsageEntry entry) {
        return dsl.insertInto(USAGE_LEDGER)
                .set(USAGE_LEDGER.ID, entry.id())
                .set(USAGE_LEDGER.PROJECT_ID, entry.projectId())
                .set(USAGE_LEDGER.RUN_ID, entry.runId())
                .set(USAGE_LEDGER.TASK_ID, entry.taskId())
                .set(USAGE_LEDGER.OPERATION_KEY, entry.operationKey())
                .set(USAGE_LEDGER.ENTRY_TYPE, entry.entryType().name())
                .set(USAGE_LEDGER.QUANTITY_JSON, JSONB.valueOf(entry.quantity().toString()))
                .set(USAGE_LEDGER.ESTIMATED_COST, entry.estimatedCost())
                .set(USAGE_LEDGER.ACTUAL_COST, entry.actualCost())
                .set(USAGE_LEDGER.CURRENCY, entry.currency())
                .set(USAGE_LEDGER.COST_STATUS, entry.costStatus().name())
                .set(USAGE_LEDGER.COST_SOURCE, entry.costSource())
                .set(USAGE_LEDGER.PROVIDER_CONFIG_VERSION, entry.providerConfigVersion())
                .set(USAGE_LEDGER.WORKFLOW_VERSION, entry.workflowVersion())
                .set(USAGE_LEDGER.MODEL_ID, entry.modelId())
                .set(USAGE_LEDGER.CREATED_AT, OffsetDateTime.ofInstant(entry.createdAt(), ZoneOffset.UTC))
                .onConflict(USAGE_LEDGER.OPERATION_KEY)
                .doNothing()
                .execute() == 1;
    }

    /** 按全局唯一 operationKey 查找既有账目，供服务层核对幂等重放载荷。 */
    @Override
    public Optional<UsageEntry> findByOperationKey(String operationKey) {
        return dsl.selectFrom(USAGE_LEDGER)
                .where(USAGE_LEDGER.OPERATION_KEY.eq(operationKey))
                .fetchOptional(this::map);
    }

    /** 按项目和时间顺序读取账目，访问权限由应用服务预先校验。 */
    @Override
    public List<UsageEntry> listProject(UUID projectId) {
        return dsl.selectFrom(USAGE_LEDGER)
                .where(USAGE_LEDGER.PROJECT_ID.eq(projectId))
                .orderBy(USAGE_LEDGER.CREATED_AT, USAGE_LEDGER.ID)
                .fetch(this::map);
    }

    /** 将数据库行中的可空价格、版本来源及数量 JSON 还原为账目实体。 */
    private UsageEntry map(UsageLedgerRecord row) {
        return new UsageEntry(row.getId(),
                row.getProjectId(),
                row.getRunId(),
                row.getTaskId(),
                row.getOperationKey(),
                UsageEntry.EntryType.valueOf(row.getEntryType()),
                mapper.readTree(row.getQuantityJson().data()),
                row.getEstimatedCost(), row.getActualCost(),
                row.getCurrency(),
                UsageEntry.CostStatus.valueOf(row.getCostStatus()),
                row.getCostSource(),
                row.getProviderConfigVersion(),
                row.getWorkflowVersion(), row.getModelId(),
                row.getCreatedAt().toInstant());
    }
}
