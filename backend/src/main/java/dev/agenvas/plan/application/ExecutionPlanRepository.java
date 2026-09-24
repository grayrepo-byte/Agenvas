package dev.agenvas.plan.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Durable plan/step/approval boundary; callers hold the Run row lock for decisions. */
public interface ExecutionPlanRepository {

    /** Returns the next plan revision for this Run and approval stage. */
    int nextRevision(UUID projectId, UUID runId, ExecutionPlan.Stage stage);

    /** Inserts a fully validated plan and all its steps in the caller transaction. */
    void create(ExecutionPlan plan);

    /** Reads a project-scoped plan and its immutable ordered steps. */
    Optional<ExecutionPlan> find(UUID projectId, UUID planId);

    /** Lists immutable plan identities for one owned Run, newest revision first. */
    List<UUID> findIdsByRun(UUID projectId, UUID runId);

    /** Locks the plan row before approval or rejection. */
    Optional<ExecutionPlan> findForUpdate(UUID projectId, UUID planId);

    /** Compare-and-set lifecycle transition without changing the proposal body. */
    boolean updateStatus(UUID projectId, UUID planId, ExecutionPlan.Status expected,
            ExecutionPlan.Status target, Instant now);

    /** Inserts the authenticated approval and exact reserved counts once. */
    void insertApproval(UUID approvalId, UUID projectId, UUID runId, UUID planId,
            UUID approvedBy, String planHash, String inputHash, JsonNode reservation, Instant now);

    /** Returns an existing approval ID for safe exact retries. */
    Optional<UUID> findApprovalId(UUID projectId, UUID planId);
}
