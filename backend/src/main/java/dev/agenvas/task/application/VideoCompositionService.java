package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Accepts semantic shot/dialogue assignments. Waveform analysis and rendering happen in the worker,
 * outside this short transaction. Exact source versions remain immutable throughout the operation. */
@Service
public class VideoCompositionService {
    public static final UUID CAPABILITY_ID = UUID.fromString("00000000-0000-4000-8000-000000000205");
    public static final int MAX_DURATION_MS = 60_000;
    private final TaskRepository tasks;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final CanvasService canvas;
    private final MediaDraftService drafts;
    private final ProjectService projects;
    private final MediaCapabilityService catalog;
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper mapper;
    private final Clock clock;

    public VideoCompositionService(TaskRepository tasks, ArtifactService artifacts, AssetService assets,
            CanvasService canvas, MediaDraftService drafts, ProjectService projects,
            MediaCapabilityService catalog, ProjectEventService events, UsageService usage,
            ObjectMapper mapper, Clock clock) {
        this.tasks = tasks; this.artifacts = artifacts; this.assets = assets; this.canvas = canvas;
        this.drafts = drafts; this.projects = projects; this.catalog = catalog; this.events = events;
        this.usage = usage; this.mapper = mapper; this.clock = clock;
    }

    public Task run(UUID owner, UUID project, JsonNode request, String commandKey) {
        return accept(owner, project, request, commandKey, null, null, null);
    }

    /** Called only after the built-in tool has checked the Run's current content scope. */
    public Task runFromAgent(UUID owner, UUID project, UUID run, JsonNode request, String commandKey) {
        if (run == null) throw invalid();
        return accept(owner, project, request, commandKey, run, null, null);
    }

    /** External operation/token identities come from the authenticated MCP command ledger. */
    public Task runExternal(UUID owner, UUID project, JsonNode request, UUID token, UUID operation) {
        if (token == null || operation == null) throw invalid();
        return accept(owner, project, request, "mcp:" + operation + ":composition", null, token, operation);
    }

