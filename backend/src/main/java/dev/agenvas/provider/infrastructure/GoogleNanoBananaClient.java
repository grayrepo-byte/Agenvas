package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.shared.http.OutboundTimeouts;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fixed Gemini generateContent protocol for Nano Banana 2; no caller-selected URL or model. */
@Component
public class GoogleNanoBananaClient {
    private static final URI OFFICIAL = URI.create("https://generativelanguage.googleapis.com");
    private static final String DEFAULT_API_PATH = "/v1/";
    private static final String BETA_MODEL_PATH = "/v1beta/models/";
    /** 能力未配置模型名时使用；中转站可通过能力参数覆盖该默认值。 */
    public static final String DEFAULT_MODEL = "gemini-3.1-flash-image";
    public static final int MAX_REFERENCE_BYTES = 10 * 1024 * 1024;
    public static final long MAX_REFERENCE_TOTAL_BYTES = 60L * 1024 * 1024;
    private static final Set<String> REFERENCE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * 单次读取上限；工厂要求显式传入，不能漏成 0（0 表示不限）。
     * 与 OpenAI 图片一样是同步生成，首字节要数十秒到数分钟才到，读超时必须覆盖整次生成，
     * 否则客户端会先超时并把已生成的结果判成 UNKNOWN。与 {@code OpenAiImage2Client} 取
     * 同一个上限，并由 {@code agenvas.task.lease-duration}（默认 30 分钟）保证租约长于它。
     */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public GoogleNanoBananaClient(ObjectMapper mapper) {
        this(mapper, READ_TIMEOUT, CALL_TIMEOUT);
    }

