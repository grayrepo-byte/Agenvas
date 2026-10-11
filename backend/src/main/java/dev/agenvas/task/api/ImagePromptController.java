package dev.agenvas.task.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.i18n.SupportedLocales;
import dev.agenvas.task.application.ImagePromptTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Project-authorized image analysis; browsers never send image bytes or Provider credentials. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/artifacts/{artifactId}/image-prompts")
public class ImagePromptController {
    private final ImagePromptTaskService prompts;
    public ImagePromptController(ImagePromptTaskService prompts) { this.prompts = prompts; }

    @GetMapping
    public ResponseEntity<List<TaskController.TaskResponse>> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID artifactId, @RequestParam UUID canvasItemId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(prompts.list(principal.userId(), projectId,
                artifactId, canvasItemId).stream().map(TaskController.TaskResponse::from).toList());
    }

    @PostMapping
    public ResponseEntity<TaskController.TaskResponse> run(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID artifactId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ImagePromptRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TaskController.TaskResponse.from(prompts.run(
                principal.userId(), projectId, artifactId, request.canvasItemId(), request.sourceVersionId(),
                request.expectedCanvasItemVersion(), SupportedLocales.resolve(http).getLanguage(), key)));
    }

    @PostMapping("/{taskId}/cancel")
    public TaskController.TaskResponse cancel(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID artifactId, @PathVariable UUID taskId) {
        return TaskController.TaskResponse.from(prompts.cancel(principal.userId(), projectId, artifactId, taskId));
    }

    public record ImagePromptRequest(@NotNull UUID canvasItemId, @NotNull UUID sourceVersionId,
            @NotNull @PositiveOrZero Long expectedCanvasItemVersion) {}
}
