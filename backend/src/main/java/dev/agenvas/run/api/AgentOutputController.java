package dev.agenvas.run.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.run.application.AgentOutputService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes current selected output provenance independently from layout groups. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agents/{agentId}/outputs")
public class AgentOutputController {
    private final AgentOutputService outputs;

    public AgentOutputController(AgentOutputService outputs) { this.outputs = outputs; }

    @GetMapping
    public AgentOutputList list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID agentId) {
        return new AgentOutputList(outputs.listCanvasItemIds(principal.userId(), projectId, agentId));
    }

    public record AgentOutputList(List<UUID> canvasItemIds) {}
}
