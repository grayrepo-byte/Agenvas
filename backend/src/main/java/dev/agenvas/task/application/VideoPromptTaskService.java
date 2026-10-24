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
import dev.agenvas.skill.application.SkillService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Text-only prompt assistance. Acceptance freezes local text and an optional published Skill. */
@Service
public class VideoPromptTaskService {
    public static final int INPUT_SCHEMA_VERSION = 4;
    public static final String PROMPT_KEY = "video.optimize-prompt";
    public record SkillRef(@NotNull UUID skillId, @NotNull UUID skillVersionId) {}
    public record Request(@NotBlank @Size(max = 22000) String prompt,
            @NotNull @Min(1) @Max(20000) Integer maxLength, @Valid SkillRef skill) {}
    private final TaskRepository tasks;
    private final ArtifactService artifacts;
    private final CanvasItemQueryService canvas;
    private final ProjectService projects;
    private final ChatGateway gateway;
    private final PromptService prompts;
    private final SkillService skills;
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper mapper;
    private final Clock clock;

    public VideoPromptTaskService(TaskRepository tasks, ArtifactService artifacts, CanvasItemQueryService canvas,
            ProjectService projects, ChatGateway gateway, PromptService prompts, SkillService skills,
            ProjectEventService events, UsageService usage, ObjectMapper mapper, Clock clock) {
        this.tasks = tasks; this.artifacts = artifacts; this.canvas = canvas; this.projects = projects;
        this.gateway = gateway; this.prompts = prompts; this.skills = skills; this.events = events;
        this.usage = usage; this.mapper = mapper; this.clock = clock;
    }

    /** Replaying a command does not re-read a newer Skill, prompt definition or model configuration. */
    @Transactional
    public Task run(UUID owner, UUID project, UUID item, Request request, String language, String key) {
        if (request == null || request.prompt() == null || request.prompt().isBlank() || request.prompt().length() > 22000
                || request.maxLength() == null || request.maxLength() < 1 || request.maxLength() > 20000
                || request.skill() != null && (request.skill().skillId() == null || request.skill().skillVersionId() == null)
                || key == null || key.isBlank() || key.length() > 160) throw problem("invalid", HttpStatus.BAD_REQUEST);
        ObjectNode command = mapper.valueToTree(request);
        command.put("canvasItemId", item.toString());
        String commandHash = Sha256.hex(command.toString());
        return events.recordChange(owner, project, () -> {
            Task prior = tasks.findDirectByStepKey(owner, project, key).orElse(null);
            if (prior != null) {
                if (!prior.isVideoPromptOptimization() || !commandHash.equals(prior.input().path("commandHash").asText()))
                    throw problem("conflict", HttpStatus.CONFLICT);
                return ProjectEventService.Change.unchanged(prior);
            }
            projects.requireActiveProject(owner, project);
            UUID artifact = requireVideo(owner, project, item);
            if (list(owner, project, item).stream().anyMatch(task -> task.status() == Task.Status.READY || task.status() == Task.Status.RUNNING))
                throw problem("in-progress", HttpStatus.CONFLICT);
            var config = gateway.configIdentity();
            var model = gateway.modelDetailsFor(config);
            if (!model.available()) throw problem("model-unavailable", HttpStatus.CONFLICT);
            var definition = prompts.require(PROMPT_KEY, PromptService.Kind.FUNCTION);
            ObjectNode input = command.deepCopy().put("schemaVersion", INPUT_SCHEMA_VERSION)
                    .put("purpose", Task.VIDEO_PROMPT_PURPOSE).put("artifactId", artifact.toString())
                    .put("commandHash", commandHash).put("language", language)
                    .put("systemPrompt", definition.content()).put("promptKey", definition.key()).put("promptVersion", definition.version())
                    .put("modelConfigSource", config.source()).put("modelConfigVersion", config.version());
            if (model.providerAdapter() != null) input.put("providerAdapter", model.providerAdapter());
            if (model.modelId() != null) input.put("modelId", model.modelId());
            if (request.skill() != null) {
                var skill = skills.requireSelectableVersion(owner, request.skill().skillId(), request.skill().skillVersionId());
                // This purpose consumes only textual methods. Slot mappings and fixed media remain Agent-only inputs.
                ObjectNode context = mapper.createObjectNode().put("name", skill.bundle().name())
                        .put("bundleHash", skill.bundleHash()).put("versionNumber", skill.versionNumber())
                        .put("skillMd", skill.bundle().skillMd());
                context.set("resources", mapper.valueToTree(skill.bundle().resources()));
                input.set("skillContext", context);
                input.put("skillName", skill.bundle().name()).put("skillVersionNumber", skill.versionNumber());
            }
            var now = clock.instant();
            Task task = new Task(UUID.randomUUID(), project, null, key, Task.Kind.TEXT_GENERATION, Task.Status.READY,
                    false, input, Sha256.hex(input.toString()), null, null, 1, now, null, null, 0, 0, null, now, now, null);
            tasks.create(task);
            usage.reserveDirectTextTask(owner, task);
            appendEvent(owner, task);
            return ProjectEventService.Change.unchanged(task);
        }).value();
    }

    @Transactional(readOnly = true)
    public List<Task> list(UUID owner, UUID project, UUID item) {
        requireVideo(owner, project, item);
        return tasks.listPromptTasks(owner, project, item, Task.VIDEO_PROMPT_PURPOSE);
    }

    @Transactional
    public Task cancel(UUID owner, UUID project, UUID item, UUID taskId) {
        return events.recordChange(owner, project, () -> {
            Task current = tasks.find(owner, project, taskId).filter(Task::isVideoPromptOptimization)
                    .filter(task -> item.toString().equals(task.input().path("canvasItemId").asText()))
                    .orElseThrow(() -> problem("not-found", HttpStatus.NOT_FOUND));
            if (current.status() == Task.Status.CANCELED) return ProjectEventService.Change.unchanged(current);
            if (!tasks.cancelPromptTask(current, clock.instant())) throw problem("conflict", HttpStatus.CONFLICT);
            Task canceled = tasks.find(owner, project, taskId).orElseThrow();
            if (current.output() == null) usage.releaseDirectTextTask(owner, canceled);
            appendEvent(owner, canceled);
            return ProjectEventService.Change.unchanged(canceled);
        }).value();
    }

    private UUID requireVideo(UUID owner, UUID project, UUID item) {
        var source = canvas.requireArtifactItem(owner, project, item);
        var artifact = artifacts.get(owner, project, source.subjectId()).artifact();
        if (artifact.kind() != Artifact.Kind.VIDEO || artifact.archivedAt() != null) throw problem("invalid", HttpStatus.BAD_REQUEST);
        return artifact.id();
    }
    private void appendEvent(UUID owner, Task task) {
        events.append(owner, task.projectId(), new ProjectEventService.EventDraft("task.status.changed", 1, task.id(), task.version(),
                mapper.createObjectNode().put("taskId", task.id().toString()).put("status", task.status().name())
                        .put("canvasItemId", task.input().path("canvasItemId").asText())));
    }
    private static ApiProblemException problem(String key, HttpStatus status) {
        String code = switch (key) {
            case "conflict" -> "VIDEO_PROMPT_CONFLICT";
            case "in-progress" -> "VIDEO_PROMPT_IN_PROGRESS";
            case "model-unavailable" -> "LLM_CONFIG_UNAVAILABLE";
            case "not-found" -> "RESOURCE_NOT_FOUND";
            default -> "VALIDATION_ERROR";
        };
        var message = ApiMessage.of("api.video-prompt." + key);
        return new ApiProblemException(status, code, message, message, false);
    }
}
