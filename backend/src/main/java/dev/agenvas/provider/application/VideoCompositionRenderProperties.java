package dev.agenvas.provider.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-selected installed font family; requests cannot select font files or filter arguments. */
@ConfigurationProperties(prefix = "agenvas.video-composition")
public record VideoCompositionRenderProperties(String fontFamily) {
    public VideoCompositionRenderProperties {
        fontFamily = fontFamily == null ? "Noto Sans CJK SC" : fontFamily;
        if (!fontFamily.matches("[\\p{L}\\p{N} ._-]{1,80}")) throw new IllegalArgumentException("Invalid composition font family");
    }
}
