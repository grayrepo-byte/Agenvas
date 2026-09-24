package dev.agenvas.llm.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicitly selects an account-free demo gateway or a configured Spring AI ChatModel. */
@ConfigurationProperties(prefix = "agenvas.llm")
public record LlmModeProperties(Mode mode) {

    /** Defaults to a deterministic local demo with no network model request. */
    public LlmModeProperties {
        mode = mode == null ? Mode.MOCK : mode;
    }

    public enum Mode { MOCK, CONFIGURED }
}
