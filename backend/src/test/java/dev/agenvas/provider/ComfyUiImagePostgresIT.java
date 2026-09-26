package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.plan.application.PlanProviderProperties;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.ComfyUiImageWorker;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.ComfyUiUnknownTaskReconciler;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiImageProperties;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
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

/** Fake-server and real-PostgreSQL contract: approval precedes one prompt and saved-id polling. */
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
    @Autowired private AssetService assets;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ExecutionPlanService plans;
    @Autowired private TaskService tasks;
    @Autowired private ComfyUiImageWorker worker;
    @Autowired private MediaCapabilityService catalog;
    @Autowired private MediaExecutionWorker mediaWorker;
    @Autowired private ComfyUiUnknownTaskReconciler reconciler;
    @Autowired private ComfyUiImageWorkflow workflow;
    @Autowired private ComfyUiClient client;
    @Autowired private ComfyUiClientRegistry clientRegistry;
    @Autowired private JdbcClient jdbc;
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
        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "Studio");
        scene.put("location", "Shanghai");
        scene.put("timeOfDay", "Day");
        scene.put("lighting", "Soft");
        scene.put("style", "Minimal");
        scene.putArray("referenceVersionIds");
        var sceneVersion = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SCENE,
                "Scene", scene).currentVersion().id();
        ObjectNode shot = mapper.createObjectNode();
        shot.put("order", 1);
        shot.put("durationSeconds", 3);
        shot.put("description", "Coffee pour");
        shot.put("camera", "Close");
        shot.put("action", "Pour coffee");
        shot.putArray("characterVersionIds");
        shot.put("sceneVersionId", sceneVersion.toString());
        shot.put("selectedImageVersionId", reference.currentVersion().id().toString());
        var target = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SHOT,
                "Shot", shot);
        ObjectNode unreferencedShot = shot.deepCopy();
        unreferencedShot.put("order", 2);
        unreferencedShot.remove("selectedImageVersionId");
        var targetWithoutReference = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.SHOT, "Second shot", unreferencedShot);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of(new AgentInstanceService.BindingInput(target.artifact().id(),
                        target.currentVersion().id()),
                        new AgentInstanceService.BindingInput(targetWithoutReference.artifact().id(),
                                targetWithoutReference.currentVersion().id())));
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Make an image", "comfy-run").run();
        runs.transition(owner.userId(), project.id(), queued.id(), queued.version(),
                AgentRun.Status.RUNNING);
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("stage", "IMAGE");
        proposal.put("objective", "One approved keyframe");
        ObjectNode step = proposal.putArray("steps").addObject();
        step.put("stepKey", "frame-1");
        step.put("outputSlotKey", "frame-1-output");
        step.put("shotArtifactId", target.artifact().id().toString());
        step.put("shotVersionId", target.currentVersion().id().toString());
        step.put("prompt", "A detailed cinematic coffee pour");
        step.putArray("dependsOnStepKeys");
        ObjectNode secondStep = ((ObjectNode) proposal.path("steps").get(0)).deepCopy();
        secondStep.put("stepKey", "frame-2");
        secondStep.put("outputSlotKey", "frame-2-output");
        secondStep.put("shotArtifactId", targetWithoutReference.artifact().id().toString());
        secondStep.put("shotVersionId", targetWithoutReference.currentVersion().id().toString());
        ((tools.jackson.databind.node.ArrayNode) proposal.path("steps")).add(secondStep);
        var plan = plans.propose(new TrustedToolContext(owner.userId(), project.id(),
                queued.id()), proposal);
        assertThat(plan.workflowVersion()).isEqualTo("media-capabilities-v1");
        assertThat(plan.steps()).allSatisfy(planned -> assertThat(planned.input()
                .path("providerOriginSha256").asText()).isEqualTo(client.originSha256()));
        assertThat(SUBMISSIONS).hasValue(0);
        jdbc.sql("update plan_step set input_json = input_json || "
                        + "jsonb_build_object('providerOriginSha256', :origin) "
                        + "where plan_id = :planId")
                .param("origin", "0".repeat(64)).param("planId", plan.id()).update();
        assertThatThrownBy(() -> plans.approve(owner.userId(), project.id(), plan.id(),
                plan.planHash(), plans.get(owner.userId(), project.id(), plan.id()).steps().stream().map(dev.agenvas.plan.application.ExecutionPlan.Step::stepKey).toList())).isInstanceOf(ApiProblemException.class);
        assertThat(jdbc.sql("select count(*) from task where plan_id = :planId")
                .param("planId", plan.id()).query(Integer.class).single()).isZero();
        jdbc.sql("update plan_step set input_json = input_json || "
                        + "jsonb_build_object('providerOriginSha256', :origin) "
                        + "where plan_id = :planId")
                .param("origin", client.originSha256()).param("planId", plan.id()).update();
        List<Task> approvedTasks = plans.approve(owner.userId(), project.id(), plan.id(),
                plan.planHash(), plans.get(owner.userId(), project.id(), plan.id()).steps().stream().map(dev.agenvas.plan.application.ExecutionPlan.Step::stepKey).toList()).tasks();
        Task approved = approvedTasks.getFirst();
        Task queuedSecond = approvedTasks.get(1);
        assertThat(approved.input().path("referenceImageVersionId").asText())
                .isEqualTo(reference.currentVersion().id().toString());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return mediaWorker.submitOnce("comfy-submitter-1");
            });
            Future<Integer> competing = pool.submit(() -> {
                start.await();
                return mediaWorker.submitOnce("comfy-submitter-2");
            });
            start.countDown();
            assertThat(first.get() + competing.get()).isEqualTo(1);
        }
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
        assertThat(mediaWorker.submitOnce("other-app-instance")).isZero();
        assertThat(tasks.get(owner.userId(), project.id(), queuedSecond.id()).status())
                .isEqualTo(Task.Status.READY);
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
        assertThat(SUBMISSIONS).hasValue(1);
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
        assertThat(SUBMISSIONS).hasValue(1);
        assertThat(jdbc.sql("select count(*) from task_provider_poll_retry where task_id = :id")
                .param("id", approved.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", approved.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(mediaWorker.submitOnce("other-app-instance")).isEqualTo(1);
        assertThat(SUBMISSIONS).hasValue(2);
        assertThat(SUBMITTED_GRAPH.get().path("6").path("inputs")
                .path("denoise").doubleValue()).isEqualTo(1.0);
        due(queuedSecond.id());
        ComfyUiClient differentOrigin = new ComfyUiClient(
                new ComfyUiProperties("http://127.0.0.1:65534"), mapper);
        assertThatThrownBy(() -> new ComfyUiClientRegistry(jdbc, mapper,
                new PlanProviderProperties("comfyui", 1),
                new ComfyUiProperties("http://127.0.0.1:65534"), differentOrigin, false)
                .registerActive()).isInstanceOf(IllegalStateException.class);
        ComfyUiClientRegistry rotatedRegistry = new ComfyUiClientRegistry(jdbc, mapper,
                new PlanProviderProperties("comfyui", 2),
                new ComfyUiProperties("http://127.0.0.1:65534"), differentOrigin, false);
        rotatedRegistry.registerActive();
        assertThatThrownBy(clientRegistry::registerActive)
                .isInstanceOf(IllegalStateException.class);
        ComfyUiImageWorkflow rotatedWorkflow = new ComfyUiImageWorkflow(
                new ComfyUiImageProperties("rotated-model.safetensors"), mapper);
        assertThat(rotatedWorkflow.version()).isNotEqualTo(workflow.version());
        ComfyUiImageWorker rotated = new ComfyUiImageWorker(tasks, artifacts, assets,
                projects, differentOrigin, rotatedRegistry, rotatedWorkflow,
                new PlanProviderProperties("comfyui", 2), mapper, callLogs);
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
        runs.cancel(owner.userId(), project.id(), queued.id());
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :id "
                        + "and entry_type = 'RELEASE'")
                .param("id", queuedSecond.id()).query(Integer.class).single()).isZero();

        // The provider accepted a prompt, but the response was lost before its id was saved.
        // Expiry must preserve the committed candidate and must never submit a second prompt.
        var uncertainAgent = agents.create(owner.userId(), project.id(), "Uncertain creator",
                "Create", List.of(new AgentInstanceService.BindingInput(
                        targetWithoutReference.artifact().id(),
                        targetWithoutReference.currentVersion().id())));
        AgentRun uncertainRun = runs.create(owner.userId(), project.id(), uncertainAgent.id(),
                "Make uncertain image", "comfy-uncertain-run").run();
        runs.transition(owner.userId(), project.id(), uncertainRun.id(), uncertainRun.version(),
                AgentRun.Status.RUNNING);
        ObjectNode uncertainProposal = mapper.createObjectNode();
        uncertainProposal.put("stage", "IMAGE");
        uncertainProposal.put("objective", "Response-loss recovery");
        ObjectNode uncertainStep = uncertainProposal.putArray("steps").addObject();
        uncertainStep.put("stepKey", "uncertain-frame");
        uncertainStep.put("outputSlotKey", "uncertain-frame-output");
        uncertainStep.put("shotArtifactId", targetWithoutReference.artifact().id().toString());
        uncertainStep.put("shotVersionId", targetWithoutReference.currentVersion().id().toString());
        uncertainStep.put("prompt", "A cinematic coffee pour");
        uncertainStep.putArray("dependsOnStepKeys");
        var uncertainPlan = plans.propose(new TrustedToolContext(owner.userId(), project.id(),
                uncertainRun.id()), uncertainProposal);
        Task uncertainTask = plans.approve(owner.userId(), project.id(), uncertainPlan.id(),
                uncertainPlan.planHash(), plans.get(owner.userId(), project.id(), uncertainPlan.id()).steps().stream().map(dev.agenvas.plan.application.ExecutionPlan.Step::stepKey).toList()).tasks().getFirst();
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
        assertThat(reconciler.reconcile(owner.userId(), project.id(), uncertainTask.id()).outcome())
                .isEqualTo(dev.agenvas.task.application.UnknownTaskReconciler.Outcome.RESUMED);
        assertThat(tasks.get(owner.userId(), project.id(), uncertainTask.id())
                .providerRequestId()).isEqualTo(LAST_PROMPT_ID.get().toString());
        assertThat(SUBMISSIONS).hasValue(acceptedBeforeLoss + 1);
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
