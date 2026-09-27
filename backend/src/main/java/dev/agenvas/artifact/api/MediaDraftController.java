package dev.agenvas.artifact.api;

import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.MediaDraftRestoreService;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Authenticated read and optimistic save of a media card's working draft. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/canvas-items/{canvasItemId}/media-draft")
public class MediaDraftController {
    private final MediaDraftService drafts;
    private final MediaDraftRestoreService restore;

    public MediaDraftController(MediaDraftService drafts, MediaDraftRestoreService restore) {
        this.drafts = drafts;
        this.restore = restore;
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
                request.expectedVersion(), request.prompt(), request.parameters(),
                request.durationSeconds(), request.capabilityId(), request.videoInputMode(),
                request.imageInputs(), request.mentions());
    }

    /** Deliberately restores all editable generation input without recreating old canvas lines. */
    @PostMapping("/restore-version-inputs")
    public MediaDraft restoreVersionInputs(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID canvasItemId,
            @Valid @RequestBody RestoreVersionInputsRequest request) {
        return restore.restore(principal.userId(), projectId, canvasItemId,
                request.versionId(), request.expectedVersion());
    }

    public record SaveDraftRequest(@PositiveOrZero long expectedVersion,
            @NotNull @Size(max = 20000) String prompt, JsonNode parameters,
            Integer durationSeconds, UUID capabilityId,
            MediaDraft.VideoInputMode videoInputMode,
            List<MediaDraftService.SaveImageInput> imageInputs,
            List<MediaDraft.PromptMention> mentions) {}

    public record RestoreVersionInputsRequest(@NotNull UUID versionId,
            @PositiveOrZero long expectedVersion) {}
}
