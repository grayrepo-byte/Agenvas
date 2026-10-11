package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** All requests use fake HTTP. No live credentials, generation or CDN downloads. */
class KlingFullProtocolClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";
    private HttpServer server;
    private KlingClient client;
    private final List<String> paths = new ArrayList<>();
    private String response;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-key");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start(); client = new KlingClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void normalImageKeepsNativeNegativePromptAndMultipleResultsInOneSubmission() {
        var profile = image("kling-v3", "GENERATE");
        var v = values(profile, mapper.createObjectNode().put("negative_prompt", "blur").put("n", 3));
        var body = client.build(profile, v, Map.of(), "stable-key");
        assertThat(body.path("negative_prompt").asText()).isEqualTo("blur");
        assertThat(body.path("prompt").asText()).isEqualTo("Prompt");
        assertThat(body.path("n").asInt()).isEqualTo(3);
        assertThat(body.path("external_task_id").asText()).isEqualTo("stable-key");
        response = "{\"code\":0,\"data\":{\"task_id\":\"image-1\",\"task_status\":\"submitted\"}}";
        assertThat(client.create(profile, null, "synthetic-key", body)).isEqualTo("image-1");
        assertThat(paths).containsExactly("/v1/images/generations");
        response = "{\"code\":0,\"data\":{\"task_id\":\"image-1\",\"task_status\":\"succeed\",\"task_result\":{\"images\":[{\"index\":2,\"url\":\"https://h1.inkwai.com/c.png\"},{\"index\":0,\"url\":\"https://h1.inkwai.com/a.png\",\"watermark_url\":\"https://h1.inkwai.com/aw.png\"},{\"index\":1,\"url\":\"https://h1.inkwai.com/b.png\"}]}}}";
        var state = client.query(profile, null, "synthetic-key", "image-1", "single");
        assertThat(state.outputs()).extracting(KlingClient.Output::ordinal).containsExactly(0, 1, 2);
        assertThat(state.outputs().getFirst().watermarkUrl()).endsWith("aw.png");
        assertThat(paths).hasSize(2);
    }

    @Test void omniSeriesUsesItsOwnPathAndResultArray() {
        var profile = image("kling-v3-omni", "GENERATE");
        var v = values(profile, mapper.createObjectNode().put("image_1", A).put("image_2", B)
                .put("elementId1", "9223372036854775807").put("resolution", "4k").put("result_type", "series").put("series_amount", "auto").put("watermark", true));
        var body = client.build(profile, v, Map.of("image_1", "AQID", "image_2", "BAUG"), "series-key");
        assertThat(body.has("n")).isFalse();
        assertThat(body.path("series_amount").asText()).isEqualTo("auto");
        assertThat(body.path("image_list")).hasSize(2);
        assertThat(body.path("element_list").get(0).path("element_id").asLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(body.path("watermark_info").path("enabled").asBoolean()).isTrue();
        response = "{\"code\":0,\"data\":{\"task_id\":\"series-1\",\"task_status\":\"submitted\"}}";
        client.create(profile, KlingClient.BEIJING_ORIGIN, "synthetic-key", body);
        assertThat(paths.getLast()).isEqualTo("/v1/images/omni-image");
        response = "{\"code\":0,\"data\":{\"task_id\":\"series-1\",\"task_status\":\"succeed\",\"task_result\":{\"result_type\":\"series\",\"images\":[{\"index\":0,\"url\":\"https://h1.inkwai.com/unselected.png\"}],\"series_images\":[{\"index\":1,\"url\":\"https://h1.inkwai.com/second.png\"},{\"index\":0,\"url\":\"https://h1.inkwai.com/first.png\"}]}}}";
        assertThat(client.query(profile, null, "synthetic-key", "series-1", "series").outputs()).hasSize(2);
        assertThatThrownBy(() -> client.query(profile, null, "synthetic-key", "series-1", "single")).isInstanceOf(KlingClient.ProtocolFailure.class);
    }

    @Test void multiExpandAndCompletionHaveDistinctNativeBodies() {
        var multi = image("kling-v2-1", "MULTI_IMAGE");
        var body = client.build(multi, values(multi, mapper.createObjectNode().put("image_1", A).put("scene_image", B)),
                Map.of("image_1", "AQID", "scene_image", "BAUG"), "multi-key");
        assertThat(body.path("subject_image_list").get(0).path("subject_image").asText()).isEqualTo("AQID");
        assertThat(body.path("scene_image").asText()).isEqualTo("BAUG");
        assertThat(body.has("image_list")).isFalse(); assertThat(body.has("resolution")).isFalse();
        var expand = image("", "EXPAND");
        body = client.build(expand, values(expand, mapper.createObjectNode().put("image_1", A).put("left_expansion_ratio", 2)), Map.of("image_1", "AQID"), "expand-key");
        assertThat(body.has("model_name")).isFalse(); assertThat(body.path("left_expansion_ratio").asInt()).isEqualTo(2);
        var completion = image("", "SUBJECT_COMPLETION");
        body = client.build(completion, values(completion, mapper.createObjectNode().put("image_1", A)), Map.of("image_1", "AQID"), "complete-key");
        assertThat(body.path("element_frontal_image").asText()).isEqualTo("AQID");
        assertThat(body.has("prompt")).isFalse(); assertThat(body.has("n")).isFalse();
        response = "{\"code\":0,\"data\":{\"task_id\":\"complete-1\",\"task_status\":\"succeed\",\"task_result\":{\"images\":[{\"index\":0,\"url_1\":\"https://h1.inkwai.com/a.png\",\"url_2\":\"https://h1.inkwai.com/b.png\",\"url_3\":\"https://h1.inkwai.com/c.png\"}]}}}";
        assertThat(client.query(completion, null, "synthetic-key", "complete-1", "single").outputs()).hasSize(3);
    }

    @Test void allNewVideoRoutesSerializeOnlyDeclaredSettingsAndPinnedContentIds() {
        var turbo = video("kling-3.0-turbo", "GENERATE");
        var body = client.build(turbo, values(turbo, mapper.createObjectNode().put("first_frame", A)), Map.of("first_frame", "AQID"), "video-key");
        assertThat(body.path("settings").has("audio")).isFalse(); assertThat(body.path("settings").has("multi_shot")).isFalse();
        assertThat(body.path("contents").get(1).has("id")).isFalse();
        response = "{\"code\":0,\"data\":{\"id\":\"video-1\",\"status\":\"submitted\"}}";
        client.create(turbo, null, "synthetic-key", body);
        assertThat(paths.getLast()).isEqualTo("/image-to-video/kling-3.0-turbo");
        var omni = video("kling-3.0-omni", "GENERATE");
        body = client.build(omni, values(omni, mapper.createObjectNode().put("feature_video", A).put("image_1", B).put("elementId1", "subject-generated-id")),
                Map.of("feature_video", "https://relay.example/feature.mp4", "image_1", "BAUG"), "omni-key");
        assertThat(body.path("contents").toString()).contains("feature_video", "refer_image", "element_1");
        assertThat(body.path("contents").get(1).path("id").asText()).isEqualTo("image_1");
        assertThat(body.path("contents").get(3).path("element_id").isTextual()).isTrue();
        assertThat(body.path("contents").get(3).path("element_id").asText()).isEqualTo("subject-generated-id");
        client.create(omni, null, "synthetic-key", body);
        assertThat(paths.getLast()).isEqualTo("/omni-video/kling-3.0-omni");
        var frames = client.build(omni, values(omni, mapper.createObjectNode().put("first_frame", A).put("last_frame", B)),
                Map.of("first_frame", "AQID", "last_frame", "BAUG"), "omni-frames-key");
        assertThat(frames.path("contents").get(1).path("id").asText()).isEqualTo("first_frame");
        assertThat(frames.path("contents").get(2).path("id").asText()).isEqualTo("last_frame");
        var motion = video("kling-2.6", "MOTION_CONTROL");
        body = client.build(motion, values(motion, mapper.createObjectNode().put("image_1", A).put("motion_video", B)),
                Map.of("image_1", "AQID", "motion_video", "https://relay.example/motion.mp4"), "motion-key");
        assertThat(body.path("settings").has("duration")).isFalse();
        assertThat(body.path("contents").get(1).has("id")).isFalse();
        assertThat(body.path("contents").get(2).has("id")).isFalse();
        client.create(motion, null, "synthetic-key", body);
        assertThat(paths.getLast()).isEqualTo("/motion-control/kling-2.6");
    }

    @Test void newGenericReceiptFailuresNeverResubmitAndQueryNeverSelectsAnotherTask() {
        var p = video("kling-3.0", "GENERATE");
        var body = client.build(p, values(p, mapper.createObjectNode()), Map.of(), "stable-key");
        response = "{\"code\":0,\"data\":{\"task_id\":\"legacy-id\",\"task_status\":\"submitted\"}}";
        assertThatThrownBy(() -> client.create(p, null, "synthetic-key", body)).isInstanceOf(KlingClient.Uncertain.class);
        assertThat(paths).hasSize(1);
        response = "{\"code\":0,\"data\":[{\"id\":\"other\",\"status\":\"succeeded\",\"outputs\":[{\"type\":\"video\",\"url\":\"https://h1.inkwai.com/a.mp4\",\"duration\":\"5\"}]}]}";
        assertThatThrownBy(() -> client.query(p, null, "synthetic-key", "original", "video")).isInstanceOf(KlingClient.ProtocolFailure.class);
        assertThat(paths.getLast()).isEqualTo("/tasks?task_ids=original");
    }

    private KlingModels.Profile image(String model, String op) { return KlingModels.resolve(model, op, Task.Kind.IMAGE_GENERATION); }
    private KlingModels.Profile video(String model, String op) { return KlingModels.resolve(model, op, Task.Kind.VIDEO_GENERATION); }
    private ObjectNode values(KlingModels.Profile p, ObjectNode supplied) {
        var parameters = mapper.createObjectNode(); parameters.set("dynamicValues", supplied);
        return KlingModels.effectiveValues(p, parameters, "Prompt", 5, mapper);
    }
}
