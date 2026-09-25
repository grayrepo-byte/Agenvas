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
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import dev.agenvas.testing.MigrationVersions;
import java.math.BigDecimal;
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

/** PostgreSQL proof that a new plan output has no fake seed Artifact and late results stay isolated. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=new-output-integration-secret")
class TaskNewOutputPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private CanvasService canvas;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;
    private UUID mediaOwnerId;
    private UUID mediaProjectId;

    @Test
    void newOutputMaterializesOnlyOnResultAndCanceledLateOutputDoesNotPromoteDependents() {
        AdminPrincipal owner = identities.setup("new-output-integration-secret",
                "output-admin", "output-password-123");
        Project project = projects.create(owner.userId(), "Output project",
                Project.AspectRatio.LANDSCAPE_16_9);
        mediaOwnerId = owner.userId();
        mediaProjectId = project.id();
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of());
        UUID agentCardId = UUID.randomUUID();
        UUID blockerCardId = UUID.randomUUID();
        ArtifactService.ArtifactView blocker = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.TEXT, "Pinned note", text("Do not move"));
        canvas.apply(owner.userId(), project.id(), List.of(
                new CanvasService.PlaceAgent(agentCardId, agent.id(),
                        decimal(100), decimal(100), decimal(340), decimal(320),
                        0, null, false),
                new CanvasService.PlaceArtifact(blockerCardId, blocker.artifact().id(),
                        decimal(504), decimal(100), decimal(300), decimal(220),
                        1, null, true)));
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Make images", "output-run").run();
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);
        UUID planId = null;
        Task first = tasks.createMediaTaskForNewOutput(owner.userId(), project.id(),
                run.id(), planId, "shot-1-image", Task.Kind.IMAGE_GENERATION,
                mapper.createObjectNode(), null, 1, List.of(), "shot-1-keyframe");
        assertThat(countImages(project.id())).isZero();
        new TaskWorker(tasks).runOnce("output-worker", 1,
                lease -> new TaskWorker.GeneratedArtifact(media(lease.id(), "first")));
        Task succeeded = tasks.get(owner.userId(), project.id(), first.id());
        UUID artifactId = UUID.fromString(succeeded.output().path("artifactId").asText());
        ArtifactService.ArtifactView image = artifacts.get(owner.userId(), project.id(), artifactId);
        assertThat(image.artifact().kind()).isEqualTo(Artifact.Kind.IMAGE);
        assertThat(image.artifact().title()).isEqualTo("shot-1-keyframe");
        assertThat(image.currentVersion().createdByKind())
                .isEqualTo(ArtifactVersion.CreatedByKind.TASK);
        assertThat(image.currentVersion().runId()).isEqualTo(run.id());
        assertThat(succeeded.output().path("selected").booleanValue()).isTrue();
        assertThat(countImages(project.id())).isEqualTo(1);
        CanvasItem generatedCard = canvas.list(owner.userId(), project.id()).stream()
                .map(CanvasService.CanvasEntry::item)
                .filter(item -> item.subjectType() == CanvasItem.SubjectType.ARTIFACT
                        && item.subjectId().equals(artifactId))
                .findFirst().orElseThrow();
        assertThat(generatedCard.groupId()).isEqualTo(agent.outputGroupId());
        assertThat(generatedCard.x()).isEqualByComparingTo("828");
        assertThat(generatedCard.y()).isEqualByComparingTo("100");
        CanvasItem lockedBlocker = canvas.list(owner.userId(), project.id()).stream()
                .map(CanvasService.CanvasEntry::item)
                .filter(item -> item.id().equals(blockerCardId)).findFirst().orElseThrow();
        assertThat(lockedBlocker.locked()).isTrue();
        assertThat(lockedBlocker.x()).isEqualByComparingTo("504");
        assertThat(lockedBlocker.version()).isZero();

        Task late = tasks.createMediaTaskForNewOutput(owner.userId(), project.id(),
                run.id(), planId, "shot-2-image", Task.Kind.IMAGE_GENERATION,
                mapper.createObjectNode(), null, 1, List.of(), "shot-2-keyframe");
        Task dependent = tasks.create(owner.userId(), project.id(), run.id(), planId,
                "shot-2-video", Task.Kind.VIDEO_GENERATION, mapper.createObjectNode(),
                null, 1, List.of(late.id()));
        Task lease = tasks.claimDue("late-output-worker", 1).getFirst();
        tasks.beginSubmission(lease, "late-output-worker");
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", late.id()).update();
        tasks.recoverExpiredSubmissions(16);
        runs.cancel(owner.userId(), project.id(), run.id());
        ArtifactService.TaskVersionResult lateResult = tasks.succeedWithArtifact(lease,
                "late-output-worker", media(late.id(), "late"));
        assertThat(lateResult.selected()).isFalse();
        assertThat(tasks.get(owner.userId(), project.id(), late.id()).status())
                .isEqualTo(Task.Status.CANCELED);
        assertThat(tasks.get(owner.userId(), project.id(), dependent.id()).status())
                .isEqualTo(Task.Status.CANCELED);
        assertThat(countImages(project.id())).isEqualTo(2);
        UUID lateArtifactId = jdbc.sql("select artifact_id from artifact_version "
                        + "where id = :versionId")
                .param("versionId", lateResult.versionId()).query(UUID.class).single();
        assertThat(canvas.list(owner.userId(), project.id())).noneMatch(entry ->
                entry.item().subjectType() == CanvasItem.SubjectType.ARTIFACT
                        && entry.item().subjectId().equals(lateArtifactId));
        assertThatThrownBy(() -> tasks.succeedWithArtifact(lease, "late-output-worker",
                media(late.id(), "duplicate"))).isInstanceOf(RuntimeException.class);
        assertThat(countImages(project.id())).isEqualTo(2);
        assertThat(jdbc.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                .query(String.class).single()).isEqualTo(MigrationVersions.latest());
    }

    private long countImages(UUID projectId) {
        return jdbc.sql("select count(*) from artifact where project_id = :projectId "
                        + "and kind = 'IMAGE'")
                .param("projectId", projectId).query(Long.class).single();
    }

    private BigDecimal decimal(int value) {
        return BigDecimal.valueOf(value);
    }

    private ObjectNode text(String value) {
        ObjectNode content = mapper.createObjectNode();
        content.put("format", "PLAIN_TEXT");
        content.put("text", value);
        return content;
    }

    private ObjectNode media(UUID taskId, String prompt) {
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", ImageAssetFixture.archive(assets, mediaOwnerId,
                mediaProjectId).toString());
        content.put("prompt", prompt);
        content.put("providerConfigVersion", 1);
        content.put("workflowVersion", "mock-v1");
        content.putObject("parameters");
        content.put("sourceTaskId", taskId.toString());
        return content;
    }
}
