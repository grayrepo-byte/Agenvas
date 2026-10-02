package dev.agenvas.provider.infrastructure;

import java.io.IOException;
import dev.agenvas.shared.http.DebugHttpCapture;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 访问管理员固定的单个 ComfyUI IPv4 origin；所有请求、文件下载和错误响应均受边界约束。 */
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
    /** 禁止代理和重定向的客户端，避免请求改道或跨域跟随。 */
    private final HttpClient client;
    /** 解析有字节上限的 JSON 协议响应。 */
    private final ObjectMapper mapper;

    /** 校验精确 origin 后创建直连客户端，所有请求限时 20 秒。 */
    public ComfyUiClient(ComfyUiProperties properties, ObjectMapper mapper) {
        this.origin = checkedOrigin(properties.endpoint());
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    /** 该客户端固定直连，不从系统代理配置选择代理。 */
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    /** 直连失败交由本次请求分类；不尝试代理或其他网络地址。 */
                    @Override
                    public void connectFailed(URI uri, java.net.SocketAddress address,
                            IOException failure) {
                    }
                }).build();
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
        HttpRequest request = request("/upload/image")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        int exchange = DebugHttpCapture.begin("POST", request.uri().toString(), payload, "application/octet-stream");
        JsonNode response = readJson(send(request, exchange), MAX_JSON_BYTES);
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
        HttpRequest request = request("/view?filename=" + encoded + "&type=output&subfolder=")
                .GET().build();
        int exchange = DebugHttpCapture.begin("GET", request.uri().toString(), null, null);
        return new FilterInputStream(send(request, exchange).body()) {
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
        HttpRequest.Builder request = request(path).header("Accept", "application/json");
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.GET();
        }
        HttpRequest built = request.build();
        int exchange = DebugHttpCapture.begin(method, built.uri().toString(),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), "application/json");
        return readJson(send(built, exchange), MAX_JSON_BYTES);
    }

    /** 只从固定 origin 拼接内部受控路径，并为单个请求设置超时。 */
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(20));
    }

    /** 禁止跟随重定向；4xx 视为协议错误，5xx 和网络异常视为受理状态不明。 */
    private record TransportResponse(InputStream body) {}

    private TransportResponse send(HttpRequest request, int exchange) {
        try {
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            InputStream captured = DebugHttpCapture.responseStream(exchange, status,
                    response.headers().firstValue("Content-Type").orElse("application/octet-stream"), response.body());
            if (status < 200 || status >= 300) {
                try (InputStream discarded = captured) {
                    if (DebugHttpCapture.enabled()) discarded.readNBytes(MAX_JSON_BYTES + 1);
                    // 不跟随重定向，也不把上游正文反射到用户或模型错误信息。
                }
                if (status >= 500) {
                    throw new TransportFailure("ComfyUI returned ambiguous HTTP " + status);
                }
                throw new ProtocolFailure("ComfyUI returned HTTP " + status);
            }
            return new TransportResponse(captured);
        } catch (IOException failure) {
            throw new TransportFailure("ComfyUI transport outcome is uncertain", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new TransportFailure("ComfyUI request interrupted", failure);
        }
    }

    /** 有界读取 JSON 响应；格式错误映射为不含原始上游正文的协议失败。 */
    private JsonNode readJson(TransportResponse response, int maximum) {
        byte[] bytes = readBounded(response, maximum);
        try {
            return mapper.readTree(bytes);
        } catch (RuntimeException failure) {
            throw new ProtocolFailure("ComfyUI returned invalid JSON", failure);
        }
    }

    /** 最多读取 maximum+1 字节以检测超限，并始终关闭响应流。 */
    private byte[] readBounded(TransportResponse response, int maximum) {
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new ProtocolFailure("ComfyUI response exceeded limit");
            return bytes;
        } catch (IOException failure) {
            throw new TransportFailure("ComfyUI response stream failed", failure);
        }
    }

    /** 文件名必须匹配固定 ASCII 白名单且不包含双点路径片段。 */
    private boolean safeFilename(String filename) {
        return filename != null && FILE_NAME.matcher(filename).matches()
                && !filename.contains("..");
    }

    /** 拒绝域名、用户信息、非根路径、查询参数和片段，只保留精确字面 IPv4 origin。 */
    static URI checkedOrigin(String endpoint) {
        if (endpoint == null) throw new IllegalArgumentException("ComfyUI endpoint is required");
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !"".equals(uri.getRawPath()) && !"/".equals(uri.getRawPath())
                || !validIpv4(host)
                || "http".equals(uri.getScheme()) && !privateIpv4(host)) {
            throw new IllegalArgumentException("ComfyUI endpoint must be an exact IPv4 origin");
        }
        return URI.create(uri.getScheme() + "://" + host + ":" + uri.getPort());
    }

    /** 验证规范十进制 IPv4 字面地址，并排除多播、链路本地和 CGNAT 范围。 */
    private static boolean validIpv4(String host) {
        if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = host.split("\\.");
        for (String part : parts) {
            if (part.length() > 1 && part.charAt(0) == '0') return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        return first > 0 && first < 224
                && !(first == 169 && second == 254)
                && !(first == 100 && second >= 64 && second <= 127);
    }

    /** 明文 HTTP 仅允许显式配置的私有、回环或 RFC1918 地址。 */
    private static boolean privateIpv4(String host) {
        String[] parts = host.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        return first == 10 || first == 127 || first == 192 && second == 168
                || first == 172 && second >= 16 && second <= 31;
    }

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
