package dev.agenvas.llm.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;

/** 单次模型响应边界；业务 Runtime 独占响应持久化、配置固定和所有工具执行。 */
public interface ChatGateway {

    /**
     * 发起一次模型请求并返回原始 Spring AI 响应；实现不得自动执行响应中的工具调用。
     *
     * @param messages 已持久化请求快照对应的有序消息
     * @param tools 本回合允许模型看到的定义式回调
     * @param toolContext 仅包含服务端建立的可信工具上下文
     * @return 原始响应及实际使用的配置身份
     */
    Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> toolContext);

    /** 可切换配置的实现须确认仍是预留时固定的来源和版本，不可静默切换模型。 */
    default Exchange call(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext, ConfigIdentity expected) {
        if (!configIdentity().equals(expected)) {
            throw new IllegalStateException("ChatGateway configuration changed before model call");
        }
        return call(messages, tools, toolContext);
    }

    /** 返回有测试依据的能力；未经验证的视觉和原生结构化输出必须保持关闭。 */
    Capabilities capabilities();

    /** 只报告指定固定配置的能力；配置变化或版本不符时全部返回 false。 */
    default Capabilities capabilitiesFor(ConfigIdentity expected) {
        return configIdentity().equals(expected)
                ? capabilities() : new Capabilities(false, false, false);
    }

    /** 预留模型回合前确认固定配置仍可用且支持工具调用，不回退到其他配置。 */
    default void requireToolCalling(ConfigIdentity expected) {
        if (!capabilitiesFor(expected).toolCalling()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "LLM_CONFIG_UNAVAILABLE",
                    "原模型配置不可用", "Run 固定的模型配置或工具能力已不可用，未切换到新配置。", false);
        }
    }

    /** 新 Run 固定的配置版本；已固定的 Run 不随当前配置变化。 */
    int configVersion();

    /** 区分 Mock、配置模型等来源，防止相同数字版本发生身份碰撞。 */
    String configSource();

    /** 同时读取来源和版本，供预留与实际派发之间做原子身份比较。 */
    default ConfigIdentity configIdentity() {
        return new ConfigIdentity(configSource(), configVersion());
    }

    /** 运行前确认使用的非敏感模型信息，不包含密钥或端点。 */
    default ModelDetails modelDetails() {
        return new ModelDetails(false, null, null, false);
    }

    /**
     * 一次调用结果及所用配置版本。
     *
     * @param configVersion 返回该响应的配置版本
     * @param response 未经工具执行改写的模型原始响应
     */
    record Exchange(int configVersion, ChatResponse response) {}

    /**
     * 带来源命名空间的配置身份。
     *
     * @param source 模型配置来源标识
     * @param version 该来源内的正整数配置版本
     */
    record ConfigIdentity(String source, int version) {}

    /**
     * 明确区分已验证能力和未经验证的模型特性。
     *
     * @param toolCalling 是否已验证工具调用能力
     * @param vision 是否已验证视觉输入能力
     * @param nativeStructuredOutput 是否已验证原生结构化输出能力
     */
    record Capabilities(boolean toolCalling, boolean vision, boolean nativeStructuredOutput) {}

    /**
     * 用于运行前用户确认的非敏感模型信息。
     *
     * @param available 固定配置当前是否可调用
     * @param providerAdapter 对外展示的适配器类别
     * @param modelId 对外展示的模型标识
     * @param toolCalling 固定配置是否支持工具调用
     */
    record ModelDetails(boolean available, String providerAdapter, String modelId,
            boolean toolCalling) {}
}
