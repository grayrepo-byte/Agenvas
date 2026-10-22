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
import dev.agenvas.llm.application.AgentImageInputService;
import dev.agenvas.llm.application.AgentMediaSampleService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.support.CanvasMediaFixture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL, FFmpeg and tools; the fake model receives Spring AI media only after committed reads. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentMediaSamplesPostgresIT.FakeConfig.class})
class AgentMediaSamplesPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE = storage();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE::toString);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaToolRunner mediaTools;
    @Autowired CanvasService canvas;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired AgentTurnWorker worker;
    @Autowired AgentImageInputService inputs;
    @Autowired LlmProtocolCodec codec;
    @Autowired FakeGateway gateway;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @Test void committedSamplesCheckpointOnlyReferencesHydrateOutsideTransactionsAndReauthorizeOnReplay() throws Exception {
        var owner = identities.setup("sample-admin", "synthetic-password-123");
        var project = projects.create(owner.userId(), "Synthetic samples", Project.AspectRatio.LANDSCAPE_16_9);
        Path source = Files.createTempFile(STORAGE, "source-", ".mp4");
        UUID assetId;
        try {
            mediaTools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=12:duration=2",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-shortest", "-y", source.toString()));
            try (var bytes = Files.newInputStream(source)) { assetId = assets.archiveVideo(owner.userId(), project.id(), bytes).id(); }
        } finally { Files.deleteIfExists(source); }
        var artifact = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO, "Exact sample source",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", assetId.toString()));
        UUID item = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifact.artifact().id());
        gateway.version = artifact.resourceDefaultVersion().id();
        var agent = configureDirector(agents, owner.userId(), project.id(), "Sample reader", "Inspect the exact requested samples");
        var run = runs.create(owner.userId(), project.id(), agent.id(), "Inspect this selected clip", "synthetic-sample-run").run();
        assertThat(worker.runOnce("sample-worker")).isEqualTo(1);
        assertThat(toolCount(run.id())).isZero(); // Two inspection reads roll back the complete batch.
        assertThat(worker.runOnce("sample-worker")).isEqualTo(1);
        assertThat(toolCount(run.id())).isEqualTo(1);
        assertThat(worker.runOnce("sample-worker")).isEqualTo(1);
        var completed = runs.get(owner.userId(), project.id(), run.id());
        assertThat(completed.status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(gateway.calls).hasValue(3);
        var checkpoints = jdbc.sql("select request_json::text from llm_turn where run_id=:run order by step_index")
                .param("run", run.id()).query(String.class).list();
        assertThat(checkpoints).hasSize(3);
        assertThat(checkpoints.getLast()).contains(AgentMediaSampleService.METADATA_KEY, gateway.version.toString(), "timestampsMs", "audioDurationMs");
        String audioBase64 = Base64.getEncoder().encodeToString(gateway.audio);
        assertThat(checkpoints).allSatisfy(value -> assertThat(value).doesNotContain(audioBase64, "agenvas-agent-sample-", STORAGE.toString()));
        var toolResults = jdbc.sql("select result_json::text from tool_execution where run_id=:run")
                .param("run", run.id()).query(String.class).list();
        assertThat(toolResults).hasSize(1);
        assertThat(toolResults.getFirst()).contains("sampleRequested", gateway.version.toString())
                .doesNotContain(audioBase64, "agenvas-agent-sample-", STORAGE.toString());

        var savedMessages = codec.requestMessages(mapper.readTree(checkpoints.getLast()));
        var replay = inputs.hydrate(owner.userId(), project.id(), run.id(), completed.contextSnapshot(), savedMessages);
        assertThat(replay.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .flatMap(message -> message.getMedia().stream())).hasSize(2);
        var current = canvas.list(owner.userId(), project.id()).stream().map(CanvasService.CanvasEntry::item)
                .filter(candidate -> candidate.id().equals(item)).findFirst().orElseThrow();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.Remove(item, current.version())));
        assertThatThrownBy(() -> inputs.hydrate(owner.userId(), project.id(), run.id(), completed.contextSnapshot(), savedMessages))
                .isInstanceOf(ApiProblemException.class).satisfies(failure -> assertThat(((ApiProblemException) failure).code()).isEqualTo("INPUT_SCOPE_DENIED"));
        assertThat(toolCount(run.id())).isEqualTo(1);
    }

    private long toolCount(UUID run) {
        return jdbc.sql("select count(*) from tool_execution where run_id=:run").param("run", run).query(Long.class).single();
    }
    @TestConfiguration static class FakeConfig {
        @Bean @Primary FakeGateway mediaSamplesGateway() { return new FakeGateway(); }
    }
    static class FakeGateway implements ChatGateway {
        final AtomicInteger calls = new AtomicInteger();
        UUID version;
        byte[] audio;
        @Override public int configVersion() { return 1; }
        @Override public String configSource() { return "test-fake"; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            int step = calls.incrementAndGet();
            var media = messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .flatMap(message -> message.getMedia().stream()).toList();
            assertThat(messages).allSatisfy(message -> assertThat(message.getMetadata()).doesNotContainKey(AgentMediaSampleService.METADATA_KEY));
            assertThat(tools.stream().map(tool -> tool.getToolDefinition().name())).contains("read_media_sample");
            if (step < 3) assertThat(media).isEmpty();
            else {
                assertThat(media).hasSize(2);
                assertThat(media.getFirst().getMimeType().toString()).isEqualTo("image/png");
                try {
                    var sheet = ImageIO.read(new ByteArrayInputStream(media.getFirst().getDataAsByteArray()));
                    assertThat(sheet.getWidth()).isEqualTo(1024);
                    assertThat(sheet.getHeight()).isEqualTo(540);
                } catch (IOException failure) { throw new IllegalStateException(failure); }
                assertThat(media.getLast().getMimeType().toString()).isEqualTo("audio/wav");
                audio = media.getLast().getDataAsByteArray();
                assertThat(audio).hasSizeBetween(16000, 17000);
                assertThat(new String(audio, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
            }
            AssistantMessage answer;
            if (step < 3) {
                String arguments = "{\"versionId\":\"" + version + "\",\"timestampsMs\":[0,1000],\"audioStartMs\":0,\"audioDurationMs\":500}";
                var call = new AssistantMessage.ToolCall("sample-" + step, "function", "read_media_sample", arguments);
                answer = AssistantMessage.builder().content("").toolCalls(step == 1 ? List.of(call,
                        new AssistantMessage.ToolCall("sample-too-many", "function", "read_media_sample", arguments)) : List.of(call)).build();
            } else answer = new AssistantMessage("已记录这两个时间点和短音频样本的观察。");
            return new Exchange(1, new ChatResponse(List.of(new Generation(answer))));
        }
    }
    private static Path storage() {
        try { return Files.createTempDirectory("agenvas-synthetic-agent-samples-"); }
        catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @AfterAll static void cleanup() throws IOException {
        try (var paths = Files.walk(STORAGE)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
