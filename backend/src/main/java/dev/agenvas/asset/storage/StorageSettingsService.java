package dev.agenvas.asset.storage;

import dev.agenvas.shared.i18n.ApiMessage;
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
        return new Status(state.version(), state.activeProfileId(), state.relayProfileId(), repository.profiles().stream()
                .map(p -> new ProfileStatus(p.id(), p.name(), p.provider(), p.endpoint(), p.region(),
                        p.bucket(), p.keyPrefix(), p.pathStyle(), p.accessKeyMask(), p.createdAt())).toList());
    }

    @Transactional
    public Status create(int expectedVersion, String name, StorageProfile.Provider provider,
            String endpoint, String region, String bucket, String prefix, boolean pathStyle,
            String accessKeyId, String secretAccessKey) {
        lock(expectedVersion);
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > NAME_MAX_LENGTH) throw invalid(ApiMessage.of("api.storage-settings-service.please-enter-a-connection-name-of-no-more-than-120"));
        if (provider == null) throw invalid(ApiMessage.of("api.storage-settings-service.please-select-a-storage-type"));
        endpoint = normalizeEndpoint(endpoint);
        if (region == null || !region.matches("[a-z0-9][a-z0-9-]{0,79}")) throw invalid(ApiMessage.of("api.storage-settings-service.region-format-is-invalid"));
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..") || bucket.matches("[0-9.]+")) throw invalid(ApiMessage.of("api.storage-settings-service.bucket-name-format-is-invalid"));
        prefix = prefix == null ? "" : prefix.trim().replaceAll("^/+|/+$", "");
        if (prefix.length() > PREFIX_MAX_LENGTH || !prefix.matches("[A-Za-z0-9_/-]*")
                || prefix.contains("//")) throw invalid(ApiMessage.of("api.storage-settings-service.object-prefixes-only-support-letters-numbers-underscores-dashes-and-directory"));
        if (provider != StorageProfile.Provider.S3 && pathStyle) throw invalid(ApiMessage.of("api.storage-settings-service.oss-and-cos-use-virtual-host-addressing"));
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

    /** Selecting a relay never changes where new or existing assets are archived. */
    @Transactional
    public Status activateRelay(int expectedVersion, UUID profileId) {
        lock(expectedVersion);
        if (profileId != null) requireProfile(profileId);
        repository.advanceRelay(expectedVersion, profileId);
        return status();
    }

    public UUID relayProfileId() { return repository.state().relayProfileId(); }

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
                "STORAGE_PROFILE_NOT_FOUND", ApiMessage.of("api.storage-settings-service.storage-connection-does-not-exist"), ApiMessage.of("api.storage-settings-service.the-storage-connection-cannot-be-found"), false));
    }

    String[] credentials(StorageProfile profile) {
        return cipher.decryptStorage(profile.id(), profile.credentialVersion(), profile.credentials()).split("\n", 2);
    }

    private void lock(int expectedVersion) {
        if (expectedVersion < 0) throw invalid(ApiMessage.of("api.storage-settings-service.the-configuration-version-is-invalid"));
        if (repository.lockVersion() != expectedVersion) throw new ApiProblemException(HttpStatus.CONFLICT,
                "STORAGE_VERSION_CONFLICT", ApiMessage.of("api.storage-settings-service.storage-configuration-has-changed"), ApiMessage.of("api.storage-settings-service.please-refresh-the-configuration-and-try-again-the-current-input"), false);
    }

    static String normalizeEndpoint(String requested) {
        try {
            URI uri = URI.create(requested == null ? "" : requested.trim());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw invalid(ApiMessage.of("api.storage-settings-service.the-endpoint-must-be-an-https-address-without-path-credentials"));
            return uri.toString().replaceAll("/+$", "");
        } catch (IllegalArgumentException failure) { throw invalid(ApiMessage.of("api.storage-settings-service.endpoint-format-is-invalid")); }
    }

    private String credentials(String id, String secret) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{4,128}") || secret == null
                || !secret.matches("[\\x21-\\x7E]{8,4096}")) throw invalid(ApiMessage.of("api.storage-settings-service.please-enter-a-valid-accesskey-id-and-secret"));
        return id + "\n" + secret;
    }
    private String mask(String id) { return "••••" + id.substring(id.length() - VISIBLE_KEY_SUFFIX_LENGTH); }
    static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "STORAGE_CONFIG_INVALID", ApiMessage.of("api.storage-settings-service.invalid-storage-configuration"), detail, false);
    }
    public record Status(int version, UUID activeProfileId, UUID relayProfileId, List<ProfileStatus> profiles) {}
    public record ProfileStatus(UUID id, String name, StorageProfile.Provider provider, String endpoint,
            String region, String bucket, String keyPrefix, boolean pathStyle, String accessKeyMask, Instant createdAt) {}
}
