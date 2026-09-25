package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.infrastructure.JdbcMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JdbcMediaCapabilityRepository.Capability;
import dev.agenvas.provider.infrastructure.JdbcMediaCapabilityRepository.Connection;
import dev.agenvas.provider.infrastructure.JdbcMediaCapabilityRepository.ConnectionVersion;
import dev.agenvas.provider.infrastructure.JdbcMediaCapabilityRepository.Snapshot;
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

/** Owns the media catalog; the returned binding freezes both published versions. */
@Service
public class MediaCapabilityService {

    private final JdbcMediaCapabilityRepository repository;
    private final MediaAdapterRegistry registry;
    private final CredentialCipher cipher;
    private final Clock clock;

    public MediaCapabilityService(JdbcMediaCapabilityRepository repository,
            MediaAdapterRegistry registry, CredentialCipher cipher, Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Transactional
    public Connection createConnection(String name, String origin) {
        String normalizedName = requireName(name);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        repository.insertConnection(id, normalizedName,
                origin == null ? "MOCK" : "COMFYUI", origin,
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
        String normalizedPlatform = requirePlatform(platform);
        String normalizedOrigin = validatedOrigin(normalizedPlatform, origin);
        validateCredential(normalizedPlatform, apiKey, true);
        String hash = sha256(normalizedName + "\u0000" + normalizedPlatform + "\u0000"
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
        if (current.version() != expectedVersion) {
            throw conflict("连接已被其他操作修改");
        }
        ConnectionVersion previous = getConnectionVersion(id, current.currentVersion())
                .orElseThrow();
        String normalizedOrigin = validatedOrigin(current.platform(), origin);
        validateCredential(current.platform(), apiKey, false);
        boolean newVersion = !java.util.Objects.equals(previous.origin(), normalizedOrigin)
                || apiKey != null && !apiKey.isBlank();
        if (newVersion && previous.credentialCiphertext() != null
                && (apiKey == null || apiKey.isBlank())) {
            throw invalid("修改连接版本时必须重新输入 API Key");
        }
        int nextVersion = current.currentVersion() + (newVersion ? 1 : 0);
        Instant now = clock.instant();
        if (!repository.updateConnection(id, expectedVersion, requireName(name),
                enabled, nextVersion, now)) {
            throw conflict("连接已被其他操作修改");
        }
        if (newVersion) {
            CredentialCipher.Encrypted encrypted = apiKey == null || apiKey.isBlank()
                    ? null : cipher.encryptMedia(id, nextVersion, apiKey);
            repository.insertConnectionVersion(id, nextVersion, normalizedOrigin,
                    normalizedOrigin == null ? null : sha256(normalizedOrigin),
                    encrypted == null ? null : encrypted.ciphertext(),
                    encrypted == null ? null : encrypted.nonce(),
                    encrypted == null ? null : encrypted.keyVersion(),
                    encrypted == null ? null : keyMask(apiKey), now);
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
        return publishCapabilityWithId(UUID.randomUUID(), connectionId, name, adapterId);
    }

    @Transactional
    public Capability publishCapability(String idempotencyKey, UUID connectionId,
            String name, String adapterId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 160) {
            throw invalid("必须提供有效的 Idempotency-Key");
        }
        String normalizedName = requireName(name);
        String hash = sha256(connectionId + "\u0000" + normalizedName + "\u0000" + adapterId);
        UUID id = UUID.randomUUID();
        if (!repository.claimCapabilityCreateKey(idempotencyKey, hash, id, clock.instant())) {
            var previous = repository.capabilityCreateKey(idempotencyKey).orElseThrow();
            if (!previous.payloadSha256().equals(hash)) {
                throw conflict("相同 Idempotency-Key 对应不同能力内容");
            }
            return repository.capability(previous.entityId()).orElseThrow();
        }
        return publishCapabilityWithId(id, connectionId, normalizedName, adapterId);
    }

    private Capability publishCapabilityWithId(UUID id, UUID connectionId,
            String name, String adapterId) {
        Connection connection = getConnection(connectionId);
        MediaAdapterRegistry.Declaration declaration = registry.declaration(adapterId);
        if (!connection.enabled()) {
            throw conflict("连接已停用");
        }
        ConnectionVersion version = getConnectionVersion(connectionId,
                connection.currentVersion()).orElseThrow();
        if (declaration.originRequired() && version.origin() == null) {
            throw invalid("该适配器需要连接地址");
        }
        if (!declaration.originRequired() && version.origin() != null) {
            throw invalid("该适配器不接受连接地址");
        }
        if (!connection.platform().equals(declaration.platform())) {
            throw invalid("适配器与平台连接不匹配");
        }
        String spec = "{\"schemaVersion\":1,\"kind\":\"" + declaration.kind().name()
                + "\",\"minimumSeconds\":" + declaration.minimumSeconds()
                + ",\"maximumSeconds\":" + declaration.maximumSeconds() + "}";
        repository.insertCapability(id, connectionId, requireName(name), adapterId,
                sha256(adapterId + ":v1"), spec, clock.instant());
        return repository.capability(id).orElseThrow();
    }

    @Transactional
    public Capability updateCapability(UUID connectionId, UUID capabilityId,
            long expectedVersion, String name, boolean enabled, String adapterId) {
        Snapshot current = capabilitySnapshot(capabilityId);
        if (!current.connection().id().equals(connectionId)) {
            throw invalid("能力不属于此连接");
        }
        if (current.capability().version() != expectedVersion) {
            throw conflict("能力已被其他操作修改");
        }
        MediaAdapterRegistry.Declaration replacement = registry.declaration(adapterId);
        if (!current.connection().platform().equals(replacement.platform())) {
            throw invalid("适配器与平台连接不匹配");
        }
        if (registry.declaration(current.adapterId()).kind() != replacement.kind()) {
            throw invalid("能力的输出类型不可变；请发布新能力");
        }
        boolean newVersion = !current.adapterId().equals(adapterId);
        int nextVersion = current.capability().currentVersion() + (newVersion ? 1 : 0);
        Instant now = clock.instant();
        if (!repository.updateCapability(capabilityId, expectedVersion, requireName(name),
                enabled, nextVersion, now)) {
            throw conflict("能力已被其他操作修改");
        }
        if (newVersion) {
            MediaAdapterRegistry.Declaration declaration = replacement;
            repository.insertCapabilityVersion(capabilityId, nextVersion, adapterId,
                    sha256(adapterId + ":v1"), "{\"schemaVersion\":1,\"kind\":\""
                            + declaration.kind().name() + "\"}", now);
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

    @Transactional
    public Connection setConnectionEnabled(UUID connectionId, long expectedVersion,
            boolean enabled) {
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

    @Transactional
    public MediaCapabilityBinding setDefault(Task.Kind kind, long expectedVersion,
            UUID capabilityId) {
        Task.Kind mediaKind = requireMediaKind(kind);
        Snapshot snapshot = enabledSnapshot(capabilityId);
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

    public MediaCapabilityBinding resolve(UUID capabilityId, Task.Kind kind,
            int durationSeconds) {
        return resolve(capabilityId, requireMediaKind(kind), durationSeconds, false);
    }

    private MediaCapabilityBinding resolve(UUID capabilityId, Task.Kind kind,
            int durationSeconds, boolean skipDuration) {
        Snapshot snapshot = enabledSnapshot(capabilityId);
        if (registry.declaration(snapshot.adapterId()).kind() != kind) {
            throw invalid("能力输出类型与计划步骤不匹配");
        }
        if (!skipDuration && !registry.supports(snapshot.adapterId(),
                new PortInput(kind, durationSeconds, null))) {
            throw invalid("该能力不支持当前镜头时长，请调整镜头时长");
        }
        return binding(snapshot);
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

    private static Task.Kind requireMediaKind(Task.Kind kind) {
        if (kind != Task.Kind.IMAGE_GENERATION && kind != Task.Kind.VIDEO_GENERATION) {
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

    private static String requirePlatform(String value) {
        if (!"MOCK".equals(value) && !"COMFYUI".equals(value)
                && !"OPENAI".equals(value) && !"ARK".equals(value)) {
            throw invalid("不支持的平台类型");
        }
        return value;
    }

    private static String validatedOrigin(String platform, String origin) {
        if (!"COMFYUI".equals(platform)) {
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

    private static void validateCredential(String platform, String apiKey, boolean creating) {
        boolean cloud = "OPENAI".equals(platform) || "ARK".equals(platform);
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
