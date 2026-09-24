package dev.agenvas.llm.infrastructure;

import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.settings.application.LlmProviderConfig;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/** 运行时调用和管理员诊断共用同一安全模型客户端构造路径。 */
@Component
public class StoredChatModelFactory {

    /** 仅在构造客户端时解密指定配置版本的密钥。 */
    private final CredentialCipher cipher;
    /** 限制端点及 DNS 解析地址，供正常调用和诊断共用。 */
    private final LlmEndpointPolicy endpoints;

    /** 注入服务端密钥解密器和端点出站策略。
     * @param cipher 加密凭据的版本化解密器
     * @param endpoints 校验 Provider 地址及解析 IP 的策略
     */
    public StoredChatModelFactory(CredentialCipher cipher, LlmEndpointPolicy endpoints) {
        this.cipher = cipher;
        this.endpoints = endpoints;
    }

    /** 只在构造模型客户端时解密凭据，并对运行和诊断请求应用相同出站限制。 */
    public SpringAiChatGateway create(LlmProviderConfig config) {
        String secret = cipher.decrypt(config.id(), config.version(),
                new CredentialCipher.Encrypted(config.credentialCiphertext(),
                        config.credentialNonce(), config.keyVersion()));
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(config.endpoint()).apiKey(secret).model(config.modelId())
                .maxRetries(0).build();
        SafeLlmTransport transport = new SafeLlmTransport(config.endpoint(), endpoints);
        return new SpringAiChatGateway(OpenAiChatModel.builder().options(options)
                .httpClientBuilderCustomizer(builder ->
                        builder.interceptor(transport.interceptor()))
                .build(), config.version());
    }

    /** 历史部署密钥已退役时，在预留用量前拒绝创建客户端。 */
    public void requireCredential(LlmProviderConfig config) {
        cipher.requireKeyVersion(config.keyVersion());
    }
}
