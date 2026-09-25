package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.MediaPayload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
    private static final String MODEL = "gemini-3.1-flash-image";
    private static final String PATH = "/v1/models/" + MODEL + ":generateContent";
    private static final String IMAGE_SIZE = "1K";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(3);
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private final URI origin;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public GoogleNanoBananaClient(ObjectMapper mapper) {
        this(mapper, OFFICIAL);
    }

    /** Loopback is available only to an explicitly constructed test client. */
    public GoogleNanoBananaClient(ObjectMapper mapper, URI origin) {
        if (!OFFICIAL.equals(origin) && !("http".equals(origin.getScheme())
                && "127.0.0.1".equals(origin.getHost()) && origin.getPort() > 0
                && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                && origin.getRawUserInfo() == null && origin.getRawQuery() == null
                && origin.getRawFragment() == null)) {
            throw new IllegalArgumentException("Unsupported fixed Google API origin");
        }
        this.mapper = mapper;
        this.origin = origin;
        this.http = new OkHttpClient.Builder().connectTimeout(CONNECT_TIMEOUT)
                .callTimeout(CALL_TIMEOUT).proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .dns(FixedCloudDns.checked(Dns.SYSTEM, !OFFICIAL.equals(origin)))
                .build();
    }

    public MediaPayload generate(String key, String prompt, String aspectRatio,
            byte[] reference, String referenceMimeType) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", prompt);
        if (reference != null) {
            ObjectNode inline = parts.addObject().putObject("inlineData");
            inline.put("mimeType", referenceMimeType);
            inline.put("data", Base64.getEncoder().encodeToString(reference));
        }
        ObjectNode config = body.putObject("generationConfig");
        config.putArray("responseModalities").add("TEXT").add("IMAGE");
        ObjectNode image = config.putObject("responseFormat").putObject("image");
        image.put("aspectRatio", aspectRatio);
        image.put("imageSize", IMAGE_SIZE);
        Request request = new Request.Builder().url(origin.resolve(PATH).toString())
                .header("x-goog-api-key", key)
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8),
                        MediaType.parse("application/json"))).build();
        try (Response response = http.newCall(request).execute()) {
            if (response.body() == null) throw new Uncertain("Google response has no body");
            try (InputStream input = response.body().byteStream()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new Uncertain("Google response exceeds bound");
                }
                int status = response.code();
                if (status >= 400 && status < 500 && status != 429) {
                    throw new Rejected(status);
                }
                if (status != 200) throw new Uncertain("Google submission status uncertain");
                return image(mapper.readTree(bytes));
            }
        } catch (IOException failure) {
            throw new Uncertain("Google image response was lost");
        } catch (RuntimeException failure) {
            if (failure instanceof Rejected || failure instanceof Uncertain) throw failure;
            throw new Uncertain("Google image response could not be decoded");
        }
    }

    private static MediaPayload image(JsonNode response) {
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.size() != 1) {
            throw new Uncertain("Google image candidate count is invalid");
        }
        JsonNode parts = candidates.path(0).path("content").path("parts");
        if (!parts.isArray()) throw new Uncertain("Google image parts are missing");
        MediaPayload result = null;
        for (JsonNode part : parts) {
            if (part.path("thought").asBoolean(false)) continue;
            JsonNode inline = part.path("inlineData");
            if (inline.isMissingNode()) continue;
            if (result != null || !inline.path("mimeType").isTextual()
                    || !inline.path("data").isTextual()) {
                throw new Uncertain("Google returned an ambiguous image result");
            }
            String mime = inline.path("mimeType").asText();
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(inline.path("data").asText());
            } catch (IllegalArgumentException invalid) {
                throw new Uncertain("Google image data is not Base64");
            }
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES || !matchesMime(mime, bytes)) {
                throw new Uncertain("Google image type or size is invalid");
            }
            result = new MediaPayload(new ByteArrayInputStream(bytes), mime);
        }
        if (result == null) throw new Uncertain("Google returned no image");
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

    public static final class Uncertain extends RuntimeException {
        public Uncertain(String message) { super(message); }
    }
}
