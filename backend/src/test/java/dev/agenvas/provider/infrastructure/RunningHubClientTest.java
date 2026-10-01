package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.provider.domain.RunningHubDefinition;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RunningHubClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private RunningHubDefinition definition(RunningHubDefinition.TargetType type) {
        return new RunningHubDefinition(1, "V2", type, "123", List.of(), List.of(), List.of(), "default", false, false, null, null);
    }
    @Test void workflowAndAppUseSeparatePathsBearerAndBooleanOptions() throws Exception {
        try (var fixture = new Server(200, "{\"taskId\":\"task-1\",\"status\":\"QUEUED\"}")) {
            var client = new RunningHubClient(mapper);
            for (var type : RunningHubDefinition.TargetType.values()) {
                assertThat(client.submit(fixture.origin(), "test-key", definition(type), mapper.createArrayNode())).isEqualTo("task-1");
                assertThat(fixture.path.get()).isEqualTo("/openapi/v2/run/" + (type == RunningHubDefinition.TargetType.WORKFLOW ? "workflow" : "ai-app") + "/123");
                var body = mapper.readTree(fixture.body.get());
                assertThat(body.path("usePersonalQueue").isBoolean()).isTrue();
                assertThat(body.has("addMetadata")).isEqualTo(type == RunningHubDefinition.TargetType.WORKFLOW);
                assertThat(fixture.authorization.get()).isEqualTo("Bearer test-key");
            }
            assertThat(fixture.calls).hasValue(2);
        }
    }
    @Test void uncertain503AndRedirectNeverResubmit() throws Exception {
        for (int status : new int[]{503, 307}) try (var fixture = new Server(status, "{}")) {
            assertThatThrownBy(() -> new RunningHubClient(mapper).submit(fixture.origin(), "test-key", definition(RunningHubDefinition.TargetType.WORKFLOW), mapper.createArrayNode())).isInstanceOf(RunningHubClient.Uncertain.class);
            assertThat(fixture.calls).hasValue(1);
        }
    }
    @Test void rejectionAndMalformedAcceptedResponseAreDifferentOutcomes() throws Exception {
        try (var fixture = new Server(400, "{}")) {
            assertThatThrownBy(() -> new RunningHubClient(mapper).submit(fixture.origin(), "test-key", definition(RunningHubDefinition.TargetType.AI_APP), mapper.createArrayNode())).isInstanceOf(RunningHubClient.Rejected.class);
        }
        try (var fixture = new Server(200, "{\"status\":\"RUNNING\"}")) {
            assertThatThrownBy(() -> new RunningHubClient(mapper).submit(fixture.origin(), "test-key", definition(RunningHubDefinition.TargetType.AI_APP), mapper.createArrayNode())).isInstanceOf(RunningHubClient.Uncertain.class);
        }
    }
    @Test void queryUsesSavedTaskIdAndAppDiscoveryNeverPutsKeyInUrl() throws Exception {
        try (var fixture = new Server(200, "{\"status\":\"RUNNING\",\"data\":{\"nodeInfoList\":[]}}")) {
            var client = new RunningHubClient(mapper);
            client.query(fixture.origin(), "test-key", "original-task");
            assertThat(mapper.readTree(fixture.body.get()).path("taskId").asText()).isEqualTo("original-task");
            client.metadata(fixture.origin(), "test-key", RunningHubDefinition.TargetType.AI_APP, "123");
            assertThat(fixture.path.get()).isEqualTo("/api/webapp/apiCallDemo?webappId=123").doesNotContain("test-key");
        }
    }
    @Test void workflowPromptStringIsDecodedTwice() throws Exception {
        try (var fixture = new Server(200, "{\"code\":0,\"data\":{\"prompt\":\"{\\\"3\\\":{\\\"inputs\\\":{\\\"seed\\\":7}}}\"}}")) {
            assertThat(new RunningHubClient(mapper).metadata(fixture.origin(), "test-key", RunningHubDefinition.TargetType.WORKFLOW, "123").path("3").path("inputs").path("seed").asInt()).isEqualTo(7);
        }
    }
    @Test void uploadAcceptsDocumentedSuccessVariantsAndKeepsResourceSemantics() throws Exception {
        var path = Files.createTempFile("rh-input", ".png");
        try {
            Files.write(path, new byte[]{1, 2, 3});
            for (String field : List.of("fileName", "filename")) try (var fixture = new Server(200, "{\"code\":0,\"data\":{\"" + field + "\":\"input/file.png\"}}")) {
                assertThat(new RunningHubClient(mapper).upload(fixture.origin(), "test-key", path, "image/png", RunningHubDefinition.ResourceFormat.FILE_NAME)).isEqualTo("input/file.png");
                assertThat(fixture.path.get()).isEqualTo("/task/openapi/upload");
                assertThat(fixture.body.get().indexOf("name=\"apiKey\"")).isLessThan(fixture.body.get().indexOf("name=\"file\""));
            }
            try (var fixture = new Server(200, "{}")) {
                fixture.reply.set("{\"code\":200,\"data\":{\"download_url\":\"" + fixture.origin() + "/file.png\",\"filename\":\"file.png\"}}");
                assertThat(new RunningHubClient(mapper).upload(fixture.origin(), "test-key", path, "image/png", RunningHubDefinition.ResourceFormat.URL)).isEqualTo(fixture.origin() + "/file.png");
                assertThat(fixture.path.get()).isEqualTo("/openapi/v2/media/upload/binary");
            }
        } finally { Files.deleteIfExists(path); }
    }
    @Test void endpointAndDownloadBoundariesRejectUnsafeUrlShapesAndCredentialRedirects() {
        for (String url : List.of("https://www.runninghub.ai/openapi", "https://user@www.runninghub.ai", "http://localhost:80", "https://www.runninghub.ai?apiKey=secret"))
            assertThatThrownBy(() -> RunningHubClient.validatedOrigin(url)).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        for (String url : List.of("http://169.254.169.254/file", "https://user@www.runninghub.ai/file", "https://example.com/file#fragment"))
            assertThatThrownBy(() -> RunningHubClient.validateDownload("https://www.runninghub.ai", url)).isInstanceOf(RunningHubClient.ProtocolFailure.class);
    }
    @Test void adminOriginsAndMediaUrlsDoNotUseADomainAllowlist() {
        assertThat(RunningHubClient.validatedOrigin("https://custom-api.example.com")).isEqualTo("https://custom-api.example.com");
        for (String host : List.of("rh-hk-images-switch.xiaoyaoyou.com", "custom-cdn.example.com"))
            RunningHubClient.validateDownload("https://www.runninghub.ai", "https://" + host + "/input/example.png");
    }
    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> path = new AtomicReference<>(), body = new AtomicReference<>(), authorization = new AtomicReference<>(), reply;
        Server(int status, String response) throws IOException {
            reply = new AtomicReference<>(response);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet(); path.set(exchange.getRequestURI().toString()); body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.getResponseHeaders().set("Location", origin() + "/redirected");
                byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start();
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
