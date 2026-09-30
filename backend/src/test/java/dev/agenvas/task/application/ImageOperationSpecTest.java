package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.task.domain.ImageOperation;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ImageOperationSpecTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DirectMediaTaskService service = new DirectMediaTaskService(
            null, null, null, null, null, null, null, null, null, null, mapper,
            Clock.systemUTC());

    @Test
    void declaresAllSevenExtensionsAsCloudImageOperations() {
        assertThat(new ImageOperation[] {
                ImageOperation.THREE_VIEW,
                ImageOperation.LAYER_SPLIT,
                ImageOperation.EXPRESSION_EDIT,
                ImageOperation.BRUSH_MARKUP,
                ImageOperation.REMOVE_BACKGROUND,
                ImageOperation.OBJECT_REMOVE,
                ImageOperation.VIEW_ANGLE
        }).allMatch(ImageOperation::cloud);

        assertThat(new ImageOperation[] {
                ImageOperation.EXPRESSION_EDIT,
                ImageOperation.BRUSH_MARKUP,
                ImageOperation.OBJECT_REMOVE
        }).allMatch(ImageOperation::instructionRequired);
    }

    @Test
    void freezesStructuredParametersForMultiViewLayersAndCameraAngle() {
        ObjectNode threeView = service.normalizeOperationParameters(ImageOperation.THREE_VIEW,
                mapper.createObjectNode().put("aspectRatio", "16:9")
                        .put("threeViewType", "CHARACTER"));
        ObjectNode foreground = service.normalizeOperationParameters(ImageOperation.LAYER_SPLIT,
                mapper.createObjectNode().put("layerTarget", "FOREGROUND"));
        ObjectNode angle = service.normalizeOperationParameters(ImageOperation.VIEW_ANGLE,
                mapper.createObjectNode().put("viewAngle", "RIGHT_THREE_QUARTER"));

        assertThat(threeView.path("aspectRatio").asText()).isEqualTo("16:9");
        assertThat(threeView.path("threeViewType").asText()).isEqualTo("CHARACTER");
        assertThat(foreground.path("layerTarget").asText()).isEqualTo("FOREGROUND");
        assertThat(angle.path("viewAngle").asText()).isEqualTo("RIGHT_THREE_QUARTER");
        assertThat(service.requiresTransparentOutput(ImageOperation.LAYER_SPLIT, foreground))
                .isTrue();
        assertThat(service.requiresTransparentOutput(ImageOperation.REMOVE_BACKGROUND,
                mapper.createObjectNode())).isTrue();
    }

    @Test
    void buildsProviderInstructionsForEveryNewAiOperation() {
        ObjectNode ratio = mapper.createObjectNode().put("aspectRatio", "16:9")
                .put("threeViewType", "CHARACTER");
        ObjectNode foreground = mapper.createObjectNode().put("layerTarget", "FOREGROUND");
        ObjectNode angle = mapper.createObjectNode().put("viewAngle", "RIGHT_PROFILE");

        assertThat(service.operationPrompt(ImageOperation.THREE_VIEW, "red dress", ratio))
                .contains("full-body character turnaround", "straight front", "exact side profile",
                        "straight back", "red dress");
        assertThat(service.operationPrompt(ImageOperation.THREE_VIEW, "same person",
                ratio.put("threeViewType", "FACE")))
                .contains("facial turnaround", "three-quarter", "side profile", "same person");
        assertThat(service.operationPrompt(ImageOperation.THREE_VIEW, "antique sword",
                ratio.put("threeViewType", "PROP")))
                .contains("prop turnaround", "orthographic views", "antique sword");
        assertThat(service.operationPrompt(ImageOperation.THREE_VIEW, "empty courtyard",
                ratio.put("threeViewType", "SCENE_GRID")))
                .contains("2 by 2 environment reference grid", "reverse view", "key-detail view",
                        "empty courtyard");
        assertThat(service.operationPrompt(ImageOperation.LAYER_SPLIT, "main person", foreground))
                .contains("fully transparent background", "main person");
        assertThat(service.operationPrompt(ImageOperation.EXPRESSION_EDIT, "gentle smile",
                mapper.createObjectNode())).contains("facial expression", "gentle smile");
        assertThat(service.operationPrompt(ImageOperation.BRUSH_MARKUP, "circle the pin",
                mapper.createObjectNode())).contains("visual annotations", "circle the pin");
        assertThat(service.operationPrompt(ImageOperation.REMOVE_BACKGROUND, "keep flowers",
                mapper.createObjectNode())).contains("fully transparent background", "keep flowers");
        assertThat(service.operationPrompt(ImageOperation.OBJECT_REMOVE, "right-hand person",
                mapper.createObjectNode())).contains("reconstruct", "right-hand person");
        assertThat(service.operationPrompt(ImageOperation.VIEW_ANGLE, "same framing", angle))
                .contains("right profile view", "same framing");
    }
}
