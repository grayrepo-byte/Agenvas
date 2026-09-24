package dev.agenvas.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.event.domain.ProjectEvent;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.application.ProjectSnapshotService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.lang.reflect.Method;
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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL evidence for transactional events, sequence serialization, and snapshot waterlines. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=event-integration-bootstrap-secret")
class ProjectEventPostgresIT {

    private static final int CONCURRENT_EVENTS = 12;

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
    private ProjectEventService eventService;

    @Autowired
    private ProjectSnapshotService snapshotService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void eventFailureRollsBackBusinessConcurrentSequencesHaveNoGapAndSnapshotIsConsistent()
            throws Exception {
        AdminPrincipal owner = identityService.setup(
                "event-integration-bootstrap-secret", "event-admin", "event-password-123");
        Project project = projectService.create(
                owner.userId(), "Event project", Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agentService.create(
                owner.userId(), project.id(), "Creator", "Create", List.of());

        installRejectingTrigger();
        assertThatThrownBy(() -> runService.create(
                        owner.userId(), project.id(), agent.id(), "Must roll back", "rollback-key"))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("agent_run", project.id())).isZero();
        assertThat(count("project_event", project.id())).isEqualTo(1);
        assertThat(jdbcClient.sql("select event_seq from project where id = :projectId")
                        .param("projectId", project.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbcClient.sql("select count(*) from idempotency_record")
                        .query(Long.class)
                        .single())
                .isZero();
        removeRejectingTrigger();

        AgentRun run = runService.create(
                        owner.userId(), project.id(), agent.id(), "Create three shots", "rollback-key")
                .run();
        assertThat(concurrentAppend(owner.userId(), project.id())).hasSize(CONCURRENT_EVENTS);
        List<ProjectEvent> events = eventService.listAfter(owner.userId(), project.id(), 1, 100);
        assertThat(events).extracting(ProjectEvent::seq)
                .containsExactlyElementsOf(sequence(2, CONCURRENT_EVENTS + 3));
        assertThat(events.getFirst().type()).isEqualTo("agent.run.changed");
        assertThat(events.getFirst().payload().get("status").stringValue()).isEqualTo("QUEUED");
        assertThat(events.get(1).type()).isEqualTo("task.status.changed");
        assertThat(jdbcClient.sql("select event_seq from project where id = :projectId")
                        .param("projectId", project.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(CONCURRENT_EVENTS + 3L);

        Task task = taskService.create(
                owner.userId(),
                project.id(),
                run.id(),
                null,
                "snapshot-task",
                Task.Kind.AGENT_TURN,
                objectMapper.readTree("{\"step\":\"snapshot\"}"),
                null,
                1,
                List.of());
        ProjectSnapshotService.ProjectSnapshot snapshot =
                snapshotService.snapshot(owner.userId(), project.id());
        assertThat(snapshot.snapshotSeq()).isEqualTo(CONCURRENT_EVENTS + 4L);
        assertThat(snapshot.activeRun().id()).isEqualTo(run.id());
        assertThat(snapshot.activeTasks()).extracting(Task::id).contains(task.id());
        assertThat(snapshot.agents()).extracting(AgentInstance::id).containsExactly(agent.id());
        assertThat(snapshot.canvas()).isEmpty();
        assertRepeatableReadContract();
        assertConcurrentSnapshotPairs(owner.userId());
        eventService.requireReplayableCursor(owner.userId(), project.id(), 0);
        assertThatThrownBy(() -> eventService.requireReplayableCursor(
                        owner.userId(), project.id(), CONCURRENT_EVENTS + 100L))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("VALIDATION_ERROR"));
        jdbcClient.sql("""
                        update project_event
                        set occurred_at = now() - interval '31 days'
                        where project_id = :projectId and seq = 1
                        """)
                .param("projectId", project.id())
                .update();
        assertThat(eventService.pruneExpired(100)).isEqualTo(1);
        assertThatThrownBy(() -> eventService.requireReplayableCursor(
                        owner.userId(), project.id(), 0))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("EVENT_CURSOR_EXPIRED"));
        assertThat(jdbcClient.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                        .query(String.class)
                        .single())
                .isEqualTo("35");
    }

    private List<ProjectEvent> concurrentAppend(UUID ownerId, UUID projectId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ProjectEvent>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            for (int index = 0; index < CONCURRENT_EVENTS; index++) {
                int eventIndex = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    UUID aggregateId = UUID.randomUUID();
                    return eventService.append(
                            ownerId,
                            projectId,
                            new ProjectEventService.EventDraft(
                                    "test.changed",
                                    1,
                                    aggregateId,
                                    eventIndex,
                                    objectMapper.createObjectNode().put("index", eventIndex)));
                }));
            }
            start.countDown();
            List<ProjectEvent> results = new ArrayList<>();
            for (Future<ProjectEvent> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private void assertConcurrentSnapshotPairs(UUID ownerId) throws Exception {
        Project project = projectService.create(
                ownerId, "Snapshot race", Project.AspectRatio.SQUARE_1_1);
        AgentInstance agent =
                agentService.create(ownerId, project.id(), "Race creator", "Create", List.of());
        AgentRun initial = runService.create(
                        ownerId, project.id(), agent.id(), "Race", "snapshot-race")
                .run();
        AtomicBoolean writerDone = new AtomicBoolean(false);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> writer = executor.submit(() -> {
                try {
                    AgentRun current = runService.transition(
                            ownerId, project.id(), initial.id(), 0, AgentRun.Status.RUNNING);
                    for (int index = 0; index < 40; index++) {
                        AgentRun.Status next = current.status() == AgentRun.Status.RUNNING
                                ? AgentRun.Status.WAITING_TASKS
                                : AgentRun.Status.RUNNING;
                        current = runService.transition(
                                ownerId, project.id(), current.id(), current.version(), next);
                    }
                } finally {
                    writerDone.set(true);
                }
            });
            Future<?> reader = executor.submit(() -> {
                int reads = 0;
                while (!writerDone.get() || reads < 30) {
                    ProjectSnapshotService.ProjectSnapshot snapshot =
                            snapshotService.snapshot(ownerId, project.id());
                    assertThat(snapshot.snapshotSeq())
                            .isEqualTo(snapshot.activeRun().version() + 3);
                    reads++;
                }
            });
            writer.get();
            reader.get();
        }
    }

    private void assertRepeatableReadContract() throws NoSuchMethodException {
        Method method = ProjectSnapshotService.class.getMethod("snapshot", UUID.class, UUID.class);
        Transactional transactional =
                AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(transactional.readOnly()).isTrue();
    }

    private List<Long> sequence(long first, long lastInclusive) {
        List<Long> values = new ArrayList<>();
        for (long value = first; value <= lastInclusive; value++) {
            values.add(value);
        }
        return values;
    }

    private long count(String table, UUID projectId) {
        String sql = switch (table) {
            case "agent_run" -> "select count(*) from agent_run where project_id = :projectId";
            case "project_event" ->
                "select count(*) from project_event where project_id = :projectId";
            default -> throw new IllegalArgumentException("Unsupported test table: " + table);
        };
        return jdbcClient.sql(sql)
                .param("projectId", projectId)
                .query(Long.class)
                .single();
    }

    private void installRejectingTrigger() {
        jdbcClient.sql("""
                        create function reject_agent_run_event() returns trigger
                        language plpgsql as $$
                        begin
                            if new.type = 'agent.run.changed' then
                                raise exception 'injected event write failure';
                            end if;
                            return new;
                        end
                        $$
                        """)
                .update();
        jdbcClient.sql("""
                        create trigger reject_agent_run_event_trigger
                        before insert on project_event
                        for each row execute function reject_agent_run_event()
                        """)
                .update();
    }

    private void removeRejectingTrigger() {
        jdbcClient.sql("drop trigger reject_agent_run_event_trigger on project_event").update();
        jdbcClient.sql("drop function reject_agent_run_event()").update();
    }
}
