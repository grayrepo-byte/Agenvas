package dev.agenvas.task.domain;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** Exact source dimensions, never canvas layout, determine proportional pixel resizing. */
public record ImageResizeSpec(Mode mode, double value) {
    public enum Mode { PERCENTAGE, LONGEST_EDGE }
    public record Dimensions(int width, int height) {}
    public static final long MAX_OUTPUT_PIXELS = 40_000_000L;
    private static final double MIN_PERCENTAGE = 0.01;
    private static final double MAX_PERCENTAGE = 1000;
    private static final int MAX_LONGEST_EDGE = 40000;

    public static ImageResizeSpec parse(JsonNode parameters) {
        String mode = parameters.path("resizeMode").asText();
        if (Mode.PERCENTAGE.name().equals(mode)) {
            JsonNode percentage = parameters.path("percentage");
            double value = percentage.asDouble(Double.NaN);
            if (!percentage.isNumber() || !Double.isFinite(value)
                    || value < MIN_PERCENTAGE || value > MAX_PERCENTAGE || parameters.has("longestEdge")) {
                throw invalid();
            }
            return new ImageResizeSpec(Mode.PERCENTAGE, value);
        }
        if (Mode.LONGEST_EDGE.name().equals(mode)) {
            JsonNode edge = parameters.path("longestEdge");
            double value = edge.asDouble(Double.NaN);
            if (!edge.isNumber() || !Double.isFinite(value) || value != Math.rint(value)
                    || value < 1 || value > MAX_LONGEST_EDGE || parameters.has("percentage")) {
                throw invalid();
            }
            return new ImageResizeSpec(Mode.LONGEST_EDGE, value);
        }
        throw invalid();
    }

    /** Round each side to the nearest pixel; very thin images retain at least one pixel. */
    public Dimensions dimensions(int sourceWidth, int sourceHeight) {
        if (sourceWidth < 1 || sourceHeight < 1) throw invalid();
        double factor = mode == Mode.PERCENTAGE ? value / 100 : value / Math.max(sourceWidth, sourceHeight);
        long width = Math.max(1, Math.round(sourceWidth * factor));
        long height = Math.max(1, Math.round(sourceHeight * factor));
        // Check before multiplying or allocating so malformed/restored tasks cannot exhaust memory.
        if (width > MAX_OUTPUT_PIXELS || height > MAX_OUTPUT_PIXELS || width * height > MAX_OUTPUT_PIXELS) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.direct-media-task-service.invalid-media-task-input"),
                    ApiMessage.of("api.image-resize.pixel-limit"), false);
        }
        return new Dimensions((int) width, (int) height);
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.direct-media-task-service.invalid-media-task-input"),
                ApiMessage.of("api.image-resize.invalid-parameters"), false);
    }
}
