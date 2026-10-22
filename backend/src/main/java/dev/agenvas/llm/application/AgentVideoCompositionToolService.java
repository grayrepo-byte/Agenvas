package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.VideoCompositionService;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Converts a model-authored edit into one local task after checking every exact input in the Run scope. */
@Service
public class AgentVideoCompositionToolService {
    private static final Set<String> REQUEST_FIELDS = Set.of("title", "rationale", "originalAudioMode", "shots", "music", "output", "subtitles");
    private static final Set<String> SHOT_FIELDS = Set.of("videoVersionId", "sourceStartMs", "durationMs", "lines", "speed", "fadeMs");
    private static final Set<String> LINE_FIELDS = Set.of("audioVersionId", "startMs");
    private final ArtifactService artifacts;
    private final VideoCompositionService compositions;
    private final ObjectMapper mapper;

    public AgentVideoCompositionToolService(ArtifactService artifacts, VideoCompositionService compositions,
            ObjectMapper mapper) {
        this.artifacts = artifacts;
        this.compositions = compositions;
        this.mapper = mapper;
    }

    /** Tool execution's transaction owns the receipt and task together; FFmpeg never runs here. */
    public JsonNode compose(TrustedToolContext context, AgentRun run, UUID operationId, String arguments) {
        JsonNode request;
        try {
            request = mapper.readTree(arguments);
        } catch (RuntimeException malformed) {
            throw invalid();
        }
        requireFields(request, REQUEST_FIELDS);
        JsonNode shots = request.path("shots");
        if (!shots.isArray() || shots.isEmpty() || shots.size() > 12) throw invalid();
        int lineCount = 0;
        for (JsonNode shot : shots) {
            requireFields(shot, SHOT_FIELDS);
            requireSource(context, run, shot.path("videoVersionId"), Artifact.Kind.VIDEO);
            JsonNode lines = shot.path("lines");
            if (!lines.isArray() || (lineCount += lines.size()) > 30) throw invalid();
            for (JsonNode line : lines) {
                requireFields(line, LINE_FIELDS);
                requireSource(context, run, line.path("audioVersionId"), Artifact.Kind.AUDIO);
            }
        }
        if (request.has("music")) requireSource(context, run, request.path("music").path("audioVersionId"), Artifact.Kind.AUDIO);
        var task = compositions.runFromAgent(context.ownerId(), context.projectId(), context.runId(),
                request, "agent-video-composition:" + operationId);
        String artifactId = task.input().path("artifactId").asText();
        String canvasItemId = task.input().path("canvasItemId").asText();
        ObjectNode result = mapper.createObjectNode().put("status", ToolResultStatus.SUCCEEDED.name())
                .put("operationId", operationId.toString()).put("userVisibleSummary", "已提交本地配音合成任务，将在后台独立执行，可在新节点取消；成片尚未完成");
        result.putArray("createdIds").add(artifactId);
        result.putArray("createdCanvasItemIds").add(canvasItemId);
        result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds").add(task.id().toString());
        result.putNull("errorCode");
        result.putObject("data").put("taskId", task.id().toString()).put("status", task.status().name())
                .put("artifactId", artifactId).put("canvasItemId", canvasItemId)
                .put("queued", true).put("completed", false).put("localProcessing", true);
        return result;
    }

    private void requireSource(TrustedToolContext context, AgentRun run, JsonNode supplied, Artifact.Kind kind) {
        if (!supplied.isTextual()) throw invalid();
        UUID id;
        try {
            id = UUID.fromString(supplied.asText());
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        var version = artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(),
                run.contextSnapshot(), id);
        if (artifacts.get(context.ownerId(), context.projectId(), version.artifactId()).artifact().kind() != kind)
            throw invalid();
    }

    private static void requireFields(JsonNode object, Set<String> allowed) {
        if (object == null || !object.isObject()
                || object.propertyNames().stream().anyMatch(field -> !allowed.contains(field))) throw invalid();
    }

    private static ApiProblemException invalid() {
        var message = ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid");
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID", message, message, false);
    }
}
