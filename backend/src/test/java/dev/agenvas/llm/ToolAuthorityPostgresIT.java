package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Model text cannot promote identity, approval, budget or run limits into trusted tool authority. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=tool-authority-integration-secret")
class ToolAuthorityPostgresIT {

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
    @Autowired private ArtifactService artifacts;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private LlmTurnCheckpointService checkpoints;
    @Autowired private LlmProtocolCodec codec;
    @Autowired private ToolRegistry registry;
    @Autowired private ToolExecutionService executor;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void forgedToolFieldsAndApprovalNameCannotCreateWork() {
        AdminPrincipal owner = identities.setup("tool-authority-integration-secret",
                "tool-authority-admin", "tool-password-123");
        Project project = projects.create(owner.userId(), "Tool authority project",
                Project.AspectRatio.LANDSCAPE_16_9);
        ArtifactService.ArtifactView brief = createBrief(owner.userId(), project.id());
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of(new AgentInstanceService.BindingInput(brief.artifact().id(),
                        brief.resourceDefaultVersion().id())));
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Draft one text", "authority-run").run();
        AgentRun running = runs.transition(owner.userId(), project.id(), queued.id(),
                queued.version(), AgentRun.Status.RUNNING);
        TrustedToolContext trusted = new TrustedToolContext(owner.userId(), project.id(),
                running.id());
        ObjectNode validDraft = draft(brief);

        // 模型可见的调用形状里没有任何身份、审批或预算字段；服务端策略同样不可由模型改写。
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String field : List.of("ownerId", "userId", "projectId", "approved", "budget",
                "maxModelTurns", "maxToolExecutions")) {
            ObjectNode forged = validDraft.deepCopy();
            if (field.startsWith("max")) forged.put(field, 999);
            else if ("approved".equals(field)) forged.put(field, true);
            else forged.put(field, UUID.randomUUID().toString());
            calls.add(new AssistantMessage.ToolCall("forged-" + field, "function",
                    "create_text", forged.toString()));
        }
        calls.add(new AssistantMessage.ToolCall("forged-approval", "function",
                "approve_plan", "{\"approved\":true}"));
        calls.add(new AssistantMessage.ToolCall("valid-draft", "function",
                "create_text", validDraft.toString()));
        int configVersion = running.policySnapshot().path("modelConfigVersion").asInt();
        String configSource = running.policySnapshot().path("modelConfigSource").asText();
        checkpoints.reserve(owner.userId(), project.id(), running.id(), 0,
                configVersion, configSource,
                codec.request(List.of(new UserMessage("Draft one text")),
                        registry.modelDefinitions()));
        AssistantMessage response = AssistantMessage.builder().content("")
                .toolCalls(calls).build();
        checkpoints.saveResponse(owner.userId(), project.id(), running.id(), 0,
                configVersion, codec.response(new ChatResponse(List.of(new Generation(response)))));

        for (String field : List.of("ownerId", "userId", "projectId", "approved", "budget",
                "maxModelTurns", "maxToolExecutions")) {
            assertThatThrownBy(() -> executor.execute(trusted, 0, "forged-" + field))
                    .as(field).isInstanceOfSatisfying(ApiProblemException.class, error -> {
                        assertThat(error.code()).isEqualTo("TOOL_ARGUMENT_INVALID");
                        // 拒绝原因必须是未知字段，而不是被当作有效参数接受。
                        assertThat(error.getMessage())
                                .contains("create_text has an unknown field");
                    });
        }
        assertThatThrownBy(() -> executor.execute(trusted, 0, "forged-approval"))
                .as("approve_plan").isInstanceOfSatisfying(ApiProblemException.class, error -> {
                    assertThat(error.code()).isEqualTo("TOOL_ARGUMENT_INVALID");
                    assertThat(error.getMessage()).contains("not allowlisted");
                });
        assertNoCommittedWork(project.id(), running.id());

        UUID createdId = UUID.fromString(executor.execute(trusted, 0, "valid-draft")
                .at("/createdIds/0").asText());
        ArtifactService.ArtifactView created =
                artifacts.get(owner.userId(), project.id(), createdId);
        assertThat(created.artifact().kind()).isEqualTo(Artifact.Kind.TEXT);
        assertThat(created.resourceDefaultVersion().createdByKind())
                .isEqualTo(ArtifactVersion.CreatedByKind.AGENT);
        assertThat(created.resourceDefaultVersion().runId()).isEqualTo(running.id());
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", running.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and kind in ('IMAGE_GENERATION', 'VIDEO_GENERATION')")
                .param("projectId", project.id()).query(Long.class).single()).isZero();
    }

    /** 被拒的模型权限必须只留下固定输入，既不产生产物，也不产生账本或计费条目。 */
    private void assertNoCommittedWork(UUID projectId, UUID runId) {
        assertThat(jdbc.sql("select count(*) from artifact where project_id = :projectId")
                .param("projectId", projectId).query(Long.class).single())
                .as("artifact").isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", runId).query(Long.class).single())
                .as("tool_execution").isZero();
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and kind in ('IMAGE_GENERATION', 'VIDEO_GENERATION')")
                .param("projectId", projectId).query(Long.class).single())
                .as("media_task").isZero();
        assertThat(jdbc.sql("select count(*) from usage_ledger where project_id = :projectId "
                        + "and task_id is not null and entry_type = 'RESERVATION'")
                .param("projectId", projectId).query(Long.class).single())
                .as("usage_reservation").isZero();
    }

    /** 生成一个同项目的固定文本输入，用作 Run 的绑定版本。 */
    private ArtifactService.ArtifactView createBrief(UUID ownerId, UUID projectId) {
        ObjectNode content = mapper.createObjectNode();
        content.put("format", "PLAIN_TEXT");
        content.put("text", "Write one short paragraph about the product");
        return artifacts.create(ownerId, projectId, Artifact.Kind.TEXT, "Brief", content);
    }

    /** 模型可见的合法调用形状只包含标题、正文和格式，不含任何服务端管理字段。 */
    private ObjectNode draft(ArtifactService.ArtifactView brief) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("title", "Draft");
        draft.put("text", brief.resourceDefaultVersion().content().path("text").asText());
        draft.put("format", "PLAIN_TEXT");
        return draft;
    }
}
