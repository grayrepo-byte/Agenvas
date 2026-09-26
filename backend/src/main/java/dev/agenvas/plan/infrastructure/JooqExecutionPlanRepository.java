package dev.agenvas.plan.infrastructure;

import static dev.agenvas.db.Tables.EXECUTION_PLAN;
import static dev.agenvas.db.Tables.PLAN_APPROVAL;
import static dev.agenvas.db.Tables.PLAN_STEP;

import dev.agenvas.db.tables.records.ExecutionPlanRecord;
import dev.agenvas.db.tables.records.PlanStepRecord;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanRepository;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/** 持久化不可变计划正文、步骤依赖和独立审批凭据的 PostgreSQL 仓储。 */
@Repository
public class JooqExecutionPlanRepository implements ExecutionPlanRepository {

    /** 执行计划、步骤和审批凭据的查询与写入。 */
    private final DSLContext dsl;
    /** 序列化步骤依赖 JSON，并还原已持久化的计划快照。 */
    private final ObjectMapper mapper;

    /** 配置计划仓储的查询上下文与 JSON 映射器。 */
    public JooqExecutionPlanRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    /** 查询指定 Run 与阶段下一个修订号；调用方须在 Run/项目事务锁内串行化写入。 */
    @Override
    public int nextRevision(UUID projectId, UUID runId, ExecutionPlan.Stage stage) {
        Field<Integer> nextRevision =
                DSL.coalesce(DSL.max(EXECUTION_PLAN.REVISION), DSL.inline(0)).add(1).as("next_revision");
        return dsl.select(nextRevision)
                .from(EXECUTION_PLAN)
                .where(EXECUTION_PLAN.PROJECT_ID.eq(projectId))
                .and(EXECUTION_PLAN.RUN_ID.eq(runId))
                .and(EXECUTION_PLAN.STAGE.eq(stage.name()))
                .fetchSingle()
                .value1();
    }

    /** 同一事务写入计划和有序步骤；步骤外键与唯一约束保证结构完整。 */
    @Override
    public void create(ExecutionPlan plan) {
        int inserted = dsl.insertInto(EXECUTION_PLAN)
                .set(EXECUTION_PLAN.ID, plan.id())
                .set(EXECUTION_PLAN.PROJECT_ID, plan.projectId())
                .set(EXECUTION_PLAN.RUN_ID, plan.runId())
                .set(EXECUTION_PLAN.REVISION, plan.revision())
                .set(EXECUTION_PLAN.STAGE, plan.stage().name())
                .set(EXECUTION_PLAN.STATUS, plan.status().name())
                .set(EXECUTION_PLAN.OBJECTIVE, plan.objective())
                .set(EXECUTION_PLAN.PLAN_JSON, JSONB.valueOf(plan.plan().toString()))
                .set(EXECUTION_PLAN.INPUT_SNAPSHOT_JSON, JSONB.valueOf(plan.inputSnapshot().toString()))
                .set(EXECUTION_PLAN.INPUT_SNAPSHOT_HASH, plan.inputSnapshotHash())
                .set(EXECUTION_PLAN.PLAN_HASH, plan.planHash())
                .set(EXECUTION_PLAN.PROVIDER_CONFIG_VERSION, plan.providerConfigVersion())
                .set(EXECUTION_PLAN.WORKFLOW_VERSION, plan.workflowVersion())
                .set(EXECUTION_PLAN.ESTIMATE_JSON, JSONB.valueOf(plan.estimate().toString()))
                .set(EXECUTION_PLAN.CREATED_AT, utc(plan.createdAt()))
                .set(EXECUTION_PLAN.UPDATED_AT, utc(plan.updatedAt()))
                .execute();
        if (inserted != 1) {
            throw new IllegalStateException("Execution plan insert did not affect one row");
        }
        for (ExecutionPlan.Step step : plan.steps()) {
            MediaCapabilityBinding binding = step.binding();
            dsl.insertInto(PLAN_STEP)
                    .set(PLAN_STEP.PLAN_ID, plan.id())
                    .set(PLAN_STEP.PROJECT_ID, plan.projectId())
                    .set(PLAN_STEP.STEP_KEY, step.stepKey())
                    .set(PLAN_STEP.ORDINAL, step.ordinal())
                    .set(PLAN_STEP.KIND, step.kind().name())
                    .set(PLAN_STEP.SHOT_ARTIFACT_ID, step.shotArtifactId())
                    .set(PLAN_STEP.SHOT_VERSION_ID, step.shotVersionId())
                    .set(PLAN_STEP.IMAGE_ARTIFACT_ID, step.imageArtifactId())
                    .set(PLAN_STEP.IMAGE_VERSION_ID, step.imageVersionId())
                    .set(PLAN_STEP.OUTPUT_SLOT_KEY, step.outputSlotKey())
                    .set(PLAN_STEP.INPUT_JSON, JSONB.valueOf(step.input().toString()))
                    .set(PLAN_STEP.DEPENDENCY_KEYS_JSON,
                            JSONB.valueOf(mapper.valueToTree(step.dependencyKeys()).toString()))
                    .set(PLAN_STEP.CAPABILITY_ID, binding == null ? null : binding.capabilityId())
                    .set(PLAN_STEP.CAPABILITY_VERSION, binding == null ? null : binding.capabilityVersion())
                    .set(PLAN_STEP.CONNECTION_ID, binding == null ? null : binding.connectionId())
                    .set(PLAN_STEP.CONNECTION_VERSION, binding == null ? null : binding.connectionVersion())
                    .set(PLAN_STEP.MAPPING_SHA256, binding == null ? null : binding.mappingSha256())
                    .set(PLAN_STEP.ADAPTER_ID, binding == null ? null : binding.adapterId())
                    .execute();
        }
    }

