package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.provider.domain.SeedreamModels;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Local fake synchronous Ark API and synthetic rasters; no real generation or paid request. */
class SeedreamClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SeedreamModels models = new SeedreamModels();
    private HttpServer server;
    private SeedreamClient client;
    private static final String PATH = "/api/v3/images/generations";

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new SeedreamClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void postsOnceAndCapturesEveryGroupSuccessWithSafeUsageAndStableIndices() throws Exception {
        var p = models.resolve(SeedreamModels.LITE, SeedreamModels.GENERATE);
        var values = mapper.createObjectNode().put("prompt", "A group").put("size", "2048x2048")
                .put("sequential_image_generation", "auto").put("max_images", 3).put("web_search", true).put("watermark", false);
        String jpg = base64(2048, 2048, "jpeg");
        ObjectNode response = response(p);
        var data = response.putArray("data");
        data.add(item(jpg, "2048x2048", "jpeg"));
        data.addObject().putObject("error").put("code", "ContentFiltered").put("message", "private upstream details");
        data.add(item(jpg, "2048x2048", "jpeg"));
        response.putObject("usage").put("generated_images", 2).put("output_tokens", 2).put("reasoning", "private reasoning")
                .putObject("tool_usage").put("web_search", 1).put("internal_reasoning", "discard");
        AtomicInteger creates = new AtomicInteger();
        server.createContext(PATH, exchange -> {
            creates.incrementAndGet();
            var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-key");
            assertThat(body.path("model").asText()).isEqualTo(p.model());
            assertThat(body.path("stream").asBoolean()).isFalse();
            assertThat(body.path("response_format").asText()).isEqualTo("b64_json");
            assertThat(body.at("/sequential_image_generation_options/max_images").asInt()).isEqualTo(3);
            assertThat(body.at("/tools/0/type").asText()).isEqualTo("web_search");
            exchange.getResponseHeaders().add("X-Request-Id", "synthetic-provider-id");
            respond(exchange, 200, response.toString());
        });
        var result = client.create(p, values, null, "synthetic-key", client.build(p, values, Map.of()), "local-request");
        assertThat(result.requestId()).isEqualTo("synthetic-provider-id");
        assertThat(result.manifest().results()).hasSize(2);
        assertThat(result.manifest().results().get(1).nodeId()).endsWith(":2");
        assertThat(result.manifest().usage().toString()).doesNotContain("reasoning", "private", "internal");
        assertThat(result.toString()).doesNotContain(jpg);
        assertThat(SeedreamClient.inlineBytes(result.manifest().results().getFirst().url())).isNotEmpty();
        assertThat(creates).hasValue(1);
    }

    @Test void layersRetainOrderedBboxesAndPngAlphaWithoutFlatteningIntoOneImage() throws Exception {
        var p = models.resolve(SeedreamModels.PRO, SeedreamModels.LAYERS);
        var values = mapper.createObjectNode().put("image1", UUID.randomUUID().toString()).put("output_format", "png");
        ObjectNode response = response(p);
        var data = response.putArray("data");
        var layer = item(base64(32, 32, "png"), "32x32", "png").put("z_index", 1).put("name", "Synthetic layer").put("description", "Blue square");
        layer.putObject("bounding_box").set("absolute", mapper.readTree("[10,20,42,52]"));
        ((ObjectNode) layer.path("bounding_box")).set("normalized", mapper.readTree("[10,20,41,51]"));
        data.add(layer); data.add(item(base64(1024, 1024, "png"), "1024x1024", "png").put("z_index", 0));
        var manifest = client.manifest(p, values, response);
        assertThat(manifest.results()).hasSize(2);
        assertThat(manifest.results().getFirst().providerMetadata().path("z_index").asInt()).isZero();
        assertThat(manifest.results().get(1).providerMetadata().at("/bounding_box/absolute")).isEqualTo(mapper.readTree("[10,20,42,52]"));
        assertThat(manifest.results().get(1).url()).startsWith("data:image/png;base64,");
        data.add(layer.deepCopy());
        assertThatThrownBy(() -> client.manifest(p, values, response)).isInstanceOf(NativeMediaFailure.class);
    }

    @Test void buildsNativeEditTagsAndRejectsAnyRawBodyOrExternalMediaBypass() throws Exception {
        var p = models.resolve(SeedreamModels.FLASH, SeedreamModels.EDIT);
        var values = mapper.createObjectNode().put("prompt", "Replace").put("image1", UUID.randomUUID().toString())
                .put("edit_regions", "[{\"image\":1,\"type\":\"point\",\"coordinates\":[400,500],\"instruction\":\"Add a hat\"}]");
        String uri = "data:image/png;base64," + base64(512, 512, "png");
        var body = client.build(p, values, Map.of("image1", uri));
        assertThat(body.path("prompt").asText()).contains("<point>400 500</point> Add a hat");
        assertThat(body.has("edit_regions")).isFalse();
        body.putArray("tools").addObject().put("type", "arbitrary_tool");
        assertThatThrownBy(() -> client.create(p, values, null, "synthetic-key", body, "local-request")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> client.build(p, values, Map.of("image1", "https://example.org/private.png"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SeedreamClient.validatedOrigin("https://127.0.0.1")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new SeedreamClient(mapper, URI.create("http://127.0.0.1:1000/path"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void lostResponsesAnd503NeverAutomaticallyRepeatAPaidPost() {
        var p = models.resolve(SeedreamModels.PRO, SeedreamModels.GENERATE);
        var values = mapper.createObjectNode().put("prompt", "Synthetic");
        AtomicInteger creates = new AtomicInteger();
        server.createContext(PATH, exchange -> {
            creates.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Retry-After", "0");
            respond(exchange, 503, "{\"error\":{\"message\":\"private\"}}");
        });
        assertThatThrownBy(() -> client.create(p, values, null, "synthetic-key", client.build(p, values, Map.of()), "local"))
                .isInstanceOf(SeedreamClient.Uncertain.class).hasMessage(SeedreamClient.CREATE_UNCERTAIN);
        assertThat(creates).hasValue(1);
    }

    @Test void permanentRefusalIsDistinctFromAnUncapturableAcceptedResponse() {
        var p = models.resolve(SeedreamModels.PRO, SeedreamModels.GENERATE);
        var values = mapper.createObjectNode().put("prompt", "Synthetic");
        AtomicInteger creates = new AtomicInteger();
        server.createContext(PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (creates.incrementAndGet() == 1) respond(exchange, 400, "{\"error\":{\"message\":\"private\"}}");
            else respond(exchange, 200, "{\"model\":\"wrong-model\",\"created\":1,\"data\":[]}");
        });
        assertThatThrownBy(() -> client.create(p, values, null, "synthetic-key", client.build(p, values, Map.of()), "local"))
                .isInstanceOf(SeedreamClient.Rejected.class).hasMessage(SeedreamClient.CREATE_REJECTED);
        assertThatThrownBy(() -> client.create(p, values, null, "synthetic-key", client.build(p, values, Map.of()), "local"))
                .isInstanceOf(SeedreamClient.Uncertain.class).hasMessage("PROVIDER_PROTOCOL_INVALID");
        assertThat(creates).hasValue(2);
    }

    @Test void acceptsLocalIdentityAndRejectsMimeOrDeclaredSizeMismatch() throws Exception {
        var p = models.resolve(SeedreamModels.PRO, SeedreamModels.GENERATE);
        var values = mapper.createObjectNode().put("prompt", "Synthetic").put("size", "1K");
        var response = response(p);
        response.putArray("data").add(item(base64(1024, 1024, "jpeg"), "1024x1024", "jpeg"));
        server.createContext(PATH, exchange -> { exchange.getRequestBody().readAllBytes(); respond(exchange, 200, response.toString()); });
        assertThat(client.create(p, values, null, "synthetic-key", client.build(p, values, Map.of()), "local-request").requestId()).isEqualTo("local-request");
        ((ObjectNode) response.path("data").get(0)).put("size", "2048x2048");
        assertThatThrownBy(() -> client.manifest(p, values, response)).isInstanceOf(NativeMediaFailure.class);
        assertThatThrownBy(() -> SeedreamClient.inlineBytes("data:image/png;base64," + base64(20, 20, "jpeg"))).isInstanceOf(NativeMediaFailure.class);
        assertThatThrownBy(() -> SeedreamClient.inlineBytes("https://example.org/image.png")).isInstanceOf(NativeMediaFailure.class);
    }

    private ObjectNode response(NativeMediaProtocol.Profile p) { return mapper.createObjectNode().put("model", p.model()).put("created", 1); }
    private ObjectNode item(String b64, String size, String format) { return mapper.createObjectNode().put("b64_json", b64).put("size", size).put("output_format", format); }
    private static String base64(int width, int height, String format) throws IOException {
        var output = new ByteArrayOutputStream();
        var image = new BufferedImage(width, height, "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, format, output);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var target = exchange.getResponseBody()) { target.write(bytes); }
    }
}
