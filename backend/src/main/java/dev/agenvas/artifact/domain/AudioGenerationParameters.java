package dev.agenvas.artifact.domain;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Speech controls persisted in a card draft and frozen before synthesis. */
public record AudioGenerationParameters(String speaker, int speechRate, int loudnessRate, int pitchRate) {
    public static final int MAX_PROMPT_LENGTH = 3000;
    public static final int MAX_GENERATION_SECONDS = 120;
    public static final int MAX_REFERENCE_AUDIOS = 3;
    public static final int MAX_REFERENCE_IMAGES = 1;
    public static final int MAX_REFERENCE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_REFERENCE_DURATION_MS = 30_000;
    public static final String DEFAULT_SPEAKER = "";
    public static final int DEFAULT_RATE = 0;
    public static final int MIN_RATE = -50;
    public static final int MAX_RATE = 100;
    public static final int MIN_PITCH_RATE = -12;
    public static final int MAX_PITCH_RATE = 12;
    private static final String SPEAKER_PATTERN = "[A-Za-z0-9_-]{0,120}";
    private static final Set<String> FIELDS = Set.of("speaker", "speechRate", "loudnessRate", "pitchRate");

    public static AudioGenerationParameters parse(JsonNode source) {
        if (source == null || source.isMissingNode() || source.isNull())
            return new AudioGenerationParameters(DEFAULT_SPEAKER, DEFAULT_RATE, DEFAULT_RATE, DEFAULT_RATE);
        if (!source.isObject() || !FIELDS.containsAll(source.propertyNames()))
            throw invalid(ApiMessage.of("api.audio-generation-parameters.audio-parameters-only-accept-timbre-speech-rate-volume-and-pitch"));
        JsonNode voice = source.get("speaker");
        String speaker = voice == null ? DEFAULT_SPEAKER : voice.asText("");
        if ((voice != null && !voice.isTextual()) || !speaker.matches(SPEAKER_PATTERN))
            throw invalid(ApiMessage.of("api.audio-generation-parameters.please-select-a-valid-tone"));
        return new AudioGenerationParameters(speaker,
                rate(source, "speechRate", MIN_RATE, MAX_RATE),
                rate(source, "loudnessRate", MIN_RATE, MAX_RATE),
                rate(source, "pitchRate", MIN_PITCH_RATE, MAX_PITCH_RATE));
    }

    public ObjectNode toJson(ObjectMapper mapper) {
        return mapper.createObjectNode().put("speaker", speaker).put("speechRate", speechRate)
                .put("loudnessRate", loudnessRate).put("pitchRate", pitchRate);
    }

    private static int rate(JsonNode source, String field, int minimum, int maximum) {
        JsonNode value = source.get(field);
        if (value == null) return DEFAULT_RATE;
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum)
            throw invalid("speechRate".equals(field)
                    ? ApiMessage.of("api.audio-generation-parameters.speech-rate-must-be-an-integer-from-50-100")
                    : ApiMessage.of("api.audio-generation-parameters.is-outside-the-allowed-range", field));
        return value.intValue();
    }

    private static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                ApiMessage.of("api.audio-generation-parameters.invalid-audio-parameter"),detail,false);
    }
}
