package dev.agenvas.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.export.application.MediaExportService;
import dev.agenvas.export.application.MediaExportWorker;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL and FFmpeg proof for project-level version-pinned silent exports. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = {"agenvas.identity.bootstrap-secret=export-integration-secret",
                "agenvas.export.scheduler-enabled=false"})
class MediaExportPostgresIT {

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
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private MediaExportService exports;
    @Autowired private MediaExportWorker worker;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private LocalAssetStorage storage;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private UsageService usage;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper mapper;

    @Test
    void normalizesMixedVideoAndKeepsPinnedInputAfterUserRevision() throws Exception {
        AdminPrincipal owner = identities.setup("export-integration-secret",
                "export-admin", "export-password-123");
        Project project = projects.create(owner.userId(), "Export project",
                Project.AspectRatio.LANDSCAPE_16_9);
        Asset red = generatedVideo(owner.userId(), project.id(), "red", "640x360", 15);
        Asset blue = generatedVideo(owner.userId(), project.id(), "blue", "800x600", 30);
        assertThat(red.durationMs()).isEqualTo(1_000);
        assertThat(blue.durationMs()).isEqualTo(1_000);
        ArtifactService.ArtifactView first = videoArtifact(owner.userId(), project.id(),
                "First", red.id());
        ArtifactService.ArtifactView second = videoArtifact(owner.userId(), project.id(),
                "Second", blue.id());
        List<MediaExportService.SegmentRequest> ordered = List.of(
                new MediaExportService.SegmentRequest(first.artifact().id(),
                        first.currentVersion().id(), 0, 1000),
                new MediaExportService.SegmentRequest(second.artifact().id(),
                        second.currentVersion().id(), 0, 1000));
        assertThatThrownBy(() -> exports.preview(owner.userId(), project.id(), List.of(
                new MediaExportService.SegmentRequest(first.artifact().id(),
                        first.currentVersion().id(), 0, 1_001))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("EXPORT_INPUT_INVALID"));
        jdbc.sql("update asset set duration_ms = null where id = :assetId")
                .param("assetId", red.id()).update();
        assertThatThrownBy(() -> exports.preview(owner.userId(), project.id(), ordered))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("EXPORT_INPUT_INVALID"));
        jdbc.sql("update asset set duration_ms = 1000 where id = :assetId")
                .param("assetId", red.id()).update();
        MediaExportService.ExportPreview preview = exports.preview(owner.userId(),
                project.id(), ordered);
        assertThat(preview.projectVersion()).isEqualTo(project.version());
        assertThat(preview.inputSnapshot().path("segments")).hasSize(2);
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single()).isZero();
        Task pending = exports.create(owner.userId(), project.id(), "mixed-export", ordered);
        assertThat(pending.input()).isEqualTo(preview.inputSnapshot());
        assertThat(pending.status()).isEqualTo(Task.Status.READY);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'RESERVATION'")
                .param("taskId", pending.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(pending.runId()).isNull();
        assertThat(pending.input().path("segments").get(0).path("assetId").asText())
                .isEqualTo(red.id().toString());
        assertThat(exports.create(owner.userId(), project.id(), "mixed-export", ordered).id())
                .isEqualTo(pending.id());
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId")
                .param("taskId", pending.id()).query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> exports.create(owner.userId(), project.id(),
                "mixed-export", List.of(ordered.getLast(), ordered.getFirst())))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));

        ObjectNode revision = mediaContent(blue.id());
        artifacts.revise(owner.userId(), project.id(), first.artifact().id(),
                first.artifact().version(), "First changed", revision);
        assertThat(worker.runOnce("export-test-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), project.id(), pending.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'SETTLEMENT' and cost_status = 'UNKNOWN' "
                        + "and actual_cost is null and quantity_json ->> 'exportCount' = '1'")
                .param("taskId", pending.id()).query(Integer.class).single()).isEqualTo(1);
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
                usage.settleExportTask(owner.userId(), completed));
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'SETTLEMENT'")
                .param("taskId", pending.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select count(*) from project_event e
                        join usage_ledger u on u.id = e.aggregate_id
                        where e.project_id = :projectId and u.task_id = :taskId
                          and e.type = 'usage.changed'
                        """)
                .param("projectId", project.id()).param("taskId", pending.id())
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(completed.input().path("segments").get(0).path("videoVersionId").asText())
                .isEqualTo(first.currentVersion().id().toString());
        UUID exportedAssetId = UUID.fromString(completed.output().path("assetId").asText());
        AssetService.AssetFile exported = assets.get(owner.userId(), project.id(), exportedAssetId);
        assertThat(exported.asset().contentType()).isEqualTo("video/mp4");
        JsonNode probe = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error",
                "-select_streams", "v:0", "-show_entries",
                "stream=codec_name,width,height,r_frame_rate:format=duration",
                "-of", "json", exported.path().toString())));
        assertThat(probe.path("streams").path(0).path("width").asInt()).isEqualTo(1280);
        assertThat(probe.path("streams").path(0).path("height").asInt()).isEqualTo(720);
        assertThat(probe.path("streams").path(0).path("r_frame_rate").asText())
                .isEqualTo("24/1");
        assertThat(probe.path("format").path("duration").asDouble())
                .isBetween(1.8, 2.2);
        JsonNode audioProbe = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error",
                "-select_streams", "a", "-show_entries", "stream=codec_type",
                "-of", "json", exported.path().toString())));
        assertThat(audioProbe.path("streams").size()).isZero();
        // The first frame must still use the originally selected red asset, not the blue revision.
        int firstPixel = framePixel(exported.path(), "0.2");
        int secondPixel = framePixel(exported.path(), "1.2");
        assertThat((firstPixel >> 16) & 0xff).isGreaterThan(100);
        assertThat((firstPixel >> 8) & 0xff).isLessThan(80);
        assertThat((secondPixel >> 16) & 0xff).isLessThan(80);
        assertThat((secondPixel >> 8) & 0xff).isLessThan(80);
        assertThat(secondPixel & 0xff).isGreaterThan(100);
        assertThat(exports.list(owner.userId(), project.id())).extracting(Task::id)
                .contains(pending.id());

        Task canceled = exports.create(owner.userId(), project.id(), "cancel-export", ordered);
        Task canceledResult = exports.cancel(owner.userId(), project.id(), canceled.id());
        assertThat(canceledResult.status()).isEqualTo(Task.Status.CANCELED);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'SETTLEMENT'")
                .param("taskId", canceled.id()).query(Integer.class).single()).isZero();
        assertThat(releaseCount(canceled.id())).isEqualTo(1);
        exports.cancel(owner.userId(), project.id(), canceled.id());
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
                usage.releaseExportTask(owner.userId(), canceledResult));
        assertThat(releaseCount(canceled.id())).isEqualTo(1);
        assertThat(worker.runOnce("export-test-worker")).isZero();
        verifyRunningCancellation(owner, project, ordered);
        verifyExpiredRunningCancellation(owner, project, ordered);
        verifyToolFailure(owner, project, ordered);
        verifyArchivedExportRecovery(owner, project, ordered, exported.path());
        verifyAspectRatioLetterboxing(owner);
        verifyKilledProcessExportRecovery(owner);
    }

    /** Portrait and square outputs retain the full source frame with black padding. */
    private void verifyAspectRatioLetterboxing(AdminPrincipal owner) throws IOException {
        Project portrait = projects.create(owner.userId(), "Portrait export",
                Project.AspectRatio.PORTRAIT_9_16);
        Asset wideRed = generatedVideo(owner.userId(), portrait.id(), "red", "640x360", 15);
        verifyPaddedExport(owner, portrait, wideRed, "portrait-padding-export",
                720, 1280, true);

        Project square = projects.create(owner.userId(), "Square export",
                Project.AspectRatio.SQUARE_1_1);
        Asset tallBlue = generatedVideo(owner.userId(), square.id(), "blue", "360x640", 30);
        verifyPaddedExport(owner, square, tallBlue, "square-padding-export",
                720, 720, false);
    }

    /** A real terminated server leaves an in-flight encode for a fresh server to reclaim. */
    private void verifyKilledProcessExportRecovery(AdminPrincipal owner) throws Exception {
        Project project = projects.create(owner.userId(), "Killed export recovery",
                Project.AspectRatio.LANDSCAPE_16_9);
        Asset source = generatedVideo(owner.userId(), project.id(), "green", "640x360", 24, 3);
        var video = videoArtifact(owner.userId(), project.id(), "Long recovery clip", source.id());
        var segment = new MediaExportService.SegmentRequest(video.artifact().id(),
                video.currentVersion().id(), 0, 2500);
        Task queued = exports.create(owner.userId(), project.id(), "real-process-kill",
                java.util.Collections.nCopies(6, segment));
        Path childLog = Files.createTempFile(STORAGE_ROOT, "export-process-", ".log");
        Process first = null;
        Process second = null;
        try {
            first = startExportServer(childLog);
            Path partialOutput = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            while (System.nanoTime() < deadline && first.isAlive()) {
                if (tasks.get(owner.userId(), project.id(), queued.id()).status()
                        == Task.Status.RUNNING) {
                    partialOutput = partialExportOutput(project.id());
                    if (partialOutput != null && Files.size(partialOutput) > 0) break;
                }
                Thread.sleep(50);
            }
            assertThat(partialOutput).as("FFmpeg must have begun writing before process kill")
                    .isNotNull();
            assertThat(Files.size(partialOutput)).isPositive();
            assertThat(tasks.get(owner.userId(), project.id(), queued.id()).status())
                    .isEqualTo(Task.Status.RUNNING);
            first.destroyForcibly();
            assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(tasks.get(owner.userId(), project.id(), queued.id()).status())
                    .isEqualTo(Task.Status.RUNNING);
            UUID outputId = AssetService.taskVideoAssetId(queued.id());
            assertThat(jdbc.sql("select count(*) from asset where id = :assetId")
                    .param("assetId", outputId).query(Integer.class).single()).isZero();

            second = startExportServer(childLog);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(95);
            Task completed = tasks.get(owner.userId(), project.id(), queued.id());
            while (System.nanoTime() < deadline && second.isAlive()
                    && completed.status() == Task.Status.RUNNING) {
                Thread.sleep(200);
                completed = tasks.get(owner.userId(), project.id(), queued.id());
            }
            assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(completed.leaseEpoch()).isGreaterThan(1);
            assertThat(completed.output().path("assetId").asText())
                    .isEqualTo(outputId.toString());
            assertThat(jdbc.sql("select count(*) from asset where id = :assetId")
                    .param("assetId", outputId).query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                            + "and entry_type = 'SETTLEMENT'")
                    .param("taskId", queued.id()).query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("select count(*) from project_event "
                            + "where project_id = :projectId and aggregate_id = :assetId "
                            + "and type = 'asset.ready'")
                    .param("projectId", project.id()).param("assetId", outputId)
                    .query(Integer.class).single()).isEqualTo(1);
            assertThat(storage.cleanupStaleExportWorkDirectories(Instant.now().plusSeconds(1), 100))
                    .isGreaterThanOrEqualTo(1);
            assertThat(Files.exists(partialOutput.getParent())).isFalse();
        } finally {
            stopServer(first);
            stopServer(second);
            Files.deleteIfExists(childLog);
        }
    }

    /** Starts the packaged application against this test's PostgreSQL and private asset root. */
    private Process startExportServer(Path log) throws IOException {
        Path jar = Path.of("target/agenvas-server-0.1.0-SNAPSHOT.jar").toAbsolutePath();
        assertThat(Files.isRegularFile(jar)).isTrue();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xmx512m",
                "-jar", jar.toString(),
                "--agenvas.export.scheduler-enabled=true",
                "--agenvas.llm.scheduler-enabled=false",
                "--agenvas.provider.mock.scheduler-enabled=false",
                "--agenvas.provider.mock.video-scheduler-enabled=false",
                "--server.port=0")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("AGENVAS_DB_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("AGENVAS_DB_USER", POSTGRES.getUsername());
        builder.environment().put("AGENVAS_DB_PASSWORD", POSTGRES.getPassword());
        builder.environment().put("AGENVAS_STORAGE_ROOT", STORAGE_ROOT.toString());
        builder.environment().put("AGENVAS_BOOTSTRAP_SECRET", "export-integration-secret");
        return builder.start();
    }

    /** Finds only this project's real FFmpeg output, not a READY archived asset. */
    private Path partialExportOutput(UUID projectId) throws IOException {
        try (var directories = Files.newDirectoryStream(
                STORAGE_ROOT.resolve(projectId.toString()), ".export-*")) {
            for (Path directory : directories) {
                Path candidate = directory.resolve("silent-export.mp4");
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }

    /** Fenced test child cleanup never touches the developer's running Compose service. */
    private void stopServer(Process process) throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void verifyPaddedExport(AdminPrincipal owner, Project project, Asset source,
            String key, int width, int height, boolean topPadding) throws IOException {
        var video = videoArtifact(owner.userId(), project.id(), key, source.id());
        Task task = exports.create(owner.userId(), project.id(), key,
                List.of(new MediaExportService.SegmentRequest(video.artifact().id(),
                        video.currentVersion().id(), 0, 1000)));
        assertThat(worker.runOnce(key + "-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), project.id(), task.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        Path output = assets.get(owner.userId(), project.id(),
                UUID.fromString(completed.output().path("assetId").asText())).path();
        JsonNode probe = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error",
                "-select_streams", "v:0", "-show_entries",
                "stream=codec_name,width,height,r_frame_rate", "-of", "json",
                output.toString())));
        assertThat(probe.path("streams").path(0).path("width").asInt()).isEqualTo(width);
        assertThat(probe.path("streams").path(0).path("height").asInt()).isEqualTo(height);
        assertThat(probe.path("streams").path(0).path("r_frame_rate").asText())
                .isEqualTo("24/1");
        BufferedImage frame = decodedFrame(output, "0.2");
        int center = frame.getRGB(width / 2, height / 2);
        int padding = topPadding ? frame.getRGB(width / 2, 10)
                : frame.getRGB(10, height / 2);
        assertThat(topPadding ? (center >> 16) & 0xff : center & 0xff)
                .isGreaterThan(100);
        assertThat(topPadding ? center & 0xff : (center >> 16) & 0xff)
                .isLessThan(80);
        assertThat((padding >> 16) & 0xff).isLessThan(40);
        assertThat((padding >> 8) & 0xff).isLessThan(40);
        assertThat(padding & 0xff).isLessThan(40);
    }

    /** A crash after READY archive but before Task success resumes without another encode. */
    private void verifyArchivedExportRecovery(AdminPrincipal owner, Project project,
            List<MediaExportService.SegmentRequest> ordered, Path normalizedVideo)
            throws IOException {
        Task pending = exports.create(owner.userId(), project.id(),
                "recovery-export", ordered);
        Task abandoned = tasks.claimExportsDue("abandoned-export-worker", 1).getFirst();
        assertThat(abandoned.id()).isEqualTo(pending.id());
        byte[] encoded = Files.readAllBytes(normalizedVideo);
        Asset archived = assets.archiveTaskVideo(owner.userId(), project.id(), pending.id(),
                () -> new java.io.ByteArrayInputStream(encoded));
        assertThat(archived.id()).isEqualTo(AssetService.taskVideoAssetId(pending.id()));
        long readyBefore = jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single();
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", pending.id()).update();
        MediaToolRunner neverEncode = mock(MediaToolRunner.class);
        MediaExportWorker resumed = new MediaExportWorker(tasks, assets, storage,
                neverEncode, mapper);
        assertThat(resumed.runOnce("resumed-export-worker")).isEqualTo(1);
        verify(neverEncode, never()).ffmpegExport(anyList(), any(), any(), any(), any());
        Task completed = tasks.get(owner.userId(), project.id(), pending.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("assetId").asText()).isEqualTo(archived.id().toString());
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single())
                .isEqualTo(readyBefore);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'SETTLEMENT'")
                .param("taskId", pending.id()).query(Integer.class).single())
                .isEqualTo(1);
        try (var children = Files.list(STORAGE_ROOT.resolve(project.id().toString()))) {
            assertThat(children.filter(path -> path.getFileName().toString()
                    .startsWith(".export-")).count()).isZero();
        }
    }

    /** Recovery closes an interrupted cancellation without another worker or settlement. */
    private void verifyExpiredRunningCancellation(AdminPrincipal owner, Project project,
            List<MediaExportService.SegmentRequest> ordered) {
        Task pending = exports.create(owner.userId(), project.id(),
                "expired-cancel-export", ordered);
        Task lease = tasks.claimExportsDue("expired-export-worker", 1).getFirst();
        assertThat(lease.id()).isEqualTo(pending.id());
        assertThat(exports.cancel(owner.userId(), project.id(), pending.id())
                .cancelRequested()).isTrue();
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", pending.id()).update();
        assertThat(tasks.recoverExpiredCancellations(16)).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), pending.id()).status())
                .isEqualTo(Task.Status.CANCELED);
        assertThat(releaseCount(pending.id())).isEqualTo(1);
        assertThat(tasks.recoverExpiredCancellations(16)).isZero();
        assertThat(releaseCount(pending.id())).isEqualTo(1);
    }

    /** Cancellation of a claimed export is persisted and removes its scratch directory. */
    private void verifyRunningCancellation(AdminPrincipal owner, Project project,
            List<MediaExportService.SegmentRequest> ordered) throws Exception {
        Task active = exports.create(owner.userId(), project.id(),
                "cancel-running-export", ordered);
        int assetsBefore = jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single();
        MediaToolRunner controlled = mock(MediaToolRunner.class);
        when(controlled.ffprobe(anyList())).thenReturn(
                "{\"streams\":[{\"codec_type\":\"video\"}],\"format\":{\"duration\":\"2.0\"}}");
        CountDownLatch encodingStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            BooleanSupplier shouldStop = invocation.getArgument(3);
            encodingStarted.countDown();
            for (int attempt = 0; attempt < 200; attempt++) {
                if (shouldStop.getAsBoolean()) {
                    throw new CancellationException("Controlled export canceled");
                }
                Thread.sleep(20);
            }
            throw new IllegalStateException("Controlled export did not observe cancellation");
        }).when(controlled).ffmpegExport(anyList(), any(), any(), any(), any());
        MediaExportWorker controlledWorker = new MediaExportWorker(tasks, assets, storage,
                controlled, mapper);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var running = pool.submit(() -> controlledWorker.runOnce("cancel-running-worker"));
            assertThat(encodingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(exports.cancel(owner.userId(), project.id(), active.id())
                    .cancelRequested()).isTrue();
            assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        Task canceled = tasks.get(owner.userId(), project.id(), active.id());
        assertThat(canceled.status()).isEqualTo(Task.Status.CANCELED);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'SETTLEMENT'")
                .param("taskId", active.id()).query(Integer.class).single()).isZero();
        assertThat(releaseCount(active.id())).isEqualTo(1);
        assertThat(canceled.output()).isNull();
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single())
                .isEqualTo(assetsBefore);
        try (var children = Files.list(STORAGE_ROOT.resolve(project.id().toString()))) {
            assertThat(children.filter(path -> path.getFileName().toString()
                    .startsWith(".export-")).count()).isZero();
        }
    }

    /** An encoder/disk/timeout failure is terminal failure, never a READY export asset. */
    private void verifyToolFailure(AdminPrincipal owner, Project project,
            List<MediaExportService.SegmentRequest> ordered) throws Exception {
        Task pending = exports.create(owner.userId(), project.id(),
                "failed-running-export", ordered);
        int assetsBefore = jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single();
        MediaToolRunner controlled = mock(MediaToolRunner.class);
        when(controlled.ffprobe(anyList())).thenReturn(
                "{\"streams\":[{\"codec_type\":\"video\"}],\"format\":{\"duration\":\"2.0\"}}");
        doAnswer(invocation -> {
            throw new MediaToolRunner.MediaToolException("Controlled timeout or full disk",
                    false, null);
        }).when(controlled).ffmpegExport(anyList(), any(), any(), any(), any());
        MediaExportWorker controlledWorker = new MediaExportWorker(tasks, assets, storage,
                controlled, mapper);
        assertThat(controlledWorker.runOnce("failed-export-worker")).isEqualTo(1);
        Task failed = tasks.get(owner.userId(), project.id(), pending.id());
        assertThat(failed.status()).isEqualTo(Task.Status.FAILED);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'SETTLEMENT'")
                .param("taskId", pending.id()).query(Integer.class).single()).isZero();
        assertThat(releaseCount(pending.id())).isEqualTo(1);
        assertThat(failed.errorCode()).isEqualTo("EXPORT_TOOL_FAILED");
        assertThat(failed.output()).isNotNull();
        assertThat(failed.output().isNull()).isTrue();
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single())
                .isEqualTo(assetsBefore);
        try (var children = Files.list(STORAGE_ROOT.resolve(project.id().toString()))) {
            assertThat(children.filter(path -> path.getFileName().toString()
                    .startsWith(".export-")).count()).isZero();
        }
    }

    /** A released reservation remains one append-only action across repeated notices. */
    private int releaseCount(UUID taskId) {
        return jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'RELEASE' and cost_status = 'UNKNOWN' "
                        + "and actual_cost is null and quantity_json ->> 'exportCount' = '1'")
                .param("taskId", taskId).query(Integer.class).single();
    }

    private Asset generatedVideo(UUID ownerId, UUID projectId, String color,
            String dimensions, int fps) throws IOException {
        return generatedVideo(ownerId, projectId, color, dimensions, fps, 1);
    }

    private Asset generatedVideo(UUID ownerId, UUID projectId, String color,
            String dimensions, int fps, int durationSeconds) throws IOException {
        Path rendered = Files.createTempFile(STORAGE_ROOT, "source-video-", ".mp4");
        try {
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "lavfi", "-i", "color=c=" + color + ":s=" + dimensions
                            + ":r=" + fps,
                    "-t", Integer.toString(durationSeconds), "-an", "-c:v", "libx264", "-preset", "veryfast",
                    "-crf", "23", "-y", rendered.toString()));
            return assets.archiveVideo(ownerId, projectId, Files.newInputStream(rendered));
        } finally {
            Files.deleteIfExists(rendered);
        }
    }

    private int framePixel(Path video, String positionSeconds) throws IOException {
        BufferedImage decoded = decodedFrame(video, positionSeconds);
        return decoded.getRGB(decoded.getWidth() / 2, decoded.getHeight() / 2);
    }

    private BufferedImage decodedFrame(Path video, String positionSeconds) throws IOException {
        Path image = Files.createTempFile(STORAGE_ROOT, "export-frame-", ".png");
        try {
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-ss", positionSeconds, "-i", video.toString(), "-frames:v", "1",
                    "-y", image.toString()));
            return ImageIO.read(image.toFile());
        } finally {
            Files.deleteIfExists(image);
        }
    }

    private ArtifactService.ArtifactView videoArtifact(UUID ownerId, UUID projectId,
            String title, UUID assetId) {
        return artifacts.create(ownerId, projectId, Artifact.Kind.VIDEO,
                title, mediaContent(assetId));
    }

    private ObjectNode mediaContent(UUID assetId) {
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", assetId.toString());
        content.put("prompt", "Fixture color");
        content.put("providerConfigVersion", 1);
        content.put("workflowVersion", "test-video-v1");
        content.putObject("parameters").put("mock", true);
        content.put("sourceTaskId", UUID.randomUUID().toString());
        return content;
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-export-it-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
