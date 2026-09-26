package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
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

/** Same-conversation output inheritance preserves exact versions, owner scope and manual-edit CAS. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=conversation-artifact-secret", "agenvas.llm.mode=mock"})
class ConversationArtifactInputsPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ArtifactService artifacts;
    @Autowired private ObjectMapper mapper;

    @Test
    void inheritsSelectedConversationOutputsButNeverForeignOrManuallyReplacedVersions() throws Exception {
        var owner = identities.setup("conversation-artifact-secret", "conversation-artifact-admin",
                "conversation-artifact-password");
        var project = projects.create(owner.userId(), "Conversation inputs", Project.AspectRatio.SQUARE_1_1);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create text", List.of());
        var first = runs.create(owner.userId(), project.id(), agent.id(), "Write a scene", "first").run();
        var editable = artifacts.createFromAgent(owner.userId(), project.id(), first.id(), Artifact.Kind.TEXT,
                "Scene", mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"First draft\"}"));
        var editedByUser = artifacts.createFromAgent(owner.userId(), project.id(), first.id(), Artifact.Kind.TEXT,
                "Another scene", mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Old draft\"}"));
        var manual = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, "Private manual input",
                mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Do not inherit\"}"));
        var foreignProject = projects.create(owner.userId(), "Foreign", Project.AspectRatio.SQUARE_1_1);
        var foreignAgent = agents.create(owner.userId(), foreignProject.id(), "Foreign", "Create text", List.of());
        var foreignRun = runs.create(owner.userId(), foreignProject.id(), foreignAgent.id(), "Write", "foreign").run();
        artifacts.createFromAgent(owner.userId(), foreignProject.id(), foreignRun.id(), Artifact.Kind.TEXT,
                "Foreign", mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Foreign scope\"}"));

        var inputs = artifacts.conversationInputs(owner.userId(), project.id(), List.of(first.id(), foreignRun.id()));
        assertThat(inputs).extracting(ArtifactService.ConversationInput::artifactId)
                .containsExactlyInAnyOrder(editable.artifact().id(), editedByUser.artifact().id())
                .doesNotContain(manual.artifact().id());
        assertThat(inputs).allSatisfy(input -> assertThat(input.expectedVersion()).isZero());
        assertThat(artifacts.conversationInputs(owner.userId(), project.id(), List.of())).isEmpty();
        assertThatThrownBy(() -> artifacts.conversationInputs(UUID.randomUUID(), project.id(), List.of(first.id())))
                .isInstanceOf(ApiProblemException.class);

        runs.cancel(owner.userId(), project.id(), first.id());
        var second = runs.create(owner.userId(), project.id(), agent.id(), "Continue that scene", "second").run();
        assertThat(artifacts.requireAgentVisibleVersion(owner.userId(), project.id(), second.id(),
                editable.currentVersion().id(), second.contextSnapshot()).id()).isEqualTo(editable.currentVersion().id());
        var revised = artifacts.reviseFromAgent(owner.userId(), project.id(), second.id(), second.contextSnapshot(),
                editable.artifact().id(), editable.artifact().version(), "Scene continued",
                mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Continued draft\"}"));
        assertThat(revised.currentVersion().runId()).isEqualTo(second.id());

        var userRevision = artifacts.revise(owner.userId(), project.id(), editedByUser.artifact().id(),
                editedByUser.artifact().version(), "User changed scene",
                mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"User decision\"}"));
        assertThat(artifacts.conversationInputs(owner.userId(), project.id(), List.of(first.id(), second.id())))
                .extracting(ArtifactService.ConversationInput::artifactId).containsExactly(editable.artifact().id());
        assertThatThrownBy(() -> artifacts.requireAgentVisibleVersion(owner.userId(), project.id(), second.id(),
                userRevision.currentVersion().id(), second.contextSnapshot())).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> artifacts.reviseFromAgent(owner.userId(), project.id(), second.id(), second.contextSnapshot(),
                editedByUser.artifact().id(), userRevision.artifact().version(), "Unsafe overwrite",
                mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Do not overwrite\"}")))
                .isInstanceOf(ApiProblemException.class);
        assertThat(artifacts.get(owner.userId(), project.id(), editedByUser.artifact().id()).currentVersion().id())
                .isEqualTo(userRevision.currentVersion().id());
    }
}
