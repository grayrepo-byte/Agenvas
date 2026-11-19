package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.asset.application.AssetRepository;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.MockImageAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.application.TaskService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real Task/PostgreSQL/asset files; the compiled Mock boundary returns synthetic one-shot image streams. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class ImageStudioArchiveRecoveryPostgresIT {
    @TempDir
    static Path root;
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agenvas.storage.root", root::toString);
    }

    @Autowired
    IdentityService identities;
    @Autowired
    WebApplicationContext context;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    MediaExecutionWorker worker;
    @Autowired
    TaskService tasks;
    @Autowired
    JdbcClient jdbc;
    @MockitoSpyBean
    AssetRepository assetRows;
    @MockitoSpyBean
    TaskRepository taskRows;
    @MockitoSpyBean
    MockImageAdapter upstream;
    private static AdminPrincipal owner;

    @BeforeEach
    void syntheticBoundaryOnly() throws Exception {
        if (owner == null)
            owner = identities.setup("studio-archive-admin", "studio-archive-password-123");
        byte[] bytes = image();
        doAnswer(invocation -> new Submission.Completed(new MediaPayload(new ByteArrayInputStream(bytes), "image/png")))
                .when(upstream).submit(any());
        doThrow(new AssertionError("A synchronous archive must never query a provider"))
                .when(upstream).reconcile(any());
    }

    @Test
    void metadataPublicationFailureRetriesSavedBytesWithoutSubmittingOrReconciling() throws Exception {
        JsonNode batch = accept();
        UUID taskId = UUID.fromString(batch.at("/tasks/0/id").asText());
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.getAndSet(false))
                throw new IllegalStateException("Synthetic metadata publication failure");
            return invocation.callRealMethod();
        }).when(assetRows).insert(any());

        assertThatCode(() -> worker.submitOnce("studio-archive-submit")).doesNotThrowAnyException();
        assertState(taskId, "WAITING_PROVIDER");
        assertThat(jdbc.sql("select provider_request_id from task where id=:id").param("id", taskId)
                .query(String.class).optional()).isEmpty();
        due(taskId);
        // A process can die after claiming the archive. Such a lease must never become a new submission.
        jdbc.sql("update task set status='RUNNING',lease_owner='stale-archive-worker',"
                + "lease_until=now()-interval '1 second' where id=:id").param("id", taskId).update();
        assertThat(worker.submitOnce("studio-archive-no-submit")).isZero();
        assertThat(worker.pollOnce("studio-archive-resume")).isEqualTo(1);
        assertState(taskId, "SUCCEEDED");
        JsonNode restored = batch(batch.path("id").asText());
        assertThat(restored.at("/tasks/0/errorCode").isNull())
                .as("A successfully recovered Studio result must stop reporting an archive retry")
                .isTrue();
        assertThat(restored.at("/tasks/0/results")).hasSize(1);
        String assetId = restored.at("/tasks/0/results/0/assetId").asText();
        assertThat(webAppContextSetup(context).apply(springSecurity()).build().perform(get("/api/v1/projects/"
                + restored.path("projectId").asText() + "/assets/" + assetId + "/content").with(authentication(
                        new UsernamePasswordAuthenticationToken(owner, null, List.of()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).isEqualTo(image());
        assertThat(jdbc.sql("select count(*) from canvas_item").query(Long.class).single()).isZero();
        verify(upstream, times(1)).submit(any());
        verify(upstream, never()).reconcile(any());
    }

    @Test
    void completedCheckpointSurvivesLeaseExpiryAndIsExcludedFromNewSubmissionClaims() throws Exception {
        JsonNode batch = accept();
        UUID taskId = UUID.fromString(batch.at("/tasks/0/id").asText());
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.getAndSet(false))
                throw new IllegalStateException("Synthetic final task transaction failure");
            return invocation.callRealMethod();
        }).when(taskRows).finishSubmitting(any(), any(), any(), any());
        assertThatThrownBy(() -> worker.submitOnce("studio-archive-crash"))
                .isInstanceOf(IllegalStateException.class);
        assertState(taskId, "SUBMITTING");
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:id").param("id", taskId).update();
        assertThat(tasks.recoverExpiredSubmissions(10)).isEqualTo(1);
        assertState(taskId, "WAITING_PROVIDER");
        assertThat(worker.submitOnce("studio-archive-restart-submit")).isZero();
        due(taskId);
        assertThat(worker.pollOnce("studio-archive-restart-poll")).isEqualTo(1);
        assertState(taskId, "SUCCEEDED");
        verify(upstream, times(1)).submit(any());
        verify(upstream, never()).reconcile(any());
    }

    @Test
    void missingSavedBytesBlockArchivalWithoutAnyNewProviderCall() throws Exception {
        JsonNode batch = accept();
        UUID taskId = UUID.fromString(batch.at("/tasks/0/id").asText());
        doAnswer(invocation -> new Submission.Completed(
                new MediaPayload(new ByteArrayInputStream(new byte[0]), "image/png")))
                .when(upstream).submit(any());
        assertThatCode(() -> worker.submitOnce("studio-archive-missing-submit")).doesNotThrowAnyException();
        due(taskId);
        assertThat(worker.pollOnce("studio-archive-missing-resume")).isEqualTo(1);
        assertState(taskId, "BLOCKED");
        assertThat(
                jdbc.sql("select error_code from task where id=:id").param("id", taskId).query(String.class).single())
                .isEqualTo("STUDIO_IMAGE_ARCHIVE_MISSING");
        assertThat(worker.submitOnce("studio-archive-missing-no-submit")).isZero();
        verify(upstream, times(1)).submit(any());
        verify(upstream, never()).reconcile(any());
    }

    @Test
    void cancellationKeepsAConfirmedArchiveRecoverableAsLateHistory() throws Exception {
        JsonNode batch = accept();
        UUID taskId = UUID.fromString(batch.at("/tasks/0/id").asText());
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.getAndSet(false))
                throw new IllegalStateException("Synthetic metadata publication failure");
            return invocation.callRealMethod();
        }).when(assetRows).insert(any());
        assertThatCode(() -> worker.submitOnce("studio-archive-cancel-submit")).doesNotThrowAnyException();
        jdbc.sql("update task set cancel_requested=true,status='RUNNING',lease_owner='stale-archive-worker',"
                + "lease_until=now()-interval '1 second',next_action_at=now() where id=:id")
                .param("id", taskId).update();
        assertThat(tasks.recoverExpiredCancellations(10)).isZero();
        assertThat(worker.pollOnce("studio-archive-cancel-resume")).isEqualTo(1);
        assertState(taskId, "CANCELED");
        assertThat(jdbc.sql("select count(*) from task_late_result where task_id=:id").param("id", taskId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(batch(batch.path("id").asText()).at("/tasks/0/errorCode").isNull())
                .as("A canceled Studio result already archived in late history must stop reporting an archive retry")
                .isTrue();
        verify(upstream, times(1)).submit(any());
        verify(upstream, never()).reconcile(any());
    }

    @Test
    void checkpointFailureClosesTheOneShotResponseBeforeAnyArchiveIsPublished() throws Exception {
        JsonNode batch = accept();
        UUID taskId = UUID.fromString(batch.at("/tasks/0/id").asText());
        AtomicInteger closes = new AtomicInteger();
        byte[] bytes = image();
        doAnswer(invocation -> new Submission.Completed(new MediaPayload(new ByteArrayInputStream(bytes) {
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        }, "image/png"))).when(upstream).submit(any());
        doThrow(new IllegalStateException("Synthetic checkpoint write failure"))
                .when(taskRows).checkpointStudioImageResult(any(), any(), any(), any());
        assertThatThrownBy(() -> worker.submitOnce("studio-archive-checkpoint-failure"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Synthetic checkpoint write failure");
        assertThat(closes).hasValue(1);
        assertState(taskId, "SUBMITTING");
        UUID assetId = dev.agenvas.asset.application.AssetService.taskImageAssetId(taskId);
        assertThat(jdbc.sql("select count(*) from asset where id=:id").param("id", assetId)
                .query(Long.class).single()).isZero();
        verify(upstream, times(1)).submit(any());
        verify(upstream, never()).reconcile(any());
    }

    @Test
    void failedArchiveDeferralClosesTheResponseAndRecoversAfterLeaseExpiry() throws Exception {
        JsonNode batch = accept();
        UUID taskId = UUID.fromString(batch.at("/tasks/0/id").asText());
        AtomicInteger closes = new AtomicInteger();
        byte[] bytes = image();
        doAnswer(invocation -> new Submission.Completed(new MediaPayload(new ByteArrayInputStream(bytes) {
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        }, "image/png"))).when(upstream).submit(any());
        AtomicBoolean failPublication = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failPublication.getAndSet(false))
                throw new IllegalStateException("Synthetic metadata publication failure");
            return invocation.callRealMethod();
        }).when(assetRows).insert(any());
        doThrow(new IllegalStateException("Synthetic archive deferral failure"))
                .when(taskRows).deferStudioImageArchive(any(), any(), any(), any());
        assertThatThrownBy(() -> worker.submitOnce("studio-archive-deferral-failure"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Synthetic archive deferral failure");
        assertThat(closes).hasValue(1);
        assertState(taskId, "SUBMITTING");
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:id").param("id", taskId).update();
        assertThat(tasks.recoverExpiredSubmissions(10)).isEqualTo(1);
        due(taskId);
        assertThat(worker.pollOnce("studio-archive-deferral-resume")).isEqualTo(1);
        assertState(taskId, "SUCCEEDED");
        verify(upstream, times(1)).submit(any());
        verify(upstream, never()).reconcile(any());
    }

    private JsonNode accept() throws Exception {
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        JsonNode studio = mapper.readTree(mvc.perform(get("/api/v1/image-studio/context").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode capability = studio.path("capabilities").valueStream()
                .filter(item -> item.path("adapterId").asText().equals("MOCK_IMAGE")).findFirst().orElseThrow();
        var request = mapper.createObjectNode().put("capabilityId", capability.path("capabilityId").asText())
                .put("expectedCapabilityVersion", capability.path("capabilityVersion").asInt())
                .put("prompt", "Synthetic archive recovery image");
        request.putObject("parameters").put("generationCount", 1);
        request.putArray("referenceVersionIds");
        return mapper.readTree(mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content(request.toString()))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode batch(String id) throws Exception {
        return mapper.readTree(webAppContextSetup(context).apply(springSecurity()).build()
                .perform(get("/api/v1/image-studio/batches/" + id).with(authentication(
                        new UsernamePasswordAuthenticationToken(owner, null, List.of()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void due(UUID id) {
        jdbc.sql("update task set next_action_at=now()-interval '1 second' where id=:id").param("id", id).update();
    }

    private void assertState(UUID id, String state) {
        assertThat(jdbc.sql("select status from task where id=:id").param("id", id).query(String.class).single())
                .isEqualTo(state);
    }

    private static byte[] image() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
