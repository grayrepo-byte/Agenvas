package dev.agenvas.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.agenvas.shared.error.ApiProblemException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ImageResizeSpecTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void roundsLandscapePortraitAndTinyResultsAndFreezesMode() {
        var percentage = mapper.readTree("{\"resizeMode\":\"PERCENTAGE\",\"percentage\":33.3}");
        var spec = ImageResizeSpec.parse(percentage);
        assertThat(spec.dimensions(1200, 800)).isEqualTo(new ImageResizeSpec.Dimensions(400, 266));
        assertThat(spec.dimensions(800, 1200)).isEqualTo(new ImageResizeSpec.Dimensions(266, 400));
        assertThat(spec.dimensions(1, 1)).isEqualTo(new ImageResizeSpec.Dimensions(1, 1));
        assertThat(ImageOperationSpec.parse(mapper, ImageOperation.RESIZE, percentage).parameters()).isEqualTo(percentage);
        var edge = ImageResizeSpec.parse(mapper.readTree("{\"resizeMode\":\"LONGEST_EDGE\",\"longestEdge\":1024}"));
        assertThat(edge.dimensions(800, 1200)).isEqualTo(new ImageResizeSpec.Dimensions(683, 1024));
        assertThat(edge.dimensions(1200, 800)).isEqualTo(new ImageResizeSpec.Dimensions(1024, 683));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"resizeMode\":\"INVALID\"}",
        "{\"resizeMode\":\"PERCENTAGE\",\"percentage\":0}", "{\"resizeMode\":\"PERCENTAGE\",\"percentage\":1001}",
        "{\"resizeMode\":\"PERCENTAGE\",\"percentage\":\"50\"}",
        "{\"resizeMode\":\"PERCENTAGE\",\"percentage\":50,\"longestEdge\":1024}",
        "{\"resizeMode\":\"LONGEST_EDGE\",\"longestEdge\":1.5}", "{\"resizeMode\":\"LONGEST_EDGE\",\"longestEdge\":40001}",
        "{\"resizeMode\":\"LONGEST_EDGE\",\"longestEdge\":-1}", "{\"resizeMode\":\"LONGEST_EDGE\",\"longestEdge\":null}"})
    void rejectsInvalidOrAmbiguousInputs(String json) {
        assertThatThrownBy(() -> ImageOperationSpec.parse(mapper, ImageOperation.RESIZE, mapper.readTree(json)))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    @Test void enforcesPixelLimitBeforeAllocation() {
        var spec = ImageResizeSpec.parse(mapper.readTree("{\"resizeMode\":\"LONGEST_EDGE\",\"longestEdge\":40000}"));
        assertThatThrownBy(() -> spec.dimensions(1000, 1000)).isInstanceOf(ApiProblemException.class);
        assertThat(spec.dimensions(40000, 1)).isEqualTo(new ImageResizeSpec.Dimensions(40000, 1));
    }
}
