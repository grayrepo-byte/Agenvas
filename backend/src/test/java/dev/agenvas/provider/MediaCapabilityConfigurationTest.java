package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityConfiguration;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MediaCapabilityConfigurationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaAdapterRegistry registry = new MediaAdapterRegistry(List.of());

    @Test
    void acceptsImageDefaultsNarrowerReferencesAndDecimalPrice() {
        ObjectNode settings = normalize("GOOGLE_NANO_BANANA_2", """
                {"maxReferenceImages":3,"defaultParameters":{"aspectRatio":"16:9",
                "resolution":"2K","generationCount":4},
                "pricing":{"amount":"0.123456","currency":"USD","unit":"IMAGE"}}
                """);
        assertThat(settings.at("/defaultParameters/resolution").asText()).isEqualTo("2K");
        assertThat(settings.at("/defaultParameters/generationCount").asInt()).isEqualTo(4);
        assertThat(settings.at("/pricing/amount").asText()).isEqualTo("0.123456");
        assertThat(MediaCapabilityConfiguration.policy(registry.declaration("GOOGLE_NANO_BANANA_2"),
                settings).maxReferenceImages()).isEqualTo(3);
    }

    @Test
    void acceptsVideoDurationRangeAndSecondPrice() {
        var settings = normalize("ARK_SEEDANCE_2_I2V", """
                {"minimumSeconds":5,"maximumSeconds":10,"defaultDurationSeconds":8,
                "defaultParameters":{"aspectRatio":"9:16"},
                "pricing":{"amount":"1.25","currency":"CNY","unit":"SECOND"}}
                """);
        var policy = MediaCapabilityConfiguration.policy(registry.declaration("ARK_SEEDANCE_2_I2V"), settings);
        assertThat(policy.minimumSeconds()).isEqualTo(5);
        assertThat(policy.maximumSeconds()).isEqualTo(10);
        assertThat(settings.path("defaultDurationSeconds").asInt()).isEqualTo(8);
    }

    @Test
    void rejectsLimitsOutsideCompiledProtocolAndDefaultsOutsideConfiguredRange() {
        for (String json : List.of("{\"minimumSeconds\":3}", "{\"maximumSeconds\":16}",
                "{\"minimumSeconds\":10,\"maximumSeconds\":5}",
                "{\"maximumSeconds\":10,\"defaultDurationSeconds\":12}",
                "{\"maxReferenceImages\":10}", "{\"maxReferenceImages\":1.5}")) {
            assertThatThrownBy(() -> normalize("ARK_SEEDANCE_2_I2V", json))
                    .isInstanceOf(ApiProblemException.class);
        }
    }

    @Test
    void rejectsUnsupportedParametersInsteadOfAdvertisingUnusedControls() {
        for (String json : List.of("{\"defaultParameters\":{\"resolution\":\"4K\"}}",
                "{\"defaultParameters\":{\"transparentBackground\":true}}",
                "{\"defaultParameters\":{\"seed\":1}}")) {
            assertThatThrownBy(() -> normalize("COMFY_IMAGE_V1", json))
                    .isInstanceOf(ApiProblemException.class);
        }
        assertThatThrownBy(() -> normalize("ARK_SEEDANCE_2_I2V",
                "{\"defaultParameters\":{\"resolution\":\"2K\"}}"))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void rejectsAmbiguousOrOverPrecisePricesAndWrongUnits() {
        for (String price : List.of("{\"amount\":0.5,\"currency\":\"CNY\",\"unit\":\"IMAGE\"}",
                "{\"amount\":\"-1\",\"currency\":\"CNY\",\"unit\":\"IMAGE\"}",
                "{\"amount\":\"0.1234567\",\"currency\":\"CNY\",\"unit\":\"IMAGE\"}",
                "{\"amount\":\"1\",\"currency\":\"USD\",\"unit\":\"SECOND\"}",
                "{\"amount\":\"1\",\"currency\":\"USD\",\"unit\":\"IMAGE\",\"extra\":true}")) {
            assertThatThrownBy(() -> normalize("GOOGLE_NANO_BANANA_2", "{\"pricing\":" + price + "}"))
                    .isInstanceOf(ApiProblemException.class);
        }
    }

    @Test
    void omittedConfigurationPreservesProtocolBoundsAndUnknownPrice() {
        ObjectNode settings = normalize("GOOGLE_NANO_BANANA_2", "{}");
        assertThat(settings.isEmpty()).isTrue();
        assertThat(MediaCapabilityConfiguration.policy(registry.declaration("GOOGLE_NANO_BANANA_2"), settings)
                .maxReferenceImages()).isEqualTo(14);
    }

    private ObjectNode normalize(String adapter, String json) {
        JsonNode source = mapper.readTree(json);
        ObjectNode target = mapper.createObjectNode();
        MediaCapabilityConfiguration.normalize(mapper, registry.declaration(adapter), source, target);
        return target;
    }
}
