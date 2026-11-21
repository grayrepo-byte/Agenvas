package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.OpenAiImageOptions;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ImageGenerationPreparationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaAdapterRegistry registry = new MediaAdapterRegistry(List.of());

    @ParameterizedTest
    @CsvSource({
            "OPENAI_GPT_IMAGE_2, resolution, '\"8K\"'",
            "OPENAI_GPT_IMAGE_2, resolution, null",
            "OPENAI_GPT_IMAGE_2, resolution, 4096",
            "OPENAI_GPT_IMAGE_2, quality, '\"max\"'",
            "GOOGLE_NANO_BANANA_2, quality, '\"high\"'",
            "COMFY_IMAGE_V1, resolution, '\"1K\"'",
            "RUNNINGHUB_IMAGE, resolution, '\"4K\"'",
            "LOCAL_IMAGE_PROCESSOR, quality, '\"low\"'"
    })
    void rejectsExplicitControlsOutsideTheBoundProtocol(String adapterId, String field, String json) {
        var request = mapper.createObjectNode().set(field, mapper.readTree(json));
        assertThatThrownBy(() -> ImageGenerationPreparation.operationParameters(mapper, adapterId,
                registry.declaration(adapterId), mapper.createObjectNode(), request, "1:1", false))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void operationOverridesMatchTheOptionalOpenApiControlsAndModelSpecificQualities() {
        var yaml = new YamlMapFactoryBean();
        yaml.setResources(new FileSystemResource("../contracts/openapi.yaml"));
        var contract = mapper.valueToTree(yaml.getObject()).at("/components/schemas/ImageOperationParameters");
        assertThat(contract.path("required").valueStream().map(JsonNode::asText))
                .doesNotContain("resolution", "quality");
        assertThat(contract.at("/properties/resolution/enum").valueStream().map(JsonNode::asText))
                .containsExactlyInAnyOrderElementsOf(ImageGenerationParameters.RESOLUTIONS);
        assertThat(contract.at("/properties/quality/enum").valueStream().map(JsonNode::asText))
                .containsExactlyInAnyOrderElementsOf(OpenAiImageOptions.EXTENDED_QUALITIES);

        var settings = mapper.createObjectNode().put("model", OpenAiImageOptions.FLARE);
        var policy = OpenAiImageOptions.declaration(registry.declaration(MediaAdapterRegistry.OPENAI_GPT_IMAGE_2),
                settings);
        var request = mapper.createObjectNode().put("quality", "max");
        var output = ImageGenerationPreparation.operationParameters(mapper, MediaAdapterRegistry.OPENAI_GPT_IMAGE_2,
                policy, settings, request, "2:3", true);
        assertThat(output).isEqualTo(new ImageGenerationParameters("2:3", "1K", "max", true, 1));
    }
}
