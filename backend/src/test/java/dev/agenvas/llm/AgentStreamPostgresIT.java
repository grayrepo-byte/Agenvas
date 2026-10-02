package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnCommitService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.application.ProjectSnapshotService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.task.application.TaskRecoveryScheduler;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL proof of durable public text, event atomicity and lease fencing; no model calls. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=synthetic-stream-bootstrap",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.providers.mock.scheduler-enabled=false",
        "agenvas.providers.mock.video-scheduler-enabled=false"})
class AgentStreamPostgresIT {
    private static final String WORKER = "stream-test-worker";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ProjectSnapshotService snapshots;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private AgentTurnCommitService commits;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private TaskRecoveryScheduler recovery;
    private static UUID owner;

    @BeforeEach void administrator() {
        if (owner == null) owner = identities.setup("synthetic-stream-bootstrap", "stream-test-admin", "synthetic-password-123").userId();
    }

    @Test void publicTextAndProjectEventCommitTogetherAndSnapshotRestoresTheWholePrefix() {
        Task lease = startedTurn();
        tasks.startAgentStream(lease, WORKER);
        long chunk = tasks.appendAgentStream(lease, WORKER, 0, "公开回复");
        assertThat(chunk).isEqualTo(1);
        var snapshot = snapshots.snapshot(owner, lease.projectId());
        assertThat(snapshot.activeTasks()).singleElement().satisfies(task -> {
            assertThat(task.output().path("assistantStream").path("text").asText()).isEqualTo("公开回复");
            assertThat(task.output().path("assistantStream").path("status").asText()).isEqualTo("STREAMING");
        });
        assertThat(streamEventCount(lease)).isEqualTo(2);
        long sequence = snapshot.snapshotSeq();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tasks.appendAgentStream(lease, WORKER, chunk, "应回滚");
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.get(owner, lease.projectId(), lease.id()).output().path("assistantStream").path("text").asText())
                .isEqualTo("公开回复");
        assertThat(streamEventCount(lease)).isEqualTo(2);
        assertThat(snapshots.snapshot(owner, lease.projectId()).snapshotSeq()).isEqualTo(sequence);
    }

    @Test void staleChunkAndOldEpochCannotAppendAndNewEpochStartsWithAnEmptyPrefix() {
        Task lease = startedTurn();
        tasks.startAgentStream(lease, WORKER);
        tasks.appendAgentStream(lease, WORKER, 0, "旧流");
        assertThatThrownBy(() -> tasks.appendAgentStream(lease, WORKER, 0, "重复分片"))
                .isInstanceOf(RuntimeException.class);
        jdbc.sql("update task set lease_epoch = lease_epoch + 1, lease_owner = :worker where id = :id")
                .param("worker", "new-stream-worker").param("id", lease.id()).update();
        Task replacement = tasks.get(owner, lease.projectId(), lease.id());
        assertThatThrownBy(() -> tasks.appendAgentStream(lease, WORKER, 1, "旧线程晚到"))
                .isInstanceOf(RuntimeException.class);
        tasks.startAgentStream(replacement, "new-stream-worker");
        tasks.appendAgentStream(replacement, "new-stream-worker", 0, "新流");
        var stream = tasks.get(owner, lease.projectId(), lease.id()).output().path("assistantStream");
        assertThat(stream.path("text").asText()).isEqualTo("新流");
        assertThat(stream.path("streamEpoch").asLong()).isEqualTo(replacement.leaseEpoch());
    }

    @Test void expiredOrCanceledLeaseCannotPublishAndFailureRetainsTheInterruptedPrefix() {
        Task expired = startedTurn();
        tasks.startAgentStream(expired, WORKER);
        tasks.appendAgentStream(expired, WORKER, 0, "已收到的公开内容");
        jdbc.sql("update task set lease_until = :past where id = :id")
                .param("past", java.sql.Timestamp.from(Instant.now().minusSeconds(60))).param("id", expired.id()).update();
        assertThatThrownBy(() -> tasks.appendAgentStream(expired, WORKER, 1, "过期晚到"))
                .isInstanceOf(RuntimeException.class);

        Task failed = startedTurn();
        tasks.startAgentStream(failed, WORKER);
        tasks.appendAgentStream(failed, WORKER, 0, "失败前的公开内容");
        tasks.fail(failed, WORKER, "MODEL_CALL_FAILED");
        var failure = tasks.get(owner, failed.projectId(), failed.id());
        assertThat(failure.status()).isEqualTo(Task.Status.FAILED);
        assertThat(failure.output().path("assistantStream").path("text").asText()).isEqualTo("失败前的公开内容");
        assertThat(failure.output().path("assistantStream").path("status").asText()).isEqualTo("INTERRUPTED");

        Task canceled = startedTurn();
        tasks.startAgentStream(canceled, WORKER);
        tasks.appendAgentStream(canceled, WORKER, 0, "取消前的公开内容");
        runs.cancel(owner, canceled.projectId(), canceled.runId());
        assertThatThrownBy(() -> tasks.appendAgentStream(canceled, WORKER, 1, "取消后晚到"))
                .isInstanceOf(RuntimeException.class);
        var cancellation = tasks.get(owner, canceled.projectId(), canceled.id());
        assertThat(cancellation.output().path("assistantStream").path("text").asText()).isEqualTo("取消前的公开内容");
        assertThat(cancellation.output().path("assistantStream").path("status").asText()).isEqualTo("INTERRUPTED");
    }

    private Task startedTurn() {
        Project project = projects.create(owner, "Synthetic stream project", Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner, project.id(), "Creator", "Write a public answer", List.of());
        var run = runs.create(owner, project.id(), agent.id(), "Write an answer", UUID.randomUUID().toString()).run();
        Task lease = tasks.claimAgentTurns(WORKER, 100).stream().filter(task -> run.id().equals(task.runId()))
                .findFirst().orElseThrow();
        commits.start(lease, WORKER);
        return lease;
    }

    private long streamEventCount(Task lease) {
        return jdbc.sql("select count(*) from project_event where project_id = :project and type like 'agent.turn.stream.%'")
                .param("project", lease.projectId()).query(Long.class).single();
    }
}
