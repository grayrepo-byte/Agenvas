package dev.agenvas.task.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.task.application.VideoOperationService;
import dev.agenvas.task.domain.VideoOperation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/artifacts/{artifactId}/video-operations")
public class VideoOperationController {
    private final VideoOperationService operations;
    public VideoOperationController(VideoOperationService operations) { this.operations = operations; }

    @PostMapping
    public TaskController.TaskResponse run(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID artifactId,
            @RequestHeader("Idempotency-Key") String commandKey, @Valid @RequestBody Request request) {
        return TaskController.TaskResponse.from(operations.run(principal.userId(), projectId, artifactId,
                request.canvasItemId(), request.sourceVersionId(), request.expectedCanvasItemVersion(),
                request.operation(), request.expectedFunctionVersion(), request.expectedCapabilityVersion(), request.prompt(), request.parameters(), commandKey));
    }

    public record Request(@NotNull UUID canvasItemId, @NotNull UUID sourceVersionId,
            @PositiveOrZero long expectedCanvasItemVersion, @NotNull VideoOperation operation,
            @PositiveOrZero long expectedFunctionVersion, @Positive int expectedCapabilityVersion, @Size(max = 4000) String prompt, @NotNull JsonNode parameters) {}
}
