package dev.agenvas.canvas.api;

import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.MediaUploadPurpose;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Card-local content choices that are not layout commands or Artifact defaults. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/canvas-items/{canvasItemId}")
public class CanvasItemController {
    private final CanvasService canvas;

    public CanvasItemController(CanvasService canvas) {
        this.canvas = canvas;
    }

    @PostMapping("/upload-version")
    @ResponseStatus(HttpStatus.CREATED)
    public CanvasController.CanvasItemResponse uploadVersion(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID canvasItemId,
            @Valid @RequestBody UploadVersionRequest request) {
        return CanvasController.CanvasItemResponse.from(canvas.uploadVersion(principal.userId(),
                projectId, canvasItemId, request.targetItemId(), request.expectedVersion(),
                request.content(), request.purpose(), request.sourceVersionId()));
    }

    public record UploadVersionRequest(@NotNull UUID targetItemId,
            @PositiveOrZero long expectedVersion,
            @NotNull JsonNode content, MediaUploadPurpose purpose, UUID sourceVersionId) {}
}
