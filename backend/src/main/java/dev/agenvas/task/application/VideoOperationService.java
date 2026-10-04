package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaFunctionService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.VideoOperation;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Atomically pins the video, creates a fresh derived node and queues one fixed processing command. */
@Service
public class VideoOperationService {
    private static final int MAX_COMMAND_KEY_LENGTH = 160;
    private static final int INPUT_SCHEMA_VERSION = 7;
    private static final int MAX_PROMPT_LENGTH = 4000;
    private static final int MILLIS_PER_SECOND = 1000;
    private final TaskRepository tasks;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final CanvasItemQueryService items;
    private final CanvasService canvas;
    private final MediaDraftService drafts;
    private final MediaFunctionService functions;
    private final MediaCapabilityService catalog;
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper mapper;
    private final Clock clock;

    public VideoOperationService(TaskRepository tasks, ArtifactService artifacts, AssetService assets,
            CanvasItemQueryService items, CanvasService canvas, MediaDraftService drafts,
            MediaFunctionService functions, MediaCapabilityService catalog, ProjectEventService events,
            UsageService usage, ObjectMapper mapper, Clock clock) {
        this.tasks = tasks; this.artifacts = artifacts; this.assets = assets; this.items = items;
        this.canvas = canvas; this.drafts = drafts; this.functions = functions; this.catalog = catalog;
        this.events = events; this.usage = usage; this.mapper = mapper; this.clock = clock;
    }

