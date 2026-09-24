package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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

/** Fake-model, real-PostgreSQL proof of a full durable tool-to-next-turn lifecycle. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentTurnWorkerPostgresIT.FakeConfig.class},
        properties = "agenvas.identity.bootstrap-secret=agent-worker-integration-secret")
class AgentTurnWorkerPostgresIT {

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
    @Autowired private AgentTurnWorker worker;
    @Autowired private FakeGateway gateway;
    @Autowired private JdbcClient jdbc;

    @Test
    void modelToolResultSchedulesASecondDurableTurnThenReleasesTheRunSlot() {
        AdminPrincipal owner = identities.setup("agent-worker-integration-secret",
                "worker-admin", "worker-password-123");
        Project project = projects.create(owner.userId(), "Worker project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator",
                "Create one text first", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Create one text", "worker-run").run();

        assertThat(worker.runOnce("worker-test")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.RUNNING);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).nextStepIndex())
                .isEqualTo(1);
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id()))
                .extracting(Task::status)
                .containsExactly(Task.Status.SUCCEEDED, Task.Status.READY);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(1);

        assertThat(worker.runOnce("worker-test")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id()))
                .extracting(Task::status)
                .containsExactly(Task.Status.SUCCEEDED, Task.Status.SUCCEEDED);
        assertThat(jdbc.sql("select count(*) from project where id = :projectId and active_run_id is not null")
                .param("projectId", project.id()).query(Long.class).single()).isZero();
        assertThat(gateway.calls.get()).isEqualTo(2);
        assertThat(worker.runOnce("worker-test")).isZero();

        gateway.toolCalling = false;
        AgentRun unavailable = runs.create(owner.userId(), project.id(), agent.id(),
                "Try without a configured model", "unavailable-model-run").run();
        assertThat(worker.runOnce("worker-test")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), unavailable.id()).status())
                .isEqualTo(AgentRun.Status.BLOCKED);
        assertThat(tasks.listByRun(owner.userId(), project.id(), unavailable.id()))
                .singleElement().satisfies(task ->
                        assertThat(task.status()).isEqualTo(Task.Status.FAILED));
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway() {
            return new FakeGateway();
        }
    }

    /** Fake responses are deterministic and never contact a real model Provider. */
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
        private volatile boolean toolCalling = true;

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            int count = calls.incrementAndGet();
            if (count == 1) {
                AssistantMessage response = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("worker-call-1",
                                "function", "create_text",
                                "{\"title\":\"Opening\",\"text\":\"First shot\",\"format\":\"PLAIN_TEXT\"}")))
                        .build();
                return new Exchange(1, new ChatResponse(List.of(new Generation(response))));
            }
            assertThat(messages.get(messages.size() - 1).getMessageType().name())
                    .isEqualTo("TOOL");
            ToolResponseMessage reply = (ToolResponseMessage) messages.getLast();
            assertThat(reply.getResponses()).singleElement().satisfies(result -> {
                assertThat(result.id()).isEqualTo("worker-call-1");
                assertThat(result.name()).isEqualTo("create_text");
                assertThat(result.responseData()).contains("\"status\":\"SUCCEEDED\"")
                        .contains("\"createdIds\"");
            });
            AssistantMessage prior = (AssistantMessage) messages.get(messages.size() - 2);
            assertThat(prior.getToolCalls()).singleElement()
                    .extracting(AssistantMessage.ToolCall::id)
                    .isEqualTo(reply.getResponses().getFirst().id());
            return new Exchange(1, new ChatResponse(List.of(
                    new Generation(new AssistantMessage("The text is ready.")))));
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(toolCalling, false, false);
        }
    }
}
