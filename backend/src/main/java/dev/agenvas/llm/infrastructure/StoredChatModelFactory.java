package dev.agenvas.llm.infrastructure;

import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.settings.application.LlmProviderConfig;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/** One security-preserving construction path for runtime and administrator diagnostics. */
@Component
public class StoredChatModelFactory {

    private final CredentialCipher cipher;
    private final LlmEndpointPolicy endpoints;

    public StoredChatModelFactory(CredentialCipher cipher, LlmEndpointPolicy endpoints) {
        this.cipher = cipher;
        this.endpoints = endpoints;
    }

    /** Decrypts only at model construction and applies the same outbound guard to both clients. */
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

    /** Fails before usage reservation when a historical deployment key was retired. */
    public void requireCredential(LlmProviderConfig config) {
        cipher.requireKeyVersion(config.keyVersion());
    }
}
