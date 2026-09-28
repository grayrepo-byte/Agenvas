package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.ManualUnknownRetryService;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL proof: an explicit retry of a direct media task creates exactly one new attempt on the
 * same card and never reuses the original reservation.
 *
 * <p>自动调度器在本用例中关闭，任务状态与租约由用例自己推进；这样「原任务仍是 UNKNOWN」
 * 这一前提不会被后台 Worker 抢先改写。
 */
@Testcontainers
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=manual-retry-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mode=mock",
        "agenvas.provider.mock.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class ManualUnknownRetryPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private DirectMediaTaskService directMedia;
    @Autowired private TaskService tasks;
    @Autowired private TaskRepository taskRepository;
    @Autowired private ManualUnknownRetryService retries;
    @Autowired private JdbcClient jdbc;
    @Autowired private WebApplicationContext webContext;

    @Test
    void explicitRiskCreatesOneNewReservationWithoutReusingTheOriginal() throws Exception {
        AdminPrincipal owner = identities.setup("manual-retry-integration-secret", "retry-admin",
                "retry-password-123");
        Project project = projects.create(owner.userId(), "Retry fixture",
                Project.AspectRatio.LANDSCAPE_16_9);
        // 用户直连媒体任务必须绑定一张媒体卡片；先建空卡片、保存草稿，再走真实运行入口。
        var card = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Retry card", null);
        UUID canvasItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, owner.userId(), project.id(), card.artifact().id());
        dev.agenvas.support.CanvasMediaFixture.save(drafts,
                owner.userId(), project.id(), canvasItemId, 0,
                "Cinematic coffee pour", null, null, null);
        Task original = directMedia.run(owner.userId(), project.id(), card.artifact().id(),
                canvasItemId, 1, "direct-image-1");
        jdbc.sql("update task set status = 'UNKNOWN', version = version + 1 where id = :id")
                .param("id", original.id()).update();
        Task unknown = tasks.get(owner.userId(), project.id(), original.id());
        assertThatThrownBy(() -> retries.create(owner.userId(), project.id(), unknown.id(),
                unknown.version(), ""))
                .isInstanceOf(ApiProblemException.class);
        Task replacement;
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Task> first = pool.submit(() -> {
                start.await();
                return retries.create(owner.userId(), project.id(), unknown.id(),
                        unknown.version(), "retry-1");
            });
            Future<Task> duplicate = pool.submit(() -> {
                start.await();
                return retries.create(owner.userId(), project.id(), unknown.id(),
                        unknown.version(), "retry-1");
            });
            start.countDown();
            replacement = first.get();
            assertThat(duplicate.get().id()).isEqualTo(replacement.id());
        }
        assertThat(replacement.id()).isNotEqualTo(unknown.id());
        assertThat(replacement.attemptNo()).isEqualTo(2);
        assertThat(tasks.get(owner.userId(), project.id(), unknown.id()).status())
                .isEqualTo(Task.Status.UNKNOWN);
        assertThat(tasks.replacementTaskId(owner.userId(), project.id(), unknown.id()))
                .isEqualTo(replacement.id());
        // 新尝试单独预留用量，原 UNKNOWN 的费用记录不消失。
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id in (:old, :new)")
                .param("old", unknown.id()).param("new", replacement.id())
                .query(Long.class).single()).isGreaterThanOrEqualTo(2L);
        // 两张任务固定在同一张卡片上：替代尝试换的是任务，不是输出目标，也不是原记录。
        assertThat(jdbc.sql("select count(*) from task_artifact_target where artifact_id = :id")
                .param("id", card.artifact().id()).query(Long.class).single()).isEqualTo(2L);
        assertThat(retries.create(owner.userId(), project.id(), unknown.id(), unknown.version(),
                "retry-1").id())
                .isEqualTo(replacement.id());
        assertThatThrownBy(() -> retries.create(owner.userId(), project.id(), unknown.id(),
                unknown.version(), "retry-2"))
                .isInstanceOf(ApiProblemException.class);

        var mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        var authenticated = authentication(new UsernamePasswordAuthenticationToken(owner, null,
                List.of()));
        String path = "/api/v1/projects/" + project.id() + "/tasks/" + unknown.id()
                + "/new-attempt";
        String body = "{\"expectedTaskVersion\":" + unknown.version() + "}";
        mvc.perform(post(path).with(authenticated).header("Idempotency-Key", "retry-1")
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(authenticated).with(csrf())
                        .header("Idempotency-Key", "retry-1")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(replacement.id().toString()));
        mvc.perform(post("/api/v1/projects/" + UUID.randomUUID() + "/tasks/"
                        + unknown.id() + "/new-attempt").with(authenticated).with(csrf())
                        .header("Idempotency-Key", "foreign")
                        .contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        // 切到 ComfyUI 适配器后仍由通用认领路径处理；原 UNKNOWN 记录保留，
        // 但不会阻塞用户显式创建的替代任务。
        jdbc.sql("update media_capability_version set adapter_id = 'COMFY_IMAGE_V1'").update();
        assertThat(taskRepository.claimDueBoundMedia("replacement-submitter", 1,
                Instant.now(), Instant.now().plusSeconds(30))).singleElement()
                .extracting(Task::id).isEqualTo(replacement.id());
    }
}
