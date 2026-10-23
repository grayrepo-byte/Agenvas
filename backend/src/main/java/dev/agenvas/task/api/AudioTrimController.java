package dev.agenvas.task.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.AudioTrimService;
import dev.agenvas.task.domain.AudioExtractionParameters;
import dev.agenvas.task.domain.AudioTrimParameters;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** The JSON boundary rejects coercion: fractional milliseconds or CAS versions must not be rounded. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/artifacts/{artifactId}/audio-trims")
public class AudioTrimController {
    private static final Set<String> FIELDS = Set.of("canvasItemId", "sourceVersionId",
            "expectedCanvasItemVersion", "startMs", "endMs", "format");
    private final AudioTrimService trims;

    public AudioTrimController(AudioTrimService trims) { this.trims = trims; }

    @PostMapping
    public TaskController.TaskResponse run(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID artifactId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        if (!request.isObject() || !FIELDS.containsAll(request.propertyNames())) throw invalid();
        long expected = integer(request, "expectedCanvasItemVersion");
        long start = integer(request, "startMs"), end = integer(request, "endMs");
        if (expected < 0 || start < 0 || start > Integer.MAX_VALUE || end < 0 || end > Integer.MAX_VALUE)
            throw invalid();
        AudioExtractionParameters.Format format = AudioExtractionParameters.Format.WAV;
        if (request.has("format")) {
            if (!request.path("format").isTextual()) throw invalid();
            try { format = AudioExtractionParameters.Format.valueOf(request.path("format").asText()); }
            catch (IllegalArgumentException malformed) { throw invalid(); }
        }
        return TaskController.TaskResponse.from(trims.run(principal.userId(), projectId, artifactId,
                uuid(request, "canvasItemId"), uuid(request, "sourceVersionId"), expected,
                new AudioTrimParameters((int) start, (int) end, format), key));
    }

    private static long integer(JsonNode request, String field) {
        JsonNode value = request.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }

    private static UUID uuid(JsonNode request, String field) {
        JsonNode value = request.path(field);
        if (!value.isTextual()) throw invalid();
        try {
            UUID id = UUID.fromString(value.asText());
            if (!id.toString().equalsIgnoreCase(value.asText())) throw invalid();
            return id;
        } catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "AUDIO_TRIM_INVALID",
                ApiMessage.of("api.media-function.title"), ApiMessage.of("api.audio-trim.invalid"), false);
    }
}
