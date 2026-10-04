package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import okhttp3.Dns;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Local fake-protocol checks; this is not evidence of a real ComfyUI template or GPU. */
class ComfyUiClientTest {

    private static final String SYNTHETIC_PROXY_PREFIX = "/proxy/synthetic-path-key";
    private static final String SYNTHETIC_PROXY_PROMPT_ID = "2100000000000000123";
    private static final int MAX_PROMPT_ID_CHARACTERS = 240;
    private static final int MAX_OUTPUT_REDIRECTS = 3;

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
    void downloadsOutputRedirectOnceWithoutForwardingCredentials() throws IOException {
        client = new ComfyUiClient(new ComfyUiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + SYNTHETIC_PROXY_PREFIX), mapper);
        AtomicInteger views = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();
        server.createContext(SYNTHETIC_PROXY_PREFIX + "/view", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            views.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/download.png");
            exchange.getResponseHeaders().set("Set-Cookie", "synthetic-cookie=test-only");
            respond(exchange, 302, "redirect");
        });
        server.createContext("/download.png", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Referer")).isNull();
            downloads.incrementAndGet();
            respond(exchange, 200, "PNG-BYTES");
        });
        try (var output = client.output("rendered_01.png")) {
            assertThat(output.readAllBytes()).isEqualTo("PNG-BYTES".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(views).hasValue(1);
        assertThat(downloads).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void followsOnlySupportedOutputRedirectStatusesAsNewGets(int status) throws IOException {
        AtomicInteger downloads = new AtomicInteger();
        server.createContext("/view", exchange -> {
            exchange.getResponseHeaders().set("Location", "/download.png?signature=synthetic-value");
            respond(exchange, status, "redirect");
        });
        server.createContext("/download.png", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("signature=synthetic-value");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Referer")).isNull();
            downloads.incrementAndGet();
            respond(exchange, 200, "PNG-BYTES");
        });
        try (var output = client.output("rendered_01.png")) {
            assertThat(output.readAllBytes()).isEqualTo("PNG-BYTES".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(downloads).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {300, 304, 305, 306, 309})
    void rejectsUnsupportedOutputRedirectStatuses(int status) {
        AtomicInteger downloads = new AtomicInteger();
        server.createContext("/view", exchange -> {
            exchange.getResponseHeaders().set("Location", "/download.png");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.createContext("/download.png", exchange -> {
            downloads.incrementAndGet();
            respond(exchange, 200, "PNG-BYTES");
        });
        assertThatThrownBy(() -> client.output("rendered_01.png"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThat(downloads).hasValue(0);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "not a URI", "/unsafe#fragment", "/encoded%2fpath", "/a/../private"})
    void refusesOutputRedirectsWithoutSafeLocation(String location) {
        server.createContext("/view", exchange -> {
            if (location != null) exchange.getResponseHeaders().set("Location", location);
            respond(exchange, 302, "redirect");
        });
        assertThatThrownBy(() -> client.output("rendered_01.png"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("ComfyUI redirect is unsafe");
    }

    @Test
    void allowsExactlyThreeOutputRedirects() throws IOException {
        AtomicInteger downloads = new AtomicInteger();
        server.createContext("/view", exchange -> {
            exchange.getResponseHeaders().set("Location", "/hop/1");
            respond(exchange, 302, "redirect");
        });
        server.createContext("/hop/", exchange -> {
            int hop = Integer.parseInt(exchange.getRequestURI().getPath().substring("/hop/".length()));
            downloads.incrementAndGet();
            if (hop < MAX_OUTPUT_REDIRECTS) {
                exchange.getResponseHeaders().set("Location", "/hop/" + (hop + 1));
                respond(exchange, 302, "redirect");
            } else respond(exchange, 200, "PNG-BYTES");
        });
        try (var output = client.output("rendered_01.png")) {
            assertThat(output.readAllBytes()).isEqualTo("PNG-BYTES".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(downloads).hasValue(MAX_OUTPUT_REDIRECTS);
    }

    @Test
    void rejectsRedirectLoopBeforeRepeatingDownloadRequest() {
        AtomicInteger downloads = new AtomicInteger();
        server.createContext("/view", exchange -> {
            exchange.getResponseHeaders().set("Location", "/loop");
            respond(exchange, 302, "redirect");
        });
        server.createContext("/loop", exchange -> {
            downloads.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/loop");
            respond(exchange, 302, "redirect");
        });
        assertThatThrownBy(() -> client.output("rendered_01.png"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("ComfyUI redirect loop detected");
        assertThat(downloads).hasValue(1);
    }

    @Test
    void rejectsExcessiveRedirectHopsEvenWithoutLoop() {
        AtomicInteger downloads = new AtomicInteger();
        server.createContext("/view", exchange -> {
            exchange.getResponseHeaders().set("Location", "/hop/1");
            respond(exchange, 302, "redirect");
        });
        server.createContext("/hop/", exchange -> {
            int hop = Integer.parseInt(exchange.getRequestURI().getPath().substring("/hop/".length()));
            downloads.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/hop/" + (hop + 1));
            respond(exchange, 302, "redirect");
        });
        assertThatThrownBy(() -> client.output("rendered_01.png"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("ComfyUI redirect limit exceeded");
        assertThat(downloads).hasValue(MAX_OUTPUT_REDIRECTS);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void followsSubmissionRedirectUsingHttpMethodRulesWithoutRepeatingOriginalPost(int status) {
        UUID requestKey = UUID.randomUUID();
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger acknowledgements = new AtomicInteger();
        AtomicReference<byte[]> submittedBody = new AtomicReference<>();
        AtomicReference<String> submittedContentType = new AtomicReference<>();
        server.createContext("/prompt", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            submissions.incrementAndGet();
            submittedBody.set(exchange.getRequestBody().readAllBytes());
            submittedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.getResponseHeaders().set("Location", "/acknowledged");
            exchange.getResponseHeaders().set("Set-Cookie", "synthetic-cookie=test-only");
            respond(exchange, status, "redirect");
        });
        server.createContext("/acknowledged", exchange -> {
            boolean retainsPost = status == 307 || status == 308;
            assertThat(exchange.getRequestMethod()).isEqualTo(retainsPost ? "POST" : "GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Referer")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Accept")).isEqualTo("application/json");
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (retainsPost) {
                assertThat(body).isEqualTo(submittedBody.get());
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo(submittedContentType.get());
            } else {
                assertThat(body).isEmpty();
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isNull();
            }
            acknowledgements.incrementAndGet();
            respond(exchange, 200, "{\"prompt_id\":\"" + SYNTHETIC_PROXY_PROMPT_ID + "\"}");
        });
        assertThat(client.submit(mapper.createObjectNode(), requestKey)).isEqualTo(SYNTHETIC_PROXY_PROMPT_ID);
        assertThat(submissions).hasValue(1);
        assertThat(acknowledgements).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void followsHistoryRedirectAsGetWithoutCredentials(int status) {
        AtomicInteger histories = new AtomicInteger();
        server.createContext("/history/", exchange -> {
            histories.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/archived-history");
            respond(exchange, status, "redirect");
        });
        server.createContext("/archived-history", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestBody().readAllBytes()).isEmpty();
            assertThat(exchange.getRequestHeaders().getFirst("Accept")).isEqualTo("application/json");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Referer")).isNull();
            respond(exchange, 200, "{}");
        });
        assertThat(client.history(SYNTHETIC_PROXY_PROMPT_ID).isEmpty()).isTrue();
        assertThat(histories).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void followsUploadRedirectUsingHttpMethodRulesAndExactMultipartBody(int status) {
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger acknowledgements = new AtomicInteger();
        AtomicReference<byte[]> uploadedBody = new AtomicReference<>();
        AtomicReference<String> uploadedContentType = new AtomicReference<>();
        server.createContext("/upload/image", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            uploadedBody.set(exchange.getRequestBody().readAllBytes());
            uploadedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            uploads.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/uploaded");
            respond(exchange, status, "redirect");
        });
        server.createContext("/uploaded", exchange -> {
            boolean retainsPost = status == 307 || status == 308;
            assertThat(exchange.getRequestMethod()).isEqualTo(retainsPost ? "POST" : "GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Referer")).isNull();
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (retainsPost) {
                assertThat(body).isEqualTo(uploadedBody.get());
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo(uploadedContentType.get());
            } else {
                assertThat(body).isEmpty();
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isNull();
            }
            acknowledgements.incrementAndGet();
            respond(exchange, 200, "{\"name\":\"input.png\",\"type\":\"input\",\"subfolder\":\"\"}");
        });
        assertThat(client.uploadImage(UUID.randomUUID(), new byte[] {1, 2, 3}, "png")).isEqualTo("input.png");
        assertThat(uploads).hasValue(1);
        assertThat(acknowledgements).hasValue(1);
    }

    @Test
    void rejectsSubmissionRedirectBackToOriginalUrlBeforeRepeatingPost() {
        AtomicInteger submissions = new AtomicInteger();
        server.createContext("/prompt", exchange -> {
            submissions.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/prompt");
            respond(exchange, 307, "redirect");
        });
        assertThatThrownBy(() -> client.submit(mapper.createObjectNode(), UUID.randomUUID()))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("ComfyUI redirect loop detected");
        assertThat(submissions).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303})
    void acceptsSubmissionReceiptGetAtSameUrlWithoutRepeatingPost(int status) {
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger receipts = new AtomicInteger();
        server.createContext("/prompt", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                submissions.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Location", "/prompt");
                respond(exchange, status, "redirect");
            } else {
                assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isNull();
                receipts.incrementAndGet();
                respond(exchange, 200, "{\"prompt_id\":\"" + SYNTHETIC_PROXY_PROMPT_ID + "\"}");
            }
        });
        assertThat(client.submit(mapper.createObjectNode(), UUID.randomUUID())).isEqualTo(SYNTHETIC_PROXY_PROMPT_ID);
        assertThat(submissions).hasValue(1);
        assertThat(receipts).hasValue(1);
    }

    @Test
    void validatesMixedSameOriginThenForeignHttpsRedirectChain() {
        URI origin = URI.create("https://comfy.example.com" + SYNTHETIC_PROXY_PREFIX);
        URI current = URI.create(origin + "/view?filename=render.png");
        URI first = ComfyUiClient.checkedRedirect(origin, current, "/archived/render.png?stage=synthetic-first", Dns.SYSTEM);
        URI second = ComfyUiClient.checkedRedirect(origin, first,
                "https://cdn.example.com/render.png?signature=synthetic%2Bvalue", Dns.SYSTEM);
        assertThat(first).isEqualTo(URI.create("https://comfy.example.com/archived/render.png?stage=synthetic-first"));
        assertThat(second).isEqualTo(URI.create("https://cdn.example.com/render.png?signature=synthetic%2Bvalue"));
        assertThatThrownBy(() -> ComfyUiClient.checkedRedirect(origin, second,
                "http://cdn.example.com/render.png", Dns.SYSTEM)).isInstanceOf(ComfyUiClient.ProtocolFailure.class);
    }

    @Test
    void redactsRedirectPathAndCustomQueryCredentialsFromDebugCapture() throws IOException {
        client = new ComfyUiClient(new ComfyUiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + SYNTHETIC_PROXY_PREFIX), mapper);
        String target = "/downloads/synthetic-path-signature/render.png?nonce=synthetic-query-secret"
                + "&signature=synthetic-standard-signature&opaque=synthetic%2Fencoded%2Bsecret";
        server.createContext(SYNTHETIC_PROXY_PREFIX + "/view", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.getResponseHeaders().set("Location", target);
            respond(exchange, 302, target);
        });
        server.createContext("/downloads/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            respond(exchange, 200, "synthetic-path-signature synthetic-query-secret synthetic-standard-signature"
                    + " synthetic/encoded+secret synthetic%2Fencoded%2Bsecret");
        });
        AtomicReference<List<dev.agenvas.shared.http.DebugHttpCapture.Exchange>> captured = new AtomicReference<>();
        try (var scope = dev.agenvas.shared.http.DebugHttpCapture.open(captured::set);
                var output = client.output("render.png")) {
            assertThat(output.readAllBytes()).isNotEmpty();
        }
        assertThat(captured.get()).hasSize(2);
        assertThat(captured.get().toString()).doesNotContain("synthetic-path-key", "synthetic-path-signature",
                "synthetic-query-secret", "synthetic-standard-signature", "synthetic/encoded+secret",
                "synthetic%2Fencoded%2Bsecret", target);
    }

    @Test
    void validatesPublicHttpsRedirectWithoutChangingSignedQuery() {
        URI origin = URI.create("https://comfy.example.com" + SYNTHETIC_PROXY_PREFIX);
        URI current = URI.create(origin + "/view?filename=render.png");
        String target = "https://cdn.example.com:443/output/render.png?signature=synthetic%2Bvalue&expires=123";
        assertThat(ComfyUiClient.checkedRedirect(origin, current, target, Dns.SYSTEM))
                .isEqualTo(URI.create(target));
    }

    @Test
    void queryOnlyRedirectKeepsCurrentPathAndExactEncodedQuery() throws IOException {
        client = new ComfyUiClient(new ComfyUiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + SYNTHETIC_PROXY_PREFIX), mapper);
        AtomicInteger originalRequests = new AtomicInteger();
        AtomicInteger redirectedRequests = new AtomicInteger();
        server.createContext(SYNTHETIC_PROXY_PREFIX + "/view", exchange -> {
            if (exchange.getRequestURI().getRawQuery().startsWith("filename=")) {
                originalRequests.incrementAndGet();
                exchange.getResponseHeaders().set("Location", "?signature=synthetic%2Bvalue&nonce=2");
                respond(exchange, 302, "redirect");
            } else {
                redirectedRequests.incrementAndGet();
                assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("signature=synthetic%2Bvalue&nonce=2");
                respond(exchange, 200, "output");
            }
        });
        try (var output = client.output("render.png")) {
            assertThat(output.readAllBytes()).isEqualTo("output".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(originalRequests).hasValue(1);
        assertThat(redirectedRequests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://cdn.example.com/render.png", "https://cdn.example.com:444/render.png",
            "https://user:synthetic-pass@cdn.example.com/render.png", "https://cdn.example.com/render.png#fragment",
            "https://127.0.0.1/render.png", "https://0.0.0.0/render.png", "https://[::1]/render.png",
            "https://[::]/render.png", "https://169.254.169.254/render.png", "https://168.63.129.16/render.png",
            "https://10.0.0.1/render.png", "https://172.16.0.1/render.png", "https://192.168.0.1/render.png",
            "https://224.0.0.1/render.png", "https://[fd00::1]/render.png", "https://[fe80::1]/render.png",
            "https://127.1/render.png", "https://2130706433/render.png",
            "https://[64:ff9b::a9fe:a9fe]/render.png", "https://[2002:a9fe:a9fe::1]/render.png",
            "https://[2001:0:1::1]/render.png"})
    void rejectsUnsafeForeignOutputRedirectBeforeOpeningRequest(String target) {
        URI origin = URI.create("https://comfy.example.com" + SYNTHETIC_PROXY_PREFIX);
        URI current = URI.create(origin + "/view?filename=render.png");
        assertThatThrownBy(() -> ComfyUiClient.checkedRedirect(origin, current, target,
                ComfyUiClient.checkedRedirectDns(host -> List.of(InetAddress.getByName(host)))))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("ComfyUI redirect is unsafe");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://cdn.example.com/synthetic-path-key/render.png",
            "https://cdn.example.com/render.png?token=synthetic-path-key",
            "https://cdn.example.com/render.png?token=synthetic%2dpath%2dkey"})
    void refusesForeignRedirectsThatEchoOriginPathCredential(String target) {
        URI origin = URI.create("https://comfy.example.com" + SYNTHETIC_PROXY_PREFIX);
        URI current = URI.create(origin + "/view?filename=render.png");
        assertThatThrownBy(() -> ComfyUiClient.checkedRedirect(origin, current, target, Dns.SYSTEM))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("ComfyUI redirect is unsafe");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "0.0.0.0", "::", "169.254.169.254", "fe80::1",
            "224.0.0.1", "ff02::1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "fc00::1", "fd00::1",
            "168.63.129.16", "100.100.100.200", "64:ff9b::a9fe:a9fe", "64:ff9b:1::a9fe:a9fe",
            "100::1", "2002:a9fe:a9fe::1", "2001:0:1::1"})
    void rejectsPrivateAndSpecialForeignOutputDnsAnswers(String address) throws UnknownHostException {
        InetAddress resolved = InetAddress.getByName(address);
        Dns dns = ComfyUiClient.checkedRedirectDns(host -> List.of(resolved));
        assertThatThrownBy(() -> dns.lookup("synthetic-cdn.example"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void foreignOutputDnsChecksEveryAnswerAndRechecksLaterLookups() throws UnknownHostException {
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        InetAddress privateAddress = InetAddress.getByName("10.0.0.1");
        assertThatThrownBy(() -> ComfyUiClient.checkedRedirectDns(host -> List.of(publicAddress, privateAddress))
                .lookup("synthetic-cdn.example")).isInstanceOf(UnknownHostException.class);
        AtomicInteger lookups = new AtomicInteger();
        Dns dns = ComfyUiClient.checkedRedirectDns(host -> List.of(
                lookups.incrementAndGet() == 1 ? publicAddress : privateAddress));
        assertThat(dns.lookup("synthetic-cdn.example")).containsExactly(publicAddress);
        assertThatThrownBy(() -> dns.lookup("synthetic-cdn.example")).isInstanceOf(UnknownHostException.class);
        assertThat(lookups).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "2606:4700:4700::1111", "198.18.0.1", "198.19.255.254", "2001:2::1"})
    void acceptsPublicAndExistingFakeIpOutputDnsAnswers(String address) throws UnknownHostException {
        InetAddress resolved = InetAddress.getByName(address);
        assertThat(ComfyUiClient.checkedRedirectDns(host -> List.of(resolved)).lookup("synthetic-cdn.example"))
                .containsExactly(resolved);
    }

    @Test
    void refusesUnsafeRedirectTargetsForAllRoutes() throws IOException {
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
            server.createContext("/history/", exchange -> {
                exchange.getResponseHeaders().add("Location", target);
                respond(exchange, 302, "redirect");
            });
            server.createContext("/upload/image", exchange -> {
                exchange.getResponseHeaders().add("Location", target);
                respond(exchange, 307, "redirect");
            });
            assertThatThrownBy(() -> client.submit(mapper.createObjectNode(), UUID.randomUUID()))
                    .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
            assertThatThrownBy(() -> client.output("rendered_01.png"))
                    .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
            assertThatThrownBy(() -> client.history(SYNTHETIC_PROXY_PROMPT_ID))
                    .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
            assertThatThrownBy(() -> client.uploadImage(UUID.randomUUID(), new byte[] {1}, "png"))
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