    /** 按项目边界读取计划及其步骤，不加行锁。 */
    @Override
    public Optional<ExecutionPlan> find(UUID projectId, UUID planId) {
        return load(projectId, planId, false);
    }

    /** 返回 Run 的计划 ID，按创建时间和 ID 稳定倒序排列。 */
    @Override
    public List<UUID> findIdsByRun(UUID projectId, UUID runId) {
        return dsl.select(EXECUTION_PLAN.ID)
                .from(EXECUTION_PLAN)
                .where(EXECUTION_PLAN.PROJECT_ID.eq(projectId))
                .and(EXECUTION_PLAN.RUN_ID.eq(runId))
                .orderBy(EXECUTION_PLAN.CREATED_AT.desc(), EXECUTION_PLAN.ID.desc())
                .fetch(EXECUTION_PLAN.ID);
    }

    /** 读取计划并锁住计划行，供审批或拒绝状态迁移使用。 */
    @Override
    public Optional<ExecutionPlan> findForUpdate(UUID projectId, UUID planId) {
        return load(projectId, planId, true);
    }

    /** 统一装载计划及步骤；仅锁定主计划行，步骤按 ordinal 恢复原执行顺序。 */
    private Optional<ExecutionPlan> load(UUID projectId, UUID planId, boolean lock) {
        var query = dsl.selectFrom(EXECUTION_PLAN)
                .where(EXECUTION_PLAN.PROJECT_ID.eq(projectId))
                .and(EXECUTION_PLAN.ID.eq(planId));
        return lock ? query.forUpdate().fetchOptional(this::map) : query.fetchOptional(this::map);
    }

    /** 将计划行还原为不可变提案，并附上按 ordinal 恢复的步骤。 */
    private ExecutionPlan map(ExecutionPlanRecord row) {
        return new ExecutionPlan(row.getId(), row.getProjectId(), row.getRunId(),
                row.getRevision(),
                ExecutionPlan.Stage.valueOf(row.getStage()),
                ExecutionPlan.Status.valueOf(row.getStatus()),
                row.getObjective(), mapper.readTree(row.getPlanJson().data()),
                mapper.readTree(row.getInputSnapshotJson().data()),
                row.getInputSnapshotHash(), row.getPlanHash(),
                row.getProviderConfigVersion(), row.getWorkflowVersion(),
                mapper.readTree(row.getEstimateJson().data()),
                row.getCreatedAt().toInstant(), row.getUpdatedAt().toInstant(),
                loadSteps(row.getId()));
    }

