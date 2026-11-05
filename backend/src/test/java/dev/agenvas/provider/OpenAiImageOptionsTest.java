package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.OpenAiImageOptions;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class OpenAiImageOptionsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest @ValueSource(strings = {"auto", "low"})
    void retainsExplicitModerationAcrossStaticAndGifCapabilities(String moderation) {
        for (String format : List.of("png", "gif")) {
            var source = mapper.createObjectNode().put("model", "gpt-image-2.6-flare")
                    .put("outputFormat", format).put("moderation", moderation);
            var target = mapper.createObjectNode().put("model", "gpt-image-2.6-flare");
            OpenAiImageOptions.normalize(source, target);
            assertThat(OpenAiImageOptions.parse(target).moderation()).isEqualTo(moderation);
        }
    }

    @Test void historicalSettingsKeepOmissionAndTheirOriginalHashInput() {
        var target = mapper.createObjectNode();
        OpenAiImageOptions.normalize(mapper.createObjectNode(), target);
        assertThat(target.isEmpty()).isTrue();
        assertThat(OpenAiImageOptions.parse(target).moderation()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"null", "false", "2", "\"off\"", "\"LOW\"", "\"\""})
    void rejectsModerationValuesOutsideTheOfficialEnum(String value) {
        assertThatThrownBy(() -> OpenAiImageOptions.parse(mapper.readTree("{\"moderation\":" + value + "}")))
                .isInstanceOf(ApiProblemException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"gpt-image-2.6-flare", "gpt-image-2.6-sunburst"})
    void declaresGifGenerationWithoutPretendingToSupportImageEdits(String model) {
        var settings = mapper.createObjectNode().put("model", model).put("outputFormat", "gif").put("complexity", "medium");
        assertThat(OpenAiImageOptions.parse(settings)).isEqualTo(new OpenAiImageOptions("gif", "medium", 9, 200));
        var declaration = OpenAiImageOptions.declaration(new MediaAdapterRegistry(List.of()).declaration("OPENAI_GPT_IMAGE_2"), settings);
        assertThat(declaration.maxReferenceImages()).isZero();
        assertThat(declaration.supportsImageMask()).isFalse();
        assertThat(declaration.supportsTransparentBackground()).isFalse();
        assertThat(declaration.supportedImageAspectRatios()).containsExactly("AUTO");
        assertThat(declaration.supportedImageQualities()).contains("xhigh", "max");
    }

    @ParameterizedTest @ValueSource(strings = {
        "{\"model\":\"gpt-image-2\",\"outputFormat\":\"gif\"}",
        "{\"model\":\"gpt-image-2.6-flare\",\"outputFormat\":\"svg\"}",
        "{\"model\":\"gpt-image-2.6-flare\",\"frameCount\":9}",
        "{\"model\":\"gpt-image-2.6-flare\",\"outputFormat\":\"gif\",\"frameCount\":17}",
        "{\"model\":\"gpt-image-2.6-flare\",\"outputFormat\":\"gif\",\"frameCount\":1}",
        "{\"model\":\"gpt-image-2.6-flare\",\"outputFormat\":\"gif\",\"frameDurationMs\":25}",
        "{\"model\":\"gpt-image-2.6-flare\",\"outputFormat\":\"gif\",\"frameDurationMs\":2001}",
        "{\"model\":\"custom-image\",\"complexity\":\"high\"}",
        "{\"outputFormat\":null}"})
    void rejectsUnsupportedOrAmbiguousOptions(String settings) {
        assertThatThrownBy(() -> OpenAiImageOptions.parse(mapper.readTree(settings))).isInstanceOf(ApiProblemException.class);
    }
}
