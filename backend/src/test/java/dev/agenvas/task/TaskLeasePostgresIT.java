package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.MigrationVersions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL evidence for SKIP LOCKED claims, lease fencing, and transaction-free work. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=task-integration-bootstrap-secret")
class TaskLeasePostgresIT {

    private static final Duration TEST_LEASE_DURATION = Duration.ofMinutes(1);

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private dev.agenvas.audit.application.CallLogService callLogs;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AgentInstanceService agentService;

    @Autowired
    private AgentRunService runService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void claimsAreExclusiveExpiredEpochIsFencedAndWorkRunsWithoutTransaction() throws Exception {
        AdminPrincipal owner = identityService.setup(
                "task-integration-bootstrap-secret", "task-admin", "task-password-123");
        Project project = projectService.create(
                owner.userId(), "Task project", Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agentService.create(
                owner.userId(), project.id(), "Creator", "Create", List.of());
        AgentRun run = runService.create(
                        owner.userId(), project.id(), agent.id(), "Create three shots", "task-run")
                .run();
        runService.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);
        Task task = create(owner.userId(), project.id(), run.id(), "exclusive");

        List<List<Task>> claims = concurrentClaims();
        assertThat(claims).flatExtracting(value -> value).hasSize(1);
        Task firstLease = claims.stream().flatMap(List::stream).findFirst().orElseThrow();
        assertThat(firstLease.leaseEpoch()).isEqualTo(1);

        jdbcClient.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", task.id())
                .update();
        Task secondLease = taskService.claimDue("worker-2", 1).getFirst();
        assertThat(secondLease.id()).isEqualTo(task.id());
        assertThat(secondLease.leaseEpoch()).isEqualTo(2);
        assertProblem(
                "TASK_LEASE_LOST",
                () -> taskService.succeed(
                        firstLease,
                        firstLease.leaseOwner(),
                        objectMapper.readTree("{\"result\":\"stale\"}")));
        taskService.succeed(
                secondLease, "worker-2", objectMapper.readTree("{\"result\":\"current\"}"));
        Task saved = taskService.get(owner.userId(), project.id(), task.id());
        assertThat(saved.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(saved.output().get("result").stringValue()).isEqualTo("current");

        Task networkTask = create(owner.userId(), project.id(), run.id(), "network");
        Task networkLease = taskService.claimDue("worker-network", 1).getFirst();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbcClient.sql("select 1").query(Integer.class).single()).isEqualTo(1);
        taskService.succeed(networkLease, "worker-network",
                objectMapper.readTree("{\"network\":\"outside-transaction\"}"));
        assertThat(taskService.get(owner.userId(), project.id(), networkTask.id()).status())
                .isEqualTo(Task.Status.SUCCEEDED);

        Task providerTask = create(owner.userId(), project.id(), run.id(), "provider");
        Task providerLease = taskService.claimDue("worker-provider", 1).getFirst();
        taskService.beginSubmission(providerLease, "worker-provider");
        taskService.waitForProvider(
                providerLease,
                "worker-provider",
                "provider-request-1",
                Instant.now().plusSeconds(10));
        Task waiting = taskService.get(owner.userId(), project.id(), providerTask.id());
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(waiting.leaseOwner()).isNull();
        assertThat(waiting.leaseUntil()).isNull();
        assertLeaseWriteGuards(owner.userId(), project.id(), run.id());
        assertProblem(
                "RESOURCE_NOT_FOUND",
                () -> taskService.get(UUID.randomUUID(), project.id(), task.id()));
        assertThat(jdbcClient.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                        .query(String.class)
                        .single())
                .isEqualTo(MigrationVersions.latest());
    }

