package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.support.CanvasMediaFixture;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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

/** Paid, explicit opt-in test. The credential file is outside the repo and is never logged. */
@EnabledIfSystemProperty(named = "agenvas.autodl.real-test", matches = "true")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class AutoDlRealProviderPostgresIT {
    private static final int REFERENCE_PIXELS = 512;
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration DEADLINE = Duration.ofMinutes(3);
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaDraftService drafts;
    @Autowired CanvasService canvas;
    @Autowired DirectMediaTaskService direct;
    @Autowired MediaCapabilityService catalog;
    @Autowired MediaExecutionWorker worker;
    @Autowired TaskService tasks;
    @Autowired MediaToolRunner mediaTools;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @Test void actualWorkerSubmitsBase64PollsOriginalTaskAndArchivesVideoWithAudio() throws Exception {
        Path credential = Path.of(System.getProperty("agenvas.autodl.credential-file"));
        UUID owner = identities.setup("autodl-real-admin", "autodl-real-password-123").userId();
        var connection = catalog.createConnection("autodl-real-create", "AutoDL real temporary", "AUTODL", null,
                Files.readString(credential).trim());
        var capability = catalog.publishCapability(connection.id(), "H3 real 1 second", AutoDlWorkflows.ADAPTER_ID,
                mapper.createObjectNode().put("workflowId", "minimax_h3_z0903").put("videoResolution", "480p"));
        UUID project = projects.create(owner, "AutoDL real test", Project.AspectRatio.LANDSCAPE_16_9).id();
        BufferedImage picture = new BufferedImage(REFERENCE_PIXELS, REFERENCE_PIXELS, BufferedImage.TYPE_INT_RGB);
        var graphics = picture.createGraphics();
        try { graphics.setColor(new Color(60, 120, 190)); graphics.fillRect(0, 0, REFERENCE_PIXELS, REFERENCE_PIXELS); }
        finally { graphics.dispose(); }
        var png = new ByteArrayOutputStream(); ImageIO.write(picture, "png", png);
        var imageAsset = assets.archiveImage(owner, project, new ByteArrayInputStream(png.toByteArray()));
        var image = artifacts.create(owner, project, Artifact.Kind.IMAGE, "Blue reference", mapper.createObjectNode()
                .put("sourceType", "UPLOAD").put("assetId", imageAsset.id().toString()));
        Path wav = Files.createTempFile("agenvas-autodl-real-", ".wav");
        byte[] audioBytes;
        try {
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                    "sine=frequency=220:sample_rate=16000", "-t", "1", "-y", wav.toString()));
            audioBytes = Files.readAllBytes(wav);
        } finally { Files.deleteIfExists(wav); }
        var audioAsset = assets.archiveAudio(owner, project, new ByteArrayInputStream(audioBytes));
        var audio = artifacts.create(owner, project, Artifact.Kind.AUDIO, "Tone reference", mapper.createObjectNode()
                .put("sourceType", "UPLOAD").put("assetId", audioAsset.id().toString()));
        var target = artifacts.create(owner, project, Artifact.Kind.VIDEO, "AutoDL result", null).artifact();
        UUID card = CanvasMediaFixture.place(canvas, owner, project, target.id());
        var draft = drafts.save(owner, project, card, 0, "A calm blue abstract scene moving gently, with a soft musical tone.",
                mapper.createObjectNode(), 1, capability.id(), MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(image.resourceDefaultVersion().id(), MediaDraft.InputRole.REFERENCE, "#F15CAF"),
                        new MediaDraftService.SaveMediaInput(audio.resourceDefaultVersion().id(), MediaDraft.InputRole.AUDIO_REFERENCE, "#67C7F3")), List.of(), null);
        Task task = direct.run(owner, project, target.id(), card, draft.version(), "autodl-real-once");
        assertThat(worker.submitOnce("autodl-real-submit")).isEqualTo(1);
        Task accepted = tasks.get(owner, project, task.id());
        assertThat(accepted.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(accepted.providerRequestId()).isNotBlank();
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            jdbc.sql("update task set next_action_at=now() - interval '1 second' where id=:id").param("id", task.id()).update();
            worker.pollOnce("autodl-real-poll");
            Task latest = tasks.get(owner, project, task.id());
            if (latest.status() != Task.Status.WAITING_PROVIDER) break;
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        Task done = tasks.get(owner, project, task.id());
        assertThat(done.status()).as("Real AutoDL task status; code=%s", done.errorCode()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(done.providerRequestId()).isEqualTo(accepted.providerRequestId());
        UUID versionId = UUID.fromString(done.output().path("artifactVersionId").asText());
        var version = artifacts.requireVersion(owner, project, target.id(), versionId);
        var archived = assets.get(owner, project, UUID.fromString(version.content().path("assetId").asText()));
        assertThat(archived.asset().width()).isEqualTo(864);
        assertThat(archived.asset().height()).isEqualTo(480);
        assertThat(mediaTools.ffprobe(List.of("-v", "error", "-show_entries", "stream=codec_type", "-of", "json", archived.path().toString())))
                .contains("audio");
        assertThat(canvas.listMediaVersions(owner, project, card)).hasSize(1);
        assertThat(canvas.list(owner, project).stream().filter(entry -> entry.item().id().equals(card))
                .findFirst().orElseThrow().item().selectedVersionId()).isEqualTo(versionId);
        Files.copy(archived.path(), Path.of(System.getProperty("agenvas.autodl.result-file")), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("Real AutoDL backend success: taskId=%s bytes=%d durationMs=%d sha256=%s%n",
                done.providerRequestId(), archived.asset().byteSize(), archived.asset().durationMs(), archived.asset().sha256());
    }
}