    public Task run(UUID ownerId, UUID projectId, UUID artifactId, UUID itemId, UUID sourceVersionId,
            long expectedItemVersion, VideoOperation operation, long expectedFunctionVersion, int expectedCapabilityVersion,
            String prompt, JsonNode parameters, String commandKey) {
        if (itemId == null || sourceVersionId == null || operation == null || expectedItemVersion < 0
                || expectedFunctionVersion < 0 || expectedCapabilityVersion < 1 || commandKey == null || commandKey.isBlank()
                || commandKey.length() > MAX_COMMAND_KEY_LENGTH || parameters == null || !parameters.isObject()) {
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.media-function.invalid-request"));
        }
        String instruction = prompt == null ? "" : prompt.trim();
        if (instruction.length() > MAX_PROMPT_LENGTH) {
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.media-function.invalid-request"));
        }
        ObjectNode request = mapper.createObjectNode().put("artifactId", artifactId.toString())
                .put("canvasItemId", itemId.toString()).put("sourceVersionId", sourceVersionId.toString())
                .put("expectedCanvasItemVersion", expectedItemVersion).put("operation", operation.name())
                .put("expectedFunctionVersion", expectedFunctionVersion).put("expectedCapabilityVersion", expectedCapabilityVersion).put("prompt", instruction);
        request.set("parameters", parameters.deepCopy());
        return events.recordChange(ownerId, projectId, () -> {
            Task prior = tasks.findDirectByStepKey(ownerId, projectId, commandKey).orElse(null);
            if (prior != null) {
                // JSONB reloads small integral values as IntNode, including originally long CAS versions.
                if (!mapper.readTree(request.toString()).equals(prior.input().path("videoOperationRequest"))) {
                    throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ApiMessage.of("api.media-function.conflict"));
                }
                return ProjectEventService.Change.unchanged(prior);
            }
            Artifact sourceArtifact = artifacts.get(ownerId, projectId, artifactId).artifact();
            var sourceItem = items.requireArtifactItem(ownerId, projectId, itemId);
            if (sourceArtifact.kind() != Artifact.Kind.VIDEO || sourceArtifact.archivedAt() != null) {
                throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.media-function.video-required"));
            }
            if (!sourceItem.subjectId().equals(artifactId) || sourceItem.version() != expectedItemVersion
                    || !sourceVersionId.equals(sourceItem.selectedVersionId())) {
                throw problem(HttpStatus.CONFLICT, "DIRECT_MEDIA_CONFLICT", ApiMessage.of("api.media-function.source-changed"));
            }
            var source = artifacts.requireMediaVersionForTask(ownerId, projectId, sourceVersionId, Artifact.Kind.VIDEO);
            var asset = assets.metadata(ownerId, projectId, UUID.fromString(source.content().path("assetId").asText()));
            var binding = functions.resolve(operation, expectedFunctionVersion);
            if (binding.capabilityVersion() != expectedCapabilityVersion) {
                throw problem(HttpStatus.CONFLICT, "MEDIA_CAPABILITY_CHANGED", ApiMessage.of("api.media-function.conflict"));
            }
            boolean local = MediaAdapterRegistry.localProcessor(binding.adapterId());
            ObjectNode frozenParameters = mapper.createObjectNode();
            RunningHubDefinition definition = catalog.runningHubDefinition(binding);
            Integer seconds = null;
            if (local) {
                if (!parameters.isEmpty()) throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.media-function.invalid-request"));
                LocalVideoOperationBounds.require(asset);
                seconds = (asset.durationMs() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND;
            } else {
                if (asset.byteSize() > dev.agenvas.provider.infrastructure.RunningHubClient.MAX_UPLOAD_BYTES) {
                    throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.direct-media-task-service.a-single-piece-of-runninghub-material-cannot-exceed-30-mb"));
                }
                var videoField = definition.fields().stream().filter(RunningHubDefinition.Field::media).findFirst().orElseThrow();
                ObjectNode supplied = (ObjectNode) parameters.deepCopy();
                if (supplied.propertyNames().stream().anyMatch(key -> !Set.of(RunningHubDefinition.VALUES_PROPERTY).contains(key))) {
                    throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.media-function.invalid-request"));
                }
                var raw = supplied.path(RunningHubDefinition.VALUES_PROPERTY);
                if (!raw.isMissingNode() && !raw.isObject()) throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.media-function.invalid-request"));
                // The tool owns its source slot; client parameters cannot replace the pinned video.
                supplied.withObject(RunningHubDefinition.VALUES_PROPERTY).put(videoField.key(), sourceVersionId.toString());
                seconds = definition.fields().stream().filter(field -> field.effectiveSource() == RunningHubDefinition.Source.DURATION_SECONDS)
                        .findFirst().map(field -> {
                            JsonNode value = raw.get(field.key());
                            if (value == null || value.isNull()) value = field.defaultValue();
                            if (value == null || value.isNull()) return null;
                            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                                throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.media-function.invalid-request"));
                            }
                            return value.intValue();
                        }).orElse(null);
                frozenParameters.set(RunningHubDefinition.VALUES_PROPERTY,
                        definition.values(mapper, supplied, instruction, seconds, true));
            }
            var sourceDraft = drafts.get(ownerId, projectId, itemId);
            var output = operation == VideoOperation.EXTRACT_AUDIO
                    ? canvas.forkAudioDerivationWithinChange(ownerId, projectId, itemId, operation.resultLabel())
                    : canvas.forkMediaDerivationWithinChange(ownerId, projectId, itemId, UUID.randomUUID(),
                            sourceDraft.version(), 0, operation.resultLabel());
            Artifact target = artifacts.get(ownerId, projectId, output.subjectId()).artifact();
            ObjectNode input = mapper.createObjectNode().put("schemaVersion", INPUT_SCHEMA_VERSION)
                    .put("artifactId", target.id().toString()).put("sourceCanvasItemId", itemId.toString())
                    .put("canvasItemId", output.id().toString()).put("canvasItemVersion", output.version())
                    .put("resultSelectionEpoch", canvas.mediaSelectionEpoch(ownerId, projectId, output.id()))
                    .put("resultDraftVersion", drafts.get(ownerId, projectId, output.id()).version())
                    .put("prompt", instruction.isBlank() ? operation.resultLabel() : instruction)
                    .put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
            if (output.selectedVersionId() == null) input.putNull("parentVersionId");
            else input.put("parentVersionId", output.selectedVersionId().toString());
            if (operation.taskKind() == Task.Kind.VIDEO_GENERATION && seconds != null) input.put("durationSeconds", seconds);
            if (local) input.put("sourceDurationMs", asset.durationMs());
            input.set("videoOperationRequest", request);
            input.putObject("videoOperation").put("name", operation.name()).put("sourceVersionId", sourceVersionId.toString());
            ObjectNode frozen = input.putObject("mediaInput");
            frozen.put("mode", "GENERAL_REFERENCE").put("prompt", instruction).put("renderedPrompt", instruction)
                    .put("capabilityId", binding.capabilityId().toString()).put("capabilityVersion", binding.capabilityVersion());
            frozen.set("parameters", frozenParameters);
            frozen.putArray("images"); frozen.putArray("audios"); frozen.putArray("mentions");
            frozen.putArray("videos").addObject().put("artifactId", artifactId.toString())
                    .put("versionId", sourceVersionId.toString()).put("role", "VIDEO_REFERENCE").put("order", 0);
            if (definition != null) {
                input.put("providerProtocol", "RUNNINGHUB_V2");
                frozen.set("runningHubContract", mapper.valueToTree(definition));
                JsonNode pricing = catalog.settings(binding).get("pricing");
                if (pricing != null) input.set("mediaPricing", pricing);
            }
            var now = clock.instant();
            Task task = new Task(UUID.randomUUID(), projectId, null, commandKey, operation.taskKind(),
                    Task.Status.READY, false, input, Sha256.hex(input.toString()), null, null, 1,
                    now, null, null, 0, 0, null, now, now, null);
            tasks.create(task); tasks.bindMediaTask(task.id(), binding);
            tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                    target.id(), output.selectedVersionId(), target.version(), output.id()));
            usage.reserveMediaTask(ownerId, task, local ? "LOCAL_NO_COST" : "PROVIDER_UNPRICED", binding.connectionVersion());
            ObjectNode payload = mapper.createObjectNode().put("taskId", task.id().toString())
                    .put("artifactId", target.id().toString()).put("canvasItemId", output.id().toString())
                    .put("sourceCanvasItemId", itemId.toString()).put("status", task.status().name());
            events.append(ownerId, projectId, new ProjectEventService.EventDraft("task.status.changed", 1,
                    task.id(), task.version(), payload));
            return ProjectEventService.Change.unchanged(task);
        }).value();
    }

    private static ApiProblemException problem(HttpStatus status, String code, ApiMessage detail) {
        return new ApiProblemException(status, code, ApiMessage.of("api.media-function.title"), detail, false);
    }
}
