package dev.agenvas.asset.storage;

import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ApiProblemException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrator-owned destinations. Location fields are immutable; credentials can rotate. */
@Service
public class StorageSettingsService {
    private static final int FIRST_CREDENTIAL_VERSION = 1;
    private static final int NAME_MAX_LENGTH = 120;
    private static final int PREFIX_MAX_LENGTH = 120;
    private static final int VISIBLE_KEY_SUFFIX_LENGTH = 4;
    private final StorageRepository repository;
    private final CredentialCipher cipher;
    private final Clock clock;

    public StorageSettingsService(StorageRepository repository, CredentialCipher cipher, Clock clock) {
        this.repository = repository; this.cipher = cipher; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Status status() {
        var state = repository.state();
        return new Status(state.version(), state.activeProfileId(), repository.profiles().stream()
                .map(p -> new ProfileStatus(p.id(), p.name(), p.provider(), p.endpoint(), p.region(),
                        p.bucket(), p.keyPrefix(), p.pathStyle(), p.accessKeyMask(), p.createdAt())).toList());
    }

    @Transactional
    public Status create(int expectedVersion, String name, StorageProfile.Provider provider,
            String endpoint, String region, String bucket, String prefix, boolean pathStyle,
            String accessKeyId, String secretAccessKey) {
        lock(expectedVersion);
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > NAME_MAX_LENGTH) throw invalid("请输入不超过 120 字的连接名称。");
        if (provider == null) throw invalid("请选择存储类型。");
        endpoint = normalizeEndpoint(endpoint);
        if (region == null || !region.matches("[a-z0-9][a-z0-9-]{0,79}")) throw invalid("Region 格式无效。");
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..") || bucket.matches("[0-9.]+")) throw invalid("Bucket 名称格式无效。");
        prefix = prefix == null ? "" : prefix.trim().replaceAll("^/+|/+$", "");
        if (prefix.length() > PREFIX_MAX_LENGTH || !prefix.matches("[A-Za-z0-9_/-]*")
                || prefix.contains("//")) throw invalid("对象前缀只支持字母、数字、下划线、短横线与目录分隔符。");
        if (provider != StorageProfile.Provider.S3 && pathStyle) throw invalid("OSS 与 COS 使用虚拟主机寻址。");
        UUID id = UUID.randomUUID();
        var encrypted = cipher.encryptStorage(id, FIRST_CREDENTIAL_VERSION, credentials(accessKeyId, secretAccessKey));
        repository.insertProfile(new StorageProfile(id, name, provider, endpoint, region, bucket, prefix,
                pathStyle, FIRST_CREDENTIAL_VERSION, encrypted, mask(accessKeyId), clock.instant()));
        repository.advance(expectedVersion, repository.state().activeProfileId());
        return status();
    }

    @Transactional
    public Status activate(int expectedVersion, UUID profileId) {
        lock(expectedVersion);
        if (profileId != null) requireProfile(profileId);
        repository.advance(expectedVersion, profileId);
        return status();
    }

    @Transactional
    public Status rotate(int expectedVersion, UUID profileId, String accessKeyId, String secretAccessKey) {
        lock(expectedVersion);
        StorageProfile profile = requireProfile(profileId);
        int revision = Math.addExact(profile.credentialVersion(), 1);
        var encrypted = cipher.encryptStorage(profileId, revision, credentials(accessKeyId, secretAccessKey));
        repository.rotate(profileId, revision, encrypted, mask(accessKeyId));
        repository.advance(expectedVersion, repository.state().activeProfileId());
        return status();
    }

    public StorageProfile requireProfile(UUID id) {
        return repository.profile(id).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "STORAGE_PROFILE_NOT_FOUND", "存储连接不存在", "找不到该存储连接。", false));
    }

    String[] credentials(StorageProfile profile) {
        return cipher.decryptStorage(profile.id(), profile.credentialVersion(), profile.credentials()).split("\n", 2);
    }

    private void lock(int expectedVersion) {
        if (expectedVersion < 0) throw invalid("配置版本无效。");
        if (repository.lockVersion() != expectedVersion) throw new ApiProblemException(HttpStatus.CONFLICT,
                "STORAGE_VERSION_CONFLICT", "存储配置已变化", "请刷新配置后重试，当前输入已保留。", false);
    }

    static String normalizeEndpoint(String requested) {
        try {
            URI uri = URI.create(requested == null ? "" : requested.trim());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw invalid("Endpoint 必须是无路径、凭证和查询参数的 HTTPS 地址。");
            return uri.toString().replaceAll("/+$", "");
        } catch (IllegalArgumentException failure) { throw invalid("Endpoint 格式无效。"); }
    }

    private String credentials(String id, String secret) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{4,128}") || secret == null
                || !secret.matches("[\\x21-\\x7E]{8,4096}")) throw invalid("请输入有效的 AccessKey ID 与 Secret。");
        return id + "\n" + secret;
    }
    private String mask(String id) { return "••••" + id.substring(id.length() - VISIBLE_KEY_SUFFIX_LENGTH); }
    static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "STORAGE_CONFIG_INVALID", "存储配置无效", detail, false);
    }
    public record Status(int version, UUID activeProfileId, List<ProfileStatus> profiles) {}
    public record ProfileStatus(UUID id, String name, StorageProfile.Provider provider, String endpoint,
            String region, String bucket, String keyPrefix, boolean pathStyle, String accessKeyMask, Instant createdAt) {}
}
