package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.AudioTrimParameters;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pins the selected audio version and creates a separate derived node in one project transaction. */
@Service
public class AudioTrimService {
    public static final UUID CAPABILITY_ID = UUID.fromString("00000000-0000-4000-8000-000000000206");
    private final TaskRepository tasks;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final CanvasItemQueryService items;
    private final CanvasService canvas;
    private final MediaDraftService drafts;
    private final ProjectService projects;
    private final MediaCapabilityService catalog;
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AudioTrimService(TaskRepository tasks, ArtifactService artifacts, AssetService assets,
            CanvasItemQueryService items, CanvasService canvas, MediaDraftService drafts,
            ProjectService projects, MediaCapabilityService catalog, ProjectEventService events,
            UsageService usage, ObjectMapper mapper, Clock clock) {
        this.tasks = tasks; this.artifacts = artifacts; this.assets = assets; this.items = items;
        this.canvas = canvas; this.drafts = drafts; this.projects = projects; this.catalog = catalog;
        this.events = events; this.usage = usage; this.mapper = mapper; this.clock = clock;
    }

    public Task run(UUID ownerId, UUID projectId, UUID artifactId, UUID canvasItemId, UUID sourceVersionId,
            long expectedCanvasItemVersion, AudioTrimParameters parameters, String commandKey) {
        return accept(ownerId, projectId, artifactId, canvasItemId, sourceVersionId,
                expectedCanvasItemVersion, parameters, commandKey, null, null);
    }

    /** Trusted tool entry: the origin is authored by the Runtime, never accepted in client parameters. */
    public Task runFromAgent(UUID ownerId, UUID projectId, UUID artifactId, UUID canvasItemId,
            UUID sourceVersionId, long expectedCanvasItemVersion, AudioTrimParameters parameters,
            String commandKey, UUID runId, UUID operationId) {
        if (runId == null || operationId == null) throw AudioTrimParameters.invalid();
        return accept(ownerId, projectId, artifactId, canvasItemId, sourceVersionId,
                expectedCanvasItemVersion, parameters, commandKey, runId, operationId);
    }

