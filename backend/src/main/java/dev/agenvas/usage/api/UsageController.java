package dev.agenvas.usage.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.usage.application.UsageService;
import dev.agenvas.usage.domain.UsageEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Owner-scoped accounting read boundary; monetary values are decimal strings or null. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/usage")
public class UsageController {

    private final UsageService usage;

    public UsageController(UsageService usage) {
        this.usage = usage;
    }

    /** Returns reservation and settlement entries without ever replacing unknown with zero. */
    @GetMapping
    public List<UsageResponse> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return usage.listProject(principal.userId(), projectId).stream()
                .map(UsageResponse::from).toList();
    }

    /** Stable public record without provider credentials or internal configuration. */
    public record UsageResponse(UUID id, UUID runId, UUID taskId, String operationKey,
            UsageEntry.EntryType entryType, JsonNode quantity,
            String estimatedCost, String actualCost, String currency,
            UsageEntry.CostStatus costStatus, String costSource,
            Integer providerConfigVersion, String workflowVersion, String modelId,
            Instant createdAt) {

        static UsageResponse from(UsageEntry entry) {
            return new UsageResponse(entry.id(), entry.runId(), entry.taskId(),
                    entry.operationKey(), entry.entryType(), entry.quantity(),
                    entry.estimatedCost() == null ? null
                            : entry.estimatedCost().toPlainString(),
                    entry.actualCost() == null ? null : entry.actualCost().toPlainString(),
                    entry.currency(), entry.costStatus(), entry.costSource(),
                    entry.providerConfigVersion(), entry.workflowVersion(),
                    entry.modelId(), entry.createdAt());
        }
    }
}
