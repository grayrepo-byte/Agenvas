package dev.agenvas.task.domain;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exact source-relative milliseconds and a fixed encoder; no client FFmpeg options are accepted. */
public record AudioTrimParameters(int startMs, int endMs, AudioExtractionParameters.Format format) {
    public static final int MAX_SOURCE_DURATION_MS = 600_000;
    public static final int MIN_DURATION_MS = 100;
    public static final long MAX_SOURCE_BYTES = 50L * 1024 * 1024;
    public static final long MAX_OUTPUT_BYTES = 50L * 1024 * 1024;
    private static final Set<String> FIELDS = Set.of("startMs", "endMs", "format");

    public AudioTrimParameters {
        if (startMs < 0 || endMs > MAX_SOURCE_DURATION_MS || endMs - (long) startMs < MIN_DURATION_MS
                || format == null) throw invalid();
    }

    public int durationMs() { return endMs - startMs; }

    public ObjectNode toJson(ObjectMapper mapper) {
        return mapper.createObjectNode().put("startMs", startMs).put("endMs", endMs).put("format", format.name());
    }

    public static AudioTrimParameters parse(JsonNode parameters) {
        if (parameters == null || !parameters.isObject() || !FIELDS.equals(parameters.propertyNames())) throw invalid();
        JsonNode start = parameters.path("startMs"), end = parameters.path("endMs"), format = parameters.path("format");
        if (!start.isIntegralNumber() || !start.canConvertToInt() || !end.isIntegralNumber()
                || !end.canConvertToInt() || !format.isString()) throw invalid();
        try { return new AudioTrimParameters(start.intValue(), end.intValue(), AudioExtractionParameters.Format.valueOf(format.asText())); }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }

    /** Reject source/range and predicted output bounds before a derived node or Task is created. */
    public void requireSource(Asset asset) {
        if (asset.mediaKind() != Asset.MediaKind.AUDIO || asset.durationMs() == null
                || asset.durationMs() < MIN_DURATION_MS || asset.durationMs() > MAX_SOURCE_DURATION_MS
                || asset.byteSize() < 1 || asset.byteSize() > MAX_SOURCE_BYTES || endMs > asset.durationMs()) throw invalid();
        if (new AudioExtractionParameters(format, 0).maximumOutputBytes(durationMs()) > MAX_OUTPUT_BYTES)
            throw outputTooLarge();
    }

    public static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "AUDIO_TRIM_INVALID",
                ApiMessage.of("api.audio-trim.invalid"), ApiMessage.of("api.audio-trim.invalid"), false);
    }

    public static ApiProblemException outputTooLarge() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "AUDIO_TRIM_OUTPUT_TOO_LARGE",
                ApiMessage.of("api.audio-trim.invalid"), ApiMessage.of("api.audio-trim.output-too-large"), false);
    }
}
