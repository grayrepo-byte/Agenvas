package dev.agenvas.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.export.application.MediaExportService;
import dev.agenvas.export.application.MediaExportWorker;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

/** Partial-write ENOSPC proof across local files, PostgreSQL metadata, and task polling. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssetDiskFullPostgresIT.FaultConfiguration.class)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = {"agenvas.identity.bootstrap-secret=disk-full-integration-test-secret",
                "agenvas.export.scheduler-enabled=false"})
class AssetDiskFullPostgresIT {

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
    @Autowired private ArtifactService artifacts;
    @Autowired private MediaExportService exports;
    @Autowired private MediaExportWorker exportWorker;
    @Autowired private FaultingStorage storage;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    /** A failed archive cannot claim success and its original Provider ID remains retryable. */
    @Test
    void partialWriteFailureDoesNotPublishReadyOrCompleteTask() throws Exception {
        AdminPrincipal owner = identities.setup("disk-full-integration-test-secret",
                "disk-admin", "disk-password-123");
        Project project = projects.create(owner.userId(), "Disk failure",
                Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Create image", "disk-full-run").run();
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);
        Task task = tasks.createMediaTaskForNewOutput(owner.userId(), project.id(), run.id(),
                null, "image", Task.Kind.IMAGE_GENERATION, mapper.createObjectNode(), null, 1,
                List.of(), "disk-image");
        Task lease = tasks.claimImagesDue("disk-submitter", 1).getFirst();
        tasks.beginSubmission(lease, "disk-submitter");
        String requestId = UUID.randomUUID().toString();
        tasks.waitForProvider(lease, "disk-submitter", requestId,
                Instant.now().plusSeconds(60));
        due(task.id());

        byte[] image = png();
        storage.failNextIngestWrite();
        TaskWorker worker = new TaskWorker(tasks, callLogs);
        assertThat(worker.runProviderPollsOnce("disk-poller", 1, poll ->
                generated(owner.userId(), project.id(), poll, image))).isEqualTo(1);
        Task afterFailure = tasks.get(owner.userId(), project.id(), task.id());
        assertThat(afterFailure.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(afterFailure.providerRequestId()).isEqualTo(requestId);
        assertThat(afterFailure.output()).isNull();
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from project_event where project_id = :projectId "
                        + "and type = 'asset.ready'")
                .param("projectId", project.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :taskId")
                .param("taskId", task.id()).query(Integer.class).single()).isEqualTo(1);
        try (var files = Files.list(STORAGE_ROOT.resolve(project.id().toString()))) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".ingest-") || name.endsWith(".png"))
                    .toList()).isEmpty();
        }

        due(task.id());
        assertThat(worker.runProviderPollsOnce("disk-poller", 1, poll ->
                generated(owner.userId(), project.id(), poll, image))).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), project.id(), task.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.providerRequestId()).isEqualTo(requestId);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :taskId")
                .param("taskId", task.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(1);

        verifyMediaWriteFailures(owner.userId(), project.id(), image);
    }

    /** Original-image, MP4 and export write stages likewise leave no partial files or READY metadata. */
    private void verifyMediaWriteFailures(UUID ownerId, UUID projectId,
            byte[] image) throws Exception {
        List<String> before = projectFiles(projectId);
        storage.failNextIngestWrite();
        assertThatThrownBy(() -> assets.archiveImage(ownerId, projectId,
                new ByteArrayInputStream(image))).isInstanceOf(IllegalStateException.class);
        assertThat(projectFiles(projectId)).containsExactlyElementsOf(before);
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single()).isEqualTo(1);
        assets.archiveImage(ownerId, projectId, new ByteArrayInputStream(image));
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single()).isEqualTo(2);

        Path sourceImage = Files.createTempFile(STORAGE_ROOT, "video-source-", ".png");
        Path sourceVideo = Files.createTempFile(STORAGE_ROOT, "video-source-", ".mp4");
        try {
            Files.write(sourceImage, image);
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-loop", "1", "-framerate", "24", "-i", sourceImage.toString(),
                    "-t", "1", "-vf", "scale=640:360,format=yuv420p", "-an",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "25",
                    "-y", sourceVideo.toString()));
            before = projectFiles(projectId);
            storage.failNextVideoIngestWrite();
            try (var input = Files.newInputStream(sourceVideo)) {
                assertThatThrownBy(() -> assets.archiveVideo(ownerId, projectId, input))
                        .isInstanceOf(IllegalStateException.class);
            }
            assertThat(projectFiles(projectId)).containsExactlyElementsOf(before);
            assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                    .param("projectId", projectId).query(Integer.class).single()).isEqualTo(2);
            Asset archivedVideo;
            try (var input = Files.newInputStream(sourceVideo)) {
                archivedVideo = assets.archiveVideo(ownerId, projectId, input);
            }
            assertThat(archivedVideo.contentType()).isEqualTo("video/mp4");
            assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                    .param("projectId", projectId).query(Integer.class).single()).isEqualTo(3);
            verifyExportArchiveWriteFailure(ownerId, projectId, archivedVideo.id());
        } finally {
            Files.deleteIfExists(sourceImage);
            Files.deleteIfExists(sourceVideo);
        }
    }

    /** An encoded MP4 that cannot be archived must not become a successful export. */
    private void verifyExportArchiveWriteFailure(UUID ownerId, UUID projectId, UUID videoAssetId)
            throws Exception {
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", videoAssetId.toString());
        content.put("prompt", "Disk-full export fixture");
        content.put("providerConfigVersion", 1);
        content.put("workflowVersion", "test-video-v1");
        content.putObject("parameters").put("mock", true);
        content.put("sourceTaskId", UUID.randomUUID().toString());
        var video = artifacts.create(ownerId, projectId, Artifact.Kind.VIDEO,
                "Export failure source", content);
        var segments = List.of(new MediaExportService.SegmentRequest(video.artifact().id(),
                video.currentVersion().id(), 0, 1));
        Task export = exports.create(ownerId, projectId, "disk-full-export", segments);
        List<String> before = projectFiles(projectId);
        int readyBefore = jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single();
        int readyEventsBefore = jdbc.sql("select count(*) from project_event "
                        + "where project_id = :projectId and type = 'asset.ready'")
                .param("projectId", projectId).query(Integer.class).single();

        storage.failNextVideoIngestWrite();
        assertThat(exportWorker.runOnce("disk-full-export-worker")).isEqualTo(1);
        Task failed = tasks.get(ownerId, projectId, export.id());
        assertThat(failed.status()).isEqualTo(Task.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("EXPORT_FAILED");
        assertThat(failed.output().isNull()).isTrue();
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single())
                .isEqualTo(readyBefore);
        assertThat(jdbc.sql("select count(*) from project_event "
                        + "where project_id = :projectId and type = 'asset.ready'")
                .param("projectId", projectId).query(Integer.class).single())
                .isEqualTo(readyEventsBefore);
        assertThat(projectFiles(projectId)).containsExactlyElementsOf(before);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'SETTLEMENT'")
                .param("taskId", export.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'RELEASE'")
                .param("taskId", export.id()).query(Integer.class).single()).isEqualTo(1);

        Task retry = exports.create(ownerId, projectId, "disk-full-export-retry", segments);
        assertThat(exportWorker.runOnce("recovered-export-worker")).isEqualTo(1);
        Task completed = tasks.get(ownerId, projectId, retry.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("assetId").asText()).isNotBlank();
    }

    /** Compares exact private file inventories before and after an injected failure. */
    private List<String> projectFiles(UUID projectId) throws IOException {
        try (var files = Files.list(STORAGE_ROOT.resolve(projectId.toString()))) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> !name.startsWith(".task-image-")
                            && !name.startsWith(".task-video-"))
                    .sorted().toList();
        }
    }

    /** Polling the saved ID only downloads and archives; it never enters submission again. */
    private TaskWorker.PollResult generated(UUID ownerId, UUID projectId, Task task, byte[] image) {
        UUID assetId = assets.archiveTaskImage(ownerId, projectId, task.id(),
                () -> new ByteArrayInputStream(image)).id();
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", assetId.toString());
        content.put("prompt", "Existing accepted request");
        content.put("providerConfigVersion", 1);
        content.put("workflowVersion", "test-image-v1");
        content.putObject("parameters");
        content.put("sourceTaskId", task.id().toString());
        return new TaskWorker.PollGenerated(content);
    }

    /** Makes only the saved polling request immediately claimable by the next worker. */
    private void due(UUID taskId) {
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                .param("id", taskId).update();
    }

    /** Uses decodable image bytes so archive failure is exclusively the injected write. */
    private static byte[] png() throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB),
                    "png", output)) {
                throw new IllegalStateException("PNG encoder unavailable");
            }
            return output.toByteArray();
        }
    }

    /** Isolates this failure test's media files from the repository and other contexts. */
    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-disk-full-");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create test storage root", failure);
        }
    }

    /** Keeps fault injection local to this Spring test context. */
    @TestConfiguration
    static class FaultConfiguration {
        /** Replaces only this test context's archive writer, not production configuration. */
        @Bean
        @Primary
        FaultingStorage faultingStorage(AssetProperties properties, MediaToolRunner mediaTools,
                ObjectMapper mapper) {
            return new FaultingStorage(properties, mediaTools, mapper);
        }
    }

    /** Fails once after partial bytes, matching an ENOSPC write instead of a bad download. */
    static final class FaultingStorage extends LocalAssetStorage {
        private final AtomicReference<String> failNextPrefix = new AtomicReference<>();

        /** Delegates all normal storage behavior to the real local implementation. */
        FaultingStorage(AssetProperties properties, MediaToolRunner mediaTools,
                ObjectMapper mapper) {
            super(properties, mediaTools, mapper);
        }

        /** Arms one partial-write failure and automatically resets for the retry. */
        void failNextIngestWrite() {
            failNextPrefix.set(".ingest-");
        }

        /** Targets the MP4 temporary stream before probing or publication. */
        void failNextVideoIngestWrite() {
            failNextPrefix.set(".video-ingest-");
        }

        @Override
        protected OutputStream openArchiveOutput(Path target) throws IOException {
            OutputStream actual = super.openArchiveOutput(target);
            String prefix = failNextPrefix.get();
            if (prefix == null || !target.getFileName().toString().startsWith(prefix)
                    || !failNextPrefix.compareAndSet(prefix, null)) {
                return actual;
            }
            return new FilterOutputStream(actual) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, Math.min(length, 16));
                    throw new IOException("No space left on device (injected)");
                }
            };
        }
    }
}
