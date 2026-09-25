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
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
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
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL evidence for SKIP LOCKED claims, lease fencing, and transaction-free work. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=task-integration-bootstrap-secret")
class TaskLeasePostgresIT {

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
        Task task = create(owner.userId(), project.id(), run.id(), "exclusive", List.of());

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

        Task networkTask = create(owner.userId(), project.id(), run.id(), "network", List.of());
        AtomicBoolean transactionSeen = new AtomicBoolean(true);
        TaskWorker worker = new TaskWorker(taskService);
        assertThat(worker.runOnce("worker-network", 1, claimed -> {
                    transactionSeen.set(TransactionSynchronizationManager.isActualTransactionActive());
                    assertThat(jdbcClient.sql("select 1").query(Integer.class).single()).isEqualTo(1);
                    return new TaskWorker.Succeeded(
                            objectMapper.readTree("{\"network\":\"outside-transaction\"}"));
                }))
                .isEqualTo(1);
        assertThat(transactionSeen).isFalse();
        assertThat(taskService.get(owner.userId(), project.id(), networkTask.id()).status())
                .isEqualTo(Task.Status.SUCCEEDED);

        Task predecessor = create(owner.userId(), project.id(), run.id(), "predecessor", List.of());
        Task dependent = create(
                owner.userId(), project.id(), run.id(), "dependent", List.of(predecessor.id()));
        assertThat(dependent.status()).isEqualTo(Task.Status.PENDING);
        Task predecessorLease = taskService.claimDue("worker-dependency", 1).getFirst();
        taskService.succeed(
                predecessorLease,
                "worker-dependency",
                objectMapper.readTree("{\"output\":true}"));
        assertThat(taskService.get(owner.userId(), project.id(), dependent.id()).status())
                .isEqualTo(Task.Status.READY);
        Task dependentLease = taskService.claimDue("worker-dependent", 1).getFirst();
        taskService.succeed(
                dependentLease,
                "worker-dependent",
                objectMapper.readTree("{\"dependent\":true}"));

        Task providerTask = create(owner.userId(), project.id(), run.id(), "provider", List.of());
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
        assertProblem(
                "RESOURCE_NOT_FOUND",
                () -> taskService.get(UUID.randomUUID(), project.id(), task.id()));
        assertThat(jdbcClient.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                        .query(String.class)
                        .single())
                .isEqualTo("36");
    }

    private Task create(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            String stepKey,
            List<UUID> dependencies) {
        return taskService.create(
                ownerId,
                projectId,
                runId,
                null,
                stepKey,
                Task.Kind.ASSET_INGEST,
                objectMapper.readTree("{\"step\":\"" + stepKey + "\"}"),
                null,
                1,
                dependencies);
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
