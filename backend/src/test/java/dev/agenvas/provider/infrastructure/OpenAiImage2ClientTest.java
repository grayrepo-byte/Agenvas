package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Local fake API checks; no OpenAI request or paid generation occurs. */
class OpenAiImage2ClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private OpenAiImage2Client client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new OpenAiImage2Client(mapper);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void generationAndReferenceEditUseSeparateFixedPathsAndPngResponses() throws IOException {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        String result = "{\"data\":[{\"b64_json\":\""
                + Base64.getEncoder().encodeToString(png) + "\"}]}";
        AtomicInteger generations = new AtomicInteger();
        AtomicInteger edits = new AtomicInteger();
        server.createContext("/v1/images/generations", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer fake-secret");
            var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(body.path("model").asText()).isEqualTo("gpt-image-2");
            assertThat(body.path("quality").asText()).isEqualTo("medium");
            assertThat(body.path("size").asText()).isEqualTo("1536x1024");
            generations.incrementAndGet();
            respond(exchange, 200, result);
        });
        server.createContext("/v1/images/edits", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
                    .startsWith("multipart/form-data; boundary=");
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.ISO_8859_1);
            assertThat(body).contains("name=\"model\"", "gpt-image-2",
                    "name=\"image\"", "filename=\"reference.png\"", "Avoid: clouds");
            edits.incrementAndGet();
            respond(exchange, 200, result);
        });

        try (var generated = client.generate("fake-secret", OpenAiImage2Client.DEFAULT_MODEL, "ridge", "medium", "1536x1024", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")) {
            assertThat(generated.stream().readAllBytes()).isEqualTo(png);
        }
        try (var edited = client.edit("fake-secret", OpenAiImage2Client.DEFAULT_MODEL, "ridge\nAvoid: clouds", "high",
                "1024x1024", png, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")) {
            assertThat(edited.stream().readAllBytes()).isEqualTo(png);
        }
        assertThat(generations).hasValue(1);
        assertThat(edits).hasValue(1);
    }

    @Test
    void configuredModelNameReplacesTheBuiltInDefault() throws IOException {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        String result = "{\"data\":[{\"b64_json\":\""
                + Base64.getEncoder().encodeToString(png) + "\"}]}";
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/images/generations", exchange -> {
            var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(body.path("model").asText()).isEqualTo("gpt-image-1");
            calls.incrementAndGet();
            respond(exchange, 200, result);
        });
        try (var generated = client.generate("fake-secret", "gpt-image-1", "ridge", "medium",
                "1024x1024", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")) {
            assertThat(generated.stream().readAllBytes()).isEqualTo(png);
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void invalidResponseAndLostConnectionAreUncertainNeverRetriedByClient() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/images/generations", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"data\":[{\"b64_json\":\"bad-base64!\"}]}");
        });
        assertThatThrownBy(() -> client.generate("key", OpenAiImage2Client.DEFAULT_MODEL, "ridge", "low", "1024x1024", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"))
                .isInstanceOf(OpenAiImage2Client.Uncertain.class);
        assertThat(requests).hasValue(1);
        server.removeContext("/v1/images/generations");
        server.createContext("/v1/images/generations", exchange -> {
            requests.incrementAndGet();
            exchange.close();
        });
        assertThatThrownBy(() -> client.generate("key", OpenAiImage2Client.DEFAULT_MODEL, "ridge", "low", "1024x1024", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"))
                .isInstanceOf(OpenAiImage2Client.Uncertain.class);
        assertThat(requests).hasValue(2);
    }

    @Test
    void customBasePathIsPreservedForImagesEndpoint() throws IOException {
        assertThat(OpenAiImage2Client.apiEndpoint("https://gateway.example.com/proxy/v1",
                "images/generations"))
                .isEqualTo(URI.create("https://gateway.example.com/proxy/v1/images/generations"));
        assertThat(OpenAiImage2Client.apiEndpoint(null, "images/edits"))
                .isEqualTo(URI.create("https://api.openai.com/v1/images/edits"));
        client = new OpenAiImage2Client(mapper);
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        String result = "{\"data\":[{\"b64_json\":\""
                + Base64.getEncoder().encodeToString(png) + "\"}]}";
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/proxy/v1/images/generations", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, result);
        });
        try (var generated = client.generate("fake-secret", OpenAiImage2Client.DEFAULT_MODEL, "ridge", "medium",
                "1024x1024", "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy/v1")) {
            assertThat(generated.stream().readAllBytes()).isEqualTo(png);
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void redirectDoesNotForwardTheApiKey() {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/v1/images/generations", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            redirected.incrementAndGet();
            exchange.close();
        });
        assertThatThrownBy(() -> client.generate("fake-secret", OpenAiImage2Client.DEFAULT_MODEL, "ridge", "low",
                "1024x1024", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")).isInstanceOf(OpenAiImage2Client.Uncertain.class);
        assertThat(redirected).hasValue(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
