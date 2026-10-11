package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.provider.domain.GoogleVideoModels;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.domain.Task;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** All HTTP/media are synthetic; this does not demonstrate real Google connectivity. */
class GoogleVideoClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> paths = new ArrayList<>();
    private final List<JsonNode> bodies = new ArrayList<>();
    private HttpServer server;
    private GoogleVideoClient client;
    private String response = "{}", location;
    private int status = 200;
    private boolean dropped;
    private String type = "application/json";
    private byte[] binary;
    private static final byte[] MP4_HEADER = new byte[]{0, 0, 0, 12, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            assertThat(exchange.getRequestHeaders().getFirst("x-goog-api-key")).isEqualTo("synthetic-key");
            if (exchange.getRequestURI().getRawQuery() != null) assertThat(exchange.getRequestURI().getRawQuery()).doesNotContain("key=");
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (request.length > 0) bodies.add(mapper.readTree(request));
            if (dropped) { exchange.close(); return; }
            byte[] bytes = binary == null ? response.getBytes(StandardCharsets.UTF_8) : binary;
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Retry-After", "0");
            if (location != null) exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        client = new GoogleVideoClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void veoUsesNativeLongRunningRouteAndPackedOriginalReferenceBytes() {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        ObjectNode values = values(p, mapper.createObjectNode().put("image_3", UUID.randomUUID().toString())
                .put("negativePrompt", "No lettering").put("seed", 13).put("enhancePrompt", false));
        var body = client.build(p, values, Map.of("image_3", new GoogleVideoClient.Input("image/png", new byte[]{1, 2, 3})));
        response = "{\"name\":\"models/veo-3.1-generate-preview/operations/synthetic-1\"}";
        assertThat(client.create(p, GoogleVideoClient.ORIGIN + "/v1", "synthetic-key", body)).endsWith("/synthetic-1");
        assertThat(paths).containsExactly("/v1beta/models/veo-3.1-generate-preview:predictLongRunning");
        assertThat(body.at("/instances/0/referenceImages/0/image/bytesBase64Encoded").asText()).isEqualTo("AQID");
        assertThat(body.at("/instances/0/referenceImages/0/referenceType").asText()).isEqualTo("asset");
        assertThat(body.at("/parameters/sampleCount").asInt()).isEqualTo(1);
        assertThat(body.at("/parameters/seed").asInt()).isEqualTo(13);
        assertThat(body.at("/parameters/enhancePrompt").asBoolean()).isFalse();
    }
    @Test void omniKeepsNativeSnakeCaseAndTaskAndExplicitRecoveryTransport() {
        var p = profile(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "EDIT");
        ObjectNode values = values(p, mapper.createObjectNode().put("base_video", UUID.randomUUID().toString())
                .put("duration", "10s").put("background", false).put("resolution", "4k"));
        var body = client.build(p, values, Map.of("base_video", new GoogleVideoClient.Input("video/mp4", MP4_HEADER)));
        response = "{\"id\":\"v1_synthetic\",\"status\":\"in_progress\",\"model\":\"gemini-omni-1.1-flash\"}";
        assertThat(client.create(p, null, "synthetic-key", body)).isEqualTo("v1_synthetic");
        assertThat(paths).containsExactly("/v1beta/interactions");
        assertThat(body.at("/generation_config/video_config/task").asText()).isEqualTo("edit");
        assertThat(body.at("/response_format/duration").asText()).isEqualTo("10s");
        assertThat(body.at("/response_format/resolution").asText()).isEqualTo("4k");
        assertThat(body.path("store").asBoolean()).isTrue();
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("background").asBoolean()).isFalse();
        assertThat(body.path("input").size()).isEqualTo(2);
        assertThat(body.at("/input/0/mime_type").asText()).isEqualTo("video/mp4");
        assertThat(body.at("/input/1/text").asText()).startsWith("[# Sources <VIDEO_0>@Video1] ");
    }
    @Test void omniRolesUseActualPackedSourceAndReferencePositionsAcrossSparseSlots() {
        var p = profile(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "GENERATE");
        ObjectNode values = values(p, mapper.createObjectNode().put("first_frame", UUID.randomUUID().toString())
                .put("last_frame", UUID.randomUUID().toString()).put("image_5", UUID.randomUUID().toString())
                .put("video_3", UUID.randomUUID().toString()));
        var image = new GoogleVideoClient.Input("image/png", new byte[]{1, 2, 3});
        var body = client.build(p, values, Map.of("first_frame", image, "last_frame", image, "image_5", image,
                "video_3", new GoogleVideoClient.Input("video/mp4", MP4_HEADER)));
        assertThat(body.at("/input/4/text").asText()).isEqualTo("[# Sources <FIRST_FRAME>@Image1 <LAST_FRAME>@Image2] "
                + "[# References <IMAGE_REF_0>@Image3 <VIDEO_REF_0>@Video1] Synthetic landscape");
        assertThat(body.at("/generation_config/video_config").isMissingNode()).isTrue();
        var extended = profile(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "EXTEND");
        ObjectNode extensionValues = values(extended, mapper.createObjectNode().put("base_video", UUID.randomUUID().toString())
                .put("video_3", UUID.randomUUID().toString()));
        var extension = client.build(extended, extensionValues, Map.of("base_video", new GoogleVideoClient.Input("video/mp4", MP4_HEADER),
                "video_3", new GoogleVideoClient.Input("video/mp4", MP4_HEADER)));
        assertThat(extension.at("/input/2/text").asText()).startsWith("[# Sources <VIDEO_0>@Video1] [# References <VIDEO_REF_0>@Video2] ");
        assertThat(extension.at("/generation_config/video_config/task").asText()).isEqualTo("extend");
    }
    @Test void originalOperationPollingHasStrictIdentityAndOneGoogleFileOutput() {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        response = "{\"name\":\"operations/synthetic\"}";
        assertThat(client.query(p, null, "synthetic-key", "operations/synthetic").status()).isEqualTo("pending");
        response = "{\"name\":\"operations/synthetic\",\"done\":true,\"response\":{\"generateVideoResponse\":{\"generatedSamples\":[{\"video\":{\"uri\":\"https://generativelanguage.googleapis.com/v1beta/files/synthetic-video\"}}]}}}";
        assertThat(client.query(p, null, "synthetic-key", "operations/synthetic").video().fileName()).isEqualTo("files/synthetic-video");
        assertThat(paths).containsOnly("/v1beta/operations/synthetic");
        response = response.replace("operations/synthetic", "operations/other");
        assertThatThrownBy(() -> client.query(p, null, "synthetic-key", "operations/synthetic")).isInstanceOf(NativeMediaFailure.class);
        assertThatThrownBy(() -> client.query(p, null, "synthetic-key", "../synthetic")).isInstanceOf(NativeMediaFailure.class);
    }
    @Test void interactionRecoveryReadsModelOutputOnlyAndAcceptsInlineInsteadOfInitialUriDelivery() {
        var p = profile(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "GENERATE");
        var result = mapper.createObjectNode().put("id", "v1_synthetic").put("model", GoogleVideoModels.OMNI_MODEL).put("status", "completed");
        var steps = result.putArray("steps");
        steps.addObject().put("type", "thought").putArray("content").addObject().put("type", "video").put("data", "not-media");
        steps.addObject().put("type", "model_output").putArray("content").addObject().put("type", "video")
                .put("mime_type", "video/mp4").put("data", Base64.getEncoder().encodeToString(MP4_HEADER));
        response = result.toString();
        assertThat(client.query(p, null, "synthetic-key", "v1_synthetic").video().bytes()).isEqualTo(MP4_HEADER);
        assertThat(paths).containsExactly("/v1beta/interactions/v1_synthetic");
        ((ObjectNode) steps.path(1).path("content").path(0)).put("uri", GoogleVideoClient.ORIGIN + "/v1beta/files/synthetic");
        response = result.toString();
        assertThatThrownBy(() -> client.query(p, null, "synthetic-key", "v1_synthetic")).isInstanceOf(NativeMediaFailure.class);
    }
    @Test void longVeoOperationNamesKeepTheirCompleteIdentityThroughCreateAndHttpQuery() {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        ObjectNode body = client.build(p, values(p, mapper.createObjectNode()), Map.of());
        String createPath = "/v1beta/models/" + p.model() + ":predictLongRunning";
        List<String> expectedPaths = new ArrayList<>();
        for (String prefix : List.of("operations/", "models/" + p.model() + "/operations/")) {
            String originalId = prefix + "a".repeat(512 - prefix.length());
            response = mapper.createObjectNode().put("name", originalId).toString();
            assertThat(client.create(p, null, "synthetic-key", body)).isEqualTo(originalId).hasSize(512);
            var result = mapper.createObjectNode().put("name", originalId).put("done", true);
            result.putObject("response").putObject("generateVideoResponse").putArray("generatedSamples")
                    .addObject().putObject("video").put("uri", GoogleVideoClient.ORIGIN + "/v1beta/files/synthetic-video");
            response = result.toString();
            assertThat(client.query(p, null, "synthetic-key", originalId).video().fileName()).isEqualTo("files/synthetic-video");
            expectedPaths.add(createPath);
            expectedPaths.add("/v1beta/" + originalId);
        }
        assertThat(paths).containsExactlyElementsOf(expectedPaths);
        String tooLong = "operations/" + "b".repeat(513 - "operations/".length());
        response = mapper.createObjectNode().put("name", tooLong).toString();
        assertThatThrownBy(() -> client.create(p, null, "synthetic-key", body)).isInstanceOf(GoogleVideoClient.Unknown.class);
        assertThat(paths).hasSize(5);
        assertThatThrownBy(() -> client.query(p, null, "synthetic-key", tooLong)).isInstanceOf(NativeMediaFailure.class);
        assertThat(paths).hasSize(5);
    }
    @Test void malformedCreateAndLostResponseStayUnknownWithNoPostRetry() {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        ObjectNode body = client.build(p, values(p, mapper.createObjectNode()), Map.of());
        for (String bad : List.of("not-json", "{}", "{\"name\":\"models/another/operations/synthetic\"}")) {
            response = bad;
            assertThatThrownBy(() -> client.create(p, null, "synthetic-key", body)).isInstanceOf(GoogleVideoClient.Unknown.class);
        }
        status = 503;
        assertThatThrownBy(() -> client.create(p, null, "synthetic-key", body)).isInstanceOf(GoogleVideoClient.Unknown.class);
        assertThat(paths).hasSize(4);
        status = 200; dropped = true;
        assertThatThrownBy(() -> client.create(p, null, "synthetic-key", body)).isInstanceOf(GoogleVideoClient.Unknown.class);
        assertThat(paths).hasSize(5);
    }
    @Test void inlineVideoUsesItsBoundedNativeParserBeyondOrdinaryTextLength() {
        var p = profile(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "GENERATE");
        byte[] bytes = java.util.Arrays.copyOf(MP4_HEADER, 16 * 1024 * 1024);
        response = "{\"id\":\"v1_synthetic\",\"status\":\"completed\",\"model\":\"gemini-omni-1.1-flash\",\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"video\",\"mime_type\":\"video/mp4\",\"data\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\"}]}]}";
        assertThat(client.query(p, null, "synthetic-key", "v1_synthetic").video().bytes()).isEqualTo(bytes);
    }
    @Test void duplicateOrTrailingJsonNeverCreatesAmbiguousAcknowledgedIdentity() {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        ObjectNode body = client.build(p, values(p, mapper.createObjectNode()), Map.of());
        for (String ambiguous : List.of("{\"name\":\"operations/a\",\"name\":\"operations/b\"}", "{\"name\":\"operations/a\"}{}")) {
            response = ambiguous;
            assertThatThrownBy(() -> client.create(p, null, "synthetic-key", body)).isInstanceOf(GoogleVideoClient.Unknown.class);
        }
        assertThat(paths).hasSize(2);
    }
    @Test void queryFailuresArePermanentOnlyForKnownRejectionAndExpiry() {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        status = 403;
        assertThatThrownBy(() -> client.query(p, null, "synthetic-key", "operations/synthetic"))
                .isInstanceOf(NativeMediaFailure.class).hasMessage("MEDIA_CREDENTIAL_UNAVAILABLE");
        status = 404;
        assertThatThrownBy(() -> client.query(p, null, "synthetic-key", "operations/synthetic")).hasMessage(GoogleVideoClient.TASK_EXPIRED);
        status = 503;
        assertThatThrownBy(() -> client.query(p, null, "synthetic-key", "operations/synthetic")).isInstanceOf(GoogleVideoClient.TechnicalFailure.class);
    }
    @Test void filesWaitForActiveAndDownloadOnlyAuthenticatedFixedPath() throws Exception {
        response = "{\"name\":\"files/synthetic-video\",\"state\":\"PROCESSING\",\"mimeType\":\"video/mp4\"}";
        assertThat(client.fileActive(null, "synthetic-key", "files/synthetic-video")).isFalse();
        response = response.replace("PROCESSING", "ACTIVE");
        assertThat(client.fileActive(null, "synthetic-key", "files/synthetic-video")).isTrue();
        binary = MP4_HEADER; type = "video/mp4";
        var target = new ByteArrayOutputStream();
        client.download(null, "synthetic-key", "files/synthetic-video", target);
        assertThat(target.toByteArray()).isEqualTo(MP4_HEADER);
        assertThat(paths.getLast()).isEqualTo("/v1beta/files/synthetic-video:download?alt=media");
        status = 302; location = "https://storage.googleapis.com.evil.invalid/result.mp4";
        assertThatThrownBy(() -> client.download(null, "synthetic-key", "files/synthetic-video", target))
                .hasMessage(ProviderFailureCodes.RESULT_URL_INVALID);
        assertThat(paths).hasSize(4);
    }
    @Test void downloadsCannotUseImageRelayOriginsSignedUrisOrUnrelatedGooglePaths() {
        for (String bad : List.of("https://relay.example.invalid/v1beta", GoogleVideoClient.ORIGIN + "/unrelated", "http://generativelanguage.googleapis.com"))
            assertThatThrownBy(() -> GoogleVideoClient.validatedOrigin(bad)).isInstanceOf(NativeMediaFailure.class);
        for (String bad : List.of("https://generativelanguage.googleapis.com.evil.invalid/v1beta/files/synthetic",
                "https://generativelanguage.googleapis.com/v1beta/files/synthetic?key=fake", "https://generativelanguage.googleapis.com/v1beta/models/synthetic",
                "https://user@generativelanguage.googleapis.com/v1beta/files/synthetic", "https://storage.googleapis.com/object"))
            assertThatThrownBy(() -> GoogleVideoClient.fileName(bad)).isInstanceOf(NativeMediaFailure.class);
        assertThat(GoogleVideoClient.fileName(GoogleVideoClient.ORIGIN + "/download/v1beta/files/synthetic:download?alt=media")).isEqualTo("files/synthetic");
        assertThat(paths).isEmpty();
    }
    private NativeMediaProtocol.Profile profile(String adapter, String model, String operation) { return new NativeMediaProtocol.Profile(adapter, model, operation, Task.Kind.VIDEO_GENERATION); }
    private ObjectNode values(NativeMediaProtocol.Profile p, ObjectNode overrides) {
        return NativeMediaProfiles.effectiveValues(new NativeMediaProfiles.Selection(new GoogleVideoModels(), p),
                mapper.createObjectNode().set("dynamicValues", overrides), "Synthetic landscape", null, mapper);
    }
}
