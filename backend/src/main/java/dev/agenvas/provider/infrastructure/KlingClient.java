package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.KlingProtocol;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
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

/** Official image and new video protocols share authentication, but have different task schemas. */
@Component
public class KlingClient {
    public static final String ORIGIN = "https://api-singapore.klingai.com";
    public static final String BEIJING_ORIGIN = "https://api-beijing.klingai.com";
    private static final Set<String> OFFICIAL_ORIGINS = Set.of(ORIGIN, BEIJING_ORIGIN);
    private static final String IMAGE_PATH = "/v1/images/generations";
    private static final Set<String> IMAGE_STATUSES = Set.of("submitted", "processing", "succeed", "failed");
    private static final Set<String> VIDEO_STATUSES = Set.of("submitted", "processing", "succeeded", "failed");
    private static final Set<Integer> REFUSAL_CODES = Set.of(1000, 1001, 1002, 1003, 1004,
            1100, 1101, 1102, 1103, 1200, 1201, 1202, 1203, 1300, 1301, 1302, 1303, 1304);
    private static final Set<Integer> AUTHENTICATION_CODES = Set.of(1000, 1001, 1002, 1003, 1004);
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final URI targetOrigin;
    private final OkHttpClient http;

    @Autowired public KlingClient(ObjectMapper mapper) { this(mapper, null); }

