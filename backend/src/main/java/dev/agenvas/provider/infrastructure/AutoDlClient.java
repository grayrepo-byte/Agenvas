package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.node.ObjectNode;

/** Fixed AutoDL task protocol. Authorization is the raw ComfyUI token, without Bearer. */
@Component
public class AutoDlClient {
    public static final URI OFFICIAL = URI.create("https://autodl.art");
    private static final String TASKS = "/api/v1/comfyui/comfyui_workflow/";
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final long MAX_RESULT_BYTES = 500L * 1024 * 1024;
    private static final Set<String> RESULT_HOSTS = Set.of(
            "cg-comfyui-prod.tos-cn-beijing.volces.com",
            "codewithgpu-test-1310972338.cos.ap-beijing.myqcloud.com",
            "codewithgpu.ks3-cn-beijing.ksyuncs.com");
    private final URI origin;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    @Autowired public AutoDlClient(ObjectMapper mapper) { this(mapper, OFFICIAL); }
    /** Fixed loopback transport for protocol tests; no connection setting exposes this override. */
    public AutoDlClient(ObjectMapper mapper, URI origin) {
        if (!OFFICIAL.equals(origin) && !("http".equals(origin.getScheme())
                && "127.0.0.1".equals(origin.getHost()) && origin.getPort() > 0
                && origin.getPort() <= 65535 && "".equals(origin.getRawPath())
                && origin.getRawUserInfo() == null && origin.getRawQuery() == null
                && origin.getRawFragment() == null)) throw new IllegalArgumentException("Invalid AutoDL origin");
        this.origin = origin;
        this.mapper = mapper;
        http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, !OFFICIAL.equals(origin)),
                Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2));
    }
    public String create(String key, String workflowId, ObjectNode body) {
        dev.agenvas.provider.domain.AutoDlWorkflows.require(workflowId);
        JsonNode data = request(key, TASKS + workflowId, body);
        String id = data.path("task_id").asText();
        if (!validId(id)) throw new Uncertain();
        return id;
    }
    public record Result(String url, String type) {}
    public record TaskState(String status, List<Result> results) {}
    public TaskState query(String key, String taskId) {
        if (!validId(taskId)) throw new ProtocolFailure();
        JsonNode data = request(key, TASKS + "result/" + taskId, null);
        if (!taskId.equals(data.path("task_id").asText())) throw new ProtocolFailure();
        String status = data.path("status").asText().toUpperCase(Locale.ROOT);
        if ("COMPLETED".equals(status)) status = "SUCCESS";
        if (!Set.of("QUEUED", "RUNNING", "SUCCESS", "FAILED", "CANCELLED", "CANCELED").contains(status))
            throw new ProtocolFailure();
        var results = new ArrayList<Result>();
        if ("SUCCESS".equals(status)) {
            if (!data.path("results").isArray()) throw new ProtocolFailure();
            for (var result : data.path("results")) {
                if (result.isObject() && "output".equals(result.path("output_type").asText("output")))
                    results.add(new Result(result.path("url").asText(), result.path("type").asText()));
                else if (result.isTextual()) results.add(new Result(result.asText(), "video"));
            }
        }
        return new TaskState(status, List.copyOf(results));
    }
    private JsonNode request(String key, String path, ObjectNode body) {
        boolean create = body != null;
        if (key == null || key.isBlank() || key.contains("\n") || key.contains("\r"))
            throw new IllegalArgumentException("Invalid AutoDL credential");
        var request = new Request.Builder().url(origin.resolve(path).toString())
                .header("Authorization", key).header("Accept", "application/json");
        if (create) request.post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.get("application/json")));
        else request.get();
        try (Response response = http.newCall(request.build()).execute()) {
            int status = response.code();
            if (create && Set.of(400, 401, 403, 404, 413, 422).contains(status)) throw new Rejected(status);
            if (status != 200 || response.body() == null) throw transportFailure(create);
            byte[] bytes = response.body().byteStream().readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw transportFailure(create);
            JsonNode parsed = mapper.readTree(bytes);
            if (!"Success".equals(parsed.path("code").asText()) || !parsed.path("data").isObject())
                throw transportFailure(create);
            return parsed.path("data");
        } catch (IOException failure) { throw transportFailure(create); }
        catch (RuntimeException failure) {
            if (failure instanceof Rejected || failure instanceof Uncertain || failure instanceof TechnicalFailure)
                throw failure;
            throw transportFailure(create);
        }
    }
    /** Result downloads never carry the API credential and never follow redirects. */
    public MediaPayload downloadVideo(String address) {
        URI url;
        try { url = URI.create(address); } catch (IllegalArgumentException invalid) { throw new ResultRejected(); }
        boolean loopback = !OFFICIAL.equals(origin) && origin.getHost().equals(url.getHost())
                && origin.getScheme().equals(url.getScheme()) && origin.getPort() == url.getPort();
        if (!loopback && !("https".equals(url.getScheme()) && url.getHost() != null && RESULT_HOSTS.contains(url.getHost())
                && (url.getPort() == -1 || url.getPort() == 443))
                || url.getRawUserInfo() != null || url.getRawFragment() != null
                || url.getRawPath() == null || !url.getRawPath().startsWith("/comfyui/outputs/")
                || url.getRawPath().contains("..") || url.getRawPath().contains("%")) throw new ResultRejected();
        try {
            Response response = http.newCall(new Request.Builder().url(url.toString()).get().build()).execute();
            if (response.code() == 403 || response.code() == 404) { response.close(); throw new ResultExpired(); }
            if (response.isRedirect()) { response.close(); throw new ResultRejected(); }
            if (response.code() != 200 || response.body() == null) { response.close(); throw new TechnicalFailure(); }
            String type = response.header("Content-Type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!Set.of("video/mp4", "application/octet-stream").contains(type)
                    || response.body().contentLength() > MAX_RESULT_BYTES) { response.close(); throw new ResultRejected(); }
            var stream = response.body().byteStream();
            return new MediaPayload(new FilterInputStream(stream) {
                private long read;
                private void count(long size) { if (size > 0 && (read += size) > MAX_RESULT_BYTES) throw new ResultRejected(); }
                @Override public int read() throws IOException { int value = in.read(); count(value < 0 ? 0 : 1); return value; }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int size = in.read(bytes, offset, length); count(size); return size;
                }
                @Override public void close() { response.close(); }
            }, "video/mp4");
        } catch (IOException failure) { throw new TechnicalFailure(); }
    }
    private static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"); }
    private static RuntimeException transportFailure(boolean create) { return create ? new Uncertain() : new TechnicalFailure(); }
    public static final class Rejected extends RuntimeException {
        public final int status;
        public Rejected(int status) { super("AutoDL submission rejected: HTTP " + status); this.status = status; }
    }
    public static final class Uncertain extends RuntimeException { public Uncertain() { super("AutoDL submission uncertain"); } }
    public static final class TechnicalFailure extends RuntimeException { public TechnicalFailure() { super("AutoDL query/download unavailable"); } }
    public static final class ProtocolFailure extends RuntimeException { public ProtocolFailure() { super("AutoDL task protocol invalid"); } }
    public static final class ResultRejected extends RuntimeException { public ResultRejected() { super("AutoDL result URL/media rejected"); } }
    public static final class ResultExpired extends RuntimeException { public ResultExpired() { super("AutoDL result expired"); } }
}
