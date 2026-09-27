package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 默认 Mock 模式下 Agent 回合必须能走完：Mock 网关接受工具定义但只回文本，因此 Run 正常结束，
 * 不产生任何业务副作用。这是「Agent 一次运行用收敛后的工具目录走完并正确结束」的端到端验证。
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=mock-agent-turn-integration-secret",
        "agenvas.llm.mode=mock",
        "agenvas.llm.scheduler-enabled=false"})
class MockAgentTurnPostgresIT {

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
    @Autowired private JdbcClient jdbc;

    @Test
    void completesAnAgentRunWithoutAnyToolSideEffect() {
        AdminPrincipal owner = identities.setup("mock-agent-turn-integration-secret",
                "mock-turn-admin", "mock-turn-password-123");
        Project project = projects.create(owner.userId(), "Mock turn project",
                Project.AspectRatio.LANDSCAPE_16_9);
        UUID agent = agents.create(owner.userId(), project.id(), "Creator",
                "Write a short line", List.of()).id();
        AgentRun run = runs.create(owner.userId(), project.id(), agent,
                "写一句话", "mock-turn-run").run();

        assertThat(worker.runOnce("mock-turn")).isEqualTo(1);

        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(artifactCount(project.id())).isZero();
        assertThat(toolExecutionCount(run.id())).isZero();
        assertThat(firstTurnResponse(run.id()))
                .contains("演示文字，非真实模型生成。")
                .contains("写一句话");
    }

    private int artifactCount(UUID projectId) {
        return jdbc.sql("select count(*) from artifact where project_id = :project")
                .param("project", projectId).query(Integer.class).single();
    }

    private int toolExecutionCount(UUID runId) {
        return jdbc.sql("select count(*) from tool_execution where run_id = :run")
                .param("run", runId).query(Integer.class).single();
    }

    private String firstTurnResponse(UUID runId) {
        return jdbc.sql("select response_json::text from llm_turn where run_id = :run"
                + " and step_index = 0").param("run", runId).query(String.class).single();
    }
}
