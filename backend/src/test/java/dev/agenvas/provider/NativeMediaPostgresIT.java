package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import dev.agenvas.artifact.application.*;
import dev.agenvas.artifact.domain.*;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.*;
import dev.agenvas.provider.domain.*;
import dev.agenvas.provider.infrastructure.*;
import dev.agenvas.task.application.*;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL/FFmpeg; upstream methods are synthetic, with no paid provider traffic. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class NativeMediaPostgresIT {
    @org.junit.jupiter.api.io.TempDir static Path assetRoot;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("agenvas.storage.root", () -> assetRoot.toString());
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired MediaDraftService drafts;
    @Autowired CanvasService canvas;
    @Autowired AssetService assets;
    @Autowired MediaCapabilityService catalog;
    @Autowired DirectMediaTaskService direct;
    @Autowired MediaExecutionWorker worker;
    @Autowired TaskService tasks;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MediaToolRunner mediaTools;
    @MockitoSpyBean SeedreamClient seedream;
    @MockitoSpyBean GoogleVideoClient google;
    @MockitoSpyBean WanClient wan;
    @MockitoSpyBean WanMediaDownloadPolicy wanDownloads;
    @org.springframework.test.context.bean.override.mockito.MockitoBean dev.agenvas.asset.storage.MediaRelayService relay;
    static UUID owner;
    @BeforeEach void initialize() {
        if (owner == null) owner = identities.setup("native-media-admin", "native-media-password-123").userId();
        // Every upstream boundary must be explicitly synthetic; fixture omissions cannot contact a real API.
        doThrow(new AssertionError("Unexpected synthetic Seedream create")).when(seedream).create(any(), any(), any(), any(), any(), any());
        doThrow(new AssertionError("Unexpected synthetic Google create")).when(google).create(any(), any(), any(), any());
        doThrow(new AssertionError("Unexpected synthetic Google query")).when(google).query(any(), any(), any(), any());
        doThrow(new AssertionError("Unexpected synthetic Wan create")).when(wan).create(any(), any(), any(), any());
        doThrow(new AssertionError("Unexpected synthetic Wan query")).when(wan).query(any(), any(), any(), any());
    }
    @AfterEach void isolatePendingFixtures() {
        // A test that only verifies acceptance leaves a durable pending task; do not let another test claim it.
        jdbc.sql("update task set next_action_at=now()+interval '1 day' where status='WAITING_PROVIDER'").update();
    }

    @Test void everyCurrentProfilePublishesWithDerivedInputsAndRejectsCredentialsInDefaults() {
        for (var p : NativeMediaProfiles.profiles()) {
            String platform = p.declaration().platform().name();
            var connection = catalog.createConnection("native-catalog-" + UUID.randomUUID(), platform, platform,
                    "WAN".equals(platform) ? "https://synthetic.cn-beijing.maas.aliyuncs.com" : null, "synthetic-key");
            var settings = mapper.createObjectNode().put("model", p.profile().model()).put("operation", p.profile().operation());
            var capability = catalog.publishCapability("native-cap-" + UUID.randomUUID(), connection.id(), "Synthetic profile", p.profile().adapterId(), settings);
            var snapshot = catalog.capabilitySnapshot(capability.id());
            assertThat(catalog.administratorSettings(snapshot).path("nativeInputs").size()).isEqualTo(p.inputs().size());
            settings.putObject("defaultParameters").put("apiKey", "must-be-rejected");
            assertThatThrownBy(() -> catalog.publishCapability("bad-" + UUID.randomUUID(), connection.id(), "Bad defaults", p.profile().adapterId(), settings)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> catalog.createConnection("bad-wan-" + UUID.randomUUID(), "Bad", "WAN", "https://attacker.invalid", "synthetic-key")).isInstanceOf(RuntimeException.class);
        var incompatible = catalog.createConnection("bad-google-" + UUID.randomUUID(), "Gateway", "GOOGLE", "https://gateway.invalid", "synthetic-key");
        assertThatThrownBy(() -> catalog.publishCapability("bad-google-cap-" + UUID.randomUUID(), incompatible.id(), "Video", "GOOGLE_VEO", mapper.createObjectNode())).isInstanceOf(RuntimeException.class);
    }

    @Test void synchronousGroupIsCheckpointedWithOriginalIdAndArchivesAllResultsOnce() throws Exception {
        Fixture f = fixture("ARK", "ARK_SEEDREAM_IMAGE", mapper.createObjectNode().put("model", SeedreamModels.LITE),
                mapper.createObjectNode().putObject("dynamicValues").put("sequential_image_generation", "auto").put("max_images", 3).put("output_format", "png"), null);
        var synthetic = images(3);
        var manifest = new ProviderResultManifest(1, synthetic.results().stream().map(r -> new ProviderResultManifest.Result(
                r.ordinal(), "seedream:GENERATE:" + SeedreamModels.LITE + ":" + r.ordinal(), r.kind(), r.primary(), r.url())).toList(), null);
        doReturn(new SeedreamClient.Created("seedream-original-request", manifest)).when(seedream).create(any(), any(), any(), any(), any(), any());
        Task accepted = accept(f);
        assertThat(accepted.input().path("expectedImageCount").isNull()).isTrue();
        assertThat(accepted.input().at("/mediaInput/providerParameters/native/model").asText()).isEqualTo(SeedreamModels.LITE);
        assertThat(worker.submitOnce("native-seedream-submit")).isEqualTo(1);
        var waiting = task(f, accepted.id());
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(waiting.providerRequestId()).isEqualTo("seedream-original-request");
        assertThat(jdbc.sql("select provider_result_manifest is not null from task where id=:id").param("id", accepted.id()).query(Boolean.class).single()).isTrue();
        due(accepted.id());
        assertThat(worker.pollOnce("native-seedream-resume")).isEqualTo(1);
        assertThat(task(f, accepted.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(jdbc.sql("select count(*) from task where project_id=:id").param("id", f.project()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:id").param("id", f.project()).query(Integer.class).single()).isEqualTo(3);
        assertThat(ledger(accepted.id()).path("imageCount").asInt()).isEqualTo(3);
        verify(seedream, times(1)).create(any(), any(), any(), any(), any(), any());
        assertThat(worker.pollOnce("native-seedream-again")).isZero();
    }

    @Test void responseLossKeepsUnknownAndCannotCreateAnotherPaidRequestAutomatically() {
        Fixture f = fixture("ARK", "ARK_SEEDREAM_IMAGE", mapper.createObjectNode(), mapper.createObjectNode(), null);
        doThrow(new SeedreamClient.Uncertain("PROVIDER_RESPONSE_LOST")).when(seedream).create(any(), any(), any(), any(), any(), any());
        Task accepted = accept(f);
        worker.submitOnce("native-seedream-lost");
        assertThat(task(f, accepted.id()).status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(worker.submitOnce("native-seedream-no-recreate")).isZero();
        assertThat(worker.pollOnce("native-seedream-no-query")).isZero();
        verify(seedream, times(1)).create(any(), any(), any(), any(), any(), any());
    }

    @Test void googlePollUsesOriginalIdAndCredentialVersionAfterConnectionEdit() throws Exception {
        Fixture f = fixture("GOOGLE", "GOOGLE_VEO", mapper.createObjectNode(), mapper.createObjectNode(), 4);
        String originalId = "models/veo-3.1-generate-preview/operations/" + "x".repeat(300);
        doReturn(originalId).when(google).create(any(), any(), any(), any());
        doReturn(new GoogleVideoClient.State("completed", new GoogleVideoClient.Video(video(), null)))
                .when(google).query(any(), any(), any(), any());
        Task accepted = accept(f);
        assertThat(accepted.input().at("/mediaInput/providerParameters/native/values/durationSeconds").asInt()).isEqualTo(4);
        worker.submitOnce("native-google-submit");
        var connection = catalog.getConnection(f.connection());
        catalog.updateConnection(connection.id(), connection.version(), connection.name(), true, null, "new-synthetic-key");
        due(accepted.id());
        worker.pollOnce("native-google-poll");
        assertThat(task(f, accepted.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        verify(google).query(any(), isNull(), eq("synthetic-original-key"), eq(originalId));
        assertThat(jdbc.sql("select provider_request_id from task where id=:id").param("id", accepted.id()).query(String.class).single()).isEqualTo(originalId);
        assertThat(jdbc.sql("select provider_request_id from provider_attempt where task_id=:id").param("id", accepted.id()).query(String.class).single()).isEqualTo(originalId);
        assertThat(jdbc.sql("select provider_request_id from call_log where task_id=:id and provider_request_id is not null limit 1").param("id", accepted.id()).query(String.class).single()).isEqualTo(originalId);
        verify(google, times(1)).create(any(), any(), any(), any());
        assertThat(new java.math.BigDecimal(ledger(accepted.id()).path("videoSeconds").asText())).isBetween(new java.math.BigDecimal("3.5"), new java.math.BigDecimal("4.5"));
    }

    @Test void invalidSynchronousResultsBlockArchivingWithoutResubmission() {
        Fixture f = fixture("ARK", "ARK_SEEDREAM_IMAGE", mapper.createObjectNode(), mapper.createObjectNode(), null);
        var manifest = new ProviderResultManifest(1, List.of(new ProviderResultManifest.Result(0, "invalid", RunningHubDefinition.OutputKind.IMAGE, true, "data:image/png;base64,bm90LWFuLWltYWdl")), null);
        doReturn(new SeedreamClient.Created("seedream-invalid-result", manifest)).when(seedream).create(any(), any(), any(), any(), any(), any());
        Task accepted = accept(f);
        worker.submitOnce("native-invalid-submit"); due(accepted.id()); worker.pollOnce("native-invalid-poll");
        assertThat(task(f, accepted.id()).status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(worker.submitOnce("native-invalid-no-create")).isZero();
        verify(seedream, times(1)).create(any(), any(), any(), any(), any(), any());
    }

    @Test void wanReferencesUsePinnedRelayAndSettleInputPlusActualOutputSeconds() throws Exception {
        var settings = mapper.createObjectNode();
        settings.putObject("defaultParameters").putObject("dynamicValues").put("resolution", "720P");
        Fixture f = fixture("WAN", "WAN_VIDEO", settings, mapper.createObjectNode(), 4);
        byte[] bytes = video();
        var asset = assets.archiveVideo(owner, f.project(), new java.io.ByteArrayInputStream(bytes));
        var source = artifacts.create(owner, f.project(), Artifact.Kind.VIDEO, "Synthetic reference video",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString()));
        var parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("video_1", source.resourceDefaultVersion().id().toString());
        long version = drafts.save(owner, f.project(), f.card(), f.version(), "Synthetic scene", parameters, 4,
                drafts.get(owner, f.project(), f.card()).capabilityId(), MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(source.resourceDefaultVersion().id(), MediaDraft.InputRole.VIDEO_REFERENCE, "#7C3AED")), List.of(), null).version();
        f = new Fixture(f.project(), f.artifact(), f.card(), version, f.connection());
        UUID relayProfile = UUID.randomUUID();
        when(relay.pinProfile(any())).thenReturn(relayProfile);
        when(relay.signedMedia(any(), any(), any(), eq(relayProfile), eq(dev.agenvas.asset.domain.Asset.MediaKind.VIDEO))).thenReturn("https://reference.example.invalid/frozen.mp4");
        doReturn("wan-original-request").when(wan).create(any(), any(), any(), any());
        doReturn(new WanClient.State("SUCCEEDED", List.of(new WanClient.Output("video", "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/synthetic.mp4")), mapper.createObjectNode()))
                .when(wan).query(any(), any(), any(), any());
        doAnswer(invocation -> { java.io.OutputStream output = invocation.getArgument(1); output.write(bytes); return "video/mp4"; }).when(wanDownloads).download(any(), any(), eq(true));
        Task accepted = accept(f);
        var inputSeconds = java.math.BigDecimal.valueOf(asset.durationMs(), 3);
        assertThat(accepted.input().path("nativeBillableInputSeconds").decimalValue()).isEqualByComparingTo(inputSeconds);
        assertThat(accepted.input().at("/mediaInput/videos/0/relayProfileId").asText()).isEqualTo(relayProfile.toString());
        assertThat(accepted.input().at("/mediaInput/providerParameters/native/values/resolution").asText()).isEqualTo("720P");
        worker.submitOnce("native-wan-submit"); due(accepted.id()); worker.pollOnce("native-wan-poll");
        assertThat(task(f, accepted.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(new java.math.BigDecimal(ledger(accepted.id()).path("videoSeconds").asText())).isEqualByComparingTo(inputSeconds.multiply(java.math.BigDecimal.valueOf(2)));
        var request = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        verify(wan, times(1)).create(any(), any(), any(), request.capture());
        assertThat(request.getValue().at("/parameters/resolution").asText()).isEqualTo("720P");
        verify(wan).query(any(), any(), eq("synthetic-original-key"), eq("wan-original-request"));
        verify(relay).signedMedia(owner, f.project(), asset.id(), relayProfile, dev.agenvas.asset.domain.Asset.MediaKind.VIDEO);
    }

    @Test void seventeenLayerResultsAndMetadataCommitTogetherAndCancellationKeepsHistoryUnselected() throws Exception {
        Fixture f = fixture("ARK", "ARK_SEEDREAM_IMAGE", mapper.createObjectNode().put("operation", "LAYER_DECOMPOSITION"), mapper.createObjectNode(), null);
        var base = images(1).results().getFirst();
        byte[] bytes = SeedreamClient.inlineBytes(base.url());
        var asset = assets.archiveImage(owner, f.project(), new java.io.ByteArrayInputStream(bytes));
        var reference = artifacts.create(owner, f.project(), Artifact.Kind.IMAGE, "Synthetic layer reference", mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString()));
        var values = mapper.createObjectNode(); values.putObject("dynamicValues").put("image1", reference.resourceDefaultVersion().id().toString()).put("output_format", "png");
        long version = drafts.save(owner, f.project(), f.card(), f.version(), "", values, null, drafts.get(owner, f.project(), f.card()).capabilityId(), null,
                List.of(new MediaDraftService.SaveMediaInput(reference.resourceDefaultVersion().id(), MediaDraft.InputRole.REFERENCE, "#7C3AED")), List.of(), null).version();
        f = new Fixture(f.project(), f.artifact(), f.card(), version, f.connection());
        var results = new ArrayList<ProviderResultManifest.Result>();
        for (int i = 0; i < 17; i++) {
            ObjectNode metadata = mapper.createObjectNode().put("z_index", i).put("name", "Synthetic layer " + i);
            if (i > 0) { var box = metadata.putObject("bounding_box"); box.putArray("absolute").add(0).add(0).add(2048).add(2048); box.putArray("normalized").add(0).add(0).add(1000).add(1000); }
            results.add(new ProviderResultManifest.Result(i, "seedream:LAYER_DECOMPOSITION:" + SeedreamModels.PRO + ":" + i, RunningHubDefinition.OutputKind.IMAGE, i == 0, base.url(), null, metadata));
        }
        doReturn(new SeedreamClient.Created("seedream-layers-original", new ProviderResultManifest(1, results, null))).when(seedream).create(any(), any(), any(), any(), any(), any());
        Task accepted = accept(f); worker.submitOnce("native-layers-submit");
        // Persisted cancellation intent stands in for a canceled approved media task; direct post-submit UI has no cancel endpoint.
        jdbc.sql("update task set cancel_requested=true where id=:id").param("id", accepted.id()).update();
        due(accepted.id()); worker.pollOnce("native-layers-poll");
        assertThat(task(f, accepted.id()).status()).isEqualTo(Task.Status.CANCELED);
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:id").param("id", f.project()).query(Integer.class).single()).isEqualTo(17);
        UUID primaryCard = f.card();
        assertThat(canvas.list(owner, f.project()).stream().filter(c -> c.item().id().equals(primaryCard)).findFirst().orElseThrow().item().selectedVersionId()).isNull();
        assertThat(jdbc.sql("select count(*) from artifact_version where project_id=:id and content_json->'parameters'->'providerLayer'->'bounding_box' is not null").param("id", f.project()).query(Integer.class).single()).isEqualTo(16);
        verify(seedream, times(1)).create(any(), any(), any(), any(), any(), any());
    }

    @Test void theSameExactAuthorizedImageCanSupplyBothGoogleFramesWithoutDuplicatingFrozenInputs() throws Exception {
        Fixture initial = fixture("GOOGLE", "GOOGLE_VEO", mapper.createObjectNode(), mapper.createObjectNode(), 8);
        byte[] bytes = SeedreamClient.inlineBytes(images(1).results().getFirst().url());
        var asset = assets.archiveImage(owner, initial.project(), new java.io.ByteArrayInputStream(bytes));
        var source = artifacts.create(owner, initial.project(), Artifact.Kind.IMAGE, "Synthetic frame",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString()));
        var parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("first_frame", source.resourceDefaultVersion().id().toString())
                .put("last_frame", source.resourceDefaultVersion().id().toString());
        long version = drafts.save(owner, initial.project(), initial.card(), initial.version(), "Synthetic loop", parameters, 8,
                drafts.get(owner, initial.project(), initial.card()).capabilityId(), MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(source.resourceDefaultVersion().id(), MediaDraft.InputRole.REFERENCE, "#7C3AED")), List.of(), null).version();
        Fixture f = new Fixture(initial.project(), initial.artifact(), initial.card(), version, initial.connection());
        doReturn("operations/synthetic-loop").when(google).create(any(), any(), any(), any());
        Task accepted = accept(f);
        assertThat(accepted.input().at("/mediaInput/images").size()).isEqualTo(1);
        worker.submitOnce("native-google-loop");
        assertThat(task(f, accepted.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        String original = Base64.getEncoder().encodeToString(bytes);
        verify(google).create(any(), any(), any(), argThat(body -> original.equals(body.at("/instances/0/image/bytesBase64Encoded").asText())
                && original.equals(body.at("/instances/0/lastFrame/bytesBase64Encoded").asText())));
    }

    @Test void copiedGoogleFilesMetadataCannotTurnAUserUploadIntoAnExtendableVeoResult() throws Exception {
        Fixture initial = fixture("GOOGLE", "GOOGLE_VEO", mapper.createObjectNode().put("operation", "EXTEND"), mapper.createObjectNode(), 8);
        var asset = assets.archiveVideo(owner, initial.project(), new java.io.ByteArrayInputStream(video()));
        // Ordinary artifact writes accept generated-shaped content but cannot author TASK provenance.
        var content = mapper.createObjectNode().put("assetId", asset.id().toString()).put("prompt", "Synthetic source")
                .put("workflowVersion", "GOOGLE_VEO").put("sourceTaskId", UUID.randomUUID().toString());
        content.putObject("parameters").putObject("nativeProviderResult").put("adapterId", "GOOGLE_VEO")
                .put("model", GoogleVideoModels.VEO_MODELS.getFirst()).put("connectionId", initial.connection().toString())
                .put("connectionVersion", 1).put("fileName", "files/synthetic-forged");
        var source = artifacts.create(owner, initial.project(), Artifact.Kind.VIDEO, "Synthetic forged source", content);
        var parameters = mapper.createObjectNode(); parameters.putObject("dynamicValues").put("base_video", source.resourceDefaultVersion().id().toString());
        long version = drafts.save(owner, initial.project(), initial.card(), initial.version(), "Synthetic extension", parameters, 8,
                drafts.get(owner, initial.project(), initial.card()).capabilityId(), MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(source.resourceDefaultVersion().id(), MediaDraft.InputRole.VIDEO_REFERENCE, "#7C3AED")), List.of(), null).version();
        Fixture f = new Fixture(initial.project(), initial.artifact(), initial.card(), version, initial.connection());
        assertThatThrownBy(() -> accept(f)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("select count(*) from task where project_id=:id").param("id", f.project()).query(Integer.class).single()).isZero();
        verify(google, never()).create(any(), any(), any(), any());
    }

    private Fixture fixture(String platform, String adapter, ObjectNode settings, ObjectNode values, Integer duration) {
        // putObject returns its child; wrap supplied dynamic values into the draft contract.
        ObjectNode parameters = values.has("dynamicValues") || values.isEmpty() ? values : mapper.createObjectNode().set("dynamicValues", values);
        var connection = catalog.createConnection("native-connection-" + UUID.randomUUID(), "Synthetic connection", platform,
                "WAN".equals(platform) ? "https://synthetic.cn-beijing.maas.aliyuncs.com" : null, "synthetic-original-key");
        var capability = catalog.publishCapability("native-capability-" + UUID.randomUUID(), connection.id(), "Synthetic capability", adapter, settings);
        var kind = catalog.inputPolicy(catalog.forDraft(capability.id(), "ARK_SEEDREAM_IMAGE".equals(adapter) ? Task.Kind.IMAGE_GENERATION : Task.Kind.VIDEO_GENERATION)).kind();
        var project = projects.create(owner, "Native synthetic project", Project.AspectRatio.LANDSCAPE_16_9);
        var artifact = artifacts.create(owner, project.id(), Artifact.Kind.valueOf(kind.name().replace("_GENERATION", "")), "Native target", null);
        UUID card = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner, project.id(), artifact.artifact().id());
        long version = drafts.save(owner, project.id(), card, 0, "Synthetic scene", parameters, duration, capability.id(),
                kind == Task.Kind.VIDEO_GENERATION ? MediaDraft.VideoInputMode.TEXT : null, List.of(), List.of(), null).version();
        return new Fixture(project.id(), artifact.artifact().id(), card, version, connection.id());
    }
    private Task accept(Fixture f) { return direct.run(owner, f.project(), f.artifact(), f.card(), f.version(), "native-command-" + UUID.randomUUID()); }
    private Task task(Fixture f, UUID id) { return tasks.get(owner, f.project(), id); }
    private void due(UUID id) { jdbc.sql("update task set next_action_at=now()-interval '1 second' where id=:id").param("id", id).update(); }
    private JsonNode ledger(UUID task) { return mapper.readTree(jdbc.sql("select quantity_json::text from usage_ledger where task_id=:id and entry_type='SETTLEMENT'").param("id", task).query(String.class).single()); }
    private ProviderResultManifest images(int count) throws Exception {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var results = new ArrayList<ProviderResultManifest.Result>();
        for (int i = 0; i < count; i++) results.add(new ProviderResultManifest.Result(i, "seedream:GENERATE:" + SeedreamModels.PRO + ":" + i, RunningHubDefinition.OutputKind.IMAGE, i == 0,
                "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray())));
        return new ProviderResultManifest(1, results, null);
    }
    private byte[] video() throws Exception {
        Path file = Files.createTempFile("native-synthetic-", ".mp4");
        try {
            mediaTools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=1280x720:r=24", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "-y", file.toString()));
            return Files.readAllBytes(file);
        } finally { Files.deleteIfExists(file); }
    }
    private record Fixture(UUID project, UUID artifact, UUID card, long version, UUID connection) {}
}
