package dev.agenvas.llm.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.llm.application.RunAction;
import dev.agenvas.llm.application.RunActionService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅向所有者提供持久化动作摘要，不公开模型回合或原始工具账本。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{runId}/actions")
public class RunActionController {

    private final RunActionService actions;

    public RunActionController(RunActionService actions) {
        this.actions = actions;
    }

    /** 按步骤及提交前登记顺序返回服务端生成的安全摘要。 */
    @GetMapping
    public List<RunAction> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID runId) {
        return actions.list(principal.userId(), projectId, runId);
    }
}
