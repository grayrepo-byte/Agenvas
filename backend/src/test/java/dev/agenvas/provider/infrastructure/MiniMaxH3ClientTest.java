package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Synthetic local HTTP only; no MiniMax account or paid generation. */
class MiniMaxH3ClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private MiniMaxH3Client client;
    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start();
        client = new MiniMaxH3Client(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void createsOfficialV2TaskAndQueriesOnlyOriginalIdentity() {
        AtomicInteger creates = new AtomicInteger();
        server.createContext("/v2/video_generation", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-key");
            var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(body.path("model").asText()).isEqualTo("MiniMax-H3");
            assertThat(body.path("resolution").asText()).isEqualTo("2K");
            assertThat(body.path("duration").asInt()).isEqualTo(15);
            assertThat(body.path("ratio").asText()).isEqualTo("9:16");
            assertThat(body.has("generate_audio")).isFalse();
            creates.incrementAndGet(); respond(exchange, 200, "{\"task_id\":\"task-1\"}");
        });
        server.createContext("/v2/query/video_generation/task-1", exchange -> respond(exchange, 200,
                "{\"task\":{\"id\":\"task-1\",\"model\":\"MiniMax-H3\",\"status\":\"running\"}}"));
        var body = client.prepare("Synthetic scene", List.of(), 15, "9:16", "1440p");
        assertThat(client.create(null, "synthetic-key", body)).isEqualTo("task-1");
        assertThat(client.query(null, "synthetic-key", "task-1").status()).isEqualTo("running");
        assertThat(creates).hasValue(1);
    }
    @Test void encodesFirstLastAndMixedReferencesWithNativeAudioAndAdaptiveRatio() {
        var frames = client.prepare("Move", List.of(
                new MiniMaxH3Client.Reference("image_url", "first_frame", "data:image/png;base64,AQ=="),
                new MiniMaxH3Client.Reference("image_url", "last_frame", "data:image/jpeg;base64,Ag==")), 4, "16:9", null);
        assertThat(frames.path("ratio").asText()).isEqualTo("adaptive");
        assertThat(frames.path("resolution").asText()).isEqualTo("768P");
        assertThat(frames.at("/content/2/role").asText()).isEqualTo("last_frame");
        var refs = List.of(new MiniMaxH3Client.Reference("image_url", "reference_image", "data:image/png;base64,AQ=="),
                new MiniMaxH3Client.Reference("video_url", "reference_video", "https://store.example.com/video.mp4?signature=synthetic"),
                new MiniMaxH3Client.Reference("audio_url", "reference_audio", "data:audio/wav;base64,Ag=="));
        var mixed = client.prepare("Use references", refs, 6, "adaptive", "768p");
        assertThat(mixed.path("content").size()).isEqualTo(4);
        assertThat(mixed.at("/content/2/video_url/url").asText()).contains("signature=synthetic");
        assertThat(refs.get(1).toString()).doesNotContain("signature", "store.example.com");
        assertThat(client.prepare("Sound", List.of(refs.get(2)), 4, "adaptive", null).path("content").size()).isEqualTo(2);
        assertThatThrownBy(() -> client.prepare("Mixed", List.of(refs.get(0),
                new MiniMaxH3Client.Reference("image_url", "first_frame", "data:image/png;base64,AQ==")), 4, "adaptive", null))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }
    @Test void rejectsUnsupportedInputsBeforeNetwork() {
        for (int seconds : List.of(3, 16)) assertThatThrownBy(() -> client.prepare("Move", List.of(), seconds, "16:9", null))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        assertThatThrownBy(() -> client.prepare("", List.of(), 4, "16:9", null)).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        assertThatThrownBy(() -> client.prepare("Move", List.of(), 4, "adaptive", null)).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        assertThatThrownBy(() -> client.prepare("Move", List.of(), 4, "16:9", "480p")).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }
    @Test void rejectsAnOversizedSerializedInlineRequestBeforeSubmission() {
        var oversized = new MiniMaxH3Client.Reference("image_url", "reference_image",
                "data:image/png;base64," + "A".repeat(64_000_000));
        assertThatThrownBy(() -> client.prepare("Move", List.of(oversized), 4, "adaptive", null))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }
    @Test void lostResponseAndMalformedAcceptedBodyStayUncertainWithoutResubmission() {
        AtomicInteger creates = new AtomicInteger();
        server.createContext("/v2/video_generation", exchange -> { creates.incrementAndGet(); exchange.close(); });
        assertThatThrownBy(() -> client.create(null, "key", client.prepare("Move", List.of(), 4, "16:9", null)))
                .isInstanceOf(MiniMaxH3Client.Uncertain.class);
        assertThat(creates).hasValue(1);
        server.removeContext("/v2/video_generation");
        server.createContext("/v2/video_generation", exchange -> { creates.incrementAndGet(); respond(exchange, 200, "{}"); });
        assertThatThrownBy(() -> client.create(null, "key", client.prepare("Move", List.of(), 4, "16:9", null)))
                .isInstanceOf(MiniMaxH3Client.Uncertain.class);
        assertThat(creates).hasValue(2);
    }
    @Test void documentedRejectionsAndServerErrorsHaveDifferentBillingUncertainty() {
        for (int status : List.of(400, 401, 402, 422, 429, 500)) {
            server.createContext("/v2/video_generation", exchange -> respond(exchange, status, "{}"));
            assertThatThrownBy(() -> client.create(null, "key", client.prepare("Move", List.of(), 4, "16:9", null)))
                    .isInstanceOf(status == 500 ? MiniMaxH3Client.Uncertain.class : MiniMaxH3Client.Rejected.class);
            server.removeContext("/v2/video_generation");
        }
    }
    @Test void blocksWrongIdentityUnknownStatusAndExpiredTask() {
        server.createContext("/v2/query/video_generation/task-1", exchange -> respond(exchange, 200,
                "{\"task\":{\"id\":\"another\",\"model\":\"MiniMax-H3\",\"status\":\"succeeded\"}}"));
        assertThatThrownBy(() -> client.query(null, "key", "task-1")).isInstanceOf(MiniMaxH3Client.ProtocolFailure.class);
        assertThatThrownBy(() -> client.query(null, "key", "../task-1")).isInstanceOf(MiniMaxH3Client.ProtocolFailure.class);
        server.removeContext("/v2/query/video_generation/task-1");
        server.createContext("/v2/query/video_generation/task-1", exchange -> respond(exchange, 200,
                "{\"task\":{\"id\":\"task-1\",\"model\":\"MiniMax-H3\",\"status\":\"unexpected\"}}"));
        assertThatThrownBy(() -> client.query(null, "key", "task-1")).isInstanceOf(MiniMaxH3Client.ProtocolFailure.class);
        server.removeContext("/v2/query/video_generation/task-1");
        server.createContext("/v2/query/video_generation/task-1", exchange -> respond(exchange, 404, "{}"));
        assertThatThrownBy(() -> client.query(null, "key", "task-1")).isInstanceOf(MiniMaxH3Client.TaskExpired.class);
    }
    @Test void permitsOnlyTheTwoOfficialRegionalOrigins() {
        assertThat(MiniMaxH3Client.validatedOrigin(null)).isEqualTo("https://api.minimax.cn");
        assertThat(MiniMaxH3Client.validatedOrigin("https://api.minimax.io/")).isEqualTo("https://api.minimax.io");
        for (String url : List.of("https://api.minimax.cn/v2", "https://user@api.minimax.cn", "https://proxy.example.com", "http://api.minimax.cn"))
            assertThatThrownBy(() -> MiniMaxH3Client.validatedOrigin(url)).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
