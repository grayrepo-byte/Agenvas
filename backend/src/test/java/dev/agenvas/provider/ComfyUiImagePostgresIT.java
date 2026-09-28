package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.provider.application.ProviderProperties;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.ComfyUiImageWorker;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiImageProperties;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
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
import tools.jackson.databind.node.ObjectNode;

/** Fake-server and real-PostgreSQL contract: approved prompts may overlap and poll saved ids. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=comfy-image-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mode=comfyui",
        "agenvas.provider.comfyui.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false",
        "agenvas.provider.comfyui.image.checkpoint=test-model.safetensors"})
class ComfyUiImagePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final HttpServer SERVER = startServer();
    private static final AtomicReference<UUID> FIRST_PROMPT_ID = new AtomicReference<>();
    private static final AtomicReference<UUID> SECOND_PROMPT_ID = new AtomicReference<>();
    private static final AtomicReference<UUID> LAST_PROMPT_ID = new AtomicReference<>();
    private static final AtomicInteger SUBMISSIONS = new AtomicInteger();
    private static final AtomicInteger QUERIES = new AtomicInteger();
    private static final AtomicInteger DOWNLOADS = new AtomicInteger();
    private static final AtomicBoolean DROP_NEXT_PROMPT_RESPONSE = new AtomicBoolean();
    private static final AtomicReference<JsonNode> SUBMITTED_GRAPH = new AtomicReference<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.provider.comfyui.endpoint",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired
    private dev.agenvas.audit.application.CallLogService callLogs;

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private DirectMediaTaskService directMedia;
    @Autowired private AssetService assets;
    @Autowired private TaskService tasks;
    @Autowired private ComfyUiImageWorker worker;
    @Autowired private MediaCapabilityService catalog;
    @Autowired private MediaExecutionWorker mediaWorker;
    @Autowired private ComfyUiImageWorkflow workflow;
    @Autowired private ComfyUiClient client;
    @Autowired private ComfyUiClientRegistry clientRegistry;
    @Autowired private JdbcClient jdbc;
    @Autowired private DSLContext dsl;
    @Autowired private ObjectMapper mapper;

    @Test
    void approvedReferenceImageReachesFixedSamplerThenArchivesOriginalPrompt() throws Exception {
        UUID connection = catalog.createConnection("Comfy fake endpoint",
                "http://127.0.0.1:" + SERVER.getAddress().getPort()).id();
        UUID capability = catalog.publishCapability(connection, "Fixed image", "COMFY_IMAGE_V1",
                mapper.readTree("{\"checkpoint\":\"test-model.safetensors\"}")).id();
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), capability);
        AdminPrincipal owner = identities.setup("comfy-image-integration-secret",
                "comfy-admin", "comfy-password-123");
        Project project = projects.create(owner.userId(), "Comfy fake",
                Project.AspectRatio.LANDSCAPE_16_9);
        UUID referenceAsset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        ObjectNode imageContent = mapper.createObjectNode();
        imageContent.put("assetId", referenceAsset.toString());
        imageContent.put("prompt", "Reference");
        imageContent.put("providerConfigVersion", 1);
        imageContent.put("workflowVersion", "fixture");
        imageContent.putObject("parameters");
        imageContent.put("sourceTaskId", UUID.randomUUID().toString());
        var reference = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Reference", imageContent);
        // 两张直连图片卡片：第一张固定参考图版本，第二张不带参考图。
        var imageCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Reference frame", null);
        UUID imageItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, owner.userId(), project.id(), imageCard.artifact().id());
        MediaDraft referenceDraft = dev.agenvas.support.CanvasMediaFixture.save(drafts,
                owner.userId(), project.id(),
                imageItemId, 0, "A detailed cinematic coffee pour",
                reference.resourceDefaultVersion().id(), null, null);
        Task approved = directMedia.run(owner.userId(), project.id(),
                imageCard.artifact().id(), imageItemId,
                referenceDraft.version(), "comfy-image-run");
        Task pinnedImage = approved;
        assertThat(pinnedImage.input().path("mediaInput").path("images").get(0)
                .path("versionId").asText())
                .isEqualTo(reference.resourceDefaultVersion().id().toString());
        assertThat(pinnedImage.input().path("providerOriginSha256").asText())
                .isEqualTo(client.originSha256());
        assertThat(SUBMISSIONS).hasValue(0);

        var secondCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Second frame", null);
        UUID secondItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, owner.userId(), project.id(), secondCard.artifact().id());
        MediaDraft plainDraft = dev.agenvas.support.CanvasMediaFixture.save(drafts,
                owner.userId(), project.id(),
                secondItemId, 0, "A detailed cinematic coffee pour", null, null, null);
        Task queuedSecond = directMedia.run(owner.userId(), project.id(),
                secondCard.artifact().id(), secondItemId,
                plainDraft.version(), "comfy-second-run");
        assertThat(mediaWorker.submitOnce("comfy-submitter-1")).isEqualTo(1);
        assertThat(SUBMISSIONS).hasValue(1);
        JsonNode graph = SUBMITTED_GRAPH.get();
        assertThat(graph.path("1").path("inputs").path("image").asText())
                .isEqualTo("uploaded-reference.png");
        assertThat(graph.path("5").path("inputs").path("pixels").get(0).asText())
                .isEqualTo("1");
        assertThat(graph.path("6").path("inputs").path("denoise").doubleValue())
                .isEqualTo(0.65);
        Task waiting = tasks.get(owner.userId(), project.id(), approved.id());
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(waiting.providerRequestId()).isEqualTo(FIRST_PROMPT_ID.get().toString());
        assertThat(jdbc.sql("select request_key from provider_attempt where task_id = :id")
                .param("id", approved.id()).query(UUID.class).single())
                .isEqualTo(FIRST_PROMPT_ID.get());
        assertThat(jdbc.sql("select candidate_request_id from provider_attempt where task_id = :id")
                .param("id", approved.id()).query(UUID.class).single())
                .isEqualTo(FIRST_PROMPT_ID.get());
        assertThat(jdbc.sql("select candidate_origin_sha256 from provider_attempt where task_id = :id")
                .param("id", approved.id()).query(String.class).single())
                .isEqualTo(client.originSha256());
        assertThat(clientRegistry.forOriginal(1, client.originSha256())).contains(client);
        // The first ComfyUI request is still active, but it is not a global product slot.
        assertThat(mediaWorker.submitOnce("other-app-instance")).isEqualTo(1);
        assertThat(SUBMISSIONS).hasValue(2);
        assertThat(tasks.get(owner.userId(), project.id(), queuedSecond.id()).status())
                .isEqualTo(Task.Status.WAITING_PROVIDER);
        due(approved.id());
        assertThat(mediaWorker.pollOnce("comfy-poller")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), approved.id()).status())
                .isEqualTo(Task.Status.WAITING_PROVIDER);
        due(approved.id());
        assertThat(mediaWorker.pollOnce("comfy-poller")).isEqualTo(1);
        Task archiveFailed = tasks.get(owner.userId(), project.id(), approved.id());
        assertThat(archiveFailed.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(archiveFailed.providerRequestId()).isEqualTo(FIRST_PROMPT_ID.get().toString());
        assertThat(archiveFailed.nextActionAt()).isAfter(archiveFailed.updatedAt());
        assertThat(jdbc.sql("select failure_count from task_provider_poll_retry where task_id = :id")
                .param("id", approved.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(DOWNLOADS).hasValue(1);
        assertThat(SUBMISSIONS).hasValue(2);
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(1);
        due(approved.id());
        assertThat(mediaWorker.submitOnce("other-app-instance")).isZero();
        assertThat(mediaWorker.pollOnce("recovered-poller")).isEqualTo(1);
        Task complete = tasks.get(owner.userId(), project.id(), approved.id());
        assertThat(complete.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(complete.output().path("selected").booleanValue()).isTrue();
        assertThat(QUERIES).hasValue(3);
        assertThat(DOWNLOADS).hasValue(2);
        assertThat(SUBMISSIONS).hasValue(2);
        assertThat(jdbc.sql("select count(*) from task_provider_poll_retry where task_id = :id")
                .param("id", approved.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", approved.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(SUBMISSIONS).hasValue(2);
        due(queuedSecond.id());
        ComfyUiClient differentOrigin = new ComfyUiClient(
                new ComfyUiProperties("http://127.0.0.1:65534"), mapper);
        assertThatThrownBy(() -> new ComfyUiClientRegistry(dsl, mapper,
                new ProviderProperties("comfyui", 1),
                new ComfyUiProperties("http://127.0.0.1:65534"), differentOrigin, false)
                .registerActive()).isInstanceOf(IllegalStateException.class);
        ComfyUiClientRegistry rotatedRegistry = new ComfyUiClientRegistry(dsl, mapper,
                new ProviderProperties("comfyui", 2),
                new ComfyUiProperties("http://127.0.0.1:65534"), differentOrigin, false);
        rotatedRegistry.registerActive();
        assertThatThrownBy(clientRegistry::registerActive)
                .isInstanceOf(IllegalStateException.class);
        ComfyUiImageWorkflow rotatedWorkflow = new ComfyUiImageWorkflow(
                new ComfyUiImageProperties("rotated-model.safetensors"), mapper);
        assertThat(rotatedWorkflow.version()).isNotEqualTo(workflow.version());
        ComfyUiImageWorker rotated = new ComfyUiImageWorker(tasks, artifacts, assets,
                projects, differentOrigin, rotatedRegistry, rotatedWorkflow,
                new ProviderProperties("comfyui", 2), mapper, callLogs);
        assertThat(mediaWorker.pollOnce("rotated-origin-poller")).isEqualTo(1);
        Task completedOld = tasks.get(owner.userId(), project.id(), queuedSecond.id());
        assertThat(completedOld.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(ComfyUiImageWorkflow.supportsHistoricalVersion(workflow.version())).isTrue();
        assertThat(ComfyUiImageWorkflow.supportsHistoricalVersion("image-v1-not-a-hash")).isFalse();
        assertThat(completedOld.output().path("artifactId").asText()).isNotBlank();
        assertThat(completedOld.providerRequestId()).isEqualTo(SECOND_PROMPT_ID.get().toString());
        assertThat(QUERIES).hasValue(4);
        assertThat(DOWNLOADS).hasValue(3);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", queuedSecond.id()).query(Integer.class).single()).isEqualTo(1);
        // 已结算的直连任务只留下 SETTLEMENT，不再产生 RELEASE（无重复释放）。
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :id "
                        + "and entry_type = 'SETTLEMENT'")
                .param("id", queuedSecond.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :id "
                        + "and entry_type = 'RELEASE'")
                .param("id", queuedSecond.id()).query(Integer.class).single()).isZero();

        // The provider accepted a prompt, but the response was lost before its id was saved.
        // Expiry must preserve the committed candidate and must never submit a second prompt.
        var uncertainCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Uncertain frame", null);
        UUID uncertainItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, owner.userId(), project.id(), uncertainCard.artifact().id());
        MediaDraft uncertainDraft = dev.agenvas.support.CanvasMediaFixture.save(drafts,
                owner.userId(), project.id(),
                uncertainItemId, 0, "A cinematic coffee pour", null, null, null);
        Task uncertainTask = directMedia.run(owner.userId(), project.id(),
                uncertainCard.artifact().id(), uncertainItemId,
                uncertainDraft.version(), "comfy-uncertain-run");
        int acceptedBeforeLoss = SUBMISSIONS.get();
        DROP_NEXT_PROMPT_RESPONSE.set(true);
        assertThatThrownBy(() -> mediaWorker.submitOnce("response-loss-worker"))
                .isInstanceOf(RuntimeException.class);
        assertThat(SUBMISSIONS).hasValue(acceptedBeforeLoss + 1);
        assertThat(tasks.get(owner.userId(), project.id(), uncertainTask.id()).status())
                .isEqualTo(Task.Status.SUBMITTING);
        assertThat(tasks.listProviderAttempts(owner.userId(), project.id(), uncertainTask.id()))
                .singleElement().satisfies(attempt -> {
                    assertThat(attempt.candidateRequestId()).isEqualTo(LAST_PROMPT_ID.get());
                    assertThat(attempt.providerRequestId()).isNull();
                });
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", uncertainTask.id()).update();
        assertThat(tasks.recoverExpiredSubmissions(16)).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), uncertainTask.id()).status())
                .isEqualTo(Task.Status.UNKNOWN);
        assertThat(tasks.listProviderAttempts(owner.userId(), project.id(), uncertainTask.id()))
                .singleElement().satisfies(attempt -> {
                    assertThat(attempt.status().name()).isEqualTo("UNKNOWN");
                    assertThat(attempt.providerRequestId()).isNull();
                });
        assertThat(mediaWorker.submitOnce("post-loss-worker")).isZero();
        assertThat(SUBMISSIONS).hasValue(acceptedBeforeLoss + 1);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", uncertainTask.id()).query(Integer.class).single()).isEqualTo(1);
    }

    private void due(UUID taskId) {
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                .param("id", taskId).update();
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/upload/image", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                assertThat(body.length).isGreaterThan(1_000);
                respond(exchange, 200,
                        "{\"name\":\"uploaded-reference.png\",\"type\":\"input\",\"subfolder\":\"\"}");
            });
            server.createContext("/prompt", exchange -> {
                JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                SUBMITTED_GRAPH.set(body.path("prompt"));
                UUID promptId = UUID.fromString(body.path("prompt_id").asText());
                LAST_PROMPT_ID.set(promptId);
                assertThat(body.path("client_id").asText()).isEqualTo(promptId.toString());
                if (SUBMISSIONS.incrementAndGet() == 1) {
                    FIRST_PROMPT_ID.set(promptId);
                } else {
                    SECOND_PROMPT_ID.set(promptId);
                }
                if (DROP_NEXT_PROMPT_RESPONSE.getAndSet(false)) {
                    exchange.close();
                    return;
                }
                respond(exchange, 200, "{\"prompt_id\":\"" + promptId + "\",\"number\":0}");
            });
            server.createContext("/history/", exchange -> {
                if (exchange.getRequestURI().getPath()
                        .equals("/history/" + SECOND_PROMPT_ID.get())) {
                    QUERIES.incrementAndGet();
                    respond(exchange, 200, "{\"" + SECOND_PROMPT_ID.get() + "\":{"
                            + "\"prompt\":[0,\"" + SECOND_PROMPT_ID.get()
                            + "\",{},{\"client_id\":\"" + SECOND_PROMPT_ID.get() + "\"}],"
                            + "\"status\":{"
                            + "\"completed\":true,\"status_str\":\"success\"},"
                            + "\"outputs\":{\"8\":{\"images\":[{\"filename\":\"render.png\","
                            + "\"type\":\"output\",\"subfolder\":\"\"}]}}}}");
                    return;
                }
                assertThat(exchange.getRequestURI().getPath())
                        .isEqualTo("/history/" + FIRST_PROMPT_ID.get());
                if (QUERIES.incrementAndGet() == 1) {
                    respond(exchange, 200, "{}");
                } else {
                    respond(exchange, 200, "{\"" + FIRST_PROMPT_ID.get() + "\":{\"status\":{"
                            + "\"completed\":true,\"status_str\":\"success\"},"
                            + "\"outputs\":{\"8\":{\"images\":[{\"filename\":\"render.png\","
                            + "\"type\":\"output\",\"subfolder\":\"\"}]}}}}");
                }
            });
            server.createContext("/view", exchange -> {
                if (DOWNLOADS.incrementAndGet() == 1) {
                    respond(exchange, 200, "not an image");
                } else {
                    respondImage(exchange);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot start fake ComfyUI", failure);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void respondImage(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        exchange.getResponseHeaders().add("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, bytes.size());
        try (var output = exchange.getResponseBody()) {
            output.write(bytes.toByteArray());
        }
    }
}
