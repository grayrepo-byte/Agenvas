package dev.agenvas.plan.infrastructure;

import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanRepository;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 持久化不可变计划正文、步骤依赖和独立审批凭据的 PostgreSQL 仓储。 */
@Repository
public class JdbcExecutionPlanRepository implements ExecutionPlanRepository {

    /** 通过参数化 SQL 保存计划、步骤和审批凭据。 */
    private final JdbcClient jdbc;
    /** 序列化步骤依赖 JSON，并还原已持久化的计划快照。 */
    private final ObjectMapper mapper;

    /** 配置计划仓储的 SQL 执行器与 JSON 映射器。 */
    public JdbcExecutionPlanRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 查询指定 Run 与阶段下一个修订号；调用方须在 Run/项目事务锁内串行化写入。 */
    @Override
    public int nextRevision(UUID projectId, UUID runId, ExecutionPlan.Stage stage) {
        return jdbc.sql("""
                        select coalesce(max(revision), 0) + 1 from execution_plan
                        where project_id = :projectId and run_id = :runId and stage = :stage
                        """)
                .param("projectId", projectId).param("runId", runId)
                .param("stage", stage.name()).query(Integer.class).single();
    }

    /** 同一事务写入计划和有序步骤；步骤外键与唯一约束保证结构完整。 */
    @Override
    public void create(ExecutionPlan plan) {
        int inserted = jdbc.sql("""
                        insert into execution_plan (id, project_id, run_id, revision, stage,
                            status, objective, plan_json, input_snapshot_json,
                            input_snapshot_hash, plan_hash, provider_config_version,
                            workflow_version, estimate_json, created_at, updated_at)
                        values (:id, :projectId, :runId, :revision, :stage, :status,
                            :objective, cast(:planJson as jsonb), cast(:snapshot as jsonb),
                            :snapshotHash, :planHash, :providerVersion, :workflowVersion,
                            cast(:estimate as jsonb), :createdAt, :updatedAt)
                        """)
                .param("id", plan.id()).param("projectId", plan.projectId())
                .param("runId", plan.runId()).param("revision", plan.revision())
                .param("stage", plan.stage().name()).param("status", plan.status().name())
                .param("objective", plan.objective()).param("planJson", plan.plan().toString())
                .param("snapshot", plan.inputSnapshot().toString())
                .param("snapshotHash", plan.inputSnapshotHash())
                .param("planHash", plan.planHash())
                .param("providerVersion", plan.providerConfigVersion())
                .param("workflowVersion", plan.workflowVersion())
                .param("estimate", plan.estimate().toString())
                .param("createdAt", utc(plan.createdAt()))
                .param("updatedAt", utc(plan.updatedAt())).update();
        if (inserted != 1) {
            throw new IllegalStateException("Execution plan insert did not affect one row");
        }
        for (ExecutionPlan.Step step : plan.steps()) {
            jdbc.sql("""
                            insert into plan_step (plan_id, project_id, step_key, ordinal, kind,
                                shot_artifact_id, shot_version_id, image_artifact_id,
                                image_version_id, output_slot_key, input_json,
                                dependency_keys_json)
                            values (:planId, :projectId, :stepKey, :ordinal, :kind,
                                :shotArtifactId, :shotVersionId, :imageArtifactId,
                                :imageVersionId, :outputSlotKey, cast(:input as jsonb),
                                cast(:dependencies as jsonb))
                            """)
                    .param("planId", plan.id()).param("projectId", plan.projectId())
                    .param("stepKey", step.stepKey()).param("ordinal", step.ordinal())
                    .param("kind", step.kind().name())
                    .param("shotArtifactId", step.shotArtifactId())
                    .param("shotVersionId", step.shotVersionId())
                    .param("imageArtifactId", step.imageArtifactId(), java.sql.Types.OTHER)
                    .param("imageVersionId", step.imageVersionId(), java.sql.Types.OTHER)
                    .param("outputSlotKey", step.outputSlotKey())
                    .param("input", step.input().toString())
                    .param("dependencies", mapper.valueToTree(step.dependencyKeys()).toString())
                    .update();
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
        return jdbc.sql("""
                        select id from execution_plan
                        where project_id = :projectId and run_id = :runId
                        order by created_at desc, id desc
                        """)
                .param("projectId", projectId).param("runId", runId)
                .query(UUID.class).list();
    }

    /** 读取计划并锁住计划行，供审批或拒绝状态迁移使用。 */
    @Override
    public Optional<ExecutionPlan> findForUpdate(UUID projectId, UUID planId) {
        return load(projectId, planId, true);
    }

    /** 统一装载计划及步骤；仅锁定主计划行，步骤按 ordinal 恢复原执行顺序。 */
    private Optional<ExecutionPlan> load(UUID projectId, UUID planId, boolean lock) {
        String suffix = lock ? " for update" : "";
        return jdbc.sql("""
                        select id, project_id, run_id, revision, stage, status, objective,
                            plan_json::text as plan_json,
                            input_snapshot_json::text as input_snapshot_json,
                            input_snapshot_hash, plan_hash, provider_config_version,
                            workflow_version, estimate_json::text as estimate_json,
                            created_at, updated_at
                        from execution_plan where project_id = :projectId and id = :planId
                        """ + suffix)
                .param("projectId", projectId).param("planId", planId)
                .query((rs, row) -> new ExecutionPlan(
                        rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getObject("run_id", UUID.class), rs.getInt("revision"),
                        ExecutionPlan.Stage.valueOf(rs.getString("stage")),
                        ExecutionPlan.Status.valueOf(rs.getString("status")),
                        rs.getString("objective"), mapper.readTree(rs.getString("plan_json")),
                        mapper.readTree(rs.getString("input_snapshot_json")),
                        rs.getString("input_snapshot_hash"), rs.getString("plan_hash"),
                        rs.getInt("provider_config_version"), rs.getString("workflow_version"),
                        mapper.readTree(rs.getString("estimate_json")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        loadSteps(planId)))
                .optional();
    }

    /** 将步骤 JSON 依赖恢复为键列表，并严格按已保存的 ordinal 返回。 */
    private List<ExecutionPlan.Step> loadSteps(UUID planId) {
        return jdbc.sql("""
                        select step_key, ordinal, kind, shot_artifact_id, shot_version_id,
                            image_artifact_id, image_version_id, output_slot_key,
                            input_json::text as input_json,
                            dependency_keys_json::text as dependency_keys_json
                        from plan_step where plan_id = :planId order by ordinal
                        """)
                .param("planId", planId)
                .query((rs, row) -> {
                    JsonNode dependencies = mapper.readTree(rs.getString("dependency_keys_json"));
                    List<String> keys = new ArrayList<>();
                    dependencies.forEach(node -> keys.add(node.asText()));
                    return new ExecutionPlan.Step(rs.getString("step_key"), rs.getInt("ordinal"),
                            Task.Kind.valueOf(rs.getString("kind")),
                            rs.getObject("shot_artifact_id", UUID.class),
                            rs.getObject("shot_version_id", UUID.class),
                            rs.getObject("image_artifact_id", UUID.class),
                            rs.getObject("image_version_id", UUID.class),
                            rs.getString("output_slot_key"),
                            mapper.readTree(rs.getString("input_json")), keys);
                }).list();
    }

    /** 仅当当前状态等于 expected 时迁移状态，防止并发审批覆盖彼此结果。 */
    @Override
    public boolean updateStatus(UUID projectId, UUID planId, ExecutionPlan.Status expected,
            ExecutionPlan.Status target, Instant now) {
        return jdbc.sql("""
                        update execution_plan set status = :target, updated_at = :now
                        where project_id = :projectId and id = :planId and status = :expected
                        """)
                .param("projectId", projectId).param("planId", planId)
                .param("expected", expected.name()).param("target", target.name())
                .param("now", utc(now)).update() == 1;
    }

    /** 插入审批时冻结的计划摘要、输入摘要和额度预留，重复审批由唯一约束阻止。 */
    @Override
    public void insertApproval(UUID approvalId, UUID projectId, UUID runId, UUID planId,
            UUID approvedBy, String planHash, String inputHash, JsonNode reservation, Instant now) {
        int inserted = jdbc.sql("""
                        insert into plan_approval (id, plan_id, project_id, run_id,
                            approved_by_user_id, approved_plan_hash, approved_input_hash,
                            reservation_json, created_at)
                        values (:id, :planId, :projectId, :runId, :approvedBy,
                            :planHash, :inputHash, cast(:reservation as jsonb), :now)
                        """)
                .param("id", approvalId).param("planId", planId)
                .param("projectId", projectId).param("runId", runId)
                .param("approvedBy", approvedBy).param("planHash", planHash)
                .param("inputHash", inputHash).param("reservation", reservation.toString())
                .param("now", utc(now)).update();
        if (inserted != 1) {
            throw new IllegalStateException("Plan approval insert did not affect one row");
        }
    }

    /** 查询计划既有审批凭据，供审批请求幂等重放。 */
    @Override
    public Optional<UUID> findApprovalId(UUID projectId, UUID planId) {
        return jdbc.sql("select id from plan_approval where project_id = :projectId and plan_id = :planId")
                .param("projectId", projectId).param("planId", planId)
                .query(UUID.class).optional();
    }

    /** 将绝对时刻转换为 JDBC 参数所需的 UTC 偏移时间。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
