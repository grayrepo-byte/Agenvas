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
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
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
    private final Clock clock;

    public MediaCapabilityService(JdbcMediaCapabilityRepository repository,
            MediaAdapterRegistry registry, Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.clock = clock;
    }

    @Transactional
    public Connection createConnection(String name, String origin) {
        String normalizedName = requireName(name);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        repository.insertConnection(id, normalizedName, origin,
                origin == null ? null : sha256(origin), null, null, null, now);
        return repository.connection(id).orElseThrow();
    }

    @Transactional
    public Capability publishCapability(UUID connectionId, String name, String adapterId) {
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
        UUID id = UUID.randomUUID();
        String spec = "{\"schemaVersion\":1,\"kind\":\"" + declaration.kind().name()
                + "\",\"minimumSeconds\":" + declaration.minimumSeconds()
                + ",\"maximumSeconds\":" + declaration.maximumSeconds() + "}";
        repository.insertCapability(id, connectionId, requireName(name), adapterId,
                sha256(adapterId + ":v1"), spec, clock.instant());
        return repository.capability(id).orElseThrow();
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
