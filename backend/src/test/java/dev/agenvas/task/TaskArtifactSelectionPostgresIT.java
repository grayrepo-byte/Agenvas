package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
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

/** PostgreSQL proof that Mock media results are immutable and current selection is fenced. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class TaskArtifactSelectionPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private dev.agenvas.task.application.TaskRepository taskRepository;

    @Autowired
    private dev.agenvas.audit.application.CallLogService callLogs;

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;
    private UUID mediaOwnerId;
    private UUID mediaProjectId;

    @Test
    void generatedVersionsSelectOnlyWithPinnedCurrentAndCanceledLateResultStaysHistorical() {
        AdminPrincipal owner = identities.setup("selection-admin", "selection-password-123");
        Project project = projects.create(owner.userId(), "Selection project",
                Project.AspectRatio.LANDSCAPE_16_9);
        mediaOwnerId = owner.userId();
        mediaProjectId = project.id();
        ArtifactService.ArtifactView image = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.IMAGE, "Shot image", media(UUID.randomUUID(), "seed"));
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Create three shots", "selection-run").run();
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);
        Project foreign = projects.create(owner.userId(), "Foreign project",
                Project.AspectRatio.SQUARE_1_1);
        ArtifactService.ArtifactView foreignImage = artifacts.create(owner.userId(), foreign.id(),
                Artifact.Kind.IMAGE, "Foreign image", media(UUID.randomUUID(), "foreign",
                        ImageAssetFixture.archive(assets, owner.userId(), foreign.id())));
        assertThatThrownBy(() -> createMedia(owner.userId(), project.id(), run.id(),
                foreignImage.artifact().id(), "cross-project"))
                .isInstanceOf(ApiProblemException.class);

        Task first = createMedia(owner.userId(), project.id(), run.id(), image.artifact().id(),
                "first");
        new TaskWorker(tasks, callLogs).runOnce("worker-first", 1,
                claimed -> new TaskWorker.GeneratedArtifact(media(claimed.id(), "first result")));
        ArtifactService.ArtifactView selected = artifacts.get(owner.userId(), project.id(),
                image.artifact().id());
        assertThat(selected.artifact().version()).isEqualTo(1);
        assertThat(selected.resourceDefaultVersion().createdByKind()).isEqualTo(ArtifactVersion.CreatedByKind.TASK);
        assertThat(tasks.get(owner.userId(), project.id(), first.id()).status())
                .isEqualTo(Task.Status.SUCCEEDED);

        Task stale = createMedia(owner.userId(), project.id(), run.id(), image.artifact().id(),
                "stale");
        ArtifactService.ArtifactView edited = artifacts.revise(owner.userId(), project.id(),
                image.artifact().id(), selected.artifact().version(), null,
                media(UUID.randomUUID(), "user edit"));
        Task staleLease = tasks.claimDue("worker-stale", 1).getFirst();
        tasks.beginSubmission(staleLease, "worker-stale");
        int versionsBeforeInvalid = artifacts.listVersions(owner.userId(), project.id(),
                image.artifact().id()).size();
        assertThatThrownBy(() -> tasks.succeedWithArtifact(staleLease, "worker-stale",
                media(UUID.randomUUID(), "wrong task")))
                .isInstanceOf(ApiProblemException.class);
        assertThat(artifacts.listVersions(owner.userId(), project.id(), image.artifact().id()))
                .hasSize(versionsBeforeInvalid);
        ArtifactService.TaskVersionResult staleResult = tasks.succeedWithArtifact(staleLease,
                "worker-stale", media(stale.id(), "stale result"));
        assertThat(staleResult.selected()).isFalse();
        assertThat(artifacts.get(owner.userId(), project.id(), image.artifact().id())
                .resourceDefaultVersion().id()).isEqualTo(edited.resourceDefaultVersion().id());
        assertThat(artifacts.listVersions(owner.userId(), project.id(), image.artifact().id()))
                .extracting(ArtifactVersion::id).contains(staleResult.versionId());

        Task late = createMedia(owner.userId(), project.id(), run.id(), image.artifact().id(),
                "late");
        Task lateLease = tasks.claimDue("worker-late", 1).getFirst();
        tasks.beginSubmission(lateLease, "worker-late");
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", late.id()).update();
        tasks.recoverExpiredSubmissions(16);
        assertThat(tasks.get(owner.userId(), project.id(), late.id()).status())
                .isEqualTo(Task.Status.UNKNOWN);
        runs.cancel(owner.userId(), project.id(), run.id());
        ArtifactService.TaskVersionResult lateResult = tasks.succeedWithArtifact(lateLease,
                "worker-late", media(late.id(), "late result"));
        assertThat(lateResult.selected()).isFalse();
        assertThat(artifacts.get(owner.userId(), project.id(), image.artifact().id())
                .resourceDefaultVersion().id()).isEqualTo(edited.resourceDefaultVersion().id());
        assertThat(artifacts.listVersions(owner.userId(), project.id(), image.artifact().id()))
                .extracting(ArtifactVersion::id).contains(lateResult.versionId());
        assertThat(tasks.get(owner.userId(), project.id(), late.id()).status())
                .isEqualTo(Task.Status.CANCELED);
        assertThat(jdbc.sql("select count(*) from task_late_result where task_id = :id")
                .param("id", late.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(tasks.claimDue("worker-after-cancel", 16)).isEmpty();
    }

    private Task createMedia(UUID ownerId, UUID projectId, UUID runId, UUID artifactId,
            String stepKey) {
        return dev.agenvas.task.application.TaskMediaFixture.create(tasks, taskRepository, artifacts, ownerId, projectId, runId, stepKey, Task.Kind.IMAGE_GENERATION, mapper.createObjectNode(), artifactId);
    }

    private ObjectNode media(UUID taskId, String prompt) {
        return media(taskId, prompt,
                ImageAssetFixture.archive(assets, mediaOwnerId, mediaProjectId));
    }

    private ObjectNode media(UUID taskId, String prompt, UUID assetId) {
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", assetId.toString());
        content.put("prompt", prompt);

        content.put("workflowVersion", "mock-v1");
        content.putObject("parameters");
        content.put("sourceTaskId", taskId.toString());
        return content;
    }
}
