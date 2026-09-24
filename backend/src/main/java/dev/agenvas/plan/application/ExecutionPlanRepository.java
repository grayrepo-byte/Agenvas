package dev.agenvas.plan.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 计划、步骤和审批的持久化边界；审批调用方需先锁定 Run 行。 */
public interface ExecutionPlanRepository {

    /** 返回指定 Run 和计划阶段的下一个修订号。 */
    int nextRevision(UUID projectId, UUID runId, ExecutionPlan.Stage stage);

    /** 在调用方事务中插入完整校验后的计划及全部步骤。 */
    void create(ExecutionPlan plan);

    /** 在项目范围内读取计划及其不可变有序步骤。 */
    Optional<ExecutionPlan> find(UUID projectId, UUID planId);

    /** 按修订号倒序列出当前用户 Run 下的计划身份。 */
    List<UUID> findIdsByRun(UUID projectId, UUID runId);

    /** 审批或拒绝前锁定计划行，串行化用户决定。 */
    Optional<ExecutionPlan> findForUpdate(UUID projectId, UUID planId);

    /** 通过条件更新变更生命周期状态，不改写提案正文。 */
    boolean updateStatus(UUID projectId, UUID planId, ExecutionPlan.Status expected,
            ExecutionPlan.Status target, Instant now);

    /** 仅一次写入认证用户的审批记录和精确预留数量。 */
    void insertApproval(UUID approvalId, UUID projectId, UUID runId, UUID planId,
            UUID approvedBy, String planHash, String inputHash, JsonNode reservation, Instant now);

    /** 安全重试时返回已存在的审批 ID。 */
    Optional<UUID> findApprovalId(UUID projectId, UUID planId);
}
