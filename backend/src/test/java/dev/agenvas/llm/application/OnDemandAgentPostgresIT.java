package dev.agenvas.llm.application;

import static dev.agenvas.testing.AgentFixture.configureDirector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.storyboard.application.StoryboardService;
import dev.agenvas.task.application.TaskService;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real transactions and lease recovery, with synthetic model outputs and no Provider traffic. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, OnDemandAgentPostgresIT.FakeConfig.class})
class OnDemandAgentPostgresIT {
    private static final String WORKER = "synthetic-on-demand-worker";
    private static final String CUSTOM = "Keep every user instruction. Preserve dialogue verbatim.";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning())
            POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired
    IdentityService identities;
    @Autowired
    AgentInstanceService agents;
    @Autowired
    ProjectService projects;
    @Autowired
    AgentRunService runs;
    @Autowired
    AgentTurnWorker worker;
    @Autowired
    AgentTurnCommitService commits;
    @Autowired
    InitialModelContextService initial;
    @Autowired
    LlmConversationService conversation;
    @Autowired
    LlmTurnCheckpointService checkpoints;
    @Autowired
    LlmTurnRepository turns;
    @Autowired
    ToolRegistry registry;
    @Autowired
    LlmProtocolCodec codec;
    @Autowired
    TaskService tasks;
    @Autowired
    StoryboardService storyboards;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    ScriptedGateway gateway;
    @Autowired
    MediaCapabilityService capabilities;
    private AdminPrincipal owner;
    private Project project;
    private AgentRun run;

    @BeforeAll
    void setupOwner() {
        owner = identities.setup("discovery-admin", "synthetic-discovery-password");
    }
    @BeforeEach
    void setupRun() {
        gateway.script.clear();
        gateway.requests.clear();
        project = projects.create(owner.userId(), "Synthetic on-demand project", Project.AspectRatio.LANDSCAPE_16_9);
        var director = configureDirector(agents, owner.userId(), project.id(), "Synthetic director", CUSTOM);
        run = runs.create(owner.userId(), project.id(), director.id(), "Complete the current requested task",
                UUID.randomUUID().toString()).run();
    }
    @AfterEach
    void stopRun() {
        runs.cancel(owner.userId(), project.id(), run.id());
    }

    @Test
    void ordinaryConversationCompletesWithoutAnyLoadOrBusinessEffect() {
        gateway.script.add(input -> {
            assertThat(input.names()).containsExactly("load_toolset");
            assertThat(input.messages()).anyMatch(message -> CUSTOM.equals(message.getText()));
            return new AssistantMessage("你好");
        });
        step();
        assertThat(current().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(toolCount()).isZero();
        assertThat(artifactCount()).isZero();
    }

    @Test
    void mediaDiscoveryPagesThroughEveryPublishedCapabilityBeyondForty() {
        var connection = capabilities.createConnection("Synthetic paged catalog", null);
        for (int index = 0; index < 41; index++)
            capabilities.publishCapability(connection.id(), "Synthetic image " + index, "MOCK_IMAGE");
        var expected = capabilities.publishedCandidates().stream()
                .map(candidate -> candidate.binding().capabilityId().toString())
                .collect(java.util.stream.Collectors.toSet());
        var observed = new ArrayList<String>();
        java.util.function.Consumer<JsonNode> collect = result -> {
            assertThat(result.path("offset").asInt()).isEqualTo(observed.size());
            assertThat(result.path("total").asInt()).isEqualTo(expected.size());
            assertThat(result.path("capabilities").size()).isBetween(1, 20);
            result.path("capabilities").forEach(entry -> observed.add(entry.path("capabilityId").asText()));
        };
        gateway.script.add(input -> call("load-media", "load_toolset", "{\"toolsets\":[\"MEDIA\"]}"));
        gateway.script.add(input -> call("catalog-0", "list_media_capabilities", "{\"limit\":20}"));
        gateway.script.add(input -> {
            collect.accept(input.lastResult());
            assertThat(input.lastResult().path("nextOffset").asInt()).isEqualTo(20);
            return call("catalog-20", "list_media_capabilities", "{\"offset\":20,\"limit\":20}");
        });
        gateway.script.add(input -> {
            collect.accept(input.lastResult());
            assertThat(input.lastResult().path("nextOffset").asInt()).isEqualTo(40);
            return call("catalog-40", "list_media_capabilities", "{\"offset\":40,\"limit\":20}");
        });
        gateway.script.add(input -> {
            collect.accept(input.lastResult());
            assertThat(input.lastResult().path("nextOffset").isNull()).isTrue();
            return call("catalog-end", "list_media_capabilities", "{\"offset\":" + expected.size() + "}");
        });
        gateway.script.add(input -> {
            assertThat(input.lastResult().path("capabilities")).isEmpty();
            assertThat(input.lastResult().path("offset").asInt()).isEqualTo(expected.size());
            assertThat(input.lastResult().path("nextOffset").isNull()).isTrue();
            return new AssistantMessage("Read the complete published catalog; no media generated.");
        });
        for (int index = 0; index < 6; index++)
            step();
        assertThat(observed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(current().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(artifactCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"offset\":-1}", "{\"offset\":2147483648}", "{\"offset\":99999}",
            "{\"limit\":0}", "{\"limit\":41}", "{\"offset\":1.5}", "{\"projectId\":\"spoof\"}"})
    void malformedMediaCatalogPagesAreRejectedWithoutBusinessEffects(String arguments) {
        gateway.script.add(input -> call("load-media", "load_toolset", "{\"toolsets\":[\"MEDIA\"]}"));
        gateway.script.add(input -> call("bad-page", "list_media_capabilities", arguments));
        gateway.script.add(input -> {
            assertThat(input.messages().toString()).contains("TOOL_ARGUMENT_INVALID", "REJECTED");
            return new AssistantMessage("Invalid catalog page was rejected.");
        });
        for (int index = 0; index < 3; index++)
            step();
        assertThat(current().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(artifactCount()).isZero();
    }

    @Test
    void changingWorkflowLoadsMoreGroupsThenSavesFormalShotsAndSupportingText() {
        gateway.script.add(input -> call("load", "load_toolset", "{\"toolsets\":[\"CANVAS\",\"TEXT\"]}"));
        gateway.script.add(input -> {
            assertThat(input.names()).contains("read_canvas", "create_text").doesNotContain("write_storyboard_shots");
            return call("load-more", "load_toolset",
                    "{\"toolsets\":[\"STORYBOARD\",\"MEDIA\",\"COMPOSITION\",\"INSPECTION\",\"LAYOUT\"]}");
        });
        gateway.script.add(input -> {
            assertThat(input.names()).contains("write_storyboard_shots", "propose_media_generation", "compose_video",
                    "read_media_sample", "create_text");
            assertThat(input.messages().getFirst().getText()).contains("Append at most six shots",
                    "bounded speech speedup");
            return call("board", "read_storyboard", "{}");
        });
        gateway.script.add(input -> call("shot", "write_storyboard_shots", "{\"expectedVersion\":"
                + input.lastResult().path("data").path("version").asLong() + ",\"shots\":[{\"code\":\"SHOT-001\","
                + "\"description\":\"A synthetic wide shot, camera static.\",\"dialogue\":\"Hello.\",\"durationSeconds\":3}]}"));
        gateway.script.add(input -> call("text", "create_text",
                "{\"title\":\"Review\",\"text\":\"SHOT-001 saved; media still pending.\",\"format\":\"PLAIN_TEXT\"}"));
        gateway.script.add(input -> call("capabilities", "list_media_capabilities", "{}"));
        gateway.script.add(input -> new AssistantMessage("Saved the shot and review; no media generated."));
        for (int i = 0; i < 7; i++)
            step();
        assertThat(current().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(storyboards.get(owner.userId(), project.id()).shots()).hasSize(1);
        assertThat(storyboards.get(owner.userId(), project.id()).shots().getFirst().dialogue()).isEqualTo("Hello.");
        assertThat(artifactCount()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from agent_media_approval where run_id=:run").param("run", run.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void loadAndHiddenWriteInOneBatchRollBackTogetherAndRepairDoesNotActivateAnything() {
        gateway.script.add(input -> AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("load", "function", "load_toolset", "{\"toolsets\":[\"TEXT\"]}"),
                new AssistantMessage.ToolCall("hidden", "function", "create_text",
                        "{\"title\":\"Hidden\",\"text\":\"must not save\",\"format\":\"PLAIN_TEXT\"}")))
                .build());
        step();
        assertThat(toolCount()).isZero();
        assertThat(artifactCount()).isZero();
        gateway.script.add(input -> {
            assertThat(input.names()).containsExactly("load_toolset");
            assertThat(input.messages().toString()).contains("REJECTED", "No tools from that response were applied");
            return call("valid-load", "load_toolset", "{\"toolsets\":[\"TEXT\"]}");
        });
        step();
        gateway.script.add(input -> {
            assertThat(input.names()).contains("create_text");
            return call("valid-write", "create_text",
                    "{\"title\":\"Allowed\",\"text\":\"saved after load\",\"format\":\"PLAIN_TEXT\"}");
        });
        step();
        gateway.script.add(input -> new AssistantMessage("Done"));
        step();
        assertThat(current().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(toolCount()).isEqualTo(2);
        assertThat(artifactCount()).isEqualTo(1);
    }

    @Test
    void loadingCannotWidenRunPermissionsOrAuthorizeUnapprovedGeneration() {
        jdbc.sql(
                "update agent_run set policy_snapshot_json=jsonb_set(policy_snapshot_json,'{allowedTools}', '[\"load_toolset\",\"list_media_capabilities\"]'::jsonb) where id=:run")
                .param("run", run.id()).update();
        gateway.script.add(input -> call("load", "load_toolset", "{\"toolsets\":[\"MEDIA\"]}"));
        step();
        gateway.script.add(input -> {
            assertThat(input.names()).containsExactly("load_toolset", "list_media_capabilities");
            return call("forged-approval", "propose_media_generation", "{\"approved\":true,\"outputs\":[]}");
        });
        step();
        assertThat(toolCount()).isEqualTo(1);
        assertThat(artifactCount()).isZero();
        gateway.script.add(input -> {
            assertThat(input.names()).containsExactly("load_toolset", "list_media_capabilities");
            return new AssistantMessage("Generation is unavailable for this Run.");
        });
        step();
        assertThat(current().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }

    @Test
    void transientFailureReplaysTheExactLoadedRequestWithoutLosingSchemas() {
        gateway.script.add(input -> call("load", "load_toolset", "{\"toolsets\":[\"CANVAS\",\"TEXT\"]}"));
        step();
        gateway.script.add(input -> {
            throw new UncheckedIOException(new SocketTimeoutException("synthetic timeout"));
        });
        step();
        var saved = turns.find(project.id(), run.id(), 1).orElseThrow().request();
        assertThat(saved.path("tools").toString()).contains("create_text", "read_canvas");
        jdbc.sql("update task set next_action_at=now()-interval '1 second' where run_id=:run and status='READY'")
                .param("run", run.id()).update();
        gateway.script.add(input -> new AssistantMessage("Recovered"));
        step();
        assertThat(current().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(turns.find(project.id(), run.id(), 1).orElseThrow().request()).isEqualTo(saved);
        assertThat(gateway.requests.get(2)).isEqualTo(gateway.requests.get(1));
        assertThat(toolCount()).isEqualTo(1);
    }

    @Test
    void reclaimedLeaseUsesReservedDefinitionsAndCommittedReplyReplayDoesNotReloadTools() {
        gateway.script.add(input -> call("load", "load_toolset", "{\"toolsets\":[\"CANVAS\",\"TEXT\"]}"));
        step();
        var lease = tasks.claimAgentTurns("lost-worker", 1).getFirst();
        var running = commits.start(lease, "lost-worker");
        var history = conversation.afterToolRound(owner.userId(), project.id(), run.id(), 0);
        var prior = turns.find(project.id(), run.id(), 0).orElseThrow();
        var loaded = registry.continuationDefinitions(running.policySnapshot(), prior.request(), history, codec);
        var reserved = codec.request(InitialModelContextService.forTools(running.policySnapshot(), history,
                registry.initialDefinitions(running.policySnapshot()), loaded), loaded);
        ((tools.jackson.databind.node.ObjectNode) reserved.path("tools").get(0)).put("description",
                "Frozen deployment loader description");
        checkpoints.reserveLeased(owner.userId(), project.id(), run.id(), 1, 1, gateway.configSource(), reserved, lease,
                "lost-worker");
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:task").param("task", lease.id())
                .update();
        gateway.script.add(input -> {
            assertThat(input.names()).contains("create_text", "read_artifacts");
            assertThat(input.tools().getFirst().getToolDefinition().description())
                    .isEqualTo("Frozen deployment loader description");
            return new AssistantMessage("Recovered reserved turn");
        });
        step();
        assertThat(current().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(turns.find(project.id(), run.id(), 1).orElseThrow().request()).isEqualTo(reserved);
        assertThat(toolCount()).isEqualTo(1);
        assertThatThrownBy(() -> conversation.afterToolRound(UUID.randomUUID(), project.id(), run.id(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void step() {
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
    }
    private AgentRun current() {
        return runs.get(owner.userId(), project.id(), run.id());
    }
    private long toolCount() {
        return jdbc.sql("select count(*) from tool_execution where run_id=:run").param("run", run.id())
                .query(Long.class).single();
    }
    private long artifactCount() {
        return jdbc.sql("select count(*) from artifact where project_id=:project").param("project", project.id())
                .query(Long.class).single();
    }
    private static AssistantMessage call(String id, String name, String arguments) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments))).build();
    }
    record Input(List<Message> messages, List<ToolCallback> tools, ObjectMapper mapper) {
        List<String> names() {
            return tools.stream().map(tool -> tool.getToolDefinition().name()).toList();
        }
        JsonNode lastResult() {
            for (int i = messages.size() - 1; i >= 0; i--)
                if (messages.get(i) instanceof ToolResponseMessage reply)
                    return mapper.readTree(reply.getResponses().getLast().responseData());
            throw new AssertionError("No committed tool reply");
        }
    }
    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        ScriptedGateway discoveryGateway(ObjectMapper mapper) {
            return new ScriptedGateway(mapper);
        }
    }
    static class ScriptedGateway implements ChatGateway {
        final ArrayDeque<Function<Input, AssistantMessage>> script = new ArrayDeque<>();
        final List<JsonNode> requests = new ArrayList<>();
        final ObjectMapper mapper;
        ScriptedGateway(ObjectMapper mapper) {
            this.mapper = mapper;
        }
        @Override
        public String configSource() {
            return "synthetic-on-demand";
        }
        @Override
        public int configVersion() {
            return 1;
        }
        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, false, false);
        }
        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context) {
            requests.add(new LlmProtocolCodec(mapper).request(messages, tools));
            assertThat(script).as("Expected another synthetic response").isNotEmpty();
            return new Exchange(1, new ChatResponse(
                    List.of(new Generation(script.removeFirst().apply(new Input(messages, tools, mapper))))));
        }
    }
}
