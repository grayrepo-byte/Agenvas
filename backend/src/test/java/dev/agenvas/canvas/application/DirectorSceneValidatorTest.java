package dev.agenvas.canvas.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DirectorSceneValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsBoundedGeometryAndEmptyScenes() {
        ObjectNode scene = scene();
        assertThatCode(() -> DirectorSceneValidator.validate(scene)).doesNotThrowAnyException();
        scene.putArray("objects");
        assertThatCode(() -> DirectorSceneValidator.validate(scene)).doesNotThrowAnyException();
    }

    @Test
    void acceptsIndependentAxisScaleForEveryKindWithoutNormalizingV1OrV2() {
        for (String kind : List.of("ACTOR", "BOX", "SPHERE", "CYLINDER")) {
            ObjectNode legacy = scene();
            item(legacy).put("kind", kind).put("scale", 2.5);
            ObjectNode originalLegacy = legacy.deepCopy();
            assertThatCode(() -> DirectorSceneValidator.validate(legacy)).doesNotThrowAnyException();
            org.assertj.core.api.Assertions.assertThat(legacy).isEqualTo(originalLegacy);

            ObjectNode current = axisScene();
            item(current).put("kind", kind);
            ObjectNode originalCurrent = current.deepCopy();
            assertThatCode(() -> DirectorSceneValidator.validate(current)).doesNotThrowAnyException();
            org.assertj.core.api.Assertions.assertThat(current).isEqualTo(originalCurrent);
        }
        ObjectNode empty = axisScene();
        empty.putArray("objects");
        assertThatCode(() -> DirectorSceneValidator.validate(empty)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownVersionsAndMismatchedScaleRepresentations() {
        assertInvalid(scene().put("schemaVersion", 0));
        assertInvalid(scene().put("schemaVersion", 3));
        assertInvalid(scene().put("schemaVersion", 2));
        assertInvalid(axisScene().put("schemaVersion", 1));
    }

    @Test
    void axisScaleRequiresExactlyThreeFiniteBoundedNumbers() {
        List<Consumer<ObjectNode>> invalid = List.of(
                s -> scale(s).put("x", 0.09), s -> scale(s).put("y", 5.01),
                s -> scale(s).put("z", -1), s -> scale(s).put("x", Double.NaN),
                s -> scale(s).put("y", Double.POSITIVE_INFINITY),
                s -> scale(s).put("z", Double.NEGATIVE_INFINITY),
                s -> scale(s).put("x", "1"), s -> scale(s).putNull("y"),
                s -> scale(s).remove("z"), s -> scale(s).put("w", 1),
                s -> item(s).putNull("scale"), s -> item(s).putArray("scale").add(1).add(2).add(3));
        for (Consumer<ObjectNode> change : invalid) {
            ObjectNode scene = axisScene();
            change.accept(scene);
            assertInvalid(scene);
        }
    }

    @Test
    void rejectsUnknownFieldsAtEveryLevelAndMalformedRequiredFields() {
        List<Consumer<ObjectNode>> invalid = List.of(
                s -> s.put("assetUrl", "https://example.invalid/model.glb"),
                s -> camera(s).put("url", "https://example.invalid"),
                s -> item(s).put("texture", "image"),
                s -> ((ObjectNode) item(s).path("position")).put("w", 1),
                s -> s.remove("camera"), s -> camera(s).remove("target"),
                s -> s.put("schemaVersion", 4294967297L),
                s -> s.put("schemaVersion", 1.5), s -> s.put("aspectRatio", "21:9"),
                s -> item(s).put("kind", "MODEL"), s -> item(s).put("id", " "),
                s -> item(s).put("id", "x".repeat(65)), s -> item(s).put("name", "\n"),
                s -> item(s).put("name", "x".repeat(81)), s -> item(s).put("color", "red"),
                s -> camera(s).put("azimuth", "30"), s -> s.putNull("objects"));
        for (Consumer<ObjectNode> change : invalid) {
            ObjectNode scene = scene();
            change.accept(scene);
            assertInvalid(scene);
        }
        assertInvalid(null);
    }

    @Test
    void rejectsNonFiniteNumbersOutOfBoundsAndDuplicateIds() {
        List<Consumer<ObjectNode>> invalid = List.of(
                s -> camera(s).put("azimuth", Double.NaN),
                s -> camera(s).put("elevation", Double.POSITIVE_INFINITY),
                s -> camera(s).put("azimuth", 181), s -> camera(s).put("elevation", -81),
                s -> camera(s).put("distance", 0.9), s -> camera(s).put("distance", 51),
                s -> camera(s).put("focalLength", 17), s -> camera(s).put("focalLength", 121),
                s -> ((ObjectNode) camera(s).path("target")).put("x", 21),
                s -> ((ObjectNode) item(s).path("position")).put("z", -21),
                s -> item(s).put("rotation", -181), s -> item(s).put("scale", 0.09),
                s -> item(s).put("scale", 5.1),
                s -> ((ArrayNode) s.path("objects")).add(item(s).deepCopy()),
                s -> { for (int i = 1; i <= 32; i++) ((ArrayNode) s.path("objects"))
                        .add(item(s).deepCopy().put("id", "item-" + i)); });
        for (Consumer<ObjectNode> change : invalid) {
            ObjectNode scene = scene();
            change.accept(scene);
            assertInvalid(scene);
        }
    }

    @Test
    void acceptsTimedCamerasWithoutRewritingTheFrozenScene() {
        ObjectNode scene = motionScene();
        ObjectNode last = keyframe(scene).deepCopy().put("id", "end").put("timeSeconds", 5);
        frames(scene).add(last);
        for (String transition : List.of("LINEAR", "SMOOTH", "CUT")) {
            last.put("transition", transition);
            assertThatCode(() -> DirectorSceneValidator.validate(scene)).doesNotThrowAnyException();
        }
        ObjectNode original = scene.deepCopy();
        assertThatCode(() -> DirectorSceneValidator.validate(scene)).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(scene).isEqualTo(original);
    }

    @Test
    void rejectsMalformedAndUnboundedMotionAndLegacyMotionFields() {
        List<Consumer<ObjectNode>> invalid = List.of(
                s -> s.remove("motion"), s -> motion(s).put("durationSeconds", 0),
                s -> motion(s).put("durationSeconds", 121), s -> motion(s).put("url", "file:///tmp/test"),
                s -> keyframe(s).put("timeSeconds", -1), s -> keyframe(s).put("timeSeconds", 6),
                s -> keyframe(s).put("timeSeconds", Double.NaN), s -> keyframe(s).put("name", " "),
                s -> keyframe(s).put("transition", "SPLINE"), s -> keyframe(s).put("unexpected", true),
                s -> ((ObjectNode) keyframe(s).path("camera")).put("distance", 0),
                s -> frames(s).add(keyframe(s).deepCopy()),
                s -> frames(s).add(keyframe(s).deepCopy().put("id", "different")),
                s -> { for (int i = 1; i <= 24; i++) frames(s)
                        .add(keyframe(s).deepCopy().put("id", "key-" + i).put("timeSeconds", i / 10.0)); });
        for (Consumer<ObjectNode> change : invalid) {
            ObjectNode scene = motionScene();
            change.accept(scene);
            assertInvalid(scene);
        }
        assertInvalid(motionScene().put("schemaVersion", 2));
    }

    @Test
    void motionPayloadAndValidationLimitsMatchTheOpenApiContract() {
        java.nio.file.Path directory = java.nio.file.Path.of("").toAbsolutePath();
        while (directory != null && !java.nio.file.Files.exists(directory.resolve("contracts/openapi.yaml"))) directory = directory.getParent();
        org.assertj.core.api.Assertions.assertThat(directory).isNotNull();
        var yaml = new org.springframework.beans.factory.config.YamlMapFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource(directory.resolve("contracts/openapi.yaml")));
        var document = yaml.getObject();
        var components = (java.util.Map<?, ?>) document.get("components");
        var schemas = (java.util.Map<?, ?>) components.get("schemas");
        var scene = motionScene();
        for (var entry : java.util.Map.of("DirectorSceneV3", scene, "DirectorCameraMotion", motion(scene),
                "DirectorCameraKeyframe", keyframe(scene)).entrySet()) {
            var schema = (java.util.Map<?, ?>) schemas.get(entry.getKey());
            org.assertj.core.api.Assertions.assertThat(entry.getValue().propertyNames())
                    .containsExactlyInAnyOrderElementsOf(((List<?>) schema.get("required")).stream().map(String.class::cast).toList());
            org.assertj.core.api.Assertions.assertThat(schema.get("additionalProperties")).isEqualTo(false);
        }
        var trackProperties = (java.util.Map<?, ?>) ((java.util.Map<?, ?>) schemas.get("DirectorCameraMotion")).get("properties");
        var duration = (java.util.Map<?, ?>) trackProperties.get("durationSeconds");
        org.assertj.core.api.Assertions.assertThat(duration.get("minimum")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(duration.get("maximum")).isEqualTo(120);
        org.assertj.core.api.Assertions.assertThat(((java.util.Map<?, ?>) trackProperties.get("keyframes")).get("maxItems")).isEqualTo(24);
        var keyProperties = (java.util.Map<?, ?>) ((java.util.Map<?, ?>) schemas.get("DirectorCameraKeyframe")).get("properties");
        org.assertj.core.api.Assertions.assertThat(((java.util.Map<?, ?>) keyProperties.get("transition")).get("enum"))
                .isEqualTo(List.of("LINEAR", "SMOOTH", "CUT"));
    }

    private ObjectNode motionScene() {
        ObjectNode scene = axisScene().put("schemaVersion", 3);
        ObjectNode motion = scene.putObject("motion").put("durationSeconds", 5);
        ObjectNode key = motion.putArray("keyframes").addObject().put("id", "key-0").put("name", "Start")
                .put("timeSeconds", 0).put("transition", "SMOOTH");
        key.set("camera", camera(scene).deepCopy());
        return scene;
    }
    private ObjectNode motion(ObjectNode scene) { return (ObjectNode) scene.path("motion"); }
    private ArrayNode frames(ObjectNode scene) { return (ArrayNode) motion(scene).path("keyframes"); }
    private ObjectNode keyframe(ObjectNode scene) { return (ObjectNode) frames(scene).get(0); }

    private void assertInvalid(ObjectNode scene) {
        assertThatThrownBy(() -> DirectorSceneValidator.validate(scene))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ApiProblemException) e).code())
                        .isEqualTo("VALIDATION_ERROR"));
    }

    private ObjectNode scene() {
        return (ObjectNode) mapper.readTree("""
                {"schemaVersion":1,"aspectRatio":"16:9","camera":{
                  "azimuth":30,"elevation":15,"distance":8,"focalLength":50,
                  "target":{"x":0,"y":1,"z":0}},"objects":[{
                  "id":"actor-1","name":"Actor","kind":"ACTOR",
                  "position":{"x":0,"y":0,"z":0},"rotation":0,"scale":1,"color":"#aabbcc"}]}
                """);
    }

    private ObjectNode camera(ObjectNode scene) { return (ObjectNode) scene.path("camera"); }
    private ObjectNode item(ObjectNode scene) { return (ObjectNode) scene.path("objects").get(0); }
    private ObjectNode scale(ObjectNode scene) { return (ObjectNode) item(scene).path("scale"); }
    private ObjectNode axisScene() {
        ObjectNode scene = scene().put("schemaVersion", 2);
        item(scene).set("scale", mapper.createObjectNode().put("x", 0.1).put("y", 5).put("z", 1.75));
        return scene;
    }
}
