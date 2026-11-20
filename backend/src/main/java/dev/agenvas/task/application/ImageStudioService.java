package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.storage.MediaRelayService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Accepts independent image batches and projects their private archives without creating canvas state. */
@Service
public class ImageStudioService {
    private static final Set<String> SUPPORTED_ADAPTERS = Set.of("MOCK_IMAGE",
            MediaAdapterRegistry.OPENAI_GPT_IMAGE_2, MediaAdapterRegistry.GOOGLE_NANO_BANANA_2);
    private static final int MAX_PROMPT_LENGTH = 8_000;
    private static final int MAX_HISTORY_PAGE = 100;
    private static final int MEDIA_TASK_INPUT_SCHEMA_VERSION = 7;
    private static final int STUDIO_REQUEST_SCHEMA_VERSION = 1;
    private final ImageStudioRepository studio;
    private final TaskRepository tasks;
    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final MediaCapabilityService capabilities;
    private final ProjectEventService events;
    private final UsageService usage;
    private final MediaRelayService relay;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ImageStudioService(ImageStudioRepository studio, TaskRepository tasks,
            ProjectService projects, ArtifactService artifacts, AssetService assets,
            MediaCapabilityService capabilities, ProjectEventService events, UsageService usage,
            MediaRelayService relay, ObjectMapper mapper, Clock clock) {
        this.studio = studio;
        this.tasks = tasks;
        this.projects = projects;
        this.artifacts = artifacts;
        this.assets = assets;
        this.capabilities = capabilities;
        this.events = events;
        this.usage = usage;
        this.relay = relay;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record Capability(UUID capabilityId, int capabilityVersion, String name, String connectionName,
            String adapterId, JsonNode defaultParameters, List<String> aspectRatios, List<String> resolutions,
            List<String> qualities, int maxReferenceImages, boolean supportsTransparentBackground,
            String complexity) {
    }
    public record Context(UUID projectId, UUID defaultCapabilityId, List<Capability> capabilities) {
    }
    public record Reference(UUID artifactId, UUID versionId, UUID assetId, UUID projectId,
            Integer width, Integer height) {
    }
    public record Result(UUID artifactId, UUID artifactVersionId, UUID assetId, Integer width, Integer height,
            String contentType) {
    }
    public record TaskView(UUID id, Task.Status status, long version, String errorCode, List<Result> results) {
    }
    public record BatchView(UUID id, UUID projectId, UUID capabilityId, int capabilityVersion, String prompt,
            JsonNode parameters, List<UUID> referenceVersionIds, List<Reference> references,
            Instant createdAt, List<TaskView> tasks) {
    }
    public record BatchPage(List<BatchView> items, String nextCursor) {
    }

    public Context context(UUID owner) {
        UUID project = projects.ensureImageStudioScope(owner).id();
        List<Capability> catalog = capabilities.candidates(Task.Kind.IMAGE_GENERATION, 0).stream()
                .filter(candidate -> supported(candidate.binding(), candidate.settings()))
                .map(candidate -> {
                    var binding = candidate.binding();
                    var policy = capabilities.inputPolicy(binding);
                    var defaults = studioParameters(binding, mapper.createObjectNode());
                    return new Capability(binding.capabilityId(), binding.capabilityVersion(),
                            candidate.capabilityName(), candidate.connectionName(), binding.adapterId(),
                            defaults.toJson(mapper), ordered(policy.supportedImageAspectRatios().stream()
                                    .filter(value -> !"AUTO".equals(value)).toList()),
                            ordered(policy.supportedImageResolutions()), ordered(policy.supportedImageQualities()),
                            policy.maxReferenceImages(), policy.supportsTransparentBackground(),
                            candidate.settings().path("complexity").asText(null));
                }).toList();
        UUID configuredDefault = capabilities.defaultCapabilityId(Task.Kind.IMAGE_GENERATION);
        UUID selected = catalog.stream().map(Capability::capabilityId).filter(id -> id.equals(configuredDefault))
                .findFirst().orElse(catalog.isEmpty() ? null : catalog.getFirst().capabilityId());
        return new Context(project, selected, catalog);
    }

    /** Byte decoding/storage finishes before the short immutable Artifact transaction. */
    public Reference uploadReference(UUID owner, InputStream input) {
        UUID project = projects.ensureImageStudioScope(owner).id();
        Asset asset = assets.archiveImage(owner, project, input);
        ImageGenerationPreparation.requireStaticImage(asset);
        var artifact = artifacts.create(owner, project, Artifact.Kind.IMAGE, "Studio reference",
                mapper.createObjectNode().put("sourceType", ArtifactVersion.MediaSourceType.UPLOAD.name())
                        .put("assetId", asset.id().toString()));
        return new Reference(artifact.artifact().id(), artifact.resourceDefaultVersion().id(), asset.id(), project,
                asset.width(), asset.height());
    }

    @Transactional
    public BatchView accept(UUID owner, UUID capabilityId, int expectedCapabilityVersion, String prompt,
            JsonNode parameters, List<UUID> referenceVersionIds, String commandKey) {
        String key = commandKey == null ? "" : commandKey.trim();
        if (key.isEmpty() || key.length() > 160 || capabilityId == null || expectedCapabilityVersion < 1
                || prompt == null || prompt.isBlank() || prompt.length() > MAX_PROMPT_LENGTH
                || parameters == null || !parameters.isObject() || referenceVersionIds == null
                || referenceVersionIds.size() > 14 || referenceVersionIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(referenceVersionIds).size() != referenceVersionIds.size())
            throw invalid();
        UUID project = projects.ensureImageStudioScope(owner).id();
        ObjectNode request = mapper.createObjectNode().put("capabilityId", capabilityId.toString())
                .put("capabilityVersion", expectedCapabilityVersion).put("prompt", prompt.strip());
        request.set("parameters", canonical(parameters));
        request.set("referenceVersionIds", mapper.valueToTree(referenceVersionIds));
        String hash = Sha256.hex(request.toString());
        return events.recordChange(owner, project, () -> {
            var prior = studio.findByCommand(owner, key).orElse(null);
            if (prior != null) {
                if (!prior.requestHash().equals(hash))
                    throw conflict("IDEMPOTENCY_CONFLICT");
                return ProjectEventService.Change.unchanged(view(owner, prior));
            }
            MediaCapabilityBinding binding = capabilities.resolve(capabilityId, Task.Kind.IMAGE_GENERATION, 0);
            JsonNode settings = capabilities.settings(binding);
            if (binding.capabilityVersion() != expectedCapabilityVersion)
                throw conflict("MEDIA_CAPABILITY_CHANGED");
            if (!supported(binding, settings))
                throw invalid();
            ImageGenerationParameters controls = studioParameters(binding, parameters);
            List<Reference> references = references(owner, project, referenceVersionIds, binding);
            ObjectNode frozenRequest = request.deepCopy();
            frozenRequest.put("schemaVersion", STUDIO_REQUEST_SCHEMA_VERSION);
            frozenRequest.set("parameters", controls.toJson(mapper));
            // PostgreSQL stores timestamptz at microsecond precision; the first
            // response and every idempotent replay must expose the same timestamp.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var batch = new ImageStudioRepository.Batch(UUID.randomUUID(), owner, project, key, hash,
                    frozenRequest, now);
            studio.create(batch);
            // This white list contains single-request standard adapters only. Provider-native
            // multi-output results remain outputs of the original task, never a second fan-out.
            for (int index = 0; index < controls.generationCount(); index++) {
                var target = artifacts.createTaskMediaIdentityWithinChange(owner, project, Artifact.Kind.IMAGE,
                        "Studio image " + (index + 1));
                ObjectNode input = frozenTaskInput(binding, batch, target.id(), controls, references, index, settings);
                Task task = new Task(UUID.randomUUID(), project, null, "image-studio:" + batch.id() + ":" + index,
                        Task.Kind.IMAGE_GENERATION, Task.Status.READY, false, input, Sha256.hex(input.toString()),
                        null, null, 1, now, null, null, 0, 0, null, now, now, null);
                tasks.create(task);
                tasks.bindMediaTask(task.id(), binding);
                tasks.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), project, target.id(),
                        null, target.version(), null));
                studio.addTask(project, batch.id(), index, task.id());
                usage.reserveMediaTask(owner, task, "PROVIDER_UNPRICED", binding.connectionVersion());
                events.append(owner, project, new ProjectEventService.EventDraft("task.status.changed", 1,
                        task.id(), task.version(), mapper.createObjectNode().put("taskId", task.id().toString())
                                .put("status", task.status().name())));
            }
            return ProjectEventService.Change.unchanged(view(owner, batch));
        }).value();
    }

    @Transactional(readOnly = true)
    public BatchView get(UUID owner, UUID batchId) {
        return view(owner, studio.find(owner, batchId).orElseThrow(this::notFound));
    }

    @Transactional(readOnly = true)
    public BatchPage list(UUID owner, String cursor, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > MAX_HISTORY_PAGE)
            throw invalid();
        ImageStudioRepository.Batch before = null;
        if (cursor != null) {
            UUID id;
            try {
                id = UUID.fromString(cursor);
            } catch (IllegalArgumentException malformed) {
                throw invalid();
            }
            before = studio.find(owner, id).orElseThrow(this::notFound);
        }
        List<ImageStudioRepository.Batch> rows = studio.list(owner, before, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<ImageStudioRepository.Batch> page = hasMore ? rows.subList(0, limit) : rows;
        return new BatchPage(page.stream().map(row -> view(owner, row)).toList(),
                hasMore ? page.getLast().id().toString() : null);
    }

    private BatchView view(UUID owner, ImageStudioRepository.Batch batch) {
        projects.get(owner, batch.projectId());
        JsonNode request = batch.request();
        List<TaskView> taskViews = studio.taskIds(batch.id()).stream().map(id -> {
            Task task = tasks.find(owner, batch.projectId(), id).orElseThrow(this::notFound);
            var outputs = new ArrayList<Result>();
            var seen = new HashSet<UUID>();
            var archives = new ArrayList<JsonNode>();
            if (task.output() != null)
                archives.add(task.output());
            archives.addAll(tasks.lateResults(owner, batch.projectId(), task.id()));
            for (JsonNode archive : archives) {
                var identities = ArchivedTaskResults.read(archive);
                var results = new ArrayList<ArchivedTaskResults.Identity>();
                if (identities.primary() != null)
                    results.add(identities.primary());
                results.addAll(identities.additional());
                for (var identity : results) {
                    if (identity.artifactId() == null || identity.artifactVersionId() == null)
                        continue;
                    UUID versionId = UUID.fromString(identity.artifactVersionId());
                    if (!seen.add(versionId))
                        continue;
                    var version = artifacts.requireImageVersionForTask(owner, batch.projectId(), versionId);
                    if (!version.artifactId().toString().equals(identity.artifactId()))
                        throw new IllegalStateException("Studio archive identity mismatch");
                    Asset asset = assets.metadata(owner, batch.projectId(),
                            UUID.fromString(version.content().path("assetId").asText()));
                    outputs.add(new Result(version.artifactId(), versionId, asset.id(), asset.width(), asset.height(),
                            asset.contentType()));
                }
            }
            return new TaskView(task.id(), task.status(), task.version(), task.errorCode(), List.copyOf(outputs));
        }).toList();
        List<UUID> referenceVersionIds = request.path("referenceVersionIds").valueStream()
                .map(value -> UUID.fromString(value.asText())).toList();
        return new BatchView(batch.id(), batch.projectId(), UUID.fromString(request.path("capabilityId").asText()),
                request.path("capabilityVersion").asInt(), request.path("prompt").asText(),
                request.path("parameters"), referenceVersionIds, referenceVersionIds.stream()
                        .map(version -> reference(owner, batch.projectId(), version)).toList(),
                batch.createdAt(), taskViews);
    }

    private ObjectNode frozenTaskInput(MediaCapabilityBinding binding, ImageStudioRepository.Batch batch,
            UUID artifact, ImageGenerationParameters controls, List<Reference> references, int index,
            JsonNode settings) {
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", MEDIA_TASK_INPUT_SCHEMA_VERSION)
                .put(MediaExecutionSurface.PROPERTY, MediaExecutionSurface.IMAGE_STUDIO.name())
                .put("studioBatchId", batch.id().toString()).put("initiator", Task.Initiator.USER_DIRECT.name())
                .put("artifactId", artifact.toString()).put("generationIndex", index)
                .put("generationCount", controls.generationCount())
                .put("prompt", batch.request().path("prompt").asText())
                .put("workflowVersion", binding.adapterId() + ":" + binding.mappingSha256());
        JsonNode price = MediaCapabilityConfiguration.price(settings, null);
        if (price != null)
            input.set("mediaPricing", price.deepCopy());
        if (settings.has("tokenPricing"))
            input.set("mediaTokenPricing", settings.path("tokenPricing").deepCopy());
        ObjectNode media = input.putObject("mediaInput").put("mode", "GENERAL_REFERENCE")
                .put("prompt", batch.request().path("prompt").asText())
                .put("renderedPrompt", batch.request().path("prompt").asText())
                .put("userRenderedPrompt", batch.request().path("prompt").asText())
                .put("capabilityId", binding.capabilityId().toString())
                .put("capabilityVersion", binding.capabilityVersion());
        media.set("parameters", controls.toJson(mapper));
        media.putArray("audios");
        media.putArray("videos");
        var images = media.putArray("images");
        for (Reference reference : references)
            images.addObject().put("artifactId", reference.artifactId().toString())
                    .put("versionId", reference.versionId().toString()).put("assetId", reference.assetId().toString())
                    .put("role", "REFERENCE").put("order", images.size() - 1);
        if (MediaAdapterRegistry.OPENAI_GPT_IMAGE_2.equals(binding.adapterId()) && !references.isEmpty()) {
            UUID profile = relay.pinImageProfile();
            if (profile == null)
                input.putNull("imageRelayProfileId");
            else
                input.put("imageRelayProfileId", profile.toString());
        }
        return input;
    }

    private ImageGenerationParameters studioParameters(MediaCapabilityBinding binding, JsonNode values) {
        ObjectNode merged = (ObjectNode) capabilities.parameters(binding, values).deepCopy();
        if (!merged.has("aspectRatio") || "AUTO".equals(merged.path("aspectRatio").asText()))
            merged.put("aspectRatio", "1:1");
        return ImageGenerationPreparation.parameters(merged, capabilities.inputPolicy(binding));
    }

    private List<Reference> references(UUID owner, UUID project, List<UUID> versionIds,
            MediaCapabilityBinding binding) {
        if (versionIds.size() > capabilities.inputPolicy(binding).maxReferenceImages())
            throw invalid();
        List<Reference> result = new ArrayList<>();
        for (UUID versionId : versionIds) {
            ArtifactVersion version = artifacts.requireImageVersionForTask(owner, project, versionId);
            if (artifacts.get(owner, project, version.artifactId()).artifact().archivedAt() != null)
                throw invalid();
            Asset asset = assets.requireReadyMedia(owner, project,
                    UUID.fromString(version.content().path("assetId").asText()), Asset.MediaKind.IMAGE);
            ImageGenerationPreparation.requireStaticImage(asset);
            result.add(new Reference(version.artifactId(), version.id(), asset.id(), project, asset.width(),
                    asset.height()));
        }
        return List.copyOf(result);
    }

    /** History always resolves the accepted version; later resource-default choices cannot replace a reference. */
    private Reference reference(UUID owner, UUID project, UUID versionId) {
        ArtifactVersion version = artifacts.requireImageVersionForTask(owner, project, versionId);
        Asset asset = assets.metadata(owner, project, UUID.fromString(version.content().path("assetId").asText()));
        return new Reference(version.artifactId(), version.id(), asset.id(), project, asset.width(), asset.height());
    }

    private boolean supported(MediaCapabilityBinding binding, JsonNode settings) {
        return SUPPORTED_ADAPTERS.contains(binding.adapterId())
                && !"gif".equals(settings.path("outputFormat").asText());
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = mapper.createObjectNode();
            value.propertyNames().stream().sorted().forEach(key -> object.set(key, canonical(value.path(key))));
            return object;
        }
        if (value.isArray()) {
            var array = mapper.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }

    private static List<String> ordered(java.util.Collection<String> values) {
        return values.stream().sorted().toList();
    }

    private ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.image-studio.title"), ApiMessage.of("api.image-studio.invalid-request"), false);
    }
    private ApiProblemException conflict(String code) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, ApiMessage.of("api.image-studio.title"),
                ApiMessage.of("api.image-studio.conflict"), false);
    }
    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                ApiMessage.of("api.image-studio.title"),
                ApiMessage.of("api.image-studio.not-found"), false);
    }
}
