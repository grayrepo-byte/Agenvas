package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaExecutionScheduler;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

/** Proves the production scheduler advances durable Mock image work without an HTTP client. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=mock-scheduler-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=true"
})
class MediaExecutionSchedulerPostgresIT {

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
    @Autowired private TaskService tasks;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private ObjectMapper mapper;
    @Autowired private MediaExecutionScheduler scheduler;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private DirectMediaTaskService directMedia;
    @Autowired private JdbcClient jdbc;

    @Test
    void backgroundTickCreatesReadableImageThroughTheBoundMediaPipeline() throws Exception {
        assertThat(scheduler).isNotNull();
        AdminPrincipal owner = identities.setup("mock-scheduler-integration-secret",
                "mock-scheduler-admin", "mock-scheduler-password-123");
        Project project = projects.create(owner.userId(), "Scheduled Mock image",
                Project.AspectRatio.LANDSCAPE_16_9);
        var card = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Demo image card", null);
        UUID itemId = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(),
                project.id(), card.artifact().id());
        var draft = dev.agenvas.support.CanvasMediaFixture.save(drafts, owner.userId(),
                project.id(), itemId, 0, "A harmless demo card", null, null, null);
        Task image = directMedia.run(owner.userId(), project.id(), card.artifact().id(),
                itemId, draft.version(), "demo-image");
        assertThat(tasks.mediaBinding(image)).isPresent();

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Task current = tasks.get(owner.userId(), project.id(), image.id());
        while (current.status() != Task.Status.SUCCEEDED && System.nanoTime() < deadline) {
            Thread.sleep(200);
            current = tasks.get(owner.userId(), project.id(), image.id());
        }
        assertThat(current.status()).isEqualTo(Task.Status.SUCCEEDED);
        UUID versionId = UUID.fromString(current.output().path("artifactVersionId").asText());
        var version = artifacts.requireVersion(owner.userId(), project.id(),
                card.artifact().id(), versionId);
        UUID assetId = UUID.fromString(version.content().path("assetId").asText());
        assertThat(assets.get(owner.userId(), project.id(), assetId).asset().contentType())
                .isEqualTo("image/png");
        assertThat(version.content().path("parameters").path("mock").asBoolean()).isTrue();
        assertThat(canvas.list(owner.userId(), project.id()).stream()
                .filter(entry -> entry.item().id().equals(itemId)).findFirst().orElseThrow()
                .item().selectedVersionId()).isEqualTo(versionId);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", image.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(directMedia.run(owner.userId(), project.id(), card.artifact().id(),
                itemId, draft.version(), "demo-image").id()).isEqualTo(image.id());
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-mock-scheduler-it-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
