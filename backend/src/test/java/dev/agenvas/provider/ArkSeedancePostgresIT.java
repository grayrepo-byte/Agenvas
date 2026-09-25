package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.plan.application.ShotKeyframeSelectionService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.infrastructure.ArkSeedanceClient;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
@SpringBootTest(classes = {AgenvasApplication.class, ArkSeedancePostgresIT.FakeClient.class},
        properties = "agenvas.identity.bootstrap-secret=ark-video-integration-secret")
class ArkSeedancePostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String TASK_ID = "cgt-test-seedance-123";
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
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ExecutionPlanService plans;
    @Autowired private ShotKeyframeSelectionService selections;
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
        AdminPrincipal owner = identities.setup("ark-video-integration-secret",
                "ark-admin", "ark-password-123");

        Fixture accepted = fixture(owner.userId(), "Accepted Seedance");
        Task acceptedTask = approve(owner.userId(), accepted);
        assertThat(acceptedTask.input().path("durationSeconds").asInt()).isEqualTo(4);
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
        assertThat(tasks.get(owner.userId(), uncertain.project().id(), uncertainTask.id())
                .status()).isEqualTo(Task.Status.SUBMITTING);
        jdbc.sql("update task set lease_until=now() - interval '1 second' where id=:id")
                .param("id", uncertainTask.id()).update();
        assertThat(tasks.recoverExpiredSubmissions(1)).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), uncertain.project().id(), uncertainTask.id())
                .status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(worker.submitOnce("ark-after-unknown-worker")).isZero();
        assertThat(CREATES).hasValue(2);
    }

    private Fixture fixture(UUID ownerId, String name) {
        Project project = projects.create(ownerId, name, Project.AspectRatio.LANDSCAPE_16_9);
        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "Studio"); scene.put("location", "Shanghai");
        scene.put("timeOfDay", "Day"); scene.put("lighting", "Soft");
        scene.put("style", "Minimal"); scene.putArray("referenceVersionIds");
        UUID sceneVersion = artifacts.create(ownerId, project.id(), Artifact.Kind.SCENE,
                "Scene", scene).currentVersion().id();
        ObjectNode shot = mapper.createObjectNode();
        shot.put("order", 1); shot.put("durationSeconds", 4);
        shot.put("description", "Coffee pour"); shot.put("camera", "Close");
        shot.put("action", "Slow pan"); shot.putArray("characterVersionIds");
        shot.put("sceneVersionId", sceneVersion.toString());
        var target = artifacts.create(ownerId, project.id(), Artifact.Kind.SHOT, "Shot", shot);
        var agent = agents.create(ownerId, project.id(), "Creator", "Create",
                List.of(new AgentInstanceService.BindingInput(target.artifact().id(),
                        target.currentVersion().id())));
        AgentRun queued = runs.create(ownerId, project.id(), agent.id(), "Animate keyframe",
                "ark-" + UUID.randomUUID()).run();
        AgentRun running = runs.transition(ownerId, project.id(), queued.id(),
                queued.version(), AgentRun.Status.RUNNING);
        ObjectNode imageProposal = mapper.createObjectNode();
        imageProposal.put("stage", "IMAGE");
        imageProposal.put("objective", "Create a keyframe before video");
        ObjectNode frameStep = imageProposal.putArray("steps").addObject();
        frameStep.put("stepKey", "frame-1");
        frameStep.put("outputSlotKey", "frame-output");
        frameStep.put("shotArtifactId", target.artifact().id().toString());
        frameStep.put("shotVersionId", target.currentVersion().id().toString());
        frameStep.put("prompt", "Studio keyframe");
        frameStep.putArray("dependsOnStepKeys");
        var imagePlan = plans.propose(new TrustedToolContext(ownerId, project.id(),
                running.id()), imageProposal);
        Task imageTask = plans.approve(ownerId, project.id(), imagePlan.id(),
                imagePlan.planHash(), List.of("frame-1")).tasks().getFirst();
        assertThat(worker.submitOnce("ark-mock-keyframe-worker")).isEqualTo(1);
        Task imageDone = tasks.get(ownerId, project.id(), imageTask.id());
        assertThat(imageDone.status()).isEqualTo(Task.Status.SUCCEEDED);
        var keyframe = artifacts.get(ownerId, project.id(),
                UUID.fromString(imageDone.output().path("artifactId").asText()));
        selections.select(ownerId, project.id(), running.id(), target.artifact().id(),
                target.currentVersion().id(), keyframe.artifact().id(),
                keyframe.currentVersion().id(), null);
        AgentRun waiting = runs.get(ownerId, project.id(), running.id());
        AgentRun readyForVideo = runs.transition(ownerId, project.id(), running.id(),
                waiting.version(), AgentRun.Status.RUNNING);
        return new Fixture(project, target, keyframe, readyForVideo);
    }

    private Task approve(UUID ownerId, Fixture fixture) {
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("stage", "VIDEO"); proposal.put("objective", "Animate selected keyframe");
        ObjectNode step = proposal.putArray("steps").addObject();
        step.put("stepKey", "clip-1"); step.put("outputSlotKey", "clip-output");
        step.put("shotArtifactId", fixture.shot().artifact().id().toString());
        step.put("shotVersionId", fixture.shot().currentVersion().id().toString());
        step.put("imageArtifactId", fixture.keyframe().artifact().id().toString());
        step.put("imageVersionId", fixture.keyframe().currentVersion().id().toString());
        step.put("prompt", "A detailed coffee pour");
        step.putArray("dependsOnStepKeys");
        var plan = plans.propose(new TrustedToolContext(ownerId, fixture.project().id(),
                fixture.run().id()), proposal);
        return plans.approve(ownerId, fixture.project().id(), plan.id(), plan.planHash(),
                List.of("clip-1")).tasks().getFirst();
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
                    assertThat(body.path("duration").asInt()).isEqualTo(4);
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

    private record Fixture(Project project, ArtifactService.ArtifactView shot,
            ArtifactService.ArtifactView keyframe, AgentRun run) {}
}
