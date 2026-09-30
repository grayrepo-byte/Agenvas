package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
            String request = "{\"url\":\"https://files.example/image.png?X-Amz-Signature=signed-secret&filename=image.png\"}";
            int id = DebugHttpCapture.begin("POST", "http://127.0.0.1:8188/prompt", request.getBytes(), "text/plain");
            String response = "{\"channel\":\"analysis\",\"text\":\"PRIVATE_ANALYSIS\"}";
            try (var input = DebugHttpCapture.responseStream(id, 200, "application/octet-stream",
                    new java.io.ByteArrayInputStream(response.getBytes()))) { input.readAllBytes(); }
            assertThat(MAPPER.writeValueAsString(saved.get())).contains("filename=image.png", "REDACTED")
                    .doesNotContain("signed-secret", "PRIVATE_ANALYSIS");
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
