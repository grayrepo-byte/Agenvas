package dev.agenvas.provider.infrastructure;

import dev.agenvas.artifact.domain.AudioGenerationParameters;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixed speech origin, no redirect or automatic POST retry; bounded JSON/audio streaming. */
@Component
public final class SeedAudioClient {
    private static final URI OFFICIAL = URI.create("https://openspeech.bytedance.com");
    private static final String PATH = "/api/v3/tts/create";
    private static final int MAX_AUDIO_BYTES = 50 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 72 * 1024 * 1024;
    private final URI origin;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public SeedAudioClient(ObjectMapper mapper) { this(mapper, OFFICIAL); }
    public SeedAudioClient(ObjectMapper mapper, URI origin) {
        if (!OFFICIAL.equals(origin) && !("http".equals(origin.getScheme())
                && "127.0.0.1".equals(origin.getHost()) && origin.getPort() > 0
                && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                && origin.getRawUserInfo() == null && origin.getRawQuery() == null
                && origin.getRawFragment() == null)) throw new IllegalArgumentException("Unsupported speech origin");
        this.mapper = mapper; this.origin = origin;
        http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, !OFFICIAL.equals(origin)),
                Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofMinutes(5));
    }

    public record Reference(String field, byte[] bytes) {}

    public byte[] synthesize(String key, String requestId, String text,
            AudioGenerationParameters parameters, List<Reference> references) {
        var body = mapper.createObjectNode();
        body.put("model", "seed-audio-1.0").put("text_prompt", text);
        var resources = body.putArray("references");
        for (Reference reference : references) {
            if (!java.util.Set.of("image_data", "audio_data").contains(reference.field()))
                throw new IllegalArgumentException("Unsupported reference field");
            resources.addObject().put(reference.field(), Base64.getEncoder().encodeToString(reference.bytes()));
        }
        if (!parameters.speaker().isEmpty()) resources.addObject().put("speaker", parameters.speaker());
        body.putObject("audio_config").put("format", "mp3").put("sample_rate", 48000)
                .put("speech_rate", parameters.speechRate()).put("loudness_rate", parameters.loudnessRate())
                .put("pitch_rate", parameters.pitchRate());
        Request request = new Request.Builder().url(origin.resolve(PATH).toString())
                .header("X-Api-Key", key).header("X-Api-Request-Id", requestId)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build();
        try (var response = http.newCall(request).execute()) {
            if (response.code() >= 400 && response.code() < 500 && response.code() != 429) throw new Rejected();
            if (!response.isSuccessful() || response.body() == null) throw new Uncertain();
            byte[] responseBytes = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (responseBytes.length > MAX_RESPONSE_BYTES) throw new Uncertain();
            JsonNode result = mapper.readTree(responseBytes);
            // Success may omit code. A malformed 2xx can have been billed: retain UNKNOWN.
            if (result == null || !result.isObject()) throw new Uncertain();
            if (result.has("code") && result.path("code").asInt(-1) != 0
                    && result.path("code").asInt(-1) != 20000000) throw new Rejected();
            byte[] audio = Base64.getDecoder().decode(result.path("audio").asText());
            if (audio.length == 0 || audio.length > MAX_AUDIO_BYTES) throw new Uncertain();
            return audio;
        } catch (IOException | IllegalArgumentException | tools.jackson.core.JacksonException failure) { throw new Uncertain(); }
    }

    public static final class Rejected extends RuntimeException {}
    public static final class Uncertain extends RuntimeException {}
}
