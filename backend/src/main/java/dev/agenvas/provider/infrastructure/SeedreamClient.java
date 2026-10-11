package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.SeedreamModels;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
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

/** One synchronous fixed Ark POST; inline results survive recovery without another paid request. */
@Component
public class SeedreamClient {
    public static final String ORIGIN = "https://ark.cn-beijing.volces.com";
    public static final String CREATE_REJECTED = "SEEDREAM_CREATE_REJECTED";
    public static final String CREATE_UNCERTAIN = "SEEDREAM_CREATE_UNCERTAIN";
    public static final String GENERATION_FAILED = "SEEDREAM_GENERATION_FAILED";
    public static final int MAX_RESPONSE_BYTES = 96 * 1024 * 1024;
    public static final int MAX_RESULT_BYTES = 50 * 1024 * 1024;
    private static final String PATH = "/api/v3/images/generations";
    private final ObjectMapper mapper;
    private final URI testOrigin;
    private final OkHttpClient http;
    private final SeedreamModels models = new SeedreamModels();
    private final ObjectMapper responses = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(4 * ((MAX_RESULT_BYTES + 2) / 3)).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @Autowired public SeedreamClient(ObjectMapper mapper) { this(mapper, null); }

    /** The loopback seam is constructor-only and cannot be selected by an administrator. */
    public SeedreamClient(ObjectMapper mapper, URI testOrigin) {
        if (testOrigin != null && !("http".equals(testOrigin.getScheme())
                && "127.0.0.1".equals(testOrigin.getHost()) && testOrigin.getPort() > 0 && testOrigin.getPort() <= 65535
                && "".equals(testOrigin.getRawPath()) && testOrigin.getRawUserInfo() == null
                && testOrigin.getRawQuery() == null && testOrigin.getRawFragment() == null))
            throw new IllegalArgumentException("Unsupported Seedream test origin");
        this.mapper = mapper;
        this.testOrigin = testOrigin;
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, testOrigin != null),
                Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofMinutes(6));
    }

    public static String validatedOrigin(String origin) {
        if (origin == null || origin.isBlank()) return ORIGIN;
        String normalized = origin.trim().replaceAll("/+$", "");
        if (!ORIGIN.equals(normalized)) throw SeedreamModels.invalid();
        return normalized;
    }

    /** Only finite compiled fields are serialized; advanced editing JSON is rendered into prompt tags. */
    public ObjectNode build(NativeMediaProtocol.Profile profile, JsonNode values, Map<String, String> media) {
        NativeMediaProfiles.validateValues(new NativeMediaProfiles.Selection(models, profile), values, true);
        Set<String> expected = new HashSet<>();
        for (var field : models.inputs(profile)) if (field.media() && SeedreamModels.present(values, field.key())) expected.add(field.key());
        if (media == null || !media.keySet().equals(expected)) throw SeedreamModels.invalid();
        ObjectNode body = mapper.createObjectNode().put("model", profile.model()).put("stream", false)
                .put("response_format", "b64_json").put("size", values.path("size").asText(SeedreamModels.defaultSize(profile)))
                .put("watermark", values.path("watermark").asBoolean(true));
        String prompt = SeedreamModels.prompt(profile, values);
        if (!prompt.isBlank()) body.put("prompt", prompt);
        if (!expected.isEmpty()) {
            var images = body.putArray("image");
            for (int i = 1; i <= SeedreamModels.maximumImages(profile); i++) if (expected.contains("image" + i)) {
                String data = media.get("image" + i);
                validateInputDataUri(data, SeedreamModels.layers(profile));
                images.add(data);
            }
        }
        body.putObject("optimize_prompt_options").put("mode", values.path("optimize_prompt_mode").asText("standard"));
        if (SeedreamModels.layers(profile)) body.put("layer_decomposition", true);
        if (SeedreamModels.proFamily(profile) || SeedreamModels.lite(profile))
            body.put("output_format", values.path("output_format").asText("transparent".equals(values.path("background").asText()) ? "png" : "jpeg"));
        if (SeedreamModels.proFamily(profile) && !SeedreamModels.layers(profile))
            body.put("background", values.path("background").asText("opaque"));
        if (!SeedreamModels.proFamily(profile)) {
            String sequential = values.path("sequential_image_generation").asText("disabled");
            body.put("sequential_image_generation", sequential);
            if ("auto".equals(sequential)) body.putObject("sequential_image_generation_options")
                    .put("max_images", values.path("max_images").asInt(15));
        }
        if (SeedreamModels.lite(profile) && values.path("web_search").asBoolean(false))
            body.putArray("tools").addObject().put("type", "web_search");
        return body;
    }

    public Created create(NativeMediaProtocol.Profile profile, JsonNode values, String origin, String key,
            ObjectNode body, String localRequestKey) {
        String runtimeOrigin = validatedOrigin(origin);
        if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0
                || localRequestKey == null || localRequestKey.isBlank() || localRequestKey.length() > 160)
            throw SeedreamModels.invalid();
        // A caller cannot bypass build's allowlist with an arbitrary body.
        Set<String> allowed = Set.of("model", "prompt", "image", "size", "watermark", "stream", "response_format",
                "optimize_prompt_options", "layer_decomposition", "output_format", "background", "sequential_image_generation",
                "sequential_image_generation_options", "tools");
        if (body == null || !allowed.containsAll(body.propertyNames()) || !profile.model().equals(body.path("model").asText())
                || !body.path("stream").isBoolean() || body.path("stream").asBoolean()
                || !"b64_json".equals(body.path("response_format").asText())) throw SeedreamModels.invalid();
        Map<String, String> inlineInputs = new java.util.HashMap<>();
        int expectedImages = SeedreamModels.imageCount(profile, values);
        if (body.has("image")) {
            if (!body.path("image").isArray() || body.path("image").size() != expectedImages) throw SeedreamModels.invalid();
            for (int i = 0; i < expectedImages; i++) {
                if (!body.path("image").get(i).isTextual()) throw SeedreamModels.invalid();
                inlineInputs.put("image" + (i + 1), body.path("image").get(i).asText());
            }
        }
        if (!build(profile, values, inlineInputs).equals(body)) throw SeedreamModels.invalid();
        URI target = (testOrigin == null ? URI.create(runtimeOrigin) : testOrigin).resolve(PATH);
        Request request = new Request.Builder().url(target.toString()).header("Authorization", "Bearer " + key)
                .header("Accept", "application/json").post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json; charset=utf-8"))).build();
        try (Response response = http.newCall(request).execute()) {
            int status = response.code();
            if (status >= 400 && status < 500 && status != 429) throw new Rejected(CREATE_REJECTED);
            if (status != 200 || response.body() == null) throw new Uncertain(CREATE_UNCERTAIN);
            if (response.body().contentLength() > MAX_RESPONSE_BYTES) throw new Uncertain(ProviderFailureCodes.RESPONSE_TOO_LARGE);
            byte[] bytes = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new Uncertain(ProviderFailureCodes.RESPONSE_TOO_LARGE);
            JsonNode parsed = responses.readTree(bytes);
            if (parsed == null || !parsed.isObject()) throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID);
            if (parsed.path("error").isObject() && !parsed.path("data").isArray()) throw new Rejected(GENERATION_FAILED);
            ProviderResultManifest manifest;
            try { manifest = manifest(profile, values, parsed); }
            catch (NativeMediaFailure malformed) { throw new Uncertain(malformed.code()); }
            String id = validIdentity(parsed.path("request_id").asText(null));
            if (id == null) id = validIdentity(response.header("X-Request-Id"));
            if (id == null) id = localRequestKey; // Local identity does not grant a remote query capability.
            return new Created(id, manifest);
        } catch (SocketTimeoutException timedOut) { throw new Uncertain(ProviderFailureCodes.CALL_TIMEOUT); }
        catch (IOException lost) { throw new Uncertain(ProviderFailureCodes.RESPONSE_LOST); }
        catch (RuntimeException failure) {
            if (failure instanceof Uncertain || failure instanceof Rejected) throw failure;
            throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID);
        }
    }

    public ProviderResultManifest manifest(NativeMediaProtocol.Profile profile, JsonNode values, JsonNode response) {
        if (response == null || !response.isObject() || !sameModel(profile, response.path("model").asText())
                || !response.path("created").isIntegralNumber() || !response.path("created").canConvertToLong()
                || response.path("created").asLong() < 0 || !response.path("data").isArray()
                || response.path("data").isEmpty() || response.path("data").size() > SeedreamModels.maximumResults(profile, values)) throw protocol();
        var outputs = new ArrayList<Parsed>();
        int providerIndex = 0;
        for (var item : response.path("data")) {
            if (!item.isObject()) throw protocol();
            if (item.path("error").isObject()) {
                if (SeedreamModels.layers(profile) || !"auto".equals(values.path("sequential_image_generation").asText())
                        || item.hasNonNull("b64_json") || item.hasNonNull("url")) throw protocol();
                providerIndex++;
                continue;
            }
            int z = SeedreamModels.layers(profile) ? integral(item.path("z_index"), 0, 16) : providerIndex;
            String expectedMime = SeedreamModels.layers(profile) && z > 0 ? "image/png"
                    : "png".equals(values.path("output_format").asText("jpeg")) ? "image/png" : "image/jpeg";
            if (item.hasNonNull("url") || !item.path("b64_json").isTextual()) throw protocol();
            byte[] image = decodeBase64(item.path("b64_json").asText(), MAX_RESULT_BYTES);
            ImageInfo info = imageInfo(image);
            if (!expectedMime.equals(info.contentType()) || !size(info.width(), info.height()).equals(item.path("size").asText())
                    || item.hasNonNull("output_format") && !expectedMime.substring(6).equals(item.path("output_format").asText())
                    || "transparent".equals(values.path("background").asText()) && !info.alpha()) throw protocol();
            if (!SeedreamModels.layers(profile)) {
                try { SeedreamModels.validateSize(profile, size(info.width(), info.height())); }
                catch (RuntimeException invalidSize) { throw protocol(); }
                String requested = values.path("size").asText(SeedreamModels.defaultSize(profile));
                if (requested.contains("x") && !requested.equals(size(info.width(), info.height()))) throw protocol();
            }
            ObjectNode metadata = mapper.createObjectNode().put("model", profile.model()).put("operation", profile.operation())
                    .put("size", size(info.width(), info.height())).put("output_format", expectedMime.substring(6));
            if (SeedreamModels.layers(profile)) {
                metadata.put("z_index", z);
                for (String field : List.of("name", "description")) if (item.hasNonNull(field)) {
                    if (!item.path(field).isTextual() || item.path(field).asText().length() > ("name".equals(field) ? 160 : 2000)) throw protocol();
                    metadata.set(field, item.path(field));
                }
                if (z == 0 && item.hasNonNull("bounding_box") || z > 0 && !item.path("bounding_box").isObject()) throw protocol();
                if (z > 0) metadata.set("bounding_box", item.path("bounding_box").deepCopy());
            }
            outputs.add(new Parsed(providerIndex, z, info.width(), info.height(),
                    "data:" + expectedMime + ";base64," + item.path("b64_json").asText(), metadata));
            providerIndex++;
        }
        if (outputs.isEmpty()) throw new Rejected(GENERATION_FAILED);
        if (SeedreamModels.layers(profile)) validateLayers(outputs);
        var ordered = SeedreamModels.layers(profile) ? outputs.stream().sorted(Comparator.comparingInt(Parsed::z)).toList() : List.copyOf(outputs);
        var results = new ArrayList<ProviderResultManifest.Result>();
        for (Parsed output : ordered) results.add(new ProviderResultManifest.Result(results.size(),
                "seedream:" + profile.operation() + ":" + profile.model() + ":" + output.providerIndex(),
                RunningHubDefinition.OutputKind.IMAGE, results.isEmpty(), output.data(), null, output.metadata()));
        return new ProviderResultManifest(ProviderResultManifest.SCHEMA_VERSION, List.copyOf(results), usage(response.path("usage")));
    }

    private void validateLayers(List<Parsed> outputs) {
        var sorted = outputs.stream().sorted(Comparator.comparingInt(Parsed::z)).toList();
        if (sorted.getFirst().z() != 0) throw protocol();
        Parsed base = sorted.getFirst();
        for (int i = 0; i < sorted.size(); i++) {
            Parsed item = sorted.get(i);
            if (item.z() != i) throw protocol();
            if (i == 0) continue;
            JsonNode box = item.metadata().path("bounding_box");
            if (!box.propertyNames().equals(Set.of("absolute", "normalized"))) throw protocol();
            rectangle(box.path("absolute"), base.width(), base.height(), false);
            // Discrete normalization can collapse a narrow layer after integer rounding.
            rectangle(box.path("normalized"), 1000, 1000, true);
        }
    }

    private static void rectangle(JsonNode value, int maximumX, int maximumY, boolean allowZero) {
        if (!value.isArray() || value.size() != 4) throw protocol();
        int left = integral(value.get(0), 0, maximumX), top = integral(value.get(1), 0, maximumY);
        int right = integral(value.get(2), 0, maximumX), bottom = integral(value.get(3), 0, maximumY);
        if (allowZero ? left > right || top > bottom : left >= right || top >= bottom) throw protocol();
    }

    private JsonNode usage(JsonNode source) {
        if (source.isMissingNode() || source.isNull()) return null;
        if (!source.isObject()) throw protocol();
        ObjectNode result = mapper.createObjectNode();
        for (String key : List.of("generated_images", "input_images", "output_tokens", "total_tokens")) if (source.hasNonNull(key)) {
            JsonNode value = source.path(key);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw protocol();
            result.set(key, value);
        }
        if (source.path("tool_usage").hasNonNull("web_search")) {
            JsonNode searches = source.path("tool_usage").path("web_search");
            if (!searches.isIntegralNumber() || !searches.canConvertToLong() || searches.asLong() < 0) throw protocol();
            result.putObject("tool_usage").set("web_search", searches);
        }
        return result;
    }

    public static byte[] inlineBytes(String data) {
        if (data == null) throw protocol();
        String prefix = data.startsWith("data:image/png;base64,") ? "data:image/png;base64,"
                : data.startsWith("data:image/jpeg;base64,") ? "data:image/jpeg;base64," : null;
        if (prefix == null) throw protocol();
        byte[] bytes = decodeBase64(data.substring(prefix.length()), MAX_RESULT_BYTES);
        ImageInfo info = imageInfo(bytes);
        if (!("data:" + info.contentType() + ";base64,").equals(prefix)) throw protocol();
        return bytes;
    }

    /** Alpha is checked on the exact reference, including palette PNG and supported WebP decoders. */
    public static void requireAlpha(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || !image.getColorModel().hasAlpha()) throw SeedreamModels.invalid();
        } catch (IOException invalid) { throw SeedreamModels.invalid(); }
    }

    private static void validateInputDataUri(String value, boolean layers) {
        if (value == null) throw SeedreamModels.invalid();
        int comma = value.indexOf(',');
        if (comma < 0 || !(layers ? Set.of("data:image/png;base64", "data:image/jpeg;base64")
                : Set.of("data:image/png;base64", "data:image/jpeg;base64", "data:image/webp;base64")).contains(value.substring(0, comma))) throw SeedreamModels.invalid();
        decodeBase64(value.substring(comma + 1), SeedreamModels.MAX_INPUT_BYTES);
    }

    private static byte[] decodeBase64(String base64, int maximum) {
        if (base64 == null || base64.isEmpty() || base64.length() % 4 != 0 || base64.length() > 4L * ((maximum + 2L) / 3)
                || !base64.matches("[A-Za-z0-9+/]+={0,2}")) throw protocol();
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length < 1 || bytes.length > maximum) throw new NativeMediaFailure(ProviderFailureCodes.RESULT_TOO_LARGE);
            return bytes;
        } catch (IllegalArgumentException invalid) { throw protocol(); }
    }

    private static ImageInfo imageInfo(byte[] bytes) {
        try (var source = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (source == null) throw protocol();
            var readers = ImageIO.getImageReaders(source);
            if (!readers.hasNext()) throw protocol();
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("png", "jpg", "jpeg").contains(format)) throw protocol();
                reader.setInput(source, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 36_000_000) throw protocol();
                BufferedImage image = reader.read(0);
                if (image == null) throw protocol();
                return new ImageInfo("png".equals(format) ? "image/png" : "image/jpeg", width, height, image.getColorModel().hasAlpha());
            } finally { reader.dispose(); }
        } catch (IOException malformed) { throw protocol(); }
    }
    private static int integral(JsonNode value, int minimum, int maximum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < minimum || value.asInt() > maximum) throw protocol();
        return value.asInt();
    }
    private static boolean sameModel(NativeMediaProtocol.Profile profile, String returned) {
        return profile.model().equals(returned) || SeedreamModels.lite(profile) && Set.of(SeedreamModels.LITE, SeedreamModels.LITE_ALIAS).contains(returned);
    }
    private static String size(int width, int height) { return width + "x" + height; }
    private static String validIdentity(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_:.\\-]{0,159}") ? id : null;
    }
    private static NativeMediaFailure protocol() { return new NativeMediaFailure(ProviderFailureCodes.PROTOCOL_INVALID); }
    private record ImageInfo(String contentType, int width, int height, boolean alpha) {}
    private record Parsed(int providerIndex, int z, int width, int height, String data, ObjectNode metadata) {
        @Override public String toString() { return "SeedreamOutput[index=" + providerIndex + "]"; }
    }
    public record Created(String requestId, ProviderResultManifest manifest) {
        @Override public String toString() { return "SeedreamCreated[results=" + manifest.results().size() + "]"; }
    }
    public static final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    public static final class Uncertain extends RuntimeException {
        private final String code;
        public Uncertain(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
