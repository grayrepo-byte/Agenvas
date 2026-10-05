package dev.agenvas.provider.infrastructure;

import dev.agenvas.shared.http.DebugHttpCapture;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 请求从管理员固定的 ComfyUI 基地址发起；手动跳转逐步校验地址与凭据边界，禁止隐式重试。 */
public class ComfyUiClient {

    /** ComfyUI JSON 请求与响应的最大字节数。 */
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    /** 上传参考图的最大字节数。 */
    private static final int MAX_IMAGE_BYTES = 30 * 1024 * 1024;
    /** 单个生成输出允许读取的最大字节数。 */
    private static final int MAX_OUTPUT_BYTES = 500 * 1024 * 1024;
    /** ComfyUI 文件名白名单；禁止路径、目录和控制字符。 */
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
    /** 与 provider_attempt.provider_request_id 的持久长度上限保持一致。 */
    private static final int MAX_PROMPT_ID_CHARACTERS = 240;
    /** 不透明的远程 ID 只能占一个非目录路径片段，禁止编码、查询参数和控制字符。 */
    private static final Pattern PROMPT_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_REDIRECT_LOCATION_CHARACTERS = 8192;
    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int IPV6_BYTES = 16;
    private static final int IPV6_GLOBAL_UNICAST_MASK = 0xe0;
    private static final int IPV6_GLOBAL_UNICAST_PREFIX = 0x20;
    private static final int IPV6_TRANSITION_FIRST_BYTE = 0x20;
    private static final int IPV6_TEREDO_SECOND_BYTE = 0x01;
    private static final int IPV6_SIX_TO_FOUR_SECOND_BYTE = 0x02;
    private static final String AZURE_METADATA_ADDRESS = "168.63.129.16";
    private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+");
    private static final String HTTP_GET = "GET";
    private static final String HTTP_HEAD = "HEAD";
    private static final String HTTP_POST = "POST";
    private static final int MOVED_PERMANENTLY = 301;
    private static final int FOUND = 302;
    private static final int SEE_OTHER = 303;
    private static final int TEMPORARY_REDIRECT = 307;
    private static final int PERMANENT_REDIRECT = 308;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(
            MOVED_PERMANENTLY, FOUND, SEE_OTHER, TEMPORARY_REDIRECT, PERMANENT_REDIRECT);
    /** 启动时校验并固定的 HTTP(S) origin；跳转回此源时保留自托管地址规则。 */
    private final URI origin;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    /** 禁止代理、重定向和隐式重试的客户端。 */
    private final OkHttpClient client;
    /** 跨域跳转使用更严格的 DNS；仍禁止自动跳转、隐式重试与代理。 */
    private final OkHttpClient redirectClient;
    /** 解析有字节上限的 JSON 协议响应。 */
    private final ObjectMapper mapper;

