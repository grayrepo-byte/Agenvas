package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

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
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.task.application.DirectMediaTaskService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL/decoding, local Mock and intercepted Seed transport; no paid provider calls. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=audio-media-integration-test-secret",
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.comfyui.scheduler-enabled=false",
        "agenvas.provider.mock.scheduler-enabled=false", "agenvas.provider.mock.video-scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class AudioMediaPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("agenvas.storage.root", () -> storage.toString());
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired CanvasService canvas;
    @Autowired MediaDraftService drafts;
    @Autowired DirectMediaTaskService direct;
    @Autowired dev.agenvas.task.application.TaskService taskService;
    @Autowired dev.agenvas.task.application.ManualUnknownRetryService manualRetry;
    @Autowired MediaExecutionWorker worker;
    @Autowired dev.agenvas.provider.application.MediaCapabilityService catalog;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    dev.agenvas.provider.infrastructure.SeedAudioClient seedClient;
    @Autowired AssetService assets;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    dev.agenvas.asset.application.AssetRepository assetRepository;
    @Autowired MediaToolRunner tools;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;

    @Test void audioUploadRegenerationAndMixedVideoReferencesStayImmutableAndAuthorized() throws Exception {
        var owner = identities.setup("audio-media-integration-test-secret", "audio-admin", "audio-password-123");
        UUID ownerId = owner.userId();
        UUID projectId = projects.create(ownerId, "Audio", Project.AspectRatio.LANDSCAPE_16_9).id();
        var audio = artifacts.create(ownerId, projectId, Artifact.Kind.AUDIO, "Audio", null).artifact();
        UUID audioCard = CanvasMediaFixture.place(canvas, ownerId, projectId, audio.id());
        var firstDraft = drafts.save(ownerId, projectId, audioCard, 0, "Hello", mapper.createObjectNode(),
                null, null, null, List.of(), List.of());
        var firstTask = direct.run(ownerId, projectId, audio.id(), audioCard, firstDraft.version(), "audio-first");
        assertThat(firstTask.kind().name()).isEqualTo("AUDIO_GENERATION");
        assertThat(worker.submitOnce("audio-test")).isEqualTo(1);
        var firstVersion = canvas.listMediaVersions(ownerId, projectId, audioCard).getFirst();
        UUID firstVersionId = firstVersion.id();
        UUID firstAssetId = UUID.fromString(firstVersion.content().path("assetId").asText());
        assertThat(assets.get(ownerId, projectId, firstAssetId).asset().contentType()).isEqualTo("audio/wav");
        assertThat(firstVersion.content().at("/parameters/mock").asBoolean()).isTrue();
        assertThat(canvas.list(ownerId, projectId).getFirst().item().selectedVersionId()).isEqualTo(firstVersionId);
        var saved = drafts.get(ownerId, projectId, audioCard);
        saved = drafts.save(ownerId, projectId, audioCard, saved.version(), "Second", mapper.createObjectNode(),
                null, null, null, List.of(), List.of());
        direct.run(ownerId, projectId, audio.id(), audioCard, saved.version(), "audio-second");
        drafts.save(ownerId, projectId, audioCard, drafts.get(ownerId, projectId, audioCard).version(), "User edited while queued", mapper.createObjectNode(),
                null, null, null, List.of(), List.of());
        assertThat(worker.submitOnce("audio-test")).isEqualTo(1);
        assertThat(canvas.listMediaVersions(ownerId, projectId, audioCard)).hasSize(2);
        assertThat(canvas.list(ownerId, projectId).getFirst().item().selectedVersionId()).isEqualTo(firstVersionId);
        assertThat(assets.get(ownerId, projectId, firstAssetId).asset().sha256()).isNotBlank();

        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        var configured = mapper.readTree(mvc.perform(get("/api/v1/settings/media-connections").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(configured.path("defaults")).anySatisfy(value -> {
            assertThat(value.path("kind").asText()).isEqualTo("AUDIO_GENERATION");
            assertThat(value.path("capabilityId").asText()).isNotBlank();
        });
        String base = "/api/v1/projects/" + projectId + "/assets";
        byte[] wav = Files.readAllBytes(assets.get(ownerId, projectId, firstAssetId).path());
        var uploaded = mapper.readTree(mvc.perform(multipart(base + "/audio").file(new MockMultipartFile(
                        "file", "pretend.jpg", "image/jpeg", wav)).with(auth).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(uploaded.path("mediaKind").asText()).isEqualTo("AUDIO");
        assertThat(uploaded.path("contentType").asText()).isEqualTo("audio/wav");
        mvc.perform(multipart(base + "/audio").file(new MockMultipartFile("file", "bad.mp3", "audio/mpeg", new byte[]{1,2,3}))
                .with(auth).with(csrf())).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(base + "/" + firstAssetId + "/content").with(auth).header("Range", "bytes=0-43"))
                .andExpect(status().isPartialContent());
        UUID otherProject = projects.create(ownerId, "Other", Project.AspectRatio.LANDSCAPE_16_9).id();
        mvc.perform(get("/api/v1/projects/" + otherProject + "/assets/" + firstAssetId).with(auth))
                .andExpect(status().isNotFound());

        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB), "png", png);
        var imageAsset = assets.archiveImage(ownerId, projectId, new ByteArrayInputStream(png.toByteArray()));
        var image = artifacts.create(ownerId, projectId, Artifact.Kind.IMAGE, "Image", mapper.createObjectNode()
                .put("sourceType", "UPLOAD").put("assetId", imageAsset.id().toString()));
        UUID imageVersion = image.resourceDefaultVersion().id();
        var video = artifacts.create(ownerId, projectId, Artifact.Kind.VIDEO, "MV", null).artifact();
        UUID videoCard = CanvasMediaFixture.place(canvas, ownerId, projectId, video.id());
        var references = List.of(new MediaDraftService.SaveMediaInput(firstVersionId, MediaDraft.InputRole.AUDIO_REFERENCE, "#67C7F3"),
                new MediaDraftService.SaveMediaInput(imageVersion, MediaDraft.InputRole.REFERENCE, "#F15CAF"));
        var videoDraft = drafts.save(ownerId, projectId, videoCard, 0, "\uFFFC and \uFFFC", mapper.createObjectNode(),
                2, null, MediaDraft.VideoInputMode.GENERAL_REFERENCE, references,
                List.of(new MediaDraft.PromptMention(firstVersionId, MediaDraft.InputRole.AUDIO_REFERENCE),
                        new MediaDraft.PromptMention(imageVersion, MediaDraft.InputRole.REFERENCE)));
        var videoTask = direct.run(ownerId, projectId, video.id(), videoCard, videoDraft.version(), "mixed-reference-video");
        assertThat(videoTask.input().path("prompt").asText()).isEqualTo("@Audio 1 and @Image 1");
        assertThat(videoTask.input().at("/mediaInput/images/0/order").asInt()).isZero();
        assertThat(videoTask.input().at("/mediaInput/audios/0/order").asInt()).isZero();
        assertThat(worker.submitOnce("audio-test")).isEqualTo(1);
        var videoVersion = canvas.listMediaVersions(ownerId, projectId, videoCard).getFirst();
        assertThat(videoVersion.inputReferences()).anySatisfy(reference -> {
            assertThat(reference.expectedKind()).isEqualTo(Artifact.Kind.AUDIO);
            assertThat(reference.versionId()).isEqualTo(firstVersionId);
        });
        UUID videoAssetId = UUID.fromString(videoVersion.content().path("assetId").asText());
        var file = assets.get(ownerId, projectId, videoAssetId);
        var streams = mapper.readTree(tools.ffprobe(List.of("-v", "error", "-show_entries", "stream=codec_type", "-of", "json", file.path().toString())));
        assertThat(streams.path("streams")).anySatisfy(stream -> assertThat(stream.path("codec_type").asText()).isEqualTo("audio"));
        var restored = drafts.restoreVersionInputs(ownerId, projectId, videoCard, videoVersion.id(), drafts.get(ownerId, projectId, videoCard).version());
        assertThat(restored.mediaInputs()).anySatisfy(input -> assertThat(input.role()).isEqualTo(MediaDraft.InputRole.AUDIO_REFERENCE));
        UUID archiveTaskId = UUID.randomUUID();
        var synthesisCalls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doThrow(new IllegalStateException("Injected metadata failure"))
                .doCallRealMethod().when(assetRepository).insert(org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> assets.archiveTaskAudio(ownerId, projectId, archiveTaskId, () -> {
            synthesisCalls.incrementAndGet(); return new ByteArrayInputStream(wav);
        })).isInstanceOf(IllegalStateException.class);
        var recoveredAudio = assets.archiveTaskAudio(ownerId, projectId, archiveTaskId, () -> {
            synthesisCalls.incrementAndGet(); throw new AssertionError("Must not synthesize again");
        });
        assertThat(synthesisCalls).hasValue(1);
        assertThat(assets.recoverTaskAudio(ownerId, projectId, archiveTaskId)).contains(recoveredAudio);
        assertThat(recoveredAudio.sha256()).isEqualTo(assets.get(ownerId, projectId, firstAssetId).asset().sha256());

        var currentAudio = drafts.get(ownerId, projectId, audioCard);
        drafts.save(ownerId, projectId, audioCard, currentAudio.version(), "Mixed invalid", mapper.createObjectNode().put("speaker", "zh_female_vv_uranus_bigtts"),
                null, null, null, List.of(references.get(1)), List.of());
        assertThatThrownBy(() -> direct.run(ownerId, projectId, audio.id(), audioCard,
                drafts.get(ownerId, projectId, audioCard).version(), "invalid-mix"))
                .isInstanceOf(ApiProblemException.class);
        var unknownDraft = drafts.save(ownerId, projectId, audioCard, drafts.get(ownerId, projectId, audioCard).version(),
                "Unknown demo", mapper.createObjectNode(), null, null, null, List.of(), List.of());
        var uncertain = direct.run(ownerId, projectId, audio.id(), audioCard, unknownDraft.version(), "uncertain-audio");
        var lease = taskService.claimBoundMedia("audio-unknown-test", 1).getFirst();
        assertThat(lease.id()).isEqualTo(uncertain.id());
        taskService.beginSubmission(lease, "audio-unknown-test");
        assertThat(taskService.markSubmissionUnknown(lease, "audio-unknown-test", "SEED_AUDIO_RESULT_UNKNOWN")).isTrue();
        assertThat(worker.submitOnce("no-automatic-retry")).isZero();
        var unknown = taskService.get(ownerId, projectId, uncertain.id());
        var replacement = manualRetry.create(ownerId, projectId, unknown.id(), unknown.version(), "explicit-audio-retry");
        assertThat(replacement.id()).isNotEqualTo(unknown.id());
        assertThat(replacement.attemptNo()).isEqualTo(2);
        assertThat(replacement.input()).isEqualTo(unknown.input());
        assertThat(manualRetry.create(ownerId, projectId, unknown.id(), unknown.version(), "explicit-audio-retry").id())
                .isEqualTo(replacement.id());
        assertThat(worker.submitOnce("explicit-audio-retry")).isEqualTo(1);
        assertThat(taskService.get(ownerId, projectId, unknown.id()).status()).isEqualTo(dev.agenvas.task.domain.Task.Status.UNKNOWN);
        assertThat(canvas.listMediaVersions(ownerId, projectId, audioCard)).hasSize(3);
        var cancelDraft = drafts.save(ownerId, projectId, audioCard, drafts.get(ownerId, projectId, audioCard).version(),
                "Cancel demo", mapper.createObjectNode(), null, null, null, List.of(), List.of());
        var queued = direct.run(ownerId, projectId, audio.id(), audioCard, cancelDraft.version(), "cancel-audio");
        assertThat(direct.cancelQueued(ownerId, projectId, queued.id()).status()).isEqualTo(dev.agenvas.task.domain.Task.Status.CANCELED);
        assertThat(worker.submitOnce("canceled-audio")).isZero();

        // Exercise the real adapter/catalog/cipher/task/archive seam with an intercepted transport.
        // Protocol headers/body/redirects are independently checked by SeedAudioClientTest.
        var seedConnection = catalog.createConnection("audio-seed-test", "Seed fake transport", "VOLCENGINE", null, "fake-seed-key");
        var seedCapability = catalog.publishCapability(seedConnection.id(), "Seed Audio 1.0", "VOLC_SEED_AUDIO_1",
                mapper.createObjectNode());
        var seedArtifact = artifacts.create(ownerId, projectId, Artifact.Kind.AUDIO, "Seed output", null).artifact();
        UUID seedCard = CanvasMediaFixture.place(canvas, ownerId, projectId, seedArtifact.id());
        var seedParameters = mapper.createObjectNode().put("speaker", "zh_female_vv_uranus_bigtts")
                .put("speechRate", -20).put("loudnessRate", 15).put("pitchRate", 3);
        var seedDraft = drafts.save(ownerId, projectId, seedCard, 0, "参考 \uFFFC 说你好", seedParameters,
                null, seedCapability.id(), null, List.of(references.getFirst()),
                List.of(new MediaDraft.PromptMention(firstVersionId, MediaDraft.InputRole.AUDIO_REFERENCE)));
        org.mockito.Mockito.doReturn(wav).when(seedClient).synthesize(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        var seedTask = direct.run(ownerId, projectId, seedArtifact.id(), seedCard, seedDraft.version(), "seed-output");
        assertThat(worker.submitOnce("seed-output-test")).isEqualTo(1);
        assertThat(taskService.get(ownerId, projectId, seedTask.id()).status()).isEqualTo(dev.agenvas.task.domain.Task.Status.SUCCEEDED);
        org.mockito.Mockito.verify(seedClient).synthesize(org.mockito.ArgumentMatchers.eq("fake-seed-key"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("参考 @音频1 说你好"),
                org.mockito.ArgumentMatchers.eq(new dev.agenvas.artifact.domain.AudioGenerationParameters("zh_female_vv_uranus_bigtts", -20, 15, 3)),
                org.mockito.ArgumentMatchers.argThat(inputs -> inputs.size() == 1 && inputs.getFirst().field().equals("audio_data")
                        && java.util.Arrays.equals(inputs.getFirst().bytes(), wav)));
        var seedVersion = canvas.listMediaVersions(ownerId, projectId, seedCard).getFirst();
        assertThat(seedVersion.content().at("/parameters/mock").asBoolean()).isFalse();
        assertThat(seedVersion.inputReferences()).anySatisfy(reference -> assertThat(reference.versionId()).isEqualTo(firstVersionId));
        org.mockito.Mockito.doThrow(new dev.agenvas.provider.infrastructure.SeedAudioClient.Uncertain()).when(seedClient)
                .synthesize(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        var unknownSeed = direct.run(ownerId, projectId, seedArtifact.id(), seedCard, seedDraft.version(), "seed-unknown");
        assertThat(worker.submitOnce("seed-unknown-test")).isEqualTo(1);
        assertThat(taskService.get(ownerId, projectId, unknownSeed.id()).status()).isEqualTo(dev.agenvas.task.domain.Task.Status.UNKNOWN);
        assertThat(worker.submitOnce("seed-no-automatic-repeat")).isZero();
        org.mockito.Mockito.verify(seedClient, org.mockito.Mockito.times(2)).synthesize(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());

    }
}
