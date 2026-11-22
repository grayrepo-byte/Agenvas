package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft.VideoInputMode;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.domain.MediaCapabilityConfiguration;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.MediaExecutionSurface;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class VideoStudioService {
    private static final int REQUEST_SCHEMA_VERSION = 1;
    // Both the current video Worker and the offline renderer consume this whole-seconds format.
    private static final int TASK_INPUT_SCHEMA_VERSION = 7;
    private static final Task.Kind KIND = Task.Kind.VIDEO_GENERATION;
    private final VideoStudioRepository studio;
    private final VideoStudioInputs inputs;
    private final TaskRepository tasks;
    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectEventService events;
    private final UsageService usage;
    private final ObjectMapper mapper;
    private final Clock clock;

    VideoStudioService(VideoStudioRepository studio, VideoStudioInputs inputs, TaskRepository tasks,
            ProjectService projects, ArtifactService artifacts, AssetService assets, ProjectEventService events,
            UsageService usage, ObjectMapper mapper, Clock clock) {
        this.studio = studio;
        this.inputs = inputs;
        this.tasks = tasks;
        this.projects = projects;
        this.artifacts = artifacts;
        this.assets = assets;
        this.events = events;
        this.usage = usage;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record Capability(UUID capabilityId, int capabilityVersion, String name, String connectionName,
            String adapterId, List<VideoInputMode> modes, VideoInputMode defaultMode, boolean supportsEndFrame,
            int minDurationSeconds, int maxDurationSeconds, int defaultDurationSeconds, List<String> aspectRatios,
            String defaultAspectRatio) {}

    public record Context(UUID projectId, UUID defaultCapabilityId, List<Capability> capabilities) {}

    public record Command(UUID capabilityId, int capabilityVersion, String prompt, VideoInputMode mode,
            int durationSeconds, String aspectRatio, UUID startFrameVersionId, UUID endFrameVersionId) {}

    public record Frame(UUID artifactId, UUID versionId, UUID assetId, UUID projectId, Integer width, Integer height) {}

    public record Result(UUID artifactId, UUID artifactVersionId, UUID assetId, Integer width, Integer height,
            Integer durationMs, String contentType) {}

    public record TaskView(UUID id, Task.Status status, long version, String errorCode, List<Result> results) {}

    public record GenerationView(UUID id, UUID projectId, UUID capabilityId, int capabilityVersion, String prompt,
            VideoInputMode mode, int durationSeconds, String aspectRatio, Frame startFrame, Frame endFrame,
            Instant createdAt, TaskView task) {}

    public record GenerationPage(List<GenerationView> items, String nextCursor) {}

    public Context context(UUID owner) {
        return inputs.context(owner);
    }

    public Frame upload(UUID owner, InputStream input) {
        return inputs.upload(owner, input);
    }

    @Transactional
    public GenerationView accept(UUID owner, Command command, String key) {
        if (key == null || key.isBlank() || key.length() > 160 || command.capabilityId() == null
                || command.capabilityVersion() < 1 || command.prompt() == null || command.prompt().isBlank()
                || command.prompt().length() > 8000 || command.mode() == null || command.durationSeconds() < 1
                || command.durationSeconds() > 30)
            throw VideoStudioInputs.invalid();
        String commandKey = key.strip();
        ObjectNode request = mapper.valueToTree(command);
        request.put("prompt", command.prompt().strip());
        String hash = Sha256.hex(request.toString());
        UUID project = projects.ensureVideoStudioScope(owner).id();
        return events.recordChange(owner, project, () -> {
            var prior = studio.findByCommand(owner, commandKey).orElse(null);
            if (prior != null) {
                if (!prior.requestHash().equals(hash))
                    throw conflict("IDEMPOTENCY_CONFLICT");
                return ProjectEventService.Change.unchanged(view(owner, prior));
            }
            var prepared = inputs.prepare(owner, project, command);
            var target = artifacts.createTaskMediaIdentityWithinChange(owner, project, Artifact.Kind.VIDEO,
                    "Studio video");
            UUID generationId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            Instant acceptedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            ObjectNode frozen = frozenInput(command, request.path("prompt").asText(), prepared, generationId, target.id());
            Task task = new Task(taskId, project, null, "video-studio:" + generationId, KIND, Task.Status.READY, false,
                    frozen, Sha256.hex(frozen.toString()), null, null, 1, acceptedAt, null, null, 0, 0, null,
                    acceptedAt, acceptedAt, null);
            tasks.create(task);
            tasks.bindMediaTask(task.id(), prepared.binding());
            tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), project, target.id(), null,
                    target.version(), null));
            request.put("schemaVersion", REQUEST_SCHEMA_VERSION).put("aspectRatio", prepared.ratio());
            // Keep the original frame descriptors for history; current file availability is a separate read.
            request.set("startFrame", mapper.valueToTree(prepared.start()));
            request.set("endFrame", mapper.valueToTree(prepared.end()));
            var row = new VideoStudioRepository.Generation(generationId, owner, project, taskId, commandKey, hash,
                    request, acceptedAt);
            studio.create(row);
            usage.reserveMediaTask(owner, task, "PROVIDER_UNPRICED", prepared.binding().connectionVersion());
            // Creating the target also records an event, so append using the current sequence under the same lock.
            events.append(owner, project, new ProjectEventService.EventDraft("task.status.changed", 1, task.id(),
                    task.version(), mapper.createObjectNode().put("taskId", task.id().toString())
                            .put("status", task.status().name())));
            return ProjectEventService.Change.unchanged(view(owner, row));
        }).value();
    }

    private ObjectNode frozenInput(Command command, String prompt, VideoStudioInputs.Prepared prepared,
            UUID generationId, UUID artifactId) {
        var binding = prepared.binding();
        var input = mapper.createObjectNode().put("schemaVersion", TASK_INPUT_SCHEMA_VERSION)
                .put(MediaExecutionSurface.PROPERTY, MediaExecutionSurface.VIDEO_STUDIO.name())
                .put("studioGenerationId", generationId.toString()).put("artifactId", artifactId.toString())
                .put("initiator", Task.Initiator.USER_DIRECT.name()).put("prompt", prompt)
                .put("durationSeconds", command.durationSeconds()).put("generationIndex", 0).put("generationCount", 1)
                .put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
        JsonNode price = MediaCapabilityConfiguration.price(prepared.settings(), null);
        if (price != null)
            input.set("mediaPricing", price.deepCopy());
        if (prepared.settings().has("tokenPricing"))
            input.set("mediaTokenPricing", prepared.settings().path("tokenPricing").deepCopy());
        ObjectNode media = input.putObject("mediaInput").put("mode", command.mode().name()).put("prompt", prompt)
                .put("renderedPrompt", prompt).put("userRenderedPrompt", prompt)
                .put("durationSeconds", command.durationSeconds()).put("capabilityId", binding.capabilityId().toString())
                .put("capabilityVersion", binding.capabilityVersion());
        media.putObject("parameters").put("aspectRatio", prepared.ratio());
        media.putObject("providerParameters").put("aspectRatio", prepared.ratio());
        prepared.writeFrames(media.putArray("images"));
        media.putArray("audios");
        media.putArray("videos");
        return input;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GenerationView get(UUID owner, UUID id) {
        return view(owner, studio.find(owner, id).orElseThrow(VideoStudioService::notFound));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GenerationPage list(UUID owner, String cursor, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 100)
            throw VideoStudioInputs.invalid();
        VideoStudioRepository.Generation before = null;
        if (cursor != null) {
            UUID id;
            try {
                id = UUID.fromString(cursor);
            } catch (IllegalArgumentException malformed) {
                throw VideoStudioInputs.invalid();
            }
            before = studio.find(owner, id).orElseThrow(VideoStudioInputs::invalid);
        }
        var rows = studio.list(owner, before, limit + 1);
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        return new GenerationPage(page.stream().map(row -> view(owner, row)).toList(),
                more ? page.getLast().id().toString() : null);
    }

    private GenerationView view(UUID owner, VideoStudioRepository.Generation row) {
        JsonNode request = row.request();
        Task task = tasks.find(owner, row.projectId(), row.taskId()).orElseThrow(VideoStudioService::notFound);
        List<Result> results = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        addResults(owner, row.projectId(), task.output(), seen, results);
        for (JsonNode late : tasks.lateResults(owner, row.projectId(), task.id()))
            addResults(owner, row.projectId(), late, seen, results);
        return new GenerationView(row.id(), row.projectId(), UUID.fromString(request.path("capabilityId").asText()),
                request.path("capabilityVersion").asInt(), request.path("prompt").asText(),
                VideoInputMode.valueOf(request.path("mode").asText()), request.path("durationSeconds").asInt(),
                request.path("aspectRatio").asText(), mapper.treeToValue(request.path("startFrame"), Frame.class),
                mapper.treeToValue(request.path("endFrame"), Frame.class), row.createdAt(),
                new TaskView(task.id(), task.status(), task.version(), task.errorCode(), List.copyOf(results)));
    }

    private void addResults(UUID owner, UUID project, JsonNode output, Set<UUID> seen, List<Result> results) {
        if (output == null)
            return;
        var identities = ArchivedTaskResults.read(output);
        addResult(owner, project, identities.primary(), seen, results);
        for (var item : identities.additional())
            addResult(owner, project, item, seen, results);
    }

    private void addResult(UUID owner, UUID project, ArchivedTaskResults.Identity item, Set<UUID> seen,
            List<Result> results) {
        if (item == null || item.artifactId() == null || item.artifactVersionId() == null)
            return;
        UUID id = UUID.fromString(item.artifactVersionId());
        if (!seen.add(id))
            return;
        var version = artifacts.requireMediaVersionForTask(owner, project, id, Artifact.Kind.VIDEO);
        if (!item.artifactId().equals(version.artifactId().toString()))
            throw new IllegalStateException("Mismatched video Studio archive identity");
        Asset asset = assets.requireReadyMedia(owner, project, UUID.fromString(version.content().path("assetId").asText()),
                Asset.MediaKind.VIDEO);
        results.add(new Result(version.artifactId(), id, asset.id(), asset.width(), asset.height(), asset.durationMs(),
                asset.contentType()));
    }

    static ApiProblemException conflict(String code) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, ApiMessage.of("api.video-studio.title"),
                ApiMessage.of("api.video-studio.conflict"), false);
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ApiMessage.of("api.video-studio.title"),
                ApiMessage.of("api.video-studio.not-found"), false);
    }
}
