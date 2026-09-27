package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.provider.application.ProviderProperties;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.ComfyUiImageWorker;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
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
import tools.jackson.databind.node.ObjectNode;

/** A same-version endpoint change fails before the non-idempotent ComfyUI submission checkpoint. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=comfy-origin-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mode=comfyui",
        "agenvas.provider.comfyui.endpoint=http://127.0.0.1:65533",
        "agenvas.provider.comfyui.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false",
        "agenvas.provider.comfyui.image.checkpoint=test-model.safetensors"})
class ComfyUiOriginPreflightPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private MediaDraftService drafts;
    @Autowired private DirectMediaTaskService directMedia;
    @Autowired private TaskService tasks;
    @Autowired private AssetService assets;
    @Autowired private ComfyUiImageWorkflow workflow;
    @Autowired private ComfyUiClient originalClient;
    @Autowired private ComfyUiClientRegistry clientRegistry;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MediaCapabilityService catalog;
    @Autowired private MediaExecutionWorker mediaWorker;

    @Test
    void changedOriginCannotSubmitApprovedTaskEvenWhenNumericVersionIsUnchanged() {
        UUID connection = catalog.createConnection("Original Comfy endpoint",
                "http://127.0.0.1:65533").id();
        UUID capability = catalog.publishCapability(connection, "Fixed image",
                "COMFY_IMAGE_V1",
                mapper.readTree("{\"checkpoint\":\"test-model.safetensors\"}")).id();
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), capability);
        var owner = identities.setup("comfy-origin-integration-secret", "origin-admin",
                "origin-password-123");
        Project project = projects.create(owner.userId(), "Pinned origin",
                Project.AspectRatio.LANDSCAPE_16_9);
        // 直连图片卡片：空卡片 + 草稿，再走真实的直连受理入口；输入里固定受理时的 origin 摘要。
        var card = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Fixed origin card", null);
        MediaDraft draft = drafts.save(owner.userId(), project.id(), card.artifact().id(), 0,
                "Coffee pour", null, null, null);
        Task approved = directMedia.run(owner.userId(), project.id(), card.artifact().id(),
                draft.version(), "origin-run");
        assertThat(approved.input().path("providerOriginSha256").asText())
                .isEqualTo(originalClient.originSha256());

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
