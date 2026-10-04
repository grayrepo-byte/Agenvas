package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
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
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.DirectMediaTaskService;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import dev.agenvas.task.application.VideoOperationService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.VideoOperation;
import dev.agenvas.provider.domain.MediaFunction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and FFmpeg; all media and credentials in this test are synthetic. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=synthetic-video-tool-bootstrap",
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class VideoOperationPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agenvas.storage.root", () -> storage.toString());
        registry.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.provider.local-image.depth-model", () -> System.getProperty("agenvas.test.depth-model", ""));
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired CanvasConnectionService connections;
    @Autowired MediaDraftService drafts;
    @Autowired VideoOperationService operations;
    @Autowired MediaFunctionService functions;
    @Autowired MediaCapabilityService capabilities;
    @Autowired MediaExecutionWorker worker;
    @Autowired TaskService tasks;
    @Autowired DirectMediaTaskService direct;
    @Autowired WebApplicationContext webContext;
    @Autowired MediaToolRunner tools;
    @Autowired ObjectMapper mapper;
    @TempDir Path temp;
    private static AdminPrincipal owner;

    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("synthetic-video-tool-bootstrap", "video-tools-test", "synthetic-video-password-123");
    }

    @Test void extractsActualAudioToIndependentArtifactAndKeepsSourceUnchanged() throws Exception {
        var source = source(true);
        var task = run(source, VideoOperation.EXTRACT_AUDIO, "audio-extraction");
        var replay = run(source, VideoOperation.EXTRACT_AUDIO, "audio-extraction");
        assertThat(replay.id()).isEqualTo(task.id());
        assertThatThrownBy(() -> run(source, VideoOperation.DEPTH_MAP, "audio-extraction"))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        UUID resultItemId = UUID.fromString(task.input().path("canvasItemId").asText());
        UUID resultArtifactId = UUID.fromString(task.input().path("artifactId").asText());
        assertThat(resultArtifactId).isNotEqualTo(source.artifactId());
        var outputItem = canvas.list(owner.userId(), source.projectId()).stream()
                .filter(entry -> entry.item().id().equals(resultItemId)).findFirst().orElseThrow().item();
        assertThat(outputItem.width()).isEqualByComparingTo("430");
        assertThat(outputItem.height()).isEqualByComparingTo("240");
        assertThat(drafts.get(owner.userId(), source.projectId(), resultItemId).mediaInputs()).isEmpty();
        assertThat(connections.list(owner.userId(), source.projectId())).singleElement().satisfies(line -> {
            assertThat(line.relationType()).isEqualTo(CanvasConnection.RelationType.MEDIA_DERIVATION);
            assertThat(line.sourceArtifactVersionId()).isEqualTo(source.versionId());
        });
        assertThat(worker.submitOnce("video-audio-test")).isEqualTo(1);
        var completed = tasks.get(owner.userId(), source.projectId(), task.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("selected").asBoolean()).isTrue();
        var result = artifacts.requireVersion(owner.userId(), source.projectId(), resultArtifactId,
                UUID.fromString(completed.output().path("artifactVersionId").asText()));
        var audio = assets.get(owner.userId(), source.projectId(), UUID.fromString(result.content().path("assetId").asText()));
        assertThat(audio.asset().durationMs()).isBetween(900, 1100);
        assertThat(audio.asset().contentType()).isEqualTo("audio/wav");
        assertThat(artifacts.get(owner.userId(), source.projectId(), source.artifactId()).resourceDefaultVersion().id()).isEqualTo(source.versionId());
        assertThat(canvas.list(owner.userId(), source.projectId()).stream().filter(entry -> entry.item().id().equals(source.itemId()))
                .findFirst().orElseThrow().item().selectedVersionId()).isEqualTo(source.versionId());
        var line = connections.list(owner.userId(), source.projectId()).getFirst();
        connections.disconnect(owner.userId(), source.projectId(), line.id(), null, null);
        assertThat(canvas.list(owner.userId(), source.projectId())).hasSize(2);
    }

    @Test void silentVideoBlocksWithExplicitReason() throws Exception {
        var source = source(false);
        var accepted = run(source, VideoOperation.EXTRACT_AUDIO, "silent-source");
        worker.submitOnce("silent-video-test");
        var blocked = tasks.get(owner.userId(), source.projectId(), accepted.id());
        assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(blocked.errorCode()).isEqualTo("VIDEO_AUDIO_TRACK_MISSING");
    }

    @Test void unconfiguredUpscaleCreatesNoPartialNodeAndSettingsUseCas() throws Exception {
        var source = source(true);
        assertThatThrownBy(() -> run(source, VideoOperation.UPSCALE, "unconfigured-upscale"))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("MEDIA_FUNCTION_UNCONFIGURED");
        assertThat(canvas.list(owner.userId(), source.projectId())).hasSize(1);
        long settingVersion = functions.list().stream().filter(setting -> setting.operation() == MediaFunction.VIDEO_UPSCALE).findFirst().orElseThrow().version();
        functions.update(MediaFunction.VIDEO_UPSCALE, settingVersion, null);
        assertThatThrownBy(() -> functions.update(MediaFunction.VIDEO_UPSCALE, settingVersion, null))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("MEDIA_FUNCTION_CONFLICT");
        assertThat(capabilities.publishedCandidates()).noneMatch(candidate ->
                candidate.binding().capabilityId().equals(MediaFunctionService.LOCAL_AUDIO_CAPABILITY));
    }

    @Test void depthUsesRealModelOrExplicitlyBlocks() throws Exception {
        var source = source(false);
        var accepted = run(source, VideoOperation.DEPTH_MAP, "depth-video");
        worker.submitOnce("depth-video-test");
        var result = tasks.get(owner.userId(), source.projectId(), accepted.id());
        if (System.getProperty("agenvas.test.depth-model", "").isBlank()) {
            assertThat(result.status()).isEqualTo(Task.Status.BLOCKED);
            assertThat(result.errorCode()).isEqualTo("LOCAL_DEPTH_MODEL_UNAVAILABLE");
        } else {
            assertThat(result.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(result.output().path("selected").asBoolean()).isTrue();
            var version = artifacts.requireMediaVersionForTask(owner.userId(), source.projectId(),
                    UUID.fromString(result.output().path("artifactVersionId").asText()), Artifact.Kind.VIDEO);
            var depthAsset = assets.metadata(owner.userId(), source.projectId(), UUID.fromString(version.content().path("assetId").asText()));
            assertThat(depthAsset.contentType()).isEqualTo("video/mp4");
            assertThat(depthAsset.durationMs()).isBetween(900, 1100);
            assertThat(depthAsset.width()).isLessThanOrEqualTo(518);
            assertThat(depthAsset.height()).isLessThanOrEqualTo(518);
        }
    }

    @Test void rejectsChangedNodeOrPublishedCapabilityWithoutCreatingOutput() throws Exception {
        var source = source(true);
        for (boolean changedNode : List.of(true, false)) {
            assertThatThrownBy(() -> operations.run(owner.userId(), source.projectId(), source.artifactId(), source.itemId(), source.versionId(),
                    changedNode ? 1 : 0, VideoOperation.EXTRACT_AUDIO, 0, changedNode ? 1 : 2, "", mapper.createObjectNode(), UUID.randomUUID().toString()))
                    .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo(changedNode ? "DIRECT_MEDIA_CONFLICT" : "MEDIA_CAPABILITY_CHANGED");
        }
        assertThat(canvas.list(owner.userId(), source.projectId())).hasSize(1);
    }

    @Test void freezesPublishedRunningHubTransformAndTrustedSourceWithoutCallingProvider() throws Exception {
        var source = source(false);
        var connection = capabilities.createConnection(UUID.randomUUID().toString(), "Synthetic transform", "RUNNINGHUB", null, "synthetic-key-not-real");
        var settings = mapper.readTree("""
                {"runningHub":{"schemaVersion":1,"protocolVersion":"V2","targetType":"WORKFLOW","targetId":"123",
                  "fields":[{"key":"clip","label":"Video","type":"VIDEO","required":true,"nodeId":"1","fieldName":"video"},
                    {"key":"scale","label":"Scale","type":"INTEGER","required":true,"defaultValue":2,"minimum":2,"maximum":4,"nodeId":"2","fieldName":"scale"}],
                  "outputs":[{"kind":"VIDEO","primary":true,"maxCount":1}]},
                 "pricing":{"amount":"0.5","currency":"CNY","unit":"SECOND"}}
                """);
        var capability = capabilities.publishCapability(connection.id(), "Synthetic AI upscale", "RUNNINGHUB_VIDEO", settings);
        var initial = functions.list().stream().filter(setting -> setting.operation() == MediaFunction.VIDEO_UPSCALE).findFirst().orElseThrow();
        functions.update(MediaFunction.VIDEO_UPSCALE, initial.version(), capability.id());
        try {
            var params = mapper.createObjectNode(); params.putObject("dynamicValues").put("clip", UUID.randomUUID().toString()).put("scale", 4);
            var task = operations.run(owner.userId(), source.projectId(), source.artifactId(), source.itemId(), source.versionId(),
                    0, VideoOperation.UPSCALE, initial.version() + 1, 1, "", params, "synthetic-upscale");
            assertThat(task.input().path("mediaInput").path("parameters").path("dynamicValues").path("clip").asText()).isEqualTo(source.versionId().toString());
            assertThat(task.input().path("mediaInput").path("parameters").path("dynamicValues").path("scale").asInt()).isEqualTo(4);
            assertThat(task.input().path("mediaInput").path("runningHubContract").path("targetId").asText()).isEqualTo("123");
            assertThat(task.input().has("durationSeconds")).isFalse();
            assertThat(drafts.get(owner.userId(), source.projectId(), UUID.fromString(task.input().path("canvasItemId").asText())).mediaInputs()).isEmpty();
            direct.cancelQueued(owner.userId(), source.projectId(), task.id());
        } finally {
            functions.update(MediaFunction.VIDEO_UPSCALE, initial.version() + 1, initial.capabilityId());
        }
    }

    @Test void settingsApiRequiresAuthenticationAndCsrfAndReturnsContract() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        String url = "/api/v1/settings/media-functions";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(auth))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response)).hasSize(18);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url + "/VIDEO_EXTRACT_AUDIO")
                .with(auth).contentType("application/json").content("{\"expectedVersion\":0,\"capabilityId\":null}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    private record Source(UUID projectId, UUID artifactId, UUID itemId, UUID versionId) {}
    private Source source(boolean audio) throws Exception {
        var project = projects.create(owner.userId(), "Synthetic video tool", Project.AspectRatio.LANDSCAPE_16_9);
        Path video = temp.resolve(UUID.randomUUID() + ".mp4");
        var command = new java.util.ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                "-f", "lavfi", "-i", "testsrc2=s=160x90:r=12"));
        if (audio) command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000"));
        command.addAll(List.of("-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p"));
        if (audio) command.addAll(List.of("-c:a", "aac"));
        command.addAll(List.of("-y", video.toString()));
        tools.ffmpeg(command);
        var asset = assets.archiveTaskVideo(owner.userId(), project.id(), UUID.randomUUID(), () -> {
            try { return Files.newInputStream(video); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
        var artifact = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO, "合成视频",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString()));
        UUID item = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifact.artifact().id());
        return new Source(project.id(), artifact.artifact().id(), item, artifact.resourceDefaultVersion().id());
    }

    private Task run(Source source, VideoOperation operation, String key) {
        long version = functions.list().stream().filter(setting -> setting.operation() == MediaFunction.forVideo(operation)).findFirst().orElseThrow().version();
        return operations.run(owner.userId(), source.projectId(), source.artifactId(), source.itemId(), source.versionId(),
                0, operation, version, 1, "", mapper.createObjectNode(), key);
    }
}
