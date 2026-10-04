package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.AgentTurnCommitService;
import dev.agenvas.llm.application.LlmConversationService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmRoundService;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.MigrationVersions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
import tools.jackson.databind.JsonNode;

/** Real database proof of response-before-tool, idempotency and server-owned identity. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, ToolExecutionPostgresIT.FakeConfig.class})
class ToolExecutionPostgresIT {

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
    @Autowired private AgentTurnCommitService turnCommits;
    @Autowired private LlmConversationService conversation;
    @Autowired private LlmTurnCheckpointService checkpoints;
    @Autowired private LlmProtocolCodec codec;
    @Autowired private ToolExecutionService executor;
    @Autowired private ToolRegistry registry;
    @Autowired private ArtifactService artifacts;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private FakeGateway gateway;

    @Test
    void committedResponseExecutesOnceAndForgedScopeNeverBecomesAuthority() throws Exception {
        AdminPrincipal owner = identities.setup("tool-admin", "tool-password-123");
        Project project = projects.create(owner.userId(), "Tool project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of());
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Create a short text", "tool-run").run();
        AgentRun run = runs.transition(owner.userId(), project.id(), queued.id(), 0,
                AgentRun.Status.RUNNING);
        Task lease = tasks.claimAgentTurns("llm-ledger-worker", 1).getFirst();
        assertThat(turnCommits.start(lease, "llm-ledger-worker").status())
                .isEqualTo(AgentRun.Status.RUNNING);
        TrustedToolContext trusted = new TrustedToolContext(owner.userId(), project.id(), run.id());
        List<Message> prompt = List.of(new UserMessage("Create a short text"));
        List<ToolCallback> definitions = registry.modelDefinitions(run.policySnapshot());

        checkpoints.reserve(owner.userId(), project.id(), run.id(), 0, 1, "test-fake",
                codec.request(prompt, definitions));
        assertThatThrownBy(() -> executor.execute(trusted, 0, "call-1"))
                .isInstanceOf(ApiProblemException.class);
        assertThat(jdbc.sql("select count(*) from tool_execution")
                .query(Long.class).single()).isZero();

        rounds.call(owner.userId(), project.id(), run.id(), 0, prompt, definitions, Map.of());
        assertThatThrownBy(() -> executor.executeLeased(trusted, 0, "unselected-call",
                lease, "llm-ledger-worker"))
                .isInstanceOf(ApiProblemException.class);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<JsonNode> first = pool.submit(() -> {
                start.await();
                return executor.executeLeased(trusted, 0, "call-1",
                        lease, "llm-ledger-worker");
            });
            Future<JsonNode> second = pool.submit(() -> {
                start.await();
                return executor.executeLeased(trusted, 0, "call-1",
                        lease, "llm-ledger-worker");
            });
            start.countDown();
            JsonNode result = first.get();
            assertThat(second.get()).isEqualTo(result);
            UUID artifactId = UUID.fromString(result.at("/createdIds/0").asText());
            ArtifactService.ArtifactView created = artifacts.get(owner.userId(), project.id(),
                    artifactId);
            assertThat(created.resourceDefaultVersion().createdByKind())
                    .isEqualTo(ArtifactVersion.CreatedByKind.AGENT);
            assertThat(created.resourceDefaultVersion().runId()).isEqualTo(run.id());
            assertThat(jdbc.sql("select count(*) from tool_execution")
                    .query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("select count(*) from artifact where project_id = :projectId")
                    .param("projectId", project.id()).query(Long.class).single()).isEqualTo(1);
            assertThatThrownBy(() -> executor.executeLeased(
                    new TrustedToolContext(UUID.randomUUID(), project.id(), run.id()),
                    0, "call-1", lease, "llm-ledger-worker"))
                    .isInstanceOf(ApiProblemException.class);
        }

        assertThat(turnCommits.complete(lease, "llm-ledger-worker"))
                .isEqualTo(AgentTurnCommitService.Decision.CONTINUE);
        Task nextLease = tasks.claimAgentTurns("llm-ledger-worker", 1).getFirst();
        assertThat(nextLease.input().path("stepIndex").asInt()).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).nextStepIndex()).isEqualTo(1);
        List<Message> continuation = conversation.afterToolRound(owner.userId(),
                project.id(), run.id(), 0);
        assertThat(continuation).hasSize(3);
        ToolResponseMessage reply = (ToolResponseMessage) continuation.get(2);
        assertThat(reply.getResponses().getFirst().id()).isEqualTo("call-1");
        assertThat(reply.getResponses().getFirst().responseData())
                .contains("\"status\":\"SUCCEEDED\"");
        assertThatThrownBy(() -> conversation.afterToolRound(UUID.randomUUID(),
                project.id(), run.id(), 0)).isInstanceOf(IllegalArgumentException.class);
        rounds.call(owner.userId(), project.id(), run.id(), 1,
                continuation, definitions, Map.of());
        assertThatThrownBy(() -> executor.executeLeased(trusted, 1, "call-2",
                nextLease, "llm-ledger-worker"))
                .isInstanceOf(ApiProblemException.class);
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :taskId")
                .param("taskId", nextLease.id()).update();
        Task reclaimed = tasks.claimAgentTurns("replacement-worker", 1).getFirst();
        assertThat(reclaimed.leaseEpoch()).isGreaterThan(nextLease.leaseEpoch());
        assertThatThrownBy(() -> executor.executeLeased(trusted, 1, "call-2",
                nextLease, "llm-ledger-worker"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("select count(*) from tool_execution")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from artifact where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(gateway.calls.get()).isEqualTo(2);
        assertThat(jdbc.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                .query(String.class).single()).isEqualTo(MigrationVersions.latest());
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway() {
            return new FakeGateway();
        }
    }

    /** The second model response deliberately attempts to inject a trusted identity field. */
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
            int call = calls.incrementAndGet();
            String arguments = call == 1
                    ? "{\"title\":\"Opening\",\"text\":\"Three shots\",\"format\":\"PLAIN_TEXT\"}"
                    : "{\"title\":\"Forged\",\"text\":\"Bad\",\"format\":\"PLAIN_TEXT\",\"ownerId\":\"spoof\"}";
            AssistantMessage output = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + call,
                            "function", "create_text", arguments))).build();
            if (call == 1) {
                AssistantMessage unselected = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("unselected-call",
                                "function", "create_text", arguments))).build();
                return new Exchange(1, new ChatResponse(List.of(new Generation(output),
                        new Generation(unselected))));
            }
            return new Exchange(1, new ChatResponse(List.of(new Generation(output))));
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, false, false);
        }
    }
}
