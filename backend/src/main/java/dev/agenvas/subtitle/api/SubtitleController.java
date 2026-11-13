package dev.agenvas.subtitle.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.subtitle.application.SubtitleService;
import dev.agenvas.subtitle.application.SubtitleRecognitionService;
import dev.agenvas.task.api.TaskController.TaskResponse;
import dev.agenvas.subtitle.domain.SubtitleCue;
import dev.agenvas.subtitle.domain.SubtitleDraft;
import dev.agenvas.subtitle.domain.SubtitleFiles;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/subtitles")
public class SubtitleController {
    private final SubtitleService subtitles;
    private final SubtitleRecognitionService recognition;
    public SubtitleController(SubtitleService subtitles, SubtitleRecognitionService recognition) {
        this.subtitles = subtitles;
        this.recognition = recognition;
    }
    @GetMapping("/capabilities")
    public JsonNode capabilities(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId) {
        return recognition.capabilities(principal.userId(), projectId);
    }
    @PostMapping("/{id}/recognition")
    public TaskResponse recognize(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return TaskResponse.from(recognition.start(principal.userId(), projectId, id, request, key));
    }
    @PostMapping("/{id}/recognition/cancel")
    public TaskResponse cancel(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID id, @RequestBody JsonNode request) {
        SubtitleService.fields(request, java.util.Set.of("taskId"));
        return TaskResponse
                .from(recognition.cancel(principal.userId(), projectId, id, SubtitleService.uuid(request, "taskId")));
    }
    @PostMapping
    public SubtitleResponse create(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @RequestBody JsonNode request) {
        return SubtitleResponse.from(subtitles.create(principal.userId(), projectId, request));
    }
    @GetMapping
    public List<SubtitleResponse> list(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @RequestParam List<UUID> sourceVersionId) {
        return subtitles.list(principal.userId(), projectId, sourceVersionId).stream().map(SubtitleResponse::from)
                .toList();
    }
    @GetMapping("/{id}")
    public SubtitleResponse get(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return SubtitleResponse.from(subtitles.get(principal.userId(), projectId, id));
    }
    @PutMapping("/{id}")
    public SubtitleResponse save(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID id, @RequestBody JsonNode request) {
        return SubtitleResponse.from(subtitles.save(principal.userId(), projectId, id, request));
    }
    @PostMapping("/{id}/import")
    public SubtitleResponse importFile(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID id, @RequestBody JsonNode request) {
        return SubtitleResponse.from(subtitles.importFile(principal.userId(), projectId, id, request));
    }
    @GetMapping("/{id}/export")
    public ResponseEntity<String> export(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID id, @RequestParam SubtitleFiles.Format format) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"subtitles." + format.name().toLowerCase(java.util.Locale.ROOT) + "\"")
                .header(HttpHeaders.CONTENT_TYPE,
                        format == SubtitleFiles.Format.VTT
                                ? "text/vtt;charset=UTF-8"
                                : "application/x-subrip;charset=UTF-8")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(subtitles.exportFile(principal.userId(), projectId, id, format));
    }
    public record SubtitleResponse(UUID id, UUID projectId, UUID sourceVersionId, int sourceDurationMs, String language,
            List<SubtitleCue> cues, long version, UUID taskId, Instant updatedAt) {
        public static SubtitleResponse from(SubtitleDraft draft) {
            return new SubtitleResponse(draft.id(), draft.projectId(), draft.track().sourceVersionId(),
                    draft.track().sourceDurationMs(),
                    draft.track().language(), draft.track().cues(), draft.version(), draft.taskId(), draft.updatedAt());
        }
    }
}
