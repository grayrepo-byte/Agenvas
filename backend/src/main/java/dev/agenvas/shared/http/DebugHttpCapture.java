package dev.agenvas.shared.http;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Invocation-local capture, installed once in transports; disabled calls never copy bodies.
 * Headers are never stored. Each checkpoint is sanitized before leaving this scope.
 */
public final class DebugHttpCapture implements AutoCloseable {
    private static final int NO_EXCHANGE = -1;
    public static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
    private static final String REDACTED = "[REDACTED]";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ThreadLocal<DebugHttpCapture> ACTIVE = new ThreadLocal<>();
    /** Internal SDK carrier, removed by the capture interceptor before any network I/O. */
    public static final String CAPTURE_HEADER = "X-Agenvas-Debug-Capture";
    private static final ConcurrentMap<String, DebugHttpCapture> REQUEST_CAPTURES = new ConcurrentHashMap<>();
    private static final Set<String> PRIVATE_FIELDS = Set.of("authorization", "proxyauthorization",
            "apikey", "key", "secret", "clientsecret", "password", "credential", "credentials",
            "token", "accesstoken", "refreshtoken", "cookie", "setcookie", "headers",
            "signature", "sig", "xgoogsignature", "xamzsignature", "reasoning", "reasoningcontent",
            "reasoningdetails", "reasoningtext", "encryptedcontent", "analysis", "thinking", "thought", "thoughts", "thoughtsignature");
    private static final Set<String> PUBLIC_HEADERS = Set.of("accept", "content-type", "content-length",
            "user-agent", "host", "connection", "accept-encoding");
    private final DebugHttpCapture previous;
    private final Consumer<List<Exchange>> checkpoint;
    private final List<Exchange> exchanges = new ArrayList<>();
    private final Set<String> secrets = new HashSet<>();
    private String requestToken;
    private boolean closed;

    public enum Encoding { UTF8, BASE64, MULTIPART_JSON, OMITTED }
    public record Body(String content, Encoding encoding, boolean truncated) {}
    public record Exchange(String method, String url, Body requestBody, Integer responseStatus,
            Body responseBody) {}

    private DebugHttpCapture(Consumer<List<Exchange>> checkpoint) {
        this.previous = ACTIVE.get();
        this.checkpoint = checkpoint;
        ACTIVE.set(this);
        publish();
    }

    public static DebugHttpCapture open(Consumer<List<Exchange>> checkpoint) {
        return new DebugHttpCapture(checkpoint);
    }
    public static boolean enabled() { return ACTIVE.get() != null; }
    /** Sanitizes a semantic log with the same credentials learned from this invocation's transport. */
    public synchronized String sanitizeJson(String json) {
        return body(json.getBytes(StandardCharsets.UTF_8), "application/json", false).content();
    }

    /**
     * Binds this invocation before entering the SDK's asynchronous path. Only use with a transport
     * that installs {@link #interceptor()}; arbitrary clients must never receive the internal carrier.
     * The token carries no project, user or audit identity and is unregistered when the scope closes.
     */
    public static Map<String, String> requestHeaders() {
        DebugHttpCapture capture = ACTIVE.get();
        return capture == null ? Map.of() : capture.bindRequest();
    }

