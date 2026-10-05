package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
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

/** A changed connection version blocks an approved binding before the submission checkpoint. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mode=configured",
        "agenvas.provider.media.scheduler-enabled=false"})
class ComfyUiOriginPreflightPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64",
                () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private DirectMediaTaskService directMedia;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MediaCapabilityService catalog;
    @Autowired private MediaExecutionWorker mediaWorker;

    @Test
    void changedOriginBlocksApprovedBindingBeforeSubmission() {
        UUID connection = catalog.createConnection("Original Comfy endpoint",
                "http://127.0.0.1:65533").id();
        UUID capability = catalog.publishCapability(connection, "Fixed image",
                "COMFY_IMAGE_V1",
                mapper.readTree("{\"checkpoint\":\"test-model.safetensors\"}")).id();
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), capability);
        var owner = identities.setup("origin-admin",
                "origin-password-123");
        Project project = projects.create(owner.userId(), "Pinned origin",
                Project.AspectRatio.LANDSCAPE_16_9);
        // 直连图片卡片：空卡片 + 草稿，再走真实的直连受理入口；任务绑定固定受理时的连接版本。
        var card = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Fixed origin card", null);
        UUID canvasItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, owner.userId(), project.id(), card.artifact().id());
        MediaDraft draft = dev.agenvas.support.CanvasMediaFixture.save(drafts,
                owner.userId(), project.id(), canvasItemId, 0,
                "Coffee pour", null, null, null);
        Task approved = directMedia.run(owner.userId(), project.id(), card.artifact().id(),
                canvasItemId, draft.version(), "origin-run");
        assertThat(tasks.mediaBinding(approved).orElseThrow().connectionVersion()).isEqualTo(1);

        var current = catalog.getConnection(connection);
        catalog.updateConnection(connection, current.version(), current.name(), true,
                "http://127.0.0.1:65534", null);
        assertThat(mediaWorker.submitOnce("changed-origin-worker")).isEqualTo(1);
        Task blocked = tasks.get(owner.userId(), project.id(), approved.id());
        assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(blocked.errorCode()).isEqualTo("MEDIA_CAPABILITY_CHANGED");
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :taskId")
                .param("taskId", approved.id()).query(Integer.class).single()).isZero();
    }
}