    /**
     * 注入超时的重载，仅供测试构造短于生成耗时上限的客户端，以免验证超时分类要真等数分钟。
     *
     * @param readTimeout 等待响应首字节的上限
     * @param callTimeout 整次生成调用的上限
     */
    GoogleNanoBananaClient(ObjectMapper mapper, Duration readTimeout, Duration callTimeout) {
        this.mapper = mapper;
        // 配置层已把端点限定为 HTTPS 公网或字面 127.0.0.1；这里放行回环，域名解析
        // 落到回环仍被拦，因为 hostname 不是字面 127.0.0.1。
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, true),
                CONNECT_TIMEOUT, readTimeout, callTimeout);
    }

    public MediaPayload generate(String key, String model, String origin, String prompt,
            String aspectRatio, String imageSize, List<InputImage> references) {
        validateReferences(references);
        if (!ImageGenerationParameters.ASPECT_RATIOS.contains(aspectRatio)
                || ImageGenerationParameters.AUTO_ASPECT_RATIO.equals(aspectRatio)
                || !ImageGenerationParameters.RESOLUTIONS.contains(imageSize)) {
            throw new IllegalArgumentException("Google image dimensions are invalid");
        }
        URI endpoint = apiEndpoint(origin, model);
        ObjectNode body = mapper.createObjectNode();
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", prompt);
        for (InputImage reference : references) {
            ObjectNode inline = parts.addObject().putObject("inlineData");
            inline.put("mimeType", reference.mimeType());
            inline.put("data", Base64.getEncoder().encodeToString(reference.bytes()));
        }
        ObjectNode config = body.putObject("generationConfig");
        config.putArray("responseModalities").add("TEXT").add("IMAGE");
        // Beta-compatible relays read imageConfig; stable v1 uses responseFormat.image.
        // Sending the stable field to a beta relay can silently ignore the chosen dimensions.
        ObjectNode image = endpoint.getRawPath().contains(BETA_MODEL_PATH)
                ? config.putObject("imageConfig")
                : config.putObject("responseFormat").putObject("image");
        image.put("aspectRatio", aspectRatio);
        image.put("imageSize", imageSize);
        Request request = new Request.Builder().url(endpoint.toString())
                .header("x-goog-api-key", key)
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8),
                        MediaType.parse("application/json"))).build();
        try (Response response = http.newCall(request).execute()) {
            if (response.body() == null) {
                throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                        "Google response has no body");
            }
            try (InputStream input = response.body().byteStream()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new Uncertain(ProviderFailureCodes.RESPONSE_TOO_LARGE,
                            "Google response exceeds bound");
                }
                int status = response.code();
                if (status >= 400 && status < 500 && status != 429) {
                    throw new Rejected(status);
                }
                if (status != 200) {
                    throw new Uncertain(ProviderFailureCodes.SUBMISSION_UNKNOWN,
                            "Google submission status uncertain");
                }
                return image(mapper.readTree(bytes));
            }
        } catch (IOException failure) {
            // 超时与断线都会让外部是否完成变得不确定，但原因必须能区分。
            if (OutboundTimeouts.isTimeout(failure)) {
                throw new Uncertain(ProviderFailureCodes.CALL_TIMEOUT,
                        "Google image call timed out");
            }
            throw new Uncertain(ProviderFailureCodes.RESPONSE_LOST,
                    "Google image response was lost");
        } catch (RuntimeException failure) {
            if (failure instanceof Rejected || failure instanceof Uncertain) throw failure;
            throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                    "Google image response could not be decoded");
        }
    }

    MediaPayload generate(String key, String model, String origin, String prompt,
            String aspectRatio, List<InputImage> references) {
        return generate(key, model, origin, prompt, aspectRatio, ImageGenerationParameters.DEFAULT_RESOLUTION, references);
    }

    /**
     * A configured API base includes its version/prefix (for example /v1beta).
     * Bare origins keep the stable default. Never probe alternate paths after submission:
     * synchronous generation can incur a charge even when its response is lost.
     */
    static URI apiEndpoint(String origin, String model) {
        URI target = origin == null || origin.isBlank() ? OFFICIAL : URI.create(origin);
        boolean loopback = "http".equals(target.getScheme())
                && "127.0.0.1".equals(target.getHost()) && target.getPort() > 0;
        if (target.getHost() == null || !(loopback || "https".equals(target.getScheme()))
                || target.getRawUserInfo() != null || target.getRawQuery() != null
                || target.getRawFragment() != null) {
            throw new IllegalArgumentException("Pinned Google API base URL is invalid");
        }
        String path = target.getRawPath();
        URI base = path == null || path.isEmpty() || "/".equals(path)
                ? target.resolve(DEFAULT_API_PATH)
                : URI.create(target.toString().replaceAll("/+$", "") + "/");
        return base.resolve("models/" + model + ":generateContent");
    }

    private static void validateReferences(List<InputImage> references) {
        if (references == null
                || references.size() > MediaAdapterRegistry.GOOGLE_MAX_REFERENCE_IMAGES) {
            throw new IllegalArgumentException("Pinned reference image count is invalid");
        }
        long total = 0;
        for (InputImage reference : references) {
            if (reference == null || reference.bytes() == null
                    || reference.bytes().length == 0
                    || reference.bytes().length > MAX_REFERENCE_BYTES
                    || !REFERENCE_MIME_TYPES.contains(reference.mimeType())) {
                throw new IllegalArgumentException("Pinned reference image is invalid");
            }
            total += reference.bytes().length;
            if (total > MAX_REFERENCE_TOTAL_BYTES) {
                throw new IllegalArgumentException("Pinned reference image total size is invalid");
            }
        }
    }

    public record InputImage(byte[] bytes, String mimeType) {}

    private static MediaPayload image(JsonNode response) {
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.size() != 1) {
            throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                    "Google image candidate count is invalid");
        }
        JsonNode parts = candidates.path(0).path("content").path("parts");
        if (!parts.isArray()) {
            throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                    "Google image parts are missing");
        }
        MediaPayload result = null;
        for (JsonNode part : parts) {
            if (part.path("thought").asBoolean(false)) continue;
            JsonNode inline = part.path("inlineData");
            if (inline.isMissingNode()) continue;
            if (result != null || !inline.path("mimeType").isTextual()
                    || !inline.path("data").isTextual()) {
                throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                        "Google returned an ambiguous image result");
            }
            String mime = inline.path("mimeType").asText();
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(inline.path("data").asText());
            } catch (IllegalArgumentException invalid) {
                throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                        "Google image data is not Base64");
            }
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES || !matchesMime(mime, bytes)) {
                throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                        "Google image type or size is invalid");
            }
            result = new MediaPayload(new ByteArrayInputStream(bytes), mime);
        }
        if (result == null) {
            throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                    "Google returned no image");
        }
        return result;
    }

    private static boolean matchesMime(String mime, byte[] bytes) {
        return switch (mime) {
            case "image/png" -> bytes.length >= 8 && bytes[0] == (byte) 0x89
                    && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
            case "image/jpeg" -> bytes.length >= 3 && bytes[0] == (byte) 0xff
                    && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
            case "image/webp" -> bytes.length >= 12 && bytes[0] == 'R'
                    && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B'
                    && bytes[11] == 'P';
            default -> false;
        };
    }

    public static final class Rejected extends RuntimeException {
        public Rejected(int status) { super("Google image request rejected: HTTP " + status); }
    }

    /**
     * 无法确认外部是否受理或完成。必须携带稳定原因码，由适配器原样写入任务，否则用户只能
     * 看到「结果未知」而无法判断是超时、断线还是协议不符。
     */
    public static final class Uncertain extends RuntimeException {
        private final String reasonCode;

        public Uncertain(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() { return reasonCode; }
    }
}
