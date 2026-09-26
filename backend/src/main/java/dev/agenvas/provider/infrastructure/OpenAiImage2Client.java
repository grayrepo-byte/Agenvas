package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.MediaPayload;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
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

/** Fixed GPT Image 2 Images API protocol with a version-pinned API base URL. */
@Component
public class OpenAiImage2Client {
    /** 能力未配置模型名时使用；中转站可通过能力参数覆盖该默认值。 */
    public static final String DEFAULT_MODEL = "gpt-image-2";
    private static final URI OFFICIAL_BASE = URI.create("https://api.openai.com/v1/");
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public OpenAiImage2Client(ObjectMapper mapper) {
        this.mapper = mapper;
        // 配置层已把端点限定为 HTTPS 公网或字面 127.0.0.1；这里放行回环，域名解析
        // 落到回环仍被拦，因为 hostname 不是字面 127.0.0.1。
        this.http = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofMinutes(3)).proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .dns(FixedCloudDns.checked(Dns.SYSTEM, true))
                .build();
    }

    public MediaPayload generate(String key, String model, String prompt, String quality,
            String size, String baseUrl) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("quality", quality);
        body.put("size", size);
        body.put("n", 1);
        body.put("output_format", "png");
        return send(key, baseUrl, "images/generations", "application/json",
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    public MediaPayload edit(String key, String model, String prompt, String quality, String size,
            byte[] referencePng, String baseUrl) {
        if (referencePng == null || referencePng.length == 0
                || referencePng.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Pinned reference PNG size is invalid");
        }
        String boundary = "agenvas-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "model", model);
        field(body, boundary, "prompt", prompt);
        field(body, boundary, "quality", quality);
        field(body, boundary, "size", size);
        field(body, boundary, "n", "1");
        field(body, boundary, "output_format", "png");
        bytes(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; "
                + "filename=\"reference.png\"\r\nContent-Type: image/png\r\n\r\n");
        body.writeBytes(referencePng);
        bytes(body, "\r\n--" + boundary + "--\r\n");
        return send(key, baseUrl, "images/edits", "multipart/form-data; boundary=" + boundary,
                body.toByteArray());
    }

    private MediaPayload send(String key, String baseUrl, String path, String contentType,
            byte[] body) {
        URI endpoint = apiEndpoint(baseUrl, path);
        Request request = new Request.Builder().url(endpoint.toString())
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .post(RequestBody.create(body, MediaType.parse(contentType))).build();
        try (Response response = http.newCall(request).execute()) {
            if (response.body() == null) throw new Uncertain("OpenAI response has no body");
            try (InputStream input = response.body().byteStream()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new Uncertain("OpenAI response exceeds bound");
                }
                int status = response.code();
                if (status >= 400 && status < 500 && status != 429) {
                    throw new Rejected(status);
                }
                if (status != 200) throw new Uncertain("OpenAI submission status uncertain");
                JsonNode data = mapper.readTree(bytes).path("data");
                if (!data.isArray() || data.size() != 1
                        || !data.path(0).path("b64_json").isTextual()) {
                    throw new Uncertain("OpenAI image result shape is invalid");
                }
                byte[] image;
                try {
                    image = Base64.getDecoder().decode(data.path(0).path("b64_json").asText());
                } catch (IllegalArgumentException invalid) {
                    throw new Uncertain("OpenAI image result is not Base64");
                }
                if (image.length == 0 || image.length > MAX_IMAGE_BYTES
                        || image.length < 8 || image[0] != (byte) 0x89
                        || image[1] != 'P' || image[2] != 'N' || image[3] != 'G') {
                    throw new Uncertain("OpenAI image result is not a bounded PNG");
                }
                return new MediaPayload(new ByteArrayInputStream(image), "image/png");
            }
        } catch (IOException failure) {
            throw new Uncertain("OpenAI image response was lost");
        } catch (RuntimeException failure) {
            if (failure instanceof Rejected || failure instanceof Uncertain) throw failure;
            throw new Uncertain("OpenAI image response could not be decoded");
        }
    }

    static URI apiEndpoint(String baseUrl, String path) {
        URI base = baseUrl == null ? OFFICIAL_BASE
                : URI.create(baseUrl + (baseUrl.endsWith("/") ? "" : "/"));
        // 与配置层一致：HTTPS 公网，或字面 127.0.0.1 的本机服务。
        boolean loopback = "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost());
        if (base.getHost() == null || !(loopback || "https".equals(base.getScheme()))
                || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null) {
            throw new IllegalArgumentException("Pinned OpenAI API base URL is invalid");
        }
        return base.resolve(path);
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name,
            String value) {
        bytes(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void bytes(ByteArrayOutputStream body, String value) {
        body.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public static final class Rejected extends RuntimeException {
        public Rejected(int status) { super("OpenAI image request rejected: HTTP " + status); }
    }

    public static final class Uncertain extends RuntimeException {
        public Uncertain(String message) { super(message); }
    }
}
