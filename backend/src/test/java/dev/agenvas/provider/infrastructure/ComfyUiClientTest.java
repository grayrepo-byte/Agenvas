package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Local fake-protocol checks; this is not evidence of a real ComfyUI template or GPU. */
class ComfyUiClientTest {

    private static final String SYNTHETIC_PROXY_PREFIX = "/proxy/synthetic-path-key";
    private static final String SYNTHETIC_PROXY_PROMPT_ID = "2100000000000000123";
    private static final int MAX_PROMPT_ID_CHARACTERS = 240;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ComfyUiClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new ComfyUiClient(new ComfyUiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort()), mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void submitsOnlyToFixedOriginAndQueriesSamePromptWithoutResubmission() {
        UUID promptId = UUID.randomUUID();
        AtomicInteger submits = new AtomicInteger();
        AtomicInteger histories = new AtomicInteger();
        server.createContext("/prompt", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(body.path("client_id").asText()).isEqualTo(promptId.toString());
            assertThat(body.path("prompt_id").asText()).isEqualTo(promptId.toString());
            assertThat(body.path("prompt").path("1").path("class_type").asText())
                    .isEqualTo("TrustedFixture");
            submits.incrementAndGet();
            respond(exchange, 200, "{\"prompt_id\":\"" + promptId + "\",\"number\":0}");
        });
        server.createContext("/history/", exchange -> {
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/history/" + promptId);
            histories.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        JsonNode fixed = mapper.readTree("{\"1\":{\"class_type\":\"TrustedFixture\",\"inputs\":{}}}");
        assertThat(client.submit(fixed, promptId)).isEqualTo(promptId.toString());
        assertThat(client.imageStatus(promptId.toString(), "9"))
                .isInstanceOf(ComfyUiHistory.Pending.class);
        assertThat(client.imageStatus(promptId.toString(), "9"))
                .isInstanceOf(ComfyUiHistory.Pending.class);
        assertThat(submits).hasValue(1);
        assertThat(histories).hasValue(2);
    }

    @Test
    void acceptsProxyAssignedPromptIdAndQueriesItWithoutResubmission() {
        UUID requestKey = UUID.randomUUID();
        AtomicInteger submits = new AtomicInteger();
        AtomicInteger histories = new AtomicInteger();
        client = new ComfyUiClient(new ComfyUiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + SYNTHETIC_PROXY_PREFIX), mapper);
        server.createContext(SYNTHETIC_PROXY_PREFIX + "/prompt", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(body.path("client_id").asText()).isEqualTo(requestKey.toString());
            assertThat(body.path("prompt_id").asText()).isEqualTo(requestKey.toString());
            submits.incrementAndGet();
            respond(exchange, 200, "{\"prompt_id\":\"" + SYNTHETIC_PROXY_PROMPT_ID + "\"}");
        });
        server.createContext(SYNTHETIC_PROXY_PREFIX + "/history/", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getPath())
                    .isEqualTo(SYNTHETIC_PROXY_PREFIX + "/history/" + SYNTHETIC_PROXY_PROMPT_ID);
            histories.incrementAndGet();
            respond(exchange, 200, "{\"" + SYNTHETIC_PROXY_PROMPT_ID + "\":{"
                    + "\"status\":{\"completed\":true,\"status_str\":\"success\"},"
                    + "\"outputs\":{\"9\":{\"images\":[{\"filename\":\"render.png\","
                    + "\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        });
        try {
            var promptId = client.submit(mapper.createObjectNode(), requestKey);
            assertThat(promptId).isEqualTo(SYNTHETIC_PROXY_PROMPT_ID);
            assertThat(client.imageStatus(promptId, "9"))
                    .isEqualTo(new ComfyUiHistory.Ready("render.png"));
            assertThat(client.imageStatus(promptId, "9"))
                    .isEqualTo(new ComfyUiHistory.Ready("render.png"));
            assertThat(histories).hasValue(2);
        } finally {
            assertThat(submits).hasValue(1);
        }
    }

