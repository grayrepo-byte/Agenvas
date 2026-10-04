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
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.testing.AgentImageInputFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Synthetic image and fake model; actual PostgreSQL, storage, read tools and continuation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentImageInputsPostgresIT.FakeConfig.class})
class AgentImageInputsPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = temporaryRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private AgentInstanceService agents;
    @Autowired private CanvasService canvas;
    @Autowired private CanvasConnectionService connections;
    @Autowired private AgentRunService runs;
    @Autowired private AgentTurnWorker worker;
    @Autowired private FakeGateway gateway;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void sendsOnlyRequestedImagesAfterCommittedReadsAndRetainsThemWithoutDuplicateAttachments() throws Exception {
        var owner = identities.setup("image-admin", "synthetic-password-123");
        var project = projects.create(owner.userId(), "Synthetic image project", Project.AspectRatio.LANDSCAPE_16_9);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        var asset = assets.archiveImage(owner.userId(), project.id(), new ByteArrayInputStream(png.toByteArray()));
        var artifact = artifacts.createTemplateImport(owner.userId(), project.id(), "Synthetic reference", asset.id());
        var agent = AgentImageInputFixture.connect(agents, canvas, connections, owner.userId(), project.id(),
                artifact.artifact().id(), artifact.resourceDefaultVersion().id(), "Image reader", "Read bound images");
        var unrequested = artifacts.createTemplateImport(owner.userId(), project.id(), "Unrequested reference", asset.id());
        UUID source = UUID.randomUUID();
        UUID target = canvas.list(owner.userId(), project.id()).stream().map(CanvasService.CanvasEntry::item)
                .filter(item -> item.subjectId().equals(agent.id())).findFirst().orElseThrow().id();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(source,
                unrequested.artifact().id(), BigDecimal.ZERO, new BigDecimal("300"),
                new BigDecimal("320"), new BigDecimal("240"), 2, null, false)));
        connections.connect(owner.userId(), project.id(), source, target, unrequested.resourceDefaultVersion().id(),
                CanvasConnection.RelationType.AGENT_IMAGE_INPUT, null, agent.version());
        gateway.versionId = artifact.resourceDefaultVersion().id();
        var preview = assets.content(owner.userId(), project.id(), asset.id(), true);
        try (var input = assets.open(preview, 0, preview.size())) { gateway.expectedImage = input.readAllBytes(); }
        var run = runs.create(owner.userId(), project.id(), agent.id(), "Describe this image", "synthetic-image-run").run();
        assertThat(run.policySnapshot().path("allowedTools").toString()).doesNotContain("read_skill_resource");
        assertThat(worker.runOnce("image-worker")).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :id")
                .param("id", run.id()).query(Long.class).single()).isZero();
        assertThat(worker.runOnce("image-worker")).isEqualTo(1);
        assertThat(worker.runOnce("image-worker")).isEqualTo(1);
        assertThat(worker.runOnce("image-worker")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(gateway.calls).hasValue(4);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :id")
                .param("id", run.id()).query(Long.class).single()).isEqualTo(3);
        var requests = jdbc.sql("select request_json::text from llm_turn where run_id = :id order by step_index")
                .param("id", run.id()).query(String.class).list();
        assertThat(requests).hasSize(4);
        assertThat(requests.subList(0, 2)).allSatisfy(request -> assertThat(request)
                .doesNotContain(AgentImageInputService.METADATA_KEY));
        for (String request : requests.subList(2, requests.size())) {
            assertThat(request).contains(AgentImageInputService.METADATA_KEY, gateway.versionId.toString())
                    .doesNotContain("base64", "image_url");
            assertThat(mapper.readTree(request).path("schemaVersion").asInt()).isEqualTo(1);
        }
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean @Primary FakeGateway imageGateway() { return new FakeGateway(); }
    }

    static class FakeGateway implements ChatGateway {
        final AtomicInteger calls = new AtomicInteger();
        UUID versionId;
        byte[] expectedImage;
        @Override public String configSource() { return "test-fake"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var images = messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .flatMap(message -> message.getMedia().stream()).toList();
            int step = calls.incrementAndGet();
            if (step <= 2) assertThat(images).isEmpty();
            else {
                assertThat(images).hasSize(1);
                assertThat(images.getFirst().getDataAsByteArray()).containsExactly(expectedImage);
            }
            assertThat(messages).allSatisfy(message -> assertThat(message.getMetadata())
                    .doesNotContainKey(AgentImageInputService.METADATA_KEY));
            assertThat(tools.stream().map(tool -> tool.getToolDefinition().name()))
                    .contains("read_project_summary", "read_artifacts").doesNotContain("read_skill_resource");
            AssistantMessage assistant;
            if (step == 1) {
                // A later invalid call rejects this entire batch, including the valid image read.
                assistant = AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("rejected-image", "function", "read_artifacts",
                                "{\"versionIds\":[\"" + versionId + "\"]}"),
                        new AssistantMessage.ToolCall("invalid-resource", "function", "read_skill_resource",
                                "{\"path\":\"unlisted.md\"}"))).build();
            } else if (step == 2) {
                assertThat(messages.getLast().getText()).contains("No tools from that response were applied");
                assistant = AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("summary", "function", "read_project_summary", "{}"),
                        new AssistantMessage.ToolCall("image-meta", "function", "read_artifacts",
                                "{\"versionIds\":[\"" + versionId + "\"]}"))).build();
            } else {
                var reply = messages.stream().filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast).toList().getLast();
                assertThat(reply.getResponses()).hasSize(step == 3 ? 2 : 1);
                assertThat(reply.getResponses()).allSatisfy(result -> assertThat(result.responseData())
                        .doesNotContain("TOOL_ARGUMENT_INVALID"));
                assistant = step == 3 ? AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("image-again", "function", "read_artifacts",
                                "{\"versionIds\":[\"" + versionId + "\"]}"))).build()
                        : new AssistantMessage("合成图片已收到，读取工具可用。");
            }
            return new Exchange(1, new ChatResponse(List.of(new Generation(assistant))));
        }
    }

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-synthetic-agent-images-"); }
        catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    @AfterAll
    static void cleanupSyntheticFiles() throws IOException {
        try (var paths = Files.walk(STORAGE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
