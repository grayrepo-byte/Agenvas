package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixed V2 workflow/app transport. Submission is sent once; uncertainty is never retried here. */
@Component
public final class RunningHubClient {
    public static final int MAX_UPLOAD_BYTES = 30 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(2);
    private final ObjectMapper mapper;
    private final OkHttpClient http;

    public RunningHubClient(ObjectMapper mapper) {
        this.mapper = mapper;
        http = PinnedHttpClients.pinned(FixedCloudDns.checked(Dns.SYSTEM, true), CONNECT_TIMEOUT, READ_TIMEOUT, CALL_TIMEOUT);
    }

    /** Admin-configured HTTPS API origin; request paths stay compiled and redirects stay disabled. */
    public static String validatedOrigin(String origin) {
        URI uri;
        try { uri = URI.create(origin == null || origin.isBlank() ? "https://www.runninghub.ai" : origin); }
        catch (IllegalArgumentException invalid) { throw RunningHubDefinition.invalid("RunningHub 站点无效"); }
        boolean local = local(uri);
        if (!(local || "https".equals(uri.getScheme()) && uri.getHost() != null && (uri.getPort() == -1 || uri.getPort() == 443))
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())))
            throw RunningHubDefinition.invalid("请输入 HTTPS API 根地址，不包含路径、凭据、查询参数或片段");
        return uri.toString().replaceAll("/+$", "");
    }

    public String submit(String origin, String key, RunningHubDefinition definition, JsonNode nodes) {
        ObjectNode body = mapper.createObjectNode();
        body.set("nodeInfoList", nodes);
        body.put("instanceType", definition.instanceType() == null ? "default" : definition.instanceType());
        body.put("usePersonalQueue", definition.usePersonalQueue());
        if (definition.targetType() == RunningHubDefinition.TargetType.WORKFLOW) body.put("addMetadata", definition.addMetadata());
        if (definition.retainSeconds() != null) body.put("retainSeconds", definition.retainSeconds());
        String category = definition.targetType() == RunningHubDefinition.TargetType.WORKFLOW ? "workflow" : "ai-app";
        JsonNode response = json(post(origin, key, "/openapi/v2/run/" + category + "/" + definition.targetId(), body), true);
        String id = response.path("taskId").asText("");
        if (!id.matches("[A-Za-z0-9_-]{1,160}")) throw new Uncertain();
        return id;
    }

    public JsonNode query(String origin, String key, String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,160}")) throw new ProtocolFailure();
        return json(post(origin, key, "/openapi/v2/query", mapper.createObjectNode().put("taskId", taskId)), false);
    }

    public JsonNode metadata(String origin, String key, RunningHubDefinition.TargetType type, String targetId) {
        if (targetId == null || !targetId.matches("[0-9]{1,32}")) throw RunningHubDefinition.invalid("请输入真实工作流或应用 ID");
        if (type == RunningHubDefinition.TargetType.WORKFLOW) {
            JsonNode result = json(post(origin, key, "/api/openapi/getJsonApiFormat", mapper.createObjectNode()
                    .put("apiKey", key).put("workflowId", targetId)), false).path("data").path("prompt");
            if (!result.isTextual()) throw new ProtocolFailure();
            try { return mapper.readTree(result.asText()); }
            catch (RuntimeException invalid) { throw new ProtocolFailure(); }
        }
        // The upstream example also puts the key in query. We deliberately use only Bearer;
        // sites that require the query key can use the sanitized local import instead.
        return json(new Request.Builder().url(validatedOrigin(origin) + "/api/webapp/apiCallDemo?webappId=" + targetId)
                .header("Authorization", "Bearer " + key).get().build(), false).path("data");
    }

    public String upload(String origin, String key, Path path, String mime, RunningHubDefinition.ResourceFormat format) {
        try {
            long size = Files.size(path);
            if (size < 1 || size > MAX_UPLOAD_BYTES) throw new ProtocolFailure();
            RequestBody file = new RequestBody() {
                @Override public MediaType contentType() { return MediaType.parse(mime); }
                @Override public long contentLength() { return size; }
                @Override public boolean isOneShot() { return true; }
                @Override public void writeTo(BufferedSink sink) throws IOException {
                    try (InputStream input = Files.newInputStream(path)) {
                        byte[] buffer = new byte[8192];
                        long written = 0;
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            written += count;
                            if (written > size) throw new IOException("Archived input size changed");
                            sink.write(buffer, 0, count);
                        }
                        if (written != size) throw new IOException("Archived input size changed");
                    }
                }
            };
            MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM);
            boolean legacy = format == RunningHubDefinition.ResourceFormat.FILE_NAME;
            // The legacy upstream reads authorization fields before streaming its file part.
            if (legacy) body.addFormDataPart("apiKey", key).addFormDataPart("fileType", "input");
            body.addFormDataPart("file", "input." + extension(mime), file);
            Request request = new Request.Builder().url(validatedOrigin(origin) + (legacy
                    ? "/task/openapi/upload" : "/openapi/v2/media/upload/binary"))
                    .header("Authorization", "Bearer " + key).post(body.build()).build();
            JsonNode response = json(request, false);
            JsonNode data = response.path("data");
            String value = legacy ? data.path("fileName").asText(data.path("filename").asText(""))
                    : data.path("download_url").asText(data.path("downloadUrl").asText(""));
            if (value.isBlank() || value.length() > 4096 || value.contains(key)) throw new ProtocolFailure();
            if (!legacy) validateDownload(origin, value);
            return value;
        } catch (IOException failure) { throw new ProtocolFailure(); }
    }

    /** Result downloads carry no credentials, do not follow redirects, and close the response with the stream. */
    public MediaPayload download(String origin, String url) {
        validateDownload(origin, url);
        try {
            Response response = http.newCall(new Request.Builder().url(url).get().build()).execute();
            if (!response.isSuccessful() || response.body() == null) { response.close(); throw new ProtocolFailure(); }
            InputStream stream = new FilterInputStream(response.body().byteStream()) {
                @Override public void close() throws IOException { try { super.close(); } finally { response.close(); } }
            };
            return new MediaPayload(stream, response.header("Content-Type", "application/octet-stream"));
        } catch (IOException failure) { throw new ProtocolFailure(); }
    }

    public static void validateDownload(String origin, String url) {
        URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException invalid) { throw new ProtocolFailure(); }
        String host = uri.getHost();
        URI api = URI.create(validatedOrigin(origin));
        boolean local = local(api) && local(uri) && api.getPort() == uri.getPort();
        if (!(local || "https".equals(uri.getScheme()) && host != null && (uri.getPort() == -1 || uri.getPort() == 443))
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null || url.length() > 4096)
            throw new ProtocolFailure();
    }

    private Request post(String origin, String key, String path, JsonNode body) {
        return new Request.Builder().url(validatedOrigin(origin) + path).header("Authorization", "Bearer " + key)
                .header("Accept", "application/json").post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), MediaType.parse("application/json"))).build();
    }

    private JsonNode json(Request request, boolean submission) {
        try (Response response = http.newCall(request).execute()) {
            if (response.code() >= 400 && response.code() < 500) throw new Rejected();
            if (!response.isSuccessful() || response.body() == null) throw new ProtocolFailure();
            byte[] bytes = response.body().byteStream().readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw new ProtocolFailure();
            JsonNode result = mapper.readTree(bytes);
            if (!result.isObject()) throw new ProtocolFailure();
            if (result.hasNonNull("code")) {
                if (!result.path("code").isIntegralNumber()) throw new ProtocolFailure();
                int code = result.path("code").asInt();
                if (code != 0 && code != 200) throw new Rejected();
            }
            if (submission && "FAILED".equals(result.path("status").asText())) throw new Rejected();
            return result;
        } catch (Rejected failure) { throw failure; }
        catch (IOException | RuntimeException failure) {
            if (submission) throw new Uncertain();
            throw new ProtocolFailure();
        }
    }

    private static boolean local(URI uri) { return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0; }
    private static String extension(String mime) {
        return switch (mime) {
            case "image/png" -> "png"; case "image/jpeg" -> "jpg"; case "image/webp" -> "webp";
            case "audio/mpeg" -> "mp3"; case "audio/wav", "audio/x-wav" -> "wav"; case "audio/flac" -> "flac";
            case "video/mp4" -> "mp4"; default -> throw new ProtocolFailure();
        };
    }
    public static final class Rejected extends RuntimeException { public Rejected() { super("RunningHub rejected the request"); } }
    public static final class Uncertain extends RuntimeException { public Uncertain() { super("RunningHub submission outcome is unknown"); } }
    public static final class ProtocolFailure extends RuntimeException { public ProtocolFailure() { super("RunningHub response or media is unavailable"); } }
}
