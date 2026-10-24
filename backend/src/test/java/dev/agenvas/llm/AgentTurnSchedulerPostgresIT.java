package dev.agenvas.llm;

import static dev.agenvas.testing.AgentFixture.configureDirector;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.LlmProtocolCodec;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.jdbc.core.simple.JdbcClient;
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
        properties = {
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
    @Autowired private JdbcClient jdbc;
    @Autowired private AgentTurnWorker worker;

    @Test
    void savedRunFinishesFromDatabaseQueueWithNoOpenHttpRequest() throws Exception {
        AdminPrincipal owner = owner();
        Project project = projects.create(owner.userId(), "Scheduler project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = configureDirector(agents, owner.userId(), project.id(), "Creator",
                "Complete this small task");
        int callsBefore = gateway.calls.get();
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
        assertThat(gateway.calls.get() - callsBefore).isEqualTo(1);
    }

    @Test
    void longRunningConversationCanBeCanceledWithoutCreatingMoreModelTurns() throws Exception {
        AdminPrincipal owner = owner();
        Project project = projects.create(owner.userId(), "Cancelable scheduler project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = configureDirector(agents, owner.userId(), project.id(), "Creator",
                "Inspect the project");
        int callsBefore = gateway.calls.get();
        gateway.keepRequestingTools.set(true);
        try {
            AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                    "Keep checking project status", "cancelable-scheduler-run").run();
            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            AgentRun current = runs.get(owner.userId(), project.id(), queued.id());
            while (gateway.calls.get() - callsBefore < 13 && !current.status().terminal()
                    && Instant.now().isBefore(deadline)) {
                worker.runOnce("cancelable-turn-test-worker");
                Thread.sleep(25);
                current = runs.get(owner.userId(), project.id(), queued.id());
            }
            assertThat(gateway.calls.get() - callsBefore).isGreaterThanOrEqualTo(13);
            assertThat(runs.cancel(owner.userId(), project.id(), queued.id()).status()).isEqualTo(AgentRun.Status.CANCELED);
            // Allow an already in-flight fake response to observe cancellation before
            // checking that no subsequent model request is dispatched.
            Thread.sleep(300);
            int stoppedCalls = gateway.calls.get();
            Thread.sleep(500);
            assertThat(gateway.calls.get()).isEqualTo(stoppedCalls);
            assertThat(runs.get(owner.userId(), project.id(), queued.id()).status()).isEqualTo(AgentRun.Status.CANCELED);
        } finally {
            gateway.keepRequestingTools.set(false);
        }
    }

    @Test
    void unboundedRunPassesNinetyToolTurnsWithoutPrematurelyTruncatingContext() throws Exception {
        AdminPrincipal owner = owner();
        Project project = projects.create(owner.userId(), "Long synthetic run", Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = configureDirector(agents, owner.userId(), project.id(), "Reader", "Keep concise public observations");
        int before = gateway.calls.get();
        gateway.stopAfter.set(before + 90);
        try {
            AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(), "Synthetic long reading sequence", "long-run").run();
            assertThat(queued.policySnapshot().path("maxModelTurns").isNull()).isTrue();
            assertThat(queued.policySnapshot().path("maxToolExecutions").isNull()).isTrue();
            AgentRun current = queued;
            Instant deadline = Instant.now().plusSeconds(60);
            while (!current.status().terminal() && current.status() != AgentRun.Status.BLOCKED && Instant.now().isBefore(deadline)) {
                worker.runOnce("long-run-worker");
                current = runs.get(owner.userId(), project.id(), queued.id());
            }
            assertThat(current.status()).isEqualTo(AgentRun.Status.SUCCEEDED);
            assertThat(gateway.calls.get() - before).isEqualTo(91);
            assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :id").param("id", queued.id())
                    .query(Long.class).single()).isEqualTo(90);
            assertThat(jdbc.sql("select max(jsonb_array_length(request_json->'messages')) from llm_turn where run_id = :id")
                    .param("id", queued.id()).query(Integer.class).single())
                    .isBetween(180, LlmProtocolCodec.MAX_MESSAGES);
            // This short transcript fits the default 1M window: retain its first and latest
            // observations instead of applying the retired 80-message clipping policy.
            assertThat(jdbc.sql("select request_json::text from llm_turn where run_id = :id and step_index = 90")
                    .param("id", queued.id()).query(String.class).single())
                    .contains("Public observation for call " + (before + 1),
                            "Public observation for call " + (before + 90));
        } finally { gateway.stopAfter.set(0); }
    }

    /** Both tests share one PostgreSQL installation with a one-time administrator setup. */
    private AdminPrincipal owner() {
        return jdbc.sql("select id from app_user where login_name = 'scheduler-admin'")
                .query(UUID.class).optional()
                .map(id -> new AdminPrincipal(id, "scheduler-admin"))
                .orElseGet(() -> identities.setup("scheduler-admin", "scheduler-password-123"));
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
        private final AtomicBoolean keepRequestingTools = new AtomicBoolean();
        private final AtomicInteger stopAfter = new AtomicInteger();

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            int call = calls.incrementAndGet();
            AssistantMessage response = (keepRequestingTools.get() || call <= stopAfter.get())
                    ? AssistantMessage.builder().content("Public observation for call " + call).toolCalls(List.of(
                            new AssistantMessage.ToolCall("read-" + call, "function",
                                    "read_project_summary", "{}"))).build()
                    : new AssistantMessage("Done.");
            return new Exchange(1, new ChatResponse(List.of(new Generation(response))));
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, false, false);
        }
    }
}
