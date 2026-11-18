package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.domain.*;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL with an explicitly compiled, in-memory synthetic protocol. No remote service or paid generation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, MediaAdapterExtensionPostgresIT.Extensions.class}, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false",
        "agenvas.library.worker-enabled=false", "agenvas.media-templates.sync-enabled=false"})
class MediaAdapterExtensionPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir
    static Path storage;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> storage.toString());
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @TestConfiguration
    static class Extensions {
        @Bean
        SyntheticAdapter syntheticAdapter() {
            return new SyntheticAdapter();
        }
    }
    @Autowired
    IdentityService identities;
    @Autowired
    ProjectService projects;
    @Autowired
    ArtifactService artifacts;
    @Autowired
    CanvasService canvas;
    @Autowired
    MediaDraftService drafts;
    @Autowired
    MediaCapabilityService capabilities;
    @Autowired
    DirectMediaTaskService direct;
    @Autowired
    TaskService tasks;
    @Autowired
    MediaExecutionWorker worker;
    @Autowired
    SyntheticAdapter adapter;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcClient jdbc;

    @Test
    void aNewCompiledAdapterPublishesExecutesAndRefreshesThroughTheExistingTaskKernel() {
        var owner = identities.setup("extension-admin", "synthetic-password-123").userId();
        var project = projects.create(owner, "Synthetic extension", Project.AspectRatio.SQUARE_1_1);
        var artifact = artifacts.create(owner, project.id(), Artifact.Kind.IMAGE, "Synthetic result", null).artifact();
        UUID card = UUID.randomUUID();
        canvas.apply(owner, project.id(), List.of(new CanvasService.PlaceArtifact(card, artifact.id(), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("280"), new BigDecimal("240"), 0, null, false)));
        var connection = capabilities.createConnection("synthetic-connection", "Synthetic", "MOCK", null, null);
        var capability = capabilities.publishCapability(connection.id(), "Synthetic image", "SYNTHETIC_IMAGE",
                mapper.createObjectNode());
        var draft = drafts.save(owner, project.id(), card, 0, "A synthetic tree", mapper.createObjectNode(), null,
                capability.id(), null, List.of(), List.of(), null);
        var task = direct.run(owner, project.id(), artifact.id(), card, draft.version(), "synthetic-request");
        assertThat(worker.submitOnce("extension-submit")).isEqualTo(1);
        assertThat(tasks.get(owner, project.id(), task.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        jdbc.sql("update task set next_action_at=now()-interval '1 second' where id=:id").param("id", task.id())
                .update();
        assertThat(worker.pollOnce("extension-poll")).isEqualTo(1);
        assertThat(tasks.get(owner, project.id(), task.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(tasks.providerResultManifest(task).orElseThrow().results().getFirst().url())
                .isEqualTo("http://synthetic.example.test/fresh");
        assertThat(adapter.submits).hasValue(1);
        assertThat(adapter.polls).hasValue(1);
        assertThat(adapter.refreshes).hasValue(1);
        assertThat(adapter.downloads).hasValue(2);
    }

    static class SyntheticAdapter implements MediaAdapter {
        final AtomicInteger submits = new AtomicInteger(), polls = new AtomicInteger(), refreshes = new AtomicInteger(),
                downloads = new AtomicInteger();
        public String adapterId() {
            return "SYNTHETIC_IMAGE";
        }
        public MediaAdapterRegistry.Declaration declaration() {
            return new MediaAdapterRegistry(List.of()).declaration("MOCK_IMAGE");
        }
        public Submission submit(AttemptContext context) {
            submits.incrementAndGet();
            return new Submission.Accepted("synthetic-original-id");
        }
        public Submission reconcile(AttemptContext context) {
            assertThat(context.originalRequestId()).isEqualTo("synthetic-original-id");
            polls.incrementAndGet();
            return new Submission.CompletedResults(manifest("expired"));
        }
        public PollFailure classifyPollFailure(RuntimeException failure) {
            return failure instanceof Expired
                    ? new PollFailure("SYNTHETIC_RESULT_EXPIRED", null, true)
                    : MediaAdapter.super.classifyPollFailure(failure);
        }
        public ProviderResultManifest refreshResults(AttemptContext context, ProviderResultManifest frozen) {
            assertThat(context.originalRequestId()).isEqualTo("synthetic-original-id");
            assertThat(context.requestKey()).isNull();
            refreshes.incrementAndGet();
            return manifest("fresh");
        }
        public MediaPayload downloadResult(AttemptContext context, ProviderResultManifest.Result result) {
            downloads.incrementAndGet();
            if (result.url().endsWith("expired"))
                throw new Expired();
            try {
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
                return new MediaPayload(new ByteArrayInputStream(bytes.toByteArray()), "image/png");
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
        private ProviderResultManifest manifest(String location) {
            return new ProviderResultManifest(ProviderResultManifest.SCHEMA_VERSION,
                    List.of(new ProviderResultManifest.Result(0, "synthetic-output",
                            RunningHubDefinition.OutputKind.IMAGE,
                            true, "http://synthetic.example.test/" + location)),
                    null);
        }
        private static final class Expired extends RuntimeException {
        }
    }
}
