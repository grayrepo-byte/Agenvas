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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The owner may revise pending video input, then approve or reject the whole current batch. */
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

    @PutMapping("/{approvalId}")
    public AgentMediaApprovalService.ApprovalView revise(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID approvalId,
            @Valid @RequestBody RevisionRequest request) {
        return approvals.revise(principal.userId(), projectId, runId, approvalId, request.expectedVersion(),
                request.outputIndex(), request.capabilityId(), request.parameters(), request.durationSeconds(), request.videoInputMode());
    }

    public record RevisionRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @PositiveOrZero Integer outputIndex, @NotNull UUID capabilityId,
            @NotNull tools.jackson.databind.JsonNode parameters,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(60) Integer durationSeconds,
            @NotNull dev.agenvas.artifact.domain.MediaDraft.VideoInputMode videoInputMode) {}

    public record DecisionRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotNull AgentMediaApprovalService.Decision decision) {}
}
