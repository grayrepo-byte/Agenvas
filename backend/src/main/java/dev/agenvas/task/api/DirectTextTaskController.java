package dev.agenvas.task.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.task.application.DirectTextTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Direct text-card generation uses the configured model without creating an Agent Run. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/artifacts/{artifactId}/text-generations")
public class DirectTextTaskController {
    private final DirectTextTaskService direct;

    public DirectTextTaskController(DirectTextTaskService direct) {
        this.direct = direct;
    }

    @PostMapping
    public TaskController.TaskResponse run(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID artifactId,
            @RequestHeader("Idempotency-Key") String commandKey,
            @Valid @RequestBody RunRequest request) {
        return TaskController.TaskResponse.from(direct.run(principal.userId(), projectId,
                artifactId, request.prompt(), request.expectedArtifactVersion(),
                request.expectedCurrentVersionId(), commandKey));
    }

    @GetMapping
    public List<TaskController.TaskResponse> list(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID artifactId) {
        return direct.list(principal.userId(), projectId, artifactId).stream()
                .map(TaskController.TaskResponse::from).toList();
    }

    public record RunRequest(
            @NotBlank @Size(max = 20_000) String prompt,
            @PositiveOrZero long expectedArtifactVersion,
            @NotNull UUID expectedCurrentVersionId) {}
}
