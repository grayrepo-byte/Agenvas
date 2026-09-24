package dev.agenvas.shared.lifecycle;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

/** A real JVM SIGTERM smoke test against isolated PostgreSQL, never the user's Compose app. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=shutdown-signal-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mock.scheduler-enabled=false",
        "agenvas.provider.mock.video-scheduler-enabled=false",
        "agenvas.export.scheduler-enabled=false"})
class ShutdownSignalPostgresIT {

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
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void sigtermClosesChildGateWithoutRewritingQueuedWork() throws Exception {
        AdminPrincipal owner = identities.setup("shutdown-signal-integration-secret",
                "signal-admin", "signal-password-123");
        Project project = projects.create(owner.userId(), "Signal smoke",
                Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        var run = runs.create(owner.userId(), project.id(), agent.id(),
                "Create storyboard", "signal-smoke-run").run();
        Task queued = tasks.create(owner.userId(), project.id(), run.id(), null,
                "image-awaiting-worker", Task.Kind.IMAGE_GENERATION,
                mapper.createObjectNode(), null, 1, List.of());
        Path childLog = Files.createTempFile("agenvas-shutdown-signal-", ".log");
        Process child = null;
        try {
            child = startChild(childLog);
            waitForStartup(child, childLog);
            assertThat(child.isAlive()).isTrue();
            child.destroy(); // Process.destroy sends SIGTERM on the supported Unix test host.
            assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.readString(childLog))
                    .contains("Shutdown gate closed: new Runs and Task claims disabled");
            assertThat(tasks.get(owner.userId(), project.id(), queued.id()).status())
                    .isEqualTo(Task.Status.READY);
            assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :taskId")
                    .param("taskId", queued.id()).query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("select count(*) from agent_run where project_id = :projectId")
                    .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(1);
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
            Files.deleteIfExists(childLog);
        }
    }

    /** Waits for the packaged application rather than relying on a fixed startup delay. */
    private static void waitForStartup(Process child, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (child.isAlive() && System.nanoTime() < deadline) {
            if (Files.readString(log).contains("Started AgenvasApplication")) return;
            Thread.sleep(200);
        }
        assertThat(Files.readString(log)).contains("Started AgenvasApplication");
    }

    /** The child shares only this Testcontainer, not the developer's Compose database. */
    private static Process startChild(Path log) throws IOException {
        Path jar = Path.of("target/agenvas-server-0.1.0-SNAPSHOT.jar").toAbsolutePath();
        assertThat(Files.isRegularFile(jar)).isTrue();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xmx512m", "-jar",
                jar.toString(), "--server.port=0", "--agenvas.llm.scheduler-enabled=false",
                "--agenvas.provider.mock.scheduler-enabled=false",
                "--agenvas.provider.mock.video-scheduler-enabled=false",
                "--agenvas.export.scheduler-enabled=false")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("AGENVAS_DB_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("AGENVAS_DB_USER", POSTGRES.getUsername());
        builder.environment().put("AGENVAS_DB_PASSWORD", POSTGRES.getPassword());
        builder.environment().put("AGENVAS_BOOTSTRAP_SECRET", "shutdown-signal-integration-secret");
        return builder.start();
    }
}
