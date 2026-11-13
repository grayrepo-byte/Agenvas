package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.LlmCallTimeouts;
import dev.agenvas.llm.application.LlmRequestBudget;
import dev.agenvas.llm.application.DirectorSettings;
import dev.agenvas.llm.application.DirectorSettingsService;
import dev.agenvas.settings.application.LlmModelPolicy;
import dev.agenvas.settings.infrastructure.ModelMetadataClient;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.settings.application.LlmProviderConfig;
import java.time.Duration;
import okhttp3.Dns;
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
    private final LlmImageRelay imageRelay;
    private final DirectorSettingsService settings;
    private final ModelMetadataClient metadata;

    /** 注入服务端密钥解密器和端点出站策略。
     * @param cipher 加密凭据的版本化解密器
     * @param endpoints 校验 Provider 地址及解析 IP 的策略
     */
    public StoredChatModelFactory(CredentialCipher cipher, LlmEndpointPolicy endpoints) {
        this(cipher, endpoints, null);
    }

    public StoredChatModelFactory(CredentialCipher cipher, LlmEndpointPolicy endpoints, LlmImageRelay imageRelay) {
        this(cipher, endpoints, imageRelay, null, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public StoredChatModelFactory(CredentialCipher cipher, LlmEndpointPolicy endpoints, LlmImageRelay imageRelay,
            DirectorSettingsService settings, ModelMetadataClient metadata) {
        this.cipher = cipher;
        this.endpoints = endpoints;
        this.imageRelay = imageRelay;
        this.settings = settings;
        this.metadata = metadata;
    }

    /** 只在构造模型客户端时解密凭据，并对运行和诊断请求应用相同出站限制。 */
    public SpringAiChatGateway create(LlmProviderConfig config) {
        return create(config, LlmCallTimeouts.REQUEST, LlmCallTimeouts.TRANSPORT_READ);
    }
    public SpringAiChatGateway create(LlmProviderConfig config, LlmRequestBudget budget) {
        return create(config, LlmCallTimeouts.REQUEST, LlmCallTimeouts.TRANSPORT_READ, budget);
    }
    public LlmRequestBudget requestBudget(LlmProviderConfig config) {
        return LlmRequestBudget.resolve(settings == null ? DirectorSettings.defaults() : settings.get(),
                config == null ? null : config.modelPolicy());
    }

    /** SDK and transport share the total deadline; silent reads have an independent limit. */
    SpringAiChatGateway create(LlmProviderConfig config, Duration modelTimeout, Duration readTimeout) {
        return create(config, modelTimeout, readTimeout, config.modelPolicy() == null ? null : requestBudget(config));
    }
    private SpringAiChatGateway create(LlmProviderConfig config, Duration modelTimeout, Duration readTimeout,
            LlmRequestBudget budget) {
        var policy = config.modelPolicy() == null ? LlmModelPolicy.genericDefault() : config.modelPolicy();
        if (budget != null && (budget.responseReserveTokens() > policy.maxOutputTokens()
                || budget.outputParameter() != policy.outputParameter()))
            throw LlmRequestBudget.unknown();
        String secret = cipher.decrypt(config.id(), config.version(),
                new CredentialCipher.Encrypted(config.credentialCiphertext(),
                        config.credentialNonce(), config.keyVersion()));
        var options = OpenAiChatOptions.builder()
                .baseUrl(config.endpoint()).apiKey(secret).model(config.modelId())
                .timeout(modelTimeout).maxRetries(0);
        if (budget != null) {
            if (budget.outputParameter() == LlmModelPolicy.OutputParameter.MAX_COMPLETION_TOKENS)
                options.maxCompletionTokens(budget.responseReserveTokens());
            else
                options.maxTokens(budget.responseReserveTokens());
        }
        if (config.modelPolicy() != null && config.modelPolicy().source() == LlmModelPolicy.Source.OLLAMA_RUNTIME) {
            if (metadata == null)
                throw LlmRequestBudget.runtimeChanged();
            dev.agenvas.settings.application.ModelCatalog.Model observed;
            try {
                observed = metadata.fetch(config.modelPolicy().provider(), config.endpoint(), secret, config.modelId())
                        .models().stream()
                        .filter(value -> value.id().equals(config.modelId())).findFirst()
                        .orElseThrow(LlmRequestBudget::runtimeChanged);
            } catch (ModelMetadataClient.MetadataFailure unavailable) {
                if (java.util.Set.of("MODEL_METADATA_BUSY", "MODEL_METADATA_TIMEOUT", "MODEL_METADATA_UNAVAILABLE")
                        .contains(unavailable.getMessage()))
                    // No generation was sent. Preserve the existing bounded connection recovery path.
                    throw new java.io.UncheckedIOException(
                            new java.io.IOException("Model metadata preflight temporarily unavailable"));
                throw LlmRequestBudget.runtimeChanged();
            }
            if (observed.runtimeContextTokens() == null
                    || observed.runtimeContextTokens() < config.modelPolicy().contextWindowTokens()
                    || (config.modelPolicy().modelDigest() != null
                            && !config.modelPolicy().modelDigest().equals(observed.digest())))
                throw LlmRequestBudget.runtimeChanged();
        }
        SafeLlmTransport transport = new SafeLlmTransport(config.endpoint(), endpoints, Dns.SYSTEM,
                readTimeout, modelTimeout);
        return SpringAiChatGateway.withDebugCapture(OpenAiChatModel.builder().options(options.build())
                .httpClientBuilderCustomizer(builder -> builder.interceptor(transport.interceptor()))
                .build(), config.version(), imageRelay, budget);
    }

    SpringAiChatGateway environment(org.springframework.ai.chat.model.ChatModel model, int version) {
        return new SpringAiChatGateway(model, version, imageRelay);
    }

    SpringAiChatGateway environment(org.springframework.ai.chat.model.ChatModel model, int version,
            LlmRequestBudget budget) {
        return new SpringAiChatGateway(model, version, imageRelay, budget);
    }

    /** 历史部署密钥已退役时，在预留用量前拒绝创建客户端。 */
    public void requireCredential(LlmProviderConfig config) {
        cipher.requireKeyVersion(config.keyVersion());
    }
}
