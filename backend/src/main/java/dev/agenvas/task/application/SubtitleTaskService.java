package dev.agenvas.task.application;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Task application boundary for ASR; subtitle code never reaches another module's repository. */
@Service
public class SubtitleTaskService {
    private final TaskRepository tasks;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;
    public SubtitleTaskService(TaskRepository tasks, ProjectEventService events, ObjectMapper mapper, Clock clock) {
        this.tasks = tasks; this.events = events; this.mapper = mapper; this.clock = clock;
    }
    public Optional<Task> prior(UUID owner, UUID project, String key, JsonNode request) {
        if (key == null || key.isBlank() || key.length() > 160) throw problem(HttpStatus.BAD_REQUEST, "SUBTITLE_INVALID", "api.subtitle.invalid");
        var prior = tasks.findDirectByStepKey(owner, project, key);
        var saved = prior.map(task -> task.input().path("recognitionRequest")).orElse(null);
        // JSONB can deserialize a small long as an IntNode. Compare the normalized protocol
        // values rather than Jackson's numeric node classes or PostgreSQL's object key order.
        if (prior.isPresent() && (prior.get().kind() != Task.Kind.SUBTITLE_TRANSCRIPTION
                || !request.path("subtitleId").asText().equals(saved.path("subtitleId").asText())
                || request.path("expectedVersion").asLong(-1) != saved.path("expectedVersion").asLong(-1)
                || !request.path("language").asText().equals(saved.path("language").asText())))
            throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "api.media-function.conflict");
        return prior;
    }
    /** Caller holds the project's event transaction, so task acceptance and draft attachment are atomic. */
    public Task createWithinChange(UUID owner, UUID project, String key, JsonNode input) {
        var now = clock.instant();
        var task = new Task(UUID.randomUUID(), project, null, key, Task.Kind.SUBTITLE_TRANSCRIPTION, Task.Status.READY, false,
                input.deepCopy(), Sha256.hex(input.toString()), null, null, 1, now, null, null, 0, 0, null, now, now, null);
        tasks.create(task);
        append(owner, task);
        return task;
    }
    public Task cancel(UUID owner, UUID project, UUID taskId) {
        return events.recordChange(owner, project, () -> {
            var current = tasks.find(owner, project, taskId).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "api.subtitle.not-found"));
            if (current.kind() != Task.Kind.SUBTITLE_TRANSCRIPTION) throw problem(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "api.subtitle.not-found");
            if (current.status() != Task.Status.READY && current.status() != Task.Status.RUNNING)
                return ProjectEventService.Change.unchanged(current);
            if (!tasks.cancelLocalProcessing(current, clock.instant())) throw problem(HttpStatus.CONFLICT, "SUBTITLE_VERSION_CONFLICT", "api.subtitle.conflict");
            var canceled = tasks.find(owner, project, taskId).orElseThrow();
            append(owner, canceled);
            return ProjectEventService.Change.unchanged(canceled);
        }).value();
    }
    private void append(UUID owner, Task task) {
        events.append(owner, task.projectId(), new ProjectEventService.EventDraft("task.status.changed", 1, task.id(), task.version(),
                mapper.createObjectNode().put("taskId", task.id().toString()).put("status", task.status().name()).put("kind", task.kind().name())
                        .put("subtitleId", task.input().path("subtitleId").asText())
                        .put("possibleExternalCost", false)));
    }
    private static ApiProblemException problem(HttpStatus status, String code, String key) {
        var text = ApiMessage.of(key); return new ApiProblemException(status, code, text, text, false);
    }
}
