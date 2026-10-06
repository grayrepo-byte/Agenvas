package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnCommitService;
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
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and synthetic model errors; automatic closure must never resend a request. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentFailureRecoveryPostgresIT.FakeConfig.class},
        properties = {"agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false",
                "agenvas.export.scheduler-enabled=false", "spring.main.allow-bean-definition-overriding=true"})
class AgentFailureRecoveryPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired TaskService tasks;
    @Autowired AgentTurnWorker worker;
    @Autowired AgentTurnCommitService commits;
    @Autowired FakeGateway gateway;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @Test void endsFatalTurnsAndRecoversLegacyBlockersWithoutChangingCheckpointsOrCompletedArtifacts() {
        AdminPrincipal owner = owner();
        var project = projects.create(owner.userId(), "Automatic ending fixture", Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Synthetic creator", "Create one text", List.of());
        var run = runs.create(owner.userId(), project.id(), agent.id(), "Create then fail", "fatal-fixture").run();
        assertThat(worker.runOnce("failure-worker")).isEqualTo(1);
        assertThat(worker.runOnce("failure-worker")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(jdbc.sql("select active_run_id is null from project where id=:id").param("id", project.id()).query(Boolean.class).single()).isTrue();
        String frozen = request(run.id());
        String policy = runs.get(owner.userId(), project.id(), run.id()).policySnapshot().toString();
        long eventsBefore = failureEvents(project.id());
        // A fixture of the old failure transition: the model Task is already FAILED,
        // yet the Run still occupies its project. No policy or checkpoint is rewritten.
        jdbc.sql("update agent_run set status = 'BLOCKED', completed_at = null where id = :id").param("id", run.id()).update();
        jdbc.sql("update project set active_run_id = :run where id = :project").param("run", run.id()).param("project", project.id()).update();
        assertThat(worker.runOnce("legacy-recovery-worker")).isZero();
        assertThat(commits.finishBlockedFailures(100)).isZero();
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(jdbc.sql("select active_run_id is null from project where id=:id").param("id", project.id()).query(Boolean.class).single()).isTrue();
        assertThat(request(run.id())).isEqualTo(frozen);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).policySnapshot().toString()).isEqualTo(policy);
        assertThat(gateway.count(run.id())).isEqualTo(2);
        assertThat(failureEvents(project.id())).isEqualTo(eventsBefore + 1);
        assertThat(jdbc.sql("select count(*) from artifact where project_id = :id").param("id", project.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :id").param("id", run.id()).query(Long.class).single()).isEqualTo(1);
        var next = runs.create(owner.userId(), project.id(), agent.id(), "Continue with the saved text", "next-fixture").run();
        assertThat(commits.finishBlockedFailures(100)).isZero();
        assertThat(jdbc.sql("select active_run_id from project where id=:id").param("id", project.id()).query(UUID.class).single()).isEqualTo(next.id());
        runs.cancel(owner.userId(), project.id(), next.id());
    }

    @Test void queuedAndUnknownMediaKeepTheRunBlockedUntilTheirStateIsSettled() {
        AdminPrincipal owner = owner();
        var project = projects.create(owner.userId(), "Uncertain media fixture", Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Synthetic creator", "Create one text", List.of());
        var run = runs.create(owner.userId(), project.id(), agent.id(), "Create then fail", "unknown-fixture").run();
        assertThat(worker.runOnce("failure-worker")).isEqualTo(1);
        var media = tasks.create(owner.userId(), project.id(), run.id(), "synthetic-media", Task.Kind.IMAGE_GENERATION,
                mapper.createObjectNode(), 1);
        assertThat(worker.runOnce("failure-worker")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.BLOCKED);
        assertThat(commits.finishBlockedFailures(100)).isZero();
        jdbc.sql("update task set status = 'UNKNOWN', error_code = 'PROVIDER_SUBMISSION_UNKNOWN' where id = :id").param("id", media.id()).update();
        assertThat(commits.finishBlockedFailures(100)).isZero();
        assertThat(jdbc.sql("select active_run_id from project where id=:id").param("id", project.id()).query(UUID.class).single()).isEqualTo(run.id());
        assertThat(gateway.count(run.id())).isEqualTo(2);
        jdbc.sql("update task set status = 'FAILED', completed_at = now() where id = :id").param("id", media.id()).update();
        assertThat(commits.finishBlockedFailures(100)).isEqualTo(1);
        assertThat(jdbc.sql("select active_run_id is null from project where id=:id").param("id", project.id()).query(Boolean.class).single()).isTrue();
        assertThat(gateway.count(run.id())).isEqualTo(2);
    }

    private String request(UUID run) {
        return jdbc.sql("select request_json::text from llm_turn where run_id = :id and step_index = 1").param("id", run).query(String.class).single();
    }
    private long failureEvents(UUID project) {
        return jdbc.sql("select count(*) from project_event where project_id = :id and type = 'agent.run.changed' and payload_json->>'status' = 'FAILED'")
                .param("id", project).query(Long.class).single();
    }
    private AdminPrincipal owner() {
        return jdbc.sql("select id from app_user where login_name = 'auto-end-admin'").query(UUID.class).optional()
                .map(id -> new AdminPrincipal(id, "auto-end-admin"))
                .orElseGet(() -> identities.setup("auto-end-admin", "synthetic-password-123"));
    }
    @TestConfiguration static class FakeConfig {
        @Bean @Primary FakeGateway fakeGateway() { return new FakeGateway(); }
    }
    static class FakeGateway implements ChatGateway {
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        int count(UUID run) { return calls.get(run.toString()).get(); }
        @Override public String configSource() { return "synthetic-auto-end"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context) {
            int count = calls.computeIfAbsent(context.get("runId").toString(), ignored -> new AtomicInteger()).incrementAndGet();
            if (count > 1) throw new IllegalStateException("Synthetic terminal model fault");
            var response = AssistantMessage.builder().content("Create a durable text before the synthetic failure.")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("saved-text", "function", "create_text",
                            "{\"title\":\"Retained result\",\"text\":\"Synthetic public text\",\"format\":\"PLAIN_TEXT\"}"))).build();
            return new Exchange(1, new ChatResponse(List.of(new Generation(response))));
        }
    }
}
