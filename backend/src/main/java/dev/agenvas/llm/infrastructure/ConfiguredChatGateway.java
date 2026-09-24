package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProperties;
import dev.agenvas.settings.application.LlmProviderConfig;
import dev.agenvas.settings.application.LlmProviderConfigRepository;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/** Resolves the configured ChatModel lazily, leaving Mock-only startup independent of LLM keys. */
public class ConfiguredChatGateway implements ChatGateway {

    private final ObjectProvider<ChatModel> models;
    private final LlmProperties properties;
    private final LlmProviderConfigRepository configs;
    private final StoredChatModelFactory factory;
    private volatile RuntimeClient activeClient;

    public ConfiguredChatGateway(ObjectProvider<ChatModel> models, LlmProperties properties) {
        this(models, properties, null, null);
    }

    /** Database settings override the legacy environment candidate when present. */
    public ConfiguredChatGateway(ObjectProvider<ChatModel> models, LlmProperties properties,
            LlmProviderConfigRepository configs, StoredChatModelFactory factory) {
        this.models = models;
        this.properties = properties;
        this.configs = configs;
        this.factory = factory;
    }

    @Override
    public Exchange call(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext) {
        return call(messages, tools, toolContext, configIdentity());
    }

    @Override
    public Exchange call(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext, ConfigIdentity expected) {
        LlmProviderConfig config = pinnedConfig(expected);
        return requireConfigured(config).call(messages, tools, toolContext);
    }

    @Override
    public Capabilities capabilitiesFor(ConfigIdentity expected) {
        if ("stored".equals(expected.source()) && configs != null) {
            return configs.findVersion(expected.version())
                    .map(config -> new Capabilities(config.toolCallingVerified(), false, false))
                    .orElse(new Capabilities(false, false, false));
        }
        return ChatGateway.super.capabilitiesFor(expected);
    }

    @Override
    public void requireToolCalling(ConfigIdentity expected) {
        ChatGateway.super.requireToolCalling(expected);
        if ("stored".equals(expected.source())) {
            factory.requireCredential(pinnedConfig(expected));
        }
    }

    @Override
    public Capabilities capabilities() {
        LlmProviderConfig config = activeConfig();
        if (config != null) {
            return new Capabilities(config.toolCallingVerified(), false, false);
        }
        ChatModel model = models.getIfAvailable();
        return model == null
                ? new Capabilities(false, false, false)
                : new Capabilities(properties.toolCallingVerified()
                        && new SpringAiChatGateway(model, properties.configVersion())
                                .capabilities().toolCalling(), false, false);
    }

    @Override
    public ModelDetails modelDetails() {
        LlmProviderConfig config = activeConfig();
        if (config != null) {
            return new ModelDetails(true, "OpenAI-compatible", config.modelId(),
                    config.toolCallingVerified());
        }
        ChatModel model = models.getIfAvailable();
        if (model == null) return new ModelDetails(false, null, null, false);
        ModelDetails adapter = new SpringAiChatGateway(model,
                properties.configVersion()).modelDetails();
        return new ModelDetails(adapter.available(), adapter.providerAdapter(),
                adapter.modelId(), properties.toolCallingVerified() && adapter.toolCalling());
    }

    private SpringAiChatGateway requireConfigured(LlmProviderConfig config) {
        if (config != null) {
            if (!config.toolCallingVerified()) {
                throw new IllegalStateException("Configured LLM tool calling is unverified");
            }
            RuntimeClient cached = activeClient;
            if (cached != null && cached.version() == config.version()) return cached.gateway();
            synchronized (this) {
                cached = activeClient;
                if (cached != null && cached.version() == config.version()) return cached.gateway();
                SpringAiChatGateway gateway = factory.create(config);
                activeClient = new RuntimeClient(config.version(), gateway);
                return gateway;
            }
        }
        ChatModel model = models.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("No LLM ChatModel is configured for this installation");
        }
        return new SpringAiChatGateway(model, properties.configVersion());
    }

    private LlmProviderConfig pinnedConfig(ConfigIdentity expected) {
        if ("stored".equals(expected.source()) && configs != null) {
            return configs.findVersion(expected.version()).orElseThrow(() ->
                    new IllegalStateException("Pinned LLM configuration is unavailable"));
        }
        if ("environment".equals(expected.source()) && activeConfig() == null
                && properties.configVersion() == expected.version()) return null;
        throw new IllegalStateException("ChatGateway configuration changed before model call");
    }

    @Override
    public int configVersion() {
        LlmProviderConfig config = activeConfig();
        return config == null ? properties.configVersion() : config.version();
    }

    @Override
    public String configSource() {
        return activeConfig() == null ? "environment" : "stored";
    }

    @Override
    public ConfigIdentity configIdentity() {
        LlmProviderConfig config = activeConfig();
        return config == null
                ? new ConfigIdentity("environment", properties.configVersion())
                : new ConfigIdentity("stored", config.version());
    }

    private LlmProviderConfig activeConfig() {
        LlmProviderConfig config = configs == null ? null : configs.active().orElse(null);
        RuntimeClient cached = activeClient;
        if (config != null && cached != null && config.version() > cached.version()) {
            activeClient = null;
        }
        return config;
    }

    /** A version has one client; rotation drops the previous credential-bearing reference. */
    private record RuntimeClient(int version, SpringAiChatGateway gateway) {}
}