    private Task accept(UUID owner, UUID project, JsonNode raw, String key, UUID run, UUID token, UUID operation) {
        if (key == null || key.isBlank() || key.length() > 160) throw invalid();
        ObjectNode request = normalize(raw);
        ObjectNode origin = mapper.createObjectNode().put("runId", run == null ? null : run.toString())
                .put("tokenId", token == null ? null : token.toString())
                .put("operationId", operation == null ? null : operation.toString());
        return events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            Task prior = tasks.findDirectByStepKey(owner, project, key).orElse(null);
            if (prior != null) {
                if (!request.equals(prior.input().path("videoCompositionRequest"))
                        || !origin.equals(prior.input().path("compositionOrigin")))
                    throw new ApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                            ApiMessage.of("api.media-function.conflict"), ApiMessage.of("api.media-function.conflict"), false);
                return ProjectEventService.Change.unchanged(prior);
            }
            ObjectNode composition = request.deepCopy();
            ObjectNode frozen = mapper.createObjectNode();
            frozen.put("mode", "GENERAL_REFERENCE").put("prompt", request.path("rationale").asText())
                    .put("renderedPrompt", request.path("rationale").asText());
            frozen.putArray("images"); frozen.putArray("mentions");
            ArrayNode videos = frozen.putArray("videos"), audios = frozen.putArray("audios");
            Set<UUID> seenVideos = new HashSet<>(), seenAudios = new HashSet<>(), seenAssets = new HashSet<>();
            long[] bytes = {0}; int duration = 0, totalFrames = 0;
            for (JsonNode value : composition.path("shots")) {
                ObjectNode shot = (ObjectNode) value;
                var metadata = source(owner, project, uuid(shot, "videoVersionId"), Artifact.Kind.VIDEO,
                        videos, seenVideos, seenAssets, bytes);
                int start = shot.path("sourceStartMs").asInt();
                int length = shot.has("durationMs") ? shot.path("durationMs").asInt() : metadata.durationMs() - start;
                if (length < 100 || start + (long) length > metadata.durationMs()) throw invalid();
                shot.put("durationMs", length);
                double speed = shot.path("speed").asDouble(1);
                int outputFrames = (int) Math.floor(length * 30.0 / (1000 * speed));
                int outputLength = outputFrames * 1000 / 30;
                if (shot.path("fadeMs").asInt() * 2 > outputLength) throw invalid();
                totalFrames += outputFrames;
                duration = totalFrames * 1000 / 30;
                if (duration > MAX_DURATION_MS) throw invalid();
                for (JsonNode line : shot.path("lines")) {
                    source(owner, project, uuid(line, "audioVersionId"), Artifact.Kind.AUDIO,
                            audios, seenAudios, seenAssets, bytes);
                    if (line.has("startMs") && line.path("startMs").asInt() >= outputLength) throw invalid();
                }
            }
            if (composition.has("music")) {
                JsonNode music = composition.path("music");
                var metadata = source(owner, project, uuid(music, "audioVersionId"), Artifact.Kind.AUDIO,
                        audios, seenAudios, seenAssets, bytes);
                if (music.path("sourceStartMs").asInt() >= metadata.durationMs()) throw invalid();
            }
            for (JsonNode subtitle : composition.path("subtitles"))
                if (subtitle.path("endMs").asInt() > duration) throw invalid();
            var binding = catalog.resolve(CAPABILITY_ID, Task.Kind.VIDEO_GENERATION, (duration + 999) / 1000);
            frozen.put("capabilityId", binding.capabilityId().toString()).put("capabilityVersion", binding.capabilityVersion());
            frozen.putObject("parameters").set("videoComposition", composition.deepCopy());
            var target = artifacts.createTaskMediaIdentityWithinChange(owner, project, Artifact.Kind.VIDEO, request.path("title").asText());
            var item = canvas.placeGeneratedArtifactWithinChange(owner, project, target.id(), null);
            ObjectNode input = mapper.createObjectNode().put("schemaVersion", 7)
                    .put("artifactId", target.id().toString()).put("canvasItemId", item.id().toString())
                    .put("canvasItemVersion", item.version()).putNull("parentVersionId")
                    .put("resultSelectionEpoch", canvas.mediaSelectionEpoch(owner, project, item.id()))
                    .put("resultDraftVersion", drafts.get(owner, project, item.id()).version())
                    .put("prompt", request.path("rationale").asText()).put("durationSeconds", (duration + 999) / 1000)
                    .put("sourceDurationMs", duration).put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
            input.set("videoCompositionRequest", request); input.set("videoComposition", composition);
            input.set("compositionOrigin", origin); input.set("mediaInput", frozen);
            if (run != null) input.put("compositionRunId", run.toString());
            if (operation != null) input.put("initiator", Task.Initiator.EXTERNAL_MCP.name())
                    .put("mcpOperationId", operation.toString()).put("mcpTokenId", token.toString());
            var now = clock.instant();
            Task task = new Task(UUID.randomUUID(), project, null, key, Task.Kind.VIDEO_GENERATION,
                    Task.Status.READY, false, input, Sha256.hex(input.toString()), null, null, 1,
                    now, null, null, 0, 0, null, now, now, null);
            tasks.create(task); tasks.bindMediaTask(task.id(), binding);
            tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), project, target.id(), null, target.version(), item.id()));
            usage.reserveMediaTask(owner, task, "LOCAL_NO_COST", binding.connectionVersion());
            var payload = mapper.createObjectNode().put("taskId", task.id().toString()).put("artifactId", target.id().toString())
                    .put("canvasItemId", item.id().toString()).put("status", task.status().name());
            events.append(owner, project, new ProjectEventService.EventDraft("task.status.changed", 1, task.id(), task.version(), payload));
            return ProjectEventService.Change.unchanged(task);
        }).value();
    }

    private dev.agenvas.asset.domain.Asset source(UUID owner, UUID project, UUID versionId, Artifact.Kind kind,
            ArrayNode refs, Set<UUID> seen, Set<UUID> seenAssets, long[] bytes) {
        var version = artifacts.requireMediaVersionForTask(owner, project, versionId, kind);
        if (artifacts.get(owner, project, version.artifactId()).artifact().archivedAt() != null) throw invalid();
        var asset = assets.metadata(owner, project, UUID.fromString(version.content().path("assetId").asText()));
        if (asset.durationMs() == null || asset.durationMs() < 1 || asset.durationMs() > 600_000) throw invalid();
        if (seenAssets.add(asset.id())) bytes[0] += asset.byteSize();
        if (bytes[0] > 1024L * 1024 * 1024) throw invalid();
        if (seen.add(versionId)) refs.addObject().put("versionId", versionId.toString()).put("artifactId", version.artifactId().toString())
                .put("role", kind == Artifact.Kind.VIDEO ? "VIDEO_REFERENCE" : "AUDIO_REFERENCE").put("order", refs.size() - 1);
        return asset;
    }

    /** Validate strictly at every entry point, including tools that do not go through Bean Validation. */
    private ObjectNode normalize(JsonNode raw) {
        fields(raw, Set.of("title", "rationale", "originalAudioMode", "shots", "output", "music", "subtitles"));
        String title = string(raw, "title", 160, true), rationale = string(raw, "rationale", 2000, false);
        String mode = raw.has("originalAudioMode") ? string(raw, "originalAudioMode", 20, true) : "MUTE";
        if (!Set.of("MUTE", "BACKGROUND", "KEEP").contains(mode)) throw invalid();
        JsonNode shots = raw.path("shots");
        if (!shots.isArray() || shots.isEmpty() || shots.size() > 12) throw invalid();
        ObjectNode request = mapper.createObjectNode().put("title", title).put("rationale", rationale).put("originalAudioMode", mode);
        ArrayNode output = request.putArray("shots"); int lines = 0;
        for (JsonNode shot : shots) {
            fields(shot, Set.of("videoVersionId", "sourceStartMs", "durationMs", "lines", "speed", "fadeMs"));
            ObjectNode target = output.addObject().put("videoVersionId", uuid(shot, "videoVersionId").toString())
                    .put("sourceStartMs", shot.has("sourceStartMs") ? integer(shot, "sourceStartMs", 0, 599_999) : 0);
            if (shot.has("durationMs")) target.put("durationMs", integer(shot, "durationMs", 100, 600_000));
            double speed = shot.has("speed") ? number(shot, "speed", 0.5, 2) : 1;
            target.put("speed", speed).put("fadeMs", shot.has("fadeMs") ? integer(shot, "fadeMs", 0, 500) : 0);
            if (!shot.path("lines").isArray() || (lines += shot.path("lines").size()) > 30) throw invalid();
            ArrayNode targetLines = target.putArray("lines");
            for (JsonNode line : shot.path("lines")) {
                fields(line, Set.of("audioVersionId", "startMs"));
                ObjectNode item = targetLines.addObject().put("audioVersionId", uuid(line, "audioVersionId").toString());
                if (line.has("startMs")) item.put("startMs", integer(line, "startMs", 0, MAX_DURATION_MS - 1));
            }
        }
        if (raw.has("output")) {
            JsonNode config = raw.path("output");
            fields(config, Set.of("resolution", "aspectRatio"));
            String resolution = config.has("resolution") ? string(config, "resolution", 5, true) : "720P";
            String aspect = config.has("aspectRatio") ? string(config, "aspectRatio", 5, true) : "16:9";
            if (!Set.of("720P", "1080P").contains(resolution) || !Set.of("16:9", "9:16", "1:1").contains(aspect)) throw invalid();
            request.putObject("output").put("resolution", resolution).put("aspectRatio", aspect);
        }
        if (raw.has("music")) {
            JsonNode music = raw.path("music");
            fields(music, Set.of("audioVersionId", "sourceStartMs", "volume"));
            request.putObject("music").put("audioVersionId", uuid(music, "audioVersionId").toString())
                    .put("sourceStartMs", music.has("sourceStartMs") ? integer(music, "sourceStartMs", 0, 599_999) : 0)
                    .put("volume", music.has("volume") ? number(music, "volume", 0, 1) : 0.2);
        }
        if (raw.has("subtitles")) {
            if (!raw.path("subtitles").isArray() || raw.path("subtitles").size() > 100) throw invalid();
            ArrayNode captions = request.putArray("subtitles");
            for (JsonNode subtitle : raw.path("subtitles")) {
                fields(subtitle, Set.of("text", "startMs", "endMs"));
                int start = integer(subtitle, "startMs", 0, MAX_DURATION_MS - 1);
                int end = integer(subtitle, "endMs", 1, MAX_DURATION_MS);
                if (end <= start) throw invalid();
                String text = string(subtitle, "text", 500, true);
                if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n')) throw invalid();
                captions.addObject().put("text", text).put("startMs", start).put("endMs", end);
            }
        }
        return request;
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject() || !allowed.containsAll(node.propertyNames())) throw invalid();
    }
    private static String string(JsonNode node, String field, int max, boolean required) {
        if (!required && !node.has(field)) return "";
        if (!node.path(field).isString()) throw invalid();
        String value = node.path(field).asText().trim();
        if (value.length() > max || required && value.isBlank()) throw invalid();
        return value;
    }
    private static UUID uuid(JsonNode node, String field) {
        String value = string(node, field, 36, true);
        try { UUID id = UUID.fromString(value); if (!id.toString().equalsIgnoreCase(value)) throw invalid(); return id; }
        catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    private static double number(JsonNode node, String field, double min, double max) {
        JsonNode value = node.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < min || value.asDouble() > max) throw invalid();
        return value.asDouble();
    }
    private static int integer(JsonNode node, String field, int min, int max) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < min || value.asInt() > max) throw invalid();
        return value.asInt();
    }
    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VIDEO_COMPOSITION_INVALID",
                ApiMessage.of("api.video-composition.invalid"), ApiMessage.of("api.video-composition.invalid"), false);
    }
}
