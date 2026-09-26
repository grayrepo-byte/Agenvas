package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.application.ProjectSnapshotService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.ProviderAttempt;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real-PostgreSQL evidence for ambiguous submission recovery and cancellation fencing. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=recovery-integration-secret")
class TaskRecoveryPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private dev.agenvas.audit.application.CallLogService callLogs;

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ProjectSnapshotService snapshots;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext webContext;

    @Test
    void crashedSubmissionBecomesUnknownAndCanceledLateResultNeverPromotes() throws Exception {
        AdminPrincipal owner = identities.setup(
                "recovery-integration-secret", "recovery-admin", "recovery-password-123");
        Project project = projects.create(owner.userId(), "Recovery project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Create shots", "recovery-run").run();
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);

        Task submission = create(owner.userId(), project.id(), run.id(), "submission", List.of());
        Task lease = tasks.claimDue("worker-submission", 1).getFirst();
        UUID requestKey = tasks.beginSubmission(lease, "worker-submission");
        assertThat(requestKey).isNotNull();
        assertThat(jdbc.sql("select status from provider_attempt where task_id = :id")
                .param("id", submission.id()).query(String.class).single()).isEqualTo("SUBMITTING");
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", submission.id()).update();
        tasks.recoverExpiredSubmissions(16);
        assertThat(tasks.get(owner.userId(), project.id(), submission.id()).status())
                .isEqualTo(Task.Status.UNKNOWN);
        assertThat(jdbc.sql("select status from provider_attempt where task_id = :id")
                .param("id", submission.id()).query(String.class).single()).isEqualTo("UNKNOWN");
        assertThat(tasks.listProviderAttempts(owner.userId(), project.id(), submission.id()))
                .singleElement().satisfies(attempt -> {
                    assertThat(attempt.status()).isEqualTo(ProviderAttempt.Status.UNKNOWN);
                    assertThat(attempt.requestKey()).isEqualTo(requestKey);
                    assertThat(attempt.candidateRequestId()).isNull();
                    assertThat(attempt.providerRequestId()).isNull();
                });
        var mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        var authenticated = authentication(new UsernamePasswordAuthenticationToken(
                owner, null, List.of()));
        String diagnostics = mvc.perform(get("/api/v1/settings/diagnostics")
                        .with(authenticated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentErrors[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.recentErrors[0].count").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(diagnostics).doesNotContain(submission.id().toString(),
                requestKey.toString(), project.id().toString());
        assertThatThrownBy(() -> tasks.rejectSubmission(lease, "worker-submission",
                "LATE_REJECTION"))
                .isInstanceOf(ApiProblemException.class);
        assertThat(jdbc.sql("select status from provider_attempt where task_id = :id")
                .param("id", submission.id()).query(String.class).single()).isEqualTo("UNKNOWN");
        assertThat(tasks.claimDue("second-worker", 16)).isEmpty();
        assertThat(jdbc.sql("select count(*) from project_event where project_id = :id "
                        + "and type = 'task.status.changed'")
                .param("id", project.id()).query(Integer.class).single()).isEqualTo(4);

        Task accepted = create(owner.userId(), project.id(), run.id(), "accepted", List.of());
        AtomicBoolean checkpointSeen = new AtomicBoolean(false);
        new TaskWorker(tasks, callLogs).runOnce("worker-accepted", 1, claimed -> {
            checkpointSeen.set(tasks.get(owner.userId(), project.id(), claimed.id()).status()
                    == Task.Status.SUBMITTING);
            return new TaskWorker.WaitingProvider("external-123", Instant.now().plusSeconds(60));
        });
        assertThat(checkpointSeen).isTrue();
        assertThat(tasks.get(owner.userId(), project.id(), accepted.id()).status())
                .isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(tasks.listProviderAttempts(owner.userId(), project.id(), accepted.id()))
                .singleElement().satisfies(attempt -> {
                    assertThat(attempt.status()).isEqualTo(ProviderAttempt.Status.ACCEPTED);
                    assertThat(attempt.providerRequestId()).isEqualTo("external-123");
                });
        assertThat(tasks.claimProviderPolls("poller", 1)).isEmpty();
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                .param("id", accepted.id()).update();
        Task firstPoll = tasks.claimProviderPolls("poller", 1).getFirst();
        assertThat(firstPoll.providerRequestId()).isEqualTo("external-123");
        tasks.deferProviderPoll(firstPoll, "poller", Instant.now().plusSeconds(60));
        assertThat(tasks.claimProviderPolls("poller", 1)).isEmpty();
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                .param("id", accepted.id()).update();
        Task secondPoll = tasks.claimProviderPolls("poller-2", 1).getFirst();
        assertThat(secondPoll.providerRequestId()).isEqualTo("external-123");
        assertThat(secondPoll.leaseEpoch()).isGreaterThan(firstPoll.leaseEpoch());
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", accepted.id()).query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> tasks.deferProviderPoll(firstPoll, "poller",
                Instant.now().plusSeconds(60))).isInstanceOf(ApiProblemException.class);
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", accepted.id()).update();
        assertThat(tasks.claimImagesDue("wrong-submitter", 1)).isEmpty();
        Task recoveredPoll = tasks.claimProviderPolls("recovered-poller", 1).getFirst();
        assertThat(recoveredPoll.providerRequestId()).isEqualTo("external-123");
        assertThat(recoveredPoll.leaseEpoch()).isGreaterThan(secondPoll.leaseEpoch());
        Task retryLease = recoveredPoll;
        for (int failure = 1; failure <= 5; failure++) {
            tasks.retryProviderPoll(retryLease, "recovered-poller",
                    "PROVIDER_POLL_TECHNICAL_FAILURE");
            Task deferred = tasks.get(owner.userId(), project.id(), accepted.id());
            assertThat(deferred.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
            assertThat(deferred.providerRequestId()).isEqualTo("external-123");
            long baseDelay = 5L << (failure - 1);
            long scheduledDelay = Duration.between(deferred.updatedAt(),
                    deferred.nextActionAt()).toSeconds();
            assertThat(scheduledDelay).isBetween(baseDelay - baseDelay / 5 - 1,
                    baseDelay + baseDelay / 5);
            assertThat(jdbc.sql("select failure_count from task_provider_poll_retry "
                            + "where task_id = :id")
                    .param("id", accepted.id()).query(Integer.class).single())
                    .isEqualTo(failure);
            jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                    .param("id", accepted.id()).update();
            retryLease = tasks.claimProviderPolls("recovered-poller", 1).getFirst();
        }
        tasks.retryProviderPoll(retryLease, "recovered-poller",
                "PROVIDER_POLL_TECHNICAL_FAILURE");
        Task exhausted = tasks.get(owner.userId(), project.id(), accepted.id());
        assertThat(exhausted.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(exhausted.errorCode()).isEqualTo("PROVIDER_POLL_RETRY_EXHAUSTED");
        assertThat(exhausted.providerRequestId()).isEqualTo("external-123");
        assertThat(jdbc.sql("select failure_count from task_provider_poll_retry "
                        + "where task_id = :id")
                .param("id", accepted.id()).query(Integer.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", accepted.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(tasks.claimProviderPolls("recovered-poller", 1)).isEmpty();

        Task abandoned = create(owner.userId(), project.id(), run.id(), "abandoned", List.of());
        Task abandonedLease = tasks.claimDue("worker-abandoned", 1).getFirst();

        Task predecessor = create(owner.userId(), project.id(), run.id(), "predecessor", List.of());
        Task dependent = create(owner.userId(), project.id(), run.id(), "dependent",
                List.of(predecessor.id()));
        Task predecessorLease = tasks.claimDue("worker-late", 1).getFirst();
        runs.cancel(owner.userId(), project.id(), run.id());
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", abandoned.id()).update();
        tasks.recoverExpiredCancellations(16);
        assertThat(tasks.get(owner.userId(), project.id(), abandoned.id()).status())
                .isEqualTo(Task.Status.CANCELED);
        tasks.succeed(abandonedLease, "worker-abandoned", mapper.readTree("{\"lateAfterScan\":true}"));
        assertThat(jdbc.sql("select count(*) from task_late_result where task_id = :id")
                .param("id", abandoned.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), dependent.id()).status())
                .isEqualTo(Task.Status.CANCELED);
        tasks.succeed(predecessorLease, "worker-late", mapper.readTree("{\"late\":true}"));
        Task canceled = tasks.get(owner.userId(), project.id(), predecessor.id());
        assertThat(canceled.status()).isEqualTo(Task.Status.CANCELED);
        assertThat(canceled.output()).isNull();
        assertThat(jdbc.sql("select count(*) from task_late_result where task_id = :id")
                .param("id", predecessor.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select payload_json ->> 'possibleExternalCost' from project_event
                        where project_id = :projectId and aggregate_id = :taskId
                        order by seq desc limit 1
                        """)
                .param("projectId", project.id())
                .param("taskId", predecessor.id())
                .query(String.class).single()).isEqualTo("true");
        assertThat(tasks.get(owner.userId(), project.id(), dependent.id()).status())
                .isEqualTo(Task.Status.CANCELED);
        assertThat(tasks.claimDue("third-worker", 16)).isEmpty();
        assertThat(snapshots.snapshot(owner.userId(), project.id()).unknownTasks())
                .extracting(Task::id).contains(submission.id());
    }

    private Task create(UUID ownerId, UUID projectId, UUID runId, String stepKey,
            List<UUID> dependencies) {
        return tasks.create(ownerId, projectId, runId, null, stepKey, Task.Kind.IMAGE_GENERATION,
                mapper.readTree("{\"step\":\"" + stepKey + "\"}"), null, 1, dependencies);
    }
}
