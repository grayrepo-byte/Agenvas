package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.AgentTurnCommitService;
import dev.agenvas.llm.application.LlmRoundService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/** PostgreSQL proof that a complete tool-call response commits before callback execution. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, LlmTurnPostgresIT.FakeConfig.class})
class LlmTurnPostgresIT {

    private static final String WORKER = "llm-checkpoint-worker";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        // PER_CLASS creates the Spring-injected test instance before the extension's beforeAll.
        if (!POSTGRES.isRunning()) POSTGRES.start();
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private LlmRoundService rounds;
    @Autowired private LlmTurnCheckpointService checkpoints;
    @Autowired private LlmProtocolCodec codec;
    @Autowired private AgentTurnCommitService turnCommits;
    @Autowired private TaskService tasks;
    @Autowired private FakeGateway gateway;
    @Autowired private JdbcClient jdbc;
    private AdminPrincipal owner;

    @BeforeAll
    void administrator() {
        owner = identities.setup("llm-admin", "llm-password-123");
    }

    @BeforeEach
    void resetGateway() {
        gateway.configVersion = 1;
        gateway.configSource = "test-fake";
        gateway.calls.set(0);
        gateway.beforeResponse = () -> {};
    }

    @Test
    void responseAndProtocolMetadataAreDurableBeforeToolExecutionAndReplaySkipsModel() {
        Project project = projects.create(owner.userId(), "LLM project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of());
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Plan three shots", "llm-checkpoint-run").run();
        Task lease = tasks.claimAgentTurns(WORKER, 1).getFirst();
        assertThat(lease.runId()).isEqualTo(queued.id());
        AgentRun run = turnCommits.start(lease, WORKER);
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_project_summary")
                        .description("Read summary")
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String input) {
                throw new AssertionError("The model round must not execute tools");
            }
        };
        List<Message> messages = List.of(new UserMessage("Plan three shots"));
        JsonNode response = rounds.callLeased(owner.userId(), project.id(), run.id(), 0,
                messages, List.of(tool), Map.of("projectId", project.id()), lease, WORKER);
        assertThat(gateway.calls.get()).isEqualTo(1);
        assertThat(run.policySnapshot().path("modelConfigVersion").asInt()).isEqualTo(1);
        assertThat(run.policySnapshot().path("modelConfigSource").asText())
                .isEqualTo("test-fake");
        assertThat(response.at("/generations/0/assistant/toolCalls/0/id").asText())
                .isEqualTo("call-checkpoint-1");
        assertThat(response.at("/metadata/id").asText()).isEqualTo("response-checkpoint-1");
        assertThat(response.at("/generations/0/assistant/metadata/providerProtocol").asText())
                .isEqualTo("opaque-protocol");
        assertThat(jdbc.sql("select status from llm_turn where run_id = :runId")
                .param("runId", run.id()).query(String.class).single()).isEqualTo("RESPONDED");
        assertThat(jdbc.sql("select count(*) from project_event where project_id = :projectId and type like 'llm.turn.%'")
                .param("projectId", project.id()).query(Long.class).single()).isEqualTo(2);
        List<String> usage = jdbc.sql("""
                        select quantity_json::text from usage_ledger
                        where run_id = :runId order by entry_type desc
                        """)
                .param("runId", run.id()).query(String.class).list();
        assertThat(usage).hasSize(2);
        assertThat(usage).anySatisfy(value -> {
            assertThat(value).contains("\"inputTokens\": 23", "\"outputTokens\": 7");
        });
        assertThat(jdbc.sql("select count(*) from usage_ledger where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(2);

        assertThat(rounds.callLeased(owner.userId(), project.id(), run.id(), 0,
                messages, List.of(tool), Map.of("projectId", project.id()), lease, WORKER)).isEqualTo(response);
        assertThat(gateway.calls.get()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from call_log where run_id=:run and operation='CHAT'")
                .param("run", run.id()).query(Long.class).single()).isEqualTo(1);
        // Production streaming audit completion is queued independently of the response transaction.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.sql("select status from call_log where run_id=:run")
                        .param("run", run.id()).query(String.class).single()).isEqualTo("SUCCEEDED"));
        assertThat(jdbc.sql("select trace_id from call_log where run_id=:run")
                .param("run", run.id()).query(String.class).single()).matches("[0-9a-f]{32}");
        gateway.configSource = "other-source";
        assertThatThrownBy(() -> rounds.callLeased(owner.userId(), project.id(), run.id(), 0,
                messages, List.of(tool), Map.of(), lease, WORKER))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("LLM_CONFIG_UNAVAILABLE"))
                .hasMessageContaining("Run 固定的模型配置");
        gateway.configSource = "test-fake";
        gateway.configVersion = 2;
        assertThatThrownBy(() -> rounds.callLeased(owner.userId(), project.id(), run.id(), 0,
                messages, List.of(tool), Map.of(), lease, WORKER))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("LLM_CONFIG_UNAVAILABLE"))
                .hasMessageContaining("Run 固定的模型配置");
        assertThat(gateway.calls.get()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from usage_ledger where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(2);
        gateway.configVersion = 1;
        assertThatThrownBy(() -> rounds.callLeased(owner.userId(), project.id(), run.id(), 0,
                List.of(new UserMessage("Different instruction")), List.of(tool), Map.of(), lease, WORKER))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("LLM_TURN_CONFLICT"));
        assertThatThrownBy(() -> rounds.callLeased(UUID.randomUUID(), project.id(), run.id(), 0,
                messages, List.of(tool), Map.of(), lease, WORKER))
                .isInstanceOf(ApiProblemException.class);
        assertThat(gateway.calls.get()).isEqualTo(1);
    }

    @Test
    void oldEpochBeforeDispatchCannotReserveOrRequestAModel() {
        Task lease = startedTurn("Expired before model dispatch");
        Task replacement = reclaim(lease);
        List<Message> messages = List.of(new UserMessage("Frozen request"));

        assertThatThrownBy(() -> rounds.callLeased(owner.userId(), lease.projectId(), lease.runId(),
                0, messages, List.of(), Map.of(), lease, WORKER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> checkpoints.reserveLeased(owner.userId(), lease.projectId(), lease.runId(),
                0, 1, "test-fake", codec.request(messages, List.of()), lease, WORKER))
                .isInstanceOf(IllegalStateException.class);

        assertThat(replacement.leaseEpoch()).isGreaterThan(lease.leaseEpoch());
        assertThat(gateway.calls).hasValue(0);
        assertThat(jdbc.sql("select count(*) from llm_turn where run_id=:run")
                .param("run", lease.runId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from usage_ledger where run_id=:run")
                .param("run", lease.runId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from call_log where run_id=:run")
                .param("run", lease.runId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from project_event where project_id=:project and type like 'llm.turn.%'")
                .param("project", lease.projectId()).query(Long.class).single()).isZero();
        Task current = tasks.get(owner.userId(), lease.projectId(), lease.id());
        assertThat(current.leaseEpoch()).isEqualTo(replacement.leaseEpoch());
        assertThat(current.output() == null || !current.output().has("assistantStream")).isTrue();
    }

    @Test
    void oldEpochAfterModelResponseCannotSaveSettleOrDispatchAgain() {
        Task lease = startedTurn("Expired after model response");
        List<Message> messages = List.of(new UserMessage("Frozen request"));
        AtomicReference<Task> replacement = new AtomicReference<>();
        gateway.beforeResponse = () -> replacement.set(reclaim(lease));

        assertThatThrownBy(() -> rounds.callLeased(owner.userId(), lease.projectId(), lease.runId(),
                0, messages, List.of(), Map.of(), lease, WORKER)).isInstanceOf(IllegalStateException.class);
        assertThat(replacement.get().leaseEpoch()).isGreaterThan(lease.leaseEpoch());
        gateway.beforeResponse = () -> {};
        assertThatThrownBy(() -> rounds.callLeased(owner.userId(), lease.projectId(), lease.runId(),
                0, messages, List.of(), Map.of(), lease, WORKER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> checkpoints.saveResponseLeased(owner.userId(), lease.projectId(), lease.runId(),
                0, 1, codec.response(gateway.response()), lease, WORKER))
                .isInstanceOf(IllegalStateException.class);

        assertThat(gateway.calls).hasValue(1);
        assertThat(jdbc.sql("select status from llm_turn where run_id=:run")
                .param("run", lease.runId()).query(String.class).single()).isEqualTo("REQUESTED");
        assertThat(jdbc.sql("select response_json is null from llm_turn where run_id=:run")
                .param("run", lease.runId()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select entry_type from usage_ledger where run_id=:run")
                .param("run", lease.runId()).query(String.class).list()).containsExactly("RESERVATION");
        assertThat(jdbc.sql("select type from project_event where project_id=:project and type like 'llm.turn.%'")
                .param("project", lease.projectId()).query(String.class).list()).containsExactly("llm.turn.requested");
        assertThat(jdbc.sql("select count(*) from call_log where run_id=:run")
                .param("run", lease.runId()).query(Long.class).single()).isEqualTo(1);
        Task current = tasks.get(owner.userId(), lease.projectId(), lease.id());
        assertThat(current.leaseEpoch()).isEqualTo(replacement.get().leaseEpoch());
        assertThat(current.output().at("/assistantStream/status").asText()).isEqualTo("STREAMING");
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id=:run")
                .param("run", lease.runId()).query(Long.class).single()).isZero();
    }

    private Task startedTurn(String title) {
        Project project = projects.create(owner.userId(), title, Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(), title,
                UUID.randomUUID().toString()).run();
        Task lease = tasks.claimAgentTurns(WORKER, 1).getFirst();
        assertThat(lease.runId()).isEqualTo(queued.id());
        turnCommits.start(lease, WORKER);
        return lease;
    }

    private Task reclaim(Task lease) {
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id=:task")
                .param("task", lease.id()).update();
        Task replacement = tasks.claimAgentTurns("replacement-worker", 1).getFirst();
        assertThat(replacement.id()).isEqualTo(lease.id());
        turnCommits.start(replacement, "replacement-worker");
        return replacement;
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway() {
            return new FakeGateway();
        }
    }

    /** Deterministic fake: no external model was contacted in this integration test. */
    static class FakeGateway implements ChatGateway {
        private volatile String configSource = "test-fake";

        @Override
        public String configSource() {
            return configSource;
        }

        private volatile int configVersion = 1;

        @Override
        public int configVersion() {
            return configVersion;
        }

        private final AtomicInteger calls = new AtomicInteger();
        private Runnable beforeResponse = () -> {};

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(org.slf4j.MDC.get("traceId")).matches("[0-9a-f]{32}");
            calls.incrementAndGet();
            beforeResponse.run();
            return new Exchange(1, response());
        }

        private ChatResponse response() {
            AssistantMessage output = AssistantMessage.builder().content("")
                    .properties(Map.of("providerProtocol", "opaque-protocol"))
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-checkpoint-1",
                            "function", "read_project_summary", "{}"))).build();
            return new ChatResponse(List.of(new Generation(output)),
                    ChatResponseMetadata.builder().id("response-checkpoint-1")
                            .model("fake-model").usage(new DefaultUsage(23, 7)).build());
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, false, false);
        }
    }
}
