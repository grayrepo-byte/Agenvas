package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProperties;
import dev.agenvas.llm.application.LlmModeProperties;
import dev.agenvas.settings.application.LlmProviderConfigRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 安装延迟初始化的聊天网关，纯 Mock 模式不会创建远端客户端。 */
@Configuration
public class ChatGatewayConfiguration {

    /** Mock 模式使用确定性文本回复；配置模式仅在需要时解析真实模型。 */
    @Bean
    public ChatGateway chatGateway(ObjectProvider<ChatModel> models, LlmProperties properties,
            LlmModeProperties mode,
            LlmProviderConfigRepository configs, StoredChatModelFactory factory) {
        return switch (mode.mode()) {
            case MOCK -> new MockChatGateway(properties.configVersion());
            case CONFIGURED -> new ConfiguredChatGateway(models, properties, configs, factory);
        };
    }
}
