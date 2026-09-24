package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Confirms the scheduled worker advances a Run without any client connection or polling. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentTurnSchedulerPostgresIT.FakeConfig.class},
        properties = {"agenvas.identity.bootstrap-secret=agent-scheduler-integration-secret",
                "agenvas.llm.scheduler-enabled=true"})
class AgentTurnSchedulerPostgresIT {

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
    @Autowired private TaskService tasks;
    @Autowired private FakeGateway gateway;

    @Test
    void savedRunFinishesFromDatabaseQueueWithNoOpenHttpRequest() throws Exception {
        AdminPrincipal owner = identities.setup("agent-scheduler-integration-secret",
                "scheduler-admin", "scheduler-password-123");
        Project project = projects.create(owner.userId(), "Scheduler project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator",
                "Complete this small task", List.of());
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Say the task is done", "scheduler-run").run();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        AgentRun current = runs.get(owner.userId(), project.id(), queued.id());
        while (current.status() != AgentRun.Status.SUCCEEDED && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
            current = runs.get(owner.userId(), project.id(), queued.id());
        }
        assertThat(current.status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(tasks.listByRun(owner.userId(), project.id(), queued.id()))
                .singleElement().satisfies(task ->
                        assertThat(task.status()).isEqualTo(Task.Status.SUCCEEDED));
        assertThat(gateway.calls.get()).isEqualTo(1);
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway() {
            return new FakeGateway();
        }
    }

    /** No external model request is made by this scheduled integration test. */
    static class FakeGateway implements ChatGateway {
        @Override
        public String configSource() {
            return "test-fake";
        }

        @Override
        public int configVersion() {
            return 1;
        }

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            calls.incrementAndGet();
            return new Exchange(1, new ChatResponse(List.of(
                    new Generation(new AssistantMessage("Done.")))));
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, false, false);
        }
    }
}
