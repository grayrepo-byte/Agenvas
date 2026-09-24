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
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.ImageAssetFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL proof that a storyboard batch has pinned, scoped references and atomic writes. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=storyboard-tools-integration-secret")
class StoryboardToolsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private CanvasService canvas;
    @Autowired private ToolRegistry registry;
    @Autowired private LlmProtocolCodec codec;
    @Autowired private LlmTurnCheckpointService checkpoints;
    @Autowired private ToolExecutionService tools;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;

    @Test
    void characterSceneAndThreeShotsCommitWithExactVersionsAndInvalidBatchRollsBack() {
        AdminPrincipal owner = identities.setup("storyboard-tools-integration-secret",
                "story-admin", "story-password-123");
        Project project = projects.create(owner.userId(), "Storyboard",
                Project.AspectRatio.LANDSCAPE_16_9);
        ObjectNode imageContent = mapper.createObjectNode();
        imageContent.put("assetId", ImageAssetFixture.archive(assets, owner.userId(),
                project.id()).toString());
        imageContent.put("prompt", "Reference portrait fixture");
        imageContent.put("providerConfigVersion", 1);
        imageContent.put("workflowVersion", "mock-v1");
        imageContent.putObject("parameters");
        imageContent.put("sourceTaskId", UUID.randomUUID().toString());
        ArtifactService.ArtifactView image = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.IMAGE, "Reference portrait", imageContent);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of(new AgentInstanceService.BindingInput(image.artifact().id(),
                        image.currentVersion().id())));
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Make a three-shot story", "storyboard-run").run();
        AgentRun run = runs.transition(owner.userId(), project.id(), queued.id(), 0,
                AgentRun.Status.RUNNING);
        TrustedToolContext context = new TrustedToolContext(owner.userId(), project.id(), run.id());

        ObjectNode character = mapper.createObjectNode();
        character.put("name", "Ari");
        character.put("description", "Explorer");
        character.put("appearance", "Blue coat");
        character.putArray("referenceVersionIds")
                .add(image.currentVersion().id().toString());
        saveCall(context, 0, "call-character", "create_character", character);
        JsonNode characterResult = tools.execute(context, 0, "call-character");
        UUID characterId = UUID.fromString(characterResult.at("/createdIds/0").asText());
        UUID characterVersionId = UUID.fromString(characterResult.path("affectedVersions")
                .path(characterId.toString()).asText());
        assertThat(artifacts.get(owner.userId(), project.id(), characterId)
                .currentVersion().inputReferences())
                .extracting(ArtifactVersion.InputReference::versionId)
                .containsExactly(image.currentVersion().id());

        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "Station");
        scene.put("location", "Mountain station");
        scene.put("timeOfDay", "Dawn");
        scene.put("lighting", "Soft");
        scene.put("style", "Cinematic");
        scene.putArray("referenceVersionIds");
        saveCall(context, 1, "call-scene", "create_scene", scene);
        JsonNode sceneResult = tools.execute(context, 1, "call-scene");
        UUID sceneId = UUID.fromString(sceneResult.at("/createdIds/0").asText());
        UUID sceneVersionId = UUID.fromString(sceneResult.path("affectedVersions")
                .path(sceneId.toString()).asText());

        ObjectNode shotBatch = batch(characterVersionId, sceneVersionId);
        saveCall(context, 2, "call-shots", "create_shots", shotBatch);
        JsonNode result = tools.execute(context, 2, "call-shots");
        assertThat(result.path("createdIds")).hasSize(3);
        for (int index = 0; index < 3; index++) {
            UUID shotId = UUID.fromString(result.path("createdIds").get(index).asText());
            ArtifactService.ArtifactView shot = artifacts.get(owner.userId(), project.id(), shotId);
            assertThat(shot.artifact().kind()).isEqualTo(Artifact.Kind.SHOT);
            assertThat(shot.currentVersion().content().path("order").intValue()).isEqualTo(index + 1);
            assertThat(shot.currentVersion().createdByKind())
                    .isEqualTo(ArtifactVersion.CreatedByKind.AGENT);
            assertThat(shot.currentVersion().inputReferences())
                    .extracting(ArtifactVersion.InputReference::versionId)
                    .containsExactlyInAnyOrder(characterVersionId, sceneVersionId);
        }
        assertThat(tools.execute(context, 2, "call-shots")).isEqualTo(result);
        assertThat(countArtifacts(project.id(), "SHOT")).isEqualTo(3);
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(5)
                .allSatisfy(entry -> assertThat(entry.item().groupId().toString())
                        .isEqualTo(run.contextSnapshot().path("outputGroupId").asText()));

        ObjectNode unboundScene = mapper.createObjectNode();
        unboundScene.put("name", "Private");
        unboundScene.put("location", "Other");
        unboundScene.put("timeOfDay", "Night");
        unboundScene.put("lighting", "Dark");
        unboundScene.put("style", "Quiet");
        unboundScene.putArray("referenceVersionIds");
        UUID unboundVersionId = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.SCENE, "Private", unboundScene).currentVersion().id();
        ObjectNode invalidBatch = batch(characterVersionId, sceneVersionId);
        ((ObjectNode) invalidBatch.path("shots").get(1))
                .put("sceneVersionId", unboundVersionId.toString());
        saveCall(context, 3, "call-invalid", "create_shots", invalidBatch);
        assertThatThrownBy(() -> tools.execute(context, 3, "call-invalid"))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code())
                        .isEqualTo("INPUT_SCOPE_DENIED"));
        assertThat(countArtifacts(project.id(), "SHOT")).isEqualTo(3);
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(5);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(3);
    }

    private ObjectNode batch(UUID characterVersionId, UUID sceneVersionId) {
        ObjectNode batch = mapper.createObjectNode();
        ArrayNode shots = batch.putArray("shots");
        for (int index = 1; index <= 3; index++) {
            ObjectNode shot = shots.addObject();
            shot.put("title", "Shot " + index);
            shot.put("order", index);
            shot.put("durationMs", 1_000);
            shot.put("description", "Action " + index);
            shot.put("camera", "Wide");
            shot.put("action", "Walk");
            shot.putArray("characterVersionIds").add(characterVersionId.toString());
            shot.put("sceneVersionId", sceneVersionId.toString());
        }
        return batch;
    }

    private void saveCall(TrustedToolContext context, int stepIndex,
            String callId, String name, JsonNode arguments) {
        checkpoints.reserve(context.ownerId(), context.projectId(), context.runId(), stepIndex, 1, "mock",
                codec.request(List.of(new UserMessage("Create story")), registry.modelDefinitions()));
        AssistantMessage output = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", name,
                        arguments.toString()))).build();
        checkpoints.saveResponse(context.ownerId(), context.projectId(), context.runId(),
                stepIndex, 1, codec.response(new ChatResponse(List.of(new Generation(output)))));
    }

    private long countArtifacts(UUID projectId, String kind) {
        return jdbc.sql("select count(*) from artifact where project_id = :projectId and kind = :kind")
                .param("projectId", projectId).param("kind", kind).query(Long.class).single();
    }
}
