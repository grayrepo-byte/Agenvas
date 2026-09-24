package dev.agenvas.export.api;

import dev.agenvas.export.application.ExportProposal;
import dev.agenvas.export.application.ExportProposalService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.task.api.TaskController.TaskResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Human-only review and decision API for Agent-proposed sequential exports. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/export-proposals")
public class ExportProposalController {

    private final ExportProposalService proposals;

    public ExportProposalController(ExportProposalService proposals) {
        this.proposals = proposals;
    }

    /** Lists recent proposals after checking project ownership. */
    @GetMapping
    public List<ProposalResponse> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return proposals.list(principal.userId(), projectId).stream()
                .map(ProposalResponse::from).toList();
    }

    /** Retrieves the exact immutable proposal the user will decide. */
    @GetMapping("/{proposalId}")
    public ProposalResponse get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID proposalId) {
        return ProposalResponse.from(proposals.get(principal.userId(), projectId, proposalId));
    }

    /** Approves only the displayed hash and returns the one resulting persistent Task. */
    @PostMapping("/{proposalId}/approve")
    public ApprovalResponse approve(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID proposalId,
            @Valid @RequestBody ApproveRequest request) {
        ExportProposalService.Approval approved = proposals.approve(principal.userId(),
                projectId, proposalId, request.proposalHash());
        return new ApprovalResponse(ProposalResponse.from(approved.proposal()),
                TaskResponse.from(approved.task()), approved.replayed());
    }

    /** Rejects a pending proposal without creating any Task. */
    @PostMapping("/{proposalId}/reject")
    public ProposalResponse reject(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID proposalId) {
        return ProposalResponse.from(proposals.reject(principal.userId(),
                projectId, proposalId));
    }

    /** A hash binds the approval to the proposal that was actually rendered. */
    public record ApproveRequest(@NotBlank @Pattern(regexp = "[0-9a-f]{64}")
            String proposalHash) {}

    /** Public proposal body contains only ordered, bounded media identities and ranges. */
    public record ProposalResponse(UUID id, UUID runId, ExportProposal.Status status,
            JsonNode input, String proposalHash, long projectVersion, UUID approvedTaskId,
            Instant createdAt, Instant decidedAt) {
        /** Hides internal CAS pins and the approving user identifier. */
        public static ProposalResponse from(ExportProposal proposal) {
            return new ProposalResponse(proposal.id(), proposal.runId(), proposal.status(),
                    proposal.input(), proposal.proposalHash(), proposal.projectVersion(),
                    proposal.approvedTaskId(), proposal.createdAt(), proposal.decidedAt());
        }
    }

    /** Same approval key replay returns the same Task and a replay indicator. */
    public record ApprovalResponse(ProposalResponse proposal, TaskResponse task,
            boolean replayed) {}
}
