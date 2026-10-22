package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.ManualUnknownRetryService;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.VideoCompositionService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import dev.agenvas.usage.domain.UsageEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL and decoded synthetic assets. Controlled rendering gates prove cancellation without Provider calls. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false", "agenvas.provider.mode=mock"})
class VideoCompositionLifecyclePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @TempDir Path temp;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("agenvas.storage.root", () -> storage.toString());
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaToolRunner mediaTools;
    @Autowired CanvasService canvas;
    @Autowired MediaDraftService drafts;
    @Autowired VideoCompositionService compositions;
    @Autowired TaskService tasks;
    @Autowired TaskRepository repository;
    @Autowired DirectMediaTaskService direct;
    @Autowired ManualUnknownRetryService retries;
    @Autowired UsageService usage;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired MediaExecutionWorker worker;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean LocalVideoCompositionRenderer renderer;
    static AdminPrincipal owner;

    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("composition-lifecycle", "synthetic-lifecycle-password-123");
    }

    @Test void preSubmissionCancellationFencesTheClaimedWorkerAndDoesNotOpenExternalCancellation() throws Exception {
        Fixture fixture = fixture();
        Task accepted = compositions.run(owner.userId(), fixture.project(), request(fixture), "before-submit");
        Task lease = tasks.claimBoundMedia("cancel-before-submit", 1).getFirst();
        Task canceled = direct.cancelQueued(owner.userId(), fixture.project(), accepted.id());
        assertThat(canceled.status()).isEqualTo(Task.Status.CANCELED);
        assertThat(canceled.leaseEpoch()).isGreaterThan(lease.leaseEpoch());
        assertThat(direct.cancelQueued(owner.userId(), fixture.project(), accepted.id())).isEqualTo(canceled);
        assertThatThrownBy(() -> tasks.beginSubmission(lease, "cancel-before-submit"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_LEASE_LOST"));
        assertThat(tasks.listProviderAttempts(owner.userId(), fixture.project(), accepted.id())).isEmpty();
        assertThat(repository.cancelVideoComposition(lease, java.time.Instant.now())).isFalse();
        assertThat(usage.listProject(owner.userId(), fixture.project())).filteredOn(e -> e.taskId().equals(accepted.id()))
                .extracting(UsageEntry::entryType).containsExactlyInAnyOrder(UsageEntry.EntryType.RESERVATION, UsageEntry.EntryType.RELEASE);

        var card = artifacts.create(owner.userId(), fixture.project(), Artifact.Kind.IMAGE, "External generation", null);
        UUID item = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), fixture.project(), card.artifact().id());
        dev.agenvas.support.CanvasMediaFixture.save(drafts, owner.userId(), fixture.project(), item, 0, "Synthetic", null, null, null);
        Task external = direct.run(owner.userId(), fixture.project(), card.artifact().id(), item, 1, "external-running");
        Task externalLease = tasks.claimBoundMedia("ordinary-worker", 1).getFirst();
        assertThat(externalLease.id()).isEqualTo(external.id());
        assertThatThrownBy(() -> direct.cancelQueued(owner.userId(), fixture.project(), external.id()))
                .isInstanceOf(ApiProblemException.class);
        assertThat(tasks.get(owner.userId(), fixture.project(), external.id()).cancelRequested()).isFalse();
        tasks.fail(externalLease, "ordinary-worker", "SYNTHETIC_TEST_DONE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationKeepsLateResultsInHistoryWithSameEpochOnly(boolean recoverFirst) throws Exception {
        Fixture fixture = fixture();
        Task accepted = compositions.run(owner.userId(), fixture.project(), request(fixture), "late-result");
        String workerId = "late-composition-worker";
        Task lease = tasks.claimBoundMedia(workerId, 1).getFirst();
        tasks.beginSubmission(lease, workerId);
        Task cancel = direct.cancelQueued(owner.userId(), fixture.project(), accepted.id());
        assertThat(cancel.status()).isEqualTo(Task.Status.SUBMITTING);
        assertThat(cancel.cancelRequested()).isTrue();
        assertThat(cancel.leaseEpoch()).isEqualTo(lease.leaseEpoch());
        assertThat(direct.cancelQueued(owner.userId(), fixture.project(), accepted.id())).isEqualTo(cancel);
        assertThatThrownBy(() -> tasks.heartbeat(lease.id(), workerId, lease.leaseEpoch()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_LEASE_LOST"));
        if (recoverFirst) {
            jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:id").param("id", accepted.id()).update();
            assertThat(tasks.recoverExpiredSubmissions(10)).isEqualTo(1);
            assertThat(tasks.get(owner.userId(), fixture.project(), accepted.id()).status()).isEqualTo(Task.Status.UNKNOWN);
            assertThat(tasks.recoverExpiredCancellations(10)).isEqualTo(1);
            assertThat(tasks.get(owner.userId(), fixture.project(), accepted.id()).status()).isEqualTo(Task.Status.CANCELED);
        }
        ObjectNode content = mapper.createObjectNode().put("assetId", fixture.videoAsset().toString())
                .put("prompt", "Synthetic late local result").put("workflowVersion", "synthetic-local-v1")
                .put("sourceTaskId", accepted.id().toString());
        content.putObject("parameters");
        var result = tasks.succeedWithArtifact(lease, workerId, content);
        assertThat(result.selected()).isFalse();
        assertThat(tasks.get(owner.userId(), fixture.project(), accepted.id()).status()).isEqualTo(Task.Status.CANCELED);
        UUID item = UUID.fromString(accepted.input().path("canvasItemId").asText());
        assertThat(canvas.list(owner.userId(), fixture.project()).stream().filter(e -> e.item().id().equals(item))
                .findFirst().orElseThrow().selectedVersion()).isNull();
        assertThat(jdbc.sql("select count(*) from task_late_result where task_id=:id").param("id", accepted.id())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(artifacts.requireMediaVersionForTask(owner.userId(), fixture.project(), result.versionId(), Artifact.Kind.VIDEO)
                .inputReferences()).hasSize(2);
        assertThatThrownBy(() -> tasks.succeedWithArtifact(lease, workerId, content))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_LEASE_LOST"));
        assertThat(jdbc.sql("select count(*) from artifact_version where artifact_id=:id")
                .param("id", UUID.fromString(accepted.input().path("artifactId").asText())).query(Long.class).single()).isEqualTo(1);
    }

    @Test void activeRendererObservesCancellationAndFinishesWithoutSelectingOutput() throws Exception {
        Fixture fixture = fixture();
        Task accepted = compositions.run(owner.userId(), fixture.project(), request(fixture), "renderer-cancel");
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!proceed.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Test renderer gate timed out");
            return invocation.callRealMethod();
        }).when(renderer).render(any(), any(), any(), any(), any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var rendering = executor.submit(() -> worker.submitOnce("renderer-cancel-worker"));
            try {
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(direct.cancelQueued(owner.userId(), fixture.project(), accepted.id()).cancelRequested()).isTrue();
            } finally { proceed.countDown(); }
            assertThat(rendering.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        }
        Task completed = tasks.get(owner.userId(), fixture.project(), accepted.id());
        assertThat(completed.status()).isEqualTo(Task.Status.CANCELED);
        assertThat(completed.output()).isNull();
        assertThat(usage.listProject(owner.userId(), fixture.project())).filteredOn(e -> e.taskId().equals(accepted.id()))
                .extracting(UsageEntry::entryType).contains(UsageEntry.EntryType.RELEASE);
    }

    @Test void explicitRetryOfDirectorCompositionHasZeroCostAndNewUserIdentity() throws Exception {
        Fixture fixture = fixture();
        var agent = dev.agenvas.testing.AgentFixture.configureDirector(agents, owner.userId(), fixture.project(), "Director", "Compose");
        var run = runs.create(owner.userId(), fixture.project(), agent.id(), "Compose", "retry-director").run();
        Task original = compositions.runFromAgent(owner.userId(), fixture.project(), run.id(), request(fixture), "director-composition");
        Task lease = tasks.claimBoundMedia("interrupted-local-worker", 1).getFirst();
        tasks.beginSubmission(lease, "interrupted-local-worker");
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:id").param("id", original.id()).update();
        assertThat(tasks.recoverExpiredSubmissions(10)).isEqualTo(1);
        Task unknown = tasks.get(owner.userId(), fixture.project(), original.id());
        Task replacement = retries.create(owner.userId(), fixture.project(), original.id(), unknown.version(), "user-local-retry");
        assertThat(retries.create(owner.userId(), fixture.project(), original.id(), unknown.version(), "user-local-retry").id())
                .isEqualTo(replacement.id());
        assertThat(replacement.initiator()).isEqualTo(Task.Initiator.USER_DIRECT);
        assertThat(replacement.compositionRunId()).isNull();
        assertThat(replacement.input().path("videoComposition")).isEqualTo(original.input().path("videoComposition"));
        assertThat(replacement.input().path("mediaInput")).isEqualTo(original.input().path("mediaInput"));
        assertThat(replacement.inputHash()).isEqualTo(Sha256.hex(replacement.input().toString()));
        assertThat(repository.findManualReplacement(fixture.project(), original.id())).get()
                .extracting(TaskRepository.ManualReplacement::replacementTaskId).isEqualTo(replacement.id());
        assertThat(tasks.get(owner.userId(), fixture.project(), original.id()).compositionRunId()).isEqualTo(run.id());
        assertThat(usage.listProject(owner.userId(), fixture.project())).filteredOn(e -> replacement.id().equals(e.taskId()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.costSource()).isEqualTo("LOCAL_NO_COST");
                    assertThat(entry.costStatus()).isEqualTo(UsageEntry.CostStatus.KNOWN);
                    assertThat(entry.estimatedCost()).isZero();
                    assertThat(entry.actualCost()).isZero();
                });
        direct.cancelQueued(owner.userId(), fixture.project(), replacement.id());
    }

    private ObjectNode request(Fixture fixture) {
        var request = mapper.createObjectNode().put("title", "Synthetic composition");
        request.putArray("shots").addObject().put("videoVersionId", fixture.video().toString())
                .putArray("lines").addObject().put("audioVersionId", fixture.audio().toString());
        return request;
    }

    private Fixture fixture() throws Exception {
        UUID project = projects.create(owner.userId(), "Lifecycle", Project.AspectRatio.LANDSCAPE_16_9).id();
        Path videoFile = temp.resolve(UUID.randomUUID() + ".mp4");
        mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                "color=c=blue:s=160x90:r=12:d=2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", videoFile.toString()));
        var videoAsset = assets.archiveTaskVideo(owner.userId(), project, UUID.randomUUID(), () -> {
            try { return Files.newInputStream(videoFile); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
        UUID audioAsset = dev.agenvas.testing.AudioAssetFixture.archive(assets, mediaTools, owner.userId(), project);
        return new Fixture(project, media(project, Artifact.Kind.VIDEO, videoAsset.id()),
                media(project, Artifact.Kind.AUDIO, audioAsset), videoAsset.id());
    }

    private UUID media(UUID project, Artifact.Kind kind, UUID asset) {
        var artifact = artifacts.create(owner.userId(), project, kind, "Synthetic " + kind,
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.toString()));
        dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project, artifact.artifact().id());
        return artifact.resourceDefaultVersion().id();
    }
    private record Fixture(UUID project, UUID video, UUID audio, UUID videoAsset) {}
}
