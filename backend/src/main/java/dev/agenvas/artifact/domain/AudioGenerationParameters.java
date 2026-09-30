package dev.agenvas.artifact.domain;

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
    public static final int MIN_RATE = -50;
    public static final int MAX_RATE = 100;
    private static final Set<String> FIELDS = Set.of("speaker", "speechRate", "loudnessRate", "pitchRate");

    public static AudioGenerationParameters parse(JsonNode source) {
        if (source == null || source.isMissingNode() || source.isNull())
            return new AudioGenerationParameters(DEFAULT_SPEAKER, 0, 0, 0);
        if (!source.isObject() || !FIELDS.containsAll(source.propertyNames()))
            throw invalid("音频参数只接受音色、语速、音量和音调。");
        JsonNode voice = source.get("speaker");
        String speaker = voice == null ? DEFAULT_SPEAKER : voice.asText("");
        if (voice != null && !voice.isTextual() || !speaker.isEmpty() && !speaker.matches("[A-Za-z0-9_-]{1,120}"))
            throw invalid("请选择有效的音色。");
        JsonNode rate = source.get("speechRate");
        if (rate != null && (!rate.isIntegralNumber() || !rate.canConvertToInt()
                || rate.intValue() < MIN_RATE || rate.intValue() > MAX_RATE))
            throw invalid("语速必须为 -50–100 的整数。");
        return new AudioGenerationParameters(speaker, rate == null ? 0 : rate.intValue(),
                integer(source, "loudnessRate", MIN_RATE, MAX_RATE), integer(source, "pitchRate", -12, 12));
    }

    public ObjectNode toJson(ObjectMapper mapper) {
        return mapper.createObjectNode().put("speaker", speaker).put("speechRate", speechRate)
                .put("loudnessRate", loudnessRate).put("pitchRate", pitchRate);
    }

    private static int integer(JsonNode source, String field, int minimum, int maximum) {
        JsonNode value = source.get(field);
        if (value == null) return 0;
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum)
            throw invalid(field + " 超出允许范围。");
        return value.intValue();
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "音频参数无效",detail,false);
    }
}
