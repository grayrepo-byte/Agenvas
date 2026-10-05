package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.infrastructure.ArkSeedanceClient;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pinned Seedance submit/poll state machine against PostgreSQL and a local fake Ark API. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, ArkSeedancePostgresIT.FakeClient.class})
class ArkSeedancePostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String TASK_ID = "cgt-test-seedance-123";
    /** 假服务端与 Ark 适配器都要求 4 秒，直连入口写入的 durationSeconds 必须与之一致。 */
    private static final int CLIP_SECONDS = 4;
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger CREATES = new AtomicInteger();
    private static final AtomicInteger QUERIES = new AtomicInteger();
    private static final AtomicBoolean DROP_NEXT_CREATE = new AtomicBoolean();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll static void stopServer() { SERVER.stop(0); }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private DirectMediaTaskService directMedia;
    @Autowired private MediaCapabilityService catalog;
    @Autowired private MediaExecutionWorker worker;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void acceptedTaskPollsOnlyOriginalIdAndLostCreateResponseStaysUnknown() {
        var connection = catalog.createConnection("ark-it-connection", "Ark fake", "ARK",
                null, "fake-ark-key");
        var capability = catalog.publishCapability(connection.id(), "Seedance first frame",
                "ARK_SEEDANCE_2_I2V");
        catalog.setDefault(Task.Kind.VIDEO_GENERATION,
                catalog.defaultVersion(Task.Kind.VIDEO_GENERATION), capability.id());
        AdminPrincipal owner = identities.setup("ark-admin", "ark-password-123");

        Fixture accepted = fixture(owner.userId(), "Accepted Seedance");
        Task acceptedTask = approve(owner.userId(), accepted);
        assertThat(acceptedTask.input().path("durationSeconds").asInt()).isEqualTo(CLIP_SECONDS);
        assertThat(worker.submitOnce("ark-submit-worker")).isEqualTo(1);
        Task waiting = tasks.get(owner.userId(), accepted.project().id(), acceptedTask.id());
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(waiting.providerRequestId()).isEqualTo(TASK_ID);
        assertThat(CREATES).hasValue(1);
        jdbc.sql("update task set next_action_at=now() - interval '1 second' where id=:id")
                .param("id", acceptedTask.id()).update();
        assertThat(worker.pollOnce("ark-poll-worker")).isEqualTo(1);
        assertThat(QUERIES).hasValue(1);
        assertThat(CREATES).hasValue(1);

        Fixture uncertain = fixture(owner.userId(), "Uncertain Seedance");
        Task uncertainTask = approve(owner.userId(), uncertain);
        DROP_NEXT_CREATE.set(true);
        assertThat(worker.submitOnce("ark-unknown-worker")).isEqualTo(1);
        assertThat(CREATES).hasValue(2);
        // 方舟上传提交的超时配置未变，因此沿用适配器自己的原因码；判定时机与图片一致，
        // 都是当场写入而不再等租约到期。
        Task uncertainResult = tasks.get(owner.userId(), uncertain.project().id(),
                uncertainTask.id());
        assertThat(uncertainResult.status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(uncertainResult.errorCode()).isEqualTo("ARK_CREATE_UNCERTAIN");
        assertThat(tasks.recoverExpiredSubmissions(1)).isZero();
        assertThat(worker.submitOnce("ark-after-unknown-worker")).isZero();
        assertThat(CREATES).hasValue(2);
    }

    /** 直连入口只需要一张真实归档图片当关键帧，不再经过规划、审批和关键帧选择。 */
    private Fixture fixture(UUID ownerId, String name) {
        Project project = projects.create(ownerId, name, Project.AspectRatio.LANDSCAPE_16_9);
        UUID assetId = ImageAssetFixture.archive(assets, ownerId, project.id());
        ObjectNode image = mapper.createObjectNode();
        image.put("assetId", assetId.toString());
        image.put("sourceTaskId", UUID.randomUUID().toString());
        image.put("prompt", "Studio keyframe");
        image.put("workflowVersion", "fixture");
        image.putObject("parameters");
        var keyframe = artifacts.create(ownerId, project.id(), Artifact.Kind.IMAGE,
                "Keyframe", image);
        var card = artifacts.create(ownerId, project.id(), Artifact.Kind.VIDEO, "Clip", null);
        UUID canvasItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, ownerId, project.id(), card.artifact().id());
        long draftVersion = dev.agenvas.support.CanvasMediaFixture.save(drafts,
                ownerId, project.id(), canvasItemId, 0,
                "A detailed coffee pour", keyframe.resourceDefaultVersion().id(), CLIP_SECONDS, null)
                .version();
        return new Fixture(project, card, keyframe, canvasItemId, draftVersion);
    }

    private Task approve(UUID ownerId, Fixture fixture) {
        return directMedia.run(ownerId, fixture.project().id(), fixture.card().artifact().id(),
                fixture.canvasItemId(), fixture.draftVersion(), "ark-" + UUID.randomUUID());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v3/contents/generations/tasks", exchange -> {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer fake-ark-key");
                if (exchange.getRequestMethod().equals("POST")) {
                    var body = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                    assertThat(body.path("model").asText())
                            .isEqualTo("doubao-seedance-2-0-260128");
                    assertThat(body.path("duration").asInt()).isEqualTo(CLIP_SECONDS);
                    assertThat(body.path("generate_audio").booleanValue()).isFalse();
                    assertThat(body.path("content").get(1).path("role").asText())
                            .isEqualTo("first_frame");
                    CREATES.incrementAndGet();
                    if (DROP_NEXT_CREATE.getAndSet(false)) { exchange.close(); return; }
                    respond(exchange, "{\"id\":\"" + TASK_ID + "\"}");
                } else {
                    assertThat(exchange.getRequestURI().getPath())
                            .isEqualTo("/api/v3/contents/generations/tasks/" + TASK_ID);
                    QUERIES.incrementAndGet();
                    respond(exchange, "{\"id\":\"" + TASK_ID + "\",\"model\":"
                            + "\"doubao-seedance-2-0-260128\",\"status\":\"running\"}");
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @TestConfiguration
    static class FakeClient {
        @Bean @Primary
        ArkSeedanceClient fakeArkSeedanceClient(ObjectMapper mapper) {
            return new ArkSeedanceClient(mapper,
                    URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort()));
        }
    }

    /** 直连视频任务的固定输入：视频卡片、作为关键帧的图片产物和已保存的草稿版本。 */
    private record Fixture(Project project, ArtifactService.ArtifactView card,
            ArtifactService.ArtifactView keyframe, UUID canvasItemId, long draftVersion) {}
}
