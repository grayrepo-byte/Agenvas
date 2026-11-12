package dev.agenvas.settings.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;

/** Named connection heads are editable; accepted work always retains its immutable global version. */
@Service
public class LlmProviderConfigService {
    private final LlmProviderConfigRepository configs;
    private final CredentialCipher cipher;
    private final LlmEndpointPolicy endpoints;
    private final Clock clock;
    private final LlmModelDiscoveryService discovery;
    private final TransactionTemplate writes;

    public LlmProviderConfigService(LlmProviderConfigRepository configs, CredentialCipher cipher,
            LlmEndpointPolicy endpoints, Clock clock, LlmModelDiscoveryService discovery,
            PlatformTransactionManager transactions) {
        this.configs = configs; this.cipher = cipher; this.endpoints = endpoints; this.clock = clock;
        this.discovery = discovery; this.writes = new TransactionTemplate(transactions);
    }

    @Transactional(readOnly = true)
    public Status status() {
        return configs.active().map(this::publicStatus).orElse(new Status(false, 0, null, null, null, false, null));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Connections connections() {
        var active = configs.active();
        var entries = configs.connections();
        UUID activeId = active.flatMap(config -> entries.stream()
                .filter(entry -> entry.currentConfigVersion() == config.version()).findFirst()).map(LlmConnection::id).orElse(null);
        return new Connections(entries.stream().map(this::publicConnection).toList(), activeId, active.map(LlmProviderConfig::version).orElse(0));
    }

    @Transactional(readOnly = true)
    public ConnectionStatus connection(UUID id) {
        return publicConnection(configs.findConnection(id).orElseThrow(this::notFound));
    }

    public Status replace(int expectedVersion, String endpoint, String model, String key) {
        return replace(expectedVersion, endpoint, model, key, null);
    }

    /** Legacy endpoint still edits the active connection; inactive saves do not invalidate its CAS. */
    public Status replace(int expectedVersion, String endpoint, String model, String key, LlmModelDiscoveryService.Selection selection) {
        if (expectedVersion < 0) throw invalid();
        var before = configs.active().orElse(null);
        if ((before == null ? 0 : before.version()) != expectedVersion) throw changed();
        var prepared = prepare(null, expectedVersion, before, endpoint, model, key, selection);
        return writes.execute(ignored -> {
            int counter = configs.lockVersion();
            var active = configs.active().orElse(null);
            if ((active == null ? 0 : active.version()) != expectedVersion) throw changed();
            var head = active == null ? null : configs.connectionForVersion(active.version()).orElseThrow(this::changed);
            UUID connectionId = head == null ? UUID.randomUUID() : head.id();
            var next = snapshot(counter, prepared, active != null && active.toolCallingVerified() && prepared.matchesBefore(), true);
            moveHead(connectionId, head, head == null ? prepared.model().substring(0, Math.min(120, prepared.model().length())) : head.name(), next);
            return publicStatus(next);
        });
    }

    /** Endpoint DNS validation and preview resolution finish before the short database write transaction. */
    public ConnectionStatus saveConnection(UUID id, String requestedName, int expectedVersion, String endpoint,
            String model, String key, LlmModelDiscoveryService.Selection selection) {
        String name = validateName(requestedName);
        var head = configs.findConnection(id).orElse(null);
        if (expectedVersion < 0 || (head == null ? 0 : head.currentConfigVersion()) != expectedVersion) throw changed();
        var before = head == null ? null : configs.findVersion(head.currentConfigVersion()).orElseThrow(this::changed);
        var prepared = prepare(id, expectedVersion, before, endpoint, model, key, selection);
        return writes.execute(ignored -> {
            int counter = configs.lockVersion();
            var current = configs.findConnection(id).orElse(null);
            if ((current == null ? 0 : current.currentConfigVersion()) != expectedVersion) throw changed();
            boolean active = current != null && configs.active().map(value -> value.version() == current.currentConfigVersion()).orElse(false);
            // Verification may have completed after preparing the draft; read it under the allocator lock.
            var currentConfig = current == null ? null : configs.findVersion(current.currentConfigVersion()).orElseThrow(this::changed);
            var next = snapshot(counter, prepared, currentConfig != null && currentConfig.toolCallingVerified() && prepared.matchesBefore(), active);
            return publicConnection(moveHead(id, current, name, next));
        });
    }

    /** Activation allocates a fresh global snapshot so switching away and back cannot cause version ABA. */
    public Connections activate(UUID id, int expectedVersion, int expectedActiveVersion) {
        if (expectedVersion < 1 || expectedActiveVersion < 0) throw invalid();
        return writes.execute(ignored -> {
            int counter = configs.lockVersion();
            var head = configs.findConnection(id).orElseThrow(this::notFound);
            var active = configs.active().orElse(null);
            if (head.currentConfigVersion() != expectedVersion || (active == null ? 0 : active.version()) != expectedActiveVersion) throw changed();
            if (active != null && active.version() == head.currentConfigVersion()) return connections();
            var source = configs.findVersion(head.currentConfigVersion()).orElseThrow(this::changed);
            var next = snapshot(counter, new Prepared(source.endpoint(), source.modelId(), decrypt(source), source.modelPolicy(), true), source.toolCallingVerified(), true);
            moveHead(id, head, head.name(), next);
            return connections();
        });
    }

    private Prepared prepare(UUID connectionId, int expectedVersion, LlmProviderConfig before, String requestedEndpoint,
            String requestedModel, String requestedKey, LlmModelDiscoveryService.Selection selection) {
        String endpoint = endpoints.normalize(requestedEndpoint);
        String model = validateModelId(requestedModel);
        if (requestedKey != null && requestedKey.length() > 4096) throw invalid();
        boolean retainKey = requestedKey == null || requestedKey.isBlank();
        if (retainKey && before == null) throw invalid();
        String key = retainKey ? decrypt(before) : validateApiKey(requestedKey);
        boolean sameTarget = before != null && endpoint.equals(before.endpoint()) && model.equals(before.modelId());
        boolean sameKey = before != null && (retainKey || matchesCredential(before, key));
        LlmModelPolicy policy = selection == null && sameTarget && sameKey ? before.modelPolicy() : null;
        try { if (selection != null) policy = discovery.resolve(connectionId, expectedVersion, endpoint, model, key, selection); }
        catch (IllegalArgumentException rejected) {
            String code = java.util.Set.of("MODEL_PREVIEW_EXPIRED", "MODEL_PREVIEW_INVALID", "MODEL_POLICY_INVALID", "MODEL_CAPACITY_UNKNOWN")
                    .contains(rejected.getMessage()) ? rejected.getMessage() : "MODEL_POLICY_INVALID";
            throw new ApiProblemException("MODEL_PREVIEW_EXPIRED".equals(code) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST,
                    code, ApiMessage.of("api.model-metadata.title"), ApiMessage.of("api.model-metadata.refresh"), false);
        }
        return new Prepared(endpoint, model, key, policy, sameTarget && sameKey && Objects.equals(policy, before.modelPolicy()));
    }

    private LlmProviderConfig snapshot(int counter, Prepared prepared, boolean verified, boolean activate) {
        int version = Math.addExact(counter, 1);
        UUID id = UUID.randomUUID();
        var encrypted = cipher.encrypt(id, version, prepared.key());
        var config = new LlmProviderConfig(id, version, prepared.endpoint(), prepared.model(), encrypted.ciphertext(),
                encrypted.nonce(), encrypted.keyVersion(), "••••" + prepared.key().substring(prepared.key().length() - 4),
                verified, activate, clock.instant(), prepared.policy());
        configs.insertSnapshot(counter, config, activate);
        return config;
    }

    private LlmConnection moveHead(UUID id, LlmConnection before, String name, LlmProviderConfig next) {
        var head = new LlmConnection(id, name, next.version(), before == null ? next.createdAt() : before.createdAt(), next.createdAt());
        if (before == null) configs.insertConnection(head);
        else if (!configs.updateConnection(id, before.currentConfigVersion(), name, next.version(), next.createdAt())) throw changed();
        return head;
    }

    private String decrypt(LlmProviderConfig config) {
        return cipher.decrypt(config.id(), config.version(), new CredentialCipher.Encrypted(config.credentialCiphertext(), config.credentialNonce(), config.keyVersion()));
    }
    /** Comparing an explicitly supplied replacement key must not block repair of an unreadable old credential. */
    private boolean matchesCredential(LlmProviderConfig before, String key) {
        try { return key.equals(decrypt(before)); }
        catch (ApiProblemException unavailable) {
            if ("CREDENTIAL_KEY_VERSION_MISSING".equals(unavailable.code())) return false;
            throw unavailable;
        } catch (IllegalStateException damagedCredential) { return false; }
    }
    private Status publicStatus(LlmProviderConfig config) {
        return new Status(true, config.version(), config.endpoint(), config.modelId(), config.keyMask(), config.toolCallingVerified(), config.createdAt(), config.modelPolicy());
    }
    private ConnectionStatus publicConnection(LlmConnection head) {
        var config = configs.findVersion(head.currentConfigVersion()).orElseThrow(this::changed);
        return new ConnectionStatus(head.id(), head.name(), config.version(), config.endpoint(), config.modelId(), config.keyMask(), config.toolCallingVerified(), head.updatedAt(), config.modelPolicy());
    }
    private String validateName(String requested) {
        if (requested == null || requested.isBlank() || requested.strip().length() > 120 || requested.chars().anyMatch(Character::isISOControl)) throw invalid();
        return requested.strip();
    }
    private String validateModelId(String requested) {
        if (requested == null || !requested.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")) throw invalid();
        return requested;
    }
    private String validateApiKey(String requested) {
        if (requested == null || requested.length() < 8 || requested.length() > 4096 || !requested.matches("[!-~]+")) throw invalid();
        return requested;
    }
    private ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "PROVIDER_CONFIG_INVALID", ApiMessage.of("api.llm-provider-config-service.invalid-model-configuration"),
                ApiMessage.of("api.llm-provider-config-service.please-provide-valid-server-side-model-credentials"), false);
    }
    private ApiProblemException changed() {
        return new ApiProblemException(HttpStatus.CONFLICT, "PROVIDER_CONFIG_VERSION_CONFLICT", ApiMessage.of("api.llm-provider-config-service.model-configuration-has-changed"),
                ApiMessage.of("api.llm-provider-config-service.please-re-read-the-model-configuration-before-saving"), false);
    }
    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "PROVIDER_CONNECTION_NOT_FOUND", ApiMessage.of("api.llm-provider-config-service.invalid-model-configuration"),
                ApiMessage.of("api.llm-provider-config-service.please-re-read-the-model-configuration-before-saving"), false);
    }
    /** Never log this prepared command; it briefly holds the effective server-side credential. */
    private record Prepared(String endpoint, String model, String key, LlmModelPolicy policy, boolean matchesBefore) {
        @Override public String toString() { return "PreparedLlmConfiguration[redacted]"; }
    }
    public record Connections(List<ConnectionStatus> items, UUID activeConnectionId, int activeVersion) {}
    public record ConnectionStatus(UUID id, String name, int version, String endpoint, String modelId, String keyMask,
            boolean toolCallingVerified, Instant updatedAt, LlmModelPolicy modelPolicy) {}
    public record Status(boolean configured, int version, String endpoint, String modelId, String keyMask,
            boolean toolCallingVerified, Instant updatedAt, LlmModelPolicy modelPolicy) {
        public Status(boolean configured, int version, String endpoint, String modelId, String keyMask,
                boolean toolCallingVerified, Instant updatedAt) {
            this(configured, version, endpoint, modelId, keyMask, toolCallingVerified, updatedAt, null);
        }
    }
}
