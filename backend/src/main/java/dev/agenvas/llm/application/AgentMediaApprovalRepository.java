package dev.agenvas.llm.application;

import dev.agenvas.llm.domain.AgentMediaApproval;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for the approval ledger; callers establish trusted project ownership. */
public interface AgentMediaApprovalRepository {
    Optional<AgentMediaApproval> find(UUID projectId, UUID runId, UUID approvalId);
    Optional<AgentMediaApproval> findForUpdate(UUID projectId, UUID runId, UUID approvalId);
    Optional<AgentMediaApproval> findByToolCall(UUID projectId, UUID runId,
            int stepIndex, String toolCallId);
    List<AgentMediaApproval> listByRun(UUID projectId, UUID runId);
    Optional<AgentMediaApproval> findByTaskId(UUID projectId, UUID taskId);
    List<AgentMediaApproval> listOutstanding(int limit);
    List<AgentMediaApproval> listOutstandingAfter(UUID afterId, int limit);
    void markNotified(UUID projectId, UUID runId, int stepIndex);
    boolean insert(AgentMediaApproval approval);
    boolean update(AgentMediaApproval approval, long expectedVersion);
}
