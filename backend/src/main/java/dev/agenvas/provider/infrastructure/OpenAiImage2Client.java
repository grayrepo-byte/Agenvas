package dev.agenvas.provider.infrastructure;

import dev.agenvas.shared.security.ServiceEndpointPolicy;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.ProviderFailure;
import dev.agenvas.provider.domain.ProviderModeration;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.shared.http.OutboundTimeouts;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
     * 每次失败都写明可核实的稳定原因码，使卡片与调用日志不必依赖服务端日志定位。
     * 普通日志只写固定原因码，不保留上游正文、URL、凭证或输入。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiImage2Client.class);
    /** 能力未配置模型名时使用；中转站可通过能力参数覆盖该默认值。 */
    public static final String DEFAULT_MODEL = "gpt-image-2";
    public static final int MAX_REFERENCE_BYTES = 20 * 1024 * 1024;
    public static final long MAX_REFERENCE_TOTAL_BYTES = 60L * 1024 * 1024;
    public static final int MAX_MASK_BYTES = 4 * 1024 * 1024;
    private static final URI OFFICIAL_BASE = URI.create("https://api.openai.com/v1/");
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = MAX_REFERENCE_BYTES;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * 等待响应首字节的上限。同步图片生成要数十秒到数分钟才吐第一个字节，读超时必须覆盖整次
     * 生成；短于生成耗时会让客户端先超时，把已经生成好的结果判成 UNKNOWN 并丢弃。
     *
     * <p>该上限已被真实链路撞破两次：先是 10 秒（生成要 30-40 秒，见 T21 证据），
     * 改为 3 分钟后仍有中转站实测 216 秒才返回。故取 5 分钟，并由
     * {@code agenvas.task.lease-duration}（默认 30 分钟）保证租约长于它。
     */
    private static final Duration GENERATION_READ_TIMEOUT = Duration.ofMinutes(5);
    /** 整次生成调用的上限；与读超时同量级，由它兜住总时长。 */
    private static final Duration GENERATION_CALL_TIMEOUT = Duration.ofMinutes(5);
    /** 结果图下载的尝试次数；只重试传输失败，不重发已计费的生成请求。 */
    private static final int DOWNLOAD_ATTEMPTS = 3;
    /** 两次下载尝试之间的固定间隔，避免瞬时抖动直接落到待人工核对。 */
    private static final Duration DOWNLOAD_RETRY_DELAY = Duration.ofMillis(300);
    /** 下载已有结果图的上限；与生成分开配置，因为下载不需要等模型推理。 */
    private static final Duration DOWNLOAD_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_READ_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration DOWNLOAD_CALL_TIMEOUT = Duration.ofMinutes(2);
    private final OkHttpClient http;
    /** 结果图托管在中转站自有 CDN，与 API Base URL 不同域，因此单独一个只做 GET 的客户端。 */
    private final OkHttpClient downloads;
    private final ObjectMapper mapper;

    @Autowired
    public OpenAiImage2Client(ObjectMapper mapper) {
        this(mapper, GENERATION_READ_TIMEOUT, GENERATION_CALL_TIMEOUT);
    }

    /**
     * 注入超时的重载，仅供测试构造短于生成耗时上限的客户端，以免验证超时分类要真等数分钟。
     *
     * @param readTimeout 等待响应首字节的上限
     * @param callTimeout 整次生成调用的上限
     */
    OpenAiImage2Client(ObjectMapper mapper, Duration readTimeout, Duration callTimeout) {
        this.mapper = mapper;
        // Each selected endpoint installs a scheme-aware pinned DNS policy before sending credentials.
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, true),
                CONNECT_TIMEOUT, readTimeout, callTimeout);
        // Result redirects are followed manually after destination and DNS validation, without credentials.
        this.downloads = PinnedHttpClients.pinned(Dns.SYSTEM,
                DOWNLOAD_CONNECT_TIMEOUT, DOWNLOAD_READ_TIMEOUT, DOWNLOAD_CALL_TIMEOUT);
    }

    private OkHttpClient serviceClient(Request request) throws IOException {
        return http.newBuilder().dns(ServiceEndpointPolicy.dns(
                request.url().uri(), Dns.SYSTEM, true)).build();
    }

    public MediaPayload generate(String key, String model, String prompt, String quality,
            String size, boolean transparentBackground, String baseUrl) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("quality", quality);
        body.put("size", size);
        body.put("background", transparentBackground ? "transparent" : "opaque");
        body.put("n", 1);
        body.put("output_format", "png");
        return send(key, baseUrl, "images/generations",
                RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8),
                        MediaType.parse("application/json")));
    }

    MediaPayload generate(String key, String model, String prompt, String quality,
            String size, String baseUrl) {
        return generate(key, model, prompt, quality, size, false, baseUrl);
    }

    public MediaPayload edit(String key, String model, String prompt, String quality, String size,
            List<byte[]> referencePngs, byte[] maskPng, boolean transparentBackground,
            String baseUrl) {
        validateReferences(referencePngs);
        validateMask(maskPng);
        MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("quality", quality)
                .addFormDataPart("size", size)
                .addFormDataPart("background", transparentBackground ? "transparent" : "opaque")
                .addFormDataPart("n", "1")
                .addFormDataPart("output_format", "png");
        for (int index = 0; index < referencePngs.size(); index++) {
            body.addFormDataPart("image[]", "reference-" + (index + 1) + ".png",
                    RequestBody.create(referencePngs.get(index), MediaType.parse("image/png")));
        }
        if (maskPng != null) {
            body.addFormDataPart("mask", "edit-mask.png",
                    RequestBody.create(maskPng, MediaType.parse("image/png")));
        }
        return send(key, baseUrl, "images/edits", body.build());
    }

    public MediaPayload edit(String key, String model, String prompt, String quality, String size,
            List<byte[]> referencePngs, boolean transparentBackground, String baseUrl) {
        return edit(key, model, prompt, quality, size, referencePngs, null,
                transparentBackground, baseUrl);
    }

    MediaPayload edit(String key, String model, String prompt, String quality, String size,
            List<byte[]> referencePngs, String baseUrl) {
        return edit(key, model, prompt, quality, size, referencePngs, false, baseUrl);
    }

    /** URL input is explicit; a rejected/uncertain request must never be retried as a file submission. */
    public MediaPayload editUrls(String key, String model, String prompt, String quality, String size,
            List<String> images, String mask, boolean transparentBackground, String baseUrl) {
        if (images == null || images.isEmpty() || images.size() > MediaAdapterRegistry.OPENAI_MAX_REFERENCE_IMAGES)
            throw new IllegalArgumentException("Pinned reference URL count is invalid");
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model).put("prompt", prompt).put("quality", quality).put("size", size)
                .put("background", transparentBackground ? "transparent" : "opaque")
                .put("n", 1).put("output_format", "png");
        var references = body.putArray("images");
        for (String image : images) references.addObject().put("image_url", requireImageUrl(image));
        if (mask != null) body.putObject("mask").put("image_url", requireImageUrl(mask));
        return send(key, baseUrl, "images/edits", RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8),
                MediaType.parse("application/json")));
    }

    private static String requireImageUrl(String value) {
        URI uri = URI.create(value);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Pinned reference URL must use HTTP or HTTPS");
        return value;
    }

    private static void validateReferences(List<byte[]> references) {
        if (references == null || references.isEmpty()
                || references.size() > MediaAdapterRegistry.OPENAI_MAX_REFERENCE_IMAGES) {
            throw new IllegalArgumentException("Pinned reference PNG count is invalid");
        }
        long total = 0;
        for (byte[] reference : references) {
            if (reference == null || reference.length == 0
                    || reference.length > MAX_REFERENCE_BYTES) {
                throw new IllegalArgumentException("Pinned reference PNG size is invalid");
            }
            total += reference.length;
            if (total > MAX_REFERENCE_TOTAL_BYTES) {
                throw new IllegalArgumentException("Pinned reference PNG total size is invalid");
            }
        }
    }

    private static void validateMask(byte[] mask) {
        if (mask == null) return;
        if (mask.length < 8 || mask.length > MAX_MASK_BYTES
                || mask[0] != (byte) 0x89 || mask[1] != 'P'
                || mask[2] != 'N' || mask[3] != 'G') {
            throw new IllegalArgumentException("OpenAI edit mask must be a bounded PNG");
        }
    }

    private MediaPayload send(String key, String baseUrl, String path, RequestBody body) {
        URI endpoint = apiEndpoint(baseUrl, path);
        Request request = new Request.Builder().url(endpoint.toString())
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .post(body).build();
        try (Response response = serviceClient(request).newCall(request).execute()) {
            int status = response.code();
            if (status >= 400 && status < 500 && status != 429) {
                throw rejection(response);
            }
            if (response.body() == null) {
                throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                        "OpenAI response has no body");
            }
            try (InputStream input = response.body().byteStream()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new Uncertain(ProviderFailureCodes.RESPONSE_TOO_LARGE,
                            "OpenAI response exceeds bound");
                }
                if (status != 200) {
                    throw new Uncertain(ProviderFailureCodes.SUBMISSION_UNKNOWN,
                            "OpenAI submission status uncertain");
                }
                JsonNode responseBody = mapper.readTree(bytes);
                JsonNode metadata = dev.agenvas.provider.domain.OpenAiImageUsage.metadata(mapper, responseBody);
                JsonNode data = responseBody.path("data");
                if (!data.isArray() || data.size() != 1) {
                    LOGGER.warn("OpenAI image result shape is invalid status={} code={}", status,
                            ProviderFailureCodes.PROTOCOL_INVALID);
                    throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                            "OpenAI image result shape is invalid");
                }
                JsonNode item = data.path(0);
                if (item.path("b64_json").isTextual()) {
                    byte[] image = decodeInline(item.path("b64_json").asText());
                    requirePng(image);
                    return new MediaPayload(new ByteArrayInputStream(image), "image/png", metadata);
                }
                if (item.path("url").isTextual()) {
                    MediaPayload downloaded = download(endpoint, item.path("url").asText());
                    return new MediaPayload(downloaded.stream(), downloaded.declaredContentType(), metadata);
                }
                LOGGER.warn("OpenAI image result payload missing status={} code={}", status,
                        ProviderFailureCodes.PROTOCOL_INVALID);
                throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                        "OpenAI image result shape is invalid");
            }
        } catch (IOException failure) {
            // 超时与断线都是「不知道外部有没有完成」，但原因必须能区分：前者是等满上限，
            // 后者是连接被切断。异常对象不写进日志，避免把底层地址与响应细节带出去。
            if (OutboundTimeouts.isTimeout(failure)) {
                LOGGER.warn("OpenAI image call exceeded its local timeout code={}",
                        ProviderFailureCodes.CALL_TIMEOUT);
                throw new Uncertain(ProviderFailureCodes.CALL_TIMEOUT,
                        "OpenAI image call timed out");
            }
            LOGGER.warn("OpenAI image response was lost code={}",
                    ProviderFailureCodes.RESPONSE_LOST);
            throw new Uncertain(ProviderFailureCodes.RESPONSE_LOST,
                    "OpenAI image response was lost");
        } catch (RuntimeException failure) {
            if (failure instanceof Rejected || failure instanceof Uncertain) {
                LOGGER.warn("OpenAI image call did not complete code={}",
                        failure instanceof Uncertain uncertain
                                ? uncertain.reasonCode() : ((Rejected) failure).code());
                throw failure;
            }
            LOGGER.warn("OpenAI image response could not be decoded code={}",
                    ProviderFailureCodes.PROTOCOL_INVALID);
            throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                    "OpenAI image response could not be decoded");
        }
    }

    /** A definitive 4xx stays rejected even when its optional diagnostic body cannot be read. */
    private Rejected rejection(Response response) {
        int status = response.code();
        if (response.body() == null) return new Rejected(status);
        try (InputStream input = response.body().byteStream()) {
            byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) return new Rejected(status);
            JsonNode error = mapper.readTree(bytes).path("error");
            if (!error.isObject() || !error.path("code").isTextual()) return new Rejected(status);
            String code = error.path("code").asText();
            if (!ProviderModeration.supportsCode(code)) return new Rejected(status);
            JsonNode details = error.path("moderation_details");
            String stage = details.path("moderation_stage").isTextual()
                    ? details.path("moderation_stage").asText() : null;
            List<String> categories = new ArrayList<>();
            if (details.path("categories").isArray()) {
                for (JsonNode category : details.path("categories")) {
                    categories.add(category.isTextual() ? category.asText() : null);
                }
            }
            return new Rejected(status, new ProviderFailure(ProviderFailure.Stage.SUBMIT,
                    ProviderFailure.Category.MODERATION, status, null, null, null,
                    new ProviderModeration(code, stage, categories)));
        } catch (IOException | RuntimeException invalidBody) {
            // Body parsing is optional: never retain its exception, message or untrusted content.
            return new Rejected(status);
        }
    }

    private static byte[] decodeInline(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                    "OpenAI image result is not Base64");
        }
    }

    /** Result URLs are untrusted; only the selected service or public HTTPS can supply bytes. */
    private MediaPayload download(URI selected, String rawUrl) {
        URI url;
        try {
            url = URI.create(rawUrl);
        } catch (IllegalArgumentException malformed) {
            LOGGER.warn("OpenAI image result URL is malformed code={}",
                    ProviderFailureCodes.RESULT_URL_INVALID);
            throw new Uncertain(ProviderFailureCodes.RESULT_URL_INVALID,
                    "OpenAI image result URL is malformed");
        }
        if (url.getHost() == null || !("https".equalsIgnoreCase(url.getScheme())
                || "http".equalsIgnoreCase(url.getScheme()))) {
            LOGGER.warn("OpenAI image result URL is unsupported code={}",
                    ProviderFailureCodes.RESULT_URL_INVALID);
            throw new Uncertain(ProviderFailureCodes.RESULT_URL_INVALID,
                    "OpenAI image result URL is unsupported");
        }
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                return fetch(selected, url);
            } catch (IOException transientFailure) {
                LOGGER.warn("OpenAI image download attempt {}/{} failed code={}",
                        attempt, DOWNLOAD_ATTEMPTS, ProviderFailureCodes.DOWNLOAD_FAILED);
                if (attempt < DOWNLOAD_ATTEMPTS) pause();
            }
        }
        throw new Uncertain(ProviderFailureCodes.DOWNLOAD_FAILED,
                "OpenAI image result could not be downloaded");
    }

    /** Follow at most three result redirects, validating each destination before a socket is opened. */
    private MediaPayload fetch(URI selected, URI url) throws IOException {
        for (int redirects = 0; redirects <= 3; redirects++) {
            okhttp3.Dns checked = ServiceEndpointPolicy.downloadDns(
                    selected, url, Dns.SYSTEM, true);
            Request request = new Request.Builder().url(url.toString()).get().build();
            try (Response response = downloads.newBuilder().dns(checked).build().newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    if (redirects == 3 || location == null || location.length() > 8192)
                        throw new Uncertain(ProviderFailureCodes.RESULT_URL_INVALID, "Unsafe image result redirect");
                    try {
                        okhttp3.HttpUrl resolved = request.url().resolve(location);
                        if (resolved == null) throw new IllegalArgumentException();
                        ServiceEndpointPolicy.requireNoDowngrade(url, resolved.uri());
                        url = resolved.uri();
                    }
                    catch (IllegalArgumentException malformed) {
                        throw new Uncertain(ProviderFailureCodes.RESULT_URL_INVALID, "Unsafe image result redirect");
                    }
                    continue;
                }
                if (response.code() != 200 || response.body() == null)
                    throw new Uncertain(ProviderFailureCodes.DOWNLOAD_FAILED, "OpenAI image result URL is unavailable");
                try (InputStream input = response.body().byteStream()) {
                    byte[] image = input.readNBytes(MAX_IMAGE_BYTES + 1);
                    if (image.length > MAX_IMAGE_BYTES)
                        throw new Uncertain(ProviderFailureCodes.RESULT_TOO_LARGE, "OpenAI image result exceeds bound");
                    return new MediaPayload(new ByteArrayInputStream(image),
                            response.header("Content-Type", "application/octet-stream"));
                }
            } catch (java.net.UnknownHostException blocked) {
                throw new Uncertain(ProviderFailureCodes.RESULT_URL_INVALID, "Image result address is unavailable or blocked");
            }
        }
        throw new Uncertain(ProviderFailureCodes.RESULT_URL_INVALID, "Unsafe image result redirect");
    }

    private static void requirePng(byte[] image) {
        if (image.length == 0 || image.length > MAX_IMAGE_BYTES
                || image.length < 8 || image[0] != (byte) 0x89
                || image[1] != 'P' || image[2] != 'N' || image[3] != 'G') {
            throw new Uncertain(ProviderFailureCodes.PROTOCOL_INVALID,
                    "OpenAI image result is not a bounded PNG");
        }
    }

    private static void pause() {
        try {
            Thread.sleep(DOWNLOAD_RETRY_DELAY.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Uncertain(ProviderFailureCodes.DOWNLOAD_FAILED,
                    "OpenAI image result download was interrupted");
        }
    }

    static URI apiEndpoint(String baseUrl, String path) {
        URI base = baseUrl == null ? OFFICIAL_BASE
                : URI.create(baseUrl + (baseUrl.endsWith("/") ? "" : "/"));
        ServiceEndpointPolicy.validate(base, true);
        return base.resolve(path);
    }

    public static final class Rejected extends RuntimeException {
        private final ProviderFailure providerFailure;

        public Rejected(int status) { this(status, null); }

        public Rejected(int status, ProviderFailure providerFailure) {
            super("OpenAI image request rejected: HTTP " + status);
            this.providerFailure = providerFailure;
        }

        public ProviderFailure providerFailure() { return providerFailure; }

        public String code() {
            return providerFailure == null ? ProviderFailureCodes.OPENAI_IMAGE_REJECTED : providerFailure.code();
        }
    }

    /**
     * 无法确认外部是否受理或完成。必须携带稳定原因码，由适配器原样写入任务，否则用户只能
     * 看到「结果未知」而无法判断是超时、断线、协议不符还是结果下载失败。
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
