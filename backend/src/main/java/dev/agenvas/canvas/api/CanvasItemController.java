package dev.agenvas.canvas.api;

import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Card-local content choices that are not layout commands or Artifact defaults. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/canvas-items/{canvasItemId}")
public class CanvasItemController {
    private final CanvasService canvas;

    public CanvasItemController(CanvasService canvas) {
        this.canvas = canvas;
    }

    @PostMapping("/select-version")
    public CanvasController.CanvasItemResponse selectVersion(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID canvasItemId,
            @Valid @RequestBody SelectVersionRequest request) {
        return CanvasController.CanvasItemResponse.from(canvas.selectVersion(principal.userId(),
                projectId, canvasItemId, request.versionId(), request.expectedVersion()));
    }

    public record SelectVersionRequest(@NotNull UUID versionId,
            @PositiveOrZero long expectedVersion) {}
}
