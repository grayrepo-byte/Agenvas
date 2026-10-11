package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.provider.infrastructure.KlingClient.Reference;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Synthetic HTTP verifies the two official schemas and never sends cloud traffic. */
class KlingClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> paths = new ArrayList<>();
    private final List<JsonNode> bodies = new ArrayList<>();
    private HttpServer server;
    private KlingClient client;
    private int status = 200;
    private String response;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-key");
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (request.length > 0) bodies.add(mapper.readTree(request));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        client = new KlingClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void imageUsesLegacySchemaAndOneResultPerTask() {
        var body = client.prepareImage("A mountain", "2:3", "2K", List.of(new Reference("REFERENCE", "AQID")));
        response = "{\"code\":0,\"data\":{\"task_id\":\"image-1\",\"task_status\":\"submitted\"}}";
        assertThat(client.createImage(null, "synthetic-key", body)).isEqualTo("image-1");
        assertThat(paths).containsExactly("/v1/images/generations");
        assertThat(bodies.getFirst().path("model_name").asText()).isEqualTo("kling-v3");
        assertThat(bodies.getFirst().path("n").asInt()).isEqualTo(1);
        assertThat(bodies.getFirst().path("image").asText()).isEqualTo("AQID");
        assertThat(bodies.getFirst().path("resolution").asText()).isEqualTo("2k");
        response = "{\"code\":0,\"data\":{\"task_id\":\"image-1\",\"task_status\":\"succeed\",\"task_result\":{\"images\":[{\"index\":0,\"url\":\"https://h1.inkwai.com/result.png\"}]}}}";
        assertThat(client.queryImage(null, "synthetic-key", "image-1").url()).isEqualTo("https://h1.inkwai.com/result.png");
        assertThat(paths.getLast()).isEqualTo("/v1/images/generations/image-1");
    }

    @Test void videoTextAndFramesUseNewSchemasAndExplicitExternalIdentity() {
        var text = client.prepareVideo("A mountain", "9:16", "2160p", 15, List.of(), "request-1");
        response = "{\"code\":0,\"data\":{\"id\":\"video-1\",\"status\":\"processing\"}}";
        assertThat(client.createVideo(null, "synthetic-key", text)).isEqualTo("video-1");
        assertThat(paths.getLast()).isEqualTo("/text-to-video/kling-3.0");
        assertThat(text.path("settings").path("aspect_ratio").asText()).isEqualTo("9:16");
        assertThat(text.path("settings").path("resolution").asText()).isEqualTo("4k");
        assertThat(text.path("settings").path("audio").asText()).isEqualTo("native");
        assertThat(text.path("settings").path("multi_shot").asBoolean()).isFalse();
        assertThat(text.path("options").path("external_task_id").asText()).isEqualTo("request-1");
        var frames = client.prepareVideo("A mountain", "ignored", "720p", 3,
                List.of(new Reference("END_FRAME", "BAUG"), new Reference("START_FRAME", "AQID")), "request-2");
        assertThat(client.createVideo(null, "synthetic-key", frames)).isEqualTo("video-1");
        assertThat(paths.getLast()).isEqualTo("/image-to-video/kling-3.0");
        assertThat(frames.has("prompt")).isFalse();
        assertThat(frames.path("settings").has("aspect_ratio")).isFalse();
        assertThat(frames.path("contents").get(1).path("type").asText()).isEqualTo("first_frame");
        assertThat(frames.path("contents").get(2).path("type").asText()).isEqualTo("last_frame");
    }

    @Test void queryMatchesTheOriginalIdAndRejectsDuplicateOrMissingIdentity() {
        response = "{\"code\":0,\"data\":[{\"id\":\"other\",\"status\":\"processing\"},{\"id\":\"video-1\",\"status\":\"succeeded\",\"outputs\":[{\"type\":\"video\",\"url\":\"https://h1.inkwai.com/a.mp4\",\"duration\":\"5\"}]}]}";
        assertThat(client.queryVideo(null, "synthetic-key", "video-1").url()).isEqualTo("https://h1.inkwai.com/a.mp4");
        assertThat(paths).containsExactly("/tasks?task_ids=video-1");
        for (String invalid : List.of("{\"code\":0,\"data\":[]}",
                "{\"code\":0,\"data\":[{\"id\":\"video-1\",\"status\":\"processing\"},{\"id\":\"video-1\",\"status\":\"processing\"}]}",
                "{\"code\":0,\"data\":[{\"id\":\"video-1\",\"status\":\"succeed\"}]}",
                "{\"code\":0,\"data\":[{\"id\":\"video-1\",\"status\":\"succeeded\",\"outputs\":[{\"type\":\"audio\",\"url\":\"https://h1.inkwai.com/a.mp3\"}]}]}")) {
            response = invalid;
            assertThatThrownBy(() -> client.queryVideo(null, "synthetic-key", "video-1")).isInstanceOf(KlingClient.ProtocolFailure.class);
        }
        assertThatThrownBy(() -> client.queryVideo(null, "synthetic-key", "../task")).isInstanceOf(KlingClient.ProtocolFailure.class);
    }

    @Test void refusesUnsupportedRequestsBeforeAnyNetworkCall() {
        assertThatThrownBy(() -> client.prepareImage("A mountain", "1:1", "4K", List.of())).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> client.prepareVideo("A mountain", "16:9", null, 2, List.of(), "request")).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> client.prepareVideo("A mountain", "16:9", null, 5,
                List.of(new Reference("END_FRAME", "AQID")), "request")).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> client.prepareImage("A mountain", "1:1", "1K",
                List.of(new Reference("REFERENCE", "data:image/png;base64,AQID")))).isInstanceOf(ApiProblemException.class);
        assertThat(paths).isEmpty();
    }

    @Test void malformedReceiptAndServerFailureStayUnknownWithoutAutomaticResubmission() {
        var body = client.prepareImage("A mountain", "1:1", "1K", List.of());
        for (String invalid : List.of("{\"code\":0,\"data\":{}}", "not-json", "{\"code\":0,\"data\":{\"task_id\":\"image-1\",\"task_status\":\"succeeded\"}}")) {
            response = invalid;
            assertThatThrownBy(() -> client.createImage(null, "synthetic-key", body)).isInstanceOf(KlingClient.Uncertain.class);
        }
        response = "{}"; status = 503;
        assertThatThrownBy(() -> client.createImage(null, "synthetic-key", body)).isInstanceOf(KlingClient.Uncertain.class);
        assertThat(paths).hasSize(4);
        status = 200;
        for (int code : List.of(1001, 1101, 1201, 1303)) {
            response = "{\"code\":" + code + ",\"message\":\"documented refusal\"}";
            assertThatThrownBy(() -> client.createImage(null, "synthetic-key", body)).isInstanceOf(KlingClient.Rejected.class);
        }
        response = "{\"code\":5000,\"message\":\"internal failure\"}";
        assertThatThrownBy(() -> client.createImage(null, "synthetic-key", body)).isInstanceOf(KlingClient.Uncertain.class);
        response = "{}"; status = 401;
        assertThatThrownBy(() -> client.createImage(null, "synthetic-key", body)).isInstanceOf(KlingClient.Rejected.class);
        status = 410;
        assertThatThrownBy(() -> client.queryImage(null, "synthetic-key", "image-1")).isInstanceOf(KlingClient.TaskExpired.class);
        status = 403;
        assertThatThrownBy(() -> client.queryImage(null, "synthetic-key", "image-1")).isInstanceOf(KlingClient.CredentialUnavailable.class);
    }

    @Test void onlyOfficialRegionalApiRootsCanBeConfigured() {
        assertThat(KlingClient.validatedOrigin(null)).isEqualTo(KlingClient.ORIGIN);
        assertThat(KlingClient.validatedOrigin(KlingClient.ORIGIN + "/")).isEqualTo(KlingClient.ORIGIN);
        assertThat(KlingClient.validatedOrigin(KlingClient.BEIJING_ORIGIN + "/")).isEqualTo(KlingClient.BEIJING_ORIGIN);
        for (String invalid : List.of("https://api.klingai.com", KlingClient.ORIGIN + "/v1", "http://api-singapore.klingai.com", "https://user@api-singapore.klingai.com", KlingClient.BEIJING_ORIGIN + "/v1", "https://api-beijing.klingai.com.untrusted.example"))
            assertThatThrownBy(() -> KlingClient.validatedOrigin(invalid)).isInstanceOf(ApiProblemException.class);
    }
}
