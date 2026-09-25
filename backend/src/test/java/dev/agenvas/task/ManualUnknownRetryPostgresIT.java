package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.ManualUnknownRetryService;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** PostgreSQL proof: explicit retry preserves UNKNOWN/cost and moves pending DAG edges once. */
@Testcontainers
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=manual-retry-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mode=mock"})
class ManualUnknownRetryPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ExecutionPlanService plans;
    @Autowired private TaskService tasks;
    @Autowired private TaskRepository taskRepository;
    @Autowired private ManualUnknownRetryService retries;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext webContext;

    @Test
    void explicitRiskCreatesOneNewReservationAndRewiresOnlyPendingConsumers() throws Exception {
        AdminPrincipal owner = identities.setup("manual-retry-integration-secret", "retry-admin",
                "retry-password-123");
        Project project = projects.create(owner.userId(), "Retry fixture",
                Project.AspectRatio.LANDSCAPE_16_9);
        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "Studio");
        scene.put("location", "Shanghai");
        scene.put("timeOfDay", "Day");
        scene.put("lighting", "Soft");
        scene.put("style", "Minimal");
        scene.putArray("referenceVersionIds");
        UUID sceneVersion = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SCENE,
                "Scene", scene).currentVersion().id();
        ObjectNode shot = mapper.createObjectNode();
        shot.put("order", 1);
        shot.put("durationSeconds", 3);
        shot.put("description", "Coffee pour");
        shot.put("camera", "Close");
        shot.put("action", "Pour coffee");
        shot.putArray("characterVersionIds");
        shot.put("sceneVersionId", sceneVersion.toString());
        var target = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SHOT,
                "Shot", shot);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of(new AgentInstanceService.BindingInput(target.artifact().id(),
                        target.currentVersion().id())));
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Make an image", "retry-run").run();
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("stage", "IMAGE");
        proposal.put("objective", "One image");
        ObjectNode step = proposal.putArray("steps").addObject();
        step.put("stepKey", "frame-1");
        step.put("outputSlotKey", "frame-1-output");
        step.put("shotArtifactId", target.artifact().id().toString());
        step.put("shotVersionId", target.currentVersion().id().toString());
        step.put("prompt", "Cinematic coffee pour");
        step.putArray("dependsOnStepKeys");
        var plan = plans.propose(new TrustedToolContext(owner.userId(), project.id(), run.id()),
                proposal);
        Task original = plans.approve(owner.userId(), project.id(), plan.id(),
                plan.planHash(), plans.get(owner.userId(), project.id(), plan.id()).steps().stream().map(dev.agenvas.plan.application.ExecutionPlan.Step::stepKey).toList()).tasks().getFirst();
        List<Task> dependents = tasks.listByRun(owner.userId(), project.id(), run.id()).stream()
                .filter(task -> task.kind() == Task.Kind.AGENT_TURN
                        && task.status() == Task.Status.PENDING).toList();
        assertThat(dependents).hasSize(1);
        jdbc.sql("update task set status = 'UNKNOWN', version = version + 1 where id = :id")
                .param("id", original.id()).update();
        AgentRun waiting = runs.get(owner.userId(), project.id(), run.id());
        runs.transition(owner.userId(), project.id(), run.id(), waiting.version(),
                AgentRun.Status.BLOCKED);
        Task unknown = tasks.get(owner.userId(), project.id(), original.id());
        assertThatThrownBy(() -> retries.create(owner.userId(), project.id(), unknown.id(),
                unknown.version(), "", "retry-1"))
                .isInstanceOf(ApiProblemException.class);
        Task replacement;
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Task> first = pool.submit(() -> {
                start.await();
                return retries.create(owner.userId(), project.id(), unknown.id(),
                        unknown.version(), ManualUnknownRetryService.RISK_ACKNOWLEDGEMENT,
                        "retry-1");
            });
            Future<Task> duplicate = pool.submit(() -> {
                start.await();
                return retries.create(owner.userId(), project.id(), unknown.id(),
                        unknown.version(), ManualUnknownRetryService.RISK_ACKNOWLEDGEMENT,
                        "retry-1");
            });
            start.countDown();
            replacement = first.get();
            assertThat(duplicate.get().id()).isEqualTo(replacement.id());
        }
        assertThat(replacement.id()).isNotEqualTo(unknown.id());
        assertThat(replacement.attemptNo()).isEqualTo(2);
        assertThat(tasks.get(owner.userId(), project.id(), unknown.id()).status())
                .isEqualTo(Task.Status.UNKNOWN);
        assertThat(tasks.replacementTaskId(owner.userId(), project.id(), unknown.id()))
                .isEqualTo(replacement.id());
        assertThat(jdbc.sql("select depends_on_task_id from task_dependency where task_id = :id")
                .param("id", dependents.getFirst().id()).query(UUID.class).list())
                .contains(replacement.id()).doesNotContain(unknown.id());
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.WAITING_TASKS);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id in (:old, :new)")
                .param("old", unknown.id()).param("new", replacement.id())
                .query(Long.class).single()).isGreaterThanOrEqualTo(2L);
        assertThat(retries.create(owner.userId(), project.id(), unknown.id(), unknown.version(),
                ManualUnknownRetryService.RISK_ACKNOWLEDGEMENT, "retry-1").id())
                .isEqualTo(replacement.id());
        assertThatThrownBy(() -> retries.create(owner.userId(), project.id(), unknown.id(),
                unknown.version(), ManualUnknownRetryService.RISK_ACKNOWLEDGEMENT, "retry-2"))
                .isInstanceOf(ApiProblemException.class);
        assertThat(jdbc.sql("select count(*) from task where plan_id = :id and step_key = 'frame-1'")
                .param("id", plan.id()).query(Long.class).single()).isEqualTo(2L);

        var mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        var authenticated = authentication(new UsernamePasswordAuthenticationToken(owner, null,
                List.of()));
        String path = "/api/v1/projects/" + project.id() + "/tasks/" + unknown.id()
                + "/new-attempt";
        String body = "{\"expectedTaskVersion\":" + unknown.version()
                + ",\"riskAcknowledgement\":\"ACCEPT_POSSIBLE_DUPLICATE_COST\"}";
        mvc.perform(post(path).with(authenticated).header("Idempotency-Key", "retry-1")
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(authenticated).with(csrf())
                        .header("Idempotency-Key", "retry-1")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(replacement.id().toString()));
        mvc.perform(post("/api/v1/projects/" + UUID.randomUUID() + "/tasks/"
                        + unknown.id() + "/new-attempt").with(authenticated).with(csrf())
                        .header("Idempotency-Key", "foreign")
                        .contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        assertThat(taskRepository.claimDueBoundMedia("replacement-submitter", 1,
                Instant.now(), Instant.now().plusSeconds(30))).singleElement()
                .extracting(Task::id).isEqualTo(replacement.id());
    }
}
