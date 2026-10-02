package dev.agenvas.llm.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.llm.application.AgentMediaApprovalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The authenticated owner approves or rejects an entire fixed media batch in one command. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{runId}/media-approvals")
public class AgentMediaApprovalController {
    private final AgentMediaApprovalService approvals;

    public AgentMediaApprovalController(AgentMediaApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<AgentMediaApprovalService.ApprovalView> list(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID runId) {
        return approvals.list(principal.userId(), projectId, runId);
    }

    @GetMapping("/{approvalId}")
    public AgentMediaApprovalService.ApprovalView get(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID approvalId) {
        return approvals.get(principal.userId(), projectId, runId, approvalId);
    }

    @PostMapping("/{approvalId}/decision")
    public AgentMediaApprovalService.ApprovalView decide(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DecisionRequest request) {
        return approvals.decide(principal.userId(), projectId, runId, approvalId,
                request.expectedVersion(), request.decision(), idempotencyKey);
    }

    public record DecisionRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotNull AgentMediaApprovalService.Decision decision) {}
}
