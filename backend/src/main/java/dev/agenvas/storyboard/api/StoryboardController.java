package dev.agenvas.storyboard.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.llm.application.AgentMediaApprovalService;
import dev.agenvas.storyboard.application.StoryboardService;
import dev.agenvas.storyboard.domain.Storyboard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated editing; explicit media authorization remains in the existing media APIs. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/storyboard")
public class StoryboardController {
    private final StoryboardService boards;
    public StoryboardController(StoryboardService boards) { this.boards = boards; }

    @GetMapping
    public BoardResponse get(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId) {
        return BoardResponse.from(boards.get(principal.userId(), projectId));
    }
    @PutMapping
    public BoardResponse save(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @Valid @RequestBody SaveRequest request) {
        return BoardResponse.from(boards.save(principal.userId(), projectId, new StoryboardService.Save(request.expectedVersion(), request.shots(), request.composition())));
    }
    @GetMapping("/revisions")
    public RevisionsResponse revisions(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") long afterVersion, @RequestParam(defaultValue = "20") int limit) {
        var revisions = boards.revisions(principal.userId(), projectId, afterVersion, limit);
        return new RevisionsResponse(revisions.stream().map(BoardResponse::from).toList(),
                revisions.size() == limit ? revisions.getLast().version() : null);
    }
    @GetMapping("/media-approvals")
    public ApprovalsResponse approvals(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId) {
        return new ApprovalsResponse(boards.mediaApprovals(principal.userId(), projectId));
    }
    public record SaveRequest(@NotNull @PositiveOrZero Long expectedVersion, @NotNull List<StoryboardService.ShotInput> shots, StoryboardService.CompositionInput composition) {}
    public record BoardResponse(UUID projectId, long version, List<ShotResponse> shots, CompositionResponse composition) {
        static BoardResponse from(Storyboard board) {
            return new BoardResponse(board.projectId(), board.version(), board.shots().stream().map(ShotResponse::from).toList(),
                    board.composition() == null ? null : new CompositionResponse(board.composition().canvasItemId(),
                            board.composition().selectedVersionId(), board.composition().needsReview()));
        }
    }
    public record ShotResponse(UUID id, String code, String scene, String title, String description, String dialogue,
            BigDecimal durationSeconds, UUID sourceVersionId, List<UUID> referenceVersionIds, int revision,
            List<ProductionResponse> productions) {
        static ShotResponse from(Storyboard.Shot shot) {
            return new ShotResponse(shot.id(), shot.code(), shot.scene(), shot.title(), shot.description(), shot.dialogue(),
                    shot.durationSeconds(), shot.sourceVersionId(), shot.referenceVersionIds(), shot.revision(),
                    shot.productions().stream().map(p -> new ProductionResponse(p.role(), p.canvasItemId(),
                            p.selectedVersionId(), p.reviewedRevision(), p.needsReview())).toList());
        }
    }
    public record ProductionResponse(Storyboard.Role role, UUID canvasItemId, UUID selectedVersionId,
            int reviewedRevision, boolean needsReview) {}
    public record CompositionResponse(UUID canvasItemId, UUID selectedVersionId, boolean needsReview) {}
    public record RevisionsResponse(List<BoardResponse> items, Long nextAfterVersion) {}
    public record ApprovalsResponse(List<AgentMediaApprovalService.ApprovalView> items) {}
}
