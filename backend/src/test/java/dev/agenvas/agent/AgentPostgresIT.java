package dev.agenvas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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

/** PostgreSQL evidence for Agent card configuration, exact inputs, and canvas projection. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=agent-integration-bootstrap-secret")
class AgentPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private IdentityService identityService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private AgentInstanceService agentService;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void explicitBindingsAreVersionPinnedScopedAndVisibleOnTheCanvas() {
        AdminPrincipal owner = identityService.setup(
                "agent-integration-bootstrap-secret", "agent-admin", "agent-password-123");
        Project project = projectService.create(
                owner.userId(), "Agent project", Project.AspectRatio.LANDSCAPE_16_9);
        Project otherProject = projectService.create(
                owner.userId(), "Other project", Project.AspectRatio.SQUARE_1_1);
        ArtifactService.ArtifactView brief = createText(owner.userId(), project.id(), "Brief");
        ArtifactService.ArtifactView second = createText(owner.userId(), project.id(), "Second");
        ArtifactService.ArtifactView foreign =
                createText(owner.userId(), otherProject.id(), "Foreign");

        AgentInstance created = agentService.create(
                owner.userId(), project.id(), "Creator", "Create three shots", List.of());
        assertThat(created.bindings()).isEmpty();
        assertThat(agentService.list(owner.userId(), project.id())).containsExactly(created);

        AgentInstance updated = agentService.update(
                owner.userId(),
                project.id(),
                created.id(),
                0,
                "Storyboard Creator",
                "Use only the pinned brief",
                List.of(new AgentInstanceService.BindingInput(
                        brief.artifact().id(), brief.currentVersion().id())));
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.outputGroupId()).isEqualTo(created.outputGroupId());
        assertThat(updated.bindings())
                .extracting(AgentInstance.Binding::selectedVersionId)
                .containsExactly(brief.currentVersion().id());

        assertProblem(
                "AGENT_VERSION_CONFLICT",
                () -> agentService.update(
                        owner.userId(),
                        project.id(),
                        created.id(),
                        0,
                        "Stale",
                        "Must fail",
                        List.of()));
        assertProblem(
                "RESOURCE_NOT_FOUND",
                () -> agentService.update(
                        owner.userId(),
                        project.id(),
                        created.id(),
                        1,
                        updated.name(),
                        updated.instruction(),
                        List.of(new AgentInstanceService.BindingInput(
                                brief.artifact().id(), second.currentVersion().id()))));
        assertProblem(
                "RESOURCE_NOT_FOUND",
                () -> agentService.update(
                        owner.userId(),
                        project.id(),
                        created.id(),
                        1,
                        updated.name(),
                        updated.instruction(),
                        List.of(new AgentInstanceService.BindingInput(
                                foreign.artifact().id(), foreign.currentVersion().id()))));
        assertProblem(
                "RESOURCE_NOT_FOUND",
                () -> agentService.get(UUID.randomUUID(), project.id(), created.id()));

        UUID itemId = UUID.randomUUID();
        CanvasService.PlaceAgent placement = new CanvasService.PlaceAgent(
                itemId,
                created.id(),
                new BigDecimal("50"),
                new BigDecimal("60"),
                new BigDecimal("340"),
                new BigDecimal("320"),
                2,
                null,
                false);
        canvasService.apply(owner.userId(), project.id(), List.of(placement));
        canvasService.apply(owner.userId(), project.id(), List.of(placement));
        CanvasService.CanvasEntry entry = canvasService.list(owner.userId(), project.id()).getFirst();
        assertThat(entry.item().id()).isEqualTo(itemId);
        assertThat(entry.artifact()).isNull();
        assertThat(entry.agent().id()).isEqualTo(created.id());
        assertThat(entry.agent().bindings()).hasSize(1);

        List<String> columns = jdbcClient.sql("""
                        select column_name
                        from information_schema.columns
                        where table_schema = 'public' and table_name = 'agent_instance'
                        """)
                .query(String.class)
                .list();
        assertThat(columns)
                .doesNotContain("user_id", "thread_id", "current_user_id", "run_context");
        assertThat(jdbcClient.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                        .query(String.class)
                        .single())
                .isEqualTo("35");
    }

    private ArtifactService.ArtifactView createText(UUID ownerId, UUID projectId, String text) {
        return artifactService.create(
                ownerId,
                projectId,
                Artifact.Kind.TEXT,
                text,
                objectMapper.readTree(
                        "{\"format\":\"PLAIN_TEXT\",\"text\":\"" + text + "\"}"));
    }

    private void assertProblem(String expectedCode, Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected ApiProblemException with code " + expectedCode);
        } catch (ApiProblemException problem) {
            assertThat(problem.code()).isEqualTo(expectedCode);
        }
    }
}