    @Test
    void acceptsServerAssignedUuidAndQueriesAcknowledgedPrompt() {
        UUID requestKey = UUID.randomUUID();
        String acknowledgedPromptId = UUID.randomUUID().toString();
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger histories = new AtomicInteger();
        server.createContext("/prompt", exchange -> {
            JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(body.path("prompt_id").asText()).isEqualTo(requestKey.toString());
            submissions.incrementAndGet();
            respond(exchange, 200, "{\"prompt_id\":\"" + acknowledgedPromptId + "\"}");
        });
        server.createContext("/history/", exchange -> {
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/history/" + acknowledgedPromptId);
            histories.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        String promptId = client.submit(mapper.createObjectNode(), requestKey);
        assertThat(promptId).isEqualTo(acknowledgedPromptId);
        assertThat(client.history(promptId).isEmpty()).isTrue();
        assertThat(submissions).hasValue(1);
        assertThat(histories).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"prompt_id\":null}", "{\"prompt_id\":1}",
            "{\"prompt_id\":true}", "{\"prompt_id\":[]}", "{\"prompt_id\":{}}"})
    void missingOrNonStringAcknowledgementIsNeverSubmittedAgain(String response) {
        AtomicInteger submissions = new AtomicInteger();
        server.createContext("/prompt", exchange -> {
            submissions.incrementAndGet();
            respond(exchange, 200, response);
        });
        assertThatThrownBy(() -> client.submit(mapper.createObjectNode(), UUID.randomUUID()))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("ComfyUI did not return a valid prompt_id");
        assertThat(submissions).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "task.id", "../other", "task/id", "task?x=1",
            "task#fragment", "task%2fother", "task\\id", "task\nid", "task\u0000id"})
    void unsafeAcknowledgementIsNeverSubmittedAgainOrUsedInHistory(String promptId) {
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger histories = new AtomicInteger();
        String response = mapper.createObjectNode().put("prompt_id", promptId).toString();
        server.createContext("/prompt", exchange -> {
            submissions.incrementAndGet();
            respond(exchange, 200, response);
        });
        server.createContext("/history/", exchange -> {
            histories.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        assertThatThrownBy(() -> client.submit(mapper.createObjectNode(), UUID.randomUUID()))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("ComfyUI did not return a valid prompt_id");
        assertThatThrownBy(() -> client.history(promptId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(submissions).hasValue(1);
        assertThat(histories).hasValue(0);
    }

    @Test
    void overlongAcknowledgementIsNeverSubmittedAgainOrUsedInHistory() {
        String promptId = "a".repeat(MAX_PROMPT_ID_CHARACTERS + 1);
        AtomicInteger submissions = new AtomicInteger();
        server.createContext("/prompt", exchange -> {
            submissions.incrementAndGet();
            respond(exchange, 200, mapper.createObjectNode().put("prompt_id", promptId).toString());
        });
        assertThatThrownBy(() -> client.submit(mapper.createObjectNode(), UUID.randomUUID()))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThatThrownBy(() -> client.history(promptId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(submissions).hasValue(1);
    }

    @Test
    void acceptsOpaquePromptIdAtPersistenceLengthLimit() {
        String promptId = "task_" + "a".repeat(MAX_PROMPT_ID_CHARACTERS - "task_".length());
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger histories = new AtomicInteger();
        server.createContext("/prompt", exchange -> {
            submissions.incrementAndGet();
            respond(exchange, 200, mapper.createObjectNode().put("prompt_id", promptId).toString());
        });
        server.createContext("/history/", exchange -> {
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/history/" + promptId);
            histories.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        String acknowledged = client.submit(mapper.createObjectNode(), UUID.randomUUID());
        assertThat(acknowledged).isEqualTo(promptId);
        assertThat(client.history(acknowledged).isEmpty()).isTrue();
        assertThat(submissions).hasValue(1);
        assertThat(histories).hasValue(1);
    }

    @Test
    void readsOnlyExpectedOutputNodeFromCompletedOriginalPrompt() {
        UUID promptId = UUID.randomUUID();
        server.createContext("/history/", exchange -> respond(exchange, 200,
                "{\"" + promptId + "\":{\"status\":{\"completed\":true,"
                        + "\"status_str\":\"success\"},\"outputs\":{\"9\":{"
                        + "\"images\":[{\"filename\":\"render.png\","
                        + "\"subfolder\":\"\",\"type\":\"output\"}]}}}}"));
        assertThat(client.imageStatus(promptId.toString(), "9"))
                .isEqualTo(new ComfyUiHistory.Ready("render.png"));
    }

    @Test
    void uploadsServerNamedImageAndStreamsOneSanitizedOutput() throws IOException {
        UUID requestId = UUID.randomUUID();
        server.createContext("/upload/image", exchange -> {
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/upload/image");
            assertThat(exchange.getRequestBody().readAllBytes().length).isGreaterThan(4);
            respond(exchange, 200, "{\"name\":\"agenvas-" + requestId
                    + ".png\",\"type\":\"input\",\"subfolder\":\"\"}");
        });
        server.createContext("/view", exchange -> {
            assertThat(exchange.getRequestURI().getQuery())
                    .isEqualTo("filename=rendered_01.png&type=output&subfolder=");
            respond(exchange, 200, "PNG-BYTES");
        });
        assertThat(client.uploadImage(requestId, new byte[] {1, 2, 3}, "png"))
                .isEqualTo("agenvas-" + requestId + ".png");
        try (var output = client.output("rendered_01.png")) {
            assertThat(output.readAllBytes()).isEqualTo("PNG-BYTES".getBytes(StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> client.output("../secrets"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesRedirectsForSubmissionsAndOutputDownloads() throws IOException {
        HttpServer redirectTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger followed = new AtomicInteger();
        redirectTarget.createContext("/private", exchange -> {
            followed.incrementAndGet();
            respond(exchange, 200, "private bytes");
        });
        redirectTarget.start();
        try {
            String target = "http://127.0.0.1:" + redirectTarget.getAddress().getPort()
                    + "/private";
            server.createContext("/prompt", exchange -> {
                exchange.getResponseHeaders().add("Location", target);
                respond(exchange, 302, "redirect");
            });
            server.createContext("/view", exchange -> {
                exchange.getResponseHeaders().add("Location", target);
                respond(exchange, 302, "redirect");
            });
            assertThatThrownBy(() -> client.submit(mapper.createObjectNode(), UUID.randomUUID()))
                    .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
            assertThatThrownBy(() -> client.output("rendered_01.png"))
                    .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
            assertThat(followed).hasValue(0);

            server.removeContext("/prompt");
            server.createContext("/prompt", exchange -> respond(exchange, 503, "unavailable"));
            assertThatThrownBy(() -> client.submit(mapper.createObjectNode(), UUID.randomUUID()))
                    .isInstanceOf(ComfyUiClient.TransportFailure.class);
        } finally {
            redirectTarget.stop(0);
        }
    }

    @Test
    void acceptsRemoteHttpsProxyBaseWithoutNetworkAccess() {
        assertThat(ComfyUiClient.checkedOrigin("https://comfy.example.com/proxy/synthetic-key/"))
                .isEqualTo(java.net.URI.create("https://comfy.example.com/proxy/synthetic-key"));
    }

    @Test
    void rejectsUnsafeSchemesAddressesAndPathsAtConstruction() {
        for (String endpoint : new String[] {"http://localhost:8188", "http://127.0.0.1:8188/../path",
                "http://user@127.0.0.1:8188", "http://127.0.0.1:8188?x=1",
                "http://169.254.169.254:80/metadata", "http://8.8.8.8:8188",
                "file:///etc/passwd", "https://169.254.169.254/proxy/key",
                "https://comfy.example.com/proxy/%2e%2e", "https://comfy.example.com//proxy",
                "https://comfy.example.com/proxy/key#fragment"}) {
            assertThatThrownBy(() -> new ComfyUiClient(new ComfyUiProperties(endpoint), mapper))
                    .as(endpoint).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void retainsProxyPrefixForEveryRouteAndRedactsItsCredentials() throws Exception {
        String prefix = "/proxy/synthetic-path-key";
        client = new ComfyUiClient(new ComfyUiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + prefix + "/"), mapper);
        UUID id = UUID.randomUUID();
        var routes = new java.util.ArrayList<String>();
        server.createContext(prefix, exchange -> {
            String path = exchange.getRequestURI().getPath();
            routes.add(path);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getRequestBody().readAllBytes();
            if (path.endsWith("/upload/image")) respond(exchange, 200,
                    "{\"name\":\"input.png\",\"type\":\"input\"}");
            else if (path.endsWith("/prompt")) respond(exchange, 200,
                    "{\"prompt_id\":\"" + id + "\",\"echo\":\"synthetic-path-key\"}");
            else if (path.endsWith("/view")) respond(exchange, 200, "output");
            else respond(exchange, 200, "{}");
        });
        var captured = new java.util.concurrent.atomic.AtomicReference<java.util.List<dev.agenvas.shared.http.DebugHttpCapture.Exchange>>();
        try (var scope = dev.agenvas.shared.http.DebugHttpCapture.open(captured::set)) {
            assertThat(client.uploadImage(id, new byte[] {1}, "png")).isEqualTo("input.png");
            assertThat(client.submit(mapper.createObjectNode(), id)).isEqualTo(id.toString());
            client.history(id.toString());
            try (var output = client.output("output.png")) { assertThat(output.readAllBytes()).isNotEmpty(); }
        }
        assertThat(routes).containsExactly(prefix + "/upload/image", prefix + "/prompt",
                prefix + "/history/" + id, prefix + "/view");
        assertThat(captured.get()).hasSize(4);
        assertThat(captured.get().toString()).doesNotContain("synthetic-path-key");
    }

    @Test
    void doesNotRetryAmbiguousSubmissionEvenWithRetryAfterZero() {
        AtomicInteger submits = new AtomicInteger();
        server.createContext("/prompt", exchange -> {
            submits.incrementAndGet();
            exchange.getResponseHeaders().set("Retry-After", "0");
            respond(exchange, 503, "ambiguous");
        });
        assertThatThrownBy(() -> client.submit(mapper.createObjectNode(), UUID.randomUUID()))
                .isInstanceOf(ComfyUiClient.TransportFailure.class);
        assertThat(submits).hasValue(1);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
