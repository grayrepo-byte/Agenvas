package dev.agenvas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.InitialModelContextService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL concurrency evidence for Run idempotency, snapshots, and project slot ownership. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = AgenvasApplication.class,
        properties = {"agenvas.identity.bootstrap-secret=run-integration-bootstrap-secret",
                "agenvas.llm.mode=configured"})
class AgentRunPostgresIT {

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
    private ArtifactService artifactService;

    @Autowired
    private AgentInstanceService agentService;

    @Autowired
    private AgentRunService runService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private InitialModelContextService initialContext;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebApplicationContext webContext;

    @Test
    void concurrentCreationReplaysOneRunAndWaitingStatesRetainTheSlot() throws Exception {
        AdminPrincipal owner = identityService.setup(
                "run-integration-bootstrap-secret", "run-admin", "run-password-123");
        Project project = projectService.create(
                owner.userId(), "Run project", Project.AspectRatio.LANDSCAPE_16_9);
        ArtifactService.ArtifactView brief = artifactService.create(
                owner.userId(),
                project.id(),
                Artifact.Kind.TEXT,
                "Brief",
                objectMapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Coffee\"}"));
        AgentInstance agent = agentService.create(
                owner.userId(),
                project.id(),
                "Creator",
                "Create a storyboard",
                List.of(new AgentInstanceService.BindingInput(
                        brief.artifact().id(), brief.currentVersion().id())));
        AgentRunService.RunPreflight preflight = runService.preflight(owner.userId(),
                project.id(), agent.id());
        assertThat(preflight.agentVersion()).isZero();
        assertThat(preflight.bindings()).singleElement()
                .satisfies(binding -> {
                    assertThat(binding.selectedVersionId())
                            .isEqualTo(brief.currentVersion().id());
                    assertThat(binding.artifactTitle()).isEqualTo("Brief");
                });
        assertThat(preflight.modelAvailable()).isFalse();
        assertThat(preflight.policySnapshot().path("maxModelTurns").asInt()).isEqualTo(12);
        assertThat(preflight.policySnapshot().path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(preflight.policySnapshot().path("systemPromptVersion").asInt())
                .isEqualTo(2);
        String reviewedModelSource = preflight.policySnapshot()
                .path("modelConfigSource").asText();
        int reviewedModelVersion = preflight.policySnapshot()
                .path("modelConfigVersion").asInt();
        assertProblem("MODEL_CONFIG_CONFLICT", () -> runService.create(owner.userId(),
                project.id(), agent.id(), "Create three shots", "stale-model-consent",
                preflight.agentVersion(), null, List.of(), reviewedModelSource,
                reviewedModelVersion + 1));
        assertProblem("SYSTEM_PROMPT_CONFLICT", () -> runService.create(owner.userId(),
                project.id(), agent.id(), "Create three shots", "stale-prompt-consent",
                preflight.agentVersion(), null, List.of(), reviewedModelSource,
                reviewedModelVersion, 1));
        assertThat(jdbcClient.sql("select count(*) from agent_run where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isZero();
        MockMvc mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        String preflightPath = "/api/v1/projects/" + project.id()
                + "/runs/preflight?agentId=" + agent.id();
        mvc.perform(get(preflightPath).with(authentication(
                        new UsernamePasswordAuthenticationToken(owner, null, List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings[0].selectedVersionId")
                        .value(brief.currentVersion().id().toString()))
                .andExpect(jsonPath("$.policySnapshot.modelConfigSource")
                        .value(reviewedModelSource))
                .andExpect(jsonPath("$.policySnapshot.modelConfigVersion")
                        .value(reviewedModelVersion))
                .andExpect(jsonPath("$.policySnapshot.systemPromptVersion").value(2))
                .andExpect(jsonPath("$.modelAvailable").value(false));
        mvc.perform(get(preflightPath)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/projects/" + project.id() + "/runs")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                owner, null, List.of())))
                        .with(csrf())
                        .header("Idempotency-Key", "stale-prompt-http")
                        .contentType("application/json")
                        .content("{\"agentId\":\"" + agent.id()
                                + "\",\"instruction\":\"Create three shots\""
                                + ",\"expectedSystemPromptVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_PROMPT_CONFLICT"));
        assertThat(jdbcClient.sql("select count(*) from agent_run where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isZero();

        List<AgentRunService.CreateResult> replays = concurrentSameKey(
                owner.userId(), project.id(), agent.id());
        assertThat(replays).extracting(result -> result.run().id()).containsOnly(replays.getFirst().run().id());
        assertThat(replays).hasSize(20);
        assertThat(replays).filteredOn(AgentRunService.CreateResult::replayed).hasSize(19);
        AgentRun run = replays.getFirst().run();
        assertThat(jdbcClient.sql("select count(*) from agent_run where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(taskService.listByRun(owner.userId(), project.id(), run.id()))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.kind()).isEqualTo(Task.Kind.AGENT_TURN);
                    assertThat(task.status()).isEqualTo(Task.Status.READY);
                    assertThat(task.stepKey()).isEqualTo("agent-turn-0");
                    assertThat(task.input().path("stepIndex").intValue()).isZero();
                });
        assertThat(run.contextSnapshot().get("bindings").get(0).get("selectedVersionId").stringValue())
                .isEqualTo(brief.currentVersion().id().toString());
        assertThat(run.contextSnapshot().path("bindings").path(0)
                .path("expectedVersion").longValue()).isEqualTo(0);
        assertThat(run.policySnapshot().get("maxModelTurns").intValue()).isEqualTo(12);
        assertThat(run.policySnapshot().path("systemPromptVersion").asInt()).isEqualTo(2);
        assertThat(initialContext.assemble(owner.userId(), project.id(), run.id())
                .getFirst().getText()).contains("no image pixels");

        agentService.update(owner.userId(), project.id(), agent.id(), 0,
                agent.name(), "Ignore the original storyboard", List.of());
        assertProblem("AGENT_VERSION_CONFLICT", () -> runService.create(owner.userId(),
                project.id(), agent.id(), "Create three shots", "stale-reviewed-version", 0L));
        artifactService.revise(owner.userId(), project.id(), brief.artifact().id(),
                brief.artifact().version(), null,
                objectMapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Changed tea\"}"));
        AgentRun snapshotted = runService.get(owner.userId(), project.id(), run.id());
        assertThat(snapshotted.contextSnapshot().get("bindings")).hasSize(1);
        assertThat(snapshotted.contextSnapshot().path("agentInstruction").asText())
                .isEqualTo("Create a storyboard");
        assertThat(initialContext.assemble(owner.userId(), project.id(), run.id()))
                .extracting(message -> message.getText())
                .anySatisfy(text -> assertThat(text).contains("Create a storyboard"))
                .anySatisfy(text -> assertThat(text).contains("Coffee")
                        .contains("expectedVersion=0")
                        .doesNotContain("Changed tea"));
        assertProblem(
                "IDEMPOTENCY_CONFLICT",
                () -> runService.create(
                        owner.userId(),
                        project.id(),
                        agent.id(),
                        "Different instruction",
                        "same-key"));

        AgentRun running = runService.transition(
                owner.userId(), project.id(), run.id(), 0, AgentRun.Status.RUNNING);
        AgentRun waiting = runService.transition(
                owner.userId(),
                project.id(),
                run.id(),
                running.version(),
                AgentRun.Status.WAITING_APPROVAL);
        assertThat(activeRun(project.id())).isEqualTo(run.id());
        assertThat(runService.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        AgentRun canceled = runService.cancel(owner.userId(), project.id(), waiting.id());
        assertThat(canceled.status()).isEqualTo(AgentRun.Status.CANCELED);
        assertThat(taskService.listByRun(owner.userId(), project.id(), run.id()))
                .singleElement().satisfies(task ->
                        assertThat(task.status()).isEqualTo(Task.Status.CANCELED));
        assertThat(canceled.completedAt()).isNotNull();
        assertThat(activeRun(project.id())).isNull();
        assertThat(runService.cancel(owner.userId(), project.id(), waiting.id()))
                .isEqualTo(canceled);

        List<String> competing = concurrentDifferentKeys(
                owner.userId(), project.id(), agent.id());
        assertThat(competing).filteredOn(value -> value.startsWith("CREATED:"))
                .hasSize(1);
        assertThat(competing).filteredOn("ACTIVE_RUN_EXISTS"::equals).hasSize(1);
        UUID winner = UUID.fromString(competing.stream()
                .filter(value -> value.startsWith("CREATED:"))
                .findFirst()
                .orElseThrow()
                .substring("CREATED:".length()));
        AgentRun winnerRunning = runService.transition(
                owner.userId(), project.id(), winner, 0, AgentRun.Status.RUNNING);
        AgentRun blocked = runService.transition(
                owner.userId(),
                project.id(),
                winner,
                winnerRunning.version(),
                AgentRun.Status.BLOCKED);
        assertThat(activeRun(project.id())).isEqualTo(winner);
        runService.transition(
                owner.userId(),
                project.id(),
                winner,
                blocked.version(),
                AgentRun.Status.FAILED);
        assertThat(activeRun(project.id())).isNull();
        AgentRun afterTerminal = runService.create(
                                owner.userId(),
                                project.id(),
                                agent.id(),
                                "Allowed after terminal state",
                                "after-terminal")
                        .run();
        assertThat(afterTerminal.status()).isEqualTo(AgentRun.Status.QUEUED);
        AgentRunService.RunPage firstHistory = runService.list(owner.userId(),
                project.id(), agent.id(), null, 1);
        assertThat(firstHistory.items()).extracting(AgentRun::id)
                .containsExactly(afterTerminal.id());
        assertThat(firstHistory.nextCursor()).isNotBlank();
        AgentRunService.RunPage secondHistory = runService.list(owner.userId(),
                project.id(), agent.id(), firstHistory.nextCursor(), 1);
        assertThat(secondHistory.items()).extracting(AgentRun::id)
                .containsExactly(winner);
        assertThat(secondHistory.nextCursor()).isNotBlank();
        assertThat(runService.list(owner.userId(), project.id(), agent.id(),
                secondHistory.nextCursor(), 1).items()).extracting(AgentRun::id)
                .containsExactly(run.id());
        assertProblem("VALIDATION_ERROR", () -> runService.list(owner.userId(),
                project.id(), agent.id(), "broken-cursor", 1));
        assertProblem("VALIDATION_ERROR", () -> runService.list(owner.userId(),
                project.id(), agent.id(), null, 101));
        assertProblem("RESOURCE_NOT_FOUND", () -> runService.list(UUID.randomUUID(),
                project.id(), agent.id(), null, 20));
        String historyPath = "/api/v1/projects/" + project.id() + "/runs?agentId=" + agent.id();
        mvc.perform(get(historyPath).with(authentication(
                        new UsernamePasswordAuthenticationToken(owner, null, List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(afterTerminal.id().toString()))
                .andExpect(jsonPath("$.items[0].contextSnapshot").doesNotExist());
        mvc.perform(get(historyPath)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/projects/" + project.id()
                        + "/runs?agentId=" + UUID.randomUUID())
                .with(authentication(new UsernamePasswordAuthenticationToken(
                        owner, null, List.of()))))
                .andExpect(status().isNotFound());
        assertThat(taskService.claimDue("media-worker", 1)).isEmpty();
        Task activeTurn = taskService.claimAgentTurns("model-worker", 1).getFirst();
        assertThat(activeTurn.runId()).isEqualTo(afterTerminal.id());
        assertThat(activeTurn.kind()).isEqualTo(Task.Kind.AGENT_TURN);
        assertThat(activeTurn.leaseEpoch()).isEqualTo(1);
        AgentRun started = runService.transition(owner.userId(), project.id(),
                afterTerminal.id(), afterTerminal.version(), AgentRun.Status.RUNNING);
        runService.transition(owner.userId(), project.id(), afterTerminal.id(),
                started.version(), AgentRun.Status.WAITING_APPROVAL);
        jdbcClient.sql("update task set lease_until = now() - interval '1 second' where id = :taskId")
                .param("taskId", activeTurn.id()).update();
        Task resumedWait = taskService.claimAgentTurns("recovery-worker", 1).getFirst();
        assertThat(resumedWait.id()).isEqualTo(activeTurn.id());
        assertThat(resumedWait.leaseEpoch()).isGreaterThan(activeTurn.leaseEpoch());
        assertProblem(
                "RESOURCE_NOT_FOUND",
                () -> runService.get(UUID.randomUUID(), project.id(), run.id()));
        assertThat(jdbcClient.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                        .query(String.class)
                        .single())
                .isEqualTo("40");

        Project httpProject = projectService.create(owner.userId(), "HTTP replay project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance httpAgent = agentService.create(owner.userId(), httpProject.id(),
                "HTTP Creator", "Create", List.of());
        assertConcurrentHttpCreate(mvc, owner, httpProject.id(), httpAgent.id());
    }

    /** Exercises the HTTP authentication, CSRF and idempotency boundary under 20 arrivals. */
    private void assertConcurrentHttpCreate(MockMvc mvc, AdminPrincipal owner,
            UUID projectId, UUID agentId) throws Exception {
        String path = "/api/v1/projects/" + projectId + "/runs";
        AgentRunService.RunPreflight reviewed = runService.preflight(owner.userId(),
                projectId, agentId);
        String body = "{\"agentId\":\"" + agentId
                + "\",\"instruction\":\"Create three shots\",\"expectedAgentVersion\":0"
                + ",\"expectedModelConfigSource\":\""
                + reviewed.policySnapshot().path("modelConfigSource").asText()
                + "\",\"expectedModelConfigVersion\":"
                + reviewed.policySnapshot().path("modelConfigVersion").asInt()
                + ",\"expectedSystemPromptVersion\":"
                + reviewed.policySnapshot().path("systemPromptVersion").asInt() + "}";
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> requests = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
            for (int index = 0; index < 20; index++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return mvc.perform(post(path)
                                    .with(authentication(new UsernamePasswordAuthenticationToken(
                                            owner, null, List.of())))
                                    .with(csrf())
                                    .header("Idempotency-Key", "http-same-key")
                                    .contentType("application/json")
                                    .content(body))
                            .andExpect(status().isAccepted()).andReturn();
                }));
            }
            start.countDown();
            List<MvcResult> responses = new ArrayList<>();
            for (Future<MvcResult> request : requests) {
                responses.add(request.get(30, TimeUnit.SECONDS));
            }
            assertThat(responses).hasSize(20);
            assertThat(responses.stream().map(result -> result.getResponse()
                    .getHeader("Idempotency-Replayed")).toList())
                    .containsOnlyOnce("false").contains("true");
            List<String> runIds = new ArrayList<>();
            for (MvcResult response : responses) {
                runIds.add(objectMapper.readTree(response.getResponse()
                        .getContentAsString()).path("id").asText());
            }
            assertThat(runIds).containsOnly(runIds.getFirst());
        }
        assertThat(jdbcClient.sql("select count(*) from agent_run where project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select count(*) from task where project_id = :projectId ")
                .param("projectId", projectId).query(Integer.class).single()).isEqualTo(1);
    }

    private List<AgentRunService.CreateResult> concurrentSameKey(
            UUID ownerId, UUID projectId, UUID agentId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AgentRunService.CreateResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
            for (int index = 0; index < 20; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return runService.create(
                            ownerId, projectId, agentId, "Create three shots", "same-key");
                }));
            }
            start.countDown();
            List<AgentRunService.CreateResult> results = new ArrayList<>();
            for (Future<AgentRunService.CreateResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private List<String> concurrentDifferentKeys(
            UUID ownerId, UUID projectId, UUID agentId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index++) {
                String key = "competing-" + index;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        AgentRun run = runService.create(
                                        ownerId, projectId, agentId, "Competing", key)
                                .run();
                        return "CREATED:" + run.id();
                    } catch (ApiProblemException problem) {
                        return problem.code();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private UUID activeRun(UUID projectId) {
        return jdbcClient.sql("select active_run_id from project where id = :projectId")
                .param("projectId", projectId)
                .query(UUID.class)
                .optional()
                .orElse(null);
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
