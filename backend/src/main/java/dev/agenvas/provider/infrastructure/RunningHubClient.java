package dev.agenvas.provider.infrastructure;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.ProviderFailure;
import dev.agenvas.provider.domain.ProviderFailure.Category;
import dev.agenvas.provider.domain.ProviderFailure.Stage;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
    private static final String APP_DETAIL_PATH = "/api/webapp/detail";
    private static final String WORKFLOW_DETAIL_PATH = "/api/portal/workflow/detail";
    private static final int MAX_TARGET_NAME_LENGTH = 160;
    private static final Duration NAME_LOOKUP_TIMEOUT = Duration.ofSeconds(5);
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
        catch (IllegalArgumentException invalid) { throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-client.runninghub-site-is-invalid")); }
        boolean local = local(uri);
        if (!(local || "https".equals(uri.getScheme()) && uri.getHost() != null && (uri.getPort() == -1 || uri.getPort() == 443))
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())))
            throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-client.please-enter-the-https-api-root-address-without-paths-credentials"));
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

    /** Display metadata is separate from the retained input contract and cannot authorize execution. */
    public record Metadata(JsonNode source, String targetName) {}

    public Metadata metadata(String origin, String key, RunningHubDefinition.TargetType type, String targetId) {
        if (targetId == null || !targetId.matches("[0-9]{1,32}")) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-client.please-enter-a-real-workflow-or-application-id"));
        if (type == RunningHubDefinition.TargetType.WORKFLOW) {
            JsonNode result = json(post(origin, key, "/api/openapi/getJsonApiFormat", mapper.createObjectNode()
                    .put("apiKey", key).put("workflowId", targetId)), false).path("data").path("prompt");
            if (!result.isTextual()) throw new ProtocolFailure();
            JsonNode source;
            try { source = mapper.readTree(result.asText()); }
            catch (RuntimeException invalid) { throw new ProtocolFailure(); }
            return new Metadata(source, workflowName(origin, key, targetId));
        }
        // Public app pages expose the input contract without credentials. The older demo
        // endpoint can require a query key even with Bearer, so prefer the public detail.
        try {
            JsonNode detail = json(post(origin, APP_DETAIL_PATH, mapper.createObjectNode().put("webappId", targetId)), false).path("data");
            if (!targetId.equals(detail.path("id").asText())) throw new ProtocolFailure();
            return new Metadata(appInputs(detail.path("inputNodes")), targetName(detail.path("name"), key));
        } catch (Rejected | ProtocolFailure unavailable) {
            // Private apps and compatible sites may only expose the authenticated demo.
            // This is read-only discovery; neither request can submit a generation.
            JsonNode demo = json(new Request.Builder().url(validatedOrigin(origin) + "/api/webapp/apiCallDemo?webappId=" + targetId)
                    .header("Authorization", "Bearer " + key).get().build(), false).path("data");
            return new Metadata(appInputs(demo.path("nodeInfoList")), targetName(demo.path("webappName"), key));
        }
    }

    private String workflowName(String origin, String key, String targetId) {
        // The public page detail is optional: private/compatible sites may expose the
        // input contract without a name. Limit its latency and never send credentials.
        try {
            JsonNode detail = json(post(origin, WORKFLOW_DETAIL_PATH, mapper.createObjectNode().put("workflowId", targetId)),
                    false, NAME_LOOKUP_TIMEOUT).path("data");
            return targetId.equals(detail.path("id").asText()) ? targetName(detail.path("name"), key) : null;
        } catch (Rejected | ProtocolFailure unavailable) { return null; }
    }

    private String targetName(JsonNode value, String key) {
        if (!value.isTextual()) return null;
        String name = value.asText().strip();
        if (name.isBlank() || key != null && !key.isEmpty() && name.contains(key)) return null;
        if (name.length() <= MAX_TARGET_NAME_LENGTH) return name;
        int end = MAX_TARGET_NAME_LENGTH;
        if (Character.isHighSurrogate(name.charAt(end - 1))) end--;
        return name.substring(0, end);
    }

    /** Retain only input candidates, never owner information or credential-bearing curl demos. */
    private JsonNode appInputs(JsonNode nodes) {
        if (!nodes.isArray() || nodes.isEmpty()) throw new ProtocolFailure();
        return mapper.createObjectNode().set("nodeInfoList", nodes);
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
            if (!response.isSuccessful() || response.body() == null) {
                int status = response.code();
                response.close();
                var diagnostic = new ProviderFailure(Stage.DOWNLOAD,
                        Set.of(403, 404, 410).contains(status) ? Category.DOWNLOAD_EXPIRED : Category.UNAVAILABLE,
                        status, null, null, null);
                if (diagnostic.category() == Category.DOWNLOAD_EXPIRED) throw new DownloadExpired(diagnostic);
                throw new ProtocolFailure(diagnostic);
            }
            InputStream stream = new FilterInputStream(response.body().byteStream()) {
                @Override public void close() throws IOException { try { super.close(); } finally { response.close(); } }
            };
            return new MediaPayload(stream, response.header("Content-Type", "application/octet-stream"));
        } catch (IOException failure) { throw new ProtocolFailure(new ProviderFailure(Stage.DOWNLOAD, Category.UNAVAILABLE, null, null, null, null)); }
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
        return post(origin, path, body).newBuilder().header("Authorization", "Bearer " + key).build();
    }

    private Request post(String origin, String path, JsonNode body) {
        return new Request.Builder().url(validatedOrigin(origin) + path)
                .header("Accept", "application/json").post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), MediaType.parse("application/json"))).build();
    }

    private JsonNode json(Request request, boolean submission) {
        return json(request, submission, CALL_TIMEOUT);
    }

    private JsonNode json(Request request, boolean submission, Duration timeout) {
        var call = http.newCall(request);
        call.timeout().timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        Stage stage = stage(request);
        try (Response response = call.execute()) {
            if (response.body() == null) throw new ProtocolFailure(diagnostic(stage, response.code(), null, request));
            byte[] bytes = response.body().byteStream().readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw new ProtocolFailure(diagnostic(stage, response.code(), null, request));
            JsonNode result;
            try { result = mapper.readTree(bytes); }
            catch (RuntimeException invalid) { result = null; }
            var diagnostic = diagnostic(stage, response.code(), result, request);
            if (!response.isSuccessful()) {
                // Gateways may return throttling/timeouts after forwarding the paid request.
                if (submission && !Set.of(400, 401, 402, 403, 404, 405, 410, 413, 422).contains(response.code()))
                    throw new Uncertain(diagnostic);
                if (response.code() >= 400 && response.code() < 500) throw new Rejected(diagnostic);
                throw new ProtocolFailure(diagnostic);
            }
            if (result == null || !result.isObject()) throw new ProtocolFailure(diagnostic);
            if (result.hasNonNull("code")) {
                if (!result.path("code").isIntegralNumber() || !result.path("code").canConvertToInt()) throw new ProtocolFailure(diagnostic);
                int code = result.path("code").asInt();
                if (code == 804 || code == 813) {
                    if (!result.hasNonNull("status")) ((ObjectNode) result).put("status", code == 804 ? "RUNNING" : "QUEUED");
                } else if (code != 0 && code != 200) {
                    if (submission && !definiteRejection(diagnostic.category())) throw new Uncertain(diagnostic);
                    throw new Rejected(diagnostic);
                }
            }
            if (submission && "FAILED".equals(result.path("status").asText())) throw new Rejected(diagnostic);
            return result;
        } catch (Rejected | Uncertain failure) { throw failure; }
        catch (ProtocolFailure failure) {
            if (submission) throw new Uncertain(failure.diagnostic());
            throw failure;
        } catch (IOException | RuntimeException failure) {
            var diagnostic = new ProviderFailure(stage, Category.UNAVAILABLE, null, null, null, null);
            if (submission) throw new Uncertain(diagnostic);
            throw new ProtocolFailure(diagnostic);
        }
    }

    private static Stage stage(Request request) {
        String path = request.url().encodedPath();
        if (path.contains("/upload")) return Stage.UPLOAD;
        if (path.startsWith("/openapi/v2/run/")) return Stage.SUBMIT;
        if (path.equals("/openapi/v2/query")) return Stage.POLL;
        return Stage.DISCOVERY;
    }

    private static boolean definiteRejection(Category category) {
        return Set.of(Category.AUTHENTICATION, Category.BALANCE, Category.PARAMETER, Category.UPLOAD).contains(category);
    }

    /** Read a small allowlist of typed locations; never mine messages or nested arbitrary data for diagnostics. */
    private static ProviderFailure diagnostic(Stage stage, int status, JsonNode body, Request request) {
        JsonNode value = body == null ? null : body.get("errorCode");
        Long code = numericCode(value);
        if (code == null || code == 0 || code == 200) code = body == null ? null : numericCode(body.get("code"));
        if (code != null && (code == 0 || code == 200)) code = null;
        String authorization = request.header("Authorization");
        String key = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        if (code != null && key != null && !key.isEmpty() && code.toString().contains(key)) code = null;
        String identifier = value != null && value.isTextual() ? value.asText() : "";
        Category category = category(stage, status, code, identifier, body != null && "FAILED".equals(body.path("status").asText()));
        JsonNode location = body;
        if (body != null && body.path("error").isObject()) location = body.path("error");
        String node = safeLocation(location, "nodeId", "[0-9]{1,32}", request);
        String field = safeLocation(location, "fieldName", "[A-Za-z_][A-Za-z0-9_.]{0,79}", request);
        if (field != null && field.matches("(?i).*(apikey|api_key|authorization|token|secret|password).*")) field = null;
        return new ProviderFailure(stage, category, status, code, node, field);
    }

    private static String safeLocation(JsonNode body, String field, String pattern, Request request) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !(value.isTextual() || value.isIntegralNumber())) return null;
        String text = value.asText();
        String authorization = request.header("Authorization");
        String key = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        return text.matches(pattern) && (key == null || key.isEmpty() || !text.contains(key)) ? text : null;
    }

    private static Long numericCode(JsonNode value) {
        if (value == null || !(value.isIntegralNumber() || value.isTextual() && value.asText().matches("[0-9]{1,9}"))) return null;
        try {
            long result = Long.parseLong(value.asText());
            return result >= 0 && result <= 999_999_999L ? result : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    private static Category category(Stage stage, int status, Long code, String identifier, boolean failed) {
        long number = code == null ? switch (identifier) {
            case "PARAMS_INVALID" -> 301; case "WORKFLOW_NOT_EXISTS" -> 380; case "TOKEN_INVALID" -> 412;
            case "TASK_INSTANCE_MAXED" -> 415; case "TASK_CREATE_FAILED_BY_NOT_ENOUGH_WALLET" -> 416;
            case "TASK_QUEUE_MAXED" -> 421; case "TASK_NOT_FOUNED" -> 423; case "VALIDATE_PROMPT_FAILED" -> 433;
            case "TASK_USER_EXCLAPI_INSTANCE_NOT_FOUND" -> 435; case "APIKEY_UNSUPPORTED_FREE_USER" -> 801;
            case "APIKEY_UNAUTHORIZED" -> 802; case "APIKEY_INVALID_NODE_INFO" -> 803;
            case "APIKEY_TASK_STATUS_ERROR" -> 805; case "APIKEY_USER_NOT_FOUND" -> 806;
            case "APIKEY_TASK_NOT_FOUND" -> 807; case "APIKEY_UPLOAD_FAILED" -> 808;
            case "APIKEY_FILE_SIZE_EXCEEDED" -> 809; case "WORKFLOW_NOT_SAVED_OR_NOT_RUNNING" -> 810;
            case "CORPAPIKEY_INVALID" -> 811; case "CORPAPIKEY_INSUFFICIENT_FUNDS" -> 812;
            case "WEBAPP_NOT_EXISTS" -> 901; default -> -1;
        } : code;
        if (Set.of(416L, 812L).contains(number) || identifier.equals("CORPAPIKEY_INSUFFICIENT_FUNDS")
                || identifier.equals("TASK_CREATE_FAILED_BY_NOT_ENOUGH_WALLET") || status == 402) return Category.BALANCE;
        if (Set.of(412L, 801L, 802L, 806L, 811L, 1002L, 1014L).contains(number)
                || Set.of("TOKEN_INVALID", "APIKEY_UNAUTHORIZED", "CORPAPIKEY_INVALID").contains(identifier)
                || status == 401 || status == 403) return Category.AUTHENTICATION;
        if (Set.of(415L, 421L, 1003L, 1011L).contains(number) || status == 429) return Category.RATE_LIMIT;
        if (Set.of(301L, 380L, 433L, 435L, 803L, 809L, 810L, 901L, 1001L, 1007L, 1008L, 1009L, 1101L, 1501L, 1505L).contains(number)
                || Set.of("PARAMS_INVALID", "APIKEY_INVALID_NODE_INFO", "VALIDATE_PROMPT_FAILED").contains(identifier)
                || status == 400 || status == 422) return Category.PARAMETER;
        if (number == 808 || number == 1013 || identifier.equals("APIKEY_UPLOAD_FAILED") || stage == Stage.UPLOAD) return Category.UPLOAD;
        if (Set.of(423L, 807L, 1004L).contains(number)) return Category.DOWNLOAD_EXPIRED;
        if (Set.of(805L, 1006L, 1015L, 1504L).contains(number) || failed) return Category.GENERATION;
        return Category.UNAVAILABLE;
    }

    public ProviderFailure generationFailure(JsonNode response, String key) {
        // V2 failure details use the same top-level fields; no credential-bearing message is copied.
        return diagnostic(Stage.POLL, 200, response, new Request.Builder().url("https://www.runninghub.ai/openapi/v2/query")
                .header("Authorization", "Bearer " + key).build());
    }

    private static boolean local(URI uri) { return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0; }
    private static String extension(String mime) {
        return switch (mime) {
            case "image/png" -> "png"; case "image/jpeg" -> "jpg"; case "image/webp" -> "webp";
            case "audio/mpeg" -> "mp3"; case "audio/wav", "audio/x-wav" -> "wav"; case "audio/flac" -> "flac";
            case "video/mp4" -> "mp4"; default -> throw new ProtocolFailure();
        };
    }
    public abstract static class Failure extends RuntimeException {
        private final ProviderFailure diagnostic;
        protected Failure(String message, ProviderFailure diagnostic) { super(message); this.diagnostic = diagnostic; }
        public ProviderFailure diagnostic() { return diagnostic; }
        public String code() { return diagnostic.code(); }
    }
    public static final class Rejected extends Failure {
        public Rejected() { this(new ProviderFailure(Stage.SUBMIT, Category.GENERATION, null, null, null, null)); }
        public Rejected(ProviderFailure diagnostic) { super("RunningHub rejected the request", diagnostic); }
    }
    public static final class Uncertain extends Failure {
        public Uncertain() { this(new ProviderFailure(Stage.SUBMIT, Category.UNAVAILABLE, null, null, null, null)); }
        public Uncertain(ProviderFailure diagnostic) { super("RunningHub submission outcome is unknown", diagnostic); }
    }
    public static class ProtocolFailure extends Failure {
        public ProtocolFailure() { this(new ProviderFailure(Stage.POLL, Category.PROTOCOL, null, null, null, null)); }
        public ProtocolFailure(ProviderFailure diagnostic) { super("RunningHub response or media is unavailable", diagnostic); }
    }
    public static final class DownloadExpired extends ProtocolFailure {
        public DownloadExpired(ProviderFailure diagnostic) { super(diagnostic); }
    }
    public static final class ResultIdentityChanged extends ProtocolFailure {
        @Override public String code() { return dev.agenvas.shared.error.ProviderFailureCodes.RUNNINGHUB_RESULT_IDENTITY_CHANGED; }
    }
}
