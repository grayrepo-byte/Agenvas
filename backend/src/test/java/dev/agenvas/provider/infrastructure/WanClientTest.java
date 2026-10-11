package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.provider.domain.WanModels;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic HTTP covers native schemas and acknowledgement boundaries without cloud traffic. */
class WanClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WanModels models = new WanModels();
    private final NativeMediaProtocol.Profile image = models.profiles().get(0), pro = models.profiles().get(1), video = models.profiles().get(2);
    private final List<String> paths = new ArrayList<>(), methods = new ArrayList<>();
    private final List<JsonNode> bodies = new ArrayList<>();
    private static final String ORIGIN = "https://llm-synthetic.cn-beijing.maas.aliyuncs.com";
    private HttpServer server;
    private WanClient client;
    private int status = 200;
    private String response = "{}";
    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            methods.add(exchange.getRequestMethod()); paths.add(exchange.getRequestURI().toString());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-key");
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (request.length > 0) {
                assertThat(exchange.getRequestHeaders().getFirst("X-DashScope-Async")).isEqualTo("enable");
                bodies.add(mapper.readTree(request));
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }); server.start();
        client = new WanClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void imageCreatesOnceWithMultiResultsAndPinnedOriginalTaskQueries() {
        response = "{\"output\":{\"task_id\":\"task-original\",\"task_status\":\"PENDING\"}}";
        var body = client.build(image, values(image).put("n", 2), Map.of());
        assertThat(client.create(image, ORIGIN, "synthetic-key", body)).isEqualTo("task-original");
        assertThat(body.path("input").path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("parameters").path("n").asInt()).isEqualTo(2);
        response = "{\"output\":{\"task_id\":\"task-original\",\"task_status\":\"SUCCEEDED\",\"finished\":true,\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"private upstream text\"},{\"type\":\"image\",\"image\":\"https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/one.png\"},{\"type\":\"image\",\"image\":\"https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/two.png\"}]}}]},\"usage\":{\"image_count\":2,\"input_tokens\":888,\"private\":\"not persisted\"}}";
        var result = client.query(image, ORIGIN, "synthetic-key", "task-original");
        assertThat(result.outputs()).extracting(WanClient.Output::identity).containsExactly("choice0:content1", "choice0:content2");
        assertThat(result.usage().propertyNames()).containsExactly("image_count");
        assertThat(methods).containsExactly("POST", "GET");
        assertThat(paths).containsExactly(WanClient.IMAGE_PATH, "/api/v1/tasks/task-original");
    }
    @Test void editedImagesBoxesAndPaletteFollowDenseMediaOrderWithoutForwardingLocalFields() {
        var values = values(pro).put("image_2", UUID.randomUUID().toString()).put("image_9", UUID.randomUUID().toString())
                .put("image_9_bbox_2_x1", 10).put("image_9_bbox_2_y1", 20).put("image_9_bbox_2_x2", 100).put("image_9_bbox_2_y2", 200)
                .put("color_1_hex", "#000000").put("color_1_ratio", 25).put("color_2_hex", "#112233").put("color_2_ratio", 25)
                .put("color_10_hex", "#FFFFFF").put("color_10_ratio", 50);
        var body = client.build(pro, values, Map.of("image_2", "data:image/jpeg;base64,AQID", "image_9", "data:image/png;base64,BAUG"));
        var content = body.at("/input/messages/0/content");
        assertThat(content.get(0).path("image").asText()).isEqualTo("data:image/jpeg;base64,AQID");
        assertThat(content.get(1).path("image").asText()).isEqualTo("data:image/png;base64,BAUG");
        assertThat(content.get(2).path("text").asText()).isEqualTo("A synthetic landscape");
        assertThat(body.at("/parameters/bbox_list/0").isEmpty()).isTrue();
        assertThat(body.at("/parameters/bbox_list/1/0").toString()).isEqualTo("[10,20,100,200]");
        assertThat(body.at("/parameters/color_palette/2/ratio").asText()).isEqualTo("50.00%");
        assertThat(body.path("parameters").has("thinking_mode")).isFalse();
        assertThat(body.toString()).doesNotContain("image_9_bbox_", "color_10", "image_9", "dynamicValues");
    }
    @Test void videoReferencesAreTypedAndAutomaticDurationSerializesMinusOne() {
        var values = values(video).put("image_10", UUID.randomUUID().toString()).put("video_5", UUID.randomUUID().toString())
                .put("audio_3", UUID.randomUUID().toString()).put("smart_duration", true).put("seed", -1);
        var body = client.build(video, values, Map.of("image_10", "data:image/png;base64,AQID", "video_5", "https://relay.example/video.mp4?signature=synthetic",
                "audio_3", "https://relay.example/audio.wav?signature=synthetic"));
        assertThat(body.path("parameters").path("duration").asInt()).isEqualTo(-1);
        assertThat(body.path("parameters").has("smart_duration")).isFalse();
        assertThat(body.path("parameters").path("audio").asBoolean()).isTrue();
        var mediaTypes = new ArrayList<String>();
        for (var media : body.path("input").path("media")) mediaTypes.add(media.path("type").asText());
        assertThat(mediaTypes).containsExactly("reference_image", "reference_video", "reference_audio");
        response = "{\"output\":{\"task_id\":\"video-original\",\"task_status\":\"RUNNING\"}}";
        assertThat(client.create(video, ORIGIN, "synthetic-key", body)).isEqualTo("video-original");
        assertThat(paths).containsExactly(WanClient.VIDEO_PATH);
        response = "{\"output\":{\"task_id\":\"video-original\",\"task_status\":\"SUCCEEDED\",\"video_url\":\"https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/result.mp4\"},\"usage\":{\"video_count\":1,\"duration\":12.5,\"input_video_duration\":5.5,\"output_video_duration\":7,\"fps\":30,\"SR\":1080}}";
        var state = client.query(video, ORIGIN, "synthetic-key", "video-original");
        assertThat(state.outputs()).singleElement().extracting(WanClient.Output::identity).isEqualTo("video");
        assertThat(state.usage().path("input_video_duration").asDouble()).isEqualTo(5.5);
    }
    @Test void malformedReceiptAndServerErrorsStayUnknownAndCannotRetryPost() {
        var body = client.build(image, values(image), Map.of());
        for (String invalid : List.of("{}", "not-json", "{\"output\":{\"task_id\":\"../other\",\"task_status\":\"PENDING\"}}", "{\"code\":\"InternalError\"}")) {
            response = invalid;
            assertThatThrownBy(() -> client.create(image, ORIGIN, "synthetic-key", body)).isInstanceOf(WanClient.Uncertain.class);
        }
        status = 503; response = "{}";
        assertThatThrownBy(() -> client.create(image, ORIGIN, "synthetic-key", body)).isInstanceOf(WanClient.Uncertain.class);
        assertThat(methods).containsExactly("POST", "POST", "POST", "POST", "POST");
        status = 200;
        for (String code : List.of("InvalidApiKey", "InvalidParameter", "DataInspectionFailed")) {
            response = "{\"code\":\"" + code + "\"}";
            assertThatThrownBy(() -> client.create(image, ORIGIN, "synthetic-key", body)).isInstanceOf(WanClient.Rejected.class);
        }
    }
    @Test void queryCannotSubstituteIdentityAndDistinguishesCredentialOrTaskExpiry() {
        response = "{\"output\":{\"task_id\":\"other\",\"task_status\":\"RUNNING\"}}";
        assertThatThrownBy(() -> client.query(video, ORIGIN, "synthetic-key", "original")).isInstanceOf(NativeMediaFailure.class).hasMessage("PROVIDER_PROTOCOL_INVALID");
        response = "{\"output\":{\"task_id\":\"original\",\"task_status\":\"UNKNOWN\"}}";
        assertThatThrownBy(() -> client.query(video, ORIGIN, "synthetic-key", "original")).hasMessage(WanClient.TASK_EXPIRED);
        status = 403;
        assertThatThrownBy(() -> client.query(video, ORIGIN, "synthetic-key", "original")).hasMessage("MEDIA_CREDENTIAL_UNAVAILABLE");
        assertThatThrownBy(() -> client.query(video, ORIGIN, "synthetic-key", "../other")).hasMessage("PROVIDER_PROTOCOL_INVALID");
        assertThat(methods).containsExactly("GET", "GET", "GET");
    }
    @Test void officialWorkspaceRootsAreRegionAndProfileBound() {
        assertThat(WanClient.validatedOrigin(ORIGIN + "/")).isEqualTo(ORIGIN);
        for (String region : List.of("cn-beijing", "ap-southeast-1", "ap-northeast-1", "eu-central-1", "us-east-1", "cn-hongkong"))
            WanClient.validateOrigin(video, "https://llm-synthetic." + region + ".maas.aliyuncs.com");
        assertThatThrownBy(() -> WanClient.validateOrigin(image, "https://llm-synthetic.us-east-1.maas.aliyuncs.com")).isInstanceOf(ApiProblemException.class);
        for (String invalid : List.of("https://dashscope.aliyuncs.com", "https://dashscope-intl.aliyuncs.com", "https://trial.cn-beijing.maas.aliyuncs.com",
                ORIGIN + "/api/v1", ORIGIN + "?apiKey=synthetic", "http://llm-synthetic.cn-beijing.maas.aliyuncs.com", "https://user@llm-synthetic.cn-beijing.maas.aliyuncs.com",
                "https://llm-synthetic.cn-beijing.maas.aliyuncs.com.untrusted.example", "https://127.0.0.1"))
            assertThatThrownBy(() -> WanClient.validatedOrigin(invalid)).isInstanceOf(ApiProblemException.class);
    }
    private ObjectNode values(NativeMediaProtocol.Profile p) { return NativeMediaProfiles.effectiveValues(new NativeMediaProfiles.Selection(models, p), mapper.createObjectNode(), "A synthetic landscape", 5, mapper); }
}
