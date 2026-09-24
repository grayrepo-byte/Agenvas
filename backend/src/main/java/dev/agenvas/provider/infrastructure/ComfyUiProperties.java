package dev.agenvas.provider.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Administrator-owned single ComfyUI origin; never supplied by Agent tool arguments. */
@ConfigurationProperties(prefix = "agenvas.provider.comfyui")
public record ComfyUiProperties(String endpoint) {}
