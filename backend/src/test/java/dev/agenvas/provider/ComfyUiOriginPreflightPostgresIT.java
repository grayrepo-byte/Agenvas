package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Autowired
    private ProjectService projects;
    @Autowired
    private ArtifactService artifacts;
    @Autowired
    private MediaDraftService drafts;
    @Autowired
    private CanvasService canvas;
    @Autowired
    private DirectMediaTaskService directMedia;
    @Autowired
    private TaskService tasks;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private MediaCapabilityService catalog;
    @Autowired
    private MediaExecutionWorker mediaWorker;

    private static UUID owner;
    private UUID connection;

    @BeforeAll
    static void createOwner(@Autowired IdentityService identities) {
        owner = identities.setup("origin-admin", "origin-password-123").userId();
    }

    private Task createTask() {
        connection = catalog.createConnection("Original Comfy endpoint",
                "http://127.0.0.1:65533").id();
        UUID capability = catalog.publishCapability(connection, "Fixed image",
                "COMFY_IMAGE_V1",
                mapper.readTree("{\"checkpoint\":\"test-model.safetensors\"}")).id();
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), capability);
        Project project = projects.create(owner, "Pinned origin",
                Project.AspectRatio.LANDSCAPE_16_9);
        // 直连图片卡片：空卡片 + 草稿，再走真实的直连受理入口；任务绑定固定受理时的连接版本。
        var card = artifacts.create(owner, project.id(), Artifact.Kind.IMAGE,
                "Fixed origin card", null);
        UUID canvasItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, owner, project.id(), card.artifact().id());
        MediaDraft draft = dev.agenvas.support.CanvasMediaFixture.save(drafts,
                owner, project.id(), canvasItemId, 0,
                "Coffee pour", null, null, null);
        Task approved = directMedia.run(owner, project.id(), card.artifact().id(),
                canvasItemId, draft.version(), "origin-run");
        assertThat(tasks.mediaBinding(approved).orElseThrow().connectionVersion()).isEqualTo(1);

        return approved;
    }

    @Test
    void changedOriginBlocksApprovedBindingBeforeSubmission() {
        Task approved = createTask();
        var current = catalog.getConnection(connection);
        catalog.updateConnection(connection, current.version(), current.name(), true,
                "http://127.0.0.1:65534", null);
        assertThat(mediaWorker.submitOnce("changed-origin-worker")).isEqualTo(1);
        Task blocked = tasks.get(owner, approved.projectId(), approved.id());
        assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(blocked.errorCode()).isEqualTo("MEDIA_CAPABILITY_CHANGED");
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :taskId")
                .param("taskId", approved.id()).query(Integer.class).single()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"blocked", "canceled", "wrong-worker", "old-epoch", "expired-canceled",
            "submitting-canceled",
            "attempt-with-running-status", "remote-id", "result-manifest", "unknown"})
    void preflightReleaseRequiresLiveLeaseAndNoSubmissionEvidence(String scenario) {
        Task task = createTask();
        String worker = "preflight-worker";
        Task lease = tasks.claimBoundMedia(worker, 1).getFirst();
        assertThat(lease.id()).isEqualTo(task.id());
        String code = "CUSTOM_MEDIA_MODEL_UNAVAILABLE";
        switch (scenario) {
            case "blocked", "canceled" -> {
                if (scenario.equals("canceled"))
                    jdbc.sql("update task set cancel_requested=true where id=?").param(task.id()).update();
                tasks.blockPreSubmission(lease, worker, code);
                Task updated = tasks.get(owner, task.projectId(), task.id());
                if (scenario.equals("blocked")) {
                    assertThat(updated.status()).isEqualTo(Task.Status.BLOCKED);
                    assertThat(updated.errorCode()).isEqualTo(code);
                } else {
                    assertThat(updated.status()).isEqualTo(Task.Status.CANCELED);
                }
                assertThat(releases(task)).isOne();
                return;
            }
            case "old-epoch" -> {
                jdbc.sql("update task set lease_until=now()-interval '1 second' where id=?").param(task.id()).update();
                Task reclaimed = tasks.claimBoundMedia(worker, 1).getFirst();
                assertThat(reclaimed.id()).isEqualTo(task.id());
                assertThat(reclaimed.leaseEpoch()).isGreaterThan(lease.leaseEpoch());
            }
            case "expired-canceled" -> jdbc.sql(
                    "update task set cancel_requested=true, lease_until=now()-interval '1 second' where id=?")
                    .param(task.id()).update();
            case "remote-id" -> jdbc.sql("update task set provider_request_id='synthetic-request' where id=?")
                    .param(task.id()).update();
            case "result-manifest" -> jdbc.sql("update task set provider_result_manifest="
                    + "'{\"schemaVersion\":1,\"results\":[{}]}'::jsonb where id=?").param(task.id()).update();
            case "submitting-canceled", "attempt-with-running-status", "unknown" -> {
                tasks.beginSubmission(lease, worker, tasks.mediaBinding(lease).orElseThrow());
                String status = switch (scenario) {
                    case "submitting-canceled" -> "SUBMITTING";
                    case "unknown" -> "UNKNOWN";
                    default -> "RUNNING";
                };
                jdbc.sql("update task set cancel_requested=true, status=? where id=?").param(status)
                        .param(task.id()).update();
            }
        }
        String caller = scenario.equals("wrong-worker") ? "other-worker" : worker;
        Task before = tasks.get(owner, task.projectId(), task.id());
        assertThatThrownBy(() -> tasks.blockPreSubmission(lease, caller, code))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TASK_LEASE_LOST"));
        assertThat(tasks.get(owner, task.projectId(), task.id())).isEqualTo(before);
        assertThat(releases(task)).isZero();
    }

    private int releases(Task task) {
        return jdbc.sql("select count(*) from usage_ledger where task_id=? and entry_type='RELEASE'")
                .param(task.id()).query(Integer.class).single();
    }
}
