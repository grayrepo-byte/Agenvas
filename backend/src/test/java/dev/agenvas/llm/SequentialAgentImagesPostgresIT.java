package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentImageInputService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.testing.AgentImageInputFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Actual PostgreSQL, synthetic archived PNGs and fake LLM; no external Provider calls. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, SequentialAgentImagesPostgresIT.FakeConfig.class})
class SequentialAgentImagesPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE = temporaryRoot();
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
    @Autowired AgentInstanceService agents;
    @Autowired CanvasService canvas;
    @Autowired CanvasConnectionService connections;
    @Autowired AgentRunService runs;
    @Autowired AgentTurnWorker worker;
    @Autowired AgentImageInputService images;
    @Autowired LlmProtocolCodec codec;
    @Autowired FakeGateway gateway;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @Test void rejectsMultiImageBatchesAtomicallyThenObservesNineImagesSequentiallyAndRestoresTheCurrentPreview() throws Exception {
        var owner = identities.setup("sequential-admin", "synthetic-password-123");
        var project = projects.create(owner.userId(), "Sequential image fixture", Project.AspectRatio.LANDSCAPE_16_9);
        var versions = new ArrayList<ArtifactService.ArtifactView>();
        for (int index = 0; index < 9; index++) {
            var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, index * 1024);
            var png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            var asset = assets.archiveImage(owner.userId(), project.id(), new ByteArrayInputStream(png.toByteArray()));
            var version = artifacts.createTemplateImport(owner.userId(), project.id(), "Synthetic image " + index, asset.id());
            versions.add(version);
            gateway.versions.add(version.resourceDefaultVersion().id());
            var preview = assets.content(owner.userId(), project.id(), asset.id(), true);
            try (var stream = assets.open(preview, 0, preview.size())) { gateway.previews.add(stream.readAllBytes()); }
        }
        var first = versions.getFirst();
        var agent = AgentImageInputFixture.connect(agents, canvas, connections, owner.userId(), project.id(),
                first.artifact().id(), first.resourceDefaultVersion().id(), "Image observer", "Record visible continuity anchors");
        UUID agentId = agent.id();
        UUID target = canvas.list(owner.userId(), project.id()).stream().map(CanvasService.CanvasEntry::item)
                .filter(item -> item.subjectId().equals(agentId)).findFirst().orElseThrow().id();
        for (var version : versions.subList(1, versions.size())) {
            UUID source = UUID.randomUUID();
            canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(source,
                    version.artifact().id(), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"), new BigDecimal("240"), 2, null, false)));
            agent = connections.connect(owner.userId(), project.id(), source, target, version.resourceDefaultVersion().id(),
                    CanvasConnection.RelationType.AGENT_IMAGE_INPUT, null, agent.version()).agent();
        }
        var run = runs.create(owner.userId(), project.id(), agent.id(), "Observe each image before reading the next", "sequential-fixture").run();
        for (int index = 0; index < 12; index++) assertThat(worker.runOnce("sequential-worker")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(gateway.calls).hasValue(12);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :id").param("id", run.id())
                .query(Long.class).single()).isEqualTo(9);
        assertThat(jdbc.sql("select count(*) from artifact where project_id = :id").param("id", project.id())
                .query(Long.class).single()).isEqualTo(9);
        var saved = mapper.readTree(jdbc.sql("select request_json::text from llm_turn where run_id = :id and step_index = 11")
                .param("id", run.id()).query(String.class).single());
        var restored = codec.requestMessages(saved);
        assertThat(restored.stream().filter(message -> message.getMetadata().containsKey(AgentImageInputService.METADATA_KEY)))
                .singleElement();
        assertThat(saved.toString()).contains("Observed exact image", gateway.versions.getFirst().toString(), gateway.versions.getLast().toString())
                .doesNotContain("base64", "image_url");
        var dispatched = images.hydrate(owner.userId(), project.id(), run.id(), run.contextSnapshot(), restored);
        assertThat(dispatched.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .flatMap(message -> message.getMedia().stream()).toList()).singleElement()
                .satisfies(media -> assertThat(media.getDataAsByteArray()).isEqualTo(gateway.previews.getLast()));
        assertThat(codec.request(restored, new dev.agenvas.llm.application.ToolRegistry().modelDefinitions(run.policySnapshot())))
                .isEqualTo(saved);
    }

    @TestConfiguration static class FakeConfig {
        @Bean @Primary FakeGateway fakeGateway() { return new FakeGateway(); }
    }
    static class FakeGateway implements ChatGateway {
        final AtomicInteger calls = new AtomicInteger();
        final List<UUID> versions = new ArrayList<>();
        final List<byte[]> previews = new ArrayList<>();
        @Override public String configSource() { return "test-fake"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context) {
            int index = calls.getAndIncrement();
            var pixels = messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .flatMap(message -> message.getMedia().stream()).toList();
            if (index < 3) assertThat(pixels).isEmpty();
            else assertThat(pixels).singleElement().satisfies(media -> assertThat(media.getDataAsByteArray()).isEqualTo(previews.get(index - 3)));
            if (index == 1 || index == 2) {
                var replies = messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                        .reduce((before, after) -> after).orElseThrow();
                assertThat(replies.getResponses()).allSatisfy(reply -> assertThat(reply.responseData()).contains("TOOL_ARGUMENT_INVALID"));
            }
            AssistantMessage assistant;
            if (index == 0) assistant = AssistantMessage.builder().content("Invalid batch fixture").toolCalls(List.of(
                    new AssistantMessage.ToolCall("create", "function", "create_text", "{\"title\":\"Must roll back\",\"text\":\"Synthetic\",\"format\":\"PLAIN_TEXT\"}"),
                    read("a", List.of(versions.get(0))), read("b", List.of(versions.get(1))))).build();
            else if (index == 1) assistant = AssistantMessage.builder().content("Invalid single call fixture")
                    .toolCalls(List.of(read("multi", versions.subList(0, 2)))).build();
            else if (index < 11) assistant = AssistantMessage.builder()
                    .content(index == 2 ? "Read first image" : "Observed exact image " + versions.get(index - 3) + ": synthetic visual anchors")
                    .toolCalls(List.of(read("sequential-" + index, List.of(versions.get(index - 2))))).build();
            else assistant = new AssistantMessage("Observed exact image " + versions.getLast() + ": all nine observations complete");
            return new Exchange(1, new ChatResponse(List.of(new Generation(assistant))));
        }
        private AssistantMessage.ToolCall read(String id, List<UUID> ids) {
            String values = ids.stream().map(value -> "\"" + value + "\"").collect(java.util.stream.Collectors.joining(","));
            return new AssistantMessage.ToolCall(id, "function", "read_artifacts", "{\"versionIds\":[" + values + "]}");
        }
    }
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-sequential-images-"); }
        catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
    }
    @AfterAll static void cleanup() throws Exception {
        try (var paths = Files.walk(STORAGE)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
}