    private synchronized Map<String, String> bindRequest() {
        if (closed) return Map.of();
        if (requestToken == null) {
            requestToken = UUID.randomUUID().toString();
            REQUEST_CAPTURES.put(requestToken, this);
        }
        return Map.of(CAPTURE_HEADER, requestToken);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (requestToken != null) REQUEST_CAPTURES.remove(requestToken, this);
        if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
    }
    private static String normalized(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
    private static boolean privateField(String name) {
        String value = normalized(name);
        return PRIVATE_FIELDS.contains(value) || value.endsWith("apikey") || value.endsWith("secret")
                || value.endsWith("password") || value.endsWith("credential") || value.endsWith("token")
                || value.contains("authorization") || value.startsWith("xamz") || value.startsWith("xgoog") || value.startsWith("xtos");
    }
    private void remember(String value) {
        if (value == null || value.isBlank()) return;
        secrets.add(value);
        if (value.startsWith("Bearer ") || value.startsWith("Basic ")) secrets.add(value.substring(value.indexOf(' ') + 1));
        // Cookies may be echoed individually by a gateway; retain only their values for redaction.
        for (String cookie : value.split(";")) {
            int equals = cookie.indexOf('=');
            if (equals >= 0 && equals + 1 < cookie.length()) secrets.add(cookie.substring(equals + 1).trim());
        }
    }
    private String safeText(String value) {
        for (String secret : secrets.stream().sorted((left, right) -> Integer.compare(right.length(), left.length())).toList()) {
            value = value.replace(secret, REDACTED);
        }
        return value.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer " + REDACTED)
                .replaceAll("(?s)<(?:think|thinking|reasoning)>.*?(</(?:think|thinking|reasoning)>|$)", REDACTED)
                .replaceAll("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(-----END [A-Z ]*PRIVATE KEY-----|$)", REDACTED)
                .replaceAll("\\bsk-[A-Za-z0-9_-]+", REDACTED)
                .replaceAll("(?i)([?&](?:api[_-]?key|key|token|access[_-]?token|signature|sig|x-amz-[a-z-]+|x-goog-[a-z-]+|x-tos-[a-z-]+)=)[^&\\s\"<>]+", "$1" + REDACTED)
                .replaceAll("(?i)(https?://)[^/\\s@]+@", "$1" + REDACTED + "@");
    }
    private JsonNode scrub(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            // Gemini marks a whole content part as thought, rather than naming its text field.
            if (object.path("thought").asBoolean(false) || "analysis".equals(object.path("channel").asText())) return MAPPER.getNodeFactory().textNode(REDACTED);
            for (String name : object.propertyNames()) {
                if (privateField(name)) object.put(name, REDACTED);
                else object.set(name, scrub(object.get(name)));
            }
        } else if (node.isArray()) {
            var array = (tools.jackson.databind.node.ArrayNode) node;
            for (int index = 0; index < array.size(); index++) array.set(index, scrub(array.get(index)));
        } else if (node.isTextual()) return MAPPER.getNodeFactory().textNode(safeText(node.asText()));
        return node;
    }
    private Body body(byte[] bytes, String type, boolean truncated) {
        String contentType = type == null ? "" : type.toLowerCase(Locale.ROOT);
        int first = 0;
        while (first < bytes.length && Character.isWhitespace((char) bytes[first])) first++;
        boolean looksLikeJson = first < bytes.length && (bytes[first] == '{' || bytes[first] == '[');
        if (contentType.contains("json") || looksLikeJson) {
            try {
                return new Body(safeText(scrub(MAPPER.readTree(bytes)).toString()), Encoding.UTF8, truncated);
            } catch (RuntimeException invalidJson) {
                // Partial JSON cannot be reliably stripped of reasoning/credential fields.
                return new Body("[无法安全解析的 JSON 正文已省略]", Encoding.OMITTED, truncated);
            }
        }
        if (contentType.contains("event-stream")) return new Body("[流式事件正文无法安全脱敏，已省略]", Encoding.OMITTED, truncated);
        if (contentType.isEmpty() || contentType.startsWith("text/") || contentType.contains("xml") || contentType.contains("x-www-form-urlencoded")) {
            String text = safeText(new String(bytes, StandardCharsets.UTF_8));
            text = text.replaceAll("(?i)(api[_-]?key|password|secret|access[_-]?token|authorization|token)([=:\\s]+)[^&\\s<]+", "$1$2" + REDACTED);
            return new Body(text, Encoding.UTF8, truncated);
        }
        // A gateway may echo a credential even in an incorrectly labelled/binary body.
        byte[] safeBytes = safeText(new String(bytes, StandardCharsets.ISO_8859_1))
                .getBytes(StandardCharsets.ISO_8859_1);
        return new Body(Base64.getEncoder().encodeToString(safeBytes), Encoding.BASE64, truncated);
    }
    private String safeUrl(String url) {
        HttpUrl parsed = HttpUrl.get(url);
        HttpUrl.Builder builder = parsed.newBuilder().username("").password("").fragment(null);
        for (String name : parsed.queryParameterNames()) {
            if (privateField(name)) {
                for (String value : parsed.queryParameterValues(name)) remember(value);
                builder.setQueryParameter(name, REDACTED);
            }
        }
        return safeText(builder.build().toString());
    }
    private int begin(String method, String url, Body request) {
        String safeAddress = safeUrl(url);
        Body safeRequest = request == null ? null : new Body(safeText(request.content()), request.encoding(), request.truncated());
        exchanges.add(new Exchange(method, safeAddress, safeRequest, null, null));
        publish();
        return exchanges.size() - 1;
    }
    private void response(int index, int status, Body body) {
        Exchange old = exchanges.get(index);
        exchanges.set(index, new Exchange(old.method(), old.url(), old.requestBody(), status, body));
        publish();
    }
    private void publish() { checkpoint.accept(List.copyOf(exchanges)); }

    private Body requestBody(RequestBody request) throws IOException {
        if (request == null) return null;
        if (request.isDuplex() || request.isOneShot()) return new Body("[流式请求正文已省略]", Encoding.OMITTED, false);
        if (request instanceof MultipartBody multipart) {
            var parts = MAPPER.createArrayNode();
            for (var part : multipart.parts()) {
                // Only the standard field/file descriptor is kept, never arbitrary part headers.
                String disposition = part.headers() == null ? null : part.headers().get("Content-Disposition");
                Body value = requestBody(part.body());
                String safeDisposition = safeText(disposition == null ? "" : disposition);
                boolean sensitive = safeDisposition.matches("(?i).*name=\"(api[_-]?key|token|password|secret|authorization)\".*");
                parts.addObject().put("field", safeDisposition).put("content", sensitive ? REDACTED : value.content())
                        .put("encoding", value.encoding().name()).put("truncated", value.truncated());
            }
            return new Body(parts.toString(), Encoding.MULTIPART_JSON, multipart.contentLength() > MAX_BODY_BYTES);
        }
        Buffer buffer = new Buffer();
        // Existing fixed adapters use bounded repeatable byte bodies. Never copy unbounded bodies.
        if (request.contentLength() < 0 || request.contentLength() > MAX_BODY_BYTES) {
            return new Body("[请求正文超过 64 MiB 或长度未知，已省略]", Encoding.OMITTED, true);
        }
        request.writeTo(buffer);
        return body(buffer.readByteArray(), request.contentType() == null ? null : request.contentType().toString(), false);
    }

