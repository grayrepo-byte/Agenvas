package dev.agenvas.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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
        ObjectNode ratio = mapper.createObjectNode().put("aspectRatio", "16:9")
                .put("threeViewType", "CHARACTER");
        ObjectNode foreground = mapper.createObjectNode().put("layerTarget", "FOREGROUND");
        ObjectNode angle = mapper.createObjectNode().put("viewAngle", "RIGHT_PROFILE");

        assertThat(parse(ImageOperation.THREE_VIEW, ratio).prompt("red dress"))
                .contains("full-body character turnaround", "straight front", "exact side profile",
                        "straight back", "red dress");
        assertThat(parse(ImageOperation.THREE_VIEW,
                ratio.put("threeViewType", "FACE")).prompt("same person"))
                .contains("facial turnaround", "three-quarter", "side profile", "same person");
        assertThat(parse(ImageOperation.THREE_VIEW,
                ratio.put("threeViewType", "PROP")).prompt("antique sword"))
                .contains("prop turnaround", "orthographic views", "antique sword");
        assertThat(parse(ImageOperation.THREE_VIEW,
                ratio.put("threeViewType", "SCENE_GRID")).prompt("empty courtyard"))
                .contains("2 by 2 environment reference grid", "reverse view", "key-detail view",
                        "empty courtyard");
        assertThat(parse(ImageOperation.THREE_VIEW, ratio).resultLabel())
                .isEqualTo("场景宫格图");
        assertThat(parse(ImageOperation.LAYER_SPLIT, foreground).prompt("main person"))
                .contains("fully transparent background", "main person");
        assertThat(parse(ImageOperation.EXPRESSION_EDIT,
                mapper.createObjectNode()).prompt("gentle smile")).contains("facial expression", "gentle smile");
        assertThat(parse(ImageOperation.REMOVE_BACKGROUND,
                mapper.createObjectNode()).prompt("keep flowers")).contains("fully transparent background", "keep flowers");
        assertThat(parse(ImageOperation.OBJECT_REMOVE,
                mapper.createObjectNode()).prompt("right-hand person")).contains("reconstruct", "right-hand person");
        assertThat(parse(ImageOperation.VIEW_ANGLE, angle).prompt("same framing"))
                .contains("right profile view", "same framing");
    }

    private ImageOperationSpec parse(ImageOperation operation, ObjectNode parameters) {
        return ImageOperationSpec.parse(mapper, operation, parameters);
    }
}
