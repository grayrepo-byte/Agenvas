package dev.agenvas.llm;

import static dev.agenvas.testing.AgentFixture.configureDirector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnCommitService;
import dev.agenvas.llm.application.AgentVideoCompositionToolService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ReadToolService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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

/** Real PostgreSQL and decoded synthetic assets; the model response is synthetic and no Provider is invoked. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class AgentVideoCompositionToolsPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @TempDir Path temp;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("agenvas.storage.root", () -> storage.toString());
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaToolRunner mediaTools;
    @Autowired CanvasService canvas;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired TaskService tasks;
    @Autowired DirectMediaTaskService directTasks;
    @Autowired AgentTurnCommitService commits;
    @Autowired LlmTurnCheckpointService checkpoints;
    @Autowired LlmProtocolCodec codec;
    @Autowired ToolRegistry registry;
    @Autowired ToolExecutionService execution;
    @Autowired AgentVideoCompositionToolService compositionTool;
    @Autowired ReadToolService reader;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @Test
    void committedCompositionIsScopedQueuedOnceAndReadableWithoutCreatingAnApproval() throws Exception {
        var owner = identities.setup("agent-compose-test", "synthetic-compose-password-123");
        var project = projects.create(owner.userId(), "Composition tools", Project.AspectRatio.LANDSCAPE_16_9);
        Path videoFile = temp.resolve("shot.mp4");
        mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                "testsrc2=s=160x90:r=12", "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", videoFile.toString()));
        var videoAsset = assets.archiveTaskVideo(owner.userId(), project.id(), UUID.randomUUID(), () -> {
            try { return Files.newInputStream(videoFile); }
            catch (java.io.IOException failed) { throw new IllegalStateException(failed); }
        });
        var video = uploaded(owner.userId(), project.id(), Artifact.Kind.VIDEO, videoAsset.id(), "镜头 1");
        UUID audioAsset = dev.agenvas.testing.AudioAssetFixture.archive(assets, mediaTools, owner.userId(), project.id());
        var audio = uploaded(owner.userId(), project.id(), Artifact.Kind.AUDIO, audioAsset, "镜头 1 台词");
        var hiddenAudio = artifacts.create(owner.userId(), project.id(), Artifact.Kind.AUDIO, "未放入画布的音频",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", audioAsset.toString()));
        var agent = configureDirector(agents, owner.userId(), project.id(), "Director", "Arrange dialogue from evidence");
        var queued = runs.create(owner.userId(), project.id(), agent.id(), "自动匹配台词并合成", "composition-tool-run").run();
        String worker = "composition-tool-worker";
        Task lease = tasks.claimAgentTurns(worker, 1).getFirst();
        var run = commits.start(lease, worker);
        var trusted = new TrustedToolContext(owner.userId(), project.id(), run.id());
        var request = request(video.resourceDefaultVersion().id(), audio.resourceDefaultVersion().id());
        int beforeNodes = canvas.list(owner.userId(), project.id()).size();
        assertThatThrownBy(() -> compositionTool.compose(trusted, run, UUID.randomUUID(),
                request(video.resourceDefaultVersion().id(), hiddenAudio.resourceDefaultVersion().id()).toString()))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("INPUT_SCOPE_DENIED");
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(beforeNodes);

        var read = reader.artifacts(trusted, run, UUID.randomUUID(), mapper.createObjectNode().set("versionIds",
                mapper.createArrayNode().add(video.resourceDefaultVersion().id().toString())
                        .add(audio.resourceDefaultVersion().id().toString())).toString());
        assertThat(read.at("/data/0/mediaMetadata/durationMs").asInt()).isBetween(1900, 2100);
        assertThat(read.at("/data/1/mediaMetadata/durationMs").asInt()).isEqualTo(1000);
        assertThat(read.toString()).doesNotContain("objectKey", "thumbnailKey");

        int config = run.policySnapshot().path("modelConfigVersion").asInt();
        checkpoints.reserveLeased(owner.userId(), project.id(), run.id(), 0, config,
                run.policySnapshot().path("modelConfigSource").asText(),
                codec.request(List.of(new UserMessage("Compose existing media")), registry.modelDefinitions(run.policySnapshot())),
                lease, worker);
        tasks.startAgentStream(lease, worker);
        var assistant = AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                "compose-existing", "function", "compose_video", request.toString()))).build();
        checkpoints.saveResponseLeased(owner.userId(), project.id(), run.id(), 0, config,
                codec.response(new ChatResponse(List.of(new Generation(assistant)))), lease, worker);

        var receipt = execution.executeLeased(trusted, 0, "compose-existing", lease, worker);
        assertThat(execution.executeLeased(trusted, 0, "compose-existing", lease, worker)).isEqualTo(receipt);
        assertThat(receipt.at("/data/queued").asBoolean()).isTrue();
        assertThat(receipt.at("/data/completed").asBoolean()).isFalse();
        UUID taskId = UUID.fromString(receipt.at("/data/taskId").asText());
        Task task = tasks.get(owner.userId(), project.id(), taskId);
        assertThat(task.status()).isEqualTo(Task.Status.READY);
        assertThat(task.runId()).isNull();
        assertThat(task.compositionRunId()).isEqualTo(run.id());
        assertThat(task.initiator()).isEqualTo(Task.Initiator.BUILTIN_AGENT);
        assertThat(task.approvedMedia()).isFalse();
        assertThat(receipt.at("/createdIds/0")).isEqualTo(task.input().path("artifactId"));
        assertThat(receipt.at("/createdCanvasItemIds/0")).isEqualTo(task.input().path("canvasItemId"));
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(beforeNodes + 1);
        assertThat(jdbc.sql("select count(*) from agent_media_approval where run_id = :run")
                .param("run", run.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :run")
                .param("run", run.id()).query(Long.class).single()).isEqualTo(1);
        String statusRequest = mapper.createObjectNode().set("taskIds", mapper.createArrayNode().add(taskId.toString())).toString();
        assertThat(reader.taskStatus(trusted, UUID.randomUUID(), statusRequest).at("/data/0/status").asText()).isEqualTo("READY");
        assertThatThrownBy(() -> reader.taskStatus(new TrustedToolContext(owner.userId(), project.id(), UUID.randomUUID()),
                UUID.randomUUID(), statusRequest)).isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("TASK_NOT_FOUND");
        assertThat(directTasks.cancelQueued(owner.userId(), project.id(), taskId).status()).isEqualTo(Task.Status.CANCELED);
    }

    private ArtifactService.ArtifactView uploaded(UUID owner, UUID project, Artifact.Kind kind, UUID asset, String title) {
        var artifact = artifacts.create(owner, project, kind, title,
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.toString()));
        dev.agenvas.support.CanvasMediaFixture.place(canvas, owner, project, artifact.artifact().id());
        return artifact;
    }

    private ObjectNode request(UUID video, UUID audio) {
        var request = mapper.createObjectNode().put("title", "配音成片").put("rationale", "按镜头编号匹配已有素材")
                .put("originalAudioMode", "MUTE");
        request.putArray("shots").addObject().put("videoVersionId", video.toString()).putArray("lines")
                .addObject().put("audioVersionId", audio.toString());
        return request;
    }
}