    /** Runtime configuration cannot select this loopback override. */
    public KlingClient(ObjectMapper mapper, URI testOrigin) {
        if (testOrigin != null && !("http".equals(testOrigin.getScheme())
                && "127.0.0.1".equals(testOrigin.getHost()) && testOrigin.getPort() > 0
                && testOrigin.getPort() <= 65535 && "".equals(testOrigin.getRawPath())
                && testOrigin.getRawUserInfo() == null && testOrigin.getRawQuery() == null
                && testOrigin.getRawFragment() == null))
            throw new IllegalArgumentException("Invalid Kling test origin");
        this.mapper = mapper;
        this.targetOrigin = testOrigin;
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, testOrigin != null),
                Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2));
    }

    public static String validatedOrigin(String origin) {
        if (origin == null || origin.isBlank()) return ORIGIN;
        String normalized = origin.trim().replaceAll("/+$", "");
        if (!OFFICIAL_ORIGINS.contains(normalized)) throw KlingProtocol.invalid();
        return normalized;
    }

    public record Reference(String role, String base64) {
        @Override public String toString() { return "Reference[role=" + role + "]"; }
    }

    public ObjectNode prepareImage(String prompt, String ratio, String resolution, List<Reference> references) {
        KlingProtocol.requireImagePrompt(prompt);
        if (!KlingProtocol.IMAGE_ASPECT_RATIOS.contains(ratio)
                || !KlingProtocol.IMAGE_RESOLUTIONS.contains(resolution)
                || references == null || references.size() > 1
                || references.stream().anyMatch(ref -> !"REFERENCE".equals(ref.role())))
            throw KlingProtocol.invalid();
        ObjectNode body = mapper.createObjectNode().put("model_name", KlingProtocol.IMAGE_MODEL_ID)
                .put("prompt", prompt).put("n", 1).put("aspect_ratio", ratio)
                .put("resolution", resolution.toLowerCase(java.util.Locale.ROOT));
        if (!references.isEmpty()) body.put("image", requireBase64(references.getFirst(), false));
        return body;
    }

    /** The endpoint is selected by validated frozen input, never by model text or a remote URL. */
    public ObjectNode prepareVideo(String prompt, String ratio, String resolution, int duration,
            List<Reference> references, String requestKey) {
        KlingProtocol.requireVideoPrompt(prompt);
        if (duration < KlingProtocol.MINIMUM_SECONDS || duration > KlingProtocol.MAXIMUM_SECONDS
                || references == null || references.size() > 2 || requestKey == null || requestKey.isBlank()
                || requestKey.length() > 160
                || references.stream().anyMatch(ref -> !Set.of("START_FRAME", "END_FRAME").contains(ref.role()))
                || !references.isEmpty() && (references.stream().filter(ref -> "START_FRAME".equals(ref.role())).count() != 1
                    || references.stream().filter(ref -> "END_FRAME".equals(ref.role())).count() > 1))
            throw KlingProtocol.invalid();
        ObjectNode body = mapper.createObjectNode();
        if (references.isEmpty()) {
            if (!KlingProtocol.VIDEO_ASPECT_RATIOS.contains(ratio)) throw KlingProtocol.invalid();
            body.put("prompt", prompt);
        } else {
            var contents = body.putArray("contents");
            contents.addObject().put("type", "prompt").put("text", prompt);
            // Roles determine the protocol order, even if a frozen input array starts with the tail.
            for (String role : List.of("START_FRAME", "END_FRAME")) {
                references.stream().filter(ref -> role.equals(ref.role())).findFirst().ifPresent(ref ->
                        contents.addObject().put("type", "START_FRAME".equals(role) ? "first_frame" : "last_frame")
                                .put("url", requireBase64(ref, true)));
            }
        }
        ObjectNode settings = body.putObject("settings").put("duration", duration)
                .put("resolution", KlingProtocol.providerResolution(resolution))
                .put("audio", "native").put("multi_shot", false);
        if (references.isEmpty()) settings.put("aspect_ratio", ratio);
        body.putObject("options").put("external_task_id", requestKey)
                .putObject("watermark_info").put("enabled", false);
        return body;
    }

    private static String requireBase64(Reference reference, boolean video) {
        String value = reference.base64();
        int maximum = video ? KlingProtocol.MAX_VIDEO_IMAGE_BYTES : KlingProtocol.MAX_IMAGE_BYTES;
        if (value == null || value.isEmpty() || value.length() % 4 != 0 || value.length() > 4L * ((maximum + 2L) / 3)
                || !value.matches("[A-Za-z0-9+/]+={0,2}")) throw KlingProtocol.invalid();
        return value;
    }

    /** Serialize only fields compiled for this immutable model/operation; never forward raw creator JSON. */
    public ObjectNode build(KlingModels.Profile profile, JsonNode values, Map<String, String> media, String requestKey) {
        KlingModels.resolve(profile.model(), profile.operation().name(), profile.kind());
        KlingModels.validateValues(profile, values, true);
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 160) throw KlingProtocol.invalid();
        Set<String> expected = new HashSet<>();
        for (var field : KlingModels.inputs(profile)) if (field.media() && KlingModels.present(values, field.key())) expected.add(field.key());
        if (media == null || !media.keySet().equals(expected)) throw KlingProtocol.invalid();
        for (var field : KlingModels.inputs(profile)) if (expected.contains(field.key()))
            validatePrepared(media.get(field.key()), field.type() == dev.agenvas.provider.domain.RunningHubDefinition.FieldType.VIDEO, KlingModels.newVideo(profile));
        ObjectNode body = mapper.createObjectNode();
        if (!KlingModels.newVideo(profile)) buildImage(profile, values, media, body);
        else buildVideo(profile, values, media, body);
        ObjectNode managed = KlingModels.newVideo(profile) ? (ObjectNode) body.path("options") : body;
        managed.put("external_task_id", requestKey);
        if (profile.operation() != KlingModels.Operation.SUBJECT_COMPLETION)
            managed.putObject("watermark_info").put("enabled", values.path("watermark").asBoolean(false));
        return body;
    }

    private void buildImage(KlingModels.Profile p, JsonNode values, Map<String, String> media, ObjectNode body) {
        if (!p.model().isEmpty()) body.put("model_name", p.model());
        copy(values, body, "prompt");
        switch (p.operation()) {
            case SUBJECT_COMPLETION -> body.put("element_frontal_image", media.get("image_1"));
            case EXPAND -> {
                body.put("image", media.get("image_1"));
                for (String side : List.of("up", "down", "left", "right")) copy(values, body, side + "_expansion_ratio");
            }
            case MULTI_IMAGE -> {
                var subjects = body.putArray("subject_image_list");
                for (int i = 1; i <= 4; i++) if (media.containsKey("image_" + i)) subjects.addObject().put("subject_image", media.get("image_" + i));
                for (String key : List.of("scene_image", "style_image")) if (media.containsKey(key)) body.put(key, media.get(key));
                copy(values, body, "aspect_ratio");
            }
            default -> {
                copy(values, body, "resolution", "aspect_ratio");
                if (KlingModels.omni(p)) {
                    if (!media.isEmpty()) {
                        var images = body.putArray("image_list");
                        for (int i = 1; i <= 10; i++) if (media.containsKey("image_" + i)) images.addObject().put("image", media.get("image_" + i));
                    }
                    if ("kling-v3-omni".equals(p.model())) {
                        copy(values, body, "result_type");
                        if ("series".equals(values.path("result_type").asText())) copy(values, body, "series_amount");
                    }
                } else {
                    if (media.containsKey("image_1")) {
                        body.put("image", media.get("image_1"));
                        copy(values, body, "image_reference", "image_fidelity");
                        if ("subject".equals(values.path("image_reference").asText())) copy(values, body, "human_fidelity");
                    } else copy(values, body, "negative_prompt");
                }
                for (int i = 1; i <= 10; i++) if (KlingModels.present(values, "elementId" + i)) {
                    var array = body.has("element_list") ? (tools.jackson.databind.node.ArrayNode) body.path("element_list") : body.putArray("element_list");
                    array.addObject().put("element_id", KlingModels.positiveLong(values.path("elementId" + i).asText()));
                }
            }
        }
        if (p.operation() != KlingModels.Operation.SUBJECT_COMPLETION && !"series".equals(values.path("result_type").asText())) copy(values, body, "n");
    }

    private void buildVideo(KlingModels.Profile p, JsonNode values, Map<String, String> media, ObjectNode body) {
        boolean contents = KlingModels.motion(p) || KlingModels.omni(p) || media.containsKey("first_frame");
        if (contents) {
            var array = body.putArray("contents");
            if (KlingModels.present(values, "prompt")) array.addObject().put("type", "prompt").put("text", values.path("prompt").asText());
            for (var field : KlingModels.inputs(p)) if (field.media() && media.containsKey(field.key())) {
                String type = switch (field.key()) {
                    case "first_frame", "last_frame", "feature_video", "base_video" -> field.key();
                    case "motion_video" -> "video";
                    default -> KlingModels.motion(p) ? "image" : "refer_image";
                };
                var content = array.addObject().put("type", type).put("url", media.get(field.key()));
                if (KlingModels.omni(p)) content.put("id", field.key());
            }
            for (int i = 1; i <= 10; i++) if (KlingModels.present(values, "elementId" + i)) array.addObject().put("type", "element")
                    .put("element_id", values.path("elementId" + i).asText())
                    .put("id", values.path("elementRefId" + i).asText("element_" + i));
            for (int i = 1; i <= 2; i++) if (KlingModels.present(values, "voiceId" + i)) array.addObject().put("type", "voice")
                    .put("voice_id", values.path("voiceId" + i).asText()).put("id", values.path("voiceRefId" + i).asText("voice_" + i));
        } else copy(values, body, "prompt");
        var settings = body.putObject("settings");
        copy(values, settings, "resolution", "duration", "audio", "multi_shot", "character_orientation");
        // Omni accepts an explicit ratio in every combination; this also satisfies its ambiguous required-condition wording.
        if (!KlingModels.motion(p) && (KlingModels.omni(p) || !media.containsKey("first_frame"))) copy(values, settings, "aspect_ratio");
        body.putObject("options");
    }

    private static void copy(JsonNode from, ObjectNode to, String... keys) {
        for (String key : keys) if (KlingModels.present(from, key)) to.set(key, from.path(key).deepCopy());
    }

    private static void validatePrepared(String value, boolean video, boolean videoGeneration) {
        if (value == null || value.isBlank()) throw KlingProtocol.invalid();
        if (value.startsWith("https://")) {
            URI uri;
            try { uri = URI.create(value); } catch (IllegalArgumentException invalid) { throw KlingProtocol.invalid(); }
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() != -1 && uri.getPort() != 443 || value.length() > 8192) throw KlingProtocol.invalid();
        } else {
            if (video) throw KlingProtocol.invalid();
            requireBase64(new Reference("REFERENCE", value), videoGeneration);
        }
    }

    public String create(KlingModels.Profile profile, String origin, String key, ObjectNode body) {
        KlingModels.resolve(profile.model(), profile.operation().name(), profile.kind());
        // Dispatch is based on the compiled profile and the prepared body's verified contents, never a supplied URL.
        JsonNode routing = mapper.createObjectNode();
        for (JsonNode content : body.path("contents")) if ("first_frame".equals(content.path("type").asText())) ((ObjectNode) routing).put("first_frame", "present");
        JsonNode data = request(origin, key, KlingModels.requestPath(profile, routing), body).path("data");
        boolean video = KlingModels.newVideo(profile);
        String idField = video ? "id" : "task_id", statusField = video ? "status" : "task_status";
        String id = data.path(idField).asText();
        if (!data.path(idField).isTextual() || !validId(id)
                || !(video ? VIDEO_STATUSES : IMAGE_STATUSES).contains(data.path(statusField).asText())) throw new Uncertain();
        return id;
    }

    public record Output(int ordinal, String url, String watermarkUrl, Double duration) {
        public Output(int ordinal, String url) { this(ordinal, url, null, null); }
    }
    public record State(String status, List<Output> outputs) {}

    /** Query only the acknowledged identity and preserve provider output indices when response order changes. */
    public State query(KlingModels.Profile profile, String origin, String key, String originalId, String resultType) {
        KlingModels.resolve(profile.model(), profile.operation().name(), profile.kind());
        if (!validId(originalId)) throw new ProtocolFailure();
        JsonNode data = request(origin, key, KlingModels.queryPath(profile, originalId), null).path("data");
        boolean video = KlingModels.newVideo(profile);
        JsonNode task = data;
        if (video) {
            if (!data.isArray()) throw new ProtocolFailure();
            task = null;
            for (JsonNode item : data) if (originalId.equals(item.path("id").asText())) {
                if (task != null) throw new ProtocolFailure();
                task = item;
            }
            if (task == null) throw new ProtocolFailure();
        }
        if (!task.path(video ? "id" : "task_id").isTextual() || !originalId.equals(task.path(video ? "id" : "task_id").asText())) throw new ProtocolFailure();
        String status = task.path(video ? "status" : "task_status").asText();
        if (!(video ? VIDEO_STATUSES : IMAGE_STATUSES).contains(status)) throw new ProtocolFailure();
        if (!(video ? "succeeded" : "succeed").equals(status)) return new State(status, List.of());
        var outputs = new ArrayList<Output>();
        if (video) {
            if (!task.path("outputs").isArray()) throw new ProtocolFailure();
            for (JsonNode item : task.path("outputs")) if ("video".equals(item.path("type").asText())) {
                JsonNode duration = item.path("duration");
                if (!duration.isTextual()) throw new ProtocolFailure();
                double seconds;
                try { seconds = Double.parseDouble(duration.asText()); } catch (NumberFormatException invalid) { throw new ProtocolFailure(); }
                if (!Double.isFinite(seconds) || seconds <= 0) throw new ProtocolFailure();
                outputs.add(new Output(0, outputUrl(item), watermarkUrl(item), seconds));
            }
            if (outputs.size() != 1) throw new ProtocolFailure();
        } else {
            JsonNode result = task.path("task_result");
            if (KlingModels.omni(profile) && (!Set.of("single", "series").contains(resultType)
                    || !resultType.equals(result.path("result_type").asText()))) throw new ProtocolFailure();
            JsonNode images = result.path(KlingModels.omni(profile) && "series".equals(resultType) ? "series_images" : "images");
            if (!images.isArray() || images.isEmpty() || images.size() > 9) throw new ProtocolFailure();
            Set<Integer> indices = new HashSet<>();
            for (JsonNode item : images) {
                JsonNode index = item.path("index");
                if (!index.isIntegralNumber() || !index.canConvertToInt() || index.asInt() < 0 || index.asInt() > 9 || !indices.add(index.asInt())) throw new ProtocolFailure();
                if (profile.operation() == KlingModels.Operation.SUBJECT_COMPLETION) {
                    if (index.asInt() > 2) throw new ProtocolFailure();
                    for (int angle = 1; angle <= 3; angle++) {
                        JsonNode url = item.path("url_" + angle);
                        if (!url.isTextual() || url.asText().isBlank() || url.asText().length() > 4096) throw new ProtocolFailure();
                        outputs.add(new Output(index.asInt() * 3 + angle - 1, url.asText()));
                    }
                } else outputs.add(new Output(index.asInt(), outputUrl(item), watermarkUrl(item), null));
            }
            outputs.sort(Comparator.comparingInt(Output::ordinal));
        }
        return new State(status, List.copyOf(outputs));
    }

    private static String watermarkUrl(JsonNode item) {
        if (!item.hasNonNull("watermark_url") || item.path("watermark_url").asText().isBlank()) return null;
        if (!item.path("watermark_url").isTextual() || item.path("watermark_url").asText().length() > 4096) throw new ProtocolFailure();
        return item.path("watermark_url").asText();
    }

    public String createImage(String origin, String key, ObjectNode body) {
        JsonNode data = request(origin, key, IMAGE_PATH, body).path("data");
        String id = data.path("task_id").asText();
        if (!data.path("task_id").isTextual() || !validId(id) || !IMAGE_STATUSES.contains(data.path("task_status").asText())) throw new Uncertain();
        return id;
    }

    public String createVideo(String origin, String key, ObjectNode body) {
        String path = body.has("contents") ? "/image-to-video/" : "/text-to-video/";
        JsonNode data = request(origin, key, path + KlingProtocol.VIDEO_MODEL_ID, body).path("data");
        String id = data.path("id").asText();
        if (!data.path("id").isTextual() || !validId(id) || !VIDEO_STATUSES.contains(data.path("status").asText())) throw new Uncertain();
        return id;
    }

    public record TaskState(String status, String url) {}

    public TaskState queryImage(String origin, String key, String originalId) {
        if (!validId(originalId)) throw new ProtocolFailure();
        JsonNode data = request(origin, key, IMAGE_PATH + "/" + originalId, null).path("data");
        if (!data.path("task_id").isTextual() || !originalId.equals(data.path("task_id").asText())) throw new ProtocolFailure();
        String status = data.path("task_status").asText();
        if (!IMAGE_STATUSES.contains(status)) throw new ProtocolFailure();
        String url = null;
        if ("succeed".equals(status)) {
            JsonNode images = data.path("task_result").path("images");
            if (!images.isArray() || images.size() != 1 || !images.get(0).path("index").isIntegralNumber()
                    || !images.get(0).path("index").canConvertToInt()
                    || images.get(0).path("index").asInt(-1) < 0) throw new ProtocolFailure();
            url = outputUrl(images.get(0));
        }
        return new TaskState(status, url);
    }

    public TaskState queryVideo(String origin, String key, String originalId) {
        if (!validId(originalId)) throw new ProtocolFailure();
        JsonNode data = request(origin, key, "/tasks?task_ids=" + originalId, null).path("data");
        if (!data.isArray()) throw new ProtocolFailure();
        JsonNode matched = null;
        for (JsonNode task : data) {
            if (task.path("id").isTextual() && originalId.equals(task.path("id").asText())) {
                if (matched != null) throw new ProtocolFailure();
                matched = task;
            }
        }
        if (matched == null) throw new ProtocolFailure();
        String status = matched.path("status").asText();
        if (!VIDEO_STATUSES.contains(status)) throw new ProtocolFailure();
        String url = null;
        if ("succeeded".equals(status)) {
            JsonNode outputs = matched.path("outputs");
            if (!outputs.isArray()) throw new ProtocolFailure();
            for (JsonNode output : outputs) {
                if ("video".equals(output.path("type").asText())) {
                    if (url != null) throw new ProtocolFailure();
                    url = outputUrl(output);
                    JsonNode duration = output.path("duration");
                    if (!duration.isTextual()) throw new ProtocolFailure();
                    try {
                        double seconds = Double.parseDouble(duration.asText());
                        if (!Double.isFinite(seconds) || seconds <= 0) throw new ProtocolFailure();
                    } catch (NumberFormatException invalid) { throw new ProtocolFailure(); }
                }
            }
            if (url == null) throw new ProtocolFailure();
        }
        return new TaskState(status, url);
    }

    private static String outputUrl(JsonNode output) {
        JsonNode url = output.path("url");
        if (!url.isTextual() || url.asText().isBlank() || url.asText().length() > 4_096) throw new ProtocolFailure();
        return url.asText();
    }

    private JsonNode request(String origin, String key, String path, ObjectNode body) {
        String officialOrigin = validatedOrigin(origin);
        if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Pinned Kling credential is invalid");
        boolean create = body != null;
        var builder = new Request.Builder().url((targetOrigin == null ? URI.create(officialOrigin) : targetOrigin).resolve(path).toString())
                .header("Authorization", "Bearer " + key).header("Accept", "application/json");
        if (create) builder.post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.get("application/json")));
        else builder.get();
        try (Response response = http.newCall(builder.build()).execute()) {
            int status = response.code();
            if (create && Set.of(400, 401, 402, 403, 404, 413, 422, 429).contains(status)) throw new Rejected();
            if (!create && Set.of(401, 403).contains(status)) throw new CredentialUnavailable();
            if (!create && Set.of(404, 410).contains(status)) throw new TaskExpired();
            if (status != 200 || response.body() == null) throw transportFailure(create);
            byte[] bytes = response.body().byteStream().readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw transportFailure(create);
            JsonNode parsed = mapper.readTree(bytes);
            if (!parsed.isObject() || !parsed.path("code").isIntegralNumber() || !parsed.path("code").canConvertToInt())
                throw transportFailure(create);
            if (parsed.path("code").asInt() != 0) {
                // Only documented refusals prove rejection; internal and unknown errors stay uncertain.
                int code = parsed.path("code").asInt();
                if (create && REFUSAL_CODES.contains(code)) throw new Rejected();
                if (!create && AUTHENTICATION_CODES.contains(code)) throw new CredentialUnavailable();
                throw transportFailure(create);
            }
            return parsed;
        } catch (IOException failure) { throw transportFailure(create); }
        catch (RuntimeException failure) {
            if (failure instanceof Rejected || failure instanceof Uncertain || failure instanceof TechnicalFailure
                    || failure instanceof TaskExpired || failure instanceof CredentialUnavailable) throw failure;
            throw transportFailure(create);
        }
    }

    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"); }
    private static RuntimeException transportFailure(boolean create) { return create ? new Uncertain() : new TechnicalFailure(); }
    public static final class Rejected extends RuntimeException { public Rejected() { super("Kling create rejected"); } }
    public static final class Uncertain extends RuntimeException { public Uncertain() { super("Kling create uncertain"); } }
    public static final class TechnicalFailure extends RuntimeException { public TechnicalFailure() { super("Kling query unavailable"); } }
    public static final class ProtocolFailure extends RuntimeException { public ProtocolFailure() { super("Kling task identity/status invalid"); } }
    public static final class TaskExpired extends RuntimeException { public TaskExpired() { super("Kling original task expired"); } }
    public static final class CredentialUnavailable extends RuntimeException { public CredentialUnavailable() { super("Kling credential unavailable"); } }
}
