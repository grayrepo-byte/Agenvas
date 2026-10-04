package dev.agenvas.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ImageOperationSpecTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void declaresModelExtensionsAsCloudImageOperations() {
        assertThat(new ImageOperation[] {
                ImageOperation.THREE_VIEW,
                ImageOperation.LAYER_SPLIT,
                ImageOperation.EXPRESSION_EDIT,
                ImageOperation.REMOVE_BACKGROUND,
                ImageOperation.OBJECT_REMOVE,
                ImageOperation.VIEW_ANGLE
        }).allMatch(ImageOperation::cloud);

        assertThat(new ImageOperation[] {
                ImageOperation.EXPRESSION_EDIT,
                ImageOperation.OBJECT_REMOVE
        }).allMatch(ImageOperation::instructionRequired);
    }

    @Test
    void freezesStructuredParametersForMultiViewLayersAndCameraAngle() {
        ImageOperationSpec threeView = parse(ImageOperation.THREE_VIEW,
                mapper.createObjectNode().put("aspectRatio", "16:9")
                        .put("threeViewType", "CHARACTER"));
        ImageOperationSpec foreground = parse(ImageOperation.LAYER_SPLIT,
                mapper.createObjectNode().put("layerTarget", "FOREGROUND"));
        ImageOperationSpec angle = parse(ImageOperation.VIEW_ANGLE,
                mapper.createObjectNode().put("viewAngle", "RIGHT_THREE_QUARTER"));

        assertThat(threeView.parameters().path("aspectRatio").asText()).isEqualTo("16:9");
        assertThat(threeView.parameters().path("threeViewType").asText()).isEqualTo("CHARACTER");
        assertThat(threeView.resultLabel())
                .isEqualTo("角色三视图");
        assertThat(foreground.parameters().path("layerTarget").asText()).isEqualTo("FOREGROUND");
        assertThat(foreground.resultLabel())
                .isEqualTo("主体图层");
        assertThat(angle.parameters().path("viewAngle").asText()).isEqualTo("RIGHT_THREE_QUARTER");
        assertThat(foreground.requiresTransparentOutput())
                .isTrue();
        assertThat(parse(ImageOperation.REMOVE_BACKGROUND, mapper.createObjectNode()).requiresTransparentOutput()).isTrue();
    }

    @Test
    void buildsProviderInstructionsForEveryNewAiOperation() {
        ObjectNode foreground = mapper.createObjectNode().put("layerTarget", "FOREGROUND");
        ObjectNode angle = mapper.createObjectNode().put("viewAngle", "RIGHT_PROFILE");

        assertThat(parse(ImageOperation.LAYER_SPLIT, foreground).prompt("main person", null))
                .contains("fully transparent background", "main person");
        assertThat(parse(ImageOperation.EXPRESSION_EDIT,
                mapper.createObjectNode()).prompt("gentle smile", null)).contains("facial expression", "gentle smile");
        assertThat(parse(ImageOperation.REMOVE_BACKGROUND,
                mapper.createObjectNode()).prompt("keep flowers", null)).contains("fully transparent background", "keep flowers");
        assertThat(parse(ImageOperation.OBJECT_REMOVE,
                mapper.createObjectNode()).prompt("right-hand person", null)).contains("reconstruct", "right-hand person");
        assertThat(parse(ImageOperation.VIEW_ANGLE, angle).prompt("same framing", null))
                .contains("right profile view", "same framing");
        assertThat(parse(ImageOperation.LAYER_SPLIT, foreground).promptKey()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "CHARACTER, image.three-view.character, 角色三视图",
            "FACE, image.three-view.face, 脸部三视图",
            "PROP, image.three-view.prop, 道具三视图",
            "SCENE_GRID, image.three-view.scene-grid, 场景宫格图"
    })
    void usesManagedContentForEachTypeAndAppendsOnlyNonBlankSubjectGuidance(
            String type, String key, String label) {
        var spec = parse(ImageOperation.THREE_VIEW, mapper.createObjectNode()
                .put("aspectRatio", "16:9").put("threeViewType", type));
        String managedContent = "Synthetic custom instruction for " + type;
        assertThat(spec.promptKey()).isEqualTo(key);
        assertThat(spec.resultLabel()).isEqualTo(label);
        assertThat(spec.prompt("retain accessories", managedContent))
                .isEqualTo(managedContent + " Subject guidance: retain accessories");
        assertThat(spec.prompt("", managedContent)).isEqualTo(managedContent);
        assertThat(spec.prompt("  ", managedContent)).isEqualTo(managedContent);
        assertThatThrownBy(() -> spec.prompt("", null)).isInstanceOf(NullPointerException.class);
    }

    private ImageOperationSpec parse(ImageOperation operation, ObjectNode parameters) {
        return ImageOperationSpec.parse(mapper, operation, parameters);
    }
}
