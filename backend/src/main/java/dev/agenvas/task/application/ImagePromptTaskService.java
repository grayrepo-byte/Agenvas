package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.settings.application.PromptService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Accepts recoverable image-to-prompt work, independent of media generation and its output targets. */
@Service
public class ImagePromptTaskService {
    public static final int INPUT_SCHEMA_VERSION = 3;
    public static final String PROMPT_KEY = "image.reverse-prompt";
    private final TaskRepository tasks;
    private final ArtifactService artifacts;
    private final ProjectService projects;
    private final CanvasItemQueryService canvas;
    private final ChatGateway gateway;
    private final PromptService prompts;
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ImagePromptTaskService(TaskRepository tasks, ArtifactService artifacts, ProjectService projects, CanvasItemQueryService canvas,
            ChatGateway gateway, PromptService prompts, ProjectEventService events, UsageService usage,
            ObjectMapper mapper, Clock clock) {
        this.tasks = tasks; this.artifacts = artifacts; this.canvas = canvas; this.gateway = gateway;
        this.projects = projects;
        this.prompts = prompts; this.events = events; this.usage = usage; this.mapper = mapper; this.clock = clock;
    }

    /** Project serialization prevents duplicate active work; command replay never consults newer settings. */
    @Transactional
    public Task run(UUID owner, UUID project, UUID artifact, UUID item, UUID version,
            long expectedItemVersion, String language, String commandKey) {
        if (item == null || version == null || expectedItemVersion < 0 || commandKey == null
                || commandKey.isBlank() || commandKey.length() > 160) throw problem(HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR", "invalid", false);
        ObjectNode request = mapper.createObjectNode().put("artifactId", artifact.toString())
                .put("canvasItemId", item.toString()).put("sourceVersionId", version.toString())
                .put("expectedCanvasItemVersion", expectedItemVersion);
        return events.recordChange(owner, project, () -> {
            Task prior = tasks.findDirectByStepKey(owner, project, commandKey).orElse(null);
            if (prior != null) {
                // JSONB may restore a small long as an int node; compare the command's typed values.
                var frozen = prior.input().path("request");
                if (!prior.isImagePrompt() || !artifact.toString().equals(frozen.path("artifactId").asText())
                        || !item.toString().equals(frozen.path("canvasItemId").asText())
                        || !version.toString().equals(frozen.path("sourceVersionId").asText())
                        || expectedItemVersion != frozen.path("expectedCanvasItemVersion").asLong(-1))
                    throw problem(HttpStatus.CONFLICT, "IMAGE_PROMPT_CONFLICT", "conflict", false);
                return ProjectEventService.Change.unchanged(prior);
            }
            projects.requireActiveProject(owner, project);
            var source = canvas.requireArtifactItem(owner, project, item);
            if (!artifact.equals(source.subjectId()) || source.version() != expectedItemVersion
                    || !version.equals(source.selectedVersionId()))
                throw problem(HttpStatus.CONFLICT, "IMAGE_PROMPT_CONFLICT", "conflict", false);
            if (requireImage(owner, project, artifact).archivedAt() != null)
                throw problem(HttpStatus.CONFLICT, "IMAGE_PROMPT_CONFLICT", "conflict", false);
            var image = artifacts.requireImageVersionForTask(owner, project, version);
            if (!artifact.equals(image.artifactId())) throw problem(HttpStatus.CONFLICT,
                    "IMAGE_PROMPT_CONFLICT", "conflict", false);
            Task active = tasks.listImagePrompts(owner, project, item).stream()
                    .filter(task -> task.status() == Task.Status.READY || task.status() == Task.Status.RUNNING)
                    .findFirst().orElse(null);
            // A different command cannot alias an existing task without its own durable replay record.
            if (active != null) throw problem(HttpStatus.CONFLICT, "IMAGE_PROMPT_IN_PROGRESS", "in-progress", false);
            var config = gateway.configIdentity();
            var model = gateway.modelDetailsFor(config);
            if (!model.available()) throw problem(HttpStatus.CONFLICT, "LLM_CONFIG_UNAVAILABLE", "model-unavailable", false);
            var prompt = prompts.require(PROMPT_KEY, PromptService.Kind.FUNCTION);
            ObjectNode input = request.deepCopy().put("schemaVersion", INPUT_SCHEMA_VERSION)
                    .put("purpose", Task.IMAGE_PROMPT_PURPOSE).put("language", language)
                    .put("systemPrompt", prompt.content()).put("promptKey", prompt.key()).put("promptVersion", prompt.version())
                    .put("modelConfigSource", config.source()).put("modelConfigVersion", config.version());
            input.set("request", request);
            if (model.providerAdapter() != null) input.put("providerAdapter", model.providerAdapter());
            if (model.modelId() != null) input.put("modelId", model.modelId());
            var now = clock.instant();
            Task task = new Task(UUID.randomUUID(), project, null, commandKey, Task.Kind.TEXT_GENERATION,
                    Task.Status.READY, false, input, Sha256.hex(input.toString()), null, null, 1, now,
                    null, null, 0, 0, null, now, now, null);
            tasks.create(task);
            usage.reserveDirectTextTask(owner, task);
            appendEvent(owner, task);
            return ProjectEventService.Change.unchanged(task);
        }).value();
    }

    @Transactional(readOnly = true)
    public List<Task> list(UUID owner, UUID project, UUID artifact, UUID item) {
        requireImage(owner, project, artifact);
        if (!artifact.equals(canvas.requireArtifactItem(owner, project, item).subjectId()))
            throw problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "not-found", false);
        return tasks.listImagePrompts(owner, project, item);
    }

