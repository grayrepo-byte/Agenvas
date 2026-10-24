package dev.agenvas.task.domain;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A fixed audio stream and encoder selection; clients cannot provide FFmpeg arguments. */
public record AudioExtractionParameters(Format format, int trackIndex) {
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 2;
    public static final int MP3_BITRATE_KBPS = 192;
    public static final int MAX_TRACK_INDEX = 31;
    private static final Set<String> FIELDS = Set.of("format", "trackIndex");

    public enum Format { WAV, MP3 }

    public AudioExtractionParameters {
        if (format == null || trackIndex < 0 || trackIndex > MAX_TRACK_INDEX) throw invalid();
    }

    /** Empty historic task parameters preserve first-track PCM WAV semantics. */
    public static AudioExtractionParameters parse(JsonNode parameters) {
        if (!parameters.isObject() || !FIELDS.containsAll(parameters.propertyNames())) throw invalid();
        JsonNode format = parameters.get("format"), track = parameters.get("trackIndex");
        Format selected = Format.WAV;
        if (format != null) {
            if (!format.isTextual()) throw invalid();
            try { selected = Format.valueOf(format.textValue()); }
            catch (IllegalArgumentException failure) { throw invalid(); }
        }
        if (track != null && (!track.isIntegralNumber() || !track.canConvertToInt())) throw invalid();
        return new AudioExtractionParameters(selected, track == null ? 0 : track.intValue());
    }

    public ObjectNode toJson(ObjectMapper mapper) {
        return mapper.createObjectNode().put("format", format.name()).put("trackIndex", trackIndex);
    }

    /** PCM output is predictable; reserve a conservative WAV header/metadata allowance. */
    public long maximumOutputBytes(int durationMs) {
        long payload = format == Format.WAV
                ? (long) durationMs * SAMPLE_RATE * CHANNELS * Short.BYTES / 1000
                : (long) durationMs * MP3_BITRATE_KBPS / 8;
        return payload + 4096;
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.media-function.title"), ApiMessage.of("api.media-function.invalid-request"), false);
    }
}
