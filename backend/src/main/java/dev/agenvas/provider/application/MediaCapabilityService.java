package dev.agenvas.provider.application;

import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaCapabilityConfiguration;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.provider.infrastructure.ComfyUiEndpoint;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.OpenAiImage2Client;
import dev.agenvas.provider.infrastructure.GoogleNanoBananaClient;
import dev.agenvas.provider.infrastructure.SeedAudioClient;
import dev.agenvas.provider.infrastructure.ArkSeedanceClient;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Capability;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Connection;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.ConnectionVersion;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Owns the media catalog; the returned binding freezes both published versions. */
@Service
public class MediaCapabilityService {
    private static final int CAPABILITY_SCHEMA_VERSION = 1;
    private static final String MODEL_NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}";
    private static final String COMFY_MODEL_FILE_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,159}";

    private final JooqMediaCapabilityRepository repository;
    private final MediaAdapterRegistry registry;
    private final CredentialCipher cipher;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final ProviderModeProperties mode;

    public MediaCapabilityService(JooqMediaCapabilityRepository repository,
            MediaAdapterRegistry registry, CredentialCipher cipher, Clock clock,
            ObjectMapper mapper, ProviderModeProperties mode) {
        this.repository = repository;
        this.registry = registry;
        this.cipher = cipher;
        this.clock = clock;
        this.mapper = mapper;
        this.mode = mode;
    }

    @Transactional
    public Connection createConnection(String name, String origin) {
        String normalizedName = requireName(name);
        requireAvailablePlatform(origin == null ? MediaPlatform.MOCK : MediaPlatform.COMFYUI);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String endpoint = origin == null ? null : validatedOrigin(MediaPlatform.COMFYUI, origin);
        // Credential-free root URLs retain the environment-bootstrap path without requiring a key.
        var encrypted = endpoint == null || java.net.URI.create(endpoint).getRawPath().isEmpty()
                ? null : cipher.encryptMedia(id, 1, endpoint);
        repository.insertConnection(id, normalizedName,
                endpoint == null ? MediaPlatform.MOCK : MediaPlatform.COMFYUI,
                endpoint == null ? null : ComfyUiEndpoint.display(endpoint),
                endpoint == null ? null : Sha256.hex(endpoint),
                encrypted == null ? null : encrypted.ciphertext(),
                encrypted == null ? null : encrypted.nonce(),
                encrypted == null ? null : encrypted.keyVersion(), null, now);
        return repository.connection(id).orElseThrow();
    }

    /** Idempotent admin creation; credentials are encrypted before any version row is written. */
    @Transactional
    public Connection createConnection(String idempotencyKey, String name, String platform,
            String origin, String apiKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 160) {
            throw invalid(ApiMessage.of("api.media-capability-service.a-valid-idempotency-key-must-be-provided"));
        }
        String normalizedName = requireName(name);
        MediaPlatform normalizedPlatform = requirePlatform(platform);
        requireAvailablePlatform(normalizedPlatform);
        if (normalizedPlatform == MediaPlatform.LOCAL) {
            throw invalid(ApiMessage.of("api.media-capability-service.local-image-processing-is-a-built-in-capability-of-the"));
        }
        String normalizedOrigin = validatedOrigin(normalizedPlatform, origin);
        validateCredential(normalizedPlatform, apiKey, true);
        String hash = Sha256.hex(normalizedName + "\u0000" + normalizedPlatform.name() + "\u0000"
                + normalizedOrigin + "\u0000" + apiKey);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        if (!repository.claimCreateKey(idempotencyKey, hash, id, now)) {
            var previous = repository.createKey(idempotencyKey).orElseThrow();
            if (!previous.payloadSha256().equals(hash)) {
                throw conflict(ApiMessage.of("api.media-capability-service.the-same-idempotency-key-corresponds-to-different-configuration-content"));
            }
            return getConnection(previous.entityId());
        }
        String credential = normalizedPlatform == MediaPlatform.COMFYUI ? normalizedOrigin : apiKey;
        CredentialCipher.Encrypted encrypted = credential == null || credential.isBlank()
                ? null : cipher.encryptMedia(id, 1, credential);
        repository.insertConnection(id, normalizedName, normalizedPlatform,
                displayOrigin(normalizedPlatform, normalizedOrigin),
                normalizedOrigin == null ? null : Sha256.hex(normalizedOrigin),
                encrypted == null ? null : encrypted.ciphertext(),
                encrypted == null ? null : encrypted.nonce(),
                encrypted == null ? null : encrypted.keyVersion(),
                keyMask(apiKey), now);
        return getConnection(id);
    }

    @Transactional
    public Connection updateConnection(UUID id, long expectedVersion, String name,
            boolean enabled, String origin, String apiKey) {
        Connection current = getConnection(id);
        requireAvailablePlatform(current.platform());
        rejectSystemManaged(current);
        if (current.version() != expectedVersion) {
            throw conflict(ApiMessage.of("api.media-capability-service.the-connection-has-been-modified-by-other-operations"));
        }
        ConnectionVersion previous = getConnectionVersion(id, current.currentVersion())
                .orElseThrow();
        // Saving the UI's redacted path unchanged preserves the encrypted endpoint.
        String normalizedOrigin = current.platform() == MediaPlatform.COMFYUI
                && java.util.Objects.equals(origin, previous.origin())
                ? comfyEndpoint(previous) : validatedOrigin(current.platform(), origin);
        validateCredential(current.platform(), apiKey, false);
        String originHash = normalizedOrigin == null ? null : Sha256.hex(normalizedOrigin);
        boolean newVersion = !java.util.Objects.equals(previous.originSha256(), originHash)
                || apiKey != null && !apiKey.isBlank();
        int nextVersion = current.currentVersion() + (newVersion ? 1 : 0);
        Instant now = clock.instant();
        if (!repository.updateConnection(id, expectedVersion, requireName(name),
                enabled, nextVersion, now)) {
            throw conflict(ApiMessage.of("api.media-capability-service.the-connection-has-been-modified-by-other-operations"));
        }
        if (newVersion) {
            String versionKey = current.platform() == MediaPlatform.COMFYUI ? normalizedOrigin : apiKey;
            if ((versionKey == null || versionKey.isBlank())
                    && previous.credentialCiphertext() != null) {
                versionKey = cipher.decryptMedia(id, previous.version(),
                        new CredentialCipher.Encrypted(previous.credentialCiphertext(),
                                previous.credentialNonce(), previous.credentialKeyVersion()));
            }
            CredentialCipher.Encrypted encrypted = versionKey == null || versionKey.isBlank()
                    ? null : cipher.encryptMedia(id, nextVersion, versionKey);
            repository.insertConnectionVersion(id, nextVersion, displayOrigin(current.platform(), normalizedOrigin),
                    normalizedOrigin == null ? null : Sha256.hex(normalizedOrigin),
                    encrypted == null ? null : encrypted.ciphertext(),
                    encrypted == null ? null : encrypted.nonce(),
                    encrypted == null ? null : encrypted.keyVersion(),
                    current.platform() == MediaPlatform.COMFYUI ? null : keyMask(versionKey), now);
        }
        return getConnection(id);
    }

    public List<Connection> connections() {
        return repository.connections().stream()
                .filter(connection -> availablePlatform(connection.platform())).toList();
    }

    public List<Capability> capabilities(UUID connectionId) {
        if (!availablePlatform(getConnection(connectionId).platform())) return List.of();
        return repository.capabilities(connectionId);
    }

    public Snapshot capabilitySnapshot(UUID capabilityId) {
        return repository.snapshot(capabilityId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "MEDIA_CAPABILITY_NOT_FOUND",
                        ApiMessage.of("api.media-capability-service.media-capabilities-do-not-exist"), ApiMessage.of("api.media-capability-service.the-media-capability-cannot-be-found"), false));
    }

    @Transactional
    public Capability publishCapability(UUID connectionId, String name, String adapterId) {
        return publishCapabilityWithId(UUID.randomUUID(), connectionId, name, adapterId,
                null);
    }

    @Transactional
    public Capability publishCapability(UUID connectionId, String name, String adapterId,
            JsonNode settings) {
        return publishCapabilityWithId(UUID.randomUUID(), connectionId, name, adapterId,
                settings);
    }

    @Transactional
    public Capability publishCapability(String idempotencyKey, UUID connectionId,
            String name, String adapterId) {
        return publishCapability(idempotencyKey, connectionId, name, adapterId, null);
    }

    @Transactional
    public Capability publishCapability(String idempotencyKey, UUID connectionId,
            String name, String adapterId, JsonNode settings) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 160) {
            throw invalid(ApiMessage.of("api.media-capability-service.a-valid-idempotency-key-must-be-provided"));
        }
        String normalizedName = requireName(name);
        String spec = spec(adapterId, settings);
        String hash = Sha256.hex(connectionId + "\u0000" + normalizedName + "\u0000"
                + adapterId + "\u0000" + spec);
        UUID id = UUID.randomUUID();
        if (!repository.claimCapabilityCreateKey(idempotencyKey, hash, id, clock.instant())) {
            var previous = repository.capabilityCreateKey(idempotencyKey).orElseThrow();
            if (!previous.payloadSha256().equals(hash)) {
                throw conflict(ApiMessage.of("api.media-capability-service.the-same-idempotency-key-corresponds-to-different-capability-content"));
            }
            return repository.capability(previous.entityId()).orElseThrow();
        }
        return publishCapabilityWithId(id, connectionId, normalizedName, adapterId, settings);
    }

    private Capability publishCapabilityWithId(UUID id, UUID connectionId,
            String name, String adapterId, JsonNode settings) {
        Connection connection = getConnection(connectionId);
        requireAvailablePlatform(connection.platform());
        rejectSystemManaged(connection);
        MediaAdapterRegistry.Declaration declaration = registry.declaration(adapterId);
        if (!connection.enabled()) {
            throw conflict(ApiMessage.of("api.media-capability-service.connection-disabled"));
        }
        ConnectionVersion version = getConnectionVersion(connectionId,
                connection.currentVersion()).orElseThrow();
        if (declaration.originRequired() && version.origin() == null) {
            throw invalid(ApiMessage.of("api.media-capability-service.the-adapter-requires-a-connection-address"));
        }
        // OpenAI 与 Google 的固定适配器都允许把端点指向自托管网关或中转站。
        if (!declaration.originRequired() && version.origin() != null
                && connection.platform() != MediaPlatform.OPENAI
                && connection.platform() != MediaPlatform.GOOGLE) {
            throw invalid(ApiMessage.of("api.media-capability-service.the-adapter-does-not-accept-the-connection-address"));
        }
        if (connection.platform() != declaration.platform()) {
            throw invalid(ApiMessage.of("api.media-capability-service.adapter-does-not-match-platform-connection"));
        }
        String spec = spec(adapterId, settings);
        repository.insertCapability(id, connectionId, requireName(name), adapterId,
                Sha256.hex(adapterId + ":v1:" + spec), spec, clock.instant());
        return repository.capability(id).orElseThrow();
    }

    @Transactional
    public Capability updateCapability(UUID connectionId, UUID capabilityId,
            long expectedVersion, String name, boolean enabled, String adapterId) {
        return updateCapability(connectionId, capabilityId, expectedVersion, name,
                enabled, adapterId, null);
    }

    @Transactional
    public Capability updateCapability(UUID connectionId, UUID capabilityId,
            long expectedVersion, String name, boolean enabled, String adapterId,
            JsonNode settings) {
        repository.lockCapability(capabilityId);
        Snapshot current = capabilitySnapshot(capabilityId);
        requireAvailablePlatform(current.connection().platform());
        if (!current.connection().id().equals(connectionId)) {
            throw invalid(ApiMessage.of("api.media-capability-service.capability-does-not-belong-to-this-connection"));
        }
        rejectSystemManaged(current.connection());
        if (current.capability().version() != expectedVersion) {
            throw conflict(ApiMessage.of("api.media-capability-service.ability-has-been-modified-by-another-operation"));
        }
        MediaAdapterRegistry.Declaration replacement = registry.declaration(adapterId);
        if (current.connection().platform() != replacement.platform()) {
            throw invalid(ApiMessage.of("api.media-capability-service.adapter-does-not-match-platform-connection"));
        }
        Task.Kind previousKind = registry.declaration(current.adapterId()).kind();
        boolean changedKind = previousKind != replacement.kind();
        if (changedKind && !(MediaAdapterRegistry.RUNNINGHUB_ADAPTERS.contains(current.adapterId())
                && MediaAdapterRegistry.RUNNINGHUB_ADAPTERS.contains(adapterId))) {
            throw invalid(ApiMessage.of("api.media-capability-service.ability-s-output-type-is-immutable-please-publish-new-capabilities"));
        }
        JsonNode oldSettings = settings == null && current.adapterId().equals(adapterId)
                ? settings(current) : null;
        JsonNode effectiveSettings = oldSettings == null ? settings : oldSettings;
        if (effectiveSettings != null && effectiveSettings.isMissingNode()) {
            effectiveSettings = mapper.createObjectNode();
        }
        String spec = spec(adapterId, effectiveSettings);
        if (oldSettings == null) oldSettings = settings(current);
        JsonNode newSettings = mapper.readTree(spec).path("settings");
        boolean newVersion = !current.adapterId().equals(adapterId)
                || !newSettings.equals(oldSettings);
        int nextVersion = current.capability().currentVersion() + (newVersion ? 1 : 0);
        Instant now = clock.instant();
        if (!repository.updateCapability(capabilityId, expectedVersion, requireName(name),
                enabled, nextVersion, now)) {
            throw conflict(ApiMessage.of("api.media-capability-service.ability-has-been-modified-by-another-operation"));
        }
        if (newVersion) {
            repository.insertCapabilityVersion(capabilityId, nextVersion, adapterId,
                    Sha256.hex(adapterId + ":v1:" + spec), spec, now);
        }
        if (changedKind) repository.clearDefaultForCapability(previousKind.name(), capabilityId);
        return repository.capability(capabilityId).orElseThrow();
    }

    public Connection getConnection(UUID connectionId) {
        return repository.connection(connectionId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "MEDIA_CONNECTION_NOT_FOUND",
                        ApiMessage.of("api.media-capability-service.media-connection-does-not-exist"), ApiMessage.of("api.media-capability-service.the-media-connection-cannot-be-found"), false));
    }

    public Optional<ConnectionVersion> getConnectionVersion(UUID connectionId, int version) {
        return repository.connectionVersion(connectionId, version);
    }

    /** Internal historical snapshot; never serialize this record into API or model output. */
    public Snapshot pinnedSnapshot(MediaCapabilityBinding binding) {
        Snapshot snapshot = repository.snapshotAt(binding.capabilityId(),
                binding.capabilityVersion(), binding.connectionId(),
                binding.connectionVersion()).orElseThrow(() ->
                new IllegalStateException("Pinned media capability version is missing"));
        if (!binding.adapterId().equals(snapshot.adapterId())
                || !binding.mappingSha256().equals(snapshot.mappingSha256())) {
            throw new IllegalStateException("Pinned media adapter identity differs from history");
        }
        return snapshot;
    }

    /** Versioned protocol settings; graph structure and adapter support remain compiled code. */
    private String spec(String adapterId, JsonNode suppliedSettings) {
        JsonNode source = suppliedSettings == null ? mapper.createObjectNode() : suppliedSettings;
        var declaration = declaration(adapterId, source);
        ObjectNode normalized = mapper.createObjectNode();
        normalized.put("schemaVersion", CAPABILITY_SCHEMA_VERSION);
        normalized.put("kind", declaration.kind().name());
        putInputLimits(normalized, declaration);
        var modes = normalized.putArray("supportedVideoInputModes");
        declaration.supportedVideoInputModes().stream().sorted().forEach(modes::add);
        normalized.put("defaultVideoInputMode", declaration.defaultVideoInputMode());
        normalized.put("supportsEndFrame", declaration.supportsEndFrame());
        putModelMetadata(normalized, adapterId);
        ObjectNode settings = normalized.putObject("settings");
        if (!source.isObject()) throw invalid(ApiMessage.of("api.media-capability-service.capability-template-parameters-must-be-objects"));
        if (MediaAdapterRegistry.RUNNINGHUB_ADAPTERS.contains(adapterId)) {
            if (!java.util.Set.of("runningHub", "pricing").containsAll(source.propertyNames()))
                throw invalid(ApiMessage.of("api.media-capability-service.runninghub-capabilities-only-accept-parameter-definitions-and-estimated-prices"));
            var definition = RunningHubDefinition.parse(mapper, source.get("runningHub"), declaration.kind());
            settings.set("runningHub", mapper.valueToTree(definition));
            ObjectNode price = mapper.createObjectNode();
            if (source.has("pricing")) price.set("pricing", source.get("pricing"));
            MediaCapabilityConfiguration.normalize(mapper, declaration, price, settings);
            return normalized.toString();
        }
        normalizeAdapterSettings(adapterId, source, settings);
        if (AutoDlWorkflows.ADAPTER_ID.equals(adapterId)) AutoDlWorkflows.normalize(source, settings);
        MediaCapabilityConfiguration.normalize(mapper, declaration, source, settings);
        var policy = MediaCapabilityConfiguration.policy(declaration, settings);
        if (AutoDlWorkflows.ADAPTER_ID.equals(adapterId)) {
            var workflow = AutoDlWorkflows.require(settings);
            if (policy.maxReferenceImages() < workflow.minimumImages() || policy.maxReferenceAudios() < workflow.minimumAudios())
                throw invalid(ApiMessage.of("api.media-capability-service.the-reference-upper-limit-cannot-be-lower-than-the-required"));
            if (settings.has("defaultParameters")) {
                String ratio = settings.path("defaultParameters").path("aspectRatio").asText();
                String tier = AutoDlWorkflows.selectedResolution(settings, null);
                if (!"AUTO".equals(ratio)) workflow.resolution(tier, ratio);
            }
        }
        putInputLimits(normalized, policy);
        return normalized.toString();
    }

    private void normalizeAdapterSettings(String adapterId, JsonNode source, ObjectNode settings) {
        List<String> fields = switch (adapterId) {
            case "COMFY_IMAGE_V1" -> List.of("checkpoint");
            case "COMFY_VIDEO_V1" -> List.of("diffusionModel", "textEncoder", "vae",
                    "clipVision");
            case "OPENAI_GPT_IMAGE_2" -> List.of("quality", "model");
            case "GOOGLE_NANO_BANANA_2" -> List.of("model");
            default -> List.of();
        };
        for (String field : source.propertyNames()) {
            if (!fields.contains(field) && !MediaCapabilityConfiguration.FIELDS.contains(field)
                    && !(AutoDlWorkflows.ADAPTER_ID.equals(adapterId) && AutoDlWorkflows.SETTINGS.contains(field)))
                throw invalid(ApiMessage.of("api.media-capability-service.capability-template-contains-parameters-that-are-not-allowed"));
        }
        for (String field : fields) {
            JsonNode value = source.path(field);
            if ("quality".equals(field)) {
                String quality = value.isMissingNode() ? ImageGenerationParameters.DEFAULT_QUALITY : value.asText();
                if (!ImageGenerationParameters.QUALITIES.contains(quality)) {
                    throw invalid(ApiMessage.of("api.media-capability-service.gpt-image-2-quality-can-only-be-low-medium-or"));
                }
                settings.put(field, quality);
                continue;
            }
            // 模型名留空表示沿用适配器内置默认；中转站命名格式无法预知，只挡明显非法的取值。
            if ("model".equals(field)) {
                String model = value.isMissingNode() || value.isNull() ? "" : value.asText();
                if (!model.isEmpty() && !model.matches(MODEL_NAME_PATTERN)) {
                    throw invalid(ApiMessage.of("api.media-capability-service.the-model-name-can-only-contain-letters-numbers-and-and"));
                }
                settings.put(field, model);
                continue;
            }
            if (!value.isTextual() || !value.asText().matches(COMFY_MODEL_FILE_PATTERN)
                    || value.asText().contains("..") || !value.asText().endsWith(".safetensors")) {
                throw invalid(ApiMessage.of("api.media-capability-service.comfyui-template-model-file-name-must-be-safetensors-file-name"));
            }
            settings.put(field, value.asText());
        }
    }

    private MediaAdapterRegistry.Declaration declaration(String adapterId, JsonNode settings) {
        return AutoDlWorkflows.ADAPTER_ID.equals(adapterId)
                ? AutoDlWorkflows.require(settings).declaration() : registry.declaration(adapterId);
    }

    private static void putInputLimits(ObjectNode target, MediaAdapterRegistry.Declaration policy) {
        target.put("minimumSeconds", policy.minimumSeconds());
        target.put("maximumSeconds", policy.maximumSeconds());
        target.put("maxReferenceImages", policy.maxReferenceImages());
        target.put("maxReferenceAudios", policy.maxReferenceAudios());
        target.put("maxReferenceVideos", policy.maxReferenceVideos());
    }

    private static void putModelMetadata(ObjectNode target, String adapterId) {
        switch (adapterId) {
            case "OPENAI_GPT_IMAGE_2" -> target.put("modelId", OpenAiImage2Client.DEFAULT_MODEL).put("outputFormat", "png");
            case "GOOGLE_NANO_BANANA_2" -> target.put("modelId", GoogleNanoBananaClient.DEFAULT_MODEL)
                    .put("outputFormat", "image").put("imageSize", ImageGenerationParameters.DEFAULT_RESOLUTION);
            case "VOLC_SEED_AUDIO_1" -> target.put("modelId", SeedAudioClient.MODEL_ID).put("outputFormat", "mp3");
            case "ARK_SEEDANCE_2_I2V" -> target.put("modelId", ArkSeedanceClient.MODEL_ID)
                    .put("outputFormat", "mp4").put("generateAudio", false);
            default -> { }
        }
    }

    @Transactional
    public Connection setConnectionEnabled(UUID connectionId, long expectedVersion,
            boolean enabled) {
        Connection connection = getConnection(connectionId);
        requireAvailablePlatform(connection.platform());
        rejectSystemManaged(connection);
        if (!repository.updateConnectionEnabled(connectionId, expectedVersion, enabled,
                clock.instant())) {
            throw conflict(ApiMessage.of("api.media-capability-service.the-connection-has-been-modified-by-other-operations"));
        }
        return getConnection(connectionId);
    }

    public long defaultVersion(Task.Kind kind) {
        return repository.defaultVersion(requireMediaKind(kind).name());
    }

    public UUID defaultCapabilityId(Task.Kind kind) {
        UUID capabilityId = repository.defaultCapabilityId(requireMediaKind(kind).name());
        if (capabilityId == null) return null;
        if (mode.mode() != ProviderModeProperties.Mode.CONFIGURED) return capabilityId;
        return availablePlatform(capabilitySnapshot(capabilityId).connection().platform())
                ? capabilityId : null;
    }

    /** Immutable administrator limits within the protocol pinned by this task. */
    public MediaAdapterRegistry.Declaration inputPolicy(MediaCapabilityBinding binding) {
        return inputPolicy(pinnedSnapshot(binding));
    }

    public MediaAdapterRegistry.Declaration inputPolicy(Snapshot snapshot) {
        JsonNode settings = mapper.readTree(snapshot.specJson()).path("settings");
        var declaration = declaration(snapshot.adapterId(), settings);
        return MediaCapabilityConfiguration.policy(declaration, settings);
    }

    public RunningHubDefinition runningHubDefinition(MediaCapabilityBinding binding) {
        if (!MediaAdapterRegistry.RUNNINGHUB_ADAPTERS.contains(binding.adapterId())) return null;
        return RunningHubDefinition.parse(mapper, settings(binding).path("runningHub"),
                registry.declaration(binding.adapterId()).kind());
    }

    public JsonNode settings(MediaCapabilityBinding binding) {
        return settings(pinnedSnapshot(binding));
    }

    private JsonNode settings(Snapshot snapshot) {
        JsonNode settings = mapper.readTree(snapshot.specJson()).path("settings");
        return settings.isMissingNode() ? mapper.createObjectNode() : settings;
    }

    /** Missing card fields use the selected version's defaults; explicit card values win. */
    public JsonNode parameters(MediaCapabilityBinding binding, JsonNode draft) {
        ObjectNode parameters = mapper.createObjectNode();
        JsonNode configuration = settings(binding);
        if (configuration.has("quality")) parameters.set("quality", configuration.get("quality"));
        if (AutoDlWorkflows.ADAPTER_ID.equals(binding.adapterId()))
            parameters.set("videoResolution", configuration.path("videoResolution"));
        JsonNode defaults = configuration.path("defaultParameters");
        if (defaults.isObject()) defaults.properties().forEach(entry ->
                parameters.set(entry.getKey(), entry.getValue()));
        if (draft != null && draft.isObject()) draft.properties().forEach(entry ->
                parameters.set(entry.getKey(), entry.getValue()));
        return parameters;
    }

    @Transactional
    public MediaCapabilityBinding setDefault(Task.Kind kind, long expectedVersion,
            UUID capabilityId) {
        Task.Kind mediaKind = requireMediaKind(kind);
        repository.lockCapability(capabilityId);
        Snapshot snapshot = enabledSnapshot(capabilityId);
        if (MediaAdapterRegistry.localProcessor(snapshot.adapterId())) {
            throw invalid(ApiMessage.of("api.media-capability-service.local-image-processing-capabilities-cannot-be-set-as-the-default"));
        }
        if (registry.declaration(snapshot.adapterId()).kind() != mediaKind) {
            throw invalid(ApiMessage.of("api.media-capability-service.default-capabilities-do-not-match-media-type"));
        }
        if (!repository.updateDefault(mediaKind.name(), expectedVersion, capabilityId)) {
            throw conflict(ApiMessage.of("api.media-capability-service.default-capabilities-have-been-modified-by-other-operations"));
        }
        return binding(snapshot);
    }

    public MediaCapabilityBinding defaultFor(Task.Kind kind) {
        Task.Kind mediaKind = requireMediaKind(kind);
        return resolve(repository.defaultCapabilityId(mediaKind.name()), mediaKind,
                mediaKind == Task.Kind.VIDEO_GENERATION ? -1 : 0, true);
    }

    public MediaCapabilityBinding forDraft(UUID capabilityId, Task.Kind kind) {
        return capabilityId == null ? defaultFor(kind)
                : resolve(capabilityId, requireMediaKind(kind), 0, true);
    }

    public MediaCapabilityBinding resolve(UUID capabilityId, Task.Kind kind,
            int durationSeconds) {
        return resolve(capabilityId, requireMediaKind(kind), durationSeconds, false);
    }

    /** Candidate metadata is server-selected and contains no endpoint or credential. */
    public List<Candidate> candidates(Task.Kind kind, int durationSeconds) {
        Task.Kind mediaKind = requireMediaKind(kind);
        return publishedSnapshots()
                .filter(snapshot -> supports(snapshot, mediaKind, durationSeconds))
                .map(snapshot -> candidate(snapshot, inputPolicy(snapshot))).toList();
    }

    /** Safe published catalog for model planning; duration suitability is checked per step. */
    public List<Candidate> publishedCandidates() {
        return publishedSnapshots().map(snapshot -> candidate(snapshot, inputPolicy(snapshot)))
                .toList();
    }

    private Stream<Snapshot> publishedSnapshots() {
        return connections().stream().filter(Connection::enabled)
                .flatMap(connection -> repository.capabilities(connection.id()).stream())
                .filter(Capability::enabled)
                .map(capability -> repository.snapshot(capability.id()).orElseThrow())
                .filter(snapshot -> !MediaAdapterRegistry.localProcessor(snapshot.adapterId()));
    }

    private Candidate candidate(Snapshot snapshot, MediaAdapterRegistry.Declaration policy) {
        return new Candidate(binding(snapshot), snapshot.connection().name(),
                snapshot.capability().name(), policy.kind(), policy.minimumSeconds(),
                policy.maximumSeconds(), false,
                mapper.readTree(snapshot.specJson()).path("settings"));
    }

    /** Approval only accepts the exact still-published versions frozen in the step. */
    public boolean isCurrentBinding(MediaCapabilityBinding binding, Task.Kind kind,
            int durationSeconds) {
        try {
            return binding.equals(resolve(binding.capabilityId(), kind, durationSeconds));
        } catch (ApiProblemException invalid) {
            return false;
        }
    }

    public record Candidate(MediaCapabilityBinding binding, String connectionName,
            String capabilityName, Task.Kind kind, int minimumSeconds,
            int maximumSeconds, boolean realGenerationTested, JsonNode settings) {}

    private MediaCapabilityBinding resolve(UUID capabilityId, Task.Kind kind,
            int durationSeconds, boolean skipDuration) {
        Snapshot snapshot = enabledSnapshot(capabilityId);
        if (registry.declaration(snapshot.adapterId()).kind() != kind) {
            throw invalid(ApiMessage.of("api.media-capability-service.ability-output-type-does-not-match-task-category"));
        }
        if (!skipDuration && !supports(snapshot, kind, durationSeconds)) {
            throw invalid(ApiMessage.of("api.media-capability-service.this-ability-does-not-support-the-current-duration-please-adjust"));
        }
        return binding(snapshot);
    }

    private boolean supports(Snapshot snapshot, Task.Kind kind, int durationSeconds) {
        var policy = inputPolicy(snapshot);
        if (MediaAdapterRegistry.RUNNINGHUB_ADAPTERS.contains(snapshot.adapterId())) return policy.kind() == kind;
        return policy.kind() == kind && (kind == Task.Kind.IMAGE_GENERATION
                || durationSeconds >= policy.minimumSeconds()
                        && durationSeconds <= policy.maximumSeconds());
    }

    private Snapshot enabledSnapshot(UUID capabilityId) {
        Snapshot snapshot = capabilitySnapshot(capabilityId);
        requireAvailablePlatform(snapshot.connection().platform());
        if (!snapshot.connection().enabled() || !snapshot.capability().enabled()) {
            throw conflict(ApiMessage.of("api.media-capability-service.media-connection-or-capability-is-disabled"));
        }
        registry.declaration(snapshot.adapterId());
        return snapshot;
    }

    /** Hide development fixtures without changing persisted configuration or pinned task history. */
    private boolean availablePlatform(MediaPlatform platform) {
        return mode.mode() != ProviderModeProperties.Mode.CONFIGURED || platform != MediaPlatform.MOCK;
    }

    private void requireAvailablePlatform(MediaPlatform platform) {
        if (!availablePlatform(platform)) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PROVIDER_UNSUPPORTED_CAPABILITY",
                    ApiMessage.of("api.media-adapter-registry.media-capabilities-are-not-available"),
                    ApiMessage.of("api.media-adapter-registry.this-media-adapter-is-not-installed-by-the-current-application"), false);
        }
    }

    private static MediaCapabilityBinding binding(Snapshot snapshot) {
        return new MediaCapabilityBinding(snapshot.connection().id(),
                snapshot.connection().currentVersion(), snapshot.capability().id(),
                snapshot.capability().currentVersion(), snapshot.adapterId(),
                snapshot.mappingSha256());
    }

    private static void rejectSystemManaged(Connection connection) {
        if (connection.platform() == MediaPlatform.LOCAL) {
            throw invalid(ApiMessage.of("api.media-capability-service.local-image-processing-is-a-built-in-capability-of-application"));
        }
    }

    private static Task.Kind requireMediaKind(Task.Kind kind) {
        if (kind != Task.Kind.IMAGE_GENERATION && kind != Task.Kind.VIDEO_GENERATION && kind != Task.Kind.AUDIO_GENERATION) {
            throw invalid(ApiMessage.of("api.media-capability-service.only-supports-picture-or-video-capabilities"));
        }
        return kind;
    }

    private static String requireName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 160) {
            throw invalid(ApiMessage.of("api.media-capability-service.name-must-be-1-160-characters-long"));
        }
        return name;
    }

    private static MediaPlatform requirePlatform(String value) {
        try {
            return MediaPlatform.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException unknownPlatform) {
            throw invalid(ApiMessage.of("api.media-capability-service.unsupported-platform-types"));
        }
    }

    private static String validatedOrigin(MediaPlatform platform, String origin) {
        if (platform == MediaPlatform.RUNNINGHUB) {
            return dev.agenvas.provider.infrastructure.RunningHubClient.validatedOrigin(origin);
        }
        if (platform == MediaPlatform.OPENAI || platform == MediaPlatform.GOOGLE) {
            if (origin == null || origin.isBlank()) return null;
            try {
                java.net.URI uri = java.net.URI.create(origin.trim());
                String path = uri.getRawPath();
                if (origin.length() > 500
                        || uri.getHost() == null || uri.getHost().isBlank()
                        || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || path != null && (path.contains("..") || path.contains("%")
                                || path.contains("\\") || path.contains("//"))) {
                    throw invalid(ApiMessage.of("api.media-capability-service.the-base-url-must-be-the-public-https-api-root"));
                }
                // 本机回环是自托管服务与本地假 API 的固定例外，与 ComfyUI 的规则一致。
                boolean loopback = "http".equals(uri.getScheme())
                        && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0;
                if (!"https".equals(uri.getScheme()) && !loopback) {
                    throw invalid(ApiMessage.of("api.media-capability-service.the-base-url-must-be-the-public-https-api-root"));
                }
                return uri.toASCIIString().replaceAll("/+$", "");
            } catch (IllegalArgumentException invalidUri) {
                throw invalid(ApiMessage.of("api.media-capability-service.base-url-is-invalid"));
            }
        }
        if (platform != MediaPlatform.COMFYUI) {
            if (origin != null && !origin.isBlank()) {
                throw invalid(ApiMessage.of("api.media-capability-service.the-platform-uses-built-in-fixed-endpoints-and-cannot-fill"));
            }
            return null;
        }
        try {
            return ComfyUiEndpoint.checked(origin).toString();
        } catch (IllegalArgumentException invalidUri) {
            throw invalid(ApiMessage.of("api.media-capability-service.comfyui-address-is-invalid"));
        }
    }

    private String comfyEndpoint(ConnectionVersion version) {
        return version.credentialCiphertext() == null ? version.origin()
                : cipher.decryptMedia(version.connectionId(), version.version(),
                        new CredentialCipher.Encrypted(version.credentialCiphertext(),
                                version.credentialNonce(), version.credentialKeyVersion()));
    }

    private static String displayOrigin(MediaPlatform platform, String origin) {
        return platform == MediaPlatform.COMFYUI ? ComfyUiEndpoint.display(origin) : origin;
    }

    private static void validateCredential(MediaPlatform platform, String apiKey, boolean creating) {
        boolean cloud = platform == MediaPlatform.OPENAI || platform == MediaPlatform.ARK
                || platform == MediaPlatform.GOOGLE || platform == MediaPlatform.VOLCENGINE
                || platform == MediaPlatform.RUNNINGHUB || platform == MediaPlatform.AUTODL;
        if (cloud && creating && (apiKey == null || apiKey.isBlank())) {
            throw invalid(ApiMessage.of("api.media-capability-service.cloud-platform-connection-must-fill-in-the-api-key"));
        }
        if (!cloud && apiKey != null && !apiKey.isBlank()) {
            throw invalid(ApiMessage.of("api.media-capability-service.the-platform-does-not-accept-api-key"));
        }
    }

    private static String keyMask(String key) {
        return key == null || key.isBlank() ? null
                : "••••" + key.substring(Math.max(0, key.length() - 4));
    }

    private static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PROVIDER_UNSUPPORTED_INPUT", ApiMessage.of("api.media-capability-service.media-capability-does-not-support-this-input"), detail, false);
    }

    private static ApiProblemException conflict(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "MEDIA_CAPABILITY_CONFLICT",
                ApiMessage.of("api.media-capability-service.media-configuration-conflict"), detail, false);
    }
}
