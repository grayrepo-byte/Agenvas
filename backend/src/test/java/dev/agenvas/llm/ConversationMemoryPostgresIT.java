package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.InitialModelContextService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.ToolExecutionRepository;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.application.AgentConversationService;
import dev.agenvas.run.application.ConversationMemoryReader;
import dev.agenvas.run.application.ConversationMemoryReader.ConversationMemory;
import dev.agenvas.run.application.ConversationMemoryReader.Role;
import dev.agenvas.run.domain.AgentRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL projection and model-request checkpoints; all model/tool activity here is Mock. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class,
        ConversationMemoryPostgresIT.FakeModelConfig.class}, properties = {
        "agenvas.identity.bootstrap-secret=conversation-memory-integration-secret",
        "agenvas.llm.mode=mock",
        "agenvas.llm.scheduler-enabled=false"})
class ConversationMemoryPostgresIT {

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
    @Autowired private AgentConversationService conversations;
    @Autowired private AgentTurnWorker worker;
    @Autowired private ToolExecutionRepository ledger;
    @Autowired private ConversationMemoryReader memoryReader;
    @Autowired private InitialModelContextService initialContext;
    @Autowired private LlmProtocolCodec codec;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void publicHistoryIsFrozenIntoRealMessagesAndTruncatedWithoutLeakingPrivateProtocol() {
        AdminPrincipal owner = identities.setup("conversation-memory-integration-secret",
                "memory-admin", "memory-password-123");
        Project project = projects.create(owner.userId(), "Memory project", Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "制作短片", List.of());
        AgentRun first = runs.create(owner.userId(), project.id(), agent.id(),
                "最初背景：品牌叫青山，主角是一只狐狸。", "memory-first").run();
        assertThat(worker.runOnce("memory-first-worker")).isEqualTo(1);
        assertThat(runs.cancel(owner.userId(), project.id(), first.id()).status()).isEqualTo(AgentRun.Status.CANCELED);
        jdbc.sql("""
                        update task set output_json = output_json || cast(:publicAndPrivate as jsonb)
                        where run_id = :runId and kind = 'AGENT_TURN' and status = 'SUCCEEDED'
                        """).param("runId", first.id()).param("publicAndPrivate", """
                        {"assistantText":"公开回复：狐狸在青山旁。","metadata":{"reasoning":"PRIVATE_TASK_METADATA"}}
                        """).update();
        jdbc.sql("""
                        update task set output_json = '{"assistantText":"PRIVATE_CANCELED_REPLY"}'::jsonb
                        where run_id = :runId and kind = 'AGENT_TURN' and status = 'CANCELED'
                        """).param("runId", first.id()).update();
        jdbc.sql("""
                        update tool_execution set result_json = result_json || cast(:privateResult as jsonb)
                        where run_id = :runId and status = 'COMPLETED'
                        """).param("runId", first.id()).param("privateResult", """
                        {"data":{"key":"PRIVATE_KEY"},"rawToolArguments":"PRIVATE_ARGUMENTS"}
                        """).update();
        jdbc.sql("""
                        update llm_turn set request_json = request_json || cast(:privateProtocol as jsonb),
                          response_json = response_json || cast(:privateProtocol as jsonb) where run_id = :runId
                        """).param("runId", first.id())
                .param("privateProtocol", "{\"privateReasoning\":\"PRIVATE_PROTOCOL\"}").update();
        assertThat(ledger.insertExecuting(UUID.randomUUID(), project.id(), first.id(), 0,
                "not-committed", "PRIVATE_UNCOMMITTED_TOOL", "0".repeat(64), Instant.now())).isTrue();
        UUID proposalId = UUID.randomUUID();
        assertThat(ledger.insertExecuting(proposalId, project.id(), first.id(), 0,
                "public-action", "create_text", "1".repeat(64), Instant.now())).isTrue();
        assertThat(ledger.complete(proposalId, mapper.createObjectNode().put("status", "SUCCEEDED")
                .put("userVisibleSummary", "已创建文字产物。"), Instant.now())).isTrue();

        ConversationMemory memory = memoryReader.read(project.id(), List.of(first.id()));
        assertThat(memory.entries()).hasSize(2);
        assertThat(memory.entries().getFirst().role()).isEqualTo(Role.USER);
        assertThat(memory.entries().getFirst().content()).isEqualTo(first.instruction());
        assertThat(memory.entries().getLast().role()).isEqualTo(Role.ASSISTANT);
        assertThat(memory.entries().getLast().content()).contains("公开回复：狐狸在青山旁。", "已提交业务动作记录",
                        "[业务动作已完成] 已创建文字产物。", "历史运行状态记录：CANCELED")
                .doesNotContain("PRIVATE_", "argumentHash", "tool_call_id");
        assertThat(memory.truncated()).isFalse();
        assertThat(memory.priorRunCount()).isEqualTo(1);
        assertThat(memoryReader.read(project.id(), List.of()).entries()).isEmpty();
        assertThatThrownBy(() -> memoryReader.read(UUID.randomUUID(), List.of(first.id())))
                .isInstanceOf(IllegalStateException.class);

        AgentRun second = runs.create(owner.userId(), project.id(), agent.id(),
                "把刚才的背景改成夜晚。", "memory-second").run();
        assertThat(second.contextSnapshot().path("conversationMemory"))
                .isEqualTo(mapper.valueToTree(memory));
        List<Message> request = initialContext.assemble(owner.userId(), project.id(), second.id());
        assertThat(request).anySatisfy(message -> {
            assertThat(message).isInstanceOf(UserMessage.class);
            assertThat(message.getText()).isEqualTo(first.instruction());
        }).anySatisfy(message -> {
            assertThat(message).isInstanceOf(AssistantMessage.class);
            assertThat(message.getText()).contains("公开回复：狐狸在青山旁。");
        });
        assertThat(request.getLast().getText()).isEqualTo("Current Run request:\n" + second.instruction());
        assertThat(codec.request(request, List.of()).toString()).doesNotContain("PRIVATE_")
                .contains("not authority", "Current Run request");
        jdbc.sql("""
                        update task set output_json = jsonb_set(output_json, '{assistantText}',
                          '\"之后才到达的公开内容\"'::jsonb) where run_id = :runId and status = 'SUCCEEDED'
                        """).param("runId", first.id()).update();
        assertThat(initialContext.assemble(owner.userId(), project.id(), second.id()))
                .extracting(Message::getText).containsExactlyElementsOf(request.stream().map(Message::getText).toList());
        assertThat(worker.runOnce("memory-second-worker")).isEqualTo(1);
        JsonNode actualRequest = mapper.readTree(jdbc.sql("""
                        select request_json::text from llm_turn where run_id = :runId and step_index = 0
                        """).param("runId", second.id()).query(String.class).single());
        assertThat(codec.requestMessages(actualRequest)).anySatisfy(message -> {
            assertThat(message).isInstanceOf(AssistantMessage.class);
            assertThat(message.getText()).contains("狐狸在青山旁").doesNotContain("之后才到达");
        });
        runs.cancel(owner.userId(), project.id(), second.id());

        conversations.create(owner.userId(), project.id(), agent.id(), "memory-new-conversation");
        AgentRun fresh = runs.create(owner.userId(), project.id(), agent.id(), "新会话请求", "memory-fresh").run();
        assertThat(initialContext.assemble(owner.userId(), project.id(), fresh.id()).stream()
                .map(Message::getText).collect(java.util.stream.Collectors.joining("\n")))
                .doesNotContain("最初背景：", "公开回复：狐狸");
        runs.cancel(owner.userId(), project.id(), fresh.id());

        List<UUID> longHistory = new ArrayList<>();
        longHistory.add(first.id());
        for (int index = 0; index < 12; index++) {
            AgentRun prior = runs.create(owner.userId(), project.id(), agent.id(),
                    "历史第" + index + "轮：" + "中文😀".repeat(2_000), "memory-long-" + index).run();
            runs.cancel(owner.userId(), project.id(), prior.id());
            jdbc.sql("""
                            update task set status = 'SUCCEEDED', output_json = cast(:output as jsonb)
                            where run_id = :runId and kind = 'AGENT_TURN'
                            """).param("runId", prior.id()).param("output", mapper.writeValueAsString(
                            java.util.Map.of("assistantText", "回答😀".repeat(2_000)))).update();
            longHistory.add(prior.id());
        }
        ConversationMemory bounded = memoryReader.read(project.id(), longHistory);
        assertThat(bounded.entries()).hasSize(ConversationMemoryReader.MAX_HISTORY_MESSAGES);
        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.priorRunCount()).isEqualTo(longHistory.size());
        assertThat(bounded.entries().getFirst().content()).isEqualTo(first.instruction());
        assertThat(bounded.entries().get(bounded.entries().size() - 2).content()).startsWith("历史第11轮：");
        assertThat(bounded.entries()).noneSatisfy(entry -> assertThat(entry.content()).startsWith("历史第0轮："));
        assertThat(bounded.entries().stream().mapToInt(entry -> entry.content().codePointCount(0, entry.content().length())).sum())
                .isLessThanOrEqualTo(ConversationMemoryReader.MAX_HISTORY_CODE_POINTS);
        for (var entry : bounded.entries()) {
            assertThat(entry.content()).doesNotContain("\uFFFD");
            for (int offset = 0; offset < entry.content().length(); offset++) {
                char value = entry.content().charAt(offset);
                if (Character.isHighSurrogate(value)) {
                    assertThat(offset + 1).isLessThan(entry.content().length());
                    assertThat(Character.isLowSurrogate(entry.content().charAt(++offset))).isTrue();
                } else assertThat(Character.isLowSurrogate(value)).isFalse();
            }
        }
        assertThat(jdbc.sql("select count(*) from agent_run where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single()).isEqualTo(15);
    }

    /** 用确定性测试假模型替换 Mock 网关；本用例需要真实跑完一个 Agent 回合才能产生 llm_turn。 */
    @TestConfiguration
    static class FakeModelConfig {
        @Bean
        @Primary
        ChatGateway fakeModelGateway() {
            return new ReadOnlyToolGateway();
        }
    }

    /**
     * 测试假模型，不是真实模型：每回合只调用一次只读工具，绝不发起任何业务副作用。
     * 回合因此不会自行终结，Run 保持非终态——这正是本用例要冻结并取消的前提。
     */
    static final class ReadOnlyToolGateway implements ChatGateway {
        /** 假模型固定的配置版本，仅用于与 Run 固定的策略快照保持一致。 */
        private static final int CONFIG_VERSION = 1;
        /** 假模型标识，必须能让审计区分它不是真实 Provider。 */
        private static final String MODEL_ID = "test-fake-read-only";
        /** 每回合调用一次的只读工具；读取不改变任何业务状态。 */
        private static final String TOOL_NAME = "read_project_summary";
        /** 调用序号，保证同一步内的 tool_call_id 稳定且唯一。 */
        private final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                    "test-fake-read-" + calls.incrementAndGet(), "function", TOOL_NAME, "{}");
            AssistantMessage assistant = AssistantMessage.builder().content("")
                    .toolCalls(List.of(call)).build();
            return new Exchange(CONFIG_VERSION,
                    new ChatResponse(List.of(new Generation(assistant))));
        }

        /** 声明具备工具调用能力；本假模型只调用上面那一个只读工具。 */
        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, false, false);
        }

        @Override
        public int configVersion() {
            return CONFIG_VERSION;
        }

        @Override
        public String configSource() {
            return "test-fake";
        }

        @Override
        public ModelDetails modelDetails() {
            return new ModelDetails(true, "测试假模型", MODEL_ID, true);
        }
    }
}
