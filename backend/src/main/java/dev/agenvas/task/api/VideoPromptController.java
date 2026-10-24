package dev.agenvas.task.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.i18n.SupportedLocales;
import dev.agenvas.task.application.VideoPromptTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

/** Project-authorized video prompt assistance with durable query and cancellation. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/canvas-items/{canvasItemId}/video-prompt-optimizations")
public class VideoPromptController {
    private final VideoPromptTaskService prompts;
    public VideoPromptController(VideoPromptTaskService prompts) { this.prompts = prompts; }

    @GetMapping
    public ResponseEntity<List<TaskController.TaskResponse>> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID canvasItemId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(prompts.list(principal.userId(), projectId,
                canvasItemId).stream().map(TaskController.TaskResponse::from).toList());
    }

    @PostMapping
    public ResponseEntity<TaskController.TaskResponse> run(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID canvasItemId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody VideoPromptTaskService.Request request,
            HttpServletRequest http) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TaskController.TaskResponse.from(prompts.run(
                principal.userId(), projectId, canvasItemId, request, SupportedLocales.resolve(http).getLanguage(), key)));
    }

    @PostMapping("/{taskId}/cancel")
    public TaskController.TaskResponse cancel(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID canvasItemId, @PathVariable UUID taskId) {
        return TaskController.TaskResponse.from(prompts.cancel(principal.userId(), projectId, canvasItemId, taskId));
    }

}
