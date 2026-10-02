package dev.agenvas.task.application;

import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Accepts one direct text-card prompt as immutable recoverable model work. */
@Service
public class DirectTextTaskService {
    private static final int MAX_PROMPT_LENGTH = 20_000;
    private static final int MAX_COMMAND_KEY_LENGTH = 160;

    private final TaskRepository tasks;
    private final ArtifactService artifacts;
    private final ChatGateway gateway;
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DirectTextTaskService(TaskRepository tasks, ArtifactService artifacts,
            ChatGateway gateway, ProjectEventService events, UsageService usage,
            ObjectMapper mapper, Clock clock) {
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.gateway = gateway;
        this.events = events;
        this.usage = usage;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Pins the prompt, selected text version and current model configuration in one Task. */
    @Transactional
    public Task run(UUID ownerId, UUID projectId, UUID artifactId, String requestedPrompt,
            long expectedArtifactVersion, UUID expectedCurrentVersionId, String commandKey) {
        String prompt = requestedPrompt == null ? "" : requestedPrompt.trim();
        if (prompt.isEmpty() || prompt.length() > MAX_PROMPT_LENGTH
                || expectedArtifactVersion < 0 || expectedCurrentVersionId == null
                || commandKey == null || commandKey.isBlank()
                || commandKey.length() > MAX_COMMAND_KEY_LENGTH) {
            throw invalid(ApiMessage.of("api.direct-text-task-service.requires-a-valid-prompt-word-current-text-version-product-version"));
        }
        return events.recordChange(ownerId, projectId, () -> {
            Task prior = tasks.findDirectByStepKey(ownerId, projectId, commandKey).orElse(null);
            if (prior != null) {
                if (prior.kind() != Task.Kind.TEXT_GENERATION
                        || !prior.input().path("artifactId").asText().equals(artifactId.toString())
                        || !prior.input().path("prompt").asText().equals(prompt)
                        || prior.input().path("expectedArtifactVersion").asLong(-1)
                                != expectedArtifactVersion
                        || !prior.input().path("expectedCurrentVersionId").asText()
                                .equals(expectedCurrentVersionId.toString())) {
                    throw conflict(ApiMessage.of("api.direct-text-task-service.the-same-idempotent-key-has-been-used-in-different-text"));
                }
                return ProjectEventService.Change.unchanged(prior);
            }
            Task occupying = tasks.findOccupyingMediaTask(projectId, artifactId).orElse(null);
            if (occupying != null) return ProjectEventService.Change.unchanged(occupying);
            ArtifactService.ArtifactView target = artifacts.get(ownerId, projectId, artifactId);
            Artifact artifact = target.artifact();
            if (artifact.kind() != Artifact.Kind.TEXT || artifact.archivedAt() != null
                    || artifact.version() != expectedArtifactVersion
                    || !expectedCurrentVersionId.equals(artifact.resourceDefaultVersionId())
                    || target.resourceDefaultVersion() == null) {
                throw conflict(ApiMessage.of("api.direct-text-task-service.the-text-card-has-changed-please-refresh-and-regenerate"));
            }
            ChatGateway.ConfigIdentity config = gateway.configIdentity();
            ChatGateway.ModelDetails model = gateway.modelDetailsFor(config);
            if (!model.available()) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "LLM_CONFIG_UNAVAILABLE",
                        ApiMessage.of("api.direct-text-task-service.text-model-is-not-available"), ApiMessage.of("api.direct-text-task-service.please-configure-available-text-models-first"), false);
            }
            JsonNode current = target.resourceDefaultVersion().content();
            ObjectNode input = mapper.createObjectNode();
            input.put("schemaVersion", 1);
            input.put("artifactId", artifactId.toString());
            input.put("expectedArtifactVersion", expectedArtifactVersion);
            input.put("expectedCurrentVersionId", expectedCurrentVersionId.toString());
            input.put("prompt", prompt);
            input.put("currentText", current.path("text").asText(""));
            input.put("format", "MARKDOWN".equals(current.path("format").asText())
                    ? "MARKDOWN" : "PLAIN_TEXT");
            input.put("modelConfigSource", config.source());
            input.put("modelConfigVersion", config.version());
            if (model.providerAdapter() != null) input.put("providerAdapter", model.providerAdapter());
            if (model.modelId() != null) input.put("modelId", model.modelId());
            Instant now = clock.instant();
            Task task = new Task(UUID.randomUUID(), projectId, null, commandKey,
                    Task.Kind.TEXT_GENERATION, Task.Status.READY, false, input,
                    Sha256.hex(input.toString()), null, null, 1, now,
                    null, null, 0, 0, null, now, now, null);
            tasks.create(task);
            tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                    artifactId, expectedCurrentVersionId, expectedArtifactVersion, null));
            usage.reserveDirectTextTask(ownerId, task);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("taskId", task.id().toString());
            payload.put("artifactId", artifactId.toString());
            payload.put("status", task.status().name());
            events.append(ownerId, projectId,
                    new ProjectEventService.EventDraft("task.status.changed", 1, task.id(),
                            task.version(), payload));
            return ProjectEventService.Change.unchanged(task);
        }).value();
    }

    @Transactional(readOnly = true)
    public List<Task> list(UUID ownerId, UUID projectId, UUID artifactId) {
        ArtifactService.ArtifactView target = artifacts.get(ownerId, projectId, artifactId);
        if (target.artifact().kind() != Artifact.Kind.TEXT) throw invalid(ApiMessage.of("api.direct-text-task-service.the-goal-is-not-a-word-card"));
        return tasks.listDirectForArtifact(ownerId, projectId, artifactId).stream()
                .filter(task -> task.kind() == Task.Kind.TEXT_GENERATION).toList();
    }

    private static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.direct-text-task-service.invalid-text-generation-input"), detail, false);
    }

    private static ApiProblemException conflict(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "DIRECT_TEXT_CONFLICT",
                ApiMessage.of("api.direct-text-task-service.text-generation-conflict"), detail, true);
    }
}
