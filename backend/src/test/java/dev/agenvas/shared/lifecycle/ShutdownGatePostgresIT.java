package dev.agenvas.shared.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL and HTTP proof that shutdown stops admission without rewriting queued tasks. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=shutdown-gate-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mock.scheduler-enabled=false",
        "agenvas.provider.mock.video-scheduler-enabled=false",
        "agenvas.export.scheduler-enabled=false"})
class ShutdownGatePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private ShutdownGate gate;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext webContext;

    @Test
    void closeSignalRejectsNewRunAndClaimsButLeavesReadyWorkDurable() throws Exception {
        AdminPrincipal owner = identities.setup("shutdown-gate-integration-secret",
                "shutdown-admin", "shutdown-password-123");
        Project project = projects.create(owner.userId(), "Shutdown gate",
                Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        var run = runs.create(owner.userId(), project.id(), agent.id(),
                "Create storyboard", "before-close").run();
        Task image = tasks.create(owner.userId(), project.id(), run.id(), null,
                "queued-image", Task.Kind.IMAGE_GENERATION, mapper.createObjectNode(),
                null, 1, List.of());
        assertThat(image.status()).isEqualTo(Task.Status.READY);
        assertThat(gate.isClosing()).isFalse();

        gate.onContextClosed(new ContextClosedEvent(webContext));
        assertThat(gate.isClosing()).isTrue();
        assertThatThrownBy(() -> runs.create(owner.userId(), project.id(), agent.id(),
                "Another storyboard", "after-close"))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(failure -> {
                    ApiProblemException problem = (ApiProblemException) failure;
                    assertThat(problem.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(problem.code()).isEqualTo("APPLICATION_STOPPING");
                });
        var mvc = webAppContextSetup(webContext).apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity()).build();
        mvc.perform(post("/api/v1/projects/" + project.id() + "/runs")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                owner, null, List.of())))
                        .with(csrf())
                        .header("Idempotency-Key", "http-after-close")
                        .contentType("application/json")
                        .content("{\"agentId\":\"" + agent.id()
                                + "\",\"instruction\":\"Another storyboard\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("APPLICATION_STOPPING"));

        assertThat(tasks.claimDue("closed-generic", 10)).isEmpty();
        assertThat(tasks.claimImagesDue("closed-image", 10)).isEmpty();
        assertThat(tasks.claimComfyImage("closed-comfy-image")).isEmpty();
        assertThat(tasks.claimComfyVideo("closed-comfy-video")).isEmpty();
        assertThat(tasks.claimVideosDue("closed-video", 10)).isEmpty();
        assertThat(tasks.claimProviderPolls("closed-poller", 10)).isEmpty();
        assertThat(tasks.claimComfyImagePolls("closed-comfy-image-poller", 10)).isEmpty();
        assertThat(tasks.claimComfyVideoPolls("closed-comfy-video-poller", 10)).isEmpty();
        assertThat(tasks.claimExportsDue("closed-export", 10)).isEmpty();
        assertThat(tasks.claimAgentTurns("closed-model", 10)).isEmpty();
        assertThat(tasks.get(owner.userId(), project.id(), image.id()).status())
                .isEqualTo(Task.Status.READY);
        assertThat(jdbc.sql("select count(*) from agent_run where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :taskId")
                .param("taskId", image.id()).query(Integer.class).single()).isZero();
    }
}
