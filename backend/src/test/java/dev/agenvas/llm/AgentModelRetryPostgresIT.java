package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.AgentTurnCommitService;
import dev.agenvas.llm.application.InitialModelContextService;
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
import java.time.Instant;
import java.time.Duration;
import java.util.function.Consumer;
import dev.agenvas.audit.domain.LlmStreamLog;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

/** Synthetic model failures with real PostgreSQL and a controlled clock; no Provider traffic. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentModelRetryPostgresIT.FakeConfig.class})
class AgentModelRetryPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private AgentTurnWorker worker;
    @Autowired private FakeGateway gateway;
    @Autowired private RetryClock clock;
    @Autowired private AgentTurnCommitService commits;
    @Autowired private InitialModelContextService context;
    @Autowired private LlmRoundService rounds;
    @Autowired private ToolRegistry registry;
    @Autowired private JdbcClient jdbc;
    private AdminPrincipal owner;
    private Project project;
    private AgentRun run;
    @BeforeAll void setupOwner() {
        owner = identities.setup("retry-admin", "retry-password-123");
    }
    @BeforeEach void setupRun() {
        clock.ticking = false;
        // Exact completion-time assertions use PostgreSQL's persisted microsecond precision.
        clock.now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        gateway.calls.set(0);
        gateway.failures = 1;
        gateway.toolCalling = true;
        gateway.createFirstTool = false;
        gateway.blockRequest = false;
        gateway.lastSink = null;
        project = projects.create(owner.userId(), "Retry project", Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Answer", List.of());
        run = runs.create(owner.userId(), project.id(), agent.id(), "Answer", "retry-run").run();
    }
    @AfterEach void stopAnyUnfinishedTestRun() { runs.cancel(owner.userId(), project.id(), run.id()); }
    private Task task() { return tasks.listByRun(owner.userId(), project.id(), run.id()).getFirst(); }
    @Test void transientTimeoutDefersThenRecoversWithoutRepeatingTheLogicalTurn() {
        assertThat(worker.runOnce("retry-worker")).isEqualTo(1);
        Task waiting = task();
        assertThat(waiting.status()).isEqualTo(Task.Status.READY);
        assertThat(waiting.errorCode()).isEqualTo("LLM_CALL_TIMEOUT");
        assertThat(waiting.nextActionAt()).isAfter(clock.instant());
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.RUNNING);
        assertThat(worker.runOnce("retry-worker")).isZero();
        clock.now = waiting.nextActionAt();
        assertThat(worker.runOnce("retry-worker")).isEqualTo(1);
        assertThat(task().id()).isEqualTo(waiting.id());
        assertThat(task().status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(gateway.calls.get()).isEqualTo(2);
    }
    @Test void tenRetriesThenAbandonAndReleaseTheProjectSlot() {
        gateway.failures = Integer.MAX_VALUE;
        for (int index = 0; index <= 10; index++) {
            assertThat(worker.runOnce("retry-worker")).isEqualTo(1);
            if (index < 10) {
                assertThat(task().status()).isEqualTo(Task.Status.READY);
                assertThat(task().output().at("/modelRetry/retryCount").asInt()).isEqualTo(index + 1);
                clock.now = task().nextActionAt();
            }
        }
        assertThat(gateway.calls).hasValue(11);
        assertThat(task().status()).isEqualTo(Task.Status.FAILED);
        assertThat(task().errorCode()).isEqualTo("LLM_RETRY_EXHAUSTED");
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(jdbc.sql("select active_run_id is null from project where id=:id")
                .param("id", project.id()).query(Boolean.class).single()).isTrue();
        assertThat(worker.runOnce("retry-worker")).isZero();
    }
    @Test void restartAfterFiveMinuteDeadlineAbandonsWithoutAnotherRequest() {
        worker.runOnce("first-worker");
        Instant firstFailure = Instant.parse(task().output().at("/modelRetry/firstFailureAt").asText());
        clock.now = firstFailure.plus(Duration.ofMinutes(5));
        assertThat(worker.runOnce("restarted-worker")).isEqualTo(1);
        assertThat(gateway.calls).hasValue(1);
        assertThat(task().errorCode()).isEqualTo("LLM_RETRY_EXHAUSTED");
        assertThat(task().completedAt()).isEqualTo(clock.instant());
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.FAILED);
    }
    @Test void stopDuringBackoffCancelsWithoutRetrying() {
        worker.runOnce("retry-worker");
        Task waiting = task();
        runs.cancel(owner.userId(), project.id(), run.id());
        clock.now = waiting.nextActionAt();
        assertThat(worker.runOnce("retry-worker")).isZero();
        assertThat(task().status()).isEqualTo(Task.Status.CANCELED);
        assertThat(gateway.calls).hasValue(1);
    }
    @Test void oldStreamIsFencedAndNewAttemptDoesNotConcatenatePrefixes() {
        worker.runOnce("retry-worker");
        Task waiting = task();
        assertThat(waiting.output().at("/assistantStream/status").asText()).isEqualTo("INTERRUPTED");
        assertThat(waiting.output().at("/assistantStream/text").asText()).isEqualTo("Interrupted prefix");
        Consumer<String> oldSink = gateway.lastSink;
        assertThatThrownBy(() -> oldSink.accept("late old prefix")).isInstanceOf(RuntimeException.class);
        clock.now = waiting.nextActionAt();
        worker.runOnce("new-worker");
        assertThat(task().output().at("/assistantStream/text").asText()).isEqualTo("Ready.");
        assertThat(task().output().at("/assistantStream/status").asText()).isEqualTo("COMPLETED");
        assertThat(task().output().at("/modelRetry/retryCount").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> oldSink.accept("late after completion")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("select count(*) from project_event where project_id=:id "
                        + "and type='agent.turn.stream.interrupted'")
                .param("id", project.id()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void responseCheckpointWinningTimeoutRaceIsReplayedWithoutAnotherRequest() {
        gateway.failures = 0;
        Task lease = tasks.claimAgentTurns("checkpoint-worker", 1).getFirst();
        AgentRun started = commits.start(lease, "checkpoint-worker");
        var response = rounds.callLeased(owner.userId(), project.id(), run.id(), 0,
                context.assemble(owner.userId(), project.id(), run.id()), registry.modelDefinitions(started.policySnapshot()),
                Map.of("projectId", project.id().toString(), "runId", run.id().toString()), lease, "checkpoint-worker");
        // The Future timed out just after the response transaction committed.
        assertThat(commits.modelFailed(lease, "checkpoint-worker", "LLM_CALL_TIMEOUT")).isEqualTo(response);
        assertThat(task().status()).isEqualTo(Task.Status.RUNNING);
        assertThat(task().output().has("modelRetry")).isFalse();
        commits.complete(lease, "checkpoint-worker");
        assertThat(task().status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(gateway.calls).hasValue(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }
    @Test void inFlightRetryIsCutOffAtTheRemainingFiveMinuteBudget() {
        worker.runOnce("retry-worker");
        clock.now = Instant.parse(task().output().at("/modelRetry/deadlineAt").asText()).minusSeconds(1);
        clock.tickStartedAt = System.nanoTime();
        clock.ticking = true;
        gateway.blockRequest = true;
        long started = System.nanoTime();
        worker.runOnce("retry-worker");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(gateway.calls).hasValue(2);
        assertThat(task().status()).isEqualTo(Task.Status.FAILED);
        assertThat(task().errorCode()).isEqualTo("LLM_RETRY_EXHAUSTED");
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.FAILED);
    }
    @Test void unavailableModelIsNotAutomaticallyRetried() {
        gateway.toolCalling = false;
        worker.runOnce("retry-worker");
        assertThat(task().status()).isEqualTo(Task.Status.FAILED);
        assertThat(task().errorCode()).isEqualTo("LLM_CONFIG_UNAVAILABLE");
        assertThat(task().output() == null || task().output().isNull()).isTrue();
        assertThat(gateway.calls).hasValue(0);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(jdbc.sql("select active_run_id is null from project where id=:id")
                .param("id", project.id()).query(Boolean.class).single()).isTrue();
    }
    @Test void continuationRetryDoesNotRepeatAnAlreadyCommittedTool() {
        gateway.createFirstTool = true;
        gateway.failures = 2;
        worker.runOnce("retry-worker");
        worker.runOnce("retry-worker");
        Task waiting = tasks.listByRun(owner.userId(), project.id(), run.id()).stream()
                .filter(task -> task.input().path("stepIndex").asInt() == 1).findFirst().orElseThrow();
        assertThat(waiting.status()).isEqualTo(Task.Status.READY);
        clock.now = waiting.nextActionAt();
        worker.runOnce("retry-worker");
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id=:id")
                .param("id", run.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from llm_turn where run_id=:id")
                .param("id", run.id()).query(Long.class).single()).isEqualTo(2);
        assertThat(gateway.calls).hasValue(3);
    }
    @TestConfiguration static class FakeConfig {
        @Bean @Primary FakeGateway fakeGateway() { return new FakeGateway(); }
        @Bean @Primary RetryClock retryClock() { return new RetryClock(); }
    }
    static class RetryClock extends Clock {
        volatile Instant now = Instant.now();
        volatile boolean ticking;
        volatile long tickStartedAt;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return ticking ? now.plusNanos(System.nanoTime() - tickStartedAt) : now; }
    }
    static class FakeGateway implements ChatGateway {
        final AtomicInteger calls = new AtomicInteger();
        volatile int failures;
        volatile boolean toolCalling = true;
        volatile boolean createFirstTool;
        volatile boolean blockRequest;
        volatile Consumer<String> lastSink;
        @Override public Exchange callStreaming(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> context, ConfigIdentity expected, Consumer<String> publicDelta,
                boolean captureContent, Consumer<LlmStreamLog> log) {
            lastSink = publicDelta;
            if (calls.get() < failures && !(createFirstTool && calls.get() == 0)) publicDelta.accept("Interrupted prefix");
            return ChatGateway.super.callStreaming(messages, tools, context, expected, publicDelta, captureContent, log);
        }
        @Override public String configSource() { return "test-fake"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(toolCalling, false, false); }
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context) {
            int attempt = calls.incrementAndGet();
            if (blockRequest) {
                try { Thread.sleep(Duration.ofSeconds(10)); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                throw new UncheckedIOException(new SocketTimeoutException("synthetic blocked request"));
            }
            if (createFirstTool && attempt == 1) {
                var assistant = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("synthetic-create-1", "function", "create_text",
                                "{\"title\":\"Opening\",\"text\":\"First shot\",\"format\":\"PLAIN_TEXT\"}"))).build();
                return new Exchange(1, new ChatResponse(List.of(new Generation(assistant))));
            }
            if (attempt <= failures) {
                throw new UncheckedIOException(new SocketTimeoutException("synthetic timeout"));
            }
            return new Exchange(1, new ChatResponse(List.of(new Generation(new AssistantMessage("Ready.")))));
        }
    }
}
