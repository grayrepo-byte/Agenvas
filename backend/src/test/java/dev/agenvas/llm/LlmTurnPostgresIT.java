package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmRoundService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, LlmTurnPostgresIT.FakeConfig.class})
class LlmTurnPostgresIT {

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
    @Autowired private LlmRoundService rounds;
    @Autowired private FakeGateway gateway;
    @Autowired private JdbcClient jdbc;

    @Test
    void responseAndProtocolMetadataAreDurableBeforeToolExecutionAndReplaySkipsModel() {
        AdminPrincipal owner = identities.setup("llm-admin",
                "llm-password-123");
        Project project = projects.create(owner.userId(), "LLM project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of());
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Plan three shots", "llm-checkpoint-run").run();
        AgentRun run = runs.transition(owner.userId(), project.id(), queued.id(), 0,
                AgentRun.Status.RUNNING);
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
        JsonNode response = rounds.call(owner.userId(), project.id(), run.id(), 0,
                messages, List.of(tool), Map.of("projectId", project.id()));
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

        assertThat(rounds.call(owner.userId(), project.id(), run.id(), 0,
                messages, List.of(tool), Map.of("projectId", project.id()))).isEqualTo(response);
        assertThat(gateway.calls.get()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from call_log where run_id=:run and operation='CHAT'")
                .param("run", run.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select status from call_log where run_id=:run")
                .param("run", run.id()).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("select trace_id from call_log where run_id=:run")
                .param("run", run.id()).query(String.class).single()).matches("[0-9a-f]{32}");
        gateway.configSource = "other-source";
        assertThatThrownBy(() -> rounds.call(owner.userId(), project.id(), run.id(), 1,
                messages, List.of(tool), Map.of()))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("Run 固定的模型配置");
        gateway.configSource = "test-fake";
        gateway.configVersion = 2;
        assertThatThrownBy(() -> rounds.call(owner.userId(), project.id(), run.id(), 1,
                messages, List.of(tool), Map.of()))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("Run 固定的模型配置");
        assertThat(gateway.calls.get()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from usage_ledger where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> rounds.call(owner.userId(), project.id(), run.id(), 0,
                List.of(new UserMessage("Different instruction")), List.of(tool), Map.of()))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> rounds.call(UUID.randomUUID(), project.id(), run.id(), 1,
                messages, List.of(tool), Map.of()))
                .isInstanceOf(ApiProblemException.class);
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

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(org.slf4j.MDC.get("traceId")).matches("[0-9a-f]{32}");
            calls.incrementAndGet();
            AssistantMessage output = AssistantMessage.builder().content("")
                    .properties(Map.of("providerProtocol", "opaque-protocol"))
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-checkpoint-1",
                            "function", "read_project_summary", "{}"))).build();
            return new Exchange(1, new ChatResponse(List.of(new Generation(output)),
                    ChatResponseMetadata.builder().id("response-checkpoint-1")
                            .model("fake-model").usage(new DefaultUsage(23, 7)).build()));
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, false, false);
        }
    }
}
