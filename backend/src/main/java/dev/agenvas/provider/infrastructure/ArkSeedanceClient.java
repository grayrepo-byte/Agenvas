package dev.agenvas.provider.infrastructure;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixed Beijing Ark task API; create and query never use an administrator supplied endpoint. */
@Component
public class ArkSeedanceClient {
    private static final URI OFFICIAL = URI.create("https://ark.cn-beijing.volces.com");
    private static final String TASKS = "/api/v3/contents/generations/tasks";
    private static final String MODEL = "doubao-seedance-2-0-260128";
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private final URI origin;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public ArkSeedanceClient(ObjectMapper mapper) {
        this(mapper, OFFICIAL);
    }

    /** Loopback is admitted solely for the local fake-service tests. */
    public ArkSeedanceClient(ObjectMapper mapper, URI origin) {
        if (!OFFICIAL.equals(origin) && !("http".equals(origin.getScheme())
                && "127.0.0.1".equals(origin.getHost()) && origin.getPort() > 0
                && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                && origin.getRawUserInfo() == null && origin.getRawQuery() == null
                && origin.getRawFragment() == null)) {
            throw new IllegalArgumentException("Unsupported fixed Ark API origin");
        }
        this.mapper = mapper;
        this.origin = origin;
        this.http = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(10)).callTimeout(Duration.ofMinutes(2))
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).build();
    }

    public String create(String key, String prompt, byte[] firstFramePng,
            int durationSeconds, String ratio) {
        if (durationSeconds < 4 || durationSeconds > 15 || firstFramePng == null
                || firstFramePng.length == 0 || firstFramePng.length >= 30 * 1024 * 1024
                || !java.util.Set.of("16:9", "9:16", "1:1").contains(ratio)) {
            throw new IllegalArgumentException("Seedance first-frame inputs are unsupported");
        }
        ObjectNode request = mapper.createObjectNode();
        request.put("model", MODEL);
        var content = request.putArray("content");
        content.addObject().put("type", "text").put("text", prompt);
        ObjectNode frame = content.addObject();
        frame.put("type", "image_url");
        frame.put("role", "first_frame");
        frame.putObject("image_url").put("url", "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(firstFramePng));
        request.put("duration", durationSeconds);
        request.put("ratio", ratio);
        request.put("resolution", "720p");
        request.put("generate_audio", false);
        request.put("output_format", "mp4");
        JsonNode response = request(key, "POST", TASKS,
                request.toString().getBytes(StandardCharsets.UTF_8), true);
        String id = response.path("id").asText();
        if (!validTaskId(id)) throw new Uncertain("Ark create response omitted task ID");
        return id;
    }

    /** Only the persisted original ID can be interpolated into this fixed query path. */
    public TaskState query(String key, String originalTaskId) {
        if (!validTaskId(originalTaskId)) {
            throw new ProtocolFailure("Persisted Ark task ID is invalid");
        }
        JsonNode response = request(key, "GET", TASKS + "/" + originalTaskId,
                null, false);
        if (!originalTaskId.equals(response.path("id").asText())
                || !MODEL.equals(response.path("model").asText())) {
            throw new ProtocolFailure("Ark query returned a different task identity");
        }
        String status = response.path("status").asText();
        if (!java.util.Set.of("queued", "running", "succeeded", "failed", "expired",
                "cancelled").contains(status)) {
            throw new ProtocolFailure("Ark task status is unknown");
        }
        String videoUrl = response.path("content").path("video_url").asText(null);
        return new TaskState(status, videoUrl);
    }

    private JsonNode request(String key, String method, String path, byte[] body,
            boolean create) {
        Request.Builder request = new Request.Builder().url(origin.resolve(path).toString())
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json");
        if (create) request.post(RequestBody.create(body,
                MediaType.parse("application/json; charset=utf-8")));
        else request.get();
        try (Response response = http.newCall(request.build()).execute()) {
            if (response.body() == null) throw responseError(create);
            byte[] bytes = response.body().byteStream().readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw responseError(create);
            int status = response.code();
            if (status >= 400 && status < 500 && status != 429 && create) {
                throw new Rejected(status);
            }
            if (status != 200) throw responseError(create);
            JsonNode parsed = mapper.readTree(bytes);
            if (!parsed.isObject()) throw responseError(create);
            return parsed;
        } catch (IOException failure) {
            throw responseError(create);
        } catch (RuntimeException failure) {
            if (failure instanceof Rejected || failure instanceof Uncertain
                    || failure instanceof TechnicalFailure) throw failure;
            throw responseError(create);
        }
    }

    private static RuntimeException responseError(boolean create) {
        return create ? new Uncertain("Ark create result is uncertain")
                : new TechnicalFailure("Ark original task could not be queried");
    }

    private static boolean validTaskId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    }

    public record TaskState(String status, String videoUrl) {}
    public static final class Rejected extends RuntimeException {
        public Rejected(int status) { super("Ark create rejected: HTTP " + status); }
    }
    public static final class Uncertain extends RuntimeException {
        public Uncertain(String message) { super(message); }
    }
    public static final class TechnicalFailure extends RuntimeException {
        public TechnicalFailure(String message) { super(message); }
    }
    public static final class ProtocolFailure extends RuntimeException {
        public ProtocolFailure(String message) { super(message); }
    }
}
