package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.application.McpToolPolicy;
import dev.agenvas.mcp.application.McpToolService;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.VideoCompositionService;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
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
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL, actual FFmpeg, synthetic source media. No model or external provider is called. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class VideoCompositionPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @TempDir Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("agenvas.storage.root", () -> storage.toString());
        r.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired VideoCompositionService compositions;
    @Autowired dev.agenvas.task.application.DirectMediaTaskService direct;
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    @Autowired MediaExecutionWorker worker;
    @Autowired TaskService tasks;
    @Autowired MediaToolRunner mediaTools;
    @Autowired McpToolService mcp;
    @Autowired McpToolPolicy contract;
    @Autowired McpTokenService tokens;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    static AdminPrincipal owner;
    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("composition-test", "synthetic-composition-password-123");
    }

    @Test void mcpQueuesExactlyOnceAndRealWorkerArchivesCompositionWithFrozenSources() throws Exception {
        var fixture = fixture();
        var principal = tokens.authenticate(tokens.create(owner.userId(), new McpTokenService.Create("Composition",
                Set.of(McpScope.READ, McpScope.MEDIA_GENERATE), 30)).token());
        ObjectNode args = mapper.createObjectNode().put("projectId", fixture.project().toString()).put("commandId", "compose-once");
        args.set("composition", request(fixture));
        var response = mcp.execute(principal, "compose_video", args).data();
        contract.validateOutput("compose_video", response);
        assertThat(mcp.execute(principal, "compose_video", args).data()).isEqualTo(response);
        assertThat(canvas.list(owner.userId(), fixture.project())).hasSize(3);
        UUID taskId = UUID.fromString(response.at("/tasks/0/taskId").asText());
        Task accepted = tasks.get(owner.userId(), fixture.project(), taskId);
        assertThat(accepted.initiator()).isEqualTo(Task.Initiator.EXTERNAL_MCP);
        assertThat(accepted.approvedMedia()).isFalse();
        assertThat(accepted.runId()).isNull();
        assertThat(accepted.input().at("/mediaInput/videos")).hasSize(1);
        assertThat(accepted.input().at("/mediaInput/audios")).hasSize(1);
        assertThat(accepted.input().at("/videoComposition/shots/0/durationMs").asInt()).isEqualTo(3000);
        assertThatThrownBy(() -> mcp.execute(principal, "compose_video", args.deepCopy().set("composition",
                request(fixture).put("title", "Different")))).isInstanceOf(ApiProblemException.class)
                .extracting("code").isEqualTo("MCP_COMMAND_CONFLICT");
        var readOnly = tokens.authenticate(tokens.create(owner.userId(), new McpTokenService.Create("Reader", Set.of(McpScope.READ), 30)).token());
        assertThatThrownBy(() -> mcp.execute(readOnly, "compose_video", args)).isInstanceOf(ApiProblemException.class)
                .extracting("code").isEqualTo("MCP_SCOPE_DENIED");
        assertThat(worker.submitOnce("composition-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), fixture.project(), taskId);
        assertThat(completed.status()).withFailMessage("Composition failed: %s", completed.errorCode()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("selected").asBoolean()).isTrue();
        var result = artifacts.requireMediaVersionForTask(owner.userId(), fixture.project(),
                UUID.fromString(completed.output().path("artifactVersionId").asText()), Artifact.Kind.VIDEO);
        assertThat(result.inputReferences()).hasSize(2);
        var plan = result.content().at("/parameters/videoComposition");
        assertThat(plan.path("durationMs").asInt()).isBetween(2950, 3000);
        assertThat(plan.at("/shots/0/lines/0/audioVersionId").asText()).isEqualTo(fixture.audio().toString());
        var output = assets.get(owner.userId(), fixture.project(), UUID.fromString(result.content().path("assetId").asText()));
        assertThat(output.asset().width()).isEqualTo(1280);
        assertThat(output.asset().height()).isEqualTo(720);
        assertThat(output.asset().durationMs()).isBetween(2950, 3050);
        mediaTools.ffmpeg(List.of("-v", "error", "-nostdin", "-i", output.path().toString(), "-f", "null", "-"));
        ObjectNode read = mapper.createObjectNode().put("projectId", fixture.project().toString());
        read.putArray("versionIds").add(result.id().toString());
        var readable = mcp.execute(principal, "read_artifacts", read).data();
        contract.validateOutput("read_artifacts", readable);
        assertThat(readable.at("/items/0/composition/shots")).hasSize(1);
        assertThat(readable.toString()).doesNotContain("objectKey", "mcpTokenId", "file://");
    }

    @Test void invalidAndForeignSourcesRollbackBeforeCreatingOutput() throws Exception {
        var fixture = fixture();
        ObjectNode tooLong = request(fixture);
        ((ObjectNode) tooLong.at("/shots/0")).put("durationMs", 60000);
        assertInvalid(() -> compositions.run(owner.userId(), fixture.project(), tooLong, "invalid"));
        ObjectNode wrongType = request(fixture);
        ((ObjectNode) wrongType.at("/shots/0/lines/0")).put("startMs", 0.5);
        assertInvalid(() -> compositions.run(owner.userId(), fixture.project(), wrongType, "invalid"));
        var foreign = projects.create(owner.userId(), "Other composition", Project.AspectRatio.LANDSCAPE_16_9);
        assertThatThrownBy(() -> compositions.run(owner.userId(), foreign.id(), request(fixture), "foreign"))
                .isInstanceOf(ApiProblemException.class);
        assertThat(canvas.list(owner.userId(), fixture.project())).hasSize(2);
        assertThat(canvas.list(owner.userId(), foreign.id())).isEmpty();
    }

    @Test void concurrentSameCommandCreatesOnlyOneTaskAndOutput() throws Exception {
        var fixture = fixture();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> compositions.run(owner.userId(), fixture.project(), request(fixture), "same-command"));
            var second = executor.submit(() -> compositions.run(owner.userId(), fixture.project(), request(fixture), "same-command"));
            var task = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(second.get(15, java.util.concurrent.TimeUnit.SECONDS).id()).isEqualTo(task.id());
            assertThat(canvas.list(owner.userId(), fixture.project())).hasSize(3);
            direct.cancelQueued(owner.userId(), fixture.project(), task.id());
        }
    }

    @Test void restRequiresSessionAndCsrfAndUsesTheSameValidatedContract() throws Exception {
        var fixture = fixture();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner, "unused",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        String url = "/api/v1/projects/" + fixture.project() + "/video-compositions";
        var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Idempotency-Key", "rest-composition").contentType("application/json").content(request(fixture).toString());
        mvc.perform(post.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(post.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf().useInvalidToken()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var response = mvc.perform(post.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var accepted = mapper.readTree(response.getResponse().getContentAsString());
        assertThat(accepted.at("/input/videoComposition/shots")).hasSize(1);
        direct.cancelQueued(owner.userId(), fixture.project(), UUID.fromString(accepted.path("id").asText()));
    }

    @Test void removingOutputBeforeCompletionKeepsResultHistoryWithoutRecreatingNode() throws Exception {
        var fixture = fixture();
        Task task = compositions.run(owner.userId(), fixture.project(), request(fixture), "removed-output");
        UUID item = UUID.fromString(task.input().path("canvasItemId").asText());
        canvas.apply(owner.userId(), fixture.project(), List.of(new CanvasService.Remove(item, 0)));
        assertThat(worker.submitOnce("removed-composition")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), fixture.project(), task.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("selected").asBoolean()).isFalse();
        assertThat(canvas.list(owner.userId(), fixture.project())).hasSize(2);
    }

    @Test void zeroDialoguePostproductionFreezesMusicSubtitlesAndOutputAndReplaysTheSameCommand() throws Exception {
        var fixture = fixture();
        ObjectNode request = postproduction(fixture);
        var accepted = compositions.run(owner.userId(), fixture.project(), request, "postproduction-freeze");
        assertThat(compositions.run(owner.userId(), fixture.project(), request.deepCopy(), "postproduction-freeze").id())
                .isEqualTo(accepted.id());
        var stored = tasks.get(owner.userId(), fixture.project(), accepted.id());
        assertThat(stored.input().at("/videoComposition/shots/0/lines")).isEmpty();
        assertThat(stored.input().at("/videoComposition/shots/0/sourceStartMs").asInt()).isEqualTo(1000);
        assertThat(stored.input().at("/videoComposition/shots/0/durationMs").asInt()).isEqualTo(2000);
        assertThat(stored.input().at("/videoComposition/shots/0/speed").asDouble()).isEqualTo(2);
        assertThat(stored.input().path("sourceDurationMs").asInt()).isEqualTo(1000);
        assertThat(stored.input().at("/videoComposition/output/resolution").asText()).isEqualTo("1080P");
        assertThat(stored.input().at("/videoComposition/output/aspectRatio").asText()).isEqualTo("9:16");
        assertThat(stored.input().at("/mediaInput/audios")).hasSize(1);
        assertThat(stored.input().at("/mediaInput/audios/0/versionId").asText()).isEqualTo(fixture.audio().toString());
        assertThat(stored.input().at("/mediaInput/parameters/videoComposition"))
                .isEqualTo(stored.input().path("videoComposition"));
        assertThat(stored.input().at("/videoComposition/subtitles/0/text").asText()).isEqualTo("字幕 {\\p1}\\N is data");
        ((ObjectNode) request.path("music")).put("volume", 0.9);
        assertThat(stored.input().at("/videoComposition/music/volume").asDouble()).isEqualTo(0.25);
        assertThatThrownBy(() -> compositions.run(owner.userId(), fixture.project(), request, "postproduction-freeze"))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(canvas.list(owner.userId(), fixture.project())).hasSize(3);
        assertThat(taskCount(fixture.project())).isEqualTo(1);
        direct.cancelQueued(owner.userId(), fixture.project(), accepted.id());
    }

    @Test void zeroDialogueSpeedAndPortraitOutputRenderThroughThePersistentWorker() throws Exception {
        var fixture = fixture();
        ObjectNode request = postproduction(fixture);
        request.remove("subtitles"); // libass pixel rendering has a separate deployment-binary test.
        var accepted = compositions.run(owner.userId(), fixture.project(), request, "postproduction-render");
        assertThat(worker.submitOnce("postproduction-render-worker")).isEqualTo(1);
        var completed = tasks.get(owner.userId(), fixture.project(), accepted.id());
        assertThat(completed.status()).withFailMessage("Postproduction failed: %s", completed.errorCode()).isEqualTo(Task.Status.SUCCEEDED);
        var version = artifacts.requireMediaVersionForTask(owner.userId(), fixture.project(),
                UUID.fromString(completed.output().path("artifactVersionId").asText()), Artifact.Kind.VIDEO);
        var output = assets.get(owner.userId(), fixture.project(), UUID.fromString(version.content().path("assetId").asText()));
        assertThat(output.asset().width()).isEqualTo(1080);
        assertThat(output.asset().height()).isEqualTo(1920);
        assertThat(output.asset().durationMs()).isEqualTo(1000);
        assertThat(version.inputReferences()).hasSize(2);
        assertThat(version.content().at("/parameters/videoComposition/shots/0/lines")).isEmpty();
        assertThat(version.content().at("/parameters/videoComposition/music/audioVersionId").asText()).isEqualTo(fixture.audio().toString());
        mediaTools.ffmpeg(List.of("-v", "error", "-xerror", "-i", output.path().toString(), "-f", "null", "-"));
    }

    @Test void postproductionRejectsFrameTimingAndUnauthorizedMusicAtomically() throws Exception {
        var fixture = fixture();
        ObjectNode beyondOutput = postproduction(fixture);
        ((ObjectNode) beyondOutput.at("/subtitles/0")).put("endMs", 1001);
        assertInvalid(() -> compositions.run(owner.userId(), fixture.project(), beyondOutput, "bad-subtitle"));
        ObjectNode roundedOutput = postproduction(fixture);
        ((ObjectNode) roundedOutput.at("/shots/0")).put("sourceStartMs", 0).put("durationMs", 100).put("speed", 1.15).put("fadeMs", 0);
        ((ObjectNode) roundedOutput.at("/subtitles/0")).put("endMs", 80);
        assertInvalid(() -> compositions.run(owner.userId(), fixture.project(), roundedOutput, "bad-rounded-subtitle"));
        ObjectNode invalidOffset = postproduction(fixture);
        ((ObjectNode) invalidOffset.path("music")).put("sourceStartMs", 1100);
        assertInvalid(() -> compositions.run(owner.userId(), fixture.project(), invalidOffset, "bad-music-offset"));
        var foreign = fixture();
        ObjectNode foreignMusic = postproduction(fixture);
        ((ObjectNode) foreignMusic.path("music")).put("audioVersionId", foreign.audio().toString());
        assertThatThrownBy(() -> compositions.run(owner.userId(), fixture.project(), foreignMusic, "foreign-music"))
                .isInstanceOf(ApiProblemException.class);
        assertThat(taskCount(fixture.project())).isZero();
        assertThat(canvas.list(owner.userId(), fixture.project())).hasSize(2);
        assertThat(canvas.list(owner.userId(), foreign.project())).hasSize(2);
    }

    private int taskCount(UUID project) {
        return jdbc.sql("select count(*) from task where project_id=:project").param("project", project).query(Integer.class).single();
    }

    private ObjectNode postproduction(Fixture fixture) {
        ObjectNode request = mapper.createObjectNode().put("title", "Synthetic postproduction").put("originalAudioMode", "KEEP");
        request.putArray("shots").addObject().put("videoVersionId", fixture.video().toString()).put("sourceStartMs", 1000)
                .put("durationMs", 2000).put("speed", 2).put("fadeMs", 100).putArray("lines");
        request.putObject("output").put("resolution", "1080P").put("aspectRatio", "9:16");
        request.putObject("music").put("audioVersionId", fixture.audio().toString()).put("sourceStartMs", 100).put("volume", 0.25);
        request.putArray("subtitles").addObject().put("text", "字幕 {\\p1}\\N is data").put("startMs", 0).put("endMs", 900);
        return request;
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("VIDEO_COMPOSITION_INVALID");
    }
    private ObjectNode request(Fixture fixture) {
        ObjectNode request = mapper.createObjectNode().put("title", "Synthetic dubbed scene").put("rationale", "Dialogue follows the established shot.");
        request.putArray("shots").addObject().put("videoVersionId", fixture.video().toString()).put("durationMs", 3000)
                .putArray("lines").addObject().put("audioVersionId", fixture.audio().toString());
        return request;
    }
    private Fixture fixture() throws Exception {
        var project = projects.create(owner.userId(), "Composition", Project.AspectRatio.LANDSCAPE_16_9);
        Path video = temp.resolve(UUID.randomUUID() + ".mp4"), audio = temp.resolve(UUID.randomUUID() + ".wav");
        mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=blue:s=160x90:r=30:d=3",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=3", "-af", "volume='if(between(t,1,2),1,0)':eval=frame",
                "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-y", video.toString()));
        mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=16000:duration=0.7",
                "-af", "adelay=200:all=1,apad=pad_dur=0.2", "-c:a", "pcm_s16le", "-y", audio.toString()));
        return new Fixture(project.id(), media(project.id(), video, Artifact.Kind.VIDEO), media(project.id(), audio, Artifact.Kind.AUDIO));
    }
    private UUID media(UUID project, Path path, Artifact.Kind kind) {
        java.util.function.Supplier<java.io.InputStream> open = () -> {
            try { return Files.newInputStream(path); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        };
        var asset = kind == Artifact.Kind.VIDEO ? assets.archiveTaskVideo(owner.userId(), project, UUID.randomUUID(), open)
                : assets.archiveTaskAudio(owner.userId(), project, UUID.randomUUID(), open);
        var artifact = artifacts.create(owner.userId(), project, kind, "Synthetic " + kind,
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString()));
        dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project, artifact.artifact().id());
        return artifact.resourceDefaultVersion().id();
    }
    private record Fixture(UUID project, UUID video, UUID audio) {}
}
