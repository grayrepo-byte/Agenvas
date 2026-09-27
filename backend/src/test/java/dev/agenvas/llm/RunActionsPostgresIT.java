package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.ToolExecutionRepository;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 测试假模型（非真实模型）的工具调用真正提交到 PostgreSQL 后，HTTP 仅公开有界、授权且可核实的动作摘要。 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, RunActionsPostgresIT.FakeConfig.class},
        properties = {
                "agenvas.identity.bootstrap-secret=run-actions-integration-secret",
                "agenvas.llm.scheduler-enabled=false"})
class RunActionsPostgresIT {

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
    @Autowired private AgentTurnWorker worker;
    @Autowired private ToolExecutionService executor;
    @Autowired private ToolExecutionRepository ledger;
    @Autowired private FakeGateway gateway;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext webContext;

    @Test
    void onlyCommittedPublicBusinessResultsAreListedWithinTheOwnedRun() throws Exception {
        AdminPrincipal owner = identities.setup("run-actions-integration-secret",
                "actions-admin", "actions-password-123");
        Project project = projects.create(owner.userId(), "Actions project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator",
                "PRIVATE_AGENT_PROMPT", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "PRIVATE_TOOL_ARGUMENT", "actions-run").run();
        MockMvc mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        String path = actionsPath(project.id(), run.id());
        assertThat(readActions(mvc, owner, path)).isEmpty();

        // 使用无外部模型的真实持久化工具链；四个回合依次读取、创建文本并修订，最后无工具结束。
        for (int step = 0; step < 4; step++) {
            assertThat(worker.runOnce("actions-test-worker")).isEqualTo(1);
        }
        // 工具执行不再停在任何等待审批的状态：Run 直接以成功终态结束，且没有遗留等待任务。
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(jdbc.sql("select count(*) from task where run_id = :runId "
                        + "and status <> 'SUCCEEDED'")
                .param("runId", run.id()).query(Long.class).single()).isZero();
        JsonNode initial = readActions(mvc, owner, path);
        assertThat(initial).hasSize(4);
        assertThat(initial).extracting(action -> action.path("toolName").asText())
                .containsExactlyInAnyOrder("read_project_summary", "read_selection",
                        "create_text", "revise_artifact");
        assertThat(initial).extracting(action -> action.path("status").asText())
                .containsOnly("SUCCEEDED");
        assertThat(initial.get(3).path("summary").asText())
                .isEqualTo("已创建产物的新内容版本");
        for (JsonNode action : initial) {
            assertThat(action.propertyNames()).containsExactlyInAnyOrder(
                    "id", "stepIndex", "toolName", "status", "summary", "completedAt");
            assertThat(UUID.fromString(action.path("id").asText())).isNotNull();
            assertThat(Instant.parse(action.path("completedAt").asText())).isNotNull();
        }

        // 幂等重放不能增加动作；公开结果不随调用次数发生变化。
        executor.execute(new TrustedToolContext(owner.userId(), project.id(), run.id()),
                0, "actions-summary");
        assertThat(readActions(mvc, owner, path)).isEqualTo(initial);

        // 模拟未提交完成的账本项，及内部响应中的敏感元数据，确认读取边界按字段筛选。
        assertThat(ledger.insertExecuting(UUID.randomUUID(), project.id(), run.id(), 2,
                "pending-call", "read_selection", "0".repeat(64), Instant.now())).isTrue();
        jdbc.sql("""
                        update tool_execution
                        set result_json = result_json || cast(:privateResult as jsonb)
                        where run_id = :runId and status = 'COMPLETED'
                        """)
                .param("runId", run.id()).param("privateResult", """
                        {"data":{"apiKey":"PRIVATE_KEY"},"rawToolArguments":"PRIVATE_RAW_ARGS",
                         "reasoning":"PRIVATE_REASONING","prompt":"PRIVATE_PROMPT"}
                        """).update();
        jdbc.sql("""
                        update llm_turn set response_json = response_json || cast(:metadata as jsonb)
                        where run_id = :runId
                        """)
                .param("runId", run.id())
                .param("metadata", "{\"privateReasoning\":\"PRIVATE_RESPONSE\"}").update();
        long eventCount = jdbc.sql("select count(*) from project_event where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single();
        assertThat(readActions(mvc, owner, path)).isEqualTo(initial);
        assertThat(initial.toString()).doesNotContain("PRIVATE_", "COMPLETED",
                "toolCallId", "argumentHash", "createdIds", "result_json");
        assertThat(jdbc.sql("select count(*) from project_event where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single()).isEqualTo(eventCount);
        assertThat(ledger.countByRun(project.id(), run.id())).isEqualTo(5);

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        AdminPrincipal outsider = new AdminPrincipal(UUID.randomUUID(), "outsider");
        mvc.perform(get(path).with(authentication(auth(outsider)))).andExpect(status().isNotFound());
        Project otherProject = projects.create(owner.userId(), "Other project",
                Project.AspectRatio.LANDSCAPE_16_9);
        mvc.perform(get(actionsPath(otherProject.id(), run.id()))
                        .with(authentication(auth(owner))))
                .andExpect(status().isNotFound());
        mvc.perform(get(actionsPath(project.id(), UUID.randomUUID()))
                        .with(authentication(auth(owner))))
                .andExpect(status().isNotFound());

        // 同一步的登记时间相同时使用 id 稳定排序；步骤优先于墙钟时间。
        jdbc.sql("""
                        update tool_execution set created_at = timestamptz '2030-01-01 00:00:00Z'
                        where run_id = :runId and step_index = 0
                        """).param("runId", run.id()).update();
        JsonNode tied = readActions(mvc, owner, path);
        assertThat(List.of(tied.get(0).path("id").asText(), tied.get(1).path("id").asText()))
                .isSorted();
        assertThat(tied).extracting(action -> action.path("stepIndex").asInt())
                .containsExactly(0, 0, 1, 2);

        // 防御性上限：即使测试直接写入超预算账本，读取也不得成为无界列表。
        for (int index = 0; index < 37; index++) {
            UUID id = UUID.randomUUID();
            assertThat(ledger.insertExecuting(id, project.id(), run.id(), 2,
                    "bounded-fixture-" + index, "read_selection", "1".repeat(64), Instant.now())).isTrue();
            assertThat(ledger.complete(id, mapper.readTree("""
                    {"status":"SUCCEEDED","userVisibleSummary":"有界历史测试记录"}
                    """), Instant.now())).isTrue();
        }
        assertThat(readActions(mvc, owner, path)).hasSize(40);
        assertThat(gateway.calls.get()).isEqualTo(4);
    }

    private JsonNode readActions(MockMvc mvc, AdminPrincipal owner, String path) throws Exception {
        String content = mvc.perform(get(path).with(authentication(auth(owner))))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(content);
    }

    private UsernamePasswordAuthenticationToken auth(AdminPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private String actionsPath(UUID projectId, UUID runId) {
        return "/api/v1/projects/" + projectId + "/runs/" + runId + "/actions";
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway(ObjectMapper mapper) {
            return new FakeGateway(mapper);
        }
    }

    /** 按回合产出已存在工具的固定调用序列，不使用任何外部模型。 */
    static class FakeGateway implements ChatGateway {
        private final ObjectMapper mapper;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile UUID createdArtifactId;
        private volatile long createdArtifactVersion;

        FakeGateway(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String configSource() { return "test-fake"; }

        @Override
        public int configVersion() { return 1; }

        @Override
        public Capabilities capabilities() { return new Capabilities(true, false, false); }

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            assertThat(tools.stream().map(tool -> tool.getToolDefinition().name()))
                    .contains("read_project_summary", "read_selection", "create_text",
                            "revise_artifact");
            List<AssistantMessage.ToolCall> toolCalls = switch (calls.incrementAndGet()) {
                case 1 -> List.of(
                        new AssistantMessage.ToolCall("actions-summary", "function",
                                "read_project_summary", "{}"),
                        new AssistantMessage.ToolCall("actions-selection", "function",
                                "read_selection", "{}"));
                case 2 -> List.of(new AssistantMessage.ToolCall("actions-text", "function",
                        "create_text", textArguments()));
                case 3 -> {
                    ToolResponseMessage reply = (ToolResponseMessage) messages.getLast();
                    JsonNode created = mapper.readTree(reply.getResponses()
                            .getFirst().responseData());
                    createdArtifactId = UUID.fromString(
                            created.path("createdIds").path(0).asText());
                    createdArtifactVersion = created.path("artifactVersions")
                            .path(createdArtifactId.toString()).longValue();
                    yield List.of(new AssistantMessage.ToolCall("actions-revise", "function",
                            "revise_artifact", revisionArguments()));
                }
                default -> List.of();
            };
            AssistantMessage response = AssistantMessage.builder().content("")
                    .toolCalls(toolCalls).build();
            return new Exchange(1, new ChatResponse(List.of(new Generation(response))));
        }

        /** 只提交允许的文本字段与固定内容。 */
        private String textArguments() {
            ObjectNode input = mapper.createObjectNode();
            input.put("title", "Draft");
            input.put("text", "Original draft text");
            input.put("format", "PLAIN_TEXT");
            return input.toString();
        }

        /** 依据上一回合提交的产物 ID 与版本修订，绝不凭空构造 ID。 */
        private String revisionArguments() {
            ObjectNode content = mapper.createObjectNode();
            content.put("format", "PLAIN_TEXT");
            content.put("text", "Revised draft text");
            ObjectNode revision = mapper.createObjectNode();
            revision.put("artifactId", createdArtifactId.toString());
            revision.put("expectedVersion", createdArtifactVersion);
            revision.set("content", content);
            return revision.toString();
        }
    }
}
