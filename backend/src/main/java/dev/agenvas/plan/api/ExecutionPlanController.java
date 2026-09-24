package dev.agenvas.plan.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated human-only boundary for inspecting and deciding model proposals. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ExecutionPlanController {

    private final ExecutionPlanService plans;

    public ExecutionPlanController(ExecutionPlanService plans) {
        this.plans = plans;
    }

    /** Lists plan revisions so a reconnecting client can find a pending proposal. */
    @GetMapping("/runs/{runId}/plans")
    public List<ExecutionPlan> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID runId) {
        return plans.listByRun(principal.userId(), projectId, runId);
    }

    /** Reads the frozen proposal, including its hash and server-owned cost estimate. */
    @GetMapping("/plans/{planId}")
    public ExecutionPlan get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID planId) {
        return plans.get(principal.userId(), projectId, planId);
    }

    /** Exact-hash approval; no Agent tool can call this authenticated user endpoint. */
    @PostMapping("/plans/{planId}/approve")
    public ExecutionPlanService.ApprovalResult approve(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID planId,
            @Valid @RequestBody ApproveRequest request) {
        return plans.approve(principal.userId(), projectId, planId, request.planHash());
    }

    /** Rejects a pending proposal without creating any media Task. */
    @PostMapping("/plans/{planId}/reject")
    public ExecutionPlan reject(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID planId) {
        return plans.reject(principal.userId(), projectId, planId);
    }

    /** The browser must confirm the hash it actually displayed. */
    public record ApproveRequest(@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String planHash) {}
}
