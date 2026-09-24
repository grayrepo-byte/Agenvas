package dev.agenvas.provider.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Admin-installed checkpoint basename for the bundled, immutable image-v1 graph. */
@ConfigurationProperties(prefix = "agenvas.provider.comfyui.image")
public record ComfyUiImageProperties(String checkpoint) {}
