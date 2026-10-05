package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.shared.error.ApiProblemException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VideoGenerationParametersTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsToProjectRatioAndAcceptsExplicitSupportedRatios() {
        assertThat(VideoGenerationParameters.parse(mapper.createObjectNode()).aspectRatio())
                .isEqualTo("AUTO");
        assertThat(VideoGenerationParameters.parse(
                mapper.createObjectNode().put("aspectRatio", "9:16")).aspectRatio())
                .isEqualTo("9:16");
    }

    @Test
    void rejectsImageOnlyAndUnknownVideoParameters() {
        assertThatThrownBy(() -> VideoGenerationParameters.parse(
                mapper.createObjectNode().put("resolution", "2K")))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> VideoGenerationParameters.parse(
                mapper.createObjectNode().put("aspectRatio", "21:9")))
                .isInstanceOf(ApiProblemException.class);
    }
}
