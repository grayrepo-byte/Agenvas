package dev.agenvas.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and synthetic PNGs: node results remain independent of library defaults. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=media-input-integration-secret", "agenvas.llm.mode=mock",
        "agenvas.llm.scheduler-enabled=false", "agenvas.tasks.scheduler-enabled=false"})
class AgentRunMediaInputsPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired CanvasConnectionService connections;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired TaskService tasks;
    @Autowired ObjectMapper mapper;

    @Test void boundNodeResultAndSelectedEmptyMediaDoNotRequireLibraryDefaults() {
        var owner = identities.setup("media-input-integration-secret", "media-input-admin", "synthetic-password-123");
        var project = projects.create(owner.userId(), "Media input regression", Project.AspectRatio.SQUARE_1_1);
        var media = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Node image", null);
        UUID imageNode = UUID.randomUUID();
        UUID emptyNode = UUID.randomUUID();
        var empty = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Empty draft", null);
        canvas.apply(owner.userId(), project.id(), List.of(place(imageNode, media.artifact().id()), place(emptyNode, empty.artifact().id())));
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var uploaded = canvas.uploadVersion(owner.userId(), project.id(), imageNode, imageNode, 0,
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", assetId.toString()), null, null);
        UUID nodeVersion = uploaded.selectedVersion().id();
        assertThat(artifacts.get(owner.userId(), project.id(), media.artifact().id()).resourceDefaultVersion()).isNull();
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Use the bound reference", List.of());
        UUID agentNode = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceAgent(agentNode, agent.id(),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("460"), new BigDecimal("600"), 0, null, false)));
        agent = connections.connect(owner.userId(), project.id(), imageNode, agentNode, nodeVersion,
                CanvasConnection.RelationType.AGENT_IMAGE_INPUT, null, agent.version()).agent();
        var preflight = runs.preflight(owner.userId(), project.id(), agent.id());
        assertThat(preflight.bindings()).singleElement().satisfies(binding -> assertThat(binding.selectedVersionId()).isEqualTo(nodeVersion));
        var bound = runs.create(owner.userId(), project.id(), agent.id(), "Use the image", "bound-media").run();
        assertThat(bound.contextSnapshot().at("/bindings/0/selectedVersionId").asText()).isEqualTo(nodeVersion.toString());
        assertThat(bound.contextSnapshot().at("/bindings/0").has("expectedVersion")).isFalse();
        assertThat(tasks.listByRun(owner.userId(), project.id(), bound.id())).singleElement()
                .satisfies(task -> assertThat(task.kind()).isEqualTo(Task.Kind.AGENT_TURN));
        runs.cancel(owner.userId(), project.id(), bound.id());
        var selectionOnly = agents.create(owner.userId(), project.id(), "Selection", "Read the selection", List.of());
        var selected = runs.create(owner.userId(), project.id(), selectionOnly.id(), "Read selection", "selected-media",
                null, List.of(imageNode, emptyNode)).run();
        assertThat(selected.contextSnapshot().at("/selection/0/versionId").asText()).isEqualTo(nodeVersion.toString());
        assertThat(selected.contextSnapshot().at("/selection/1/kind").asText()).isEqualTo("IMAGE");
        assertThat(selected.contextSnapshot().at("/selection/1").has("versionId")).isFalse();
        assertThat(artifacts.get(owner.userId(), project.id(), media.artifact().id()).resourceDefaultVersion()).isNull();
    }

    private static CanvasService.PlaceArtifact place(UUID nodeId, UUID artifactId) {
        return new CanvasService.PlaceArtifact(nodeId, artifactId, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("320"), new BigDecimal("240"), 0, null, false);
    }
}
