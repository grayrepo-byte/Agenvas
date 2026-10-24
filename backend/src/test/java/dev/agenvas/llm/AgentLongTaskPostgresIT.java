package dev.agenvas.llm;

import static dev.agenvas.testing.AgentFixture.configureDirector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentContextService;
import dev.agenvas.llm.application.AgentTurnCommitService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmRoundService;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL, synthetic model and virtual elapsed time; no real Provider or wall-clock soak. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentLongTaskPostgresIT.FakeConfig.class})
class AgentLongTaskPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired AgentInstanceService agents;
    @Autowired ProjectService projects;
    @Autowired AgentRunService runs;
    @Autowired AgentTurnWorker worker;
    @Autowired AgentTurnCommitService commits;
    @Autowired AgentContextService context;
    @Autowired LlmProtocolCodec codec;
    @Autowired LlmRoundService rounds;
    @Autowired ToolRegistry registry;
    @Autowired TaskService tasks;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired LongClock clock;
    @Autowired FakeGateway gateway;
    @Autowired WebApplicationContext webContext;
    private AdminPrincipal owner;
    private Project project;
    private AgentRun run;

    @BeforeAll void setupOwner() { owner = identities.setup("long-task-admin", "synthetic-long-task-password"); }
    @BeforeEach void setupRun() {
        jdbc.sql("update director_settings set context_window_tokens=32768").update();
        gateway.written = 0;
        gateway.compactions = 0;
        gateway.failed = false;
        gateway.invalidSummary = false;
        gateway.failAt = 21;
        gateway.finishAt = 70;
        project = projects.create(owner.userId(), "Synthetic long task", Project.AspectRatio.LANDSCAPE_16_9);
        var agent = configureDirector(agents, owner.userId(), project.id(), "Long task director", "Complete the requested work");
        run = runs.create(owner.userId(), project.id(), agent.id(), "Finish exactly 70 shot cards", UUID.randomUUID().toString()).run();
    }

    @Test void crossesSixteenHoursAndRepeatedCompactionsThenSurvivesATwoHourOutageWithoutDuplicateWrites() throws Exception {
        Instant start = clock.instant();
        boolean recovered = false;
        int iterations = 0;
        while (!currentRun().status().terminal()) {
            assertThat(++iterations).isLessThan(200);
            int before = gateway.written;
            assertThat(worker.runOnce("long-worker-" + iterations)).isEqualTo(1);
            Task latest = tasks.listByRun(owner.userId(), project.id(), run.id()).stream()
                    .filter(task -> task.output() != null && task.output().has("modelRetry") && task.status() == Task.Status.READY)
                    .findFirst().orElse(null);
            if (latest != null) {
                webAppContextSetup(webContext).apply(springSecurity()).build()
                        .perform(get("/api/v1/projects/" + project.id() + "/tasks/" + latest.id())
                                .with(authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()))))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.output.modelRetry.maxRetries").value(288));
                JsonNode frozen = request(latest.input().path("stepIndex").asInt());
                long effects = count("select count(*) from tool_execution where run_id=:run");
                clock.now = clock.now.plus(Duration.ofHours(2));
                assertThat(worker.runOnce("restored-after-outage")).isEqualTo(1);
                assertThat(request(latest.input().path("stepIndex").asInt())).isEqualTo(frozen);
                assertThat(count("select count(*) from tool_execution where run_id=:run")).isEqualTo(effects + 1);
                recovered = true;
            }
            if (gateway.written > before) clock.now = clock.now.plus(Duration.ofMinutes(15));
        }
        assertThat(currentRun().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(Duration.between(start, clock.instant())).isGreaterThan(Duration.ofHours(16));
        assertThat(recovered).isTrue();
        assertThat(gateway.written).isEqualTo(70);
        assertThat(gateway.compactions).isGreaterThan(2);
        assertThat(count("select count(*) from tool_execution where run_id=:run")).isEqualTo(70);
        long turns = count("select count(*) from llm_turn where run_id=:run");
        assertThat(turns).isGreaterThan(70);
        assertThat(count("select count(*) from usage_ledger where run_id=:run and entry_type='SETTLEMENT'"))
                .isEqualTo(turns);
        assertThat(count("select count(*) from task where run_id=:run and jsonb_exists(output_json, 'contextCompacted') "
                + "and (jsonb_exists(output_json, 'assistantText') or jsonb_exists(output_json, 'assistantStream'))")).isZero();
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id())).allSatisfy(task ->
                assertThat(task.status()).isEqualTo(Task.Status.SUCCEEDED));
        Task compacted = tasks.listByRun(owner.userId(), project.id(), run.id()).stream()
                .filter(task -> task.output().path("contextCompacted").asBoolean()).findFirst().orElseThrow();
        webAppContextSetup(webContext).apply(springSecurity()).build()
                .perform(get("/api/v1/projects/" + project.id() + "/tasks/" + compacted.id())
                        .with(authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.output.contextCompacted").value(true))
                .andExpect(jsonPath("$.output.assistantText").doesNotExist())
                .andExpect(jsonPath("$.output.assistantStream").doesNotExist());
    }

    @Test void restartReplaysACommittedCompactionAndFencesTheOldWorkerBeforeContinuing() {
        gateway.failAt = -1;
        gateway.finishAt = 1;
        Task lease = tasks.claimAgentTurns("before-restart", 1).getFirst();
        AgentRun started = commits.start(lease, "before-restart");
        JsonNode compact = context.prepare(syntheticHistory(), registry.modelDefinitions(started.policySnapshot()));
        assertThat(AgentContextService.isCompaction(compact)).isTrue();
        JsonNode response = rounds.callLeased(owner.userId(), project.id(), run.id(), 0, compact,
                List.of(), Map.of(), lease, "before-restart");
        assertThat(gateway.compactions).isEqualTo(1);
        assertThat(count("select count(*) from tool_execution where run_id=:run")).isZero();
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        assertThat(worker.runOnce("after-restart")).isEqualTo(1);
        assertThat(gateway.compactions).isEqualTo(1);
        assertThat(currentRun().nextStepIndex()).isEqualTo(1);
        assertThatThrownBy(() -> commits.complete(lease, "before-restart")).isInstanceOf(RuntimeException.class);
        assertThat(request(0)).isEqualTo(compact);
        assertThat(context.afterCompaction(compact, response).toString()).contains("70 shot cards", "Next unfinished shot");
        for (int index = 0; index < 15 && !currentRun().status().terminal(); index++) worker.runOnce("continue-restored");
        assertThat(currentRun().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(count("select count(*) from tool_execution where run_id=:run")).isEqualTo(1);
    }

    @Test void cancellationAfterACompactionCheckpointPreventsAnyContinuationOrToolExecution() {
        Task lease = tasks.claimAgentTurns("cancel-compaction", 1).getFirst();
        AgentRun started = commits.start(lease, "cancel-compaction");
        JsonNode compact = context.prepare(syntheticHistory(), registry.modelDefinitions(started.policySnapshot()));
        rounds.callLeased(owner.userId(), project.id(), run.id(), 0, compact, List.of(), Map.of(), lease, "cancel-compaction");
        runs.cancel(owner.userId(), project.id(), run.id());
        assertThatThrownBy(() -> commits.complete(lease, "cancel-compaction")).isInstanceOf(RuntimeException.class);
        clock.now = clock.now.plus(Duration.ofHours(16));
        assertThat(worker.runOnce("after-cancel")).isZero();
        assertThat(count("select count(*) from tool_execution where run_id=:run")).isZero();
        assertThat(gateway.compactions).isEqualTo(1);
    }

    @Test void aToolBearingCompactionIsRepairedDurablyAndItsToolCanNeverRun() {
        gateway.invalidSummary = true;
        gateway.failAt = -1;
        gateway.finishAt = 1;
        Task lease = tasks.claimAgentTurns("invalid-summary", 1).getFirst();
        AgentRun started = commits.start(lease, "invalid-summary");
        JsonNode compact = context.prepare(syntheticHistory(), registry.modelDefinitions(started.policySnapshot()));
        rounds.callLeased(owner.userId(), project.id(), run.id(), 0, compact, List.of(), Map.of(), lease, "invalid-summary");
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        assertThat(worker.runOnce("repair-summary")).isEqualTo(1);
        assertThat(count("select count(*) from tool_execution where run_id=:run")).isZero();
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id()).getLast().input().path("maxRepairAttempts").asInt())
                .isEqualTo(4);
        for (int index = 0; index < 15 && !currentRun().status().terminal(); index++) worker.runOnce("repaired-summary");
        assertThat(currentRun().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(request(0)).isEqualTo(compact);
        assertThat(gateway.compactions).isEqualTo(2);
        assertThat(count("select count(*) from tool_execution where run_id=:run")).isEqualTo(1);
    }

    private AgentRun currentRun() { return runs.get(owner.userId(), project.id(), run.id()); }
    private long count(String sql) { return jdbc.sql(sql).param("run", run.id()).query(Long.class).single(); }
    private JsonNode request(int step) {
        return mapper.readTree(jdbc.sql("select request_json::text from llm_turn where run_id=:run and step_index=:step")
                .param("run", run.id()).param("step", step).query(String.class).single());
    }
    private List<Message> syntheticHistory() {
        var messages = new ArrayList<Message>(List.of(new SystemMessage("Pinned safety rules"),
                new UserMessage("Current Run request: Finish exactly 70 shot cards")));
        for (int index = 0; index < 40; index++) {
            var assistant = AssistantMessage.builder().content("Verified public progress ".repeat(100)).toolCalls(List.of(
                    new AssistantMessage.ToolCall("read-" + index, "function", "read_project_summary", "{}"))).build();
            messages.add(assistant);
            messages.add(codec.toolResults(assistant, Map.of("read-" + index,
                    mapper.createObjectNode().put("status", "SUCCEEDED").put("versionId", "synthetic-version-" + index))));
        }
        return messages;
    }

    @TestConfiguration static class FakeConfig {
        @Bean @Primary LongClock longClock() { return new LongClock(); }
        @Bean @Primary FakeGateway fakeGateway() { return new FakeGateway(); }
    }
    static class LongClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-20T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    static class FakeGateway implements ChatGateway {
        volatile int written, compactions, finishAt, failAt;
        volatile boolean failed, invalidSummary;
        @Override public String configSource() { return "test-fake"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context) {
            assertThat(messages.size()).isLessThanOrEqualTo(LlmProtocolCodec.MAX_MESSAGES);
            AssistantMessage assistant;
            if (context.containsKey(AgentContextService.CALL_PURPOSE)) {
                assertThat(tools).isEmpty();
                compactions++;
                assistant = new AssistantMessage("{\"goal\":\"Finish exactly 70 shot cards\",\"completed\":[\""
                        + written + " cards saved\"],\"decisions\":[\"Keep continuity\"],\"pending\":[\"Next unfinished shot\"],"
                        + "\"nextSteps\":[\"Read current canvas before writing\"],\"references\":[],\"blockers\":[]}");
                if (invalidSummary) {
                    invalidSummary = false;
                    assistant = AssistantMessage.builder().content(assistant.getText()).toolCalls(List.of(
                            new AssistantMessage.ToolCall("forged-compaction-tool", "function", "create_text",
                                    "{\"title\":\"Must never be created\",\"text\":\"Forged action\",\"format\":\"PLAIN_TEXT\"}"))).build();
                }
            } else if (written == finishAt) assistant = new AssistantMessage("All requested cards are saved.");
            else {
                if (written + 1 == failAt && !failed) {
                    failed = true;
                    throw new UncheckedIOException(new SocketTimeoutException("Synthetic model outage"));
                }
                written++;
                assistant = AssistantMessage.builder().content("Verified creative progress for shot " + written + ": "
                        + "人物保持蓝色外套，场景与轴线延续上一镜，待完成后续镜头。".repeat(180))
                        .toolCalls(List.of(new AssistantMessage.ToolCall("create-" + written, "function", "create_text",
                                "{\"title\":\"Synthetic shot " + written + "\",\"text\":\"Immutable shot " + written
                                        + "\",\"format\":\"PLAIN_TEXT\"}"))).build();
            }
            return new Exchange(1, new ChatResponse(List.of(new Generation(assistant))));
        }
    }
}
