package dev.agenvas.llm.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Versioned model-policy settings independent of any Provider credential. */
@ConfigurationProperties(prefix = "agenvas.llm")
public record LlmProperties(int configVersion, boolean toolCallingVerified) {

    /** Fails startup for a malformed version instead of writing an untraceable model response. */
    public LlmProperties {
        if (configVersion < 1) {
            throw new IllegalArgumentException("LLM configVersion must be positive");
        }
    }
}
