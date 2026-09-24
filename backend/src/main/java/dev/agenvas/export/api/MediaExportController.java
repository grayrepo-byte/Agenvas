package dev.agenvas.export.api;

import dev.agenvas.export.application.MediaExportService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.api.TaskController.TaskResponse;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated project-level sequential export, status and cancellation boundary. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/exports")
public class MediaExportController {

    private final MediaExportService exports;
    private final TaskService tasks;

    public MediaExportController(MediaExportService exports, TaskService tasks) {
        this.exports = exports;
        this.tasks = tasks;
    }

    /** Saves an immutable input snapshot before any FFmpeg process is started. */
    @PostMapping
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateExportRequest request) {
        Task task = exports.create(principal.userId(), projectId, idempotencyKey,
                request.segments().stream().map(item -> new MediaExportService.SegmentRequest(
                        item.videoArtifactId(), item.videoVersionId(),
                        item.startMs(), item.endMs())).toList());
        return ResponseEntity.accepted().body(TaskResponse.from(task));
    }

    /** Returns up to one hundred newest durable exports for history and recovery. */
    @GetMapping
    public List<TaskResponse> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return exports.list(principal.userId(), projectId).stream()
                .map(TaskResponse::from).toList();
    }

    /** Reads one exact export without relying on the current Agent Run slot. */
    @GetMapping("/{taskId}")
    public TaskResponse get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID taskId) {
        Task task = tasks.get(principal.userId(), projectId, taskId);
        if (task.kind() != Task.Kind.MEDIA_EXPORT) {
            throw new ApiProblemException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                    "导出不存在", "该项目没有此导出任务。", false);
        }
        return TaskResponse.from(task);
    }

    /** Requests stop without promising any external generation cancellation or refund. */
    @PostMapping("/{taskId}/cancel")
    public TaskResponse cancel(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID taskId) {
        return TaskResponse.from(exports.cancel(principal.userId(), projectId, taskId));
    }

    /** One to six exact video-version ranges in the caller's intended order. */
    public record CreateExportRequest(
            @NotEmpty @Size(max = 6) List<@Valid Segment> segments) {}

    /** Millisecond ranges are closed-open and validated against archived duration by worker. */
    public record Segment(@NotNull UUID videoArtifactId, @NotNull UUID videoVersionId,
            @Min(0) @Max(60_000) int startMs,
            @Min(1) @Max(60_000) int endMs) {}
}
