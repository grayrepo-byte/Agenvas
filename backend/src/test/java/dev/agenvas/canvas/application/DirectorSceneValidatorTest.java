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
}
