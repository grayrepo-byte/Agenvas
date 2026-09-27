package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
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
            throw invalid("需要有效提示词、当前文字版本、产物版本和 Idempotency-Key。");
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
                    throw conflict("相同幂等键已用于不同的文字生成请求。");
                }
                return ProjectEventService.Change.unchanged(prior);
            }
            Task occupying = tasks.findOccupyingMediaTask(projectId, artifactId).orElse(null);
            if (occupying != null) return ProjectEventService.Change.unchanged(occupying);
            ArtifactService.ArtifactView target = artifacts.get(ownerId, projectId, artifactId);
            Artifact artifact = target.artifact();
            if (artifact.kind() != Artifact.Kind.TEXT || artifact.archivedAt() != null
                    || artifact.version() != expectedArtifactVersion
                    || !expectedCurrentVersionId.equals(artifact.currentVersionId())
                    || target.currentVersion() == null) {
                throw conflict("文字卡片已变化，请刷新后重新生成。");
            }
            ChatGateway.ConfigIdentity config = gateway.configIdentity();
            ChatGateway.ModelDetails model = gateway.modelDetailsFor(config);
            if (!model.available()) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "LLM_CONFIG_UNAVAILABLE",
                        "文字模型不可用", "请先配置可用的文字模型。", false);
            }
            JsonNode current = target.currentVersion().content();
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
                    hash(input.toString()), null, null, null, 1, now,
                    null, null, 0, 0, null, now, now, null);
            tasks.create(task, List.of());
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
        if (target.artifact().kind() != Artifact.Kind.TEXT) throw invalid("目标不是文字卡片。");
        return tasks.listDirectForArtifact(ownerId, projectId, artifactId).stream()
                .filter(task -> task.kind() == Task.Kind.TEXT_GENERATION).toList();
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "文字生成输入无效", detail, false);
    }

    private static ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "DIRECT_TEXT_CONFLICT",
                "文字生成冲突", detail, true);
    }
}
