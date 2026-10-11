package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.provider.domain.WanModels;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.IOException;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Region-bound asynchronous Wan transport; a create response can never trigger a second POST. */
@Component
public class WanClient {
    public static final String CREATE_REJECTED = "WAN_CREATE_REJECTED";
    public static final String CREATE_UNCERTAIN = "WAN_CREATE_UNCERTAIN";
    public static final String TASK_FAILED = "WAN_TASK_FAILED";
    public static final String TASK_EXPIRED = "WAN_TASK_EXPIRED";
    public static final String RESULT_EXPIRED = "WAN_RESULT_EXPIRED";
    public static final String IMAGE_PATH = "/api/v1/services/aigc/image-generation/generation";
    public static final String VIDEO_PATH = "/api/v1/services/aigc/video-generation/video-synthesis";
    private static final Set<String> REGIONS = Set.of("cn-beijing", "ap-southeast-1", "ap-northeast-1", "eu-central-1", "us-east-1", "cn-hongkong");
    private static final Set<String> STATUSES = Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED", "UNKNOWN");
    private static final Set<String> AUTH_CODES = Set.of("InvalidApiKey", "invalid_api_key", "AccessDenied", "access_denied", "Forbidden");
    private static final Set<String> REFUSAL_CODES = Set.of("InvalidApiKey", "invalid_api_key", "AccessDenied", "access_denied",
            "InvalidParameter", "InvalidParameterValue", "InvalidInput", "DataInspectionFailed", "InputDataInspectionFailed",
            "InvalidModel", "ModelNotFound", "UnsupportedOperation", "Throttling", "Throttling.RateQuota", "Throttling.Concurrency",
            "Throttling.AllocationQuota", "Throttling.BurstRate", "CommodityNotPurchased", "PrepaidBillOverdue", "PostpaidBillOverdue", "BudgetLimitExceeded");
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final URI testOrigin;
    private final OkHttpClient http;
    private final WanModels models = new WanModels();

