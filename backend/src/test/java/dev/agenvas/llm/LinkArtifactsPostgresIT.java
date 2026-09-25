package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.CreativeArtifactToolService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL proof that semantic links are immutable content revisions, not execution. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, LinkArtifactsPostgresIT.FakeConfig.class},
        properties = "agenvas.identity.bootstrap-secret=link-artifacts-integration-secret")
class LinkArtifactsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = storageRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AssetService assets;
    @Autowired private ArtifactService artifacts;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private AgentTurnWorker worker;
    @Autowired private CreativeArtifactToolService links;
    @Autowired private FakeGateway gateway;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void linksFourTypedRolesWithoutGeneratingOrDuplicatingWork() throws Exception {
        AdminPrincipal owner = identities.setup("link-artifacts-integration-secret",
                "link-admin", "link-password-123");
        Project project = projects.create(owner.userId(), "Link project",
                Project.AspectRatio.LANDSCAPE_16_9);
        UUID assetId = assets.archiveImage(owner.userId(), project.id(),
                new ByteArrayInputStream(tinyPng())).id();
        ObjectNode imageContent = mapper.createObjectNode();
        imageContent.put("sourceType", "UPLOAD");
        imageContent.put("assetId", assetId.toString());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Reference", imageContent);
        var character = artifacts.create(owner.userId(), project.id(), Artifact.Kind.CHARACTER,
                "Hero", characterContent("Hero"));
        var secondCharacter = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.CHARACTER, "Friend", characterContent("Friend"));
        var hiddenCharacter = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.CHARACTER, "Hidden", characterContent("Hidden"));
        var scene = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SCENE,
                "Studio", sceneContent("Studio"));
        var secondScene = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SCENE,
                "Street", sceneContent("Street"));
        var shot = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SHOT,
                "Opening", shotContent(scene.currentVersion().id()));
        List<ArtifactService.ArtifactView> visible = List.of(image, character,
                secondCharacter, scene, secondScene, shot);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Linker",
                "Link only authorized semantic inputs",
                visible.stream().map(view -> new AgentInstanceService.BindingInput(
                        view.artifact().id(), view.currentVersion().id())).toList());
        gateway.shotId = shot.artifact().id();
        gateway.characterVersionId = character.currentVersion().id();
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Connect the storyboard references", "link-run").run();
        TrustedToolContext context = new TrustedToolContext(owner.userId(), project.id(),
                run.id());

        assertThat(worker.runOnce("link-worker")).isEqualTo(1);
        assertThat(worker.runOnce("link-worker")).isEqualTo(1);
        assertThat(artifacts.listVersions(owner.userId(), project.id(), shot.artifact().id()))
                .hasSize(2);
        var linkedShot = artifacts.get(owner.userId(), project.id(), shot.artifact().id());
        assertThat(linkedShot.currentVersion().content().path("characterVersionIds")
                .get(0).asText()).isEqualTo(character.currentVersion().id().toString());
        assertThat(linkedShot.currentVersion().createdByKind())
                .isEqualTo(ArtifactVersion.CreatedByKind.AGENT);
        assertThat(linkedShot.currentVersion().runId()).isEqualTo(run.id());
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from task where run_id = :runId "
                        + "and kind <> 'AGENT_TURN'")
                .param("runId", run.id()).query(Long.class).single()).isZero();

        var appended = links.linkArtifacts(context, run, UUID.randomUUID(),
                linkRequest(shot.artifact().id(), 1, secondCharacter.currentVersion().id(),
                        "SHOT_CHARACTER"));
        assertThat(appended.path("updatedIds")).hasSize(1);
        assertThat(artifacts.listVersions(owner.userId(), project.id(), shot.artifact().id()))
                .hasSize(3);
        var replacedScene = links.linkArtifacts(context, run, UUID.randomUUID(),
                linkRequest(shot.artifact().id(), 2, secondScene.currentVersion().id(),
                        "SHOT_SCENE"));
        assertThat(replacedScene.path("updatedIds")).hasSize(1);
        assertThat(artifacts.get(owner.userId(), project.id(), shot.artifact().id())
                .currentVersion().content().path("sceneVersionId").asText())
                .isEqualTo(secondScene.currentVersion().id().toString());
        links.linkArtifacts(context, run, UUID.randomUUID(),
                linkRequest(character.artifact().id(), 0, image.currentVersion().id(),
                        "CHARACTER_REFERENCE_IMAGE"));
        links.linkArtifacts(context, run, UUID.randomUUID(),
                linkRequest(scene.artifact().id(), 0, image.currentVersion().id(),
                        "SCENE_REFERENCE_IMAGE"));
        assertThat(artifacts.get(owner.userId(), project.id(), character.artifact().id())
                .currentVersion().content().path("referenceVersionIds").get(0).asText())
                .isEqualTo(image.currentVersion().id().toString());
        assertThat(artifacts.get(owner.userId(), project.id(), scene.artifact().id())
                .currentVersion().content().path("referenceVersionIds").get(0).asText())
                .isEqualTo(image.currentVersion().id().toString());
        assertThatThrownBy(() -> links.linkArtifacts(context, run, UUID.randomUUID(),
                linkRequest(shot.artifact().id(), 3, hiddenCharacter.currentVersion().id(),
                        "SHOT_CHARACTER")))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("INPUT_SCOPE_DENIED");
        assertThatThrownBy(() -> links.linkArtifacts(context, run, UUID.randomUUID(),
                linkRequest(shot.artifact().id(), 3, image.currentVersion().id(),
                        "SHOT_CHARACTER")))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("TOOL_ARGUMENT_INVALID");
        assertThatThrownBy(() -> links.linkArtifacts(context, run, UUID.randomUUID(),
                linkRequest(shot.artifact().id(), 0, character.currentVersion().id(),
                        "SHOT_CHARACTER")))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("ARTIFACT_VERSION_CONFLICT");
    }

    private ObjectNode characterContent(String name) {
        ObjectNode content = mapper.createObjectNode();
        content.put("name", name);
        content.put("description", name + " description");
        content.put("appearance", "Plain");
        content.putArray("referenceVersionIds");
        return content;
    }

    private ObjectNode sceneContent(String name) {
        ObjectNode content = mapper.createObjectNode();
        content.put("name", name);
        content.put("location", name);
        content.put("timeOfDay", "Day");
        content.put("lighting", "Soft");
        content.put("style", "Minimal");
        content.putArray("referenceVersionIds");
        return content;
    }

    private ObjectNode shotContent(UUID sceneVersionId) {
        ObjectNode content = mapper.createObjectNode();
        content.put("order", 1);
        content.put("durationSeconds", 5);
        content.put("description", "Opening");
        content.put("camera", "Wide");
        content.put("action", "Introduce cast");
        content.putArray("characterVersionIds");
        content.put("sceneVersionId", sceneVersionId.toString());
        return content;
    }

    private String linkRequest(UUID sourceId, long expectedVersion, UUID targetVersionId,
            String relationship) {
        ObjectNode input = mapper.createObjectNode();
        input.put("sourceArtifactId", sourceId.toString());
        input.put("expectedVersion", expectedVersion);
        input.put("targetVersionId", targetVersionId.toString());
        input.put("relationship", relationship);
        return input.toString();
    }

    private static byte[] tinyPng() throws Exception {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        }
    }

    private static Path storageRoot() {
        try {
            return Files.createTempDirectory("agenvas-link-it-");
        } catch (java.io.IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway(ObjectMapper mapper) {
            return new FakeGateway(mapper);
        }
    }

    /** Uses a persisted link result for the next CAS token and repeats the same relation. */
    static class FakeGateway implements ChatGateway {
        private final ObjectMapper mapper;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile UUID shotId;
        private volatile UUID characterVersionId;

        FakeGateway(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String configSource() { return "test-fake"; }

        @Override
        public int configVersion() { return 1; }

        @Override
        public Capabilities capabilities() { return new Capabilities(true, false, false); }

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            assertThat(tools.stream().map(tool -> tool.getToolDefinition().name()))
                    .contains("link_artifacts");
            int turn = calls.incrementAndGet();
            long expectedVersion = 0;
            if (turn == 2) {
                ToolResponseMessage reply = (ToolResponseMessage) messages.getLast();
                var result = mapper.readTree(reply.getResponses().getFirst().responseData());
                expectedVersion = result.path("artifactVersions")
                        .path(shotId.toString()).longValue();
                assertThat(expectedVersion).isEqualTo(1);
            }
            ObjectNode input = mapper.createObjectNode();
            input.put("sourceArtifactId", shotId.toString());
            input.put("expectedVersion", expectedVersion);
            input.put("targetVersionId", characterVersionId.toString());
            input.put("relationship", "SHOT_CHARACTER");
            AssistantMessage response = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("link-" + turn,
                            "function", "link_artifacts", input.toString()))).build();
            return new Exchange(1, new ChatResponse(List.of(new Generation(response))));
        }
    }
}
