package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.MediaFunctionService;
import dev.agenvas.provider.application.MiniMaxH3ReferenceLoader;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaFunction;
import dev.agenvas.provider.infrastructure.MiniMaxH3Client;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.AudioTrimService;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.VideoOperationService;
import dev.agenvas.task.domain.AudioExtractionParameters;
import dev.agenvas.task.domain.AudioTrimParameters;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.VideoOperation;
import dev.agenvas.usage.application.UsageService;
import dev.agenvas.usage.domain.UsageEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL transactions and FFmpeg decoding; all files and account details are synthetic. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class AudioTrimPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @TempDir Path temp;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agenvas.storage.root", () -> storage.toString());
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired CanvasConnectionService connections;
    @Autowired MediaDraftService drafts;
    @Autowired AudioTrimService trims;
    @Autowired VideoOperationService operations;
    @Autowired MediaFunctionService functions;
    @Autowired MediaCapabilityService catalog;
    @Autowired MediaExecutionWorker worker;
    @Autowired TaskService tasks;
    @Autowired DirectMediaTaskService direct;
    @Autowired UsageService usage;
    @Autowired MediaToolRunner tools;
    @Autowired MiniMaxH3ReferenceLoader h3References;
    @Autowired MiniMaxH3Client h3Client;
    @Autowired WebApplicationContext webContext;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    private static AdminPrincipal owner;

    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("audio-trim-test", "synthetic-audio-password-123");
    }

    @ParameterizedTest
    @EnumSource(AudioExtractionParameters.Format.class)
    void trimsExactSourceIntervalIntoIndependentNodeAndFullyDecodes(AudioExtractionParameters.Format format) throws Exception {
        Source source = audioSource();
        Task accepted = run(source, 1_000, 3_000, format, "real-trim");
        UUID target = targetItem(accepted);
        assertThat(accepted.input().at("/audioTrim/sourceVersionId").asText()).isEqualTo(source.version().toString());
        assertThat(accepted.input().at("/mediaInput/audios/0/versionId").asText()).isEqualTo(source.version().toString());
        assertThat(accepted.input().path("artifactId").asText()).isEqualTo(source.artifact().toString());
        assertThat(target).isNotEqualTo(source.item());
        var blank = drafts.get(owner.userId(), source.project(), target);
        assertThat(blank.prompt()).isEmpty();
        assertThat(blank.parameters()).isEmpty();
        assertThat(blank.mediaInputs()).isEmpty();
        assertThat(connections.list(owner.userId(), source.project())).singleElement().satisfies(line -> {
            assertThat(line.relationType()).isEqualTo(CanvasConnection.RelationType.MEDIA_DERIVATION);
            assertThat(line.sourceArtifactVersionId()).isEqualTo(source.version());
        });
        assertThat(worker.submitOnce("trim-real-worker")).isEqualTo(1);
        Task complete = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(complete.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(complete.output().path("selected").asBoolean()).isTrue();
        var version = artifacts.requireMediaVersionForTask(owner.userId(), source.project(), resultVersion(complete), Artifact.Kind.AUDIO);
        assertThat(version.id()).isNotEqualTo(source.version());
        assertThat(version.inputReferences()).hasSize(1);
        assertThat(version.content().at("/parameters/startMs").asInt()).isEqualTo(1_000);
        assertThat(version.content().at("/parameters/endMs").asInt()).isEqualTo(3_000);
        assertThat(version.content().at("/parameters/format").asText()).isEqualTo(format.name());
        var result = assets.get(owner.userId(), source.project(), UUID.fromString(version.content().path("assetId").asText()));
        assertThat(result.asset().durationMs()).isBetween(2_000, 2_100);
        assertThat(result.asset().contentType()).isEqualTo(format == AudioExtractionParameters.Format.WAV ? "audio/wav" : "audio/mpeg");
        // The source's first second is silence. A non-silent 660 Hz result proves this is a crop, not a re-encode of the prefix.
        assertCroppedTone(result.path());
        assertThat(canvas.listMediaVersions(owner.userId(), source.project(), source.item())).extracting(v -> v.id()).containsExactly(source.version());
        assertThat(canvas.listMediaVersions(owner.userId(), source.project(), target)).extracting(v -> v.id()).contains(resultVersion(complete));
        assertThat(artifacts.get(owner.userId(), source.project(), source.artifact()).resourceDefaultVersion().id()).isEqualTo(source.version());
        assertThat(selectedVersion(source, source.item())).isEqualTo(source.version());
        assertThat(usage.listProject(owner.userId(), source.project())).filteredOn(entry -> accepted.id().equals(entry.taskId()))
                .allSatisfy(entry -> assertThat(entry.costSource()).isEqualTo("LOCAL_NO_COST"));
    }

    @Test void rejectsInvalidBoundsAndMetadataBeforeCreatingAnyTaskOrDerivedNode() throws Exception {
        Source source = audioSource();
        for (int[] bounds : List.of(new int[]{-1, 100}, new int[]{0, 99}, new int[]{100, 100},
                new int[]{0, 600_001}, new int[]{3_900, 4_100})) {
            assertThatThrownBy(() -> run(source, bounds[0], bounds[1], AudioExtractionParameters.Format.WAV, UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.code()).isEqualTo("AUDIO_TRIM_INVALID"));
        }
        var metadata = assets.metadata(owner.userId(), source.project(), source.asset());
        // Ingest already enforces the byte limit; a legacy/imported record must still be rejected at the task boundary.
        jdbc.sql("update asset set byte_size=:bytes where id=:id")
                .param("bytes", 50L * 1024 * 1024 + 1).param("id", source.asset()).update();
        assertThatThrownBy(() -> run(source, 0, 1_000, AudioExtractionParameters.Format.WAV, "oversized-source"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.code()).isEqualTo("AUDIO_TRIM_INVALID"));
        // A legal ten-minute compressed source can produce an oversized stereo PCM result.
        jdbc.sql("update asset set duration_ms=600000, byte_size=:bytes where id=:id")
                .param("bytes", metadata.byteSize()).param("id", source.asset()).update();
        assertThatThrownBy(() -> run(source, 0, 600_000, AudioExtractionParameters.Format.WAV, "oversized-wav"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.code()).isEqualTo("AUDIO_TRIM_OUTPUT_TOO_LARGE"));
        jdbc.sql("update asset set duration_ms=:duration, byte_size=:bytes where id=:id")
                .param("duration", metadata.durationMs()).param("bytes", metadata.byteSize()).param("id", source.asset()).update();
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
        assertThat(taskCount(source.project())).isZero();
        assertThat(connections.list(owner.userId(), source.project())).isEmpty();
        Task minimum = run(source, 0, 100, AudioExtractionParameters.Format.WAV, "minimum-duration");
        direct.cancelQueued(owner.userId(), source.project(), minimum.id());
    }

    @Test void enforcesOwnerProjectMediaTypeAndSelectedNodeCas() throws Exception {
        Source source = audioSource();
        Source foreign = audioSource();
        assertThatThrownBy(() -> trims.run(UUID.randomUUID(), source.project(), source.artifact(), source.item(), source.version(), 0,
                parameters(0, 1_000), "other-user"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> trims.run(owner.userId(), foreign.project(), source.artifact(), source.item(), source.version(), 0,
                parameters(0, 1_000), "other-project"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> trims.run(owner.userId(), source.project(), source.artifact(), source.item(), foreign.version(), 0,
                parameters(0, 1_000), "foreign-version"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.code()).isEqualTo("DIRECT_MEDIA_CONFLICT"));
        Source video = videoSource();
        assertThatThrownBy(() -> run(video, 0, 1_000, AudioExtractionParameters.Format.WAV, "wrong-kind"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.code()).isEqualTo("AUDIO_TRIM_INVALID"));
        canvas.selectMediaVersion(owner.userId(), source.project(), source.item(), source.version(), 0);
        assertThatThrownBy(() -> run(source, 0, 1_000, AudioExtractionParameters.Format.WAV, "old-node-version"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.code()).isEqualTo("DIRECT_MEDIA_CONFLICT"));
        assertThat(taskCount(source.project())).isZero();
        assertThat(taskCount(foreign.project())).isZero();
        assertThat(taskCount(video.project())).isZero();
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
    }

    @Test void concurrentSameCommandCreatesExactlyOneTaskNodeAndDerivationAndRejectsChangedRequest() throws Exception {
        Source source = audioSource();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Task> submit = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test did not start");
                return run(source, 500, 2_500, AudioExtractionParameters.Format.WAV, "concurrent-trim");
            };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Task accepted = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS).id()).isEqualTo(accepted.id());
            assertThat(run(source, 500, 2_500, AudioExtractionParameters.Format.WAV, "concurrent-trim").id()).isEqualTo(accepted.id());
            assertThatThrownBy(() -> run(source, 0, 2_500, AudioExtractionParameters.Format.WAV, "concurrent-trim"))
                    .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
            assertThat(canvas.list(owner.userId(), source.project())).hasSize(2);
            assertThat(connections.list(owner.userId(), source.project())).hasSize(1);
            assertThat(taskCount(source.project())).isEqualTo(1);
            direct.cancelQueued(owner.userId(), source.project(), accepted.id());
        }
    }

    @Test void restRequiresSessionAndCsrfRejectsCoercionAndReplaysDefaultWavRequest() throws Exception {
        Source source = audioSource();
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        String url = "/api/v1/projects/" + source.project() + "/artifacts/" + source.artifact() + "/audio-trims";
        ObjectNode body = mapper.createObjectNode().put("canvasItemId", source.item().toString())
                .put("sourceVersionId", source.version().toString()).put("expectedCanvasItemVersion", 0).put("startMs", 500).put("endMs", 2_500);
        mvc.perform(post(url).with(csrf()).header("Idempotency-Key", "rest-trim").contentType("application/json").content(body.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(auth).header("Idempotency-Key", "rest-trim").contentType("application/json").content(body.toString()))
                .andExpect(status().isForbidden());
        for (String field : List.of("startMs", "endMs", "expectedCanvasItemVersion")) {
            for (ObjectNode invalid : List.of(body.deepCopy().put(field, 1.5), body.deepCopy().put(field, "1"))) {
                mvc.perform(post(url).with(auth).with(csrf()).header("Idempotency-Key", "invalid-rest")
                                .contentType("application/json").content(invalid.toString())).andExpect(status().isBadRequest());
            }
        }
        for (ObjectNode invalid : List.of(body.deepCopy().put("unexpected", true), body.deepCopy().put("format", "FLAC"))) {
            mvc.perform(post(url).with(auth).with(csrf()).header("Idempotency-Key", "invalid-rest")
                            .contentType("application/json").content(invalid.toString())).andExpect(status().isBadRequest());
        }
        assertThat(taskCount(source.project())).isZero();
        String result = mvc.perform(post(url).with(auth).with(csrf()).header("Idempotency-Key", "rest-trim")
                        .contentType("application/json").content(body.toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID taskId = UUID.fromString(mapper.readTree(result).path("id").asText());
        assertThat(tasks.get(owner.userId(), source.project(), taskId).input().at("/audioTrim/parameters/format").asText()).isEqualTo("WAV");
        String replay = mvc.perform(post(url).with(auth).with(csrf()).header("Idempotency-Key", "rest-trim")
                        .contentType("application/json").content(body.deepCopy().put("format", "WAV").toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(replay).path("id").asText()).isEqualTo(taskId.toString());
        assertThat(taskCount(source.project())).isEqualTo(1);
        direct.cancelQueued(owner.userId(), source.project(), taskId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedOrRemovedTargetKeepsCompletedCropInHistoryOnly(boolean removeTarget) throws Exception {
        Source source = audioSource();
        Task accepted = run(source, 1_000, 3_000, AudioExtractionParameters.Format.WAV, "changed-target");
        UUID target = targetItem(accepted);
        if (removeTarget) canvas.apply(owner.userId(), source.project(), List.of(new CanvasService.Remove(target, 0)));
        else drafts.save(owner.userId(), source.project(), target, 0, "Preserved user draft", mapper.createObjectNode(),
                null, null, null, List.of(), List.of(), null);
        assertThat(worker.submitOnce("changed-target-worker")).isEqualTo(1);
        Task complete = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(complete.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(complete.output().path("selected").asBoolean()).isFalse();
        assertThat(artifacts.requireMediaVersionForTask(owner.userId(), source.project(), resultVersion(complete), Artifact.Kind.AUDIO)).isNotNull();
        if (removeTarget) assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
        else {
            assertThat(drafts.get(owner.userId(), source.project(), target).prompt()).isEqualTo("Preserved user draft");
            assertThat(selectedVersion(source, target)).isEqualTo(source.version());
            assertThat(canvas.listMediaVersions(owner.userId(), source.project(), target)).extracting(v -> v.id()).contains(resultVersion(complete));
        }
        assertThat(selectedVersion(source, source.item())).isEqualTo(source.version());
    }

    @ParameterizedTest
    @EnumSource(value = Task.Status.class, names = {"READY", "RUNNING", "SUBMITTING"})
    void cancellationFencesUnstartedWorkAndRecoversInterruptedActiveTrim(Task.Status state) throws Exception {
        Source source = audioSource();
        Task accepted = run(source, 0, 2_000, AudioExtractionParameters.Format.WAV, "cancel-trim");
        Task lease = null;
        if (state != Task.Status.READY) {
            lease = tasks.claimBoundMedia("trim-cancel-worker", 1).getFirst();
            assertThat(lease.id()).isEqualTo(accepted.id());
            if (state == Task.Status.SUBMITTING) tasks.beginSubmission(lease, "trim-cancel-worker");
        }
        Task canceled = direct.cancelQueued(owner.userId(), source.project(), accepted.id());
        assertThat(direct.cancelQueued(owner.userId(), source.project(), accepted.id())).isEqualTo(canceled);
        if (state == Task.Status.SUBMITTING) {
            assertThat(canceled.status()).isEqualTo(Task.Status.SUBMITTING);
            assertThat(canceled.cancelRequested()).isTrue();
            assertThat(canceled.leaseEpoch()).isEqualTo(lease.leaseEpoch());
            jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:id").param("id", accepted.id()).update();
            assertThat(tasks.recoverExpiredSubmissions(10)).isEqualTo(1);
            assertThat(tasks.recoverExpiredCancellations(10)).isEqualTo(1);
        } else {
            assertThat(canceled.status()).isEqualTo(Task.Status.CANCELED);
            if (lease != null) {
                Task stale = lease;
                assertThat(canceled.leaseEpoch()).isGreaterThan(stale.leaseEpoch());
                assertThatThrownBy(() -> tasks.beginSubmission(stale, "trim-cancel-worker"))
                        .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.code()).isEqualTo("TASK_LEASE_LOST"));
            }
        }
        assertThat(tasks.get(owner.userId(), source.project(), accepted.id()).status()).isEqualTo(Task.Status.CANCELED);
        assertThat(worker.submitOnce("after-trim-cancel")).isZero();
        assertThat(canvas.listMediaVersions(owner.userId(), source.project(), targetItem(accepted))).isEmpty();
        assertThat(selectedVersion(source, targetItem(accepted))).isEqualTo(source.version());
        assertThat(usage.listProject(owner.userId(), source.project())).filteredOn(entry -> accepted.id().equals(entry.taskId()))
                .extracting(UsageEntry::entryType).contains(UsageEntry.EntryType.RELEASE);
    }

    @Test void missingSourceBeforeProcessingBlocksAndReleasesLocalReservation() throws Exception {
        Source source = audioSource();
        Task accepted = run(source, 0, 2_000, AudioExtractionParameters.Format.WAV, "source-disappeared");
        Files.delete(assets.get(owner.userId(), source.project(), source.asset()).path());
        assertThat(worker.submitOnce("missing-source-worker")).isEqualTo(1);
        Task blocked = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(blocked.errorCode()).isEqualTo("AUDIO_TRIM_INVALID");
        assertThat(blocked.providerRequestId()).isNull();
        assertThat(canvas.listMediaVersions(owner.userId(), source.project(), targetItem(accepted))).isEmpty();
        assertThat(usage.listProject(owner.userId(), source.project())).filteredOn(entry -> accepted.id().equals(entry.taskId()))
                .extracting(UsageEntry::entryType).containsExactlyInAnyOrder(UsageEntry.EntryType.RESERVATION, UsageEntry.EntryType.RELEASE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCAL_AUDIO_TRIM_FAILED", "LOCAL_AUDIO_TRIM_TIMEOUT"})
    void confirmedLocalFailureClosesReservationWithoutAResult(String errorCode) throws Exception {
        Source source = audioSource();
        Task accepted = run(source, 0, 2_000, AudioExtractionParameters.Format.WAV, "failed-local-trim");
        Task lease = tasks.claimBoundMedia("failed-trim-worker", 1).getFirst();
        assertThat(lease.id()).isEqualTo(accepted.id());
        tasks.beginSubmission(lease, "failed-trim-worker");
        tasks.rejectSubmission(lease, "failed-trim-worker", errorCode);
        Task failed = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(failed.status()).isEqualTo(Task.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo(errorCode);
        assertThat(canvas.listMediaVersions(owner.userId(), source.project(), targetItem(accepted))).isEmpty();
        assertThat(selectedVersion(source, source.item())).isEqualTo(source.version());
        assertThat(usage.listProject(owner.userId(), source.project())).filteredOn(entry -> accepted.id().equals(entry.taskId()))
                .allSatisfy(entry -> assertThat(entry.costSource()).isEqualTo("LOCAL_NO_COST"))
                .extracting(UsageEntry::entryType).containsExactlyInAnyOrder(UsageEntry.EntryType.RESERVATION, UsageEntry.EntryType.RELEASE);
    }

    @Test void extractedVideoAudioCanBeTrimmedAndFrozenAsActualH3ReferenceWithoutCallingProvider() throws Exception {
        Source video = videoSource();
        long functionVersion = functions.list().stream().filter(setting -> setting.operation() == MediaFunction.VIDEO_EXTRACT_AUDIO)
                .findFirst().orElseThrow().version();
        Task extraction = operations.run(owner.userId(), video.project(), video.artifact(), video.item(), video.version(),
                0, VideoOperation.EXTRACT_AUDIO, functionVersion, 1, "", mapper.createObjectNode(), "closed-loop-extract");
        assertThat(worker.submitOnce("closed-loop-extract-worker")).isEqualTo(1);
        Task extracted = tasks.get(owner.userId(), video.project(), extraction.id());
        assertThat(extracted.status()).isEqualTo(Task.Status.SUCCEEDED);
        UUID audioArtifact = UUID.fromString(extraction.input().path("artifactId").asText());
        UUID audioItem = targetItem(extraction);
        UUID audioVersion = resultVersion(extracted);
        long audioItemVersion = canvas.list(owner.userId(), video.project()).stream().filter(entry -> entry.item().id().equals(audioItem))
                .findFirst().orElseThrow().item().version();
        Task trim = trims.run(owner.userId(), video.project(), audioArtifact, audioItem, audioVersion, audioItemVersion,
                parameters(500, 3_500), "closed-loop-trim");
        assertThat(worker.submitOnce("closed-loop-trim-worker")).isEqualTo(1);
        Task trimmed = tasks.get(owner.userId(), video.project(), trim.id());
        assertThat(trimmed.status()).isEqualTo(Task.Status.SUCCEEDED);
        UUID referenceVersion = resultVersion(trimmed);
        var connection = catalog.createConnection(UUID.randomUUID().toString(), "Synthetic H3 preflight", "MINIMAX", null, "synthetic-not-a-real-key");
        var capability = catalog.publishCapability(connection.id(), "H3 reference preflight", "MINIMAX_H3");
        var target = artifacts.create(owner.userId(), video.project(), Artifact.Kind.VIDEO, "H3 target", null).artifact();
        UUID targetItem = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), video.project(), target.id());
        var draft = drafts.save(owner.userId(), video.project(), targetItem, 0, "Use the reference voice", mapper.createObjectNode(),
                4, capability.id(), MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(referenceVersion, MediaDraft.InputRole.AUDIO_REFERENCE, "#67C7F3")), List.of(), null);
        Task generation = direct.run(owner.userId(), video.project(), target.id(), targetItem, draft.version(), "closed-loop-h3");
        try {
            var references = h3References.load(new AttemptContext(generation, null, owner.userId(), "preflight-only", null), false);
            assertThat(references).singleElement().satisfies(reference -> {
                assertThat(reference.type()).isEqualTo("audio_url");
                assertThat(reference.role()).isEqualTo("reference_audio");
                assertThat(reference.url()).startsWith("data:audio/wav;base64,");
            });
            var pinned = artifacts.requireMediaVersionForTask(owner.userId(), video.project(), referenceVersion, Artifact.Kind.AUDIO);
            Path file = assets.get(owner.userId(), video.project(), UUID.fromString(pinned.content().path("assetId").asText())).path();
            byte[] referencedBytes = Base64.getDecoder().decode(references.getFirst().url().split(",", 2)[1]);
            assertThat(referencedBytes).isEqualTo(Files.readAllBytes(file));
            var request = h3Client.prepare("Use the reference voice", references, 4, "adaptive", "768p");
            assertThat(request.at("/content/1/role").asText()).isEqualTo("reference_audio");
            assertThat(generation.input().at("/mediaInput/audios/0/versionId").asText()).isEqualTo(referenceVersion.toString());
            assertThat(tasks.listProviderAttempts(owner.userId(), video.project(), generation.id())).isEmpty();
        } finally { direct.cancelQueued(owner.userId(), video.project(), generation.id()); }
    }

    private Source audioSource() throws Exception {
        UUID project = projects.create(owner.userId(), "Synthetic audio trim", Project.AspectRatio.LANDSCAPE_16_9).id();
        Path path = temp.resolve(UUID.randomUUID() + ".wav");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                "aevalsrc=if(lt(t\\,1)\\,0\\,0.25*sin(2*PI*660*t)):s=48000:d=4", "-c:a", "pcm_s16le", "-y", path.toString()));
        var asset = assets.archiveTaskAudio(owner.userId(), project, UUID.randomUUID(), () -> open(path));
        return media(project, Artifact.Kind.AUDIO, asset.id());
    }

    private Source videoSource() throws Exception {
        UUID project = projects.create(owner.userId(), "Synthetic audio extraction", Project.AspectRatio.LANDSCAPE_16_9).id();
        Path path = temp.resolve(UUID.randomUUID() + ".mp4");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                "color=c=blue:s=320x180:r=24:d=4", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=4",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "-y", path.toString()));
        var asset = assets.archiveTaskVideo(owner.userId(), project, UUID.randomUUID(), () -> open(path));
        return media(project, Artifact.Kind.VIDEO, asset.id());
    }

    private Source media(UUID project, Artifact.Kind kind, UUID asset) {
        var artifact = artifacts.create(owner.userId(), project, kind, "Synthetic source",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.toString()));
        UUID item = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project, artifact.artifact().id());
        return new Source(project, artifact.artifact().id(), item, artifact.resourceDefaultVersion().id(), asset);
    }

    private void assertCroppedTone(Path file) throws IOException {
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-xerror", "-i", file.toString(), "-f", "null", "-"));
        Path pcm = temp.resolve(UUID.randomUUID() + ".pcm");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-i", file.toString(),
                "-ac", "1", "-ar", "8000", "-f", "s16le", "-y", pcm.toString()));
        byte[] bytes = Files.readAllBytes(pcm);
        assertThat(bytes.length).isBetween(31_900, 33_000);
        int crossings = 0, previous = 0;
        long energy = 0;
        for (int sample = 0; sample < bytes.length / 2; sample++) {
            int value = (short) ((bytes[2 * sample] & 0xff) | bytes[2 * sample + 1] << 8);
            if (sample > 0 && previous <= 0 && value > 0) crossings++;
            previous = value;
            energy += (long) value * value;
        }
        double seconds = bytes.length / 16_000.0;
        assertThat(crossings / seconds).isBetween(640.0, 680.0);
        assertThat(Math.sqrt((double) energy / (bytes.length / 2))).isGreaterThan(3_000);
    }

    private Task run(Source source, int start, int end, AudioExtractionParameters.Format format, String key) {
        return trims.run(owner.userId(), source.project(), source.artifact(), source.item(), source.version(), 0,
                new AudioTrimParameters(start, end, format), key);
    }
    private AudioTrimParameters parameters(int start, int end) {
        return new AudioTrimParameters(start, end, AudioExtractionParameters.Format.WAV);
    }
    private UUID targetItem(Task task) { return UUID.fromString(task.input().path("canvasItemId").asText()); }
    private UUID resultVersion(Task task) { return UUID.fromString(task.output().path("artifactVersionId").asText()); }
    private UUID selectedVersion(Source source, UUID item) {
        return canvas.list(owner.userId(), source.project()).stream().filter(entry -> entry.item().id().equals(item))
                .findFirst().orElseThrow().item().selectedVersionId();
    }
    private long taskCount(UUID project) {
        return jdbc.sql("select count(*) from task where project_id=:id").param("id", project).query(Long.class).single();
    }
    private static java.io.InputStream open(Path path) {
        try { return Files.newInputStream(path); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    private record Source(UUID project, UUID artifact, UUID item, UUID version, UUID asset) {}
}
