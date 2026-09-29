package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenAiImage2AdapterTest {
    @Test
    void mapsAtomicRatiosAndResolutionLabelsToBoundedGptImageDimensions() {
        assertThat(OpenAiImage2Adapter.openAiSize("9:16", "1K")).isEqualTo("720x1280");
        assertThat(OpenAiImage2Adapter.openAiSize("1:1", "2K")).isEqualTo("2048x2048");
        assertThat(OpenAiImage2Adapter.openAiSize("16:9", "4K")).isEqualTo("3840x2160");
        assertThat(OpenAiImage2Adapter.openAiSize("21:9", "4K")).isEqualTo("3808x1632");
    }
}