    private void assertLeaseWriteGuards(UUID ownerId, UUID projectId, UUID runId) {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        Instant leaseUntil = now.plus(TEST_LEASE_DURATION);
        Instant renewedLeaseUntil = leaseUntil.plus(TEST_LEASE_DURATION);
        String workerId = "worker-write-guards";
        Task task = create(ownerId, projectId, runId, "write-guards");
        Task lease = taskRepository.claimDue(workerId, 1, now, leaseUntil).getFirst();
        assertThat(lease.id()).isEqualTo(task.id());
        JsonNode output = objectMapper.readTree("{\"result\":\"guarded\"}");

        assertThat(taskRepository.heartbeat(
                task.id(), "worker-other", lease.leaseEpoch(), now, renewedLeaseUntil)).isFalse();
        assertThat(taskRepository.finish(task.id(), "worker-other", lease.leaseEpoch(),
                Task.Status.SUCCEEDED, output, null, now)).isFalse();
        assertThat(taskRepository.findById(task.id())).contains(lease);

        assertThat(jdbcClient.sql("update task set status = :status where id = :id")
                .param("status", Task.Status.SUBMITTING.name())
                .param("id", task.id()).update()).isEqualTo(1);
        Task submitting = taskRepository.findById(task.id()).orElseThrow();
        assertThat(taskRepository.finish(task.id(), workerId, lease.leaseEpoch(),
                Task.Status.SUCCEEDED, output, null, now)).isFalse();
        assertThat(taskRepository.beginSubmission(task.id(), workerId, lease.leaseEpoch(),
                UUID.randomUUID(), UUID.randomUUID(), now)).isFalse();
        assertThat(taskRepository.findById(task.id())).contains(submitting);
        assertThat(jdbcClient.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", task.id()).query(Long.class).single()).isZero();

        // SUBMITTING permits renewal even though ordinary completion requires RUNNING.
        assertThat(taskRepository.heartbeat(
                task.id(), workerId, lease.leaseEpoch(), now, renewedLeaseUntil)).isTrue();
        Task renewed = taskRepository.findById(task.id()).orElseThrow();
        assertThat(renewed.status()).isEqualTo(Task.Status.SUBMITTING);
        assertThat(renewed.leaseUntil()).isEqualTo(renewedLeaseUntil);
        assertThat(renewed.version()).isEqualTo(lease.version() + 1);

        assertThat(jdbcClient.sql("update task set status = :status, lease_until = :leaseUntil where id = :id")
                .param("status", Task.Status.RUNNING.name())
                .param("leaseUntil", now.atOffset(ZoneOffset.UTC))
                .param("id", task.id()).update()).isEqualTo(1);
        Task expiredAtBoundary = taskRepository.findById(task.id()).orElseThrow();
        assertThat(expiredAtBoundary.leaseUntil()).isEqualTo(now);
        assertThat(taskRepository.heartbeat(
                task.id(), workerId, lease.leaseEpoch(), now, renewedLeaseUntil)).isFalse();
        assertThat(taskRepository.finish(task.id(), workerId, lease.leaseEpoch(),
                Task.Status.SUCCEEDED, output, null, now)).isFalse();
        assertThat(taskRepository.findById(task.id())).contains(expiredAtBoundary);

        assertThat(jdbcClient.sql("update task set lease_until = :leaseUntil where id = :id")
                .param("leaseUntil", renewedLeaseUntil.atOffset(ZoneOffset.UTC))
                .param("id", task.id()).update()).isEqualTo(1);
        assertThat(taskRepository.finish(task.id(), workerId, lease.leaseEpoch(),
                Task.Status.SUCCEEDED, output, null, now)).isTrue();
        Task succeeded = taskRepository.findById(task.id()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(succeeded.output()).isEqualTo(output);
        assertThat(succeeded.leaseOwner()).isNull();
        assertThat(succeeded.leaseUntil()).isNull();
        assertThat(succeeded.completedAt()).isEqualTo(now);
        assertThat(succeeded.version()).isEqualTo(renewed.version() + 1);
    }

    private Task create(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            String stepKey) {
        return taskService.create(
                ownerId,
                projectId,
                runId,
                stepKey,
                Task.Kind.IMAGE_GENERATION,
                objectMapper.readTree("{\"step\":\"" + stepKey + "\"}"),
                1);
    }

    private List<List<Task>> concurrentClaims() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Task>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            futures.add(executor.submit(() -> {
                start.await();
                return taskService.claimDue("worker-1", 1);
            }));
            futures.add(executor.submit(() -> {
                start.await();
                return taskService.claimDue("worker-2", 1);
            }));
            start.countDown();
            List<List<Task>> results = new ArrayList<>();
            for (Future<List<Task>> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private void assertProblem(String expectedCode, Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected ApiProblemException with code " + expectedCode);
        } catch (ApiProblemException problem) {
            assertThat(problem.code()).isEqualTo(expectedCode);
        }
    }
}
