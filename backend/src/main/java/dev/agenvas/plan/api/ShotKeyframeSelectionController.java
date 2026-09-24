package dev.agenvas.plan.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.plan.application.ShotKeyframeSelection;
import dev.agenvas.plan.application.ShotKeyframeSelectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User-only REST boundary for the explicit stage-B keyframe choice. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{runId}/shots/{shotId}/keyframe-selection")
public class ShotKeyframeSelectionController {

    private final ShotKeyframeSelectionService selections;

    public ShotKeyframeSelectionController(ShotKeyframeSelectionService selections) {
        this.selections = selections;
    }

    /** Returns the exact persisted choice, or 404 when the shot has no choice. */
    @GetMapping
    public ShotKeyframeSelection get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID runId,
            @PathVariable UUID shotId) {
        return selections.get(principal.userId(), projectId, runId, shotId);
    }

    /** Selects one completed image from this Run with optimistic concurrency. */
    @PutMapping
    public ShotKeyframeSelection select(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID runId,
            @PathVariable UUID shotId,
            @Valid @RequestBody SelectRequest request) {
        return selections.select(principal.userId(), projectId, runId, shotId,
                request.shotVersionId(), request.imageArtifactId(),
                request.imageVersionId(), request.expectedVersion());
    }

    /** A nullable expectedVersion means the first selection; later choices require CAS. */
    public record SelectRequest(@NotNull UUID shotVersionId,
            @NotNull UUID imageArtifactId, @NotNull UUID imageVersionId,
            Long expectedVersion) {}
}
