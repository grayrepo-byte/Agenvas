package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MockImageScheduler;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Proves the production scheduler advances durable Mock image work without an HTTP client. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=mock-scheduler-integration-secret",
        "agenvas.provider.mock.scheduler-enabled=true",
        "agenvas.provider.mock.video-scheduler-enabled=false"
})
class MockImageSchedulerPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = temporaryRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private ObjectMapper mapper;
    @Autowired private MockImageScheduler scheduler;
    @Autowired private JdbcClient jdbc;

    @Test
    void backgroundTickCreatesReadableImageWithoutClaimingVideo() throws Exception {
        assertThat(scheduler).isNotNull();
        AdminPrincipal owner = identities.setup("mock-scheduler-integration-secret",
                "mock-scheduler-admin", "mock-scheduler-password-123");
        Project project = projects.create(owner.userId(), "Scheduled Mock image",
                Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Draw",
                List.of());
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Draw a demo image", "mock-scheduler-run").run();
        runs.transition(owner.userId(), project.id(), queued.id(), queued.version(),
                AgentRun.Status.RUNNING);
        ObjectNode imageInput = mapper.createObjectNode();
        imageInput.put("prompt", "A harmless demo card");
        imageInput.put("providerConfigVersion", 1);
        imageInput.put("workflowVersion", "mock-image-v1");
        var imageCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Demo image card", null);
        Task image = tasks.createMediaTask(owner.userId(), project.id(),
                queued.id(), "demo-image", Task.Kind.IMAGE_GENERATION,
                imageInput, null, 1, List.of(), imageCard.artifact().id());
        var videoCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO,
                "Later video card", null);
        Task video = tasks.createMediaTask(owner.userId(), project.id(),
                queued.id(), "later-video", Task.Kind.VIDEO_GENERATION,
                mapper.createObjectNode(), null, 1, List.of(), videoCard.artifact().id());

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Task current = tasks.get(owner.userId(), project.id(), image.id());
        while (current.status() != Task.Status.SUCCEEDED && System.nanoTime() < deadline) {
            Thread.sleep(200);
            current = tasks.get(owner.userId(), project.id(), image.id());
        }
        assertThat(current.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(tasks.get(owner.userId(), project.id(), video.id()).status())
                .isEqualTo(Task.Status.READY);
        UUID artifactId = UUID.fromString(current.output().path("artifactId").asText());
        UUID assetId = UUID.fromString(artifacts.get(owner.userId(), project.id(), artifactId)
                .currentVersion().content().path("assetId").asText());
        assertThat(assets.get(owner.userId(), project.id(), assetId).asset().contentType())
                .isEqualTo("image/png");

        ObjectNode staleInput = imageInput.deepCopy();
        staleInput.put("providerConfigVersion", 2);
        var staleCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Stale config card", null);
        Task stale = tasks.createMediaTask(owner.userId(), project.id(),
                queued.id(), "stale-config", Task.Kind.IMAGE_GENERATION,
                staleInput, null, 1, List.of(), staleCard.artifact().id());
        long staleDeadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Task staleCurrent = tasks.get(owner.userId(), project.id(), stale.id());
        while (staleCurrent.status() != Task.Status.FAILED
                && System.nanoTime() < staleDeadline) {
            Thread.sleep(200);
            staleCurrent = tasks.get(owner.userId(), project.id(), stale.id());
        }
        assertThat(staleCurrent.status()).isEqualTo(Task.Status.FAILED);
        assertThat(staleCurrent.errorCode()).isEqualTo("PROVIDER_CONFIG_CHANGED");
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :taskId")
                .param("taskId", stale.id()).query(Integer.class).single()).isZero();
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-mock-scheduler-it-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