    /** A canceled response remains private; a late worker cannot checkpoint or publish over the cancel fence. */
    @Transactional
    public Task cancel(UUID owner, UUID project, UUID artifact, UUID taskId) {
        return events.recordChange(owner, project, () -> {
            Task current = tasks.find(owner, project, taskId).filter(Task::isImagePrompt)
                    .filter(task -> artifact.toString().equals(task.input().path("artifactId").asText()))
                    .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "not-found", false));
            if (current.status() == Task.Status.CANCELED) return ProjectEventService.Change.unchanged(current);
            if (!tasks.cancelImagePrompt(current, clock.instant()))
                throw problem(HttpStatus.CONFLICT, "IMAGE_PROMPT_CONFLICT", "conflict", false);
            Task canceled = tasks.find(owner, project, taskId).orElseThrow();
            if (current.output() == null) usage.releaseDirectTextTask(owner, canceled);
            appendEvent(owner, canceled);
            return ProjectEventService.Change.unchanged(canceled);
        }).value();
    }

    private Artifact requireImage(UUID owner, UUID project, UUID artifact) {
        Artifact source = artifacts.get(owner, project, artifact).artifact();
        if (source.kind() != Artifact.Kind.IMAGE)
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "invalid", false);
        return source;
    }
    private void appendEvent(UUID owner, Task task) {
        ObjectNode payload = mapper.createObjectNode().put("taskId", task.id().toString())
                .put("artifactId", task.input().path("artifactId").asText()).put("status", task.status().name());
        events.append(owner, task.projectId(), new ProjectEventService.EventDraft("task.status.changed", 1,
                task.id(), task.version(), payload));
    }
    private static ApiProblemException problem(HttpStatus status, String code, String key, boolean retryable) {
        var message = switch (key) {
            case "invalid" -> ApiMessage.of("api.image-prompt.invalid");
            case "conflict" -> ApiMessage.of("api.image-prompt.conflict");
            case "model-unavailable" -> ApiMessage.of("api.image-prompt.model-unavailable");
            case "not-found" -> ApiMessage.of("api.image-prompt.not-found");
            case "in-progress" -> ApiMessage.of("api.image-prompt.in-progress");
            default -> throw new IllegalArgumentException("Unknown image prompt error");
        };
        return new ApiProblemException(status, code, message, message, retryable);
    }
}
