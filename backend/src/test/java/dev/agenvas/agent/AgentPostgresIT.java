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
import dev.agenvas.testing.MigrationVersions;
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
        classes = AgenvasApplication.class)
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

    @Autowired private org.springframework.web.context.WebApplicationContext webContext;

    @Test
    void assistantConfigurationIsIndependentOfCanvasAndKeepsItsIdentityAcrossCasEdits() throws Exception {
        AdminPrincipal owner = identityService.setup("agent-admin", "agent-password-123");
        Project project = projectService.create(owner.userId(), "Assistant project", Project.AspectRatio.LANDSCAPE_16_9);
        Project other = projectService.create(owner.userId(), "Other project", Project.AspectRatio.SQUARE_1_1);
        var creation = agentService.createIdempotent(owner.userId(), project.id(), "Creator", "Create three shots", "assistant-create");
        AgentInstance created = creation.agent();
        assertThat(agentService.list(owner.userId(), project.id())).containsExactly(created);
        assertThat(canvasService.list(owner.userId(), project.id())).isEmpty();
        AgentInstance updated = agentService.update(owner.userId(), project.id(), created.id(), 0,
                "Storyboard Creator", "Use the current canvas");
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(agentService.createIdempotent(owner.userId(), project.id(), "Creator", "Create three shots", "assistant-create").agent())
                .isEqualTo(created);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String path = "/api/v1/projects/" + project.id() + "/agents";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(auth))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].bindings").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].outputGroupId").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(auth)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"Old client\",\"instruction\":\"Use old bindings\",\"bindings\":[]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path + "/" + created.id()).with(auth)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"name\":\"Old client\",\"instruction\":\"Use old bindings\",\"bindings\":[]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThat(agentService.get(owner.userId(), project.id(), created.id())).isEqualTo(updated);
        assertThat(agentService.list(owner.userId(), project.id())).hasSize(1);

        assertProblem("AGENT_VERSION_CONFLICT", () -> agentService.update(owner.userId(), project.id(), created.id(),
                0, "Stale", "Must fail"));
        assertProblem("RESOURCE_NOT_FOUND", () -> agentService.get(UUID.randomUUID(), project.id(), created.id()));
        assertProblem("RESOURCE_NOT_FOUND", () -> agentService.get(owner.userId(), other.id(), created.id()));
        assertThat(jdbcClient.sql("select column_name from information_schema.columns where table_schema = 'public' and table_name = 'agent_instance'")
                .query(String.class).list()).doesNotContain("output_group_id", "user_id", "thread_id", "current_user_id", "run_context");
        assertThat(jdbcClient.sql("select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'agent_binding'")
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                .query(String.class).single()).isEqualTo(MigrationVersions.latest());
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
