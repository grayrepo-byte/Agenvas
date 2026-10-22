package dev.agenvas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.application.ProjectSnapshotService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.MigrationVersions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL evidence for one director identity, atomic initialization and CAS settings. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class AgentPostgresIT {
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
    @Autowired private CanvasService canvas;
    @Autowired private ProjectSnapshotService snapshots;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext webContext;

    @Test
    void oneProjectDirectorIsInitializedOnceAndSettingsNeverRevertOnEnsure() throws Exception {
        AdminPrincipal owner = identities.setup("director-admin", "director-password-123");
        Project project = projects.create(owner.userId(), "Director project", Project.AspectRatio.LANDSCAPE_16_9);
        Project other = projects.create(owner.userId(), "Independent project", Project.AspectRatio.SQUARE_1_1);
        assertThat(agents.findProjectDirector(owner.userId(), project.id())).isEmpty();
        assertThat(snapshots.snapshot(owner.userId(), project.id()).director()).isNull();
        var mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String base = "/api/v1/projects/" + project.id();
        mvc.perform(put(base + "/director").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(put(base + "/director").with(auth)).andExpect(status().isForbidden());
        for (String body : List.of("{}", "{\"name\":\"Another assistant\"}",
                "{\"instruction\":\"Replace defaults\"}", "{\"promptKey\":\"agent.writer\"}")) {
            mvc.perform(put(base + "/director").with(auth).with(csrf())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(agents.findProjectDirector(owner.userId(), project.id())).isEmpty();
        var response = mvc.perform(put(base + "/director").with(auth).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bindings").doesNotExist())
                .andExpect(jsonPath("$.outputGroupId").doesNotExist()).andReturn().getResponse();
        UUID id = UUID.fromString(mapper.readTree(response.getContentAsString()).path("id").asText());
        AgentInstance director = agents.get(owner.userId(), project.id(), id);
        assertThat(director.version()).isZero();
        assertThat(agents.ensureDirector(owner.userId(), project.id())).isEqualTo(director);
        assertThat(directorChanges(project.id())).isEqualTo(1);
        assertThat(canvas.list(owner.userId(), project.id())).isEmpty();
        assertThat(jdbc.sql("select count(*) from agent_run where project_id = :project")
                .param("project", project.id()).query(Long.class).single()).isZero();
        mvc.perform(get(base + "/snapshot").with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.director.id").value(id.toString()))
                .andExpect(jsonPath("$.agents").doesNotExist());

        AgentInstance edited = agents.update(owner.userId(), project.id(), id, director.version(),
                "My director", "Use the current canvas and keep my creative instructions");
        long sequence = snapshots.snapshot(owner.userId(), project.id()).snapshotSeq();
        assertThat(agents.ensureDirector(owner.userId(), project.id())).isEqualTo(edited);
        mvc.perform(put(base + "/director").with(auth).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(edited.name())).andExpect(jsonPath("$.version").value(1));
        assertThat(snapshots.snapshot(owner.userId(), project.id()).snapshotSeq()).isEqualTo(sequence);
        mvc.perform(get(base + "/agents").with(auth)).andExpect(status().isNotFound());
        mvc.perform(post(base + "/agents").with(auth).with(csrf()).contentType("application/json")
                .content("{\"name\":\"Retired\",\"instruction\":\"Create freely\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch(base + "/agents/" + id).with(auth).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":1,\"name\":\"Invalid\",\"instruction\":\"Invalid\",\"bindings\":[]}"))
                .andExpect(status().isBadRequest());
        assertProblem("AGENT_VERSION_CONFLICT", () -> agents.update(owner.userId(), project.id(), id,
                0, "Stale", "Must fail"));
        assertProblem("RESOURCE_NOT_FOUND", () -> agents.ensureDirector(UUID.randomUUID(), project.id()));
        assertProblem("RESOURCE_NOT_FOUND", () -> agents.get(owner.userId(), other.id(), id));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<AgentInstance> concurrent;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(1, 2).stream().map(ignored -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Director race timed out");
                return agents.ensureDirector(owner.userId(), other.id());
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            concurrent = List.of(futures.getFirst().get(20, TimeUnit.SECONDS), futures.getLast().get(20, TimeUnit.SECONDS));
        }
        assertThat(concurrent.getFirst()).isEqualTo(concurrent.getLast());
        assertThat(concurrent.getFirst().id()).isNotEqualTo(id);
        assertThat(directorChanges(other.id())).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from agent_instance where project_id = :project")
                .param("project", other.id()).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("""
                insert into agent_instance(id, project_id, profile_key, profile_version, name,
                                           instruction, version, created_at, updated_at)
                select gen_random_uuid(), project_id, profile_key, profile_version, name,
                       instruction, version, created_at, updated_at
                from agent_instance where id = :director
                """).param("director", id).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                .query(String.class).single()).isEqualTo(MigrationVersions.latest());
    }

    private long directorChanges(UUID projectId) {
        return jdbc.sql("select count(*) from project_event where project_id = :project and type = 'agent.instance.changed'")
                .param("project", projectId).query(Long.class).single();
    }

    private void assertProblem(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> assertThat(problem.code()).isEqualTo(code));
    }
}
