package dev.agenvas.llm.application;

import dev.agenvas.run.application.AgentRunService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在项目与 Run 授权后读取有界的公开动作历史。 */
@Service
public class RunActionService {

    /** This public-list page size is independent of the Run's tool execution count. */
    private static final int MAX_ACTIONS_PER_RUN = 40;

    private final AgentRunService runs;
    private final ToolExecutionRepository ledger;

    public RunActionService(AgentRunService runs, ToolExecutionRepository ledger) {
        this.runs = runs;
        this.ledger = ledger;
    }

    /** 先验证 Run 的所有者及项目归属；空列表仅代表该 Run 尚无已提交动作。 */
    @Transactional(readOnly = true)
    public List<RunAction> list(UUID ownerId, UUID projectId, UUID runId) {
        runs.get(ownerId, projectId, runId);
        return ledger.listCompletedActions(projectId, runId, MAX_ACTIONS_PER_RUN);
    }
}
