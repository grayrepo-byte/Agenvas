package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixed GPT Image 2 Images API protocol with a version-pinned API base URL. */
@Component
public class OpenAiImage2Client {
    /**
     * 解析失败只按固定文案进入 UNKNOWN，用户与运维看不到原因，所以这里把可核实的
     * 形状与地址写入日志。响应体只截断预览，凭证不在此路径中。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiImage2Client.class);
    /** 诊断预览的最大字符数，避免把整张 base64 写进日志。 */
    private static final int PREVIEW_CHARS = 200;
    /** 能力未配置模型名时使用；中转站可通过能力参数覆盖该默认值。 */
    public static final String DEFAULT_MODEL = "gpt-image-2";
    private static final URI OFFICIAL_BASE = URI.create("https://api.openai.com/v1/");
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * 等待响应首字节的上限。同步图片生成要 30-40 秒才吐第一个字节，读超时必须覆盖整次
     * 生成；短于生成耗时会让客户端先超时，把已经生成好的结果判成 UNKNOWN 并丢弃。
     */
    private static final Duration GENERATION_READ_TIMEOUT = Duration.ofMinutes(3);
    /** 整次生成调用的上限；与读超时同量级，由它兜住总时长。 */
    private static final Duration GENERATION_CALL_TIMEOUT = Duration.ofMinutes(3);
    /** 结果图下载的尝试次数；只重试传输失败，不重发已计费的生成请求。 */
    private static final int DOWNLOAD_ATTEMPTS = 3;
    /** 两次下载尝试之间的固定间隔，避免瞬时抖动直接落到待人工核对。 */
    private static final Duration DOWNLOAD_RETRY_DELAY = Duration.ofMillis(300);
    private final OkHttpClient http;
    /** 结果图托管在中转站自有 CDN，与 API Base URL 不同域，因此单独一个只做 GET 的客户端。 */
    private final OkHttpClient downloads;
    private final ObjectMapper mapper;

