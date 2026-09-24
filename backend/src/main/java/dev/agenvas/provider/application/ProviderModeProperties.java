package dev.agenvas.provider.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Selects the account-free fixture gateway or the fixed ComfyUI adapter. */
@ConfigurationProperties(prefix = "agenvas.provider")
public record ProviderModeProperties(Mode mode) {

    /** The self-hosted installation defaults to locally generated Mock media. */
    public ProviderModeProperties {
        mode = mode == null ? Mode.MOCK : mode;
    }

    public enum Mode { MOCK, COMFYUI }
}
