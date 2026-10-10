package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.MiniMaxH3Protocol;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import okhttp3.Dns;
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

/** Official H3 V2 protocol. A create response can never trigger another POST. */
@Component
public class MiniMaxH3Client {
    public static final String CHINA = "https://api.minimax.cn";
    public static final String INTERNATIONAL = "https://api.minimax.io";
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final URI testOrigin;
    private final OkHttpClient http;

    @Autowired public MiniMaxH3Client(ObjectMapper mapper) { this(mapper, null); }

    /** Loopback override is only injectable by fake-service tests. */
    public MiniMaxH3Client(ObjectMapper mapper, URI testOrigin) {
        if (testOrigin != null && !("http".equals(testOrigin.getScheme())
                && "127.0.0.1".equals(testOrigin.getHost()) && testOrigin.getPort() > 0
                && testOrigin.getPort() <= 65535 && "".equals(testOrigin.getRawPath())
                && testOrigin.getRawUserInfo() == null && testOrigin.getRawQuery() == null
                && testOrigin.getRawFragment() == null)) throw new IllegalArgumentException("Invalid MiniMax test origin");
        this.mapper = mapper;
        this.testOrigin = testOrigin;
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, testOrigin != null),
                Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2));
    }

    public static String validatedOrigin(String origin) {
        if (origin == null || origin.isBlank()) return CHINA;
        String normalized = origin.trim().replaceAll("/+$", "");
        if (!Set.of(CHINA, INTERNATIONAL).contains(normalized)) throw MiniMaxH3Protocol.invalid();
        return normalized;
    }

    public record Reference(String type, String role, String url) {
        @Override public String toString() { return "Reference[type=" + type + ", role=" + role + "]"; }
    }

    /** Build before the submission checkpoint to reject oversized inline inputs without billing. */
    public ObjectNode prepare(String prompt, List<Reference> references, int duration, String ratio, String resolution) {
        if (prompt == null || prompt.isBlank() || duration < 4 || duration > 15
                || !Set.of("adaptive", "16:9", "9:16", "1:1").contains(ratio)) throw MiniMaxH3Protocol.invalid();
        boolean frames = references.stream().anyMatch(ref -> Set.of("first_frame", "last_frame").contains(ref.role()));
        boolean general = references.stream().anyMatch(ref -> Set.of("reference_image", "reference_video", "reference_audio").contains(ref.role()));
        if (frames && general || references.isEmpty() && "adaptive".equals(ratio)
                || count(references, "first_frame") > 1 || count(references, "last_frame") > 1
                || count(references, "reference_image") > 9 || count(references, "reference_video") > 3
                || count(references, "reference_audio") > 3) throw MiniMaxH3Protocol.invalid();
        ObjectNode request = mapper.createObjectNode().put("model", MiniMaxH3Protocol.MODEL_ID)
                .put("duration", duration).put("ratio", frames ? "adaptive" : ratio)
                .put("resolution", "1440p".equals(MiniMaxH3Protocol.resolution(resolution)) ? "2K" : "768P");
        var content = request.putArray("content");
        content.addObject().put("type", "text").put("text", prompt);
        for (Reference reference : references) {
            Set<String> roles = switch (reference.type()) {
                case "image_url" -> Set.of("first_frame", "last_frame", "reference_image");
                case "video_url" -> Set.of("reference_video");
                case "audio_url" -> Set.of("reference_audio");
                default -> Set.of();
            };
            if (!roles.contains(reference.role()) || reference.url() == null || reference.url().isBlank()) throw MiniMaxH3Protocol.invalid();
            content.addObject().put("type", reference.type()).put("role", reference.role())
                    .putObject(reference.type()).put("url", reference.url());
        }
        if (mapper.writeValueAsBytes(request).length > MiniMaxH3Protocol.MAX_REQUEST_BYTES) throw MiniMaxH3Protocol.invalid();
        return request;
    }

    private static long count(List<Reference> references, String role) {
        return references.stream().filter(ref -> role.equals(ref.role())).count();
    }

    public String create(String origin, String key, ObjectNode request) {
        JsonNode response = request(origin, key, "/v2/video_generation", request);
        String id = response.path("task_id").asText();
        if (!validId(id)) throw new Uncertain();
        return id;
    }

    public TaskState query(String origin, String key, String originalId) {
        if (!validId(originalId)) throw new ProtocolFailure();
        JsonNode task = request(origin, key, "/v2/query/video_generation/" + originalId, null).path("task");
        if (!originalId.equals(task.path("id").asText())
                || !MiniMaxH3Protocol.MODEL_ID.equals(task.path("model").asText())) throw new ProtocolFailure();
        String status = task.path("status").asText();
        if (!Set.of("queued", "running", "succeeded", "failed", "cancelled").contains(status)) throw new ProtocolFailure();
        return new TaskState(status, task.path("content").path("url").asText(null));
    }

    private JsonNode request(String origin, String key, String path, ObjectNode body) {
        boolean create = body != null;
        URI target = testOrigin != null ? testOrigin : URI.create(validatedOrigin(origin));
        var request = new Request.Builder().url(target.resolve(path).toString())
                .header("Authorization", "Bearer " + key).header("Accept", "application/json");
        if (create) request.post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.get("application/json")));
        else request.get();
        try (Response response = http.newCall(request.build()).execute()) {
            int status = response.code();
            if (create && Set.of(400, 401, 402, 403, 404, 413, 422, 429).contains(status)) throw new Rejected();
            if (!create && Set.of(404, 410).contains(status)) throw new TaskExpired();
            if (status != 200 || response.body() == null) throw transportFailure(create);
            byte[] bytes = response.body().byteStream().readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw transportFailure(create);
            JsonNode parsed = mapper.readTree(bytes);
            if (!parsed.isObject() || parsed.has("error")) throw transportFailure(create);
            return parsed;
        } catch (IOException failure) { throw transportFailure(create); }
        catch (RuntimeException failure) {
            if (failure instanceof Rejected || failure instanceof Uncertain || failure instanceof TechnicalFailure
                    || failure instanceof TaskExpired) throw failure;
            throw transportFailure(create);
        }
    }
    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"); }
    private static RuntimeException transportFailure(boolean create) { return create ? new Uncertain() : new TechnicalFailure(); }
    public record TaskState(String status, String videoUrl) {}
    public static final class Rejected extends RuntimeException { public Rejected() { super("MiniMax create rejected"); } }
    public static final class Uncertain extends RuntimeException { public Uncertain() { super("MiniMax create uncertain"); } }
    public static final class TechnicalFailure extends RuntimeException { public TechnicalFailure() { super("MiniMax query unavailable"); } }
    public static final class ProtocolFailure extends RuntimeException { public ProtocolFailure() { super("MiniMax task identity/status invalid"); } }
    public static final class TaskExpired extends RuntimeException { public TaskExpired() { super("MiniMax original task expired"); } }
}
