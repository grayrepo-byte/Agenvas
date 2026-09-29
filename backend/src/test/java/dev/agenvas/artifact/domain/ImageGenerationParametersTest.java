package dev.agenvas.artifact.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.shared.error.ApiProblemException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ImageGenerationParametersTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fillsCompatibleDefaultsAndRoundTripsEveryAtomicControl() {
        var defaults = ImageGenerationParameters.parse(mapper.createObjectNode());
        assertThat(defaults).isEqualTo(new ImageGenerationParameters(
                "AUTO", "1K", "medium", false, 1, false));

        var selected = ImageGenerationParameters.parse(mapper.readTree("""
                {"aspectRatio":"9:16","resolution":"4K","quality":"high",
                 "transparentBackground":true,"generationCount":4,
                 "openNewNodeOnGenerate":true}
                """));
        assertThat(selected.toJson(mapper)).isEqualTo(mapper.readTree("""
                {"aspectRatio":"9:16","resolution":"4K","quality":"high",
                 "transparentBackground":true,"generationCount":4,
                 "openNewNodeOnGenerate":true}
                """));
    }

    @Test
    void rejectsUnknownFieldsAndUnsupportedCounts() {
        assertThatThrownBy(() -> ImageGenerationParameters.parse(
                mapper.readTree("{\"seed\":42}"))).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> ImageGenerationParameters.parse(
                mapper.readTree("{\"generationCount\":3}")))
                .isInstanceOf(ApiProblemException.class);
    }
}
