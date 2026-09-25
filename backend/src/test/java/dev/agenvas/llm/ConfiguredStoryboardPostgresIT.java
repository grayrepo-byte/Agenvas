package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.export.application.MediaExportService;
import dev.agenvas.export.application.MediaExportWorker;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.plan.application.ShotKeyframeSelectionService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MockImageWorker;
import dev.agenvas.provider.application.MockVideoWorker;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Configured Spring AI drives durable creative tools and Mock image media through real PostgreSQL. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=configured-storyboard-secret",
        "agenvas.llm.mode=configured",
        "agenvas.llm.tool-calling-verified=true",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mock.scheduler-enabled=false",
        "spring.ai.model.chat=openai",
        "spring.ai.openai.api-key=local-test-key",
        "spring.ai.openai.chat.model=test-storyboard-model"})
class ConfiguredStoryboardPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = temporaryRoot();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer SERVER = fakeModel();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
        registry.add("spring.ai.openai.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private AgentTurnWorker turns;
    @Autowired private ExecutionPlanService plans;
    @Autowired private ShotKeyframeSelectionService keyframes;
    @Autowired private MockImageWorker images;
    @Autowired private MockVideoWorker videos;
    @Autowired private TaskService tasks;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private MediaExportService exports;
    @Autowired private MediaExportWorker exportWorker;
    @Autowired private JdbcClient jdbc;

    @Test
    void configuredModelResponsesDriveHumanApprovedImagesAndVideos() throws Exception {
        AdminPrincipal owner = identities.setup("configured-storyboard-secret",
                "configured-storyboard-admin", "configured-storyboard-password-123");
        Project project = projects.create(owner.userId(), "Configured storyboard",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator",
                "Create three coherent shots", List.of());
        AgentRunService.RunPreflight preflight = runs.preflight(owner.userId(), project.id(),
                agent.id());
        assertThat(preflight.modelAvailable()).isTrue();
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "制作三个镜头的咖啡广告", "configured-three-shots", agent.version()).run();

        for (int step = 0; step < 3; step++) {
            assertThat(turns.runOnce("configured-turn-worker")).isEqualTo(1);
        }
        assertThat(CALLS).hasValue(3);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        assertThat(jdbc.sql("select count(*) from artifact where project_id = :projectId "
                        + "and kind = 'SHOT'").param("projectId", project.id())
                .query(Long.class).single()).isEqualTo(3);
        ExecutionPlan plan = plans.listByRun(owner.userId(), project.id(), run.id()).getFirst();
        assertThat(plan.stage()).isEqualTo(ExecutionPlan.Stage.IMAGE);
        assertThat(plan.steps()).hasSize(3);
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id()))
                .noneMatch(task -> task.kind() == Task.Kind.IMAGE_GENERATION);
        assertThat(jdbc.sql("select count(*) from llm_turn where run_id = :runId "
                        + "and status = 'RESPONDED'").param("runId", run.id())
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId "
                        + "and status = 'COMPLETED'").param("runId", run.id())
                .query(Long.class).single()).isEqualTo(4);

        plans.approve(owner.userId(), project.id(), plan.id(), plan.planHash());
        for (int image = 0; image < 3; image++) {
            assertThat(images.runOnce("configured-image-worker")).isEqualTo(1);
        }
        List<Task> imageTasks = tasks.listByRun(owner.userId(), project.id(), run.id())
                .stream().filter(task -> task.kind() == Task.Kind.IMAGE_GENERATION).toList();
        assertThat(imageTasks).hasSize(3).allSatisfy(task -> {
            assertThat(task.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(artifacts.get(owner.userId(), project.id(),
                    UUID.fromString(task.output().path("artifactId").asText()))
                    .artifact().kind()).isEqualTo(Artifact.Kind.IMAGE);
        });
        for (Task imageTask : imageTasks) {
            keyframes.select(owner.userId(), project.id(), run.id(),
                    UUID.fromString(imageTask.input().path("shotArtifactId").asText()),
                    UUID.fromString(imageTask.input().path("shotVersionId").asText()),
                    UUID.fromString(imageTask.output().path("artifactId").asText()),
                    UUID.fromString(imageTask.output().path("artifactVersionId").asText()),
                    null);
        }
        assertThat(turns.runOnce("configured-turn-worker")).isEqualTo(1);
        assertThat(CALLS).hasValue(4);
        ExecutionPlan videoPlan = plans.listByRun(owner.userId(), project.id(), run.id())
                .stream().filter(candidate -> candidate.stage() == ExecutionPlan.Stage.VIDEO)
                .findFirst().orElseThrow();
        assertThat(videoPlan.steps()).hasSize(3).allSatisfy(step ->
                assertThat(step.imageVersionId()).isNotNull());
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id()))
                .noneMatch(task -> task.kind() == Task.Kind.VIDEO_GENERATION);
        plans.approve(owner.userId(), project.id(), videoPlan.id(), videoPlan.planHash());
        for (int video = 0; video < 3; video++) {
            assertThat(videos.runOnce("configured-video-worker")).isEqualTo(1);
        }
        assertThat(turns.runOnce("configured-turn-worker")).isEqualTo(1);
        assertThat(CALLS).hasValue(5);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.SUCCEEDED);
        List<Task> videoTasks = tasks.listByRun(owner.userId(), project.id(), run.id()).stream()
                .filter(task -> task.kind() == Task.Kind.VIDEO_GENERATION)
                .toList();
        assertThat(videoTasks).hasSize(3).allSatisfy(task -> assertThat(task.status())
                .isEqualTo(Task.Status.SUCCEEDED));
        List<MediaExportService.SegmentRequest> orderedSegments = videoTasks.stream()
                .sorted(java.util.Comparator.comparingInt(task -> artifacts.get(owner.userId(),
                        project.id(), UUID.fromString(task.input().path("shotArtifactId").asText()))
                        .currentVersion().content().path("order").asInt()))
                .map(task -> new MediaExportService.SegmentRequest(
                        UUID.fromString(task.output().path("artifactId").asText()),
                        UUID.fromString(task.output().path("artifactVersionId").asText()),
                        0, 1_000))
                .toList();
        Task export = exports.create(owner.userId(), project.id(),
                "configured-storyboard-export", orderedSegments);
        assertThat(exportWorker.runOnce("configured-export-worker")).isEqualTo(1);
        Task completedExport = tasks.get(owner.userId(), project.id(), export.id());
        assertThat(completedExport.status()).isEqualTo(Task.Status.SUCCEEDED);
        UUID exportedAssetId = UUID.fromString(completedExport.output().path("assetId").asText());
        assertThat(assets.get(owner.userId(), project.id(), exportedAssetId).asset().contentType())
                .isEqualTo("video/mp4");
        String audio = mediaTools.ffprobe(List.of("-v", "error", "-select_streams", "a",
                "-show_entries", "stream=codec_type", "-of", "json",
                assets.get(owner.userId(), project.id(), exportedAssetId).path().toString()));
        assertThat(MAPPER.readTree(audio).path("streams").size()).isZero();
    }

    /** Responds using only committed tool results visible in the next HTTP request. */
    private static HttpServer fakeModel() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                int turn = CALLS.incrementAndGet();
                ObjectNode message = MAPPER.createObjectNode();
                message.put("role", "assistant");
                message.putNull("content");
                ArrayNode calls = message.putArray("tool_calls");
                if (turn == 1) {
                    ObjectNode brief = MAPPER.createObjectNode();
                    brief.put("title", "咖啡广告说明");
                    brief.put("format", "PLAIN_TEXT");
                    brief.put("text", "三个镜头展示咖啡产品。");
                    tool(calls, "call_brief_0", "create_text", brief);
                    ObjectNode scene = MAPPER.createObjectNode();
                    scene.put("name", "咖啡空间");
                    scene.put("location", "明亮的咖啡馆");
                    scene.put("timeOfDay", "白天");
                    scene.put("lighting", "自然窗光");
                    scene.put("style", "现代极简");
                    scene.putArray("referenceVersionIds");
                    tool(calls, "call_scene_0", "create_scene", scene);
                } else if (turn == 2) {
                    JsonNode sceneResult = reply(request, "call_scene_0");
                    String sceneId = sceneResult.path("createdIds").path(0).asText();
                    String sceneVersion = sceneResult.path("affectedVersions")
                            .path(sceneId).asText();
                    ObjectNode arguments = MAPPER.createObjectNode();
                    ArrayNode shots = arguments.putArray("shots");
                    for (int index = 1; index <= 3; index++) {
                        ObjectNode shot = shots.addObject();
                        shot.put("title", "镜头 " + index);
                        shot.put("order", index);
                        shot.put("durationSeconds", 5);
                        shot.put("description", "展示咖啡产品镜头 " + index);
                        shot.put("camera", "中景");
                        shot.put("action", "展示咖啡产品");
                        shot.putArray("characterVersionIds");
                        shot.put("sceneVersionId", sceneVersion);
                    }
                    tool(calls, "call_shots_1", "create_shots", arguments);
                } else if (turn == 3) {
                    JsonNode result = reply(request, "call_shots_1");
                    ObjectNode arguments = MAPPER.createObjectNode();
                    arguments.put("stage", "IMAGE");
                    arguments.put("objective", "为三个镜头生成关键帧");
                    ArrayNode steps = arguments.putArray("steps");
                    for (int index = 0; index < 3; index++) {
                        String shotId = result.path("createdIds").path(index).asText();
                        ObjectNode step = steps.addObject();
                        step.put("stepKey", "image-" + (index + 1));
                        step.put("outputSlotKey", "shot-" + (index + 1) + "-keyframe");
                        step.put("shotArtifactId", shotId);
                        step.put("shotVersionId", result.path("affectedVersions")
                                .path(shotId).asText());
                        step.put("prompt", "咖啡广告镜头 " + (index + 1));
                        step.putArray("dependsOnStepKeys");
                    }
                    tool(calls, "call_plan_2", "propose_generation_plan", arguments);
                } else if (turn == 4) {
                    String decision = latestUserText(request);
                    if (!decision.contains("APPROVED by the authenticated user")) {
                        throw new IllegalStateException("Image plan lacks authenticated approval");
                    }
                    ObjectNode arguments = MAPPER.createObjectNode();
                    arguments.put("stage", "VIDEO");
                    arguments.put("objective", "将人工选定的三个关键帧制作成视频");
                    ArrayNode steps = arguments.putArray("steps");
                    int index = 0;
                    for (String line : decision.split("\\n")) {
                        if (!line.startsWith("shotArtifactId=")) continue;
                        Map<String, String> fields = new java.util.HashMap<>();
                        for (String assignment : line.split(" ")) {
                            String[] pair = assignment.split("=", 2);
                            if (pair.length == 2) fields.put(pair[0], pair[1]);
                        }
                        ObjectNode step = steps.addObject();
                        step.put("stepKey", "video-" + (++index));
                        step.put("outputSlotKey", "shot-" + index + "-video");
                        for (String field : List.of("shotArtifactId", "shotVersionId",
                                "imageArtifactId", "imageVersionId")) {
                            step.put(field, UUID.fromString(fields.get(field)).toString());
                        }
                        step.put("prompt", "咖啡广告视频镜头 " + index);
                        step.putArray("dependsOnStepKeys");
                    }
                    if (index != 3) throw new IllegalStateException("Expected three human choices");
                    tool(calls, "call_video_plan_3", "propose_generation_plan", arguments);
                } else if (turn == 5) {
                    if (!latestUserText(request).contains("APPROVED by the authenticated user")) {
                        throw new IllegalStateException("Video plan lacks authenticated approval");
                    }
                    message.put("content", "三个视频已归档。");
                    message.remove("tool_calls");
                } else {
                    throw new IllegalStateException("Unexpected fake-model turn " + turn);
                }
                ObjectNode response = MAPPER.createObjectNode();
                response.put("id", "chatcmpl-storyboard-" + turn);
                response.put("object", "chat.completion");
                response.put("created", 1_700_000_000);
                response.put("model", "test-storyboard-model");
                response.putArray("choices").addObject().put("index", 0)
                        .set("message", message).put("finish_reason",
                                turn == 5 ? "stop" : "tool_calls");
                ObjectNode usage = response.putObject("usage");
                usage.put("prompt_tokens", 20);
                usage.put("completion_tokens", 10);
                usage.put("total_tokens", 30);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot start fake storyboard endpoint", failure);
        }
    }

    private static void tool(ArrayNode calls, String id, String name, ObjectNode arguments) {
        ObjectNode call = calls.addObject();
        call.put("id", id);
        call.put("type", "function");
        call.putObject("function").put("name", name)
                .put("arguments", arguments.toString());
    }

    private static JsonNode reply(JsonNode request, String id) {
        for (JsonNode message : request.path("messages")) {
            if ("tool".equals(message.path("role").asText())
                    && id.equals(message.path("tool_call_id").asText())) {
                return MAPPER.readTree(message.path("content").asText());
            }
        }
        throw new IllegalStateException("Missing committed reply for " + id);
    }

    private static String latestUserText(JsonNode request) {
        JsonNode messages = request.path("messages");
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if ("user".equals(message.path("role").asText())) {
                return message.path("content").asText();
            }
        }
        throw new IllegalStateException("Model request lacks user decision context");
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-configured-storyboard-");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create test asset directory", failure);
        }
    }
}
