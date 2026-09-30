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

/** Local fake Ark protocol; no paid video task is created. */
class ArkSeedanceClientTest {
    private static final String TASK_ID = "cgt-test-123";
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ArkSeedanceClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new ArkSeedanceClient(mapper,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void submitsOneFixedFirstFrameTaskAndQueriesOnlySavedId() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        server.createContext("/api/v3/contents/generations/tasks", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
                assertThat(body.path("model").asText()).isEqualTo("doubao-seedance-2-0-260128");
                assertThat(body.path("duration").asInt()).isEqualTo(4);
                assertThat(body.path("ratio").asText()).isEqualTo("16:9");
                assertThat(body.path("generate_audio").booleanValue()).isFalse();
                assertThat(body.path("output_format").asText()).isEqualTo("mp4");
                assertThat(body.path("content").size()).isEqualTo(2);
                assertThat(body.path("content").get(1).path("role").asText())
                        .isEqualTo("first_frame");
                assertThat(body.path("content").get(1).path("image_url")
                        .path("url").asText()).isEqualTo("data:image/png;base64,"
                                + Base64.getEncoder().encodeToString(png));
                creates.incrementAndGet();
                respond(exchange, 200, "{\"id\":\"" + TASK_ID + "\"}");
            } else {
                assertThat(exchange.getRequestURI().getPath())
                        .isEqualTo("/api/v3/contents/generations/tasks/" + TASK_ID);
                queries.incrementAndGet();
                respond(exchange, 200, "{\"id\":\"" + TASK_ID + "\",\"model\":"
                        + "\"doubao-seedance-2-0-260128\",\"status\":\"running\"}");
            }
        });
        assertThat(client.create("fake-key", "Move", png, 4, "16:9")).isEqualTo(TASK_ID);
        assertThat(client.query("fake-key", TASK_ID).status()).isEqualTo("running");
        assertThat(creates).hasValue(1);
        assertThat(queries).hasValue(1);
    }

    @Test
    void sendsAllReferenceImagesAndAudiosWithoutSilencingReferenceVideo() {
        server.createContext("/api/v3/contents/generations/tasks", exchange -> {
            var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(body.path("generate_audio").asBoolean()).isTrue();
            assertThat(body.path("content").size()).isEqualTo(4);
            assertThat(body.at("/content/1/role").asText()).isEqualTo("reference_image");
            assertThat(body.at("/content/2/role").asText()).isEqualTo("reference_audio");
            assertThat(body.at("/content/2/audio_url/url").asText()).startsWith("data:audio/mp3;base64,");
            assertThat(body.at("/content/3/audio_url/url").asText()).startsWith("data:audio/wav;base64,");
            respond(exchange, 200, "{\"id\":\"" + TASK_ID + "\"}");
        });
        var references = java.util.List.of(new ArkSeedanceClient.Reference("image/png", new byte[]{1}, "reference_image"),
                new ArkSeedanceClient.Reference("audio/mpeg", new byte[]{2}, "reference_audio"),
                new ArkSeedanceClient.Reference("audio/wav", new byte[]{3}, "reference_audio"));
        assertThat(client.create("fake-key", "Animate", references, 4, "16:9", true)).isEqualTo(TASK_ID);
    }

    @Test
    void badDurationsAndLostCreateResponseNeverCauseAutomaticResubmission() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        assertThatThrownBy(() -> client.create("key", "Move", png, 3, "16:9"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.create("key", "Move", png, 16, "16:9"))
                .isInstanceOf(IllegalArgumentException.class);
        AtomicInteger creates = new AtomicInteger();
        server.createContext("/api/v3/contents/generations/tasks", exchange -> {
            creates.incrementAndGet();
            exchange.close();
        });
        assertThatThrownBy(() -> client.create("key", "Move", png, 15, "16:9"))
                .isInstanceOf(ArkSeedanceClient.Uncertain.class);
        assertThat(creates).hasValue(1);
    }

    @Test
    void mismatchedQueriedIdIsBlockedAndCannotSelectAnotherTask() {
        server.createContext("/api/v3/contents/generations/tasks", exchange -> respond(exchange,
                200, "{\"id\":\"another-task\",\"model\":"
                        + "\"doubao-seedance-2-0-260128\",\"status\":\"succeeded\"}"));
        assertThatThrownBy(() -> client.query("key", TASK_ID))
                .isInstanceOf(ArkSeedanceClient.ProtocolFailure.class);
        assertThatThrownBy(() -> client.query("key", "../tasks"))
                .isInstanceOf(ArkSeedanceClient.ProtocolFailure.class);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
