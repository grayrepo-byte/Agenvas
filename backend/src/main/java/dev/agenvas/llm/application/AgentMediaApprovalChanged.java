package dev.agenvas.llm.application;

import java.util.UUID;

/** Transactional local wake-up; the persistent approval ledger remains the recovery source. */
public record AgentMediaApprovalChanged(UUID ownerId, UUID projectId, UUID runId,
        UUID approvalId) {}
