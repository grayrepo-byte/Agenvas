package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Fake HTTP protocol tests; these never call a paid provider. */
class AutoDlClientTest {
    private static final String ID = "fake-task-123";
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private AutoDlClient client;
    private String response;
    private int status;
    private final AtomicInteger calls = new AtomicInteger();
    private String receivedPath;
    private String receivedAuthorization;
    private String receivedBody;
    @BeforeEach void start() throws IOException {
        status = 200;
        response = "{\"code\":\"Success\",\"data\":{\"task_id\":\"" + ID + "\",\"status\":\"QUEUED\"}}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            receivedPath = exchange.getRequestURI().getPath();
            receivedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (status == 0) { exchange.close(); return; }
            exchange.getResponseHeaders().set("Content-Type", receivedPath.contains("/outputs/") ? "video/mp4" : "application/json");
            if (status == 503) exchange.getResponseHeaders().set("Retry-After", "0");
            if (status == 302) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/private");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        client = new AutoDlClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void submitsDataUrlsWithRawTokenThenQueriesOnlyPersistedId() {
        var body = mapper.createObjectNode().put("prompt", "test").put("duration", 1)
                .put("ref_image_0", "data:image/png;base64,aGVsbG8=");
        assertThat(client.create("fake-token", "minimax_h3_z0903", body)).isEqualTo(ID);
        assertThat(receivedAuthorization).isEqualTo("fake-token");
        assertThat(receivedPath).endsWith("/minimax_h3_z0903");
        assertThat(mapper.readTree(receivedBody)).isEqualTo(body);
        assertThat(client.query("fake-token", ID).status()).isEqualTo("QUEUED");
        assertThat(receivedPath).endsWith("/result/" + ID);
        assertThat(receivedBody).isEmpty();
    }
    @Test void lostResponseMissingIdAnd503NeverRepeatSubmission() {
        for (int code : new int[] {0, 503, 200}) {
            status = code; response = "{\"code\":\"Success\",\"data\":{}}";
            int before = calls.get();
            assertThatThrownBy(() -> client.create("fake-token", "minimax_h3_z0903", mapper.createObjectNode()))
                    .isInstanceOf(AutoDlClient.Uncertain.class);
            assertThat(calls.get()).isEqualTo(before + 1);
        }
    }
    @Test void permissionDenialIsKnownRejection() {
        status = 403;
        assertThatThrownBy(() -> client.create("fake-token", "minimax_h3_z0903", mapper.createObjectNode()))
                .isInstanceOf(AutoDlClient.Rejected.class);
        assertThat(calls).hasValue(1);
    }
    @Test void parsesCompletedEnvelopeAndRejectsDifferentTaskOrUnknownStatus() {
        response = "{\"code\":\"Success\",\"data\":{\"task_id\":\""+ID+"\",\"status\":\"completed\","
                + "\"results\":[{\"url\":\"https://example.invalid/video.mp4\",\"type\":\"video\",\"output_type\":\"output\"}]}}";
        assertThat(client.query("fake-token", ID).results()).hasSize(1);
        assertThat(client.query("fake-token", ID).status()).isEqualTo("SUCCESS");
        assertThatThrownBy(() -> client.query("fake-token", "different-task"))
                .isInstanceOf(AutoDlClient.ProtocolFailure.class);
        response = response.replace("completed", "surprise");
        assertThatThrownBy(() -> client.query("fake-token", ID)).isInstanceOf(AutoDlClient.ProtocolFailure.class);
    }
    @Test void downloadsWithoutTokenAndRejectsUntrustedHostsPathsAndRedirects() throws IOException {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/comfyui/outputs/test.mp4";
        response = "fake-media";
        try (var payload = client.downloadVideo(url)) {
            assertThat(new String(payload.stream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(response);
        }
        assertThat(receivedAuthorization).isNull();
        for (String bad : new String[] {"http://169.254.169.254/comfyui/outputs/x.mp4",
                "https://example.com/comfyui/outputs/x.mp4", url.replace("outputs", "%2e%2e"),
                url.replace("/comfyui/outputs", "/private")})
            assertThatThrownBy(() -> client.downloadVideo(bad)).isInstanceOf(AutoDlClient.ResultRejected.class);
        int before = calls.get(); status = 302;
        assertThatThrownBy(() -> client.downloadVideo(url)).isInstanceOf(AutoDlClient.ResultRejected.class);
        assertThat(calls.get()).isEqualTo(before + 1);
    }
}
