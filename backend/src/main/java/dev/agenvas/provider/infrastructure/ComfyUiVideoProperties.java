package dev.agenvas.provider.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-installed Wan 2.1 model basenames for the fixed image-to-video candidate. */
@ConfigurationProperties(prefix = "agenvas.provider.comfyui.video")
public record ComfyUiVideoProperties(boolean enabled, String diffusionModel,
        String textEncoder, String vae, String clipVision) {}
