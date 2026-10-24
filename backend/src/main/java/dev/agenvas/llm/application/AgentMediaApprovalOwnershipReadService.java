package dev.agenvas.llm.application;

import dev.agenvas.project.application.ProjectService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Boolean guard only; approval prompts/results remain inside the approval module. */
@Service
public class AgentMediaApprovalOwnershipReadService {
    private final AgentMediaApprovalRepository approvals;
    private final ProjectService projects;

    public AgentMediaApprovalOwnershipReadService(AgentMediaApprovalRepository approvals, ProjectService projects) {
        this.approvals = approvals; this.projects = projects;
    }

    @Transactional(readOnly = true)
    public boolean hasOwnership(UUID ownerId, UUID projectId, UUID canvasItemId) {
        projects.get(ownerId, projectId);
        return approvals.hasCanvasOwnership(ownerId, projectId, canvasItemId);
    }
}
