package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Fake HTTP only: raw wire content, credential removal, and no capture outside a call scope. */
class DebugHttpCaptureTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TIMEOUT_SECONDS = 5;

    @Test void llmCaptureKeepsSseFieldsAndMultipartDataEventsButRemovesAuthentication() throws Exception {
        var saved = new AtomicReference<List<DebugHttpCapture.Exchange>>();
        try (var scope = DebugHttpCapture.openLlm(saved::set)) {
            DebugHttpCapture.registerSecret("synthetic-sse-auth");
            int index = DebugHttpCapture.begin("POST", "https://provider.invalid/chat", null, null);
            String events = "event: message\r\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"full model field\"}}],\r\n"
                    + "data: \"image\":\"data:image/png;base64,c3ludGhldGljLWltYWdl\",\"apiKey\":\"synthetic-sse-auth\",\"nested\":{\"authorization\":\"Bearer synthetic-other-auth\"}}\r\n\r\n"
                    + "data: [DONE]\n\n";
            try (var stream = DebugHttpCapture.responseStream(index, 200, "text/event-stream",
                    new java.io.ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)))) {
                assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(events);
            }
        }
        assertThat(saved.get().getFirst().responseBody().content()).contains("event: message", "full model field", "[DONE]", "REDACTED", "[image bytes omitted]")
                .doesNotContain("synthetic-sse-auth", "synthetic-other-auth", "c3ludGhldGljLWltYWdl");
        assertThat(saved.get().getFirst().responseBody().encoding()).isEqualTo(DebugHttpCapture.Encoding.UTF8);
    }

    @Test void requestBindingCapturesOnAnUnpropagatedThreadWithoutSendingTheCarrier() throws Exception {
        HttpServer server = bindingServer();
        server.start();
        try {
            var saved = new AtomicReference<List<DebugHttpCapture.Exchange>>();
            try (var scope = DebugHttpCapture.open(saved::set)) {
                var headers = DebugHttpCapture.requestHeaders();
                assertThat(headers).containsKey(DebugHttpCapture.CAPTURE_HEADER);
                CompletableFuture.runAsync(() -> {
                    assertThat(DebugHttpCapture.enabled()).isFalse();
                    executeBoundRequest(server, headers);
                }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(saved.get()).hasSize(1);
                assertThat(saved.get().getFirst().requestBody().content()).contains("bound request");
                assertThat(saved.get().getFirst().responseBody().content()).contains("ok");
            }
            assertThat(DebugHttpCapture.requestHeaders()).isEmpty();
        } finally { server.stop(0); }
    }

    @Test void expiredAndUnknownBindingsAreRemovedWithoutFallingBackToAnotherScope() throws Exception {
        HttpServer server = bindingServer();
        server.start();
        try {
            var original = new AtomicReference<List<DebugHttpCapture.Exchange>>();
            Map<String, String> expired;
            try (var scope = DebugHttpCapture.open(original::set)) {
                expired = DebugHttpCapture.requestHeaders();
            }
            var unrelated = new AtomicReference<List<DebugHttpCapture.Exchange>>();
            try (var scope = DebugHttpCapture.open(unrelated::set)) {
                executeBoundRequest(server, expired);
                executeBoundRequest(server, Map.of(DebugHttpCapture.CAPTURE_HEADER, "synthetic-unknown-token"));
            }
            assertThat(original.get()).isEmpty();
            assertThat(unrelated.get()).isEmpty();
        } finally { server.stop(0); }
    }

    @Test void llmDebugReplacesImageBytesButKeepsModelContent() {
        try (var capture = DebugHttpCapture.openLlm(ignored -> {})) {
            String body = capture.sanitizeJson("{\"reasoning_content\":\"Actual model field\",\"messages\":[{\"content\":[{\"type\":\"text\",\"text\":\"Synthetic prompt\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,c3ludGhldGljLWltYWdl\",\"detail\":\"low\"}}]}]}");
            assertThat(body).contains("[image bytes omitted]", "Synthetic prompt", "Actual model field", "image_url", "low")
                    .doesNotContain("c3ludGhldGljLWltYWdl", "data:image/png;base64");
        }
    }

    @Test void imageAttachmentsAreOmittedFromDebugJsonWithoutRemovingThePrompt() {
        try (var capture = DebugHttpCapture.open(ignored -> {})) {
            String body = capture.sanitizeJson("{\"messages\":[{\"content\":[{\"type\":\"text\",\"text\":\"Synthetic prompt\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,c3ludGhldGljLWltYWdl\"}}]}]}");
            assertThat(body).contains("Synthetic prompt", "[image bytes omitted]")
                    .doesNotContain("c3ludGhldGljLWltYWdl", "data:image/png;base64");
        }
    }

    @Test void nestedScopeRestoresTheOuterBindingAndRepeatedCloseDoesNotClearIt() {
        try (var outer = DebugHttpCapture.open(ignored -> {})) {
            var headers = DebugHttpCapture.requestHeaders();
            var inner = DebugHttpCapture.open(ignored -> {});
            assertThat(DebugHttpCapture.requestHeaders()).isNotEqualTo(headers);
            inner.close();
            assertThat(DebugHttpCapture.requestHeaders()).isEqualTo(headers);
            inner.close();
            assertThat(DebugHttpCapture.requestHeaders()).isEqualTo(headers);
        }
        assertThat(DebugHttpCapture.requestHeaders()).isEmpty();
    }

    private static HttpServer bindingServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(DebugHttpCapture.CAPTURE_HEADER)).isNull();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] response = "{\"output\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }

    private static void executeBoundRequest(HttpServer server, Map<String, String> headers) {
        var client = PinnedHttpClients.pinned(Dns.SYSTEM, Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(TIMEOUT_SECONDS));
        var request = new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/generate")
                .post(RequestBody.create("{\"prompt\":\"bound request\"}", MediaType.get("application/json")));
        headers.forEach(request::header);
        try (var response = client.newCall(request.build()).execute()) {
            assertThat(response.body().string()).contains("ok");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Synthetic HTTP request failed", failure);
        }
    }

    @Test void capturesRealFailedResponseWithoutHeadersCredentialsOrPrivateReasoning() throws Exception {
        var attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            attempts.incrementAndGet();
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("full prompt", "request-secret");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Set-Cookie", "session=response-cookie");
            exchange.getResponseHeaders().set("Retry-After", "0");
            byte[] body = """
                    {"output":"full output","echo":"request-secret custom-header-secret response-cookie query-secret",
                     "reasoning_content":"private chain of thought","apiKey":"body-secret",
                     "parts":[{"thought":true,"text":"private thought"},{"text":"public output"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = PinnedHttpClients.pinned(Dns.SYSTEM, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(5));
            var saved = new AtomicReference<List<DebugHttpCapture.Exchange>>();
            var request = new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/generate?api_key=query-secret")
                    .header("Authorization", "Bearer request-secret").header("X-Custom-Credential", "custom-header-secret")
                    .post(RequestBody.create("{\"prompt\":\"full prompt\",\"apiKey\":\"request-secret\"}", MediaType.get("application/json"))).build();
            try (var scope = DebugHttpCapture.open(saved::set); var response = client.newCall(request).execute()) {
                assertThat(response.code()).isEqualTo(503);
                // Caller still receives the untouched provider body, including its private fields.
                assertThat(response.body().string()).contains("full output", "private chain of thought");
            }
            assertThat(attempts).hasValue(1);
            String json = MAPPER.writeValueAsString(saved.get());
            assertThat(json).contains("full prompt", "full output", "public output", "503", "REDACTED")
                    .doesNotContain("request-secret", "custom-header-secret", "body-secret", "response-cookie", "query-secret",
                            "private chain of thought", "private thought", "Set-Cookie", "Authorization");
            assertThat(saved.get().getFirst().responseBody().truncated()).isFalse();
            assertThat(DebugHttpCapture.enabled()).isFalse();
        } finally { server.stop(0); }
    }

    @Test void removesAutoDlSignedTosCredentialsFromUrlsAndJsonBodies() {
        var saved = new AtomicReference<List<DebugHttpCapture.Exchange>>();
        String url = "https://cg-comfyui-prod.tos-cn-beijing.volces.com/comfyui/outputs/test.mp4?X-Tos-Signature=secret-sig&X-Tos-Credential=secret-credential";
        try (var scope = DebugHttpCapture.open(saved::set)) {
            DebugHttpCapture.begin("GET", url, ("{\"url\":\"" + url + "\"}").getBytes(StandardCharsets.UTF_8), "application/json");
        }
        assertThat(MAPPER.writeValueAsString(saved.get())).contains("REDACTED")
                .doesNotContain("secret-sig", "secret-credential");
    }

    @Test void disabledDoesNotReadRequestOrCreateCheckpointsAndScopeDoesNotLeakOnFailure() {
        assertThat(DebugHttpCapture.begin("POST", "http://127.0.0.1:80/test", "private".getBytes(), "text/plain")).isEqualTo(-1);
        assertThatThrownBy(() -> {
            try (var scope = DebugHttpCapture.open(ignored -> {})) {
                assertThat(DebugHttpCapture.enabled()).isTrue();
                throw new IllegalStateException("provider failed");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(DebugHttpCapture.enabled()).isFalse();
    }

    @Test void jdkStreamAndPartialJsonKeepResponseAndSafeBoundaries() throws Exception {
        var saved = new AtomicReference<List<DebugHttpCapture.Exchange>>();
        try (var scope = DebugHttpCapture.open(saved::set)) {
            int id = DebugHttpCapture.begin("GET", "http://127.0.0.1:8188/history/id", null, null);
            var input = new java.io.ByteArrayInputStream("{\"output\":\"complete\"}".getBytes(StandardCharsets.UTF_8));
            try (var stream = DebugHttpCapture.responseStream(id, 200, "application/json", input)) {
                assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).contains("complete");
            }
            assertThat(saved.get().getFirst().responseBody().content()).contains("complete");
            int partial = DebugHttpCapture.begin("POST", "http://127.0.0.1:8188/prompt", "{}".getBytes(), "application/json");
            try (var stream = DebugHttpCapture.responseStream(partial, 400, "application/json",
                    new java.io.ByteArrayInputStream("{\"reasoning\":\"private".getBytes()))) {
                stream.readNBytes(8);
            }
            assertThat(saved.get().getLast().responseBody().encoding()).isEqualTo(DebugHttpCapture.Encoding.OMITTED);
            assertThat(saved.get().getLast().responseBody().truncated()).isTrue();
            assertThat(MAPPER.writeValueAsString(saved.get())).doesNotContain("private");
        }
    }

    @Test void credentialsInNestedSignedUrlsAndMislabelledJsonAreRemoved() throws Exception {
        var saved = new AtomicReference<List<DebugHttpCapture.Exchange>>();
        try (var scope = DebugHttpCapture.open(saved::set)) {
            String request = "{\"url\":\"https://files.example/image.png?X-Amz-Signature=signed-secret&x-oss-signature=oss-signed-secret&x-oss-credential=private-id&filename=image.png\"}";
            int id = DebugHttpCapture.begin("POST", "http://127.0.0.1:8188/prompt", request.getBytes(), "text/plain");
            String response = "{\"channel\":\"analysis\",\"text\":\"PRIVATE_ANALYSIS\"}";
            try (var input = DebugHttpCapture.responseStream(id, 200, "application/octet-stream",
                    new java.io.ByteArrayInputStream(response.getBytes()))) { input.readAllBytes(); }
            assertThat(MAPPER.writeValueAsString(saved.get())).contains("filename=image.png", "REDACTED")
                    .doesNotContain("signed-secret", "oss-signed-secret", "private-id", "PRIVATE_ANALYSIS");
        }
    }

    @Test void multipartKeepsPromptAndBinaryFileWithoutArbitraryPartHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/edit", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes());
            exchange.close();
        });
        server.start();
        var saved = new AtomicReference<List<DebugHttpCapture.Exchange>>();
        try (var scope = DebugHttpCapture.open(saved::set)) {
            var body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("prompt", "edit prompt")
                    .addFormDataPart("image", "input.png", RequestBody.create(new byte[] {1, 2, 3}, MediaType.get("image/png")))
                    .addFormDataPart("apiKey", "multipart-secret").build();
            var client = PinnedHttpClients.pinned(Dns.SYSTEM, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(5));
            try (var response = client.newCall(new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/edit").post(body).build()).execute()) {
                response.body().string();
            }
            String captured = saved.get().getFirst().requestBody().content();
            assertThat(captured).contains("edit prompt", "AQID", "REDACTED").doesNotContain("multipart-secret");
            assertThat(saved.get().getFirst().requestBody().encoding()).isEqualTo(DebugHttpCapture.Encoding.MULTIPART_JSON);
        } finally { server.stop(0); }
    }
}
