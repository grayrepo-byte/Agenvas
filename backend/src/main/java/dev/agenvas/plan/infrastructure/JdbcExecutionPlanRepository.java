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

/** PostgreSQL implementation of immutable plan bodies and authenticated approvals. */
@Repository
public class JdbcExecutionPlanRepository implements ExecutionPlanRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcExecutionPlanRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public int nextRevision(UUID projectId, UUID runId, ExecutionPlan.Stage stage) {
        return jdbc.sql("""
                        select coalesce(max(revision), 0) + 1 from execution_plan
                        where project_id = :projectId and run_id = :runId and stage = :stage
                        """)
                .param("projectId", projectId).param("runId", runId)
                .param("stage", stage.name()).query(Integer.class).single();
    }

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

    @Override
    public Optional<ExecutionPlan> find(UUID projectId, UUID planId) {
        return load(projectId, planId, false);
    }

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

    @Override
    public Optional<ExecutionPlan> findForUpdate(UUID projectId, UUID planId) {
        return load(projectId, planId, true);
    }

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

    @Override
    public Optional<UUID> findApprovalId(UUID projectId, UUID planId) {
        return jdbc.sql("select id from plan_approval where project_id = :projectId and plan_id = :planId")
                .param("projectId", projectId).param("planId", planId)
                .query(UUID.class).optional();
    }

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
