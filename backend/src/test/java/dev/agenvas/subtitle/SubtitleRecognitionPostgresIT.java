package dev.agenvas.subtitle;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.subtitle.application.*;
import dev.agenvas.subtitle.domain.*;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Controlled ASR boundary, real PostgreSQL and synthetic local FFmpeg media. No real model runs. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {"agenvas.subtitles.worker-enabled=false",
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class SubtitleRecognitionPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @TempDir Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agenvas.storage.root", () -> storage.toString());
        r.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaToolRunner tools;
    @Autowired SubtitleService subtitles;
    @Autowired SubtitleRecognitionService recognition;
    @Autowired TaskService tasks;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @MockitoBean WhisperCppTranscriber recognizer;
    @MockitoBean dev.agenvas.subtitle.runtime.ManagedAsrService runtime;
    static AdminPrincipal owner;

    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("asr-test-admin", "synthetic-asr-password-123");
        var fixtureRuntime = mock(dev.agenvas.subtitle.runtime.ManagedAsrService.Snapshot.class);
        when(fixtureRuntime.enabled()).thenReturn(true);
        when(runtime.get()).thenReturn(fixtureRuntime);
        when(recognizer.availability()).thenReturn(WhisperCppTranscriber.Availability.AVAILABLE);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            WhisperCppTranscriber.Source source = invocation.getArgument(0);
            return transcript(source.versionId(), invocation.getArgument(1));
        }).when(recognizer).transcribe(any(), anyString(), any(), any(), any());
    }
    @Test void recognizesAPinnedVersionAndReplaysAcceptanceWithoutChangingTheDraftAgain() throws Exception {
        var draft = fixture();
        var request = request(0);
        var task = recognition.start(owner.userId(), draft.projectId(), draft.id(), request, "recognize-once");
        assertThat(task.kind()).isEqualTo(Task.Kind.SUBTITLE_TRANSCRIPTION);
        assertThat(recognition.start(owner.userId(), draft.projectId(), draft.id(), request, "recognize-once").id()).isEqualTo(task.id());
        assertThat(recognition.processNext("asr-worker")).isEqualTo(1);
        var saved = subtitles.get(owner.userId(), draft.projectId(), draft.id());
        assertThat(saved.track().cues()).containsExactly(new SubtitleCue("受控识别夹具", 100, 1900));
        assertThat(saved.version()).isEqualTo(2);
        assertThat(tasks.get(owner.userId(), draft.projectId(), task.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThatThrownBy(() -> recognition.start(owner.userId(), draft.projectId(), draft.id(), request(2), "recognize-once"))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
    @Test void aLateRecognitionResultCannotOverwriteCorrectionsSavedDuringExecution() throws Exception {
        var draft = fixture();
        var task = recognition.start(owner.userId(), draft.projectId(), draft.id(), request(0), "edit-race");
        doAnswer(invocation -> {
            subtitles.save(owner.userId(), draft.projectId(), draft.id(), mapper.readTree(
                    "{\"expectedVersion\":1,\"cues\":[{\"text\":\"人工校正\",\"startMs\":200,\"endMs\":1800}]}"));
            return transcript(draft.track().sourceVersionId(), "zh");
        }).when(recognizer).transcribe(any(), anyString(), any(), any(), any());
        recognition.processNext("asr-edit-race");
        assertThat(subtitles.get(owner.userId(), draft.projectId(), draft.id()).track().cues().getFirst().text()).isEqualTo("人工校正");
        var output = tasks.get(owner.userId(), draft.projectId(), task.id()).output();
        assertThat(output.path("applied").asBoolean()).isFalse();
        assertThat(output.at("/cues/0/text").asText()).isEqualTo("受控识别夹具");
    }
    @Test void queuedAndRunningCancellationRetainCorrectionsAndDoNotPublishLateOutput() throws Exception {
        var draft = fixture();
        var queued = recognition.start(owner.userId(), draft.projectId(), draft.id(), request(0), "cancel-queued");
        assertThat(recognition.cancel(owner.userId(), draft.projectId(), draft.id(), queued.id()).status()).isEqualTo(Task.Status.CANCELED);
        assertThat(recognition.processNext("asr-cancel-queued")).isZero();
        var active = recognition.start(owner.userId(), draft.projectId(), draft.id(), request(1), "cancel-active");
        doAnswer(invocation -> {
            recognition.cancel(owner.userId(), draft.projectId(), draft.id(), active.id());
            BooleanSupplier canceled = invocation.getArgument(2);
            assertThat(canceled.getAsBoolean()).isTrue();
            throw new CancellationException();
        }).when(recognizer).transcribe(any(), anyString(), any(), any(), any());
        recognition.processNext("asr-cancel-active");
        assertThat(tasks.get(owner.userId(), draft.projectId(), active.id()).status()).isEqualTo(Task.Status.CANCELED);
        assertThat(subtitles.get(owner.userId(), draft.projectId(), draft.id()).track().cues()).isEmpty();
    }
    @Test void expiredLocalLeasesRecoverAndAnOldEpochCannotCommitItsDraftWrite() throws Exception {
        var draft = fixture();
        var task = recognition.start(owner.userId(), draft.projectId(), draft.id(), request(0), "recover-asr");
        var old = tasks.claimSubtitleTranscriptions("old-asr-worker", 1).getFirst();
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:id").param("id", task.id()).update();
        var current = tasks.claimSubtitleTranscriptions("new-asr-worker", 1).getFirst();
        assertThat(current.leaseEpoch()).isGreaterThan(old.leaseEpoch());
        assertThatThrownBy(() -> recognition.complete(old, transcript(draft.track().sourceVersionId(), "zh")))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.code()).isEqualTo("TASK_LEASE_LOST"));
        assertThat(subtitles.get(owner.userId(), draft.projectId(), draft.id()).track().cues()).isEmpty();
        recognition.complete(current, transcript(draft.track().sourceVersionId(), "zh"));
        assertThat(subtitles.get(owner.userId(), draft.projectId(), draft.id()).track().cues()).hasSize(1);
    }
    @Test void recognitionFailureRemainsAnExplicitFailedTask() throws Exception {
        var draft = fixture();
        var task = recognition.start(owner.userId(), draft.projectId(), draft.id(), request(0), "failed-asr");
        doThrow(new LocalAsrException(LocalAsrException.Code.TIMED_OUT)).when(recognizer).transcribe(any(), anyString(), any(), any(), any());
        recognition.processNext("asr-failure");
        var failed = tasks.get(owner.userId(), draft.projectId(), task.id());
        assertThat(failed.status()).isEqualTo(Task.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("LOCAL_ASR_TIMED_OUT");
        assertThat(subtitles.get(owner.userId(), draft.projectId(), draft.id()).track().cues()).isEmpty();
    }
    @Test void concurrentCorrectionSavesHaveOneWinnerAndNeverMergeUnrelatedTextSilently() throws Exception {
        var draft = fixture();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(2);
            var results = executor.invokeAll(List.<Callable<Boolean>>of(() -> saveRace(draft, "first", gate), () -> saveRace(draft, "second", gate)), 5, TimeUnit.SECONDS);
            assertThat(results.stream().map(future -> { try { return future.get(); } catch (Exception e) { throw new AssertionError(e); } }).toList())
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(subtitles.get(owner.userId(), draft.projectId(), draft.id()).version()).isEqualTo(1);
    }
    private boolean saveRace(SubtitleDraft draft, String text, CountDownLatch gate) throws Exception {
        gate.countDown(); gate.await(2, TimeUnit.SECONDS);
        try {
            subtitles.save(owner.userId(), draft.projectId(), draft.id(), mapper.readTree(
                    "{\"expectedVersion\":0,\"cues\":[{\"text\":\"" + text + "\",\"startMs\":100,\"endMs\":1900}]}"));
            return true;
        } catch (ApiProblemException conflict) {
            assertThat(conflict.code()).isEqualTo("SUBTITLE_VERSION_CONFLICT"); return false;
        }
    }
    private tools.jackson.databind.JsonNode request(long expected) {
        return mapper.createObjectNode().put("expectedVersion", expected).put("language", "zh");
    }
    private SubtitleTrack transcript(UUID source, String language) {
        return new SubtitleTrack(source, 2000, language, List.of(new SubtitleCue("受控识别夹具", 100, 1900)));
    }
    private SubtitleDraft fixture() throws Exception {
        var project = projects.create(owner.userId(), "ASR fixture", Project.AspectRatio.LANDSCAPE_16_9);
        Path audio = temp.resolve(UUID.randomUUID() + ".wav");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-y", audio.toString()));
        var asset = assets.archiveTaskAudio(owner.userId(), project.id(), UUID.randomUUID(), () -> {
            try { return Files.newInputStream(audio); } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        var version = artifacts.create(owner.userId(), project.id(), Artifact.Kind.AUDIO, "Synthetic ASR input",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString())).resourceDefaultVersion();
        return subtitles.create(owner.userId(), project.id(), mapper.createObjectNode().put("sourceVersionId", version.id().toString()));
    }
}
