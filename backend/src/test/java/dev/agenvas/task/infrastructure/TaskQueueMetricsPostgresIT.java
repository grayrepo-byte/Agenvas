package dev.agenvas.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
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

/** Verifies the read-only queue-age aggregate against durable PostgreSQL Task rows. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=task-queue-age-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mock.scheduler-enabled=false",
        "agenvas.provider.mock.video-scheduler-enabled=false",
        "agenvas.export.scheduler-enabled=false"})
class TaskQueueMetricsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private TaskQueueMetrics metrics;
    @Autowired private MeterRegistry meters;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void oldestDueReadyAgeExcludesFutureWorkAndHasNoIdentifierLabels() {
        AdminPrincipal owner = identities.setup("task-queue-age-integration-secret",
                "queue-admin", "queue-password-123");
        Project project = projects.create(owner.userId(), "Queue metrics",
                Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        var run = runs.create(owner.userId(), project.id(), agent.id(),
                "Create a plan", "queue-age-run").run();
        Task due = tasks.create(owner.userId(), project.id(), run.id(),
                "queue-due", Task.Kind.IMAGE_GENERATION, mapper.createObjectNode(),
                null, 1, List.of());
        Task future = tasks.create(owner.userId(), project.id(), run.id(),
                "queue-future", Task.Kind.IMAGE_GENERATION, mapper.createObjectNode(),
                null, 1, List.of());
        jdbc.sql("update task set updated_at = now() - interval '120 seconds' where id = :id")
                .param("id", due.id()).update();
        jdbc.sql("update task set updated_at = now() - interval '600 seconds', "
                        + "next_action_at = now() + interval '1 hour' where id = :id")
                .param("id", future.id()).update();

        metrics.refresh();
        double age = meters.get("agenvas.tasks.ready.oldest.age.seconds").gauge().value();
        assertThat(age).isBetween(110.0, 150.0);
        assertThat(meters.get("agenvas.tasks.current").tag("status", "READY")
                .gauge().value()).isGreaterThanOrEqualTo(2.0);
        assertThat(meters.get("agenvas.tasks.ready.oldest.age.seconds")
                .gauge().getId().getTags()).isEmpty();
    }
}
