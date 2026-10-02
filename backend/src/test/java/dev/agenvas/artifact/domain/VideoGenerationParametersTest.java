package dev.agenvas.artifact.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VideoGenerationParametersTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void preservesAnExplicitTierInTheFrozenParameters() {
        var parameters = VideoGenerationParameters.parse(mapper.readTree("{\"aspectRatio\":\"9:16\",\"videoResolution\":\"768p\"}"));
        assertThat(parameters.aspectRatio()).isEqualTo("9:16");
        assertThat(parameters.videoResolution()).isEqualTo("768p");
        assertThat(parameters.toJson(mapper).path("videoResolution").asText()).isEqualTo("768p");
    }

    @Test void missingResolutionRemainsAbsentForOtherVideoProtocolsAndHistoricalInputs() {
        var parameters = VideoGenerationParameters.parse(mapper.createObjectNode());
        assertThat(parameters.aspectRatio()).isEqualTo("AUTO");
        assertThat(parameters.videoResolution()).isNull();
        assertThat(parameters.toJson(mapper).has("videoResolution")).isFalse();
    }

    @Test void rejectsInvalidResolutionValuesAndUnknownFields() {
        for (String input : List.of("{\"videoResolution\":\"2K\"}", "{\"videoResolution\":768}",
                "{\"videoResolution\":null}", "{\"resolution\":\"768p\"}")) {
            assertThatThrownBy(() -> VideoGenerationParameters.parse(mapper.readTree(input)))
                    .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        }
    }
}
