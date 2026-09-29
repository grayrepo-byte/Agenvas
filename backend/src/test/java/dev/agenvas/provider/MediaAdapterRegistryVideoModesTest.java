package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaAdapterRegistryVideoModesTest {
    private final MediaAdapterRegistry registry = new MediaAdapterRegistry(List.of());

    @Test
    void mockSupportsContextualTextAndImageDefaults() {
        var mock = registry.declaration("MOCK_VIDEO");
        assertThat(mock.supportedVideoInputModes())
                .containsExactlyInAnyOrder("TEXT", "START_END", "GENERAL_REFERENCE");
        assertThat(mock.defaultVideoInputMode()).isEqualTo("TEXT");
        assertThat(mock.maxReferenceImages()).isEqualTo(4);
    }

    @Test
    void fixedImageToVideoAdaptersDoNotClaimTextOrGeneralReference() {
        assertThat(registry.declaration("COMFY_VIDEO_V1").supportedVideoInputModes())
                .containsExactly("START_END");
        assertThat(registry.declaration("ARK_SEEDANCE_2_I2V").supportedVideoInputModes())
                .containsExactly("START_END");
    }
}
