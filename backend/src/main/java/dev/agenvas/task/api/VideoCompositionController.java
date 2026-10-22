package dev.agenvas.task.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.task.application.VideoCompositionService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/video-compositions")
public class VideoCompositionController {
    private final VideoCompositionService compositions;
    public VideoCompositionController(VideoCompositionService compositions) { this.compositions = compositions; }

    @PostMapping
    public TaskController.TaskResponse run(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return TaskController.TaskResponse.from(compositions.run(principal.userId(), projectId, request, key));
    }
}
