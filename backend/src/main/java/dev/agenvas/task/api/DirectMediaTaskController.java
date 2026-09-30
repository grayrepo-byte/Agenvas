package dev.agenvas.task.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.domain.ImageOperation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** A deliberate click runs the saved card draft without Agent approval. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class DirectMediaTaskController {
    private final DirectMediaTaskService direct;

    public DirectMediaTaskController(DirectMediaTaskService direct) {
        this.direct = direct;
    }

    @PostMapping("/artifacts/{artifactId}/run")
    public TaskController.TaskResponse run(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID artifactId,
            @RequestHeader("Idempotency-Key") String commandKey,
            @Valid @RequestBody RunRequest request) {
        return TaskController.TaskResponse.from(direct.run(principal.userId(), projectId,
                artifactId, request.canvasItemId(), request.expectedDraftVersion(), commandKey));
    }

    public record RunRequest(@jakarta.validation.constraints.NotNull UUID canvasItemId,
            @PositiveOrZero long expectedDraftVersion) {}

    @PostMapping("/artifacts/{artifactId}/image-operations")
    public TaskController.TaskResponse runImageOperation(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID artifactId,
            @RequestHeader("Idempotency-Key") String commandKey,
            @Valid @RequestBody ImageOperationRequest request) {
        return TaskController.TaskResponse.from(direct.runImageOperation(principal.userId(),
                projectId, artifactId, request.canvasItemId(), request.sourceVersionId(),
                request.expectedCanvasItemVersion(), request.operation(), request.instruction(),
                request.capabilityId(), request.referenceVersionIds(), request.maskAssetId(),
                request.parameters(), commandKey));
    }

    public record ImageOperationRequest(
            @NotNull UUID canvasItemId,
            @NotNull UUID sourceVersionId,
            @PositiveOrZero long expectedCanvasItemVersion,
            @NotNull ImageOperation operation,
            @jakarta.validation.constraints.Size(max = 4000) String instruction,
            UUID capabilityId,
            @jakarta.validation.constraints.Size(max = 15) List<UUID> referenceVersionIds,
            UUID maskAssetId,
            @NotNull JsonNode parameters) {}

    @GetMapping("/artifacts/{artifactId}/run")
    public List<TaskController.TaskResponse> list(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID artifactId,
            @RequestParam UUID canvasItemId) {
        return direct.list(principal.userId(), projectId, artifactId, canvasItemId).stream()
                .map(TaskController.TaskResponse::from).toList();
    }

    @PostMapping("/tasks/{taskId}/cancel-queued")
    public TaskController.TaskResponse cancelQueued(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID taskId) {
        return TaskController.TaskResponse.from(direct.cancelQueued(principal.userId(),
                projectId, taskId));
    }

    @GetMapping("/tasks/{taskId}/queue")
    public TaskRepository.QueueStatus queueStatus(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID taskId) {
        return direct.queueStatus(principal.userId(), projectId, taskId);
    }
}