    @Autowired
    public OpenAiImage2Client(ObjectMapper mapper) {
        this.mapper = mapper;
        // 配置层已把端点限定为 HTTPS 公网或字面 127.0.0.1；这里放行回环，域名解析
        // 落到回环仍被拦，因为 hostname 不是字面 127.0.0.1。
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, true),
                CONNECT_TIMEOUT, GENERATION_READ_TIMEOUT, GENERATION_CALL_TIMEOUT);
        // 下载已有结果用允许重定向的客户端：中转站与 CDN 常把结果 302/307 跳到实际
        // 对象存储，禁掉重定向会让正常结果失败。该请求是幂等 GET 且不携带凭证。
        this.downloads = PinnedHttpClients.pinnedFollowingRedirects(Dns.SYSTEM,
                Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(2));
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
        return send(key, baseUrl, "images/generations",
                RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8),
                        MediaType.parse("application/json")));
    }

    public MediaPayload edit(String key, String model, String prompt, String quality, String size,
            byte[] referencePng, String baseUrl) {
        if (referencePng == null || referencePng.length == 0
                || referencePng.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Pinned reference PNG size is invalid");
        }
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("quality", quality)
                .addFormDataPart("size", size)
                .addFormDataPart("n", "1")
                .addFormDataPart("output_format", "png")
                .addFormDataPart("image", "reference.png",
                        RequestBody.create(referencePng, MediaType.parse("image/png")))
                .build();
        return send(key, baseUrl, "images/edits", body);
    }

    private MediaPayload send(String key, String baseUrl, String path, RequestBody body) {
        URI endpoint = apiEndpoint(baseUrl, path);
        Request request = new Request.Builder().url(endpoint.toString())
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .post(body).build();
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
                if (!data.isArray() || data.size() != 1) {
                    LOGGER.warn("OpenAI image result shape is invalid (status={}, preview={})",
                            status, preview(bytes));
                    throw new Uncertain("OpenAI image result shape is invalid");
                }
                JsonNode item = data.path(0);
                if (item.path("b64_json").isTextual()) {
                    byte[] image = decodeInline(item.path("b64_json").asText());
                    requirePng(image);
                    return new MediaPayload(new ByteArrayInputStream(image), "image/png");
                }
                if (item.path("url").isTextual()) {
                    return download(item.path("url").asText());
                }
                LOGGER.warn("OpenAI image item has neither b64_json nor url (status={}, "
                        + "preview={})", status, preview(bytes));
                throw new Uncertain("OpenAI image result shape is invalid");
            }
        } catch (IOException failure) {
            LOGGER.warn("OpenAI image response was lost", failure);
            throw new Uncertain("OpenAI image response was lost");
        } catch (RuntimeException failure) {
            if (failure instanceof Rejected || failure instanceof Uncertain) {
                LOGGER.warn("OpenAI image call did not complete: {}", failure.getMessage());
                throw failure;
            }
            LOGGER.warn("OpenAI image response could not be decoded", failure);
            throw new Uncertain("OpenAI image response could not be decoded");
        }
    }

    /** 只截断预览响应的开头，用于判断形状；不记录完整 body。 */
    private static String preview(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() <= PREVIEW_CHARS ? text
                : text.substring(0, PREVIEW_CHARS) + "...";
    }

    private static byte[] decodeInline(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new Uncertain("OpenAI image result is not Base64");
        }
    }

    /**
     * 中转站把结果图托管到自有 CDN 后只回一个 URL（官方 API 回的是 b64_json），该地址与
     * API Base URL 不同域，也可能再重定向到实际对象存储。这里只按 http/https 取回字节，
     * 不预设中转站用什么域名、跳几次、给什么格式——格式由归档层解码判定。下载不携带
     * API Key，避免把凭证交给结果托管方。
     */
    private MediaPayload download(String rawUrl) {
        URI url;
        try {
            url = URI.create(rawUrl);
        } catch (IllegalArgumentException malformed) {
            LOGGER.warn("OpenAI image result URL is malformed: {}",
                    preview(rawUrl.getBytes(StandardCharsets.UTF_8)));
            throw new Uncertain("OpenAI image result URL is malformed");
        }
        if (url.getHost() == null || !("https".equalsIgnoreCase(url.getScheme())
                || "http".equalsIgnoreCase(url.getScheme()))) {
            LOGGER.warn("OpenAI image result URL is unsupported: scheme={} host={}",
                    url.getScheme(), url.getHost());
            throw new Uncertain("OpenAI image result URL is unsupported");
        }
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                return fetch(url);
            } catch (IOException transientFailure) {
                LOGGER.warn("OpenAI image download attempt {}/{} failed for host {}: {}",
                        attempt, DOWNLOAD_ATTEMPTS, url.getHost(),
                        transientFailure.getClass().getSimpleName());
                if (attempt < DOWNLOAD_ATTEMPTS) pause();
            }
        }
        throw new Uncertain("OpenAI image result could not be downloaded from "
                + url.getHost());
    }

    /** 单次下载；已生成的结果没有重发语义，所以非 200 直接判失败而不重试。 */
    private MediaPayload fetch(URI url) throws IOException {
        Request request = new Request.Builder().url(url.toString()).get().build();
        try (Response response = downloads.newCall(request).execute()) {
            if (response.code() != 200 || response.body() == null) {
                LOGGER.warn("OpenAI image result URL returned HTTP {} for host {}",
                        response.code(), url.getHost());
                throw new Uncertain("OpenAI image result URL is unavailable");
            }
            try (InputStream input = response.body().byteStream()) {
                byte[] image = input.readNBytes(MAX_IMAGE_BYTES + 1);
                if (image.length > MAX_IMAGE_BYTES) {
                    LOGGER.warn("OpenAI image result from host {} exceeds {} bytes",
                            url.getHost(), MAX_IMAGE_BYTES);
                    throw new Uncertain("OpenAI image result exceeds bound");
                }
                return new MediaPayload(new ByteArrayInputStream(image),
                        response.header("Content-Type", "application/octet-stream"));
            }
        }
    }

    private static void requirePng(byte[] image) {
        if (image.length == 0 || image.length > MAX_IMAGE_BYTES
                || image.length < 8 || image[0] != (byte) 0x89
                || image[1] != 'P' || image[2] != 'N' || image[3] != 'G') {
            throw new Uncertain("OpenAI image result is not a bounded PNG");
        }
    }

    private static void pause() {
        try {
            Thread.sleep(DOWNLOAD_RETRY_DELAY.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Uncertain("OpenAI image result download was interrupted");
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

    public static final class Rejected extends RuntimeException {
        public Rejected(int status) { super("OpenAI image request rejected: HTTP " + status); }
    }

    public static final class Uncertain extends RuntimeException {
        public Uncertain(String message) { super(message); }
    }
}