    private Task accept(UUID ownerId, UUID projectId, UUID artifactId, UUID canvasItemId, UUID sourceVersionId,
            long expectedCanvasItemVersion, AudioTrimParameters parameters, String commandKey,
            UUID runId, UUID operationId) {
        if (artifactId == null || canvasItemId == null || sourceVersionId == null || expectedCanvasItemVersion < 0
                || parameters == null || commandKey == null || commandKey.isBlank() || commandKey.length() > 160)
            throw AudioTrimParameters.invalid();
        ObjectNode request = mapper.createObjectNode().put("artifactId", artifactId.toString())
                .put("canvasItemId", canvasItemId.toString()).put("sourceVersionId", sourceVersionId.toString())
                .put("expectedCanvasItemVersion", expectedCanvasItemVersion);
        request.set("parameters", parameters.toJson(mapper));
        if (runId != null) request.putObject("agentLocalOrigin").put("runId", runId.toString())
                .put("operationId", operationId.toString());
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            Task prior = tasks.findDirectByStepKey(ownerId, projectId, commandKey).orElse(null);
            if (prior != null) {
                // JSONB reload normalizes small integral CAS values to IntNode.
                if (!mapper.readTree(request.toString()).equals(prior.input().path("audioTrimRequest")))
                    throw conflict("IDEMPOTENCY_CONFLICT", "api.media-function.conflict");
                return ProjectEventService.Change.unchanged(prior);
            }
            var sourceArtifact = artifacts.get(ownerId, projectId, artifactId).artifact();
            if (sourceArtifact.kind() != Artifact.Kind.AUDIO || sourceArtifact.archivedAt() != null)
                throw AudioTrimParameters.invalid();
            var sourceItem = items.requireArtifactItem(ownerId, projectId, canvasItemId);
            if (!sourceItem.subjectId().equals(artifactId) || sourceItem.version() != expectedCanvasItemVersion
                    || !sourceVersionId.equals(sourceItem.selectedVersionId()))
                throw conflict("DIRECT_MEDIA_CONFLICT", "api.media-function.source-changed");
            var source = artifacts.requireMediaVersionForTask(ownerId, projectId, sourceVersionId, Artifact.Kind.AUDIO);
            if (!artifactId.equals(source.artifactId())) throw AudioTrimParameters.invalid();
            var asset = assets.metadata(ownerId, projectId, UUID.fromString(source.content().path("assetId").asText()));
            parameters.requireSource(asset);
            var binding = catalog.resolve(CAPABILITY_ID, Task.Kind.AUDIO_GENERATION, 0);
            if (runId != null && !dev.agenvas.provider.domain.MediaAdapterRegistry.LOCAL_AUDIO_TRIMMER.equals(binding.adapterId()))
                throw AudioTrimParameters.invalid();
            var sourceDraft = drafts.get(ownerId, projectId, canvasItemId);
            var output = canvas.forkMediaDerivationWithinChange(ownerId, projectId, canvasItemId, UUID.randomUUID(),
                    sourceDraft.version(), 0, "音频裁剪");
            var target = artifacts.get(ownerId, projectId, output.subjectId()).artifact();
            ObjectNode input = mapper.createObjectNode().put("schemaVersion", 7)
                    .put("artifactId", target.id().toString()).put("sourceCanvasItemId", canvasItemId.toString())
                    .put("canvasItemId", output.id().toString()).put("canvasItemVersion", output.version())
                    .put("parentVersionId", sourceVersionId.toString())
                    .put("resultSelectionEpoch", canvas.mediaSelectionEpoch(ownerId, projectId, output.id()))
                    .put("resultDraftVersion", drafts.get(ownerId, projectId, output.id()).version())
                    .put("prompt", "音频裁剪").put("sourceDurationMs", asset.durationMs())
                    .put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
            input.set("audioTrimRequest", request);
            if (runId != null) input.set("agentLocalOrigin", request.path("agentLocalOrigin"));
            input.putObject("audioTrim").put("sourceVersionId", sourceVersionId.toString())
                    .set("parameters", parameters.toJson(mapper));
            var frozen = input.putObject("mediaInput");
            frozen.put("mode", "GENERAL_REFERENCE").put("prompt", "音频裁剪").put("renderedPrompt", "音频裁剪")
                    .put("capabilityId", binding.capabilityId().toString()).put("capabilityVersion", binding.capabilityVersion());
            frozen.set("parameters", parameters.toJson(mapper));
            frozen.putArray("images"); frozen.putArray("videos"); frozen.putArray("mentions");
            frozen.putArray("audios").addObject().put("artifactId", artifactId.toString())
                    .put("versionId", sourceVersionId.toString()).put("role", "AUDIO_REFERENCE").put("order", 0);
            var now = clock.instant();
            Task task = new Task(UUID.randomUUID(), projectId, null, commandKey, Task.Kind.AUDIO_GENERATION,
                    Task.Status.READY, false, input, Sha256.hex(input.toString()), null, null, 1,
                    now, null, null, 0, 0, null, now, now, null);
            tasks.create(task); tasks.bindMediaTask(task.id(), binding);
            tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                    target.id(), output.selectedVersionId(), target.version(), output.id()));
            usage.reserveMediaTask(ownerId, task, "LOCAL_NO_COST", binding.connectionVersion());
            var payload = mapper.createObjectNode().put("taskId", task.id().toString()).put("artifactId", target.id().toString())
                    .put("canvasItemId", output.id().toString()).put("sourceCanvasItemId", canvasItemId.toString())
                    .put("status", task.status().name());
            events.append(ownerId, projectId, new ProjectEventService.EventDraft("task.status.changed", 1,
                    task.id(), task.version(), payload));
            return ProjectEventService.Change.unchanged(task);
        }).value();
    }

    private static ApiProblemException conflict(String code, String message) {
        return new ApiProblemException(HttpStatus.CONFLICT, code,
                ApiMessage.of("api.audio-trim.invalid"), ApiMessage.of(message), false);
    }
}
