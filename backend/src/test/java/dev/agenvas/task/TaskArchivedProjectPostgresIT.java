package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.run.infrastructure.ActiveRunMetrics;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.infrastructure.TaskQueueMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
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

/** Proves archived projects retain accepted Provider results without resuming composition. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=archived-project-test-secret")
class TaskArchivedProjectPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = temporaryRoot();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired
    private dev.agenvas.audit.application.CallLogService callLogs;

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private AssetService assets;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private ArtifactService artifacts;
    @Autowired private CanvasService canvas;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private TaskQueueMetrics queueMetrics;
    @Autowired private ActiveRunMetrics activeRunMetrics;
    @Autowired private MeterRegistry meters;

    /** A late accepted image stays auditable, but never creates an active canvas output. */
    @Test
    void acceptedImageAfterArchiveIsHistoricalAndDoesNotReactivateProject() throws Exception {
        AdminPrincipal owner = identities.setup("archived-project-test-secret",
                "archive-admin", "archive-password-123");
        Project project = projects.create(owner.userId(), "Archive while generating",
                Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Create image", "archive-run").run();
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);
        activeRunMetrics.refresh();
        assertThat(meters.get("agenvas.runs.active").gauge().value()).isEqualTo(1);
        assertThat(meters.get("agenvas.runs.active").gauge().getId().getTags()).isEmpty();
        // 直连生成固定写入一张已存在的空媒体卡片；归档发生在提交之后。
        var card = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Archived image card", null);
        Task task = tasks.createMediaTask(owner.userId(), project.id(), run.id(),
                "image", Task.Kind.IMAGE_GENERATION, mapper.createObjectNode(), null, 1,
                List.of(), card.artifact().id());
        Task lease = tasks.claimImagesDue("archive-submitter", 1).getFirst();
        tasks.beginSubmission(lease, "archive-submitter");
        String requestId = UUID.randomUUID().toString();
        tasks.waitForProvider(lease, "archive-submitter", requestId,
                Instant.now().plusSeconds(60));
        var unsubmittedCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Not submitted card", null);
        Task unsubmitted = tasks.createMediaTask(owner.userId(), project.id(),
                run.id(), "not-submitted", Task.Kind.IMAGE_GENERATION,
                mapper.createObjectNode(), null, 1, List.of(),
                unsubmittedCard.artifact().id());

        Project archived = projects.archive(owner.userId(), project.id(),
                projects.get(owner.userId(), project.id()).version());
        assertThat(archived.status()).isEqualTo(Project.Status.ARCHIVED);
        AtomicInteger newSubmissions = new AtomicInteger();
        assertThat(new TaskWorker(tasks, callLogs).runImagesOnce("archive-new-worker", 1,
                (claimed, key) -> {
                    newSubmissions.incrementAndGet();
                    return new TaskWorker.Failed("UNEXPECTED_SUBMISSION");
                })).isEqualTo(1);
        assertThat(newSubmissions).hasValue(0);
        assertThat(tasks.get(owner.userId(), project.id(), unsubmitted.id()).errorCode())
                .isEqualTo("TASK_PROJECT_ARCHIVED");
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :taskId")
                .param("taskId", unsubmitted.id()).query(Integer.class).single()).isZero();
        queueMetrics.refresh();
        assertThat(meters.get("agenvas.tasks.current").tag("status", "BLOCKED")
                .gauge().value()).isEqualTo(1);
        assertThat(meters.get("agenvas.tasks.current").tag("status", "UNKNOWN")
                .gauge().value()).isZero();
        assertThat(meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("agenvas.tasks.current"))
                .map(meter -> meter.getId().getTag("status")).toList())
                .containsExactlyInAnyOrder("READY", "UNKNOWN", "BLOCKED");
        assertThatThrownBy(() -> assets.archiveImage(owner.userId(), project.id(),
                new ByteArrayInputStream(png()))).hasMessageContaining("归档项目");

        UUID assetId = assets.archiveTaskImage(owner.userId(), project.id(), task.id(),
                () -> new ByteArrayInputStream(png())).id();
        verifyArchivedVideoCanStillBeStored(owner.userId(), project.id());
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                .param("id", task.id()).update();
        Task poll = tasks.claimProviderPolls("archive-poller", 1).getFirst();
        assertThat(poll.providerRequestId()).isEqualTo(requestId);
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", assetId.toString());
        content.put("prompt", "Accepted before archive");
        content.put("providerConfigVersion", 1);
        content.put("workflowVersion", "test-image-v1");
        content.putObject("parameters");
        content.put("sourceTaskId", task.id().toString());
        var result = tasks.succeedWithArtifact(poll, "archive-poller", content);

        assertThat(result.selected()).isFalse();
        Task completed = tasks.get(owner.userId(), project.id(), task.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("artifactVersionId").asText())
                .isEqualTo(result.versionId().toString());
        assertThat(projects.get(owner.userId(), project.id()).status())
                .isEqualTo(Project.Status.ARCHIVED);
        assertThat(canvas.list(owner.userId(), project.id())).noneMatch(item ->
                item.item().subjectId().toString().equals(
                        completed.output().path("artifactId").asText()));
        assertThat(jdbc.sql("select count(*) from project_event where project_id = :projectId "
                        + "and aggregate_id = :taskId and type = 'task.status.changed'")
                .param("projectId", project.id()).param("taskId", task.id())
                .query(Integer.class).single()).isGreaterThan(0);
        assertThat(jdbc.sql("select status from provider_attempt where task_id = :taskId "
                        + "and provider_request_id = :requestId")
                .param("taskId", task.id()).param("requestId", requestId)
                .query(String.class).single()).isEqualTo("ACCEPTED");
        // 晚到结果只追加为不可变的卡片历史版本：可在事后核对，但不会把卡片切到该版本，
        // 也不会因为晚到而把归档项目重新激活。
        UUID cardId = UUID.fromString(completed.output().path("artifactId").asText());
        assertThat(artifacts.listVersions(owner.userId(), project.id(), cardId))
                .extracting(ArtifactVersion::id).contains(result.versionId());
        assertThat(artifacts.get(owner.userId(), project.id(), cardId).currentVersion()).isNull();
    }

    /** Real PNG bytes exercise the same decoder and archive path as a completed Provider. */
    private static byte[] png() {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB),
                    "png", output)) {
                throw new IllegalStateException("PNG encoder unavailable");
            }
            return output.toByteArray();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot encode test PNG", failure);
        }
    }

    /** Video task archival has the same archived-project exception; user uploads do not. */
    private void verifyArchivedVideoCanStillBeStored(UUID ownerId, UUID projectId)
            throws Exception {
        Path image = Files.createTempFile(STORAGE_ROOT, "late-image-", ".png");
        Path video = Files.createTempFile(STORAGE_ROOT, "late-video-", ".mp4");
        try {
            Files.write(image, png());
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-loop", "1", "-framerate", "24", "-i", image.toString(),
                    "-t", "1", "-vf", "scale=640:360,format=yuv420p", "-an",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "25",
                    "-y", video.toString()));
            try (var upload = Files.newInputStream(video)) {
                assertThatThrownBy(() -> assets.archiveVideo(ownerId, projectId, upload))
                        .hasMessageContaining("归档项目");
            }
            UUID videoAsset = assets.archiveTaskVideo(ownerId, projectId, UUID.randomUUID(),
                    () -> {
                        try {
                            return Files.newInputStream(video);
                        } catch (java.io.IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }).id();
            assertThat(assets.get(ownerId, projectId, videoAsset).asset().contentType())
                    .isEqualTo("video/mp4");
        } finally {
            Files.deleteIfExists(image);
            Files.deleteIfExists(video);
        }
    }

    /** Keeps generated media for this test outside the repository and other test contexts. */
    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-archived-project-");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot create test asset root", failure);
        }
    }
}
