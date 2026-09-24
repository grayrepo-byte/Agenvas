package dev.agenvas.provider.infrastructure;

import java.io.IOException;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Strict, bounded HTTP protocol for one configured ComfyUI instance; no arbitrary URL fetches. */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiClient {

    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 30 * 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 500 * 1024 * 1024;
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
    private final URI origin;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public ComfyUiClient(ComfyUiProperties properties, ObjectMapper mapper) {
        this.origin = checkedOrigin(properties.endpoint());
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI uri, java.net.SocketAddress address,
                            IOException failure) {
                        // A failed direct connection is handled by the request caller.
                    }
                }).build();
    }

    /** Stable fingerprint of the administrator-pinned origin, never a browser-supplied URL. */
    public String originSha256() {
        try {
            byte[] bytes = origin.toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /** Submits once with the previously committed request key as ComfyUI's prompt id. */
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
                // The external request may already be running; never classify a mismatch as
                // a safe rejection or submit it a second time.
                throw new ProtocolFailure("ComfyUI acknowledged a different prompt_id");
            }
            return acknowledged;
        } catch (IllegalArgumentException failure) {
            throw new ProtocolFailure("ComfyUI did not return a valid prompt_id", failure);
        }
    }

    /** Empty history is pending, not proof of rejection or a reason to resubmit. */
    public JsonNode history(UUID promptId) {
        if (promptId == null) throw new IllegalArgumentException("promptId is required");
        return json("GET", "/history/" + promptId, null);
    }

    /** Looks only for the precommitted prompt id; absence is never proof that submission failed. */
    public boolean originalPromptExists(UUID promptId) {
        if (promptId == null) throw new IllegalArgumentException("promptId is required");
        JsonNode history = history(promptId);
        if (!history.isObject()) {
            throw new ProtocolFailure("ComfyUI history was not an object");
        }
        if (history.has(promptId.toString())) {
            requireOriginalIdentity(history.path(promptId.toString()).path("prompt"), promptId);
            return true;
        }
        if (!history.isEmpty()) {
            throw new ProtocolFailure("ComfyUI returned history for a different prompt");
        }
        JsonNode queue = json("GET", "/queue", null);
        if (!queue.isObject() || !queue.path("queue_running").isArray()
                || !queue.path("queue_pending").isArray()) {
            throw new ProtocolFailure("ComfyUI queue was malformed");
        }
        return queueContainsOriginal(queue.path("queue_running"), promptId)
                | queueContainsOriginal(queue.path("queue_pending"), promptId);
    }

    private boolean queueContainsOriginal(JsonNode entries, UUID promptId) {
        boolean found = false;
        for (JsonNode entry : entries) {
            if (!entry.isArray()) {
                throw new ProtocolFailure("ComfyUI queue entry was malformed");
            }
            if (promptId.toString().equals(entry.path(1).asText())) {
                requireOriginalIdentity(entry, promptId);
                found = true;
            }
        }
        return found;
    }

    private void requireOriginalIdentity(JsonNode entry, UUID promptId) {
        if (!entry.isArray() || !promptId.toString().equals(entry.path(1).asText())
                || !promptId.toString().equals(entry.path(3).path("client_id").asText())) {
            throw new ProtocolFailure("ComfyUI prompt identity did not match the saved request");
        }
    }

    /** Polls only the saved prompt id and the installed template's fixed image output node. */
    public ComfyUiHistory.ImageResult imageStatus(UUID promptId, String outputNodeId) {
        return ComfyUiHistory.image(history(promptId), promptId, outputNodeId);
    }

    /** Video polling has the same saved-id rule but requires the fixed animated MP4 node. */
    public ComfyUiHistory.VideoResult videoStatus(UUID promptId, String outputNodeId) {
        return ComfyUiHistory.video(history(promptId), promptId, outputNodeId);
    }

    /** Uploads only a server-generated basename; ComfyUI may rename it on collision. */
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
        JsonNode response = readJson(send(request), MAX_JSON_BYTES);
        String returned = response.path("name").asText();
        if (!safeFilename(returned) || !"input".equals(response.path("type").asText())
                || !response.path("subfolder").asText("").isEmpty()) {
            throw new ProtocolFailure("ComfyUI returned an unsafe upload name");
        }
        return returned;
    }

    /** Retrieves one history-declared output from the same fixed origin, never an external URL. */
    public InputStream output(String filename) {
        if (!safeFilename(filename)) {
            throw new IllegalArgumentException("Unsafe ComfyUI output filename");
        }
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        HttpRequest request = request("/view?filename=" + encoded + "&type=output&subfolder=")
                .GET().build();
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

    private JsonNode json(String method, String path, String body) {
        HttpRequest.Builder request = request(path).header("Accept", "application/json");
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.GET();
        }
        return readJson(send(request.build()), MAX_JSON_BYTES);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(20));
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                try (InputStream discarded = response.body()) {
                    // Never follow redirects or reflect upstream bodies into user/model errors.
                }
                if (status >= 500) {
                    throw new TransportFailure("ComfyUI returned ambiguous HTTP " + status);
                }
                throw new ProtocolFailure("ComfyUI returned HTTP " + status);
            }
            return response;
        } catch (IOException failure) {
            throw new TransportFailure("ComfyUI transport outcome is uncertain", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new TransportFailure("ComfyUI request interrupted", failure);
        }
    }

    private JsonNode readJson(HttpResponse<InputStream> response, int maximum) {
        byte[] bytes = readBounded(response, maximum);
        try {
            return mapper.readTree(bytes);
        } catch (RuntimeException failure) {
            throw new ProtocolFailure("ComfyUI returned invalid JSON", failure);
        }
    }

    private byte[] readBounded(HttpResponse<InputStream> response, int maximum) {
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new ProtocolFailure("ComfyUI response exceeded limit");
            return bytes;
        } catch (IOException failure) {
            throw new TransportFailure("ComfyUI response stream failed", failure);
        }
    }

    private boolean safeFilename(String filename) {
        return filename != null && FILE_NAME.matcher(filename).matches()
                && !filename.contains("..");
    }

    /** A literal IPv4 origin prevents DNS rebinding and forbids userinfo, paths and redirects. */
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

    /** Plain HTTP is only a deliberately configured private ComfyUI exception. */
    private static boolean privateIpv4(String host) {
        String[] parts = host.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        return first == 10 || first == 127 || first == 192 && second == 168
                || first == 172 && second >= 16 && second <= 31;
    }

    /** A definite malformed upstream response, not permission to regenerate. */
    public static class ProtocolFailure extends RuntimeException {
        public ProtocolFailure(String message) { super(message); }
        public ProtocolFailure(String message, Throwable cause) { super(message, cause); }
    }

    /** A submit-time transport failure whose acceptance status cannot be inferred. */
    public static class TransportFailure extends RuntimeException {
        public TransportFailure(String message) { super(message); }
        public TransportFailure(String message, Throwable cause) { super(message, cause); }
    }
}
