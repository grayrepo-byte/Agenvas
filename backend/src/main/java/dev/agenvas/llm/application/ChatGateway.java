package dev.agenvas.llm.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;

/** One model response boundary; the business Runtime owns persistence and all tool execution. */
public interface ChatGateway {

    /** Calls the model once and returns its untouched assistant message and protocol metadata. */
    Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> toolContext);

    /** Implementations with mutable configuration bind dispatch to the already reserved identity. */
    default Exchange call(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext, ConfigIdentity expected) {
        if (!configIdentity().equals(expected)) {
            throw new IllegalStateException("ChatGateway configuration changed before model call");
        }
        return call(messages, tools, toolContext);
    }

    /** Capabilities backed by tests; unknown vision and native structured output stay disabled. */
    Capabilities capabilities();

    /** Reports only capabilities of the exact pinned configuration, never a replacement. */
    default Capabilities capabilitiesFor(ConfigIdentity expected) {
        return configIdentity().equals(expected)
                ? capabilities() : new Capabilities(false, false, false);
    }

    /** Rejects unavailable pinned credentials or capability before reserving a model turn. */
    default void requireToolCalling(ConfigIdentity expected) {
        if (!capabilitiesFor(expected).toolCalling()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "LLM_CONFIG_UNAVAILABLE",
                    "原模型配置不可用", "Run 固定的模型配置或工具能力已不可用，未切换到新配置。", false);
        }
    }

    /** Version selected for a new Run; a changed version must not silently replace it. */
    int configVersion();

    /** Stable namespace prevents equal version numbers from different sources colliding. */
    String configSource();

    /** Reads source and version together when a mutable adapter can switch configuration. */
    default ConfigIdentity configIdentity() {
        return new ConfigIdentity(configSource(), configVersion());
    }

    /** Public, non-secret model identification for informed Run consent. */
    default ModelDetails modelDetails() {
        return new ModelDetails(false, null, null, false);
    }

    /** The exact model configuration version used for the returned response. */
    record Exchange(int configVersion, ChatResponse response) {}

    /** A source-qualified version avoids equal integer versions from different adapters. */
    record ConfigIdentity(String source, int version) {}

    /** Explicitly distinguishes tested tool support from unverified model features. */
    record Capabilities(boolean toolCalling, boolean vision, boolean nativeStructuredOutput) {}

    /** Never includes credentials, endpoint URLs, or provider-private request metadata. */
    record ModelDetails(boolean available, String providerAdapter, String modelId,
            boolean toolCalling) {}
}
