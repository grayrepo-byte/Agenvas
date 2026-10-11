package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.audit.domain.LlmStreamLog;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnCommitService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ToolBatchExecutionService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real lease renewal and SQL faults around synthetic gated model calls; no Provider traffic. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentHeartbeatPostgresIT.FakeConfig.class},
        properties = "agenvas.task.lease-duration=3s")
class AgentHeartbeatPostgresIT {
    private static final String WORKER = "heartbeat-worker";
    private static final String PREFIX = "Synthetic public prefix";
    private static final String FAULT = "test_agent_heartbeat_failure";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        // PER_CLASS injection can create the Spring context before the container extension starts it.
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
    @Autowired private AgentTurnCommitService commits;
    @Autowired private LlmTurnCheckpointService checkpoints;
    @Autowired private LlmProtocolCodec codec;
    @Autowired private ToolBatchExecutionService tools;
    @Autowired private FakeGateway gateway;
    @Autowired private HeartbeatClock clock;
    @Autowired private JdbcClient jdbc;

    private AdminPrincipal owner;
    private Project project;
    private AgentRun run;
    private ExecutorService caller;
    private Future<Integer> invocation;

    @BeforeAll
    void administrator() {
        owner = identities.setup("heartbeat-admin", "synthetic-heartbeat-password-123");
    }

    @BeforeEach
    void createRun() {
        clock.now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        invocation = null;
        gateway.calls.set(0);
        gateway.control = new CallControl();
        project = projects.create(owner.userId(), "Synthetic heartbeat project",
                Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create a text", List.of());
        run = runs.create(owner.userId(), project.id(), agent.id(), "Create a text", "heartbeat-run").run();
        caller = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("synthetic-worker-caller").factory());
    }

