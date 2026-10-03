package dev.agenvas.provider.infrastructure;

import dev.agenvas.shared.http.DebugHttpCapture;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 访问管理员固定的单个 ComfyUI 基地址及代理前缀；所有请求、文件下载和错误响应均受边界约束。 */
public class ComfyUiClient {

    /** ComfyUI JSON 请求与响应的最大字节数。 */
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    /** 上传参考图的最大字节数。 */
    private static final int MAX_IMAGE_BYTES = 30 * 1024 * 1024;
    /** 单个生成输出允许读取的最大字节数。 */
    private static final int MAX_OUTPUT_BYTES = 500 * 1024 * 1024;
    /** ComfyUI 文件名白名单；禁止路径、目录和控制字符。 */
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
    /** 启动时校验并固定的 HTTP(S) origin，请求不得更换主机或端口。 */
    private final URI origin;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    /** 禁止代理、重定向和隐式重试的客户端。 */
    private final OkHttpClient client;
    /** 解析有字节上限的 JSON 协议响应。 */
    private final ObjectMapper mapper;

    /** 校验基地址后创建直连客户端，保留代理路径，所有请求限时 20 秒。 */
    public ComfyUiClient(ComfyUiProperties properties, ObjectMapper mapper) {
        this.origin = checkedOrigin(properties.endpoint());
        this.mapper = mapper;
        this.client = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, true),
                CONNECT_TIMEOUT, REQUEST_TIMEOUT, REQUEST_TIMEOUT);
    }

    /** 对管理员固定的规范化 origin 计算摘要，供轮询校验；不接收浏览器 URL。 */
    public String originSha256() {
        try {
            byte[] bytes = origin.toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /** 只提交一次，并将已落库 requestKey 同时用作 client_id 和 prompt_id。 */
    public UUID submit(JsonNode fixedWorkflow, UUID requestKey) {
        if (fixedWorkflow == null || !fixedWorkflow.isObject() || requestKey == null) {
            throw new IllegalArgumentException("Fixed workflow and request key are required");
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("prompt", fixedWorkflow.deepCopy());
        body.put("client_id", requestKey.toString());
        body.put("prompt_id", requestKey.toString());
        JsonNode response = json("POST", "/prompt", body.toString());
        try {
            UUID acknowledged = UUID.fromString(response.path("prompt_id").asText());
            if (!requestKey.equals(acknowledged)) {
                // 外部任务可能已经运行；ID 不符属于结果不确定，不能当作安全拒绝再提交。
                throw new ProtocolFailure("ComfyUI acknowledged a different prompt_id");
            }
            return acknowledged;
        } catch (IllegalArgumentException failure) {
            throw new ProtocolFailure("ComfyUI did not return a valid prompt_id", failure);
        }
    }

    /** 读取原 prompt 历史；空历史表示暂无证据，不代表拒绝或允许重提。 */
    public JsonNode history(UUID promptId) {
        if (promptId == null) throw new IllegalArgumentException("promptId is required");
        return json("GET", "/history/" + promptId, null);
    }

    /** 查询已保存 prompt ID，并只解析固定模板声明的图片输出节点。 */
    public ComfyUiHistory.ImageResult imageStatus(UUID promptId, String outputNodeId) {
        return ComfyUiHistory.image(history(promptId), promptId, outputNodeId);
    }

    /** 视频查询也只使用原 prompt ID，并要求固定模板中的动画 MP4 输出节点。 */
    public ComfyUiHistory.VideoResult videoStatus(UUID promptId, String outputNodeId) {
        return ComfyUiHistory.video(history(promptId), promptId, outputNodeId);
    }

    /** 仅上传经验证且有界的 PNG/JPEG 字节，文件名由服务端生成并校验返回路径类别。 */
    public String uploadImage(UUID requestId, byte[] verifiedImage, String extension) {
        if (requestId == null || verifiedImage == null || verifiedImage.length == 0
                || verifiedImage.length > MAX_IMAGE_BYTES
                || !("png".equals(extension) || "jpg".equals(extension))) {
            throw new IllegalArgumentException("Verified bounded PNG or JPEG required");
        }
        String filename = "agenvas-" + requestId + "." + extension;
        String boundary = "agenvas-" + UUID.randomUUID();
        byte[] before = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\""
                + filename + "\"\r\nContent-Type: image/" + ("jpg".equals(extension) ? "jpeg" : "png")
                + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] after = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[before.length + verifiedImage.length + after.length];
        System.arraycopy(before, 0, payload, 0, before.length);
        System.arraycopy(verifiedImage, 0, payload, before.length, verifiedImage.length);
        System.arraycopy(after, 0, payload, before.length + verifiedImage.length, after.length);
        Request request = request("/upload/image")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .post(RequestBody.create(payload, MediaType.get("multipart/form-data; boundary=" + boundary))).build();
        JsonNode response = readJson(send(request), MAX_JSON_BYTES);
        String returned = response.path("name").asText();
        if (!safeFilename(returned) || !"input".equals(response.path("type").asText())
                || !response.path("subfolder").asText("").isEmpty()) {
            throw new ProtocolFailure("ComfyUI returned an unsafe upload name");
        }
        return returned;
    }

    /** 从相同固定 origin 下载白名单文件名，并在读取时累计限制响应字节。 */
    public InputStream output(String filename) {
        if (!safeFilename(filename)) {
            throw new IllegalArgumentException("Unsafe ComfyUI output filename");
        }
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        Request request = request("/view?filename=" + encoded + "&type=output&subfolder=")
                .get().build();
        return new FilterInputStream(send(request).body()) {
            private long total;

            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value >= 0 && ++total > MAX_OUTPUT_BYTES) {
                    throw new IOException("ComfyUI output exceeded archive limit");
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = super.read(bytes, offset, length);
                if (count > 0 && (total += count) > MAX_OUTPUT_BYTES) {
                    throw new IOException("ComfyUI output exceeded archive limit");
                }
                return count;
            }
        };
    }

    /** 发送有限路径上的 JSON GET/POST，并在解析前限制响应长度。 */
    private JsonNode json(String method, String path, String body) {
        Request.Builder request = request(path).header("Accept", "application/json");
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .post(RequestBody.create(body, MediaType.get("application/json")));
        } else {
            request.get();
        }
        return readJson(send(request.build()), MAX_JSON_BYTES);
    }

    /** Strip the internal route's leading slash so URI resolution retains the proxy prefix. */
    private Request.Builder request(String path) {
        URI base = URI.create(origin.toString() + "/");
        return new Request.Builder().url(base.resolve(path.substring(1)).toString());
    }

    private record TransportResponse(InputStream body) {}

    /** No redirects or retries; a 5xx/network error remains an uncertain submission. */
    private TransportResponse send(Request request) {
        for (String segment : origin.getPath().split("/")) DebugHttpCapture.registerSecret(segment);
        try {
            Response response = client.newCall(request).execute();
            int status = response.code();
            if (status < 200 || status >= 300) {
                try (response) {
                    if (DebugHttpCapture.enabled() && response.body() != null) {
                        response.body().byteStream().readNBytes(MAX_JSON_BYTES + 1);
                    }
                }
                if (status >= 500) throw new TransportFailure("ComfyUI returned ambiguous HTTP " + status);
                throw new ProtocolFailure("ComfyUI returned HTTP " + status);
            }
            if (response.body() == null) {
                response.close();
                throw new ProtocolFailure("ComfyUI returned an empty body");
            }
            return new TransportResponse(new FilterInputStream(response.body().byteStream()) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { response.close(); }
                }
            });
        } catch (IOException failure) {
            // OkHttp exception messages can contain the authenticated URL; do not retain the cause.
            throw new TransportFailure("ComfyUI transport outcome is uncertain");
        }
    }

    /** 有界读取 JSON 响应；格式错误映射为不含原始上游正文的协议失败。 */
    private JsonNode readJson(TransportResponse response, int maximum) {
        byte[] bytes = readBounded(response, maximum);
        try {
            return mapper.readTree(bytes);
        } catch (RuntimeException failure) {
            throw new ProtocolFailure("ComfyUI returned invalid JSON");
        }
    }

    /** 最多读取 maximum+1 字节以检测超限，并始终关闭响应流。 */
    private byte[] readBounded(TransportResponse response, int maximum) {
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new ProtocolFailure("ComfyUI response exceeded limit");
            return bytes;
        } catch (IOException failure) {
            throw new TransportFailure("ComfyUI response stream failed");
        }
    }

    /** 文件名必须匹配固定 ASCII 白名单且不包含双点路径片段。 */
    private boolean safeFilename(String filename) {
        return filename != null && FILE_NAME.matcher(filename).matches()
                && !filename.contains("..");
    }

    /** Shared validation keeps saved addresses and execution transport in agreement. */
    static URI checkedOrigin(String endpoint) { return ComfyUiEndpoint.checked(endpoint); }

    /** 明确的协议或格式错误；不能据此再次提交生成请求。 */
    public static class ProtocolFailure extends RuntimeException {
        /**
         * @param message 安全摘要，不包含上游响应正文
         */
        public ProtocolFailure(String message) { super(message); }
        /**
         * @param message 安全错误摘要
         * @param cause 解析或协议异常
         */
        public ProtocolFailure(String message, Throwable cause) { super(message, cause); }
    }

    /** 提交时网络结果不确定；任务可能已被 ComfyUI 接受，因此不能当作安全失败。 */
    public static class TransportFailure extends RuntimeException {
        /**
         * @param message 不暴露远端正文的错误摘要
         */
        public TransportFailure(String message) { super(message); }
        /**
         * @param message 错误摘要
         * @param cause 网络或流读取异常
         */
        public TransportFailure(String message, Throwable cause) { super(message, cause); }
    }
}
