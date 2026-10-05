package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiImage2AdapterTest {
    @Test
    void mapsAtomicRatiosAndResolutionLabelsToBoundedGptImageDimensions() {
        assertThat(OpenAiImage2Adapter.openAiSize("9:16", "1K")).isEqualTo("720x1280");
        assertThat(OpenAiImage2Adapter.openAiSize("1:1", "2K")).isEqualTo("2048x2048");
        assertThat(OpenAiImage2Adapter.openAiSize("16:9", "4K")).isEqualTo("3840x2160");
        assertThat(OpenAiImage2Adapter.openAiSize("21:9", "4K")).isEqualTo("3808x1632");
    }

    @Test
    void publishesExplicitMaskSupportOnlyForTheOpenAiImageAdapter() {
        MediaAdapterRegistry registry = new MediaAdapterRegistry(List.of());
        assertThat(registry.declaration(MediaAdapterRegistry.OPENAI_GPT_IMAGE_2)
                .supportsImageMask()).isTrue();
        assertThat(registry.declaration(MediaAdapterRegistry.GOOGLE_NANO_BANANA_2)
                .supportsImageMask()).isFalse();
    }
}