    /** 将步骤 JSON 依赖恢复为键列表，并严格按已保存的 ordinal 返回。 */
    private List<ExecutionPlan.Step> loadSteps(UUID planId) {
        return dsl.selectFrom(PLAN_STEP)
                .where(PLAN_STEP.PLAN_ID.eq(planId))
                .orderBy(PLAN_STEP.ORDINAL)
                .fetch(this::mapStep);
    }

    /** 还原单个步骤及其可空的 Provider 绑定。 */
    private ExecutionPlan.Step mapStep(PlanStepRecord row) {
        JsonNode dependencies = mapper.readTree(row.getDependencyKeysJson().data());
        List<String> keys = new ArrayList<>();
        dependencies.forEach(node -> keys.add(node.asText()));
        UUID capabilityId = row.getCapabilityId();
        return new ExecutionPlan.Step(row.getStepKey(), row.getOrdinal(),
                Task.Kind.valueOf(row.getKind()),
                row.getShotArtifactId(), row.getShotVersionId(),
                row.getImageArtifactId(), row.getImageVersionId(),
                row.getOutputSlotKey(),
                mapper.readTree(row.getInputJson().data()), keys,
                capabilityId == null ? null
                        : new MediaCapabilityBinding(
                                row.getConnectionId(),
                                row.getConnectionVersion(),
                                capabilityId,
                                row.getCapabilityVersion(),
                                row.getAdapterId(),
                                row.getMappingSha256()));
    }

    /** 仅当当前状态等于 expected 时迁移状态，防止并发审批覆盖彼此结果。 */
    @Override
    public boolean updateStatus(UUID projectId, UUID planId, ExecutionPlan.Status expected,
            ExecutionPlan.Status target, Instant now) {
        return dsl.update(EXECUTION_PLAN)
                .set(EXECUTION_PLAN.STATUS, target.name())
                .set(EXECUTION_PLAN.UPDATED_AT, utc(now))
                .where(EXECUTION_PLAN.PROJECT_ID.eq(projectId))
                .and(EXECUTION_PLAN.ID.eq(planId))
                .and(EXECUTION_PLAN.STATUS.eq(expected.name()))
                .execute() == 1;
    }

    /** 插入审批时冻结的计划摘要、输入摘要和额度预留，重复审批由唯一约束阻止。 */
    @Override
    public void insertApproval(UUID approvalId, UUID projectId, UUID runId, UUID planId,
            UUID approvedBy, String planHash, String inputHash, JsonNode reservation, Instant now) {
        int inserted = dsl.insertInto(PLAN_APPROVAL)
                .set(PLAN_APPROVAL.ID, approvalId)
                .set(PLAN_APPROVAL.PLAN_ID, planId)
                .set(PLAN_APPROVAL.PROJECT_ID, projectId)
                .set(PLAN_APPROVAL.RUN_ID, runId)
                .set(PLAN_APPROVAL.APPROVED_BY_USER_ID, approvedBy)
                .set(PLAN_APPROVAL.APPROVED_PLAN_HASH, planHash)
                .set(PLAN_APPROVAL.APPROVED_INPUT_HASH, inputHash)
                .set(PLAN_APPROVAL.RESERVATION_JSON, JSONB.valueOf(reservation.toString()))
                .set(PLAN_APPROVAL.CREATED_AT, utc(now))
                .execute();
        if (inserted != 1) {
            throw new IllegalStateException("Plan approval insert did not affect one row");
        }
    }

    /** 查询计划既有审批凭据，供审批请求幂等重放。 */
    @Override
    public Optional<UUID> findApprovalId(UUID projectId, UUID planId) {
        return dsl.select(PLAN_APPROVAL.ID)
                .from(PLAN_APPROVAL)
                .where(PLAN_APPROVAL.PROJECT_ID.eq(projectId))
                .and(PLAN_APPROVAL.PLAN_ID.eq(planId))
                .fetchOptional(PLAN_APPROVAL.ID);
    }

    /** 将绝对时刻转换为 timestamptz 列所需的 UTC 偏移时间。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
