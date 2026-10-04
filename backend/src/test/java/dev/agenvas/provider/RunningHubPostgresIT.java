package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import tools.jackson.databind.node.ObjectNode;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Real PostgreSQL, production adapters/archives and a local fake RunningHub. No real provider calls. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = { "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class RunningHubPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static java.nio.file.Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("agenvas.storage.root", () -> storage.toString());
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired MediaDraftService drafts;
    @Autowired CanvasService canvas;
    @Autowired AssetService assets;
    @Autowired MediaCapabilityService catalog;
    @Autowired MediaAdapterRegistry adapters;
    @Autowired MediaExecutionWorker worker;
    @Autowired DirectMediaTaskService direct;
    @Autowired TaskService tasks;
    @Autowired UsageService usage;
    @Autowired CallLogService callLogs;
    @Autowired MediaToolRunner mediaTools;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext webContext;
    private static UUID owner;
    @BeforeEach void owner() { if (owner == null) owner = identities.setup("rh-admin", "runninghub-password-123").userId(); }

    @Test void archivesMultipleMediaOutputsReadsCompanionZipResumesFromManifestAndPreservesANewerDraft() throws Exception {
        try (var provider = new Fake(2, true, false)) {
            var fixture = fixture(provider, Artifact.Kind.IMAGE, "WORKFLOW", false);
            Task task = accept(fixture, "");
            assertThat(worker.submitOnce("rh-submit")).isEqualTo(1);
            assertThat(provider.submissionPath.get()).isEqualTo("/openapi/v2/run/workflow/123");
            var previous = catalog.capabilitySnapshot(fixture.capability);
            ObjectNode replacement = (ObjectNode) mapper.readTree(previous.specJson()).path("settings").deepCopy();
            ((ObjectNode) replacement.path("runningHub")).put("targetId", "456");
            catalog.updateCapability(previous.connection().id(), fixture.capability, previous.capability().version(),
                    "Changed after acceptance", true, "RUNNINGHUB_IMAGE", replacement);
            assertThat(tasks.get(owner, fixture.project.id(), task.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
            due(task.id()); worker.pollOnce("rh-poll");
            assertThat(tasks.get(owner, fixture.project.id(), task.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
            assertThat(jdbc.sql("select provider_result_manifest is not null from task where id=:id").param("id", task.id()).query(Boolean.class).single()).isTrue();
            var draft = drafts.get(owner, fixture.project.id(), fixture.card);
            drafts.save(owner, fixture.project.id(), fixture.card, draft.version(), "new local draft", draft.parameters(), null,
                    fixture.capability, null, List.of(), List.of(), null);
            // A fresh worker has no in-memory result state, and must not re-query/re-submit or re-download the ready first asset.
            var restarted = new MediaExecutionWorker(tasks, catalog, adapters, assets, mapper, callLogs);
            due(task.id()); restarted.pollOnce("rh-restarted");
            var completed = tasks.get(owner, fixture.project.id(), task.id());
            assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(provider.submits).hasValue(1); assertThat(provider.queries).hasValue(1);
            assertThat(provider.firstDownloads).hasValue(1); assertThat(provider.secondDownloads).hasValue(2);
            assertThat(provider.zipDownloads).hasValue(1);
            assertThat(completed.output().path("additionalResults")).hasSize(1);
            assertThat(completed.output().path("selected").asBoolean()).isFalse();
            assertThat(drafts.get(owner, fixture.project.id(), fixture.card).prompt()).isEqualTo("new local draft");
            assertThat(canvas.list(owner, fixture.project.id())).hasSize(2);
            assertThat(mapper.writeValueAsString(completed)).doesNotContain(provider.origin(), "fake-runninghub-key");
            assertThat(restarted.pollOnce("rh-repeat")).isZero();
            assertThat(canvas.list(owner, fixture.project.id())).hasSize(2);
            assertThat(completed.output().path("providerUsage").path("consumeMoney").isNull()).isTrue();
        }
    }

    @Test void zipOnlyOutputsBecomeResourcesAndResumeWithoutQueryingOrSubmittingAgain() throws Exception {
        try (var provider = new Fake(2, false, false)) {
            provider.zipOnly = true;
            provider.failZipArchiveOnce = true;
            provider.zipBytes = zip(new String[] { "output/图片一.png", "output/图片二.png" }, new byte[][] { provider.imageBytes, provider.imageBytes });
            var fixture = fixture(provider, Artifact.Kind.IMAGE, "WORKFLOW", false);
            Task task = accept(fixture, "ZIP results"); worker.submitOnce("rh-zip-submit");
            due(task.id()); worker.pollOnce("rh-zip-first");
            assertThat(tasks.get(owner, fixture.project.id(), task.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
            var manifest = tasks.providerResultManifest(task).orElseThrow();
            assertThat(manifest.results()).hasSize(2);
            assertThat(manifest.results().getFirst().archiveEntry().name()).isEqualTo("output/图片一.png");
            // Model a crash after the first member has already been published as a READY asset.
            UUID archiveId = UUID.nameUUIDFromBytes(("agenvas:provider-output:v1:" + task.id() + ":0").getBytes(StandardCharsets.UTF_8));
            var first = assets.archiveTaskImage(owner, fixture.project.id(), archiveId, () -> new ByteArrayInputStream(provider.imageBytes));
            var restarted = new MediaExecutionWorker(tasks, catalog, adapters, assets, mapper, callLogs);
            due(task.id()); restarted.pollOnce("rh-zip-resumed");
            var completed = tasks.get(owner, fixture.project.id(), task.id());
            assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
            var primaryVersion = artifacts.requireMediaVersionForTask(owner, fixture.project.id(),
                    UUID.fromString(completed.output().path("artifactVersionId").asText()), Artifact.Kind.IMAGE);
            assertThat(primaryVersion.content().path("assetId").asText()).isEqualTo(first.id().toString());
            assertThat(completed.output().path("additionalResults")).hasSize(1);
            var extraVersion = artifacts.requireMediaVersionForTask(owner, fixture.project.id(),
                    UUID.fromString(completed.output().path("additionalResults").get(0).path("artifactVersionId").asText()), Artifact.Kind.IMAGE);
            UUID extraAsset = UUID.fromString(extraVersion.content().path("assetId").asText());
            assertThat(assets.get(owner, fixture.project.id(), extraAsset).asset().mediaKind().name()).isEqualTo("IMAGE");
            assertThat(canvas.list(owner, fixture.project.id())).hasSize(2);
            assertThat(provider.submits).hasValue(1); assertThat(provider.queries).hasValue(1);
            assertThat(provider.zipDownloads).hasValue(3); assertThat(provider.firstDownloads).hasValue(0);
            assertThat(mapper.writeValueAsString(completed)).doesNotContain(provider.origin(), ".zip", "图片一", "archiveEntry", "fake-runninghub-key");
            assertThat(restarted.pollOnce("rh-zip-repeat")).isZero();
        }
    }

    @Test void zipMixedMediaUsesExistingAssetValidationAndCreatesDifferentArtifactKinds() throws Exception {
        try (var provider = new Fake(2, false, false)) {
            var audio = Files.createTempFile("rh-zip-audio-", ".wav");
            try {
                mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono", "-t", "1", "-y", audio.toString()));
                provider.zipOnly = true; provider.secondType = "wav";
                provider.zipBytes = zip(new String[] { "image.png", "audio.wav", "README.txt" },
                        new byte[][] { provider.imageBytes, Files.readAllBytes(audio), "synthetic metadata".getBytes(StandardCharsets.UTF_8) });
                var fixture = fixture(provider, Artifact.Kind.IMAGE, "AI_APP", false);
                Task task = accept(fixture, "Mixed ZIP"); worker.submitOnce("rh-zip-mixed-submit");
                due(task.id()); worker.pollOnce("rh-zip-mixed-poll");
                var completed = tasks.get(owner, fixture.project.id(), task.id());
                assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
                var extra = completed.output().path("additionalResults").get(0);
                assertThat(artifacts.get(owner, fixture.project.id(), UUID.fromString(extra.path("artifactId").asText())).artifact().kind()).isEqualTo(Artifact.Kind.AUDIO);
                var audioVersion = artifacts.requireMediaVersionForTask(owner, fixture.project.id(),
                        UUID.fromString(extra.path("artifactVersionId").asText()), Artifact.Kind.AUDIO);
                assertThat(assets.get(owner, fixture.project.id(), UUID.fromString(audioVersion.content().path("assetId").asText())).asset().contentType()).isEqualTo("audio/wav");
                assertThat(drafts.get(owner, fixture.project.id(), UUID.fromString(extra.path("canvasItemId").asText())).prompt()).isEmpty();
                assertThat(canvas.list(owner, fixture.project.id())).hasSize(2);
                assertThat(provider.zipDownloads).hasValue(2); assertThat(provider.submits).hasValue(1);
            } finally { Files.deleteIfExists(audio); }
        }
    }

    @Test void aPngFilenameInsideZipCannotBypassMediaDecodingOrCauseAnotherGeneration() throws Exception {
        try (var provider = new Fake(1, false, false)) {
            provider.zipOnly = true;
            provider.zipBytes = zip(new String[] { "fake.png" }, new byte[][] { "not an image".getBytes(StandardCharsets.UTF_8) });
            var fixture = fixture(provider, Artifact.Kind.IMAGE, "WORKFLOW", false);
            Task task = accept(fixture, ""); worker.submitOnce("rh-zip-invalid-submit");
            due(task.id()); worker.pollOnce("rh-zip-invalid-poll");
            assertThat(tasks.get(owner, fixture.project.id(), task.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
            assertThat(tasks.providerResultManifest(task)).isPresent();
            due(task.id()); worker.pollOnce("rh-zip-invalid-retry");
            assertThat(provider.submits).hasValue(1); assertThat(provider.queries).hasValue(1);
            assertThat(artifacts.get(owner, fixture.project.id(), fixture.artifact.id()).resourceDefaultVersion()).isNull();
            assertThat(jdbc.sql("select count(*) from asset where project_id=:project").param("project", fixture.project.id()).query(Integer.class).single()).isZero();
        }
    }

    @Test void zipVideoIsDecodedAndArchivedAsTheCurrentNodeResult() throws Exception {
        try (var provider = new Fake(1, false, false)) {
            var video = Files.createTempFile("rh-zip-video-", ".mp4");
            try {
                mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=blue:s=64x64:r=8", "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an", "-y", video.toString()));
                provider.zipOnly = true;
                provider.zipBytes = zip(new String[] { "output/video.mp4" }, new byte[][] { Files.readAllBytes(video) });
                var fixture = fixture(provider, Artifact.Kind.VIDEO, "WORKFLOW", false);
                Task task = accept(fixture, "Video ZIP"); worker.submitOnce("rh-zip-video-submit");
                due(task.id()); worker.pollOnce("rh-zip-video-poll");
                var completed = tasks.get(owner, fixture.project.id(), task.id());
                assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
                assertThat(completed.output().path("selected").asBoolean()).isTrue();
                var version = artifacts.requireMediaVersionForTask(owner, fixture.project.id(),
                        UUID.fromString(completed.output().path("artifactVersionId").asText()), Artifact.Kind.VIDEO);
                var asset = assets.get(owner, fixture.project.id(), UUID.fromString(version.content().path("assetId").asText())).asset();
                assertThat(asset.contentType()).isEqualTo("video/mp4");
                assertThat(asset.width()).isEqualTo(64); assertThat(asset.height()).isEqualTo(64);
                assertThat(asset.durationMs()).isEqualTo(1000);
                assertThat(provider.submits).hasValue(1); assertThat(provider.queries).hasValue(1);
                assertThat(provider.zipDownloads).hasValue(2); assertThat(canvas.list(owner, fixture.project.id())).hasSize(1);
            } finally { Files.deleteIfExists(video); }
        }
    }

    private static byte[] zip(String[] names, byte[][] contents) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < names.length; i++) { zip.putNextEntry(new ZipEntry(names[i])); zip.write(contents[i]); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }

    @Test void lostSubmissionResponseIsUnknownAndNeverAutomaticallySubmittedAgain() throws Exception {
        try (var provider = new Fake(1, false, true)) {
            var fixture = fixture(provider, Artifact.Kind.IMAGE, "AI_APP", false);
            Task task = accept(fixture, ""); worker.submitOnce("rh-unknown");
            assertThat(tasks.get(owner, fixture.project.id(), task.id()).status()).isEqualTo(Task.Status.UNKNOWN);
            assertThat(worker.submitOnce("rh-no-retry")).isZero(); assertThat(worker.pollOnce("rh-no-query")).isZero();
            assertThat(provider.submits).hasValue(1);
        }
    }

    @Test void changedCapabilityBlocksBeforeExternalSubmissionWithoutSwitchingToTheNewTarget() throws Exception {
        try (var provider = new Fake(1, false, false)) {
            var fixture = fixture(provider, Artifact.Kind.IMAGE, "WORKFLOW", false);
            Task task = accept(fixture, "");
            var previous = catalog.capabilitySnapshot(fixture.capability);
            ObjectNode replacement = (ObjectNode) mapper.readTree(previous.specJson()).path("settings").deepCopy();
            ((ObjectNode) replacement.path("runningHub")).put("targetId", "456");
            catalog.updateCapability(previous.connection().id(), fixture.capability, previous.capability().version(),
                    "Changed before submission", true, "RUNNINGHUB_IMAGE", replacement);
            worker.submitOnce("rh-config-guard");
            var blocked = tasks.get(owner, fixture.project.id(), task.id());
            assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
            assertThat(blocked.errorCode()).isEqualTo("MEDIA_CAPABILITY_CHANGED");
            assertThat(provider.submits).hasValue(0);
        }
    }

    @Test void canceledLateBatchIsArchivedWithoutSelectingAnyResult() throws Exception {
        try (var provider = new Fake(2, false, false)) {
            var fixture = fixture(provider, Artifact.Kind.IMAGE, "WORKFLOW", false);
            Task task = accept(fixture, ""); worker.submitOnce("rh-canceled-submit");
            jdbc.sql("update task set cancel_requested=true where id=:id").param("id", task.id()).update();
            due(task.id()); worker.pollOnce("rh-canceled-poll");
            var completed = tasks.get(owner, fixture.project.id(), task.id());
            assertThat(completed.status()).isEqualTo(Task.Status.CANCELED);
            var late = mapper.readTree(jdbc.sql("select output_json::text from task_late_result where task_id=:id")
                    .param("id", task.id()).query(String.class).single());
            assertThat(late.path("selected").asBoolean()).isFalse();
            assertThat(late.path("additionalResults").get(0).path("selected").asBoolean()).isFalse();
            assertThat(canvas.list(owner, fixture.project.id())).hasSize(2);
            assertThat(provider.submits).hasValue(1);
        }
    }

    @Test void mixedOutputsCreateTheCorrectIdentityAndStartWithABlankDraft() throws Exception {
        try (var provider = new Fake(2, false, false)) {
            var file = Files.createTempFile("rh-audio-result", ".wav");
            try {
                mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono", "-t", "1", "-y", file.toString()));
                provider.secondType = "wav"; provider.secondBytes = Files.readAllBytes(file);
                var fixture = fixture(provider, Artifact.Kind.IMAGE, "AI_APP", false);
                Task task = accept(fixture, "source prompt"); worker.submitOnce("rh-mixed-submit");
                due(task.id()); worker.pollOnce("rh-mixed-poll");
                var completed = tasks.get(owner, fixture.project.id(), task.id());
                assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
                var extra = completed.output().path("additionalResults").get(0);
                assertThat(artifacts.get(owner, fixture.project.id(), UUID.fromString(extra.path("artifactId").asText())).artifact().kind()).isEqualTo(Artifact.Kind.AUDIO);
                var extraDraft = drafts.get(owner, fixture.project.id(), UUID.fromString(extra.path("canvasItemId").asText()));
                assertThat(extraDraft.prompt()).isEmpty(); assertThat(extraDraft.mediaInputs()).isEmpty();
                assertThat(provider.submits).hasValue(1); assertThat(canvas.list(owner, fixture.project.id())).hasSize(2);
            } finally { Files.deleteIfExists(file); }
        }
    }

    @Test void aStaleWorkerCannotWriteThePrivateResultCheckpoint() throws Exception {
        try (var provider = new Fake(1, false, false)) {
            var fixture = fixture(provider, Artifact.Kind.IMAGE, "WORKFLOW", false);
            Task task = accept(fixture, ""); worker.submitOnce("rh-fence-submit"); due(task.id());
            assertThatThrownBy(() -> jdbc.sql("update task set provider_result_manifest='{\"results\":[{}]}'::jsonb where id=:id")
                    .param("id", task.id()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            Task lease = tasks.claimBoundMediaPolls("rh-old-worker", 1).getFirst();
            jdbc.sql("update task set lease_epoch=lease_epoch+1 where id=:id").param("id", task.id()).update();
            var manifest = new ProviderResultManifest(1, List.of(new ProviderResultManifest.Result(0, "9",
                    RunningHubDefinition.OutputKind.IMAGE, true, provider.origin() + "/first.png")), null);
            assertThatThrownBy(() -> tasks.checkpointProviderResults(lease, "rh-old-worker", manifest))
                    .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
            assertThat(jdbc.sql("select provider_result_manifest is null from task where id=:id").param("id", task.id()).query(Boolean.class).single()).isTrue();
            jdbc.sql("update task set status='WAITING_PROVIDER', lease_owner=null, lease_until=null where id=:id").param("id", task.id()).update();
            due(task.id()); worker.pollOnce("rh-new-worker");
            assertThat(tasks.get(owner, fixture.project.id(), task.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(provider.submits).hasValue(1);
        }
    }

    @Test void localDiscoveryPreviewRequiresAdminAndCsrfAndCannotGenerate() throws Exception {
        try (var provider = new Fake(1, false, false)) {
            var connection = catalog.createConnection(UUID.randomUUID().toString(), "Preview", "RUNNINGHUB", provider.origin(), "fake-runninghub-key");
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                    .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
            var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                    new UsernamePasswordAuthenticationToken(new AdminPrincipal(owner, "rh-admin"), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            String url = "/api/v1/settings/media-connections/" + connection.id() + "/runninghub/preview";
            String body = "{\"targetType\":\"AI_APP\",\"targetId\":\"123\",\"kind\":\"IMAGE_GENERATION\",\"source\":{\"nodeInfoList\":[{\"nodeId\":\"1\",\"fieldName\":\"text\",\"fieldType\":\"STRING\",\"fieldValue\":\"hello\"}]}}";
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).contentType("application/json").content(body)
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).contentType("application/json").content(body).with(auth))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).contentType("application/json").content(body).with(auth)
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(response).path("definition").path("fields")).hasSize(1);
            assertThat(response).doesNotContain("fake-runninghub-key"); assertThat(provider.submits).hasValue(0); assertThat(provider.queries).hasValue(0);
        }
    }

    @Test void videoReferenceIsAnExactAuthorizedSlotAndUnknownDurationHasUnknownSecondPricing() throws Exception {
        try (var provider = new Fake(1, false, false)) {
            var fixture = fixture(provider, Artifact.Kind.VIDEO, "AI_APP", true);
            var file = Files.createTempFile("rh-video", ".mp4");
            try {
                mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=blue:s=64x64:r=8", "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an", "-y", file.toString()));
                var asset = assets.archiveVideo(owner, fixture.project.id(), new ByteArrayInputStream(Files.readAllBytes(file)));
                var reference = artifacts.create(owner, fixture.project.id(), Artifact.Kind.VIDEO, "Reference", mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString()));
                UUID version = reference.resourceDefaultVersion().id();
                var parameters = mapper.createObjectNode(); parameters.putObject("dynamicValues").put("clip", version.toString());
                var draft = drafts.save(owner, fixture.project.id(), fixture.card, 0, "", parameters, null, fixture.capability,
                        MediaDraft.VideoInputMode.GENERAL_REFERENCE, List.of(new MediaDraftService.SaveMediaInput(version, MediaDraft.InputRole.VIDEO_REFERENCE, "#7C3AED")), List.of(), null);
                var task = direct.run(owner, fixture.project.id(), fixture.artifact.id(), fixture.card, draft.version(), UUID.randomUUID().toString());
                assertThat(task.input().path("mediaInput").path("videos").get(0).path("versionId").asText()).isEqualTo(version.toString());
                assertThat(task.input().has("durationSeconds")).isFalse();
                var reservation = usage.listProject(owner, fixture.project.id()).getFirst();
                assertThat(reservation.estimatedCost()).isNull(); assertThat(reservation.actualCost()).isNull();
                assertThat(reservation.quantity().path("videoSeconds").isNull()).isTrue();
                assertThat(worker.submitOnce("rh-video-reference")).isEqualTo(1);
                var nodes = mapper.readTree(provider.submitted.get()).path("nodeInfoList");
                assertThat(nodes.get(0).path("fieldValue").asText()).isEqualTo("input/reference.mp4");
                assertThat(provider.uploads).hasValue(1);
                // A resource can disappear after acceptance. Preflight must block before another upload/run.
                UUID missingCard = place(fixture.project, fixture.artifact);
                var missingDraft = drafts.save(owner, fixture.project.id(), missingCard, 0, "", parameters, null, fixture.capability,
                        MediaDraft.VideoInputMode.GENERAL_REFERENCE, List.of(new MediaDraftService.SaveMediaInput(version, MediaDraft.InputRole.VIDEO_REFERENCE, "#7C3AED")), List.of(), null);
                var missing = direct.run(owner, fixture.project.id(), fixture.artifact.id(), missingCard, missingDraft.version(), UUID.randomUUID().toString());
                var archivedPath = assets.get(owner, fixture.project.id(), asset.id()).path();
                Files.delete(archivedPath);
                try {
                    worker.submitOnce("rh-missing-input");
                    assertThat(tasks.get(owner, fixture.project.id(), missing.id()).status()).isEqualTo(Task.Status.BLOCKED);
                    assertThat(tasks.get(owner, fixture.project.id(), missing.id()).errorCode()).isEqualTo("RUNNINGHUB_INPUT_UNAVAILABLE");
                    assertThat(usage.listProject(owner, fixture.project.id())).anyMatch(entry -> missing.id().equals(entry.taskId())
                            && entry.entryType() == dev.agenvas.usage.domain.UsageEntry.EntryType.RELEASE);
                    assertThat(provider.submits).hasValue(1); assertThat(provider.uploads).hasValue(1);
                } finally { Files.write(archivedPath, Files.readAllBytes(file)); }
                // Another project cannot reuse the exact version, even with a valid named parameter value.
                var other = projects.create(owner, "Other", Project.AspectRatio.SQUARE_1_1);
                var otherArtifact = artifacts.create(owner, other.id(), Artifact.Kind.VIDEO, "Other card", null).artifact();
                UUID otherCard = place(other, otherArtifact);
                assertThatThrownBy(() -> drafts.save(owner, other.id(), otherCard, 0, "", parameters, null, fixture.capability,
                        MediaDraft.VideoInputMode.GENERAL_REFERENCE, List.of(new MediaDraftService.SaveMediaInput(version, MediaDraft.InputRole.VIDEO_REFERENCE, "#7C3AED")), List.of(), null)).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
                // Isolate later tests from this accepted video fixture without pretending it completed.
                jdbc.sql("update task set next_action_at=now() + interval '1 day' where id=:id").param("id", task.id()).update();
            } finally { Files.deleteIfExists(file); }
        }
    }

    private record Fixture(Project project, Artifact artifact, UUID card, UUID capability) {}
    private Fixture fixture(Fake provider, Artifact.Kind kind, String targetType, boolean videoSlot) {
        var project = projects.create(owner, "RunningHub " + UUID.randomUUID(), Project.AspectRatio.SQUARE_1_1);
        var artifact = artifacts.create(owner, project.id(), kind, "Result", null).artifact();
        UUID card = place(project, artifact);
        var connection = catalog.createConnection(UUID.randomUUID().toString(), "RunningHub fixture", "RUNNINGHUB", provider.origin(), "fake-runninghub-key");
        ObjectNode settings = mapper.createObjectNode(); ObjectNode definition = settings.putObject("runningHub");
        definition.put("schemaVersion", 1).put("protocolVersion", "V2").put("targetType", targetType).put("targetId", "123");
        var fields = definition.putArray("fields");
        if (videoSlot) fields.addObject().put("key", "clip").put("label", "参考视频").put("type", "VIDEO").put("required", true).put("nodeId", "1").put("fieldName", "video");
        var outputs = definition.putArray("outputs");
        outputs.addObject().put("kind", kind.name()).put("primary", true).put("maxCount", "wav".equals(provider.secondType) ? 1 : provider.resultCount);
        if ("wav".equals(provider.secondType)) outputs.addObject().put("kind", "AUDIO").put("primary", false).put("maxCount", 1);
        if (videoSlot) settings.putObject("pricing").put("amount", "0.5").put("currency", "CNY").put("unit", "SECOND");
        var capability = catalog.publishCapability(connection.id(), "Different " + targetType, "RUNNINGHUB_" + kind, settings);
        return new Fixture(project, artifact, card, capability.id());
    }
    private UUID place(Project project, Artifact artifact) {
        UUID card = UUID.randomUUID();
        canvas.apply(owner, project.id(), List.of(new CanvasService.PlaceArtifact(card, artifact.id(), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("280"), new BigDecimal("240"), 0, null, false)));
        return card;
    }
    private Task accept(Fixture fixture, String prompt) {
        var parameters = mapper.createObjectNode(); parameters.putObject("dynamicValues");
        var draft = drafts.save(owner, fixture.project.id(), fixture.card, 0, prompt, parameters, null, fixture.capability, null, List.of(), List.of(), null);
        return direct.run(owner, fixture.project.id(), fixture.artifact.id(), fixture.card, draft.version(), UUID.randomUUID().toString());
    }
    private void due(UUID taskId) { jdbc.sql("update task set next_action_at=now()-interval '1 second' where id=:id").param("id", taskId).update(); }

    private static final class Fake implements AutoCloseable {
        final HttpServer server;
        final int resultCount;
        final AtomicInteger submits = new AtomicInteger(), queries = new AtomicInteger(), firstDownloads = new AtomicInteger(), secondDownloads = new AtomicInteger(), uploads = new AtomicInteger();
        final AtomicInteger zipDownloads = new AtomicInteger();
        final AtomicReference<String> submitted = new AtomicReference<>();
        final AtomicReference<String> submissionPath = new AtomicReference<>();
        volatile String secondType = "png";
        volatile byte[] secondBytes;
        final byte[] imageBytes;
        volatile byte[] zipBytes;
        volatile boolean zipOnly, failZipArchiveOnce;
        Fake(int resultCount, boolean failSecondOnce, boolean uncertain) throws Exception {
            this.resultCount = resultCount;
            var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            imageBytes = bytes.toByteArray();
            zipBytes = zip(new String[] { "README.txt" }, new byte[][] { "synthetic metadata".getBytes(StandardCharsets.UTF_8) });
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); byte[] response;
                int status = 200;
                if (path.startsWith("/openapi/v2/run/")) {
                    submissionPath.set(path);
                    submits.incrementAndGet(); submitted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    status = uncertain ? 503 : 200; exchange.getResponseHeaders().set("Retry-After", "0");
                    response = "{\"taskId\":\"original-task\",\"status\":\"QUEUED\"}".getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/openapi/v2/query")) {
                    queries.incrementAndGet();
                    response = ("{\"status\":\"SUCCESS\",\"results\":["
                            + (zipOnly ? "" : "{\"nodeId\":\"9\",\"outputType\":\"png\",\"url\":\"" + origin() + "/first.png\"}"
                                + (resultCount > 1 ? ",{\"nodeId\":\"9\",\"outputType\":\"" + secondType + "\",\"url\":\"" + origin() + "/second.png\"}" : "") + ",")
                            + "{\"nodeId\":\"99\",\"outputType\":\"zip\",\"url\":\"" + origin() + "/companion.zip\"}"
                            + "],\"usage\":{\"consumeMoney\":null,\"consumeCoins\":0.25,\"taskCostTime\":3}}").getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/task/openapi/upload")) {
                    uploads.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                    response = "{\"code\":0,\"data\":{\"fileName\":\"input/reference.mp4\"}}".getBytes(StandardCharsets.UTF_8);
                } else {
                    if (path.equals("/first.png")) firstDownloads.incrementAndGet();
                    else if (path.equals("/companion.zip") && zipDownloads.incrementAndGet() == 2 && failZipArchiveOnce) status = 503;
                    else if (path.equals("/second.png") && secondDownloads.incrementAndGet() == 1 && failSecondOnce) status = 503;
                    response = path.equals("/companion.zip") ? zipBytes : path.equals("/second.png") && secondBytes != null ? secondBytes : imageBytes;
                }
                exchange.sendResponseHeaders(status, response.length); exchange.getResponseBody().write(response); exchange.close();
            }); server.start();
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
