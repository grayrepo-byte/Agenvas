package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaCapabilityConfiguration;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Capability;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Connection;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.ConnectionVersion;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Owns the media catalog; the returned binding freezes both published versions. */
@Service
public class MediaCapabilityService {
    private static final String NANO_BANANA_MODEL_ID = "gemini-3.1-flash-image";
    private static final String NANO_BANANA_IMAGE_SIZE = "1K";

    private final JooqMediaCapabilityRepository repository;
    private final MediaAdapterRegistry registry;
    private final CredentialCipher cipher;
    private final Clock clock;
    private final ObjectMapper mapper;

    public MediaCapabilityService(JooqMediaCapabilityRepository repository,
            MediaAdapterRegistry registry, CredentialCipher cipher, Clock clock,
            ObjectMapper mapper) {
        this.repository = repository;
        this.registry = registry;
        this.cipher = cipher;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional
    public Connection createConnection(String name, String origin) {
        String normalizedName = requireName(name);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        repository.insertConnection(id, normalizedName,
                origin == null ? MediaPlatform.MOCK : MediaPlatform.COMFYUI, origin,
                origin == null ? null : sha256(origin), null, null, null, null, now);
        return repository.connection(id).orElseThrow();
    }

    /** Idempotent admin creation; credentials are encrypted before any version row is written. */
    @Transactional
    public Connection createConnection(String idempotencyKey, String name, String platform,
            String origin, String apiKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 160) {
            throw invalid("必须提供有效的 Idempotency-Key");
        }
        String normalizedName = requireName(name);
        MediaPlatform normalizedPlatform = requirePlatform(platform);
        if (normalizedPlatform == MediaPlatform.LOCAL) {
            throw invalid("本地图片处理是应用内置能力，不能创建重复连接");
        }
        String normalizedOrigin = validatedOrigin(normalizedPlatform, origin);
        validateCredential(normalizedPlatform, apiKey, true);
        String hash = sha256(normalizedName + "\u0000" + normalizedPlatform.name() + "\u0000"
                + normalizedOrigin + "\u0000" + apiKey);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        if (!repository.claimCreateKey(idempotencyKey, hash, id, now)) {
            var previous = repository.createKey(idempotencyKey).orElseThrow();
            if (!previous.payloadSha256().equals(hash)) {
                throw conflict("相同 Idempotency-Key 对应不同配置内容");
            }
            return getConnection(previous.entityId());
        }
        CredentialCipher.Encrypted encrypted = apiKey == null || apiKey.isBlank()
                ? null : cipher.encryptMedia(id, 1, apiKey);
        repository.insertConnection(id, normalizedName, normalizedPlatform, normalizedOrigin,
                normalizedOrigin == null ? null : sha256(normalizedOrigin),
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
        rejectSystemManaged(current);
        if (current.version() != expectedVersion) {
            throw conflict("连接已被其他操作修改");
        }
        ConnectionVersion previous = getConnectionVersion(id, current.currentVersion())
                .orElseThrow();
        String normalizedOrigin = validatedOrigin(current.platform(), origin);
        validateCredential(current.platform(), apiKey, false);
        boolean newVersion = !java.util.Objects.equals(previous.origin(), normalizedOrigin)
                || apiKey != null && !apiKey.isBlank();
        int nextVersion = current.currentVersion() + (newVersion ? 1 : 0);
        Instant now = clock.instant();
        if (!repository.updateConnection(id, expectedVersion, requireName(name),
                enabled, nextVersion, now)) {
            throw conflict("连接已被其他操作修改");
        }
        if (newVersion) {
            String versionKey = apiKey;
            if ((versionKey == null || versionKey.isBlank())
                    && previous.credentialCiphertext() != null) {
                versionKey = cipher.decryptMedia(id, previous.version(),
                        new CredentialCipher.Encrypted(previous.credentialCiphertext(),
                                previous.credentialNonce(), previous.credentialKeyVersion()));
            }
            CredentialCipher.Encrypted encrypted = versionKey == null || versionKey.isBlank()
                    ? null : cipher.encryptMedia(id, nextVersion, versionKey);
            repository.insertConnectionVersion(id, nextVersion, normalizedOrigin,
                    normalizedOrigin == null ? null : sha256(normalizedOrigin),
                    encrypted == null ? null : encrypted.ciphertext(),
                    encrypted == null ? null : encrypted.nonce(),
                    encrypted == null ? null : encrypted.keyVersion(),
                    encrypted == null ? null : keyMask(versionKey), now);
        }
        return getConnection(id);
    }

    public List<Connection> connections() {
        return repository.connections();
    }

    public List<Capability> capabilities(UUID connectionId) {
        getConnection(connectionId);
        return repository.capabilities(connectionId);
    }

    public Snapshot capabilitySnapshot(UUID capabilityId) {
        return repository.snapshot(capabilityId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "MEDIA_CAPABILITY_NOT_FOUND",
                        "媒体能力不存在", "找不到该媒体能力", false));
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
            throw invalid("必须提供有效的 Idempotency-Key");
        }
        String normalizedName = requireName(name);
        String spec = spec(adapterId, settings);
        String hash = sha256(connectionId + "\u0000" + normalizedName + "\u0000"
                + adapterId + "\u0000" + spec);
        UUID id = UUID.randomUUID();
        if (!repository.claimCapabilityCreateKey(idempotencyKey, hash, id, clock.instant())) {
            var previous = repository.capabilityCreateKey(idempotencyKey).orElseThrow();
            if (!previous.payloadSha256().equals(hash)) {
                throw conflict("相同 Idempotency-Key 对应不同能力内容");
            }
            return repository.capability(previous.entityId()).orElseThrow();
        }
        return publishCapabilityWithId(id, connectionId, normalizedName, adapterId, settings);
    }

    private Capability publishCapabilityWithId(UUID id, UUID connectionId,
            String name, String adapterId, JsonNode settings) {
        Connection connection = getConnection(connectionId);
        rejectSystemManaged(connection);
        MediaAdapterRegistry.Declaration declaration = registry.declaration(adapterId);
        if (!connection.enabled()) {
            throw conflict("连接已停用");
        }
        ConnectionVersion version = getConnectionVersion(connectionId,
                connection.currentVersion()).orElseThrow();
        if (declaration.originRequired() && version.origin() == null) {
            throw invalid("该适配器需要连接地址");
        }
        // OpenAI 与 Google 的固定适配器都允许把端点指向自托管网关或中转站。
        if (!declaration.originRequired() && version.origin() != null
                && connection.platform() != MediaPlatform.OPENAI
                && connection.platform() != MediaPlatform.GOOGLE) {
            throw invalid("该适配器不接受连接地址");
        }
        if (connection.platform() != declaration.platform()) {
            throw invalid("适配器与平台连接不匹配");
        }
        String spec = spec(adapterId, settings);
        repository.insertCapability(id, connectionId, requireName(name), adapterId,
                sha256(adapterId + ":v1:" + spec), spec, clock.instant());
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
        Snapshot current = capabilitySnapshot(capabilityId);
        if (!current.connection().id().equals(connectionId)) {
            throw invalid("能力不属于此连接");
        }
        rejectSystemManaged(current.connection());
        if (current.capability().version() != expectedVersion) {
            throw conflict("能力已被其他操作修改");
        }
        MediaAdapterRegistry.Declaration replacement = registry.declaration(adapterId);
        if (current.connection().platform() != replacement.platform()) {
            throw invalid("适配器与平台连接不匹配");
        }
        if (registry.declaration(current.adapterId()).kind() != replacement.kind()) {
            throw invalid("能力的输出类型不可变；请发布新能力");
        }
        JsonNode effectiveSettings = settings == null && current.adapterId().equals(adapterId)
                ? mapper.readTree(current.specJson()).path("settings") : settings;
        if (effectiveSettings != null && effectiveSettings.isMissingNode()) {
            effectiveSettings = mapper.createObjectNode();
        }
        String spec = spec(adapterId, effectiveSettings);
        JsonNode oldSettings = mapper.readTree(current.specJson()).path("settings");
        JsonNode newSettings = mapper.readTree(spec).path("settings");
        boolean newVersion = !current.adapterId().equals(adapterId)
                || !newSettings.equals(oldSettings.isMissingNode()
                        ? mapper.createObjectNode() : oldSettings);
        int nextVersion = current.capability().currentVersion() + (newVersion ? 1 : 0);
        Instant now = clock.instant();
        if (!repository.updateCapability(capabilityId, expectedVersion, requireName(name),
                enabled, nextVersion, now)) {
            throw conflict("能力已被其他操作修改");
        }
        if (newVersion) {
            repository.insertCapabilityVersion(capabilityId, nextVersion, adapterId,
                    sha256(adapterId + ":v1:" + spec), spec, now);
        }
        return repository.capability(capabilityId).orElseThrow();
    }

    public Connection getConnection(UUID connectionId) {
        return repository.connection(connectionId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "MEDIA_CONNECTION_NOT_FOUND",
                        "媒体连接不存在", "找不到该媒体连接", false));
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
        var declaration = registry.declaration(adapterId);
        ObjectNode normalized = mapper.createObjectNode();
        normalized.put("schemaVersion", 1);
        normalized.put("kind", declaration.kind().name());
        normalized.put("minimumSeconds", declaration.minimumSeconds());
        normalized.put("maximumSeconds", declaration.maximumSeconds());
        normalized.put("maxReferenceImages", declaration.maxReferenceImages());
        normalized.put("maxReferenceAudios", declaration.maxReferenceAudios());
        var modes = normalized.putArray("supportedVideoInputModes");
        declaration.supportedVideoInputModes().stream().sorted().forEach(modes::add);
        if (declaration.defaultVideoInputMode() == null) {
            normalized.putNull("defaultVideoInputMode");
        } else {
            normalized.put("defaultVideoInputMode", declaration.defaultVideoInputMode());
        }
        normalized.put("supportsEndFrame", declaration.supportsEndFrame());
        if ("OPENAI_GPT_IMAGE_2".equals(adapterId)) {
            normalized.put("modelId", "gpt-image-2");
            normalized.put("outputFormat", "png");
        } else if ("GOOGLE_NANO_BANANA_2".equals(adapterId)) {
            normalized.put("modelId", NANO_BANANA_MODEL_ID);
            normalized.put("outputFormat", "image");
            normalized.put("imageSize", NANO_BANANA_IMAGE_SIZE);
        } else if ("VOLC_SEED_AUDIO_1".equals(adapterId)) {
            normalized.put("modelId", "seed-audio-1.0");
            normalized.put("outputFormat", "mp3");
        } else if ("ARK_SEEDANCE_2_I2V".equals(adapterId)) {
            normalized.put("modelId", "doubao-seedance-2-0-260128");
            normalized.put("outputFormat", "mp4");
            normalized.put("generateAudio", false);
        }
        ObjectNode settings = normalized.putObject("settings");
        JsonNode source = suppliedSettings == null ? mapper.createObjectNode() : suppliedSettings;
        if (!source.isObject()) throw invalid("能力模板参数必须为对象");
        if (MediaAdapterRegistry.RUNNINGHUB_ADAPTERS.contains(adapterId)) {
            if (!java.util.Set.of("runningHub", "pricing").containsAll(source.propertyNames()))
                throw invalid("RunningHub 能力只接受参数定义与估算价格");
            var definition = RunningHubDefinition.parse(mapper, source.get("runningHub"), declaration.kind());
            settings.set("runningHub", mapper.valueToTree(definition));
            ObjectNode price = mapper.createObjectNode();
            if (source.has("pricing")) price.set("pricing", source.get("pricing"));
            MediaCapabilityConfiguration.normalize(mapper, declaration, price, settings);
            return normalized.toString();
        }
        List<String> fields = switch (adapterId) {
            case "COMFY_IMAGE_V1" -> List.of("checkpoint");
            case "COMFY_VIDEO_V1" -> List.of("diffusionModel", "textEncoder", "vae",
                    "clipVision");
            case "OPENAI_GPT_IMAGE_2" -> List.of("quality", "model");
            case "GOOGLE_NANO_BANANA_2" -> List.of("model");
            default -> List.of();
        };
        for (String field : source.propertyNames()) {
            if (!fields.contains(field) && !MediaCapabilityConfiguration.FIELDS.contains(field))
                throw invalid("能力模板包含不允许的参数");
        }
        for (String field : fields) {
            JsonNode value = source.path(field);
            if ("quality".equals(field)) {
                String quality = value.isMissingNode() ? "medium" : value.asText();
                if (!List.of("low", "medium", "high").contains(quality)) {
                    throw invalid("GPT Image 2 质量只能为 low、medium 或 high");
                }
                settings.put(field, quality);
                continue;
            }
            // 模型名留空表示沿用适配器内置默认；中转站命名格式无法预知，只挡明显非法的取值。
            if ("model".equals(field)) {
                String model = value.isMissingNode() || value.isNull() ? "" : value.asText();
                if (!model.isEmpty() && !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}")) {
                    throw invalid("模型名只能包含字母、数字及 . _ : / -，且不超过 120 字符");
                }
                settings.put(field, model);
                continue;
            }
            if (!value.isTextual() || !value.asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                    || value.asText().contains("..") || !value.asText().endsWith(".safetensors")) {
                throw invalid("ComfyUI 模板模型文件名必须为 .safetensors 文件名");
            }
            settings.put(field, value.asText());
        }
        MediaCapabilityConfiguration.normalize(mapper, declaration, source, settings);
        var policy = MediaCapabilityConfiguration.policy(declaration, settings);
        normalized.put("minimumSeconds", policy.minimumSeconds());
        normalized.put("maximumSeconds", policy.maximumSeconds());
        normalized.put("maxReferenceImages", policy.maxReferenceImages());
        return normalized.toString();
    }

    @Transactional
    public Connection setConnectionEnabled(UUID connectionId, long expectedVersion,
            boolean enabled) {
        rejectSystemManaged(getConnection(connectionId));
        if (!repository.updateConnectionEnabled(connectionId, expectedVersion, enabled,
                clock.instant())) {
            throw conflict("连接已被其他操作修改");
        }
        return getConnection(connectionId);
    }

    public long defaultVersion(Task.Kind kind) {
        return repository.defaultVersion(requireMediaKind(kind).name());
    }

    public UUID defaultCapabilityId(Task.Kind kind) {
        return repository.defaultCapabilityId(requireMediaKind(kind).name());
    }

    /** Immutable administrator limits within the protocol pinned by this task. */
    public MediaAdapterRegistry.Declaration inputPolicy(MediaCapabilityBinding binding) {
        return inputPolicy(pinnedSnapshot(binding));
    }

    public MediaAdapterRegistry.Declaration inputPolicy(Snapshot snapshot) {
        return MediaCapabilityConfiguration.policy(registry.declaration(snapshot.adapterId()),
                mapper.readTree(snapshot.specJson()).path("settings"));
    }

    public RunningHubDefinition runningHubDefinition(MediaCapabilityBinding binding) {
        if (!MediaAdapterRegistry.RUNNINGHUB_ADAPTERS.contains(binding.adapterId())) return null;
        return RunningHubDefinition.parse(mapper, settings(binding).path("runningHub"),
                registry.declaration(binding.adapterId()).kind());
    }

    /** Validates only supplied values so incomplete dynamic drafts can still be persisted. */
    public void validateDynamicDraft(UUID capabilityId, Task.Kind kind, JsonNode parameters,
            String prompt, Integer seconds) {
        if (capabilityId == null) throw invalid("动态参数必须选择明确的能力");
        var definition = runningHubDefinition(forDraft(capabilityId, kind));
        if (definition == null) throw invalid("当前能力不接受动态参数");
        definition.values(mapper, parameters, prompt, seconds, false);
    }

    public JsonNode settings(MediaCapabilityBinding binding) {
        JsonNode settings = mapper.readTree(pinnedSnapshot(binding).specJson()).path("settings");
        return settings.isMissingNode() ? mapper.createObjectNode() : settings;
    }

    /** Missing card fields use the selected version's defaults; explicit card values win. */
    public JsonNode parameters(MediaCapabilityBinding binding, JsonNode draft) {
        ObjectNode parameters = mapper.createObjectNode();
        JsonNode configuration = settings(binding);
        if (configuration.has("quality")) parameters.set("quality", configuration.get("quality"));
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
        Snapshot snapshot = enabledSnapshot(capabilityId);
        if (MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(snapshot.adapterId())) {
            throw invalid("本地图片处理能力不能设为普通图片生成默认能力");
        }
        if (registry.declaration(snapshot.adapterId()).kind() != mediaKind) {
            throw invalid("默认能力与媒体类型不匹配");
        }
        if (!repository.updateDefault(mediaKind.name(), expectedVersion, capabilityId)) {
            throw conflict("默认能力已被其他操作修改");
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
        return repository.connections().stream().filter(Connection::enabled)
                .flatMap(connection -> repository.capabilities(connection.id()).stream())
                .filter(Capability::enabled)
                .map(capability -> repository.snapshot(capability.id()).orElseThrow())
                .filter(snapshot -> !MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(
                        snapshot.adapterId()))
                .filter(snapshot -> supports(snapshot, mediaKind, durationSeconds))
                .map(snapshot -> new Candidate(binding(snapshot), snapshot.connection().name(),
                        snapshot.capability().name(), mediaKind,
                        inputPolicy(snapshot).minimumSeconds(),
                        inputPolicy(snapshot).maximumSeconds(),
                        false, mapper.readTree(snapshot.specJson()).path("settings"))).toList();
    }

    /** Safe published catalog for model planning; duration suitability is checked per step. */
    public List<Candidate> publishedCandidates() {
        return repository.connections().stream().filter(Connection::enabled)
                .flatMap(connection -> repository.capabilities(connection.id()).stream())
                .filter(Capability::enabled)
                .map(capability -> repository.snapshot(capability.id()).orElseThrow())
                .filter(snapshot -> !MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(
                        snapshot.adapterId()))
                .map(snapshot -> {
                    var declaration = inputPolicy(snapshot);
                    return new Candidate(binding(snapshot), snapshot.connection().name(),
                            snapshot.capability().name(), declaration.kind(),
                            declaration.minimumSeconds(), declaration.maximumSeconds(), false,
                            mapper.readTree(snapshot.specJson()).path("settings"));
                }).toList();
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
            throw invalid("能力输出类型与任务类别不匹配");
        }
        if (!skipDuration && !supports(snapshot, kind, durationSeconds)) {
            throw invalid("该能力不支持当前时长，请调整时长");
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
        Snapshot snapshot = repository.snapshot(capabilityId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "MEDIA_CAPABILITY_NOT_FOUND",
                        "媒体能力不存在", "找不到该媒体能力", false));
        if (!snapshot.connection().enabled() || !snapshot.capability().enabled()) {
            throw conflict("媒体连接或能力已停用");
        }
        registry.declaration(snapshot.adapterId());
        return snapshot;
    }

    private static MediaCapabilityBinding binding(Snapshot snapshot) {
        return new MediaCapabilityBinding(snapshot.connection().id(),
                snapshot.connection().currentVersion(), snapshot.capability().id(),
                snapshot.capability().currentVersion(), snapshot.adapterId(),
                snapshot.mappingSha256());
    }

    private static void rejectSystemManaged(Connection connection) {
        if (connection.platform() == MediaPlatform.LOCAL) {
            throw invalid("本地图片处理是应用管理的内置能力，不能修改");
        }
    }

    private static Task.Kind requireMediaKind(Task.Kind kind) {
        if (kind != Task.Kind.IMAGE_GENERATION && kind != Task.Kind.VIDEO_GENERATION && kind != Task.Kind.AUDIO_GENERATION) {
            throw invalid("仅支持图片或视频能力");
        }
        return kind;
    }

    private static String requireName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 160) {
            throw invalid("名称长度必须为 1–160 个字符");
        }
        return name;
    }

    private static MediaPlatform requirePlatform(String value) {
        try {
            return MediaPlatform.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException unknownPlatform) {
            throw invalid("不支持的平台类型");
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
                    throw invalid("Base URL 必须是公开的 HTTPS API 根地址，不能包含凭证、查询或片段");
                }
                // 本机回环是自托管服务与本地假 API 的固定例外，与 ComfyUI 的规则一致。
                boolean loopback = "http".equals(uri.getScheme())
                        && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0;
                if (!"https".equals(uri.getScheme()) && !loopback) {
                    throw invalid("Base URL 必须是公开的 HTTPS API 根地址，不能包含凭证、查询或片段");
                }
                return uri.toASCIIString().replaceAll("/+$", "");
            } catch (IllegalArgumentException invalidUri) {
                throw invalid("Base URL 无效");
            }
        }
        if (platform != MediaPlatform.COMFYUI) {
            if (origin != null && !origin.isBlank()) {
                throw invalid("该平台使用内置固定端点，不能填写自定义地址");
            }
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(origin == null ? "" : origin);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !"".equals(uri.getRawPath())) {
                throw invalid("ComfyUI 仅允许精确的本机 127.0.0.1 地址和端口");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalidUri) {
            throw invalid("ComfyUI 地址无效");
        }
    }

    private static void validateCredential(MediaPlatform platform, String apiKey, boolean creating) {
        boolean cloud = platform == MediaPlatform.OPENAI || platform == MediaPlatform.ARK
                || platform == MediaPlatform.GOOGLE || platform == MediaPlatform.VOLCENGINE
                || platform == MediaPlatform.RUNNINGHUB;
        if (cloud && creating && (apiKey == null || apiKey.isBlank())) {
            throw invalid("云平台连接必须填写 API Key");
        }
        if (!cloud && apiKey != null && !apiKey.isBlank()) {
            throw invalid("该平台不接受 API Key");
        }
    }

    private static String keyMask(String key) {
        return key == null || key.isBlank() ? null
                : "••••" + key.substring(Math.max(0, key.length() - 4));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PROVIDER_UNSUPPORTED_INPUT", "媒体能力不支持该输入", detail, false);
    }

    private static ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "MEDIA_CAPABILITY_CONFLICT",
                "媒体配置冲突", detail, false);
    }
}
