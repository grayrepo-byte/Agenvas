package dev.agenvas.settings.application;

import dev.agenvas.settings.infrastructure.ModelMetadataClient;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;

/** Preview never saves credentials or changes model verification. */
@Service
public class LlmModelDiscoveryService {
    public enum CredentialMode {
        DRAFT, SAVED
    }
    public enum Mode {
        AUTO, MANUAL
    }
    public record PreviewRequest(ModelCatalog.Provider provider, CredentialMode credentialMode,
            Integer expectedVersion, String endpoint, String apiKey, String modelId, UUID connectionId) {
        public PreviewRequest(ModelCatalog.Provider provider, CredentialMode credentialMode,
                Integer expectedVersion, String endpoint, String apiKey, String modelId) {
            this(provider, credentialMode, expectedVersion, endpoint, apiKey, modelId, null);
        }
        @Override
        public String toString() {
            return "ModelPreviewRequest[credentialMode=" + credentialMode + "]";
        }
    }
    public record Preview(UUID id, List<ModelCatalog.Model> models, boolean partial, Instant checkedAt) {
    }
    public record Selection(ModelCatalog.Provider provider, Mode mode, UUID previewId,
            Integer contextWindowTokens, Integer inputLimitTokens, int maxOutputTokens,
            LlmModelPolicy.OutputParameter outputParameter) {
    }
    private final ModelMetadataClient client;
    private final LlmProviderConfigRepository repository;
    private final CredentialCipher cipher;
    private final LlmEndpointPolicy endpoints;
    private final Clock clock;
    private final LinkedHashMap<UUID, Accepted> accepted = new LinkedHashMap<>();
    private record Accepted(ModelCatalog.Provider provider, String endpoint, String credentialDigest, int configVersion,
            UUID connectionId, Preview preview) {
    }
    public LlmModelDiscoveryService(ModelMetadataClient client, LlmProviderConfigRepository repository,
            CredentialCipher cipher, LlmEndpointPolicy endpoints, Clock clock) {
        this.client = client;
        this.repository = repository;
        this.cipher = cipher;
        this.endpoints = endpoints;
        this.clock = clock;
    }
    public Preview preview(PreviewRequest request) {
        if (request == null || request.provider() == null || request.credentialMode() == null
                || request.expectedVersion() == null || request.expectedVersion() < 0)
            throw invalid();
        if (request.apiKey() != null && request.apiKey().length() > 4096)
            throw invalid();
        String endpoint, key;
        LlmProviderConfig target = null;
        if (request.connectionId() != null) {
            var connection = repository.findConnection(request.connectionId());
            if (connection.isPresent())
                target = repository.findVersion(connection.get().currentConfigVersion())
                        .orElseThrow(LlmModelDiscoveryService::invalid);
            if ((target == null ? 0 : target.version()) != request.expectedVersion())
                throw invalid();
        }
        if (request.credentialMode() == CredentialMode.SAVED) {
            if (request.endpoint() != null || request.apiKey() != null || request.expectedVersion() == null)
                throw invalid();
            var config = request.connectionId() == null
                    ? repository.active().orElseThrow(LlmModelDiscoveryService::invalid)
                    : target;
            if (config == null)
                throw invalid();
            if (config.version() != request.expectedVersion()
                    || request.provider() != (config.modelPolicy() == null
                            ? ModelCatalog.Provider.COMPATIBLE
                            : config.modelPolicy().provider()))
                throw invalid();
            endpoint = config.endpoint();
            key = cipher.decrypt(config.id(), config.version(), new CredentialCipher.Encrypted(
                    config.credentialCiphertext(), config.credentialNonce(), config.keyVersion()));
        } else {
            endpoint = canonical(request.endpoint());
            key = request.apiKey();
            if (key == null || key.isBlank()) {
                var config = request.connectionId() == null
                        ? repository.active().orElseThrow(LlmModelDiscoveryService::invalid)
                        : target;
                if (config == null || config.version() != request.expectedVersion())
                    throw invalid();
                key = cipher.decrypt(config.id(), config.version(), new CredentialCipher.Encrypted(
                        config.credentialCiphertext(), config.credentialNonce(), config.keyVersion()));
            }
        }
        if (key == null || key.length() < 8 || key.length() > 4096 || !key.matches("[!-~]+"))
            throw invalid();
        var result = client.fetch(request.provider(), endpoint, key, request.modelId());
        var preview = new Preview(UUID.randomUUID(), result.models(), result.partial(), clock.instant());
        synchronized (accepted) {
            expire();
            if (accepted.size() >= 128)
                accepted.remove(accepted.keySet().iterator().next());
            accepted.put(preview.id(), new Accepted(request.provider(), endpoint, digest(key),
                    request.expectedVersion(), request.connectionId(), preview));
        }
        return preview;
    }
    public LlmModelPolicy resolve(int expectedVersion, String endpoint, String modelId, String key,
            Selection selection) {
        return resolve(null, expectedVersion, endpoint, modelId, key, selection);
    }
    public LlmModelPolicy resolve(UUID connectionId, int expectedVersion, String endpoint, String modelId, String key,
            Selection selection) {
        if (selection == null)
            return null; // Legacy clients/configurations explicitly remain unknown.
        if (selection.provider() == null || selection.mode() == null)
            throw invalid();
        if (selection.mode() == Mode.MANUAL)
            return LlmModelPolicy.manual(selection.provider(), selection.contextWindowTokens(),
                    selection.inputLimitTokens(), selection.maxOutputTokens(), selection.outputParameter());
        if (selection.contextWindowTokens() != null || selection.inputLimitTokens() != null)
            throw invalid();
        Accepted prior;
        synchronized (accepted) {
            expire();
            prior = accepted.get(selection.previewId());
        }
        if (prior == null || prior.provider() != selection.provider() || prior.configVersion() != expectedVersion
                || !prior.endpoint().equals(endpoint)
                || !java.util.Objects.equals(prior.connectionId(), connectionId)
                || !prior.credentialDigest().equals(digest(key)))
            throw expired();
        var model = prior.preview().models().stream().filter(value -> value.id().equals(modelId)).findFirst()
                .orElseThrow(LlmModelDiscoveryService::expired);
        var value = LlmModelPolicy.automatic(selection.provider(), model, selection.maxOutputTokens(),
                selection.outputParameter());
        return new LlmModelPolicy(value.provider(), value.source(), value.contextWindowTokens(),
                value.inputLimitTokens(),
                value.maxOutputTokens(), value.outputParameter(), value.theoreticalContextTokens(),
                value.reportedOutputTokens(),
                value.runtimeContextTokens(), value.modelDigest(), prior.preview().checkedAt());
    }
    private String canonical(String requested) {
        if (requested == null || requested.length() > 500)
            throw invalid();
        URI uri;
        try {
            uri = URI.create(requested.trim());
            endpoints.requireAllowedOrigin(uri);
        } catch (RuntimeException bad) {
            throw invalid();
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://"
                + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + path;
    }
    private void expire() {
        accepted.entrySet()
                .removeIf(entry -> entry.getValue().preview().checkedAt().plusSeconds(600).isBefore(clock.instant()));
    }
    private static String digest(String key) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Credential binding unavailable");
        }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("MODEL_PREVIEW_INVALID");
    }
    private static IllegalArgumentException expired() {
        return new IllegalArgumentException("MODEL_PREVIEW_EXPIRED");
    }
}
