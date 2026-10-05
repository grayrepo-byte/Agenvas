package dev.agenvas.task.domain;

import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Normalized image operation settings, result naming and provider instructions. */
public final class ImageOperationSpec {
    private static final int MIN_RELIGHT_BRIGHTNESS = -100;
    private static final int MAX_RELIGHT_BRIGHTNESS = 100;
    private static final int DEFAULT_RELIGHT_BRIGHTNESS = 10;
    private static final int MIN_RELIGHT_COLOR_TEMPERATURE = 2000;
    private static final int MAX_RELIGHT_COLOR_TEMPERATURE = 10000;
    private static final int DEFAULT_RELIGHT_COLOR_TEMPERATURE = 3200;
    private static final double DEFAULT_LIGHT_X = 0.15;
    private static final double DEFAULT_LIGHT_Y = 0.75;
    private static final double MIN_NORMALIZED_POSITION = 0;
    private static final double MAX_NORMALIZED_POSITION = 1;
    // Preserve the tolerance used when accepting normalized crop coordinates.
    private static final double MAX_CROP_EDGE = 1.000001;
    private static final int MIN_UPSCALE_FACTOR = 2;
    private static final int MAX_UPSCALE_FACTOR = 4;
    private static final int MIN_QUARTER_TURNS = 1;
    private static final int MAX_QUARTER_TURNS = 3;
    private static final String DEFAULT_LIGHTING_PRESET = "GOLDEN_HOUR";
    private static final String DEFAULT_LAYER_TARGET = "FOREGROUND";
    private static final String DEFAULT_VIEW_ANGLE = "FRONT";
    private static final Map<String, String> RELIGHT_PRESETS = Map.of(
            DEFAULT_LIGHTING_PRESET, "warm golden-hour", "BLUE_HOUR", "cool blue-hour",
            "OVERCAST_SOFT", "soft overcast daylight", "MOONLIGHT", "cool moonlight",
            "SOFT_STUDIO", "soft studio", "NEON_NIGHT", "colorful neon-night");
    private static final Map<String, String> LAYER_RESULT_LABELS = Map.of(
            DEFAULT_LAYER_TARGET, "主体图层", "BACKGROUND", "背景图层");
    private record ThreeViewDefinition(String resultLabel, String promptKey) {}
    private static final Map<String, ThreeViewDefinition> THREE_VIEW_DEFINITIONS = Map.of(
            "CHARACTER", new ThreeViewDefinition("角色三视图", "image.three-view.character"),
            "FACE", new ThreeViewDefinition("脸部三视图", "image.three-view.face"),
            "PROP", new ThreeViewDefinition("道具三视图", "image.three-view.prop"),
            "SCENE_GRID", new ThreeViewDefinition("场景宫格图", "image.three-view.scene-grid"));
    private static final Map<String, String> VIEW_ANGLES = Map.of(
            DEFAULT_VIEW_ANGLE, "a straight-on front view",
            "LEFT_THREE_QUARTER", "a left three-quarter view",
            "RIGHT_THREE_QUARTER", "a right three-quarter view",
            "LEFT_PROFILE", "a left profile view", "RIGHT_PROFILE", "a right profile view",
            "HIGH_ANGLE", "a high-angle view looking downward",
            "LOW_ANGLE", "a low-angle view looking upward", "BACK", "a straight-on back view");

    private final ImageOperation operation;
    private final ObjectNode parameters;

    private ImageOperationSpec(ImageOperation operation, ObjectNode parameters) {
        this.operation = operation;
        this.parameters = parameters;
    }

    /** Returns a task snapshot without exposing the validated settings to mutation. */
    public ObjectNode parameters() {
        return parameters.deepCopy();
    }

