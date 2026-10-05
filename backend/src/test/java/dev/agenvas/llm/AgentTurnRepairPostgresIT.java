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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

/** Fake-model, real-PostgreSQL proof that invalid rounds are atomic and repair is bounded. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentTurnRepairPostgresIT.FakeConfig.class})
class AgentTurnRepairPostgresIT {

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
    void repairsWholeInvalidRoundOnceAndBlocksAfterTwoFailedRepairs() {
        AdminPrincipal owner = identities.setup("repair-admin", "repair-password-123");
        Project project = projects.create(owner.userId(), "Repair project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator",
                "Write a short text", List.of());
        AgentRun recovered = runs.create(owner.userId(), project.id(), agent.id(),
                "Write", "repair-success-run").run();

        assertThat(worker.runOnce("repair-test")).isEqualTo(1);
        assertThat(artifactCount(project.id())).isZero();
        assertThat(ledgerCount(recovered.id())).isZero();
        assertThat(tasks.listByRun(owner.userId(), project.id(), recovered.id()))
                .extracting(Task::status)
                .containsExactly(Task.Status.SUCCEEDED, Task.Status.READY);
        assertThat(tasks.listByRun(owner.userId(), project.id(), recovered.id()).getLast()
                .input().path("repairAttempt").asInt()).isEqualTo(1);

        assertThat(worker.runOnce("repair-test")).isEqualTo(1);
        assertThat(artifactCount(project.id())).isEqualTo(1);
        assertThat(ledgerCount(recovered.id())).isEqualTo(1);
        assertThat(worker.runOnce("repair-test")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), recovered.id()).status())
                .isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(gateway.calls(recovered.id())).isEqualTo(3);

        gateway.alwaysInvalid = true;
        AgentRun exhausted = runs.create(owner.userId(), project.id(), agent.id(),
                "Write again", "repair-exhaust-run").run();
        for (int index = 0; index < 3; index++) {
            assertThat(worker.runOnce("repair-test")).as("exhausted turn %s", index + 1)
                    .isEqualTo(1);
        }
        assertThat(runs.get(owner.userId(), project.id(), exhausted.id()).status())
                .isEqualTo(AgentRun.Status.BLOCKED);
        assertThat(tasks.listByRun(owner.userId(), project.id(), exhausted.id()))
                .extracting(Task::status)
                .containsExactly(Task.Status.SUCCEEDED, Task.Status.SUCCEEDED,
                        Task.Status.FAILED);
        assertThat(tasks.listByRun(owner.userId(), project.id(), exhausted.id()).get(1)
                .input().path("repairAttempt").asInt()).isEqualTo(1);
        assertThat(tasks.listByRun(owner.userId(), project.id(), exhausted.id()).get(2)
                .input().path("repairAttempt").asInt()).isEqualTo(2);
        assertThat(tasks.listByRun(owner.userId(), project.id(), exhausted.id()).getLast()
                .errorCode()).isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(gateway.calls(exhausted.id())).isEqualTo(3);
        assertThat(artifactCount(project.id())).isEqualTo(1);
        assertThat(ledgerCount(exhausted.id())).isZero();
        assertThat(jdbc.sql("select count(*) from llm_turn where run_id = :runId")
                .param("runId", exhausted.id()).query(Long.class).single()).isEqualTo(3);
        assertThat(worker.runOnce("repair-test")).isZero();
    }

    private long artifactCount(UUID projectId) {
        return jdbc.sql("select count(*) from artifact where project_id = :projectId")
                .param("projectId", projectId).query(Long.class).single();
    }

    private long ledgerCount(UUID runId) {
        return jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", runId).query(Long.class).single();
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway() {
            return new FakeGateway();
        }
    }

    /** Produces a valid call before a malformed one to expose partial-commit regressions. */
    static class FakeGateway implements ChatGateway {
        private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();
        private volatile boolean alwaysInvalid;

        @Override
        public String configSource() { return "test-fake"; }

        @Override
        public int configVersion() { return 1; }

        @Override
        public Capabilities capabilities() { return new Capabilities(true, false, false); }

        int calls(UUID runId) {
            return callCounts.get(runId.toString()).get();
        }

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            int count = callCounts.computeIfAbsent((String) toolContext.get("runId"),
                    ignored -> new AtomicInteger()).incrementAndGet();
            if (count == 2) {
                assertThat(messages.getLast().getText()).contains("TOOL_ARGUMENT_INVALID")
                        .contains("No tools from that response were applied");
                AssistantMessage rejected = (AssistantMessage) messages.get(messages.size() - 3);
                ToolResponseMessage failedCalls = (ToolResponseMessage) messages.get(messages.size() - 2);
                assertThat(rejected.getToolCalls()).hasSize(2);
                assertThat(failedCalls.getResponses()).hasSize(2);
                assertThat(failedCalls.getResponses().stream().map(ToolResponseMessage.ToolResponse::id))
                        .containsExactlyElementsOf(rejected.getToolCalls().stream()
                                .map(AssistantMessage.ToolCall::id).toList());
                assertThat(failedCalls.getResponses()).allSatisfy(reply ->
                        assertThat(reply.responseData()).contains("\"businessEffect\":false"));
            }
            AssistantMessage response;
            if (alwaysInvalid) {
                response = AssistantMessage.builder().content("")
                        .toolCalls(List.of(textCall("valid-" + count),
                                new AssistantMessage.ToolCall("invalid-tool-" + count,
                                        "function", "create_text", "{not-json")))
                        .build();
            } else if (count == 1) {
                response = AssistantMessage.builder().content("")
                        .toolCalls(List.of(textCall("valid-" + count),
                                new AssistantMessage.ToolCall("invalid-" + count,
                                        "function", "create_text", "{not-json")))
                        .build();
            } else if (count == 2) {
                response = AssistantMessage.builder().content("")
                        .toolCalls(List.of(textCall("repaired"))).build();
            } else {
                response = new AssistantMessage("Done.");
            }
            return new Exchange(1, new ChatResponse(List.of(new Generation(response))));
        }

        private AssistantMessage.ToolCall textCall(String id) {
            return new AssistantMessage.ToolCall(id, "function", "create_text",
                    "{\"title\":\"Opening\",\"text\":\"First shot\",\"format\":\"PLAIN_TEXT\"}");
        }
    }
}
