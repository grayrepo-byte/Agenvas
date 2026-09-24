package dev.agenvas.settings.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理员维护的 LLM 配置；每次修改生成加密新版本，并通过 CAS 防止并发覆盖。 */
@Service
public class LlmProviderConfigService {

    /** 读取活动配置并以行锁串行化版本更新。 */
    private final LlmProviderConfigRepository configs;
    /** 加密服务端密钥并按配置 ID、版本绑定认证数据。 */
    private final CredentialCipher cipher;
    /** 规范化并限制 Provider 地址，防止配置进入未允许的网络目标。 */
    private final LlmEndpointPolicy endpoints;
    /** 为新配置版本提供创建时间。 */
    private final Clock clock;

    /** 注入加密、端点策略与版本化配置仓储。 */
    public LlmProviderConfigService(LlmProviderConfigRepository configs,
            CredentialCipher cipher, LlmEndpointPolicy endpoints, Clock clock) {
        this.configs = configs;
        this.cipher = cipher;
        this.endpoints = endpoints;
        this.clock = clock;
    }

    /** 返回安全状态字段，不包含密文、完整密钥、nonce 或内部文件路径。 */
    @Transactional(readOnly = true)
    public Status status() {
        return configs.active().map(this::publicStatus)
                .orElse(new Status(false, 0, null, null, null, false, null));
    }

    /** 校验输入并按预期版本发布新的加密配置；密钥必须通过受保护通道重新提交。 */
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

    /** 将内部配置投影为可公开状态，丢弃所有加密材料。 */
    private Status publicStatus(LlmProviderConfig config) {
        return new Status(true, config.version(), config.endpoint(), config.modelId(),
                config.keyMask(), config.toolCallingVerified(), config.createdAt());
    }

    /** 限制模型 ID 为允许的 ASCII 名称字符及长度。 */
    private String validateModelId(String requested) {
        if (requested == null || !requested.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")) {
            throw invalid("模型 ID 必须是明确的安全名称。");
        }
        return requested;
    }

    /** 要求凭证为长度受限的可打印 ASCII 文本，避免控制字符进入配置。 */
    private String validateApiKey(String requested) {
        if (requested == null || requested.length() < 8 || requested.length() > 4096
                || !requested.matches("[\\x21-\\x7E]+")) {
            throw invalid("请提供有效的服务端模型凭证。");
        }
        return requested;
    }

    /** 构造端点、模型 ID 或凭证未满足配置契约时的 400 响应。 */
    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST,
                "PROVIDER_CONFIG_INVALID", "模型配置无效", detail, false);
    }

    /** 返回给管理员的安全配置视图，不包含密钥或加密材料。
     * @param configured 是否已保存活动配置
     * @param version 当前不可变配置版本
     * @param endpoint 规范化后的 Provider 地址
     * @param modelId 当前模型标识
     * @param keyMask 仅用于识别密钥的遮罩后缀
     * @param toolCallingVerified 工具调用往返是否通过显式诊断
     * @param updatedAt 当前配置创建时间
     */
    public record Status(boolean configured, int version, String endpoint, String modelId,
            String keyMask, boolean toolCallingVerified, Instant updatedAt) {}
}
