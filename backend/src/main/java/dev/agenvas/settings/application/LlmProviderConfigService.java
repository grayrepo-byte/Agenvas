package dev.agenvas.settings.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrator-owned LLM settings with immutable encrypted versions and CAS updates. */
@Service
public class LlmProviderConfigService {

    private final LlmProviderConfigRepository configs;
    private final CredentialCipher cipher;
    private final LlmEndpointPolicy endpoints;
    private final Clock clock;

    public LlmProviderConfigService(LlmProviderConfigRepository configs,
            CredentialCipher cipher, LlmEndpointPolicy endpoints, Clock clock) {
        this.configs = configs;
        this.cipher = cipher;
        this.endpoints = endpoints;
        this.clock = clock;
    }

    /** The response exposes no ciphertext, full key, nonce, or internal object path. */
    @Transactional(readOnly = true)
    public Status status() {
        return configs.active().map(this::publicStatus)
                .orElse(new Status(false, 0, null, null, null, false, null));
    }

    /** Creates a new version; a changed key must be submitted again over the protected channel. */
    @Transactional
    public Status replace(int expectedVersion, String requestedEndpoint,
            String requestedModelId, String requestedApiKey) {
        if (expectedVersion < 0) throw invalid("配置版本必须为非负数。");
        String endpoint = endpoints.normalize(requestedEndpoint);
        String modelId = validateModelId(requestedModelId);
        String apiKey = validateApiKey(requestedApiKey);
        int currentVersion = configs.lockVersion();
        if (currentVersion != expectedVersion) {
            throw new ApiProblemException(HttpStatus.CONFLICT,
                    "PROVIDER_CONFIG_VERSION_CONFLICT", "模型配置已变化",
                    "请重新读取模型配置后再保存。", false);
        }
        int next = Math.addExact(currentVersion, 1);
        UUID id = UUID.randomUUID();
        CredentialCipher.Encrypted encrypted = cipher.encrypt(id, next, apiKey);
        LlmProviderConfig config = new LlmProviderConfig(id, next, endpoint, modelId,
                encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(),
                "••••" + apiKey.substring(apiKey.length() - 4), false, true,
                clock.instant());
        configs.publish(currentVersion, config);
        return publicStatus(config);
    }

    private Status publicStatus(LlmProviderConfig config) {
        return new Status(true, config.version(), config.endpoint(), config.modelId(),
                config.keyMask(), config.toolCallingVerified(), config.createdAt());
    }

    private String validateModelId(String requested) {
        if (requested == null || !requested.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")) {
            throw invalid("模型 ID 必须是明确的安全名称。");
        }
        return requested;
    }

    private String validateApiKey(String requested) {
        if (requested == null || requested.length() < 8 || requested.length() > 4096
                || !requested.matches("[\\x21-\\x7E]+")) {
            throw invalid("请提供有效的服务端模型凭证。");
        }
        return requested;
    }

    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST,
                "PROVIDER_CONFIG_INVALID", "模型配置无效", detail, false);
    }

    /** Public API payload; encrypted material is deliberately absent. */
    public record Status(boolean configured, int version, String endpoint, String modelId,
            String keyMask, boolean toolCallingVerified, Instant updatedAt) {}
}