    @Autowired public WanClient(ObjectMapper mapper) { this(mapper, null); }
    /** The loopback transport is available only through this explicit fake-provider test constructor. */
    public WanClient(ObjectMapper mapper, URI testOrigin) {
        requireLoopback(testOrigin);
        this.mapper = mapper; this.testOrigin = testOrigin;
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, testOrigin != null),
                Duration.ofSeconds(10), Duration.ofSeconds(45), Duration.ofMinutes(3));
    }
    static void requireLoopback(URI origin) {
        if (origin != null && !("http".equals(origin.getScheme()) && "127.0.0.1".equals(origin.getHost())
                && origin.getPort() > 0 && origin.getPort() <= 65535 && origin.getRawPath().isEmpty()
                && origin.getRawUserInfo() == null && origin.getRawQuery() == null && origin.getRawFragment() == null))
            throw new IllegalArgumentException("Invalid Wan test origin");
    }
    public static String validatedOrigin(String value) {
        if (value == null || value.isBlank()) throw NativeMediaProfiles.invalid();
        String normalized = value.trim().replaceAll("/+$", "");
        URI origin;
        try { origin = URI.create(normalized); } catch (RuntimeException invalid) { throw NativeMediaProfiles.invalid(); }
        String host = origin.getHost();
        boolean workspace = host != null && !host.startsWith("trial.") && REGIONS.stream().anyMatch(region ->
                host.matches("[A-Za-z0-9][A-Za-z0-9-]{0,62}\\." + java.util.regex.Pattern.quote(region) + "\\.maas\\.aliyuncs\\.com"));
        if (!"https".equals(origin.getScheme()) || origin.getPort() != -1 && origin.getPort() != 443
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !origin.getRawPath().isEmpty() || !workspace)
            throw NativeMediaProfiles.invalid();
        return normalized;
    }
    public static void validateOrigin(NativeMediaProtocol.Profile profile, String value) {
        String origin = validatedOrigin(value);
        if (!WanModels.video(profile) && !origin.matches("https://[A-Za-z0-9][A-Za-z0-9-]{0,62}\\.(cn-beijing|ap-southeast-1)\\.maas\\.aliyuncs\\.com(?::443)?"))
            throw NativeMediaProfiles.invalid();
    }

    /** Values are already normalized; only native fields from the compiled profile are serialized. */
    public ObjectNode build(NativeMediaProtocol.Profile profile, JsonNode values, Map<String, String> prepared) {
        WanModels.requireProfile(profile);
        NativeMediaProfiles.validateValues(new NativeMediaProfiles.Selection(models, profile), values, true);
        Set<String> expected = new HashSet<>();
        for (var field : models.inputs(profile)) if (field.media() && WanModels.present(values, field.key())) expected.add(field.key());
        if (prepared == null || !expected.equals(prepared.keySet())) throw NativeMediaProfiles.invalid();
        for (var field : models.inputs(profile)) if (expected.contains(field.key())) validatePrepared(prepared.get(field.key()), field.type() == dev.agenvas.provider.domain.RunningHubDefinition.FieldType.IMAGE);
        ObjectNode body = mapper.createObjectNode().put("model", profile.model());
        ObjectNode input = body.putObject("input"), parameters = body.putObject("parameters");
        if (WanModels.video(profile)) {
            copy(values, input, "prompt");
            if (!prepared.isEmpty()) {
                var media = input.putArray("media");
                for (var field : models.inputs(profile)) if (prepared.containsKey(field.key())) {
                    String type = field.key().startsWith("video_") ? "reference_video" : field.key().startsWith("audio_") ? "reference_audio"
                            : field.key().startsWith("image_") ? "reference_image" : field.key();
                    media.addObject().put("type", type).put("url", prepared.get(field.key()));
                }
            }
            copy(values, parameters, "resolution", "ratio", "audio", "seed", "prompt_extend", "watermark");
            parameters.put("duration", values.path("smart_duration").asBoolean(false) ? -1 : values.path("duration").asInt(5));
        } else {
            var content = input.putArray("messages").addObject().put("role", "user").putArray("content");
            boolean boxes = false;
            for (int i = 1; i <= 9; i++) if (prepared.containsKey("image_" + i)) {
                content.addObject().put("image", prepared.get("image_" + i));
                for (int box = 1; box <= 2; box++) boxes |= WanModels.present(values, "image_" + i + "_bbox_" + box + "_x1");
            }
            content.addObject().put("text", values.path("prompt").asText());
            copy(values, parameters, "size", "n", "enable_sequential", "watermark", "seed");
            if (prepared.isEmpty() && !values.path("enable_sequential").asBoolean(false)) copy(values, parameters, "thinking_mode");
            if (boxes) {
                var boxList = parameters.putArray("bbox_list");
                for (int i = 1; i <= 9; i++) if (prepared.containsKey("image_" + i)) {
                    var imageBoxes = boxList.addArray();
                    for (int box = 1; box <= 2; box++) {
                        String prefix = "image_" + i + "_bbox_" + box + "_";
                        if (WanModels.present(values, prefix + "x1")) {
                            var coordinates = imageBoxes.addArray();
                            for (String coordinate : List.of("x1", "y1", "x2", "y2")) coordinates.add(values.path(prefix + coordinate).asInt());
                        }
                    }
                }
            }
            for (int i = 1; i <= 10; i++) if (WanModels.present(values, "color_" + i + "_hex")) {
                var palette = parameters.has("color_palette") ? (tools.jackson.databind.node.ArrayNode) parameters.path("color_palette") : parameters.putArray("color_palette");
                palette.addObject().put("hex", values.path("color_" + i + "_hex").asText())
                        .put("ratio", values.path("color_" + i + "_ratio").decimalValue().setScale(2, RoundingMode.UNNECESSARY).toPlainString() + "%");
            }
        }
        return body;
    }
    private static void copy(JsonNode source, ObjectNode target, String... keys) {
        for (String key : keys) if (WanModels.present(source, key)) target.set(key, source.path(key).deepCopy());
    }
    private static void validatePrepared(String value, boolean image) {
        if (value == null || value.isBlank()) throw NativeMediaProfiles.invalid();
        if (image && value.startsWith("data:")) {
            int separator = value.indexOf(';');
            if (separator < 0 || !Set.of("data:image/jpeg", "data:image/png", "data:image/bmp", "data:image/webp").contains(value.substring(0, separator))
                    || !value.startsWith(";base64,", separator)) throw NativeMediaProfiles.invalid();
            String bytes = value.substring(separator + 8);
            if (bytes.isEmpty() || bytes.length() % 4 != 0 || bytes.length() > 4L * ((WanModels.MAX_IMAGE_BYTES + 2L) / 3)
                    || !bytes.matches("[A-Za-z0-9+/]+={0,2}")) throw NativeMediaProfiles.invalid();
        } else {
            URI url;
            try { url = URI.create(value); } catch (RuntimeException invalid) { throw NativeMediaProfiles.invalid(); }
            if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getRawUserInfo() != null || url.getRawFragment() != null
                    || url.getPort() != -1 && url.getPort() != 443 || value.length() > 8192) throw NativeMediaProfiles.invalid();
        }
    }

    public record Output(String identity, String url) {}
    public record State(String status, List<Output> outputs, JsonNode usage) {}
    public String create(NativeMediaProtocol.Profile profile, String origin, String key, ObjectNode body) {
        validateOrigin(profile, origin);
        if (!profile.model().equals(body.path("model").asText())) throw NativeMediaProfiles.invalid();
        JsonNode output = request(origin, key, WanModels.video(profile) ? VIDEO_PATH : IMAGE_PATH, body).path("output");
        String id = output.path("task_id").asText();
        if (!output.path("task_id").isTextual() || !validId(id) || !STATUSES.contains(output.path("task_status").asText())) throw new Uncertain();
        return id;
    }
    public State query(NativeMediaProtocol.Profile profile, String origin, String key, String id) {
        validateOrigin(profile, origin);
        if (!validId(id)) throw protocolFailure();
        JsonNode response = request(origin, key, "/api/v1/tasks/" + id, null), output = response.path("output");
        if (!output.path("task_id").isTextual() || !id.equals(output.path("task_id").asText())) throw protocolFailure();
        String status = output.path("task_status").asText();
        if (!STATUSES.contains(status)) throw protocolFailure();
        if ("UNKNOWN".equals(status)) throw new NativeMediaFailure(TASK_EXPIRED);
        List<Output> results = new ArrayList<>();
        ObjectNode usage = mapper.createObjectNode();
        if ("SUCCEEDED".equals(status)) {
            if (WanModels.video(profile)) results.add(new Output("video", outputUrl(output.path("video_url"))));
            else {
                if (!output.path("finished").isBoolean() || !output.path("finished").asBoolean() || !output.path("choices").isArray()) throw protocolFailure();
                int choice = 0;
                for (JsonNode item : output.path("choices")) {
                    var message = item.path("message");
                    if (!"stop".equals(item.path("finish_reason").asText()) || !"assistant".equals(message.path("role").asText())
                            || !message.path("content").isArray()) throw protocolFailure();
                    int index = 0;
                    for (JsonNode content : message.path("content")) {
                        String type = content.path("type").asText();
                        if ("image".equals(type)) results.add(new Output("choice" + choice + ":content" + index, outputUrl(content.path("image"))));
                        else if (!"text".equals(type) || !content.path("text").isTextual()) throw protocolFailure();
                        index++;
                    }
                    choice++;
                }
                if (results.isEmpty() || results.size() > 12) throw protocolFailure();
            }
            for (String field : List.of("image_count", "video_count", "duration", "input_video_duration", "output_video_duration", "fps", "SR")) {
                JsonNode value = response.path("usage").path(field);
                if (!value.isMissingNode() && !value.isNull()) {
                    if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0) throw protocolFailure();
                    usage.set(field, value.deepCopy());
                }
            }
            if (usage.has("image_count") && (!usage.path("image_count").isIntegralNumber() || usage.path("image_count").asInt() != results.size())
                    || usage.has("video_count") && (!usage.path("video_count").isIntegralNumber() || usage.path("video_count").asInt() != 1)) throw protocolFailure();
        }
        return new State(status, List.copyOf(results), usage);
    }
    private static String outputUrl(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 8192) throw protocolFailure();
        return value.asText();
    }

    private JsonNode request(String origin, String key, String path, ObjectNode body) {
        URI endpoint = URI.create(validatedOrigin(origin));
        if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) throw new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE");
        boolean create = body != null;
        var builder = new Request.Builder().url((testOrigin == null ? endpoint : testOrigin).resolve(path).toString())
                .header("Authorization", "Bearer " + key).header("Accept", "application/json");
        if (create) builder.header("X-DashScope-Async", "enable").post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.get("application/json")));
        else builder.get();
        try (Response response = http.newCall(builder.build()).execute()) {
            int status = response.code();
            if (create && Set.of(400, 401, 402, 403, 404, 413, 422, 429).contains(status)) throw new Rejected();
            if (!create && Set.of(401, 403).contains(status)) throw new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE");
            if (!create && Set.of(404, 410).contains(status)) throw new NativeMediaFailure(TASK_EXPIRED);
            if (status != 200 || response.body() == null) throw transportFailure(create);
            byte[] bytes = response.body().byteStream().readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw transportFailure(create);
            JsonNode parsed = mapper.readTree(bytes);
            if (!parsed.isObject()) throw transportFailure(create);
            String code = parsed.path("code").asText("");
            if (parsed.hasNonNull("code") && !parsed.path("code").isTextual()) throw transportFailure(create);
            if (!code.isEmpty()) {
                if (create && REFUSAL_CODES.contains(code)) throw new Rejected();
                if (!create && AUTH_CODES.contains(code)) throw new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE");
                if (!create && REFUSAL_CODES.contains(code)) throw protocolFailure();
                throw transportFailure(create);
            }
            return parsed;
        } catch (IOException unavailable) { throw transportFailure(create); }
        catch (RuntimeException failure) {
            if (failure instanceof Rejected || failure instanceof Uncertain || failure instanceof TechnicalFailure || failure instanceof NativeMediaFailure) throw failure;
            throw transportFailure(create);
        }
    }
    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"); }
    public static NativeMediaFailure protocolFailure() { return new NativeMediaFailure("PROVIDER_PROTOCOL_INVALID"); }
    private static RuntimeException transportFailure(boolean create) { return create ? new Uncertain() : new TechnicalFailure(); }
    public static final class Rejected extends RuntimeException { public Rejected() { super(CREATE_REJECTED); } }
    public static final class Uncertain extends RuntimeException { public Uncertain() { super(CREATE_UNCERTAIN); } }
    public static final class TechnicalFailure extends RuntimeException { public TechnicalFailure() { super("Wan transport unavailable"); } }
}