    public static ImageOperationSpec parse(ObjectMapper mapper, ImageOperation operation, JsonNode supplied) {
        JsonNode source = supplied == null || supplied.isNull()
                ? mapper.createObjectNode() : supplied;
        if (!source.isObject()) throw invalid(ApiMessage.of("api.direct-media-task-service.image-processing-parameters-must-be-objects"));
        ObjectNode result = mapper.createObjectNode();
        switch (operation) {
            case DEPTH_MAP, SMART_EDIT, EXPRESSION_EDIT,
                    REMOVE_BACKGROUND, OBJECT_REMOVE, FLIP_HORIZONTAL, FLIP_VERTICAL -> { }
            case RELIGHT -> {
                String preset = source.path("lightingPreset").asText(DEFAULT_LIGHTING_PRESET);
                int brightness = source.path("brightness").asInt(DEFAULT_RELIGHT_BRIGHTNESS);
                int colorTemperature = source.path("colorTemperature").asInt(DEFAULT_RELIGHT_COLOR_TEMPERATURE);
                double lightX = source.path("lightX").asDouble(DEFAULT_LIGHT_X);
                double lightY = source.path("lightY").asDouble(DEFAULT_LIGHT_Y);
                if (!RELIGHT_PRESETS.containsKey(preset)) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.lighting-presets-are-not-supported"));
                }
                if (brightness < MIN_RELIGHT_BRIGHTNESS
                        || brightness > MAX_RELIGHT_BRIGHTNESS) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.the-lighting-brightness-must-be-between-100-and-100"));
                }
                if (colorTemperature < MIN_RELIGHT_COLOR_TEMPERATURE
                        || colorTemperature > MAX_RELIGHT_COLOR_TEMPERATURE) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.color-temperature-must-be-between-2000k-and-10000k"));
                }
                if (lightX < MIN_NORMALIZED_POSITION || lightX > MAX_NORMALIZED_POSITION
                        || lightY < MIN_NORMALIZED_POSITION || lightY > MAX_NORMALIZED_POSITION) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.the-light-source-position-must-be-within-the-image-range"));
                }
                result.put("lightingPreset", preset);
                result.put("brightness", brightness);
                result.put("colorTemperature", colorTemperature);
                result.put("lightX", lightX);
                result.put("lightY", lightY);
            }
            case RESIZE -> {
                var resize = ImageResizeSpec.parse(source);
                result.put("resizeMode", resize.mode().name());
                if (resize.mode() == ImageResizeSpec.Mode.PERCENTAGE) result.put("percentage", resize.value());
                else result.put("longestEdge", (int) resize.value());
            }
            case UPSCALE -> {
                int scale = source.path("scale").asInt(MIN_UPSCALE_FACTOR);
                if (scale != MIN_UPSCALE_FACTOR && scale != MAX_UPSCALE_FACTOR) throw invalid(ApiMessage.of("api.direct-media-task-service.magnification-can-only-be-2-or-4"));
                result.put("scale", scale);
            }
            case CROP -> {
                double x = source.path("x").asDouble(MIN_NORMALIZED_POSITION);
                double y = source.path("y").asDouble(MIN_NORMALIZED_POSITION);
                double width = source.path("width").asDouble(MAX_NORMALIZED_POSITION);
                double height = source.path("height").asDouble(MAX_NORMALIZED_POSITION);
                if (x < MIN_NORMALIZED_POSITION || y < MIN_NORMALIZED_POSITION
                        || width <= MIN_NORMALIZED_POSITION || height <= MIN_NORMALIZED_POSITION
                        || x + width > MAX_CROP_EDGE || y + height > MAX_CROP_EDGE) {
                    throw invalid(ApiMessage.of("api.direct-media-task-service.the-cropped-area-must-be-within-the-image"));
                }
                result.put("x", x); result.put("y", y);
                result.put("width", width); result.put("height", height);
            }
            case ROTATE -> {
                int turns = source.path("quarterTurns").asInt(MIN_QUARTER_TURNS);
                if (turns < MIN_QUARTER_TURNS || turns > MAX_QUARTER_TURNS) throw invalid(ApiMessage.of("api.direct-media-task-service.rotation-only-supports-90-180-or-270-degrees"));
                result.put("quarterTurns", turns);
            }
            case OUTPAINT, THREE_VIEW -> {
                String ratio = source.path("aspectRatio").asText("");
                if (!ImageGenerationParameters.ASPECT_RATIOS.contains(ratio)
                        || ImageGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio)) {
                    throw invalid(operation == ImageOperation.OUTPAINT
                            ? ApiMessage.of("api.direct-media-task-service.expanding-images-requires-choosing-a-clear-target-frame")
                            : ApiMessage.of("api.direct-media-task-service.three-views-require-a-clear-output-frame-to-be-selected"));
                }
                result.put("aspectRatio", ratio);
                if (operation == ImageOperation.THREE_VIEW) {
                    String type = source.path("threeViewType").asText("");
                    if (!THREE_VIEW_DEFINITIONS.containsKey(type)) {
                        throw invalid(ApiMessage.of("api.direct-media-task-service.three-view-types-are-not-supported"));
                    }
                    result.put("threeViewType", type);
                }
            }
            case LAYER_SPLIT -> {
                String target = source.path("layerTarget").asText(DEFAULT_LAYER_TARGET);
                if (!LAYER_RESULT_LABELS.containsKey(target)) throw invalid(ApiMessage.of("api.direct-media-task-service.layer-output-type-is-not-supported"));
                result.put("layerTarget", target);
            }
            case VIEW_ANGLE -> {
                String angle = source.path("viewAngle").asText(DEFAULT_VIEW_ANGLE);
                if (!VIEW_ANGLES.containsKey(angle)) throw invalid(ApiMessage.of("api.direct-media-task-service.target-perspective-is-not-supported"));
                result.put("viewAngle", angle);
            }
        }
        return new ImageOperationSpec(operation, result);
    }

    /** Parameters have already been normalized and validated before naming the result node. */
    public String resultLabel() {
        return switch (operation) {
            case THREE_VIEW -> THREE_VIEW_DEFINITIONS.get(parameters.path("threeViewType").asText()).resultLabel();
            case LAYER_SPLIT -> LAYER_RESULT_LABELS.get(parameters.path("layerTarget").asText());
            default -> operation.resultLabel();
        };
    }

    /** A registered function key is selected by the validated operation, never by user input. */
    public String promptKey() {
        return operation == ImageOperation.THREE_VIEW
                ? THREE_VIEW_DEFINITIONS.get(parameters.path("threeViewType").asText()).promptKey()
                : null;
    }

    /** Managed content is required for three views; other operations retain their fixed instructions. */
    public String prompt(String instruction, String managedContent) {
        return switch (operation) {
            case SMART_EDIT -> "Edit the provided image according to this instruction. Preserve all "
                    + "unmentioned subjects, identity, composition, and visual style. Instruction: "
                    + instruction;
            case RELIGHT -> "Relight the provided image realistically while preserving subject identity, "
                    + "geometry, materials, composition, and camera view. Use a "
                    + RELIGHT_PRESETS.get(parameters.path("lightingPreset").asText())
                    + " lighting style, brightness adjustment "
                    + parameters.path("brightness").asInt() + " on a -100 to 100 scale, color "
                    + "temperature " + parameters.path("colorTemperature").asInt()
                    + "K, and a normalized light source position of ("
                    + parameters.path("lightX").asDouble() + ", "
                    + parameters.path("lightY").asDouble()
                    + "), where (0, 0) is top-left and (1, 1) is bottom-right."
                    + (instruction.isBlank() ? "" : " Additional lighting direction: " + instruction);
            case OUTPAINT -> "Extend the provided image naturally to "
                    + parameters.path("aspectRatio").asText() + ". Preserve the original image exactly "
                    + "inside the expanded canvas and continue its scene, perspective, lighting, and style."
                    + (instruction.isBlank() ? "" : " Additional instruction: " + instruction);
            case THREE_VIEW -> Objects.requireNonNull(managedContent, "Three views require a managed prompt")
                    + (instruction.isBlank() ? "" : " Subject guidance: " + instruction);
            case LAYER_SPLIT -> "FOREGROUND".equals(parameters.path("layerTarget").asText())
                    ? "Extract the primary foreground subject from the provided image as a clean isolated "
                            + "layer on a fully transparent background. Preserve fine edges, hair, materials, "
                            + "colors, and all subject details."
                            + (instruction.isBlank() ? "" : " Subject guidance: " + instruction)
                    : "Reconstruct a clean background layer from the provided image with all foreground "
                            + "subjects removed. Fill occluded regions seamlessly while preserving the scene, "
                            + "perspective, lighting, and visual style."
                            + (instruction.isBlank() ? "" : " Background guidance: " + instruction);
            case EXPRESSION_EDIT -> "Change only the subject's facial expression according to the instruction. "
                    + "Preserve identity, face shape, hair, pose, clothing, composition, lighting, and style. "
                    + "Instruction: " + instruction;
            case REMOVE_BACKGROUND -> "Remove the entire background from the provided image and return the "
                    + "primary subject on a fully transparent background. Preserve fine edges, hair, shadows "
                    + "belonging to the subject, original colors, and full subject detail."
                    + (instruction.isBlank() ? "" : " Subject guidance: " + instruction);
            case OBJECT_REMOVE -> "Remove only the described object or region from the provided image and "
                    + "reconstruct the newly exposed background seamlessly. Preserve all other pixels, "
                    + "subjects, perspective, lighting, and style. Target: " + instruction;
            case VIEW_ANGLE -> "Re-render the same subject from "
                    + VIEW_ANGLES.get(parameters.path("viewAngle").asText())
                    + " while preserving identity, proportions, clothing, materials, environment, lighting, "
                    + "and visual style."
                    + (instruction.isBlank() ? "" : " Additional guidance: " + instruction);
            case DEPTH_MAP -> "Extract a relative monocular depth map from the source image.";
            case UPSCALE -> "Upscale the source image by " + parameters.path("scale").asInt()
                    + "x while preserving its composition and subject identity.";
            case RESIZE -> "Local proportional resize";
            case CROP -> "Local crop";
            case ROTATE -> "Local rotation";
            case FLIP_HORIZONTAL -> "Local horizontal mirror";
            case FLIP_VERTICAL -> "Local vertical mirror";
        };
    }

    public boolean requiresTransparentOutput() {
        return operation == ImageOperation.REMOVE_BACKGROUND
                || operation == ImageOperation.LAYER_SPLIT
                        && "FOREGROUND".equals(parameters.path("layerTarget").asText());
    }

    private static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.direct-media-task-service.invalid-media-task-input"), detail, false);
    }
}
