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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/** Mock 工具真正提交到 PostgreSQL 后，HTTP 仅公开有界、授权且可核实的动作摘要。 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=run-actions-integration-secret",
        "agenvas.llm.mode=mock"})
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

        // 使用无外部模型的真实持久化工具链；第三轮仅提出图片计划，尚未生成媒体。
        for (int step = 0; step < 3; step++) {
            assertThat(worker.runOnce("actions-test-worker")).isEqualTo(1);
        }
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        JsonNode initial = readActions(mvc, owner, path);
        assertThat(initial).hasSize(4);
        assertThat(initial).extracting(action -> action.path("toolName").asText())
                .containsExactly("create_text", "create_scene", "create_shots", "propose_generation_plan");
        assertThat(initial).extracting(action -> action.path("status").asText())
                .containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED", "WAITING_APPROVAL");
        assertThat(initial.get(3).path("summary").asText())
                .isEqualTo("已提出媒体计划，等待用户审批");
        for (JsonNode action : initial) {
            assertThat(action.propertyNames()).containsExactlyInAnyOrder(
                    "id", "stepIndex", "toolName", "status", "summary", "completedAt");
            assertThat(UUID.fromString(action.path("id").asText())).isNotNull();
            assertThat(Instant.parse(action.path("completedAt").asText())).isNotNull();
        }

        // 幂等重放不能增加动作；公开结果不随调用次数发生变化。
        executor.execute(new TrustedToolContext(owner.userId(), project.id(), run.id()),
                0, "mock-brief-0");
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
}
