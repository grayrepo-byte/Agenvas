package dev.agenvas.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnScheduler;
import dev.agenvas.llm.application.DirectTextGenerationScheduler;
import dev.agenvas.provider.application.MediaExecutionScheduler;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.task.application.TaskRecoveryScheduler;
import dev.agenvas.testing.MigrationVersions;
import org.flywaydb.core.Flyway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A restored database remains inspectable without scheduled claims or HTTP resource writes. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.recovery-mode=true"})
class RecoveryModePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine");

    private static boolean backedUpSchemaPrepared;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        if (!backedUpSchemaPrepared) {
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                            POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            backedUpSchemaPrepared = true;
        }
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private IdentityService identities;

    @Autowired
    private ProjectService projects;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void recoveryModeKeepsReadsAndLoginSurfaceWhileFreezingAllProjectWrites()
            throws Exception {
        assertThat(schemaVersion()).isEqualTo(MigrationVersions.latest());
        assertThat(context.getBeansOfType(AgentTurnScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(TaskRecoveryScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(DirectTextGenerationScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(MediaExecutionScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(dev.agenvas.skill.application.SkillWorker.class)).isEmpty();

        AdminPrincipal owner = identities.setup("recovery-admin", "recovery-password-123");
        Project project = projects.create(owner.userId(), "Restored project",
                Project.AspectRatio.LANDSCAPE_16_9);
        var authentication = authentication(new UsernamePasswordAuthenticationToken(
                owner, null, List.of()));
        var mvc = webAppContextSetup(webContext).apply(springSecurity()).build();

        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects/" + project.id()).with(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Restored project"));
        mvc.perform(post("/api/v1/projects").with(authentication).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Should not exist\",\"aspectRatio\":\"LANDSCAPE_16_9\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOVERY_MODE_READ_ONLY"));
        mvc.perform(post("/api/v1/projects/" + project.id() + "/runs")
                        .with(authentication).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOVERY_MODE_READ_ONLY"));
        mvc.perform(post("/api/v1/settings/llm/diagnose")
                        .with(authentication).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOVERY_MODE_READ_ONLY"));
        mvc.perform(post("/api/v1/media-templates").with(authentication).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOVERY_MODE_READ_ONLY"));
        mvc.perform(post("/api/v1/media-templates/images/from-version").with(authentication).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOVERY_MODE_READ_ONLY"));
        var adminAuthentication = authentication(new UsernamePasswordAuthenticationToken(
                owner, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        for (String settingsPath : List.of("/api/v1/settings/media-styles",
                "/api/v1/settings/media-templates")) {
            mvc.perform(post(settingsPath).with(adminAuthentication).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("RECOVERY_MODE_READ_ONLY"));
        }
        mvc.perform(post("/api/v1/skills").with(authentication).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Frozen Skill\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOVERY_MODE_READ_ONLY"));
        assertThat(projects.list(owner.userId(), false, null, 20).items())
                .extracting(Project::name).containsExactly("Restored project");
        assertThat(schemaVersion()).isEqualTo(MigrationVersions.latest());
    }

    private String schemaVersion() {
        return jdbc.sql("select version from flyway_schema_history "
                        + "order by installed_rank desc limit 1")
                .query(String.class).single();
    }
}