    /** 校验基地址后创建直连客户端，保留代理路径，所有请求限时 20 秒。 */
    public ComfyUiClient(ComfyUiProperties properties, ObjectMapper mapper) {
        this.origin = checkedOrigin(properties.endpoint());
        this.mapper = mapper;
        this.client = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, true),
                CONNECT_TIMEOUT, REQUEST_TIMEOUT, REQUEST_TIMEOUT);
        this.redirectClient = PinnedHttpClients.pinned(checkedRedirectDns(Dns.SYSTEM),
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

    /** 只提交一次；请求保留本地 requestKey，查询使用上游实际受理的原始 prompt_id。 */
    public String submit(JsonNode fixedWorkflow, UUID requestKey) {
        if (fixedWorkflow == null || !fixedWorkflow.isObject() || requestKey == null) {
            throw new IllegalArgumentException("Fixed workflow and request key are required");
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("prompt", fixedWorkflow.deepCopy());
        body.put("client_id", requestKey.toString());
        body.put("prompt_id", requestKey.toString());
        JsonNode response = json("POST", "/prompt", body.toString());
        JsonNode acknowledged = response.path("prompt_id");
        if (!acknowledged.isString() || !safePromptId(acknowledged.stringValue())) {
            // 外部可能已经受理；无可安全查询的回执仍不能自动重新提交。
            throw new ProtocolFailure("ComfyUI did not return a valid prompt_id");
        }
        return acknowledged.stringValue();
    }

    /** 读取原 prompt 历史；空历史表示暂无证据，不代表拒绝或允许重提。 */
    public JsonNode history(String promptId) {
        if (!safePromptId(promptId)) throw new IllegalArgumentException("A safe promptId is required");
        return json("GET", "/history/" + promptId, null);
    }

    /** 查询已保存 prompt ID，并只解析固定模板声明的图片输出节点。 */
    public ComfyUiHistory.ImageResult imageStatus(String promptId, String outputNodeId) {
        return ComfyUiHistory.image(history(promptId), promptId, outputNodeId);
    }

    /** 视频查询也只使用原 prompt ID，并要求固定模板中的动画 MP4 输出节点。 */
    public ComfyUiHistory.VideoResult videoStatus(String promptId, String outputNodeId) {
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
        return output(filename, "");
    }

    /** Published output starts at the pinned server and shares the bounded checked redirect transport. */
    public InputStream output(String filename, String subfolder) {
        if (!ComfyUiHistory.safeFile(filename) || !ComfyUiHistory.safeSubfolder(subfolder)) {
            throw new IllegalArgumentException("Unsafe ComfyUI output filename");
        }
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        Request request = request("/view?filename=" + encoded + "&type=output&subfolder="
                + URLEncoder.encode(subfolder, StandardCharsets.UTF_8))
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
    private record RedirectTarget(HttpUrl url, String method) {}

    /** Redirects are bounded and explicit; HTTP method rules never become an implicit retry of the original URL. */
    private TransportResponse send(Request initialRequest) {
        registerOriginSecrets();
        registerRedirectSecrets(initialRequest);
        Request request = initialRequest;
        Set<RedirectTarget> visited = new HashSet<>();
        visited.add(new RedirectTarget(initialRequest.url(), initialRequest.method()));
        for (int redirects = 0; ; redirects++) {
            OkHttpClient transport = sameOrigin(origin, request.url().uri()) ? client : redirectClient;
            Response response = execute(transport, request);
            if (!REDIRECT_STATUSES.contains(response.code())) return checkedResponse(response);
            try (response) {
                String location = response.header("Location");
                DebugHttpCapture.registerSecret(location);
                if (redirects >= MAX_REDIRECTS) throw new ProtocolFailure("ComfyUI redirect limit exceeded");
                URI target = checkedRedirect(origin, request.url().uri(), location, redirectClient.dns());
                Request nextRequest = redirectedRequest(request, target, response.code());
                // Register before closing the redirect body, which may echo a signed destination.
                registerRedirectSecrets(nextRequest);
                if (!visited.add(new RedirectTarget(nextRequest.url(), nextRequest.method())))
                    throw new ProtocolFailure("ComfyUI redirect loop detected");
                request = nextRequest;
            }
        }
    }

    private Request redirectedRequest(Request previous, URI target, int status) {
        String method = previous.method();
        RequestBody body = previous.body();
        if (status == SEE_OTHER && !HTTP_HEAD.equals(method)
                || (status == MOVED_PERMANENTLY || status == FOUND) && HTTP_POST.equals(method)) {
            method = HTTP_GET;
            body = null;
        }
        Request.Builder request = new Request.Builder().url(target.toASCIIString()).method(method, body);
        String accept = previous.header("Accept");
        if (accept != null) request.header("Accept", accept);
        String contentType = previous.header("Content-Type");
        if (body != null && contentType != null) request.header("Content-Type", contentType);
        return request.build();
    }

    private static void registerRedirectSecrets(Request request) {
        if (!DebugHttpCapture.enabled()) return;
        var url = request.url();
        DebugHttpCapture.registerSecret(url.toString());
        for (String segment : url.pathSegments()) DebugHttpCapture.registerSecret(segment);
        for (String segment : url.encodedPathSegments()) DebugHttpCapture.registerSecret(segment);
        for (int index = 0; index < url.querySize(); index++)
            DebugHttpCapture.registerSecret(url.queryParameterValue(index));
        String query = url.encodedQuery();
        if (query != null) for (String parameter : query.split("&")) {
            int separator = parameter.indexOf('=');
            if (separator >= 0) DebugHttpCapture.registerSecret(parameter.substring(separator + 1));
        }
    }

    private void registerOriginSecrets() {
        for (String segment : origin.getPath().split("/")) DebugHttpCapture.registerSecret(segment);
    }

    private Response execute(OkHttpClient transport, Request request) {
        try {
            return transport.newCall(request).execute();
        } catch (IOException failure) {
            // OkHttp exception messages can contain the authenticated URL; do not retain the cause.
            throw new TransportFailure("ComfyUI transport outcome is uncertain");
        }
    }

    private TransportResponse checkedResponse(Response response) {
        int status = response.code();
        if (status < 200 || status >= 300) {
            try (response) {
                if (DebugHttpCapture.enabled() && response.body() != null) {
                    try {
                        response.body().byteStream().readNBytes(MAX_JSON_BYTES + 1);
                    } catch (IOException failure) {
                        throw new TransportFailure("ComfyUI response stream failed");
                    }
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
    }

    /** Same-origin redirects retain administrator address rules; foreign targets must use public HTTPS. */
    static URI checkedRedirect(URI origin, URI current, String location, Dns redirectDns) {
        try {
            if (location == null || location.isBlank() || location.length() > MAX_REDIRECT_LOCATION_CHARACTERS)
                throw new IllegalArgumentException();
            URI reference = URI.create(location);
            URI target;
            if (reference.getScheme() == null && reference.getRawAuthority() == null
                    && reference.getRawPath().isEmpty()) {
                // Java URI.resolve drops the final path segment for a query-only Location.
                String query = reference.getRawQuery() == null ? current.getRawQuery() : reference.getRawQuery();
                target = URI.create(current.getScheme() + "://" + current.getRawAuthority() + current.getRawPath()
                        + (query == null ? "" : "?" + query)
                        + (reference.getRawFragment() == null ? "" : "#" + reference.getRawFragment()));
            } else {
                target = current.resolve(reference);
            }
            if (target.getHost() == null || target.getRawUserInfo() != null || target.getRawFragment() != null)
                throw new IllegalArgumentException();
            if (sameOrigin(origin, target)) {
                // Validate the selected address and path, while retaining the target's own query/signature.
                ComfyUiEndpoint.checked(target.getScheme() + "://" + target.getRawAuthority() + target.getRawPath());
            } else {
                if (!"https".equals(target.getScheme()) || target.getPort() != -1 && target.getPort() != DEFAULT_HTTPS_PORT)
                    throw new IllegalArgumentException();
                rejectOriginPathSecrets(origin, target);
                // Literal IP URLs bypass OkHttp DNS; resolve them through the same strict policy before a request.
                if (NUMERIC_HOST.matcher(target.getHost()).matches() || target.getHost().contains(":"))
                    redirectDns.lookup(target.getHost());
            }
            return target;
        } catch (IllegalArgumentException | UnknownHostException failure) {
            throw new ProtocolFailure("ComfyUI redirect is unsafe");
        }
    }

    private static void rejectOriginPathSecrets(URI origin, URI target) {
        String decodedPath = target.getPath();
        String decodedQuery = target.getQuery();
        for (String segment : origin.getPath().split("/")) {
            if (!segment.isEmpty() && (target.toASCIIString().contains(segment)
                    || decodedPath != null && decodedPath.contains(segment)
                    || decodedQuery != null && decodedQuery.contains(segment))) {
                throw new IllegalArgumentException();
            }
        }
    }

    static Dns checkedRedirectDns(Dns resolver) {
        Dns checked = FixedCloudDns.checked(resolver, false);
        return hostname -> {
            List<InetAddress> addresses = checked.lookup(hostname);
            for (InetAddress address : addresses) {
                byte[] bytes = address.getAddress();
                if (address.isSiteLocalAddress() || bytes.length == IPV6_BYTES && !publicIpv6(bytes)
                        || AZURE_METADATA_ADDRESS.equals(address.getHostAddress())) {
                    throw new UnknownHostException("ComfyUI redirect DNS returned blocked address");
                }
            }
            return addresses;
        };
    }

    private static boolean publicIpv6(byte[] bytes) {
        // Only global unicast may leave the administrator's origin. NAT64 is outside this range;
        // Teredo and 6to4 can translate an apparently public IPv6 address to private IPv4.
        return (bytes[0] & IPV6_GLOBAL_UNICAST_MASK) == IPV6_GLOBAL_UNICAST_PREFIX
                && !(bytes[0] == IPV6_TRANSITION_FIRST_BYTE && bytes[1] == IPV6_TEREDO_SECOND_BYTE
                        && bytes[2] == 0 && bytes[3] == 0)
                && !(bytes[0] == IPV6_TRANSITION_FIRST_BYTE && bytes[1] == IPV6_SIX_TO_FOUR_SECOND_BYTE);
    }

    private static boolean sameOrigin(URI origin, URI target) {
        return origin.getScheme().equals(target.getScheme()) && origin.getHost().equalsIgnoreCase(target.getHost())
                && effectivePort(origin) == effectivePort(target);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equals(uri.getScheme()) ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
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

    /** 同时约束提交回执、持久 ID 的历史请求和纯历史解析器，始终保留 ID 原文。 */
    static boolean safePromptId(String promptId) {
        return promptId != null && promptId.length() <= MAX_PROMPT_ID_CHARACTERS
                && PROMPT_ID.matcher(promptId).matches();
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
