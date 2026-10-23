package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.AudioTrimService;
import dev.agenvas.task.application.VideoOperationService;
import dev.agenvas.task.domain.AudioExtractionParameters;
import dev.agenvas.task.domain.AudioTrimParameters;
import dev.agenvas.task.domain.Task;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Thin trusted-tool boundary for fixed local audio commands; decoding never happens in this transaction. */
@Service
public class AgentLocalMediaToolService {
    private static final Set<String> EXTRACT_FIELDS = Set.of("canvasItemId", "sourceVersionId",
            "expectedCanvasItemVersion", "format", "trackIndex");
    private static final Set<String> TRIM_FIELDS = Set.of("canvasItemId", "sourceVersionId",
            "expectedCanvasItemVersion", "startMs", "endMs", "format");
    private final CanvasItemQueryService items;
    private final ArtifactService artifacts;
    private final VideoOperationService videoOperations;
    private final AudioTrimService trims;
    private final ObjectMapper mapper;

    public AgentLocalMediaToolService(CanvasItemQueryService items, ArtifactService artifacts,
            VideoOperationService videoOperations, AudioTrimService trims, ObjectMapper mapper) {
        this.items = items; this.artifacts = artifacts; this.videoOperations = videoOperations;
        this.trims = trims; this.mapper = mapper;
    }

    public JsonNode extract(TrustedToolContext context, AgentRun run, UUID operationId, String arguments) {
        ObjectNode input = parse(arguments, EXTRACT_FIELDS);
        UUID versionId = uuid(input, "sourceVersionId");
        long expected = integer(input, "expectedCanvasItemVersion", Long.MAX_VALUE);
        ObjectNode parameters = mapper.createObjectNode();
        if (input.has("format")) parameters.set("format", input.get("format"));
        if (input.has("trackIndex")) parameters.set("trackIndex", input.get("trackIndex"));
        var extraction = AudioExtractionParameters.parse(parameters);
        CanvasItem source = requireSource(context, run, input, versionId, expected, Artifact.Kind.VIDEO);
        Task task = videoOperations.extractAudioFromAgent(context.ownerId(), context.projectId(), source.subjectId(),
                source.id(), versionId, expected, extraction, "agent-audio-extract:" + operationId,
                context.runId(), operationId);
        return receipt(operationId, task, "已受理本地音轨提取，完成后导演将自动继续");
    }

    public JsonNode trim(TrustedToolContext context, AgentRun run, UUID operationId, String arguments) {
        ObjectNode input = parse(arguments, TRIM_FIELDS);
        UUID versionId = uuid(input, "sourceVersionId");
        long expected = integer(input, "expectedCanvasItemVersion", Long.MAX_VALUE);
        ObjectNode format = mapper.createObjectNode();
        if (input.has("format")) format.set("format", input.get("format"));
        var parameters = new AudioTrimParameters((int) integer(input, "startMs", 599_900),
                (int) integer(input, "endMs", 600_000), AudioExtractionParameters.parse(format).format());
        CanvasItem source = requireSource(context, run, input, versionId, expected, Artifact.Kind.AUDIO);
        Task task = trims.runFromAgent(context.ownerId(), context.projectId(), source.subjectId(), source.id(),
                versionId, expected, parameters, "agent-audio-trim:" + operationId, context.runId(), operationId);
        return receipt(operationId, task, "已受理本地音频裁剪，完成后导演将自动继续");
    }

    private CanvasItem requireSource(TrustedToolContext context, AgentRun run, JsonNode input, UUID versionId,
            long expected, Artifact.Kind kind) {
        CanvasItem item = items.requireArtifactItem(context.ownerId(), context.projectId(), uuid(input, "canvasItemId"));
        if (item.locked()) throw problem(HttpStatus.CONFLICT, "CANVAS_ITEM_LOCKED",
                "api.creative-artifact-tool-service.locked-cards-cannot-be-arranged-by-the-agent");
        if (item.version() != expected || !versionId.equals(item.selectedVersionId()))
            throw problem(HttpStatus.CONFLICT, "DIRECT_MEDIA_CONFLICT", "api.media-function.source-changed");
        var version = artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), run.contextSnapshot(), context.runId(), versionId);
        var artifact = artifacts.get(context.ownerId(), context.projectId(), item.subjectId()).artifact();
        if (!item.subjectId().equals(version.artifactId()) || artifact.kind() != kind || artifact.archivedAt() != null)
            throw invalid();
        return item;
    }

    private ObjectNode receipt(UUID operationId, Task task, String summary) {
        String artifact = task.input().path("artifactId").asText(), item = task.input().path("canvasItemId").asText();
        ObjectNode result = mapper.createObjectNode().put("status", ToolResultStatus.SUCCEEDED.name())
                .put("operationId", operationId.toString()).put("userVisibleSummary", summary).put("awaitingLocalTask", true);
        result.putArray("createdIds").add(artifact); result.putArray("createdCanvasItemIds").add(item);
        result.putArray("updatedIds"); result.putObject("affectedVersions");
        result.putArray("taskIds").add(task.id().toString()); result.putNull("errorCode");
        result.putObject("data").put("taskId", task.id().toString()).put("status", task.status().name())
                .put("artifactId", artifact).put("canvasItemId", item)
                .put("queued", true).put("completed", false).put("localProcessing", true);
        return result;
    }

    private ObjectNode parse(String arguments, Set<String> allowed) {
        JsonNode parsed;
        try { parsed = mapper.readTree(arguments); } catch (RuntimeException malformed) { throw invalid(); }
        if (!(parsed instanceof ObjectNode object) || !allowed.containsAll(object.propertyNames())) throw invalid();
        return object;
    }

    private UUID uuid(JsonNode input, String field) {
        JsonNode value = input.path(field);
        if (!value.isTextual()) throw invalid();
        try {
            UUID parsed = UUID.fromString(value.asText());
            if (!parsed.toString().equalsIgnoreCase(value.asText())) throw invalid();
            return parsed;
        } catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    private long integer(JsonNode input, String field, long maximum) {
        JsonNode value = input.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() > maximum)
            throw invalid();
        return value.longValue();
    }

    private static ApiProblemException invalid() {
        return problem(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID", "api.tool-execution-service.tool-parameter-is-invalid");
    }
    private static ApiProblemException problem(HttpStatus status, String code, String key) {
        var message = ApiMessage.of(key);
        return new ApiProblemException(status, code, message, message, false);
    }
}