    public static Interceptor interceptor() {
        return chain -> {
            var request = chain.request();
            String token = request.header(CAPTURE_HEADER);
            // Even an expired/unknown carrier is stripped, and must not fall back to another
            // invocation's ThreadLocal on a reused execution thread.
            DebugHttpCapture capture = token == null ? ACTIVE.get() : REQUEST_CAPTURES.get(token);
            if (token != null) request = request.newBuilder().removeHeader(CAPTURE_HEADER).build();
            if (capture == null) return chain.proceed(request);
            int index;
            synchronized (capture) {
                for (String name : request.headers().names()) {
                    if (!PUBLIC_HEADERS.contains(name.toLowerCase(Locale.ROOT))) for (String value : request.headers().values(name)) capture.remember(value);
                }
                Body requestBody;
                try { requestBody = capture.requestBody(request.body()); }
                catch (IOException | RuntimeException failure) { requestBody = new Body("[请求正文采集失败]", Encoding.OMITTED, false); }
                index = capture.begin(request.method(), request.url().toString(), requestBody);
            }
            // No capture monitor or database transaction is held while waiting for the Provider.
            var response = chain.proceed(request);
            synchronized (capture) {
                for (String name : response.headers().names()) {
                    if (privateField(name)) for (String value : response.headers().values(name)) capture.remember(value);
                }
                capture.response(index, response.code(), null);
            }
            ResponseBody original = response.body();
            if (original == null) return response;
            Collector collector = new Collector(capture, index, response.code(),
                    original.contentType() == null ? null : original.contentType().toString());
            BufferedSource source = Okio.buffer(new ForwardingSource(original.source()) {
                @Override public long read(Buffer sink, long byteCount) throws IOException {
                    long count = super.read(sink, byteCount);
                    if (count == -1) collector.finish(false);
                    else if (count > 0) {
                        Buffer copy = new Buffer();
                        long remaining = Math.max(0, MAX_BODY_BYTES - collector.bytes.size());
                        sink.copyTo(copy, sink.size() - count, Math.min(count, remaining));
                        collector.add(copy.readByteArray(), count);
                    }
                    return count;
                }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { collector.finish(true); }
                }
            });
            return response.newBuilder().body(new ResponseBody() {
                @Override public okhttp3.MediaType contentType() { return original.contentType(); }
                @Override public long contentLength() { return original.contentLength(); }
                @Override public BufferedSource source() { return source; }
            }).build();
        };
    }

    /** ComfyUI uses the JDK client; it joins the same invocation scope and redaction policy. */
    public static int begin(String method, String url, byte[] bytes, String type) {
        DebugHttpCapture capture = ACTIVE.get();
        if (capture == null) return NO_EXCHANGE;
        synchronized (capture) {
            return capture.begin(method, url, bytes == null ? null : capture.body(bytes, type, false));
        }
    }
    public static InputStream responseStream(int index, int status, String type, InputStream stream) {
        DebugHttpCapture capture = ACTIVE.get();
        if (capture == null || index < 0) return stream;
        synchronized (capture) { capture.response(index, status, null); }
        Collector collector = new Collector(capture, index, status, type);
        return new FilterInputStream(stream) {
            @Override public int read() throws IOException {
                int value = in.read();
                if (value < 0) collector.finish(false);
                else collector.add(new byte[] {(byte) value}, 1);
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = in.read(bytes, offset, length);
                if (count < 0) collector.finish(false);
                else if (count > 0) collector.add(java.util.Arrays.copyOfRange(bytes, offset,
                        offset + Math.min(count, Math.max(0, MAX_BODY_BYTES - collector.bytes.size()))), count);
                return count;
            }
            @Override public void close() throws IOException {
                try { super.close(); } finally { collector.finish(true); }
            }
        };
    }
    private static final class Collector {
        private final DebugHttpCapture capture;
        private final int index;
        private final int status;
        private final String type;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private long total;
        private boolean finished;
        private Collector(DebugHttpCapture capture, int index, int status, String type) {
            this.capture = capture; this.index = index; this.status = status; this.type = type;
        }
        private synchronized void add(byte[] value, long count) {
            if (finished) return;
            total += count;
            int length = Math.min(value.length, Math.max(0, MAX_BODY_BYTES - bytes.size()));
            bytes.write(value, 0, length);
        }
        // The SDK may close a stream on a cancellation/timer thread while its reader is active.
        private synchronized void finish(boolean partial) {
            if (finished) return;
            finished = true;
            synchronized (capture) {
                capture.response(index, status, capture.body(bytes.toByteArray(), type, partial || total > MAX_BODY_BYTES));
            }
        }
    }
}