    @AfterEach
    void releaseGatesAndStopCaller() throws InterruptedException {
        try {
            gateway.control.release.countDown();
            if (invocation != null) invocation.cancel(true);
            caller.shutdownNow();
            assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            if (gateway.control.entered.getCount() == 0) {
                assertThat(gateway.control.returned.await(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            runs.cancel(owner.userId(), project.id(), run.id());
        }
    }

    @Test
    void cancellationStopsTheWaitingWorkerAndFencesEveryOldContinuation() throws Exception {
        invocation = caller.submit(() -> worker.runOnce(WORKER));
        assertThat(gateway.control.entered.await(5, TimeUnit.SECONDS)).isTrue();
        Task lease = task();
        assertThat(lease.output().at("/assistantStream/text").asText()).isEqualTo(PREFIX);

        runs.cancel(owner.userId(), project.id(), run.id());
        assertThat(invocation.get(3, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(gateway.control.interrupted.await(3, TimeUnit.SECONDS)).isTrue();

        long eventCount = eventCount();
        assertThatThrownBy(() -> checkpoints.saveResponseLeased(owner.userId(), project.id(), run.id(),
                0, 1, codec.response(FakeGateway.toolResponse()), lease, WORKER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tools.executeLeased(new TrustedToolContext(owner.userId(), project.id(), run.id()),
                0, FakeGateway.toolResponse().getResult().getOutput().getToolCalls(), lease, WORKER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> commits.complete(lease, WORKER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> commits.finishFailure(lease, WORKER, "SYNTHETIC_FAILURE"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> commits.modelFailed(lease, WORKER, "LLM_CALL_TIMEOUT"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.control.sink.accept("late old prefix"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_LEASE_LOST"));

        Task canceled = task();
        assertThat(canceled.status()).isEqualTo(Task.Status.RUNNING);
        assertThat(canceled.cancelRequested()).isTrue();
        assertThat(canceled.leaseEpoch()).isEqualTo(lease.leaseEpoch());
        assertThat(canceled.errorCode()).isNull();
        assertThat(canceled.output().has("modelRetry")).isFalse();
        assertThat(canceled.output().at("/assistantStream/text").asText()).isEqualTo(PREFIX);
        assertThat(canceled.output().at("/assistantStream/status").asText()).isEqualTo("INTERRUPTED");
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.CANCELED);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).nextStepIndex()).isZero();
        assertRequestedWithoutEffects();
        assertThat(eventCount()).isEqualTo(eventCount);
        assertThat(gateway.calls).hasValue(1);
        assertThat(worker.runOnce("after-cancellation-worker")).isZero();
    }

    @Test
    void unconfirmedSqlRenewalStopsLocallyButAValidLateCheckpointIsReplayedOnce() throws Exception {
        gateway.control.ignoreInterrupt = true;
        invocation = caller.submit(() -> worker.runOnce(WORKER));
        assertThat(gateway.control.entered.await(5, TimeUnit.SECONDS)).isTrue();
        Task lease = task();
        try {
            installHeartbeatFault(lease);
            assertThat(invocation.get(3, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(gateway.control.interrupted.await(3, TimeUnit.SECONDS)).isTrue();

            // The failed UPDATE rolled back. Local stopping must not invent a model failure,
            // clear this valid lease, or schedule a fresh request while the first call is in flight.
            Task stopped = task();
            assertThat(stopped.status()).isEqualTo(Task.Status.RUNNING);
            assertThat(stopped.leaseEpoch()).isEqualTo(lease.leaseEpoch());
            assertThat(stopped.leaseOwner()).isEqualTo(WORKER);
            assertThat(stopped.leaseUntil()).isEqualTo(lease.leaseUntil()).isAfter(clock.instant());
            assertThat(stopped.errorCode()).isNull();
            assertThat(stopped.output().has("modelRetry")).isFalse();
            assertThat(stopped.output().at("/assistantStream/text").asText()).isEqualTo(PREFIX);
            assertThat(stopped.output().at("/assistantStream/status").asText()).isEqualTo("STREAMING");
            assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.RUNNING);
            assertRequestedWithoutEffects();
            assertThat(worker.runOnce("too-early-recovery")).isZero();
            assertThat(gateway.calls).hasValue(1);
        } finally {
            try {
                removeHeartbeatFault();
            } finally {
                // This synthetic Provider deliberately ignores Future interruption. Cancellation
                // cannot guarantee no external usage or prevent a still-fenced response transaction.
                gateway.control.release.countDown();
            }
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(checkpointStatus()).isEqualTo("RESPONDED"));
        Task responded = task();
        assertThat(responded.status()).isEqualTo(Task.Status.RUNNING);
        assertThat(responded.leaseEpoch()).isEqualTo(lease.leaseEpoch());
        assertThat(responded.output().has("modelRetry")).isFalse();
        assertThat(responded.output().at("/assistantStream/status").asText()).isEqualTo("COMPLETED");
        assertThat(runCount("tool_execution")).isZero();
        assertThat(projectArtifactCount()).isZero();
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id())).hasSize(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).nextStepIndex()).isZero();
        assertThat(usageTypes()).containsExactlyInAnyOrder("RESERVATION", "SETTLEMENT");
        assertThat(gateway.calls).hasValue(1);

        // Actual claim/start/leased recovery consumes that durable response, not a new model call.
        clock.now = responded.leaseUntil().plus(1, ChronoUnit.MICROS);
        assertThat(worker.runOnce("checkpoint-recovery-worker")).isEqualTo(1);
        List<Task> turns = tasks.listByRun(owner.userId(), project.id(), run.id());
        assertThat(turns).hasSize(2);
        Task completed = turns.stream().filter(turn -> turn.id().equals(lease.id())).findFirst().orElseThrow();
        Task successor = turns.stream().filter(turn -> !turn.id().equals(lease.id())).findFirst().orElseThrow();
        assertThat(completed.leaseEpoch()).isGreaterThan(lease.leaseEpoch());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("decision").asText()).isEqualTo("CONTINUE");
        assertThat(completed.output().has("modelRetry")).isFalse();
        assertThat(successor.status()).isEqualTo(Task.Status.READY);
        assertThat(successor.input().path("stepIndex").asInt()).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).nextStepIndex()).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.RUNNING);
        assertThat(runCount("llm_turn")).isEqualTo(1);
        assertThat(runCount("tool_execution")).isEqualTo(1);
        assertThat(projectArtifactCount()).isEqualTo(1);
        assertThat(usageTypes()).containsExactlyInAnyOrder("RESERVATION", "SETTLEMENT");
        assertThat(jdbc.sql("select quantity_json->>'inputTokens' from usage_ledger "
                        + "where run_id=:run and entry_type='SETTLEMENT'")
                .param("run", run.id()).query(String.class).single()).isEqualTo("17");
        assertThat(jdbc.sql("select count(*) from project_event where project_id=:project "
                        + "and type='llm.turn.recorded'")
                .param("project", project.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(gateway.calls).hasValue(1);
    }

    private Task task() {
        return tasks.listByRun(owner.userId(), project.id(), run.id()).getFirst();
    }

    private String checkpointStatus() {
        return jdbc.sql("select status from llm_turn where run_id=:run")
                .param("run", run.id()).query(String.class).single();
    }

    private void assertRequestedWithoutEffects() {
        assertThat(checkpointStatus()).isEqualTo("REQUESTED");
        assertThat(jdbc.sql("select response_json is null from llm_turn where run_id=:run")
                .param("run", run.id()).query(Boolean.class).single()).isTrue();
        assertThat(usageTypes()).containsExactly("RESERVATION");
        assertThat(runCount("tool_execution")).isZero();
        assertThat(projectArtifactCount()).isZero();
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id())).hasSize(1);
    }

    private List<String> usageTypes() {
        return jdbc.sql("select entry_type from usage_ledger where run_id=:run")
                .param("run", run.id()).query(String.class).list();
    }

    private long runCount(String table) {
        return jdbc.sql("select count(*) from " + table + " where run_id=:run")
                .param("run", run.id()).query(Long.class).single();
    }

    private long projectArtifactCount() {
        return jdbc.sql("select count(*) from artifact where project_id=:project")
                .param("project", project.id()).query(Long.class).single();
    }

    private long eventCount() {
        return jdbc.sql("select count(*) from project_event where project_id=:project")
                .param("project", project.id()).query(Long.class).single();
    }

    private void installHeartbeatFault(Task lease) {
        jdbc.sql("create function " + FAULT + "() returns trigger language plpgsql as $$ "
                + "begin raise exception 'synthetic heartbeat UPDATE failure'; end; $$").update();
        // UPDATE OF fires even when a fixed Clock renews to the same lease_until. Claim
        // changes epoch; completion changes status, so neither is part of this fault scope.
        jdbc.sql("create trigger " + FAULT + " before update of lease_until on task "
                + "for each row when (old.id='" + lease.id() + "'::uuid "
                + "and old.status='RUNNING' and new.status=old.status "
                + "and new.lease_epoch=old.lease_epoch and new.lease_owner=old.lease_owner) "
                + "execute function " + FAULT + "()").update();
    }

    private void removeHeartbeatFault() {
        try {
            jdbc.sql("drop trigger if exists " + FAULT + " on task").update();
        } finally {
            jdbc.sql("drop function if exists " + FAULT + "()").update();
        }
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean @Primary FakeGateway fakeGateway() { return new FakeGateway(); }
        @Bean @Primary HeartbeatClock heartbeatClock() { return new HeartbeatClock(); }
    }

    static class HeartbeatClock extends Clock {
        volatile Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static class CallControl {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);
        volatile boolean ignoreInterrupt;
        volatile Consumer<String> sink;
    }

    static class FakeGateway implements ChatGateway {
        final AtomicInteger calls = new AtomicInteger();
        volatile CallControl control = new CallControl();

        @Override public String configSource() { return "test-heartbeat"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }

        @Override
        public Exchange callStreaming(List<Message> messages, List<ToolCallback> definitions,
                Map<String, Object> context, ConfigIdentity expected, Consumer<String> publicDelta,
                boolean captureContent, Consumer<LlmStreamLog> streamLog) {
            if (calls.get() == 0) {
                control.sink = publicDelta;
                publicDelta.accept(PREFIX);
            }
            return ChatGateway.super.callStreaming(messages, definitions, context, expected,
                    publicDelta, captureContent, streamLog);
        }

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> definitions, Map<String, Object> context) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (calls.incrementAndGet() != 1) {
                return new Exchange(1, new ChatResponse(List.of(new Generation(new AssistantMessage("Finished.")))));
            }
            CallControl pending = control;
            pending.entered.countDown();
            try {
                while (true) {
                    try {
                        pending.release.await();
                        return new Exchange(1, toolResponse());
                    } catch (InterruptedException interrupted) {
                        pending.interrupted.countDown();
                        if (!pending.ignoreInterrupt) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Synthetic cooperative Provider interrupted", interrupted);
                        }
                        // CountDownLatch.await cleared the interrupt flag. Deliberately continue
                        // this one request so a valid lease can still protect its late checkpoint.
                    }
                }
            } finally {
                pending.returned.countDown();
            }
        }

        static ChatResponse toolResponse() {
            var assistant = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("heartbeat-create-1", "function", "create_text",
                            "{\"title\":\"Synthetic text\",\"text\":\"One result\",\"format\":\"PLAIN_TEXT\"}")))
                    .build();
            return new ChatResponse(List.of(new Generation(assistant)), ChatResponseMetadata.builder()
                    .id("synthetic-heartbeat-response").model("synthetic-model").usage(new DefaultUsage(17, 9)).build());
        }
    }
}
