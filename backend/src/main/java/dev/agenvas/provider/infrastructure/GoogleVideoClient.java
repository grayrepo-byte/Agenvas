package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.GoogleVideoModels;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.NativeMediaProtocol.Profile;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.shared.http.OutboundTimeouts;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
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
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Gemini video transports never use a creator URL and never retry a generation POST. */
@Component
public class GoogleVideoClient {
    public static final String ORIGIN = "https://generativelanguage.googleapis.com";
    public static final String CREATE_REJECTED = "GOOGLE_VIDEO_CREATE_REJECTED";
    public static final String TASK_FAILED = "GOOGLE_VIDEO_TASK_FAILED";
    public static final String TASK_EXPIRED = "GOOGLE_VIDEO_TASK_EXPIRED";
    public static final String RESULT_EXPIRED = "GOOGLE_VIDEO_RESULT_EXPIRED";
    public static final int MAX_VIDEO_BYTES = 100 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 145 * 1024 * 1024;
    private static final Set<String> INTERACTION_STATUSES = Set.of("queued", "in_progress", "requires_action",
            "completed", "failed", "cancelled", "incomplete");
    private final ObjectMapper mapper;
    private final URI testOrigin;
    private final OkHttpClient http;
    /** Inline video exceeds Jackson's ordinary text budget; this bounded parser is private to native media responses. */
    private final ObjectMapper responses = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(4 * ((MAX_VIDEO_BYTES + 2) / 3)).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @Autowired public GoogleVideoClient(ObjectMapper mapper) { this(mapper, null); }
    public GoogleVideoClient(ObjectMapper mapper, URI testOrigin) {
        if (testOrigin != null && !("http".equals(testOrigin.getScheme()) && "127.0.0.1".equals(testOrigin.getHost())
                && testOrigin.getPort() > 0 && testOrigin.getPort() <= 65535 && "".equals(testOrigin.getRawPath())
                && testOrigin.getRawQuery() == null && testOrigin.getRawFragment() == null && testOrigin.getRawUserInfo() == null))
            throw new IllegalArgumentException("Invalid Google video test origin");
        this.mapper = mapper; this.testOrigin = testOrigin;
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, testOrigin != null),
                Duration.ofSeconds(10), Duration.ofMinutes(8), Duration.ofMinutes(10));
    }
    /** Existing Google image base versions are accepted, but native video always chooses its fixed v1beta route. */
    public static String validatedOrigin(String origin) {
        if (origin == null || origin.isBlank()) return ORIGIN;
        if (!Set.of(ORIGIN, ORIGIN + "/", ORIGIN + "/v1", ORIGIN + "/v1/", ORIGIN + "/v1beta", ORIGIN + "/v1beta/").contains(origin))
            throw GoogleVideoModels.invalid();
        return ORIGIN;
    }
    public record Input(String mimeType, byte[] bytes, String fileName) {
        public Input(String mimeType, byte[] bytes) { this(mimeType, bytes, null); }
        @Override public String toString() { return "GoogleVideoInput[mimeType=" + mimeType + "]"; }
    }
    public record Video(byte[] bytes, String fileName) {
        @Override public String toString() { return "GoogleVideoOutput[inline=" + (bytes != null) + "]"; }
    }
    public record State(String status, Video video) {}

    public ObjectNode build(Profile p, JsonNode values, Map<String, Input> media) {
        GoogleVideoModels.requireProfile(p);
        NativeMediaProfiles.validateValues(new NativeMediaProfiles.Selection(new GoogleVideoModels(), p), values, true);
        Set<String> expected = new java.util.HashSet<>();
        for (var field : new GoogleVideoModels().inputs(p)) if (field.media() && GoogleVideoModels.present(values, field.key())) expected.add(field.key());
        if (!expected.equals(media.keySet())) throw GoogleVideoModels.invalid();
        ObjectNode body = mapper.createObjectNode();
        if (GoogleVideoModels.veo(p)) {
            ObjectNode instance = body.putArray("instances").addObject();
            copy(values, instance, "prompt");
            for (String slot : List.of("first_frame", "last_frame")) if (media.containsKey(slot)) {
                Input image = media.get(slot); validateInput(image, false);
                ObjectNode node = instance.putObject("first_frame".equals(slot) ? "image" : "lastFrame");
                node.put("bytesBase64Encoded", Base64.getEncoder().encodeToString(image.bytes())).put("mimeType", image.mimeType());
            }
            for (int i = 1; i <= 3; i++) if (media.containsKey("image_" + i)) {
                Input image = media.get("image_" + i); validateInput(image, false);
                var images = instance.has("referenceImages") ? (tools.jackson.databind.node.ArrayNode) instance.path("referenceImages") : instance.putArray("referenceImages");
                images.addObject().put("referenceType", "asset").putObject("image")
                        .put("bytesBase64Encoded", Base64.getEncoder().encodeToString(image.bytes())).put("mimeType", image.mimeType());
            }
            if (media.containsKey("base_video")) {
                Input video = media.get("base_video");
                if (!validFileName(video.fileName()) || video.bytes() != null) throw GoogleVideoModels.invalid();
                instance.putObject("video").put("uri", ORIGIN + "/v1beta/" + video.fileName());
            }
            copy(values, body.putObject("parameters"), "durationSeconds", "aspectRatio", "resolution", "personGeneration", "negativePrompt", "enhancePrompt", "seed");
            ((ObjectNode) body.path("parameters")).put("sampleCount", 1);
        } else {
            body.put("model", p.model()).put("store", true).put("stream", false)
                    .put("background", values.path("background").asBoolean(true));
            var input = body.putArray("input");
            for (var field : new GoogleVideoModels().inputs(p)) if (field.media() && media.containsKey(field.key())) {
                Input item = media.get(field.key());
                boolean video = field.type() == dev.agenvas.provider.domain.RunningHubDefinition.FieldType.VIDEO;
                validateInput(item, video);
                input.addObject().put("type", video ? "video" : "image").put("mime_type", "video/quicktime".equals(item.mimeType()) ? "video/mov" : item.mimeType())
                        .put("data", Base64.getEncoder().encodeToString(item.bytes()));
            }
            input.addObject().put("type", "text").put("text", omniRolePrefix(p, media) + values.path("prompt").asText());
            ObjectNode format = body.putObject("response_format").put("type", "video");
            copy(values, format, "aspect_ratio", "resolution", "duration", "delivery");
            ObjectNode generation = body.putObject("generation_config");
            copy(values, generation, "seed", "max_output_tokens");
            String task = GoogleVideoModels.GENERATE.equals(p.operation()) ? values.path("task").asText("auto") : p.operation().toLowerCase(java.util.Locale.ROOT);
            if (!"auto".equals(task)) generation.putObject("video_config").put("task", task);
        }
        return body;
    }
    /** Native Omni source/reference declarations preserve roles even when the prompt has no explicit mentions. */
    private static String omniRolePrefix(Profile profile, Map<String, Input> media) {
        var sources = new java.util.ArrayList<String>();
        var references = new java.util.ArrayList<String>();
        int image = 0, video = 0, imageReference = 0, videoReference = 0;
        for (var field : new GoogleVideoModels().inputs(profile)) if (field.media() && media.containsKey(field.key())) {
            boolean videoInput = field.type() == dev.agenvas.provider.domain.RunningHubDefinition.FieldType.VIDEO;
            String position = videoInput ? "@Video" + ++video : "@Image" + ++image;
            switch (field.key()) {
                case "first_frame" -> sources.add("<FIRST_FRAME>" + position);
                case "last_frame" -> sources.add("<LAST_FRAME>" + position);
                case "base_video" -> sources.add("<VIDEO_0>" + position);
                default -> references.add("<" + (videoInput ? "VIDEO_REF_" + videoReference++ : "IMAGE_REF_" + imageReference++) + ">" + position);
            }
        }
        return (sources.isEmpty() ? "" : "[# Sources " + String.join(" ", sources) + "] ")
                + (references.isEmpty() ? "" : "[# References " + String.join(" ", references) + "] ");
    }
    private static void validateInput(Input item, boolean video) {
        if (item == null || item.bytes() == null || item.bytes().length < 1
                || item.bytes().length > (video ? GoogleVideoModels.MAX_INPUT_BYTES : GoogleVideoModels.MAX_IMAGE_BYTES)
                || !(video ? GoogleVideoModels.VIDEO_TYPES : GoogleVideoModels.IMAGE_TYPES).contains(item.mimeType()) || item.fileName() != null)
            throw GoogleVideoModels.invalid();
    }
    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) if (GoogleVideoModels.present(from, field)) to.set(field, from.path(field).deepCopy());
    }

    public String create(Profile p, String origin, String key, ObjectNode body) {
        GoogleVideoModels.requireProfile(p);
        String path = GoogleVideoModels.veo(p) ? "/v1beta/models/" + p.model() + ":predictLongRunning" : "/v1beta/interactions";
        try {
            JsonNode response = request(origin, key, path, body);
            String id = response.path(GoogleVideoModels.veo(p) ? "name" : "id").asText();
            if (!validId(p, id)) throw protocol();
            if (!GoogleVideoModels.veo(p) && (!p.model().equals(response.path("model").asText())
                    || !INTERACTION_STATUSES.contains(response.path("status").asText()))) throw protocol();
            return id;
        } catch (NativeMediaFailure failed) {
            if (CREATE_REJECTED.equals(failed.code()) || "MEDIA_CREDENTIAL_UNAVAILABLE".equals(failed.code())) throw failed;
            throw new Unknown(failed.code());
        }
    }
    public State query(Profile p, String origin, String key, String originalId) {
        GoogleVideoModels.requireProfile(p);
        if (!validId(p, originalId)) throw protocol();
        JsonNode response = request(origin, key, "/v1beta/" + (GoogleVideoModels.veo(p) ? originalId : "interactions/" + originalId), null);
        if (!originalId.equals(response.path(GoogleVideoModels.veo(p) ? "name" : "id").asText())) throw protocol();
        if (GoogleVideoModels.veo(p)) {
            if (response.has("done") && !response.path("done").isBoolean()) throw protocol();
            if (!response.path("done").asBoolean(false)) return new State("pending", null);
            if (response.hasNonNull("error")) return new State("failed", null);
            JsonNode result = response.at("/response/generateVideoResponse");
            JsonNode samples = result.path("generatedSamples");
            if (result.path("raiMediaFilteredCount").asInt(0) > 0 && (!samples.isArray() || samples.isEmpty())) return new State("failed", null);
            if (!samples.isArray() || samples.size() != 1) throw protocol();
            JsonNode video = samples.path(0).path("video");
            if (!video.path("uri").isTextual()) throw protocol();
            return new State("completed", new Video(null, fileName(video.path("uri").asText())));
        }
        if (!p.model().equals(response.path("model").asText())) throw protocol();
        String status = response.path("status").asText();
        if (!INTERACTION_STATUSES.contains(status)) throw protocol();
        if (Set.of("queued", "in_progress").contains(status)) return new State("pending", null);
        if (!"completed".equals(status)) return new State("failed", null);
        if (!response.path("steps").isArray()) throw protocol();
        Video output = null;
        for (JsonNode step : response.path("steps")) if ("model_output".equals(step.path("type").asText())) {
            if (!step.path("content").isArray()) throw protocol();
            for (JsonNode content : step.path("content")) if ("video".equals(content.path("type").asText())) {
                if (output != null || !"video/mp4".equals(content.path("mime_type").asText())) throw protocol();
                boolean inline = content.path("data").isTextual(), uri = content.path("uri").isTextual();
                if (inline == uri) throw protocol();
                if (inline) {
                    byte[] bytes;
                    try { bytes = Base64.getDecoder().decode(content.path("data").asText()); }
                    catch (IllegalArgumentException invalid) { throw protocol(); }
                    if (!mp4(bytes) || bytes.length > MAX_VIDEO_BYTES) throw protocol();
                    output = new Video(bytes, null);
                } else output = new Video(null, fileName(content.path("uri").asText()));
            }
        }
        if (output == null) throw protocol();
        return new State("completed", output);
    }

    public boolean fileActive(String origin, String key, String name) {
        if (!validFileName(name)) throw protocol();
        JsonNode file = request(origin, key, "/v1beta/" + name, null);
        if (!name.equals(file.path("name").asText()) || !"video/mp4".equals(file.path("mimeType").asText())) throw protocol();
        return switch (file.path("state").asText()) {
            case "ACTIVE" -> true;
            case "PROCESSING" -> false;
            case "FAILED" -> throw new NativeMediaFailure(TASK_FAILED);
            default -> throw protocol();
        };
    }

    /** The only authenticated download is the fixed Files route; a single GCS hop is credential-free. */
    public void download(String origin, String key, String name, OutputStream target) {
        if (!validFileName(name)) throw new NativeMediaFailure(ProviderFailureCodes.RESULT_URL_INVALID);
        Request request = new Request.Builder().url(endpoint(origin, "/v1beta/" + name + ":download?alt=media").toString())
                .header("x-goog-api-key", checkedKey(key)).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (Set.of(301, 302, 303, 307, 308).contains(response.code())) {
                String location = response.header("Location");
                URI redirect;
                try { redirect = URI.create(location == null ? "" : location); } catch (IllegalArgumentException invalid) { throw resultUrl(); }
                if (!"https".equals(redirect.getScheme()) || !"storage.googleapis.com".equals(redirect.getHost())
                        || redirect.getRawUserInfo() != null || redirect.getRawFragment() != null || redirect.getPort() != -1
                        || redirect.toString().length() > 8192 || redirect.getRawPath() == null || redirect.getRawPath().length() < 2)
                    throw resultUrl();
                dev.agenvas.shared.http.DebugHttpCapture.registerMediaRelayUrl(redirect.toString());
                // Build a new request: no API key, authorization, cookies or caller-selected headers cross hosts.
                try (Response object = http.newCall(new Request.Builder().url(redirect.toString()).get().build()).execute()) {
                    transfer(object, target);
                }
            } else transfer(response, target);
        } catch (IOException unavailable) { throw new TechnicalFailure(); }
    }
    private static void transfer(Response response, OutputStream target) throws IOException {
        if (response.code() == 404 || response.code() == 410) throw new NativeMediaFailure(RESULT_EXPIRED);
        if (response.code() == 401 || response.code() == 403) throw new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE");
        if (response.code() == 429 || response.code() >= 500) throw new TechnicalFailure();
        if (response.code() != 200 || response.body() == null) throw resultUrl();
        String contentType = response.body().contentType() == null ? "" : response.body().contentType().toString().split(";", 2)[0];
        if (!Set.of("video/mp4", "application/octet-stream").contains(contentType)) throw protocol();
        if (response.body().contentLength() > MAX_VIDEO_BYTES) throw new NativeMediaFailure(ProviderFailureCodes.RESULT_TOO_LARGE);
        try (InputStream input = response.body().byteStream()) {
            byte[] buffer = new byte[65536]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count; if (total > MAX_VIDEO_BYTES) throw new NativeMediaFailure(ProviderFailureCodes.RESULT_TOO_LARGE);
                target.write(buffer, 0, count);
            }
            if (total == 0) throw protocol();
        }
    }
    private JsonNode request(String origin, String key, String path, ObjectNode body) {
        Request.Builder request = new Request.Builder().url(endpoint(origin, path).toString())
                .header("x-goog-api-key", checkedKey(key)).header("Accept", "application/json");
        if (body != null) request.post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), MediaType.parse("application/json")));
        try (Response response = http.newCall(request.build()).execute()) {
            int status = response.code();
            if (status == 401 || status == 403) throw new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE");
            if (body == null && (status == 404 || status == 410)) throw new NativeMediaFailure(TASK_EXPIRED);
            if (status >= 400 && status < 500 && status != 429) throw new NativeMediaFailure(CREATE_REJECTED);
            if (status != 200) { if (body != null) throw new Unknown(ProviderFailureCodes.SUBMISSION_UNKNOWN); throw new TechnicalFailure(); }
            if (response.body() == null) throw protocol();
            try (InputStream input = response.body().byteStream()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw new NativeMediaFailure(ProviderFailureCodes.RESPONSE_TOO_LARGE);
                return responses.readTree(bytes);
            }
        } catch (IOException unavailable) {
            if (body != null) throw new Unknown(OutboundTimeouts.isTimeout(unavailable) ? ProviderFailureCodes.CALL_TIMEOUT : ProviderFailureCodes.RESPONSE_LOST);
            throw new TechnicalFailure();
        } catch (RuntimeException invalid) {
            if (invalid instanceof NativeMediaFailure || invalid instanceof Unknown || invalid instanceof TechnicalFailure) throw invalid;
            if (body != null) throw new Unknown(ProviderFailureCodes.PROTOCOL_INVALID);
            throw protocol();
        }
    }
    private URI endpoint(String origin, String path) {
        validatedOrigin(origin);
        return (testOrigin == null ? URI.create(ORIGIN) : testOrigin).resolve(path);
    }
    public static boolean validId(Profile p, String id) {
        if (id == null || id.length() > 512) return false;
        if (!GoogleVideoModels.veo(p)) return id.matches("[A-Za-z0-9_-]{1,512}");
        return id.matches("operations/[A-Za-z0-9._-]{1,512}")
                || id.matches("models/" + java.util.regex.Pattern.quote(p.model()) + "/operations/[A-Za-z0-9._-]{1,512}");
    }
    public static boolean validFileName(String name) { return name != null && name.matches("files/[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?"); }
    public static String fileName(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException invalid) { throw resultUrl(); }
        if (!"https".equals(uri.getScheme()) || !"generativelanguage.googleapis.com".equals(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() != -1
                || uri.getRawQuery() != null && !"alt=media".equals(uri.getRawQuery())) throw resultUrl();
        String path = uri.getRawPath();
        String name = path != null ? path.replaceFirst("^/(?:download/)?v1beta/", "").replaceFirst(":download$", "") : "";
        if (!validFileName(name)) throw resultUrl();
        return name;
    }
    private static String checkedKey(String key) {
        if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0)
            throw new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE");
        return key;
    }
    private static boolean mp4(byte[] bytes) { return bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p'; }
    private static NativeMediaFailure protocol() { return new NativeMediaFailure(ProviderFailureCodes.PROTOCOL_INVALID); }
    private static NativeMediaFailure resultUrl() { return new NativeMediaFailure(ProviderFailureCodes.RESULT_URL_INVALID); }
    public static final class Unknown extends RuntimeException {
        private final String code;
        public Unknown(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    public static final class TechnicalFailure extends RuntimeException { public TechnicalFailure() { super(ProviderFailureCodes.DOWNLOAD_FAILED); } }
}
