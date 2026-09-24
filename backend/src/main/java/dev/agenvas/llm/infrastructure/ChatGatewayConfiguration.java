package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProperties;
import dev.agenvas.llm.application.LlmModeProperties;
import dev.agenvas.settings.application.LlmProviderConfigRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Installs a lazy chat boundary without creating a remote client in Mock-only mode. */
@Configuration
public class ChatGatewayConfiguration {

    /** Mock mode uses a deterministic fixture; configured mode resolves the real model lazily. */
    @Bean
    public ChatGateway chatGateway(ObjectProvider<ChatModel> models, LlmProperties properties,
            LlmModeProperties mode, ObjectMapper mapper,
            LlmProviderConfigRepository configs, StoredChatModelFactory factory) {
        return switch (mode.mode()) {
            case MOCK -> new MockStoryboardChatGateway(mapper, properties.configVersion());
            case CONFIGURED -> new ConfiguredChatGateway(models, properties, configs, factory);
        };
    }
}
