package dev.agenvas.artifact.api;

import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated read and optimistic save of a media card's working draft. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/canvas-items/{canvasItemId}/media-draft")
public class MediaDraftController {
    private final MediaDraftService drafts;

    public MediaDraftController(MediaDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping
    public MediaDraft get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID canvasItemId) {
        return drafts.get(principal.userId(), projectId, canvasItemId);
    }

    @PutMapping
    public MediaDraft save(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID canvasItemId,
            @Valid @RequestBody SaveDraftRequest request) {
        return drafts.save(principal.userId(), projectId, canvasItemId,
                request.expectedVersion(), request.prompt(), request.inputImageVersionId(),
                request.durationSeconds(), request.capabilityId());
    }

    public record SaveDraftRequest(@PositiveOrZero long expectedVersion,
            @NotNull @Size(max = 20000) String prompt, UUID inputImageVersionId,
            Integer durationSeconds, UUID capabilityId) {}
}
