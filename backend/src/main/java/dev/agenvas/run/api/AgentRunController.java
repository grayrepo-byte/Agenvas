package dev.agenvas.run.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** REST boundary for idempotent Run creation, status reads, and cancellation. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class AgentRunController {

    private final AgentRunService runs;

    public AgentRunController(AgentRunService runs) {
        this.runs = runs;
    }

    /** Allows the UI to show model availability, bounded policy and exact inputs before consent. */
    @GetMapping("/preflight")
    public AgentRunService.RunPreflight preflight(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestParam UUID agentId) {
        return runs.preflight(principal.userId(), projectId, agentId);
    }

    /** Lists owner-scoped history for exactly one Agent with a bounded opaque cursor. */
    @GetMapping
    public RunListResponse list(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestParam UUID agentId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        AgentRunService.RunPage page = runs.list(principal.userId(), projectId,
                agentId, cursor, limit);
        return new RunListResponse(page.items().stream().map(RunSummary::from).toList(),
                page.nextCursor());
    }

    /** Atomically reserves the project activity slot and returns a durable queued Run. */
    @PostMapping
    public ResponseEntity<RunResponse> create(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRunRequest request) {
        AgentRunService.CreateResult result = runs.create(
                principal.userId(),
                projectId,
                request.agentId(),
                request.instruction(),
                idempotencyKey,
                request.expectedAgentVersion(),
                request.redoShotArtifactId(),
                request.selectedItemIds(),
                request.expectedModelConfigSource(),
                request.expectedModelConfigVersion());
        return ResponseEntity.accepted()
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(RunResponse.from(result.run()));
    }

    /** Returns persistent status and immutable creation-time snapshots. */
    @GetMapping("/{runId}")
    public RunResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID runId) {
        return RunResponse.from(runs.get(principal.userId(), projectId, runId));
    }

    /** Idempotently stops future orchestration and releases the project activity slot. */
    @PostMapping("/{runId}/cancel")
    public RunResponse cancel(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID runId) {
        return RunResponse.from(runs.cancel(principal.userId(), projectId, runId));
    }

    /** Client-controlled fields for a new Run. Policy and context are server snapshots. */
    public record CreateRunRequest(
            @NotNull UUID agentId,
            @NotBlank @Size(max = 20_000) String instruction,
            @jakarta.validation.constraints.PositiveOrZero Long expectedAgentVersion,
            UUID redoShotArtifactId,
            @Size(max = 20) List<@NotNull UUID> selectedItemIds,
            @Size(max = 80) String expectedModelConfigSource,
            @jakarta.validation.constraints.Positive Integer expectedModelConfigVersion) {}

    /** Safe list representation omitting context, policy and model-private messages. */
    public record RunSummary(UUID id, UUID agentInstanceId, AgentRun.Status status,
            String instruction, Instant createdAt, Instant updatedAt, Instant completedAt) {
        public static RunSummary from(AgentRun run) {
            return new RunSummary(run.id(), run.agentInstanceId(), run.status(),
                    run.instruction(), run.createdAt(), run.updatedAt(), run.completedAt());
        }
    }

    /** One page of Run summaries, newest first. */
    public record RunListResponse(List<RunSummary> items, String nextCursor) {}

    /** Public Run representation without model-private reasoning or credentials. */
    public record RunResponse(
            UUID id,
            UUID projectId,
            UUID agentInstanceId,
            AgentRun.Status status,
            String instruction,
            JsonNode contextSnapshot,
            JsonNode policySnapshot,
            int profileVersion,
            int nextStepIndex,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {

        public static RunResponse from(AgentRun run) {
            return new RunResponse(
                    run.id(),
                    run.projectId(),
                    run.agentInstanceId(),
                    run.status(),
                    run.instruction(),
                    run.contextSnapshot(),
                    run.policySnapshot(),
                    run.profileVersion(),
                    run.nextStepIndex(),
                    run.version(),
                    run.createdAt(),
                    run.updatedAt(),
                    run.completedAt());
        }
    }
}
