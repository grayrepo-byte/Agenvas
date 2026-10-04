package dev.agenvas.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ImageOperationValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsAndTaskSnapshotsDoNotExposeMutableSettings() {
        var supplied = mapper.createObjectNode().put("unused", "ignored");
        var relight = ImageOperationSpec.parse(mapper, ImageOperation.RELIGHT, supplied);
        assertThat(relight.parameters()).isEqualTo(mapper.readTree("""
                {"lightingPreset":"GOLDEN_HOUR","brightness":10,"colorTemperature":3200,
                 "lightX":0.15,"lightY":0.75}
                """));
        supplied.put("lightingPreset", "NEON_NIGHT");
        relight.parameters().put("lightingPreset", "MOONLIGHT");
        assertThat(relight.prompt("", null)).contains("warm golden-hour", "brightness adjustment 10", "3200K", "(0.15, 0.75)");
        assertThat(ImageOperationSpec.parse(mapper, ImageOperation.CROP, null).parameters())
                .isEqualTo(mapper.readTree("{\"x\":0.0,\"y\":0.0,\"width\":1.0,\"height\":1.0}"));
        assertThat(ImageOperationSpec.parse(mapper, ImageOperation.UPSCALE, null).prompt("", null))
                .contains("2x", "preserving its composition and subject identity");
        assertThat(ImageOperationSpec.parse(mapper, ImageOperation.ROTATE, null).parameters().path("quarterTurns").asInt())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("presetPrompts")
    void presetAndCameraInstructionsRemainSupported(ImageOperation operation, String field, String value, String instruction) {
        var spec = ImageOperationSpec.parse(mapper, operation, mapper.createObjectNode().put(field, value));
        assertThat(spec.prompt("keep identity", null)).contains(instruction, "keep identity");
    }

    static Stream<Arguments> presetPrompts() {
        return Stream.of(
                Arguments.of(ImageOperation.RELIGHT, "lightingPreset", "GOLDEN_HOUR", "warm golden-hour"),
                Arguments.of(ImageOperation.RELIGHT, "lightingPreset", "BLUE_HOUR", "cool blue-hour"),
                Arguments.of(ImageOperation.RELIGHT, "lightingPreset", "OVERCAST_SOFT", "soft overcast daylight"),
                Arguments.of(ImageOperation.RELIGHT, "lightingPreset", "MOONLIGHT", "cool moonlight"),
                Arguments.of(ImageOperation.RELIGHT, "lightingPreset", "SOFT_STUDIO", "soft studio"),
                Arguments.of(ImageOperation.RELIGHT, "lightingPreset", "NEON_NIGHT", "colorful neon-night"),
                Arguments.of(ImageOperation.VIEW_ANGLE, "viewAngle", "FRONT", "a straight-on front view"),
                Arguments.of(ImageOperation.VIEW_ANGLE, "viewAngle", "LEFT_THREE_QUARTER", "a left three-quarter view"),
                Arguments.of(ImageOperation.VIEW_ANGLE, "viewAngle", "RIGHT_THREE_QUARTER", "a right three-quarter view"),
                Arguments.of(ImageOperation.VIEW_ANGLE, "viewAngle", "LEFT_PROFILE", "a left profile view"),
                Arguments.of(ImageOperation.VIEW_ANGLE, "viewAngle", "RIGHT_PROFILE", "a right profile view"),
                Arguments.of(ImageOperation.VIEW_ANGLE, "viewAngle", "HIGH_ANGLE", "a high-angle view looking downward"),
                Arguments.of(ImageOperation.VIEW_ANGLE, "viewAngle", "LOW_ANGLE", "a low-angle view looking upward"),
                Arguments.of(ImageOperation.VIEW_ANGLE, "viewAngle", "BACK", "a straight-on back view"));
    }

    @Test
    void acceptsCropToleranceAndLightingEndpointsWithoutChangingCoordinates() {
        var crop = ImageOperationSpec.parse(mapper, ImageOperation.CROP,
                mapper.readTree("{\"width\":1.000001}"));
        assertThat(crop.parameters().path("width").asDouble()).isEqualTo(1.000001);
        var relight = ImageOperationSpec.parse(mapper, ImageOperation.RELIGHT,
                mapper.readTree("{\"brightness\":-100,\"colorTemperature\":10000,\"lightX\":0,\"lightY\":1}"));
        assertThat(relight.parameters().path("brightness").asInt()).isEqualTo(-100);
        assertThat(relight.parameters().path("lightY").asDouble()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void rejectsInvalidGeometryOrSelectionsWithTheExistingPublicError(ImageOperation operation, String json) {
        JsonNode parameters = mapper.readTree(json);
        assertThatThrownBy(() -> ImageOperationSpec.parse(mapper, operation, parameters))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(
                Arguments.of(ImageOperation.SMART_EDIT, "[]"),
                Arguments.of(ImageOperation.RELIGHT, "{\"lightingPreset\":\"UNKNOWN\"}"),
                Arguments.of(ImageOperation.RELIGHT, "{\"brightness\":101}"),
                Arguments.of(ImageOperation.RELIGHT, "{\"colorTemperature\":1999}"),
                Arguments.of(ImageOperation.RELIGHT, "{\"lightY\":1.1}"),
                Arguments.of(ImageOperation.UPSCALE, "{\"scale\":3}"),
                Arguments.of(ImageOperation.CROP, "{\"width\":0}"),
                Arguments.of(ImageOperation.CROP, "{\"x\":-0.1}"),
                Arguments.of(ImageOperation.CROP, "{\"x\":0.5,\"width\":0.500002}"),
                Arguments.of(ImageOperation.ROTATE, "{\"quarterTurns\":0}"),
                Arguments.of(ImageOperation.OUTPAINT, "{\"aspectRatio\":\"AUTO\"}"),
                Arguments.of(ImageOperation.THREE_VIEW, "{\"aspectRatio\":\"16:9\",\"threeViewType\":\"UNKNOWN\"}"),
                Arguments.of(ImageOperation.LAYER_SPLIT, "{\"layerTarget\":\"UNKNOWN\"}"),
                Arguments.of(ImageOperation.VIEW_ANGLE, "{\"viewAngle\":\"UNKNOWN\"}"));
    }
}
