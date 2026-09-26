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

/** 延迟解析已配置 ChatModel；Mock 启动不依赖 LLM 凭证，运行时按固定配置版本调用。 */
public class ConfiguredChatGateway implements ChatGateway {

    /** 读取旧环境配置中的 Spring AI ChatModel；仅环境配置路径会使用。 */
    private final ObjectProvider<ChatModel> models;
    /** 提供环境配置的版本和工具调用验证标记。 */
    private final LlmProperties properties;
    /** 查询管理员保存配置的当前版本或 Run 固定历史版本。 */
    private final LlmProviderConfigRepository configs;
    /** 解密服务端凭证并创建使用安全传输策略的模型客户端。 */
    private final StoredChatModelFactory factory;
    /** 当前版本缓存；配置轮换时丢弃旧的凭证客户端引用。 */
    private volatile RuntimeClient activeClient;

    /** 创建仅使用环境配置的兼容入口；不启用数据库凭证配置。 */
    public ConfiguredChatGateway(ObjectProvider<ChatModel> models, LlmProperties properties) {
        this(models, properties, null, null);
    }

    /** 数据库中存在活动配置时优先使用；环境候选只用于无活动数据库配置的安装。 */
    public ConfiguredChatGateway(ObjectProvider<ChatModel> models, LlmProperties properties,
            LlmProviderConfigRepository configs, StoredChatModelFactory factory) {
        this.models = models;
        this.properties = properties;
        this.configs = configs;
        this.factory = factory;
    }

    /** 调用前读取当下来源和版本，并通过同一固定身份入口派发。 */
    @Override
    public Exchange call(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext) {
        return call(messages, tools, toolContext, configIdentity());
    }

    /** 重新读取 Run 固定的配置版本，凭证缺失或版本漂移时拒绝改用其他模型。 */
    @Override
    public Exchange call(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext, ConfigIdentity expected) {
        LlmProviderConfig config = pinnedConfig(expected);
        return requireConfigured(config).call(messages, tools, toolContext);
    }

    /** 数据库配置能力只按其固定历史版本报告；缺失配置或其他来源走接口默认核验。 */
    @Override
    public Capabilities capabilitiesFor(ConfigIdentity expected) {
        if ("stored".equals(expected.source()) && configs != null) {
            return configs.findVersion(expected.version())
                    .map(config -> new Capabilities(config.toolCallingVerified(), false, false))
                    .orElse(new Capabilities(false, false, false));
        }
        return ChatGateway.super.capabilitiesFor(expected);
    }

    /** 在普通能力检查外，再解密并验证固定数据库配置的凭证可用。 */
    @Override
    public void requireToolCalling(ConfigIdentity expected) {
        ChatGateway.super.requireToolCalling(expected);
        if ("stored".equals(expected.source())) {
            factory.requireCredential(pinnedConfig(expected));
        }
    }

    /** 展示当前活动配置或环境候选的工具调用能力；视觉能力始终报告未验证。 */
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

    /** 为用户预检提供非秘密 Provider 类别和模型 ID，不包含端点或密钥。 */
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

    /** Resolve the immutable version used by the run without constructing a client or exposing its endpoint. */
    @Override
    public ModelDetails modelDetailsFor(ConfigIdentity expected) {
        LlmProviderConfig config = pinnedConfig(expected);
        if (config != null) {
            return new ModelDetails(true, "OpenAI-compatible", config.modelId(),
                    config.toolCallingVerified());
        }
        return modelDetails();
    }

    /** 验证工具调用能力并复用同版本客户端；版本变化时重建并丢弃旧客户端。 */
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

    /** 只解析 Run 固定的数据库版本或仍未被数据库配置取代的环境版本。 */
    private LlmProviderConfig pinnedConfig(ConfigIdentity expected) {
        if ("stored".equals(expected.source()) && configs != null) {
            return configs.findVersion(expected.version()).orElseThrow(() ->
                    new IllegalStateException("Pinned LLM configuration is unavailable"));
        }
        if ("environment".equals(expected.source()) && activeConfig() == null
                && properties.configVersion() == expected.version()) return null;
        throw new IllegalStateException("ChatGateway configuration changed before model call");
    }

    /** 返回新 Run 应固定的当前配置版本。 */
    @Override
    public int configVersion() {
        LlmProviderConfig config = activeConfig();
        return config == null ? properties.configVersion() : config.version();
    }

    /** 返回数据库配置或环境配置命名空间，防止同号版本身份混淆。 */
    @Override
    public String configSource() {
        return activeConfig() == null ? "environment" : "stored";
    }

    /** 一次读取当前配置来源和版本，供预留模型回合时建立不可变身份。 */
    @Override
    public ConfigIdentity configIdentity() {
        LlmProviderConfig config = activeConfig();
        return config == null
                ? new ConfigIdentity("environment", properties.configVersion())
                : new ConfigIdentity("stored", config.version());
    }

    /** 读取活动数据库配置，并在发现新版本时移除旧的凭证客户端缓存。 */
    private LlmProviderConfig activeConfig() {
        LlmProviderConfig config = configs == null ? null : configs.active().orElse(null);
        RuntimeClient cached = activeClient;
        if (config != null && cached != null && config.version() > cached.version()) {
            activeClient = null;
        }
        return config;
    }

    /** 一个配置版本对应一个模型客户端；轮换后旧凭证客户端不再保存在缓存字段中。
     * @param version 客户端对应的数据库配置版本
     * @param gateway 持有该版本解密凭证的模型调用入口
     */
    private record RuntimeClient(int version, SpringAiChatGateway gateway) {}
}
