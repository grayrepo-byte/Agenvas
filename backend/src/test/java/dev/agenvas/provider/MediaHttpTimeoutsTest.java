package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityConfiguration;
import dev.agenvas.provider.domain.MediaHttpTimeouts;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.http.HttpTimeouts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Strict JSON publication boundaries; no HTTP traffic or provider credentials. */
class MediaHttpTimeoutsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaAdapterRegistry registry = new MediaAdapterRegistry(List.of());

    @Test
    void absenceKeepsLegacyDefaultsAndExplicitSettingsAreCopiedWithoutMutatingTheirSource() {
        assertThat(MediaHttpTimeouts.configured(mapper.createObjectNode())).isNull();
        var source = mapper.readTree("""
                {"httpTimeouts":{"connectSeconds":12,"writeSeconds":90,"readSeconds":120,"callSeconds":180}}
                """);
        var original = source.deepCopy();
        var target = mapper.createObjectNode();
        MediaCapabilityConfiguration.normalize(mapper, registry.declaration("OPENAI_GPT_IMAGE_2"), source, target);

        assertThat(target.path("httpTimeouts")).isEqualTo(source.path("httpTimeouts"));
        assertThat(MediaHttpTimeouts.configured(target)).isEqualTo(new HttpTimeouts(12, 90, 120, 180));
        target.withObject("httpTimeouts").put("writeSeconds", 7);
        assertThat(source).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}",
            "{\"connectSeconds\":1,\"writeSeconds\":1,\"readSeconds\":1}",
            "{\"connectSeconds\":1,\"writeSeconds\":1,\"readSeconds\":1,\"callSeconds\":1,\"extra\":2}",
            "{\"connectSeconds\":0,\"writeSeconds\":1,\"readSeconds\":1,\"callSeconds\":1}",
            "{\"connectSeconds\":121,\"writeSeconds\":1,\"readSeconds\":1,\"callSeconds\":180}",
            "{\"connectSeconds\":1,\"writeSeconds\":-1,\"readSeconds\":1,\"callSeconds\":1}",
            "{\"connectSeconds\":1,\"writeSeconds\":3601,\"readSeconds\":1,\"callSeconds\":3600}",
            "{\"connectSeconds\":1,\"writeSeconds\":1,\"readSeconds\":1.5,\"callSeconds\":2}",
            "{\"connectSeconds\":1,\"writeSeconds\":1,\"readSeconds\":\"1\",\"callSeconds\":2}",
            "{\"connectSeconds\":1,\"writeSeconds\":1,\"readSeconds\":1,\"callSeconds\":null}",
            "{\"connectSeconds\":1,\"writeSeconds\":1,\"readSeconds\":1,\"callSeconds\":2147483648}",
            "{\"connectSeconds\":2,\"writeSeconds\":1,\"readSeconds\":1,\"callSeconds\":1}",
            "{\"connectSeconds\":1,\"writeSeconds\":2,\"readSeconds\":1,\"callSeconds\":1}",
            "{\"connectSeconds\":1,\"writeSeconds\":1,\"readSeconds\":2,\"callSeconds\":1}"})
    void rejectsMissingNonIntegerUnboundedOrInconsistentTimeouts(String json) {
        var source = mapper.createObjectNode().set("httpTimeouts", mapper.readTree(json));
        assertThatThrownBy(() -> MediaCapabilityConfiguration.normalize(mapper,
                registry.declaration("OPENAI_GPT_IMAGE_2"), source, mapper.createObjectNode()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("PROVIDER_UNSUPPORTED_INPUT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOCK_IMAGE", "MOCK_VIDEO", "MOCK_AUDIO", "LOCAL_IMAGE_PROCESSOR",
            "LOCAL_VIDEO_PROCESSOR", "LOCAL_VIDEO_AUDIO_EXTRACTOR", "LOCAL_VIDEO_COMPOSITOR", "LOCAL_AUDIO_TRIMMER"})
    void noNetworkProtocolsRejectTimeoutSettings(String adapter) {
        var source = mapper.readTree("""
                {"httpTimeouts":{"connectSeconds":1,"writeSeconds":1,"readSeconds":1,"callSeconds":1}}
                """);
        assertThatThrownBy(() -> MediaCapabilityConfiguration.normalize(mapper,
                registry.declaration(adapter), source, mapper.createObjectNode()))
                .isInstanceOf(ApiProblemException.class);
    }
}
