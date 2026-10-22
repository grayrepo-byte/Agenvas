package dev.agenvas.canvas.application;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** A director scene is bounded local geometry, never a source of URLs or executable asset data. */
final class DirectorSceneValidator {
    private static final Set<String> SCENE_FIELDS = Set.of("schemaVersion", "aspectRatio", "camera", "objects");
    private static final Set<String> CAMERA_FIELDS = Set.of("azimuth", "elevation", "distance", "target", "focalLength");
    private static final Set<String> OBJECT_FIELDS = Set.of("id", "name", "kind", "position", "rotation", "scale", "color");
    private static final Set<String> VECTOR_FIELDS = Set.of("x", "y", "z");
    private static final Set<String> ASPECT_RATIOS = Set.of("16:9", "9:16", "1:1", "4:3");
    private static final Set<String> KINDS = Set.of("ACTOR", "BOX", "SPHERE", "CYLINDER");

    private DirectorSceneValidator() {}

    static void validate(JsonNode scene) {
        object(scene, SCENE_FIELDS);
        JsonNode schemaVersion = scene.path("schemaVersion");
        if (!schemaVersion.isIntegralNumber() || !schemaVersion.canConvertToInt() || schemaVersion.asInt() != 1
                || !ASPECT_RATIOS.contains(text(scene.path("aspectRatio"), 4))) throw invalid();
        JsonNode camera = scene.path("camera");
        object(camera, CAMERA_FIELDS);
        number(camera.path("azimuth"), -180, 180);
        number(camera.path("elevation"), -80, 80);
        number(camera.path("distance"), 1, 50);
        number(camera.path("focalLength"), 18, 120);
        vector(camera.path("target"));
        JsonNode objects = scene.path("objects");
        if (!objects.isArray() || objects.size() > 32) throw invalid();
        Set<String> ids = new HashSet<>();
        for (JsonNode item : objects) {
            object(item, OBJECT_FIELDS);
            if (!ids.add(text(item.path("id"), 64))) throw invalid();
            text(item.path("name"), 80);
            if (!KINDS.contains(text(item.path("kind"), 8))) throw invalid();
            vector(item.path("position"));
            number(item.path("rotation"), -180, 180);
            number(item.path("scale"), 0.1, 5);
            if (!text(item.path("color"), 7).matches("#[0-9a-fA-F]{6}")) throw invalid();
        }
    }

    private static void vector(JsonNode node) {
        object(node, VECTOR_FIELDS);
        for (String field : VECTOR_FIELDS) number(node.path(field), -20, 20);
    }

    private static void object(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw invalid();
        for (String field : fields) if (!node.has(field)) throw invalid();
    }

    private static String text(JsonNode node, int maxLength) {
        if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > maxLength) throw invalid();
        return node.asText();
    }

    private static void number(JsonNode node, double minimum, double maximum) {
        if (!node.isNumber()) throw invalid();
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw invalid();
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.canvas-controller.canvas-command-is-invalid"),
                ApiMessage.of("api.canvas-service.invalid-director-scene"), false);
    }
}
