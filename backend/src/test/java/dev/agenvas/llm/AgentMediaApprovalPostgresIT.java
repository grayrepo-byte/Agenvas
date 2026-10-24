package dev.agenvas.llm;

import static dev.agenvas.testing.AgentFixture.configureDirector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.event.application.ProjectEventRecorded;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentMediaApprovalService;
import dev.agenvas.llm.application.AgentMediaApprovalService.ApprovalView;
import dev.agenvas.llm.application.AgentMediaOutcomeService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.domain.AgentMediaApproval;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.MockImageAdapter;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskRecoveryScheduler;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.storyboard.domain.Storyboard;
import dev.agenvas.storyboard.application.StoryboardService;
import dev.agenvas.storyboard.application.StoryboardService.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL and local Mock media; the deterministic model never polls or contacts a Provider. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentMediaApprovalPostgresIT.FakeConfig.class},
        properties = {
                "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false",
                "spring.main.allow-bean-definition-overriding=true"})
class AgentMediaApprovalPostgresIT {
    private static final String MODEL_WORKER = "approval-model-test";
    private static final String MEDIA_WORKER = "approval-media-test";
    private static final int CONCURRENT_APPROVERS = 2;
    private static final int CONCURRENT_WAIT_SECONDS = 30;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path storage = privateStorage();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // PER_CLASS can prepare the Spring test instance before Testcontainers' beforeAll callback.
        if (!POSTGRES.isRunning()) POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> storage.toString());
        registry.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static Path privateStorage() {
        try {
            return Files.createTempDirectory("agenvas-media-approval-it-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private DirectMediaTaskService directMediaTasks;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AgentTurnWorker modelWorker;
    @Autowired private dev.agenvas.llm.application.AgentTurnCommitService commits;
    @Autowired private MediaExecutionWorker mediaWorker;
    @Autowired private AgentMediaApprovalService approvals;
    @Autowired private AgentMediaOutcomeService outcomes;
    @Autowired private MediaDraftService drafts;
    @Autowired private ArtifactService artifacts;
    @Autowired private MediaCapabilityService capabilities;
    @Autowired private CanvasService canvas;
    @Autowired private CanvasConnectionService connections;
    @Autowired private AssetService assets;
    @Autowired private MockImageAdapter mockImages;
    @Autowired private ProjectEventService events;
    @Autowired private FakeGateway gateway;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext context;
    private AdminPrincipal owner;
    private MockMvc mvc;
    private RequestPostProcessor auth;

    @BeforeAll
    void setup() throws Exception {
        Files.setPosixFilePermissions(storage, PosixFilePermissions.fromString("rwx------"));
        owner = identities.setup("synthetic-approval-admin",
                "synthetic-approval-password-123");
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        auth = authentication(new UsernamePasswordAuthenticationToken(owner, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterAll
    void removeOnlyThisTestsMedia() throws IOException {
        try (var paths = Files.walk(storage)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Autowired private dev.agenvas.storyboard.application.StoryboardService storyboards;

    @Test
    void mediaCardsExposeApprovedAgentTasksThroughTheirTaskQuery() throws Exception {
        Scenario scenario = propose(outputs("IMAGE", "VIDEO", "AUDIO"));
        var productions = scenario.approval().outputs().stream().map(output ->
                new dev.agenvas.storyboard.application.StoryboardService.ProductionInput(
                        switch (output.kind()) {
                            case IMAGE -> dev.agenvas.storyboard.domain.Storyboard.Role.KEYFRAME;
                            case VIDEO -> dev.agenvas.storyboard.domain.Storyboard.Role.VIDEO;
                            case AUDIO -> dev.agenvas.storyboard.domain.Storyboard.Role.AUDIO;
                            default -> throw new IllegalStateException();
                        }, output.canvasItemId(), null, false)).toList();
        storyboards.save(owner.userId(), scenario.project().id(),
                new dev.agenvas.storyboard.application.StoryboardService.Save(0,
                        List.of(new dev.agenvas.storyboard.application.StoryboardService.ShotInput(
                                UUID.randomUUID(), "SHOT-001", "SC-01", "Approval shot", "", "", BigDecimal.ONE,
                                null, List.of(), productions)), null));
        assertThat(storyboards.mediaApprovals(owner.userId(), scenario.project().id()))
                .extracting(ApprovalView::id).containsExactly(scenario.approval().id());
        var unlinked = projects.create(owner.userId(), "Unlinked storyboard", Project.AspectRatio.LANDSCAPE_16_9);
        assertThat(storyboards.mediaApprovals(owner.userId(), unlinked.id())).isEmpty();
        for (var output : scenario.approval().outputs()) {
            assertThat(cardTasks(scenario, output)).isEmpty();
        }
        decide(scenario, "APPROVE", "card-status-approve");
        for (var output : scenario.approval().outputs()) {
            assertThat(cardTasks(scenario, output)).singleElement().satisfies(task -> {
                assertThat(task.path("runId").asText()).isEqualTo(scenario.run().id().toString());
                assertThat(task.path("status").asText()).isEqualTo("READY");
                assertThat(task.at("/input/canvasItemId").asText()).isEqualTo(output.canvasItemId().toString());
            });
        }
        var image = scenario.approval().outputs().getFirst();
        String imageTasksPath = "/api/v1/projects/" + scenario.project().id()
                + "/artifacts/" + image.artifactId() + "/run";
        mvc.perform(get(imageTasksPath).param("canvasItemId", image.canvasItemId().toString()))
                .andExpect(status().isUnauthorized());
        var outsider = authentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "synthetic-card-outsider"), null, List.of()));
        mvc.perform(get(imageTasksPath).with(outsider).param("canvasItemId", image.canvasItemId().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get(imageTasksPath).with(auth)
                        .param("canvasItemId", scenario.approval().outputs().getLast().canvasItemId().toString()))
                .andExpect(status().isBadRequest());

        // A second placement of the same Artifact must not inherit the first card's task.
        UUID siblingId = UUID.randomUUID();
        mvc.perform(post("/api/v1/projects/" + scenario.project().id() + "/canvas/commands")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"commands\":[{\"type\":\"PLACE_ARTIFACT\",\"itemId\":\"" + siblingId
                                + "\",\"artifactId\":\"" + image.artifactId()
                                + "\",\"x\":0,\"y\":0,\"width\":280,\"height\":240,"
                                + "\"zIndex\":0,\"locked\":false}]}"))
                .andExpect(status().isOk());
        assertThat(mapper.readTree(mvc.perform(get(imageTasksPath).with(auth)
                        .param("canvasItemId", siblingId.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())).isEmpty();

        for (int index = 0; index < scenario.approval().outputs().size(); index++) {
            Task lease = tasks.claimBoundMedia(MEDIA_WORKER, 1).getFirst();
            var output = scenario.approval().outputs().stream()
                    .filter(candidate -> candidate.canvasItemId().toString()
                            .equals(lease.input().path("canvasItemId").asText())).findFirst().orElseThrow();
            assertThat(cardTasks(scenario, output).get(0).path("status").asText()).isEqualTo("RUNNING");
            tasks.beginSubmission(lease, MEDIA_WORKER);
            assertThat(cardTasks(scenario, output).get(0).path("status").asText()).isEqualTo("SUBMITTING");
            tasks.waitForProvider(lease, MEDIA_WORKER, "synthetic-card-request-" + index,
                    Instant.now().plusSeconds(CONCURRENT_WAIT_SECONDS));
            assertThat(cardTasks(scenario, output).get(0).path("status").asText()).isEqualTo("WAITING_PROVIDER");
        }
        runs.cancel(owner.userId(), scenario.project().id(), scenario.run().id());
        for (var output : scenario.approval().outputs()) {
            assertThat(cardTasks(scenario, output)).singleElement().satisfies(task ->
                    assertThat(task.path("cancelRequested").asBoolean()).isTrue());
        }
    }

    private JsonNode cardTasks(Scenario scenario, AgentMediaApprovalService.ApprovalOutput output) throws Exception {
        String path = "/api/v1/projects/" + scenario.project().id()
                + "/artifacts/" + output.artifactId() + "/run";
        return mapper.readTree(mvc.perform(get(path).with(auth)
                        .param("canvasItemId", output.canvasItemId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void authenticatedBatchApprovalWaitsForAllMediaAndContinuesTheOriginalToolCallExactlyOnce() throws Exception {
        Scenario scenario = propose(outputs("IMAGE", "AUDIO", "VIDEO"));
        assertThat(scenario.approval().outputs()).hasSize(3);
        assertThat(scenario.approval().taskIds()).isEmpty();
        assertThat(scenario.approval().outputs()).allSatisfy(output -> {
            assertThat(output.preview().path("mediaInputs").isArray()).isTrue();
            assertThat(output.preview().path("mediaInputs")).isEmpty();
            assertThat(output.preview().toString()).doesNotContain("endpoint", "apiKey", "credential");
        });
        assertThat(mediaTasks(scenario)).isEmpty();
        assertThat(mediaUsage(scenario)).isZero();
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isZero();
        mvc.perform(get(base(scenario))).andExpect(status().isUnauthorized());
        mvc.perform(get(base(scenario)).with(auth)).andExpect(status().isOk());
        mvc.perform(get(decisionPath(scenario).replace("/decision", "")).with(auth))
                .andExpect(status().isOk());
        mvc.perform(post(decisionPath(scenario)).with(auth).header("Idempotency-Key", "approve")
                        .contentType("application/json").content(decision("APPROVE")))
                .andExpect(status().isForbidden());
        var outsider = authentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "synthetic-outsider"), null, List.of()));
        mvc.perform(get(base(scenario)).with(outsider)).andExpect(status().isNotFound());

        JsonNode approved = decide(scenario, "APPROVE", "approve");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("taskIds")).hasSize(3);
        assertThat(mediaTasks(scenario)).hasSize(3).allSatisfy(task -> {
            assertThat(task.runId()).isEqualTo(scenario.run().id());
            assertThat(task.approvedMedia()).isTrue();
        });
        assertThat(decide(scenario, "APPROVE", "approve").path("taskIds"))
                .isEqualTo(approved.path("taskIds"));
        mvc.perform(post(decisionPath(scenario)).with(auth).with(csrf())
                        .header("Idempotency-Key", "approve").contentType("application/json")
                        .content(decision("REJECT"))).andExpect(status().isConflict());
        assertThat(mediaTasks(scenario)).hasSize(3);

        for (int index = 0; index < 3; index++) {
            assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
            if (index < 2) {
                assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.WAITING_TASKS);
                assertThat(turnCount(scenario)).isEqualTo(1);
            }
        }
        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.SUCCEEDED);
        assertThat(currentApproval(scenario).result().path("tasks")).hasSize(3);
        assertThat(mediaTasks(scenario)).allSatisfy(task -> {
            assertThat(task.status()).isEqualTo(Task.Status.SUCCEEDED);
            UUID version = UUID.fromString(task.output().path("artifactVersionId").asText());
            assertThat(version).isNotNull();
            assertThat(task.output().path("selected").asBoolean()).isTrue();
            UUID asset = jdbc.sql("select (content_json->>'assetId')::uuid from artifact_version where id=:id")
                    .param("id", version).query(UUID.class).single();
            assertThat(Files.exists(assets.get(owner.userId(), scenario.project().id(), asset).path())).isTrue();
        });
        assertThat(decide(scenario, "APPROVE", "approve").path("status").asText()).isEqualTo("SUCCEEDED");
        replayOutcome(scenario);
        finish(scenario, "SUCCEEDED");
        assertThat(gateway.plan(scenario.run().id()).reply.at("/mediaApproval/tasks"))
                .allSatisfy(task -> assertThat(task.path("artifactVersionId").asText()).isNotBlank());
    }

    @Test
    void largeSkillReceiptsResumeWithReferencesAndKeepDurableApprovalDetails() throws Exception {
        Scenario scenario = propose(outputs("IMAGE", "IMAGE", "IMAGE", "IMAGE", "IMAGE", "IMAGE"));
        UUID runId = scenario.run().id();
        String originalRequest = jdbc.sql("select request_json::text from llm_turn where run_id=:run and step_index=0")
                .param("run", runId).query(String.class).single();
        ObjectNode targets = (ObjectNode) mapper.readTree(jdbc.sql("select target_json::text from agent_media_approval where id=:id")
                .param("id", scenario.approval().id()).query(String.class).single());
        var skill = mapper.createObjectNode().put("skillId", UUID.randomUUID().toString())
                .put("skillVersionId", UUID.randomUUID().toString()).put("name", "Synthetic method")
                .put("skillMd", "x".repeat(32 * 1024));
        for (JsonNode output : targets.path("outputs")) {
            var source = mapper.createObjectNode().put("schemaVersion", 2); source.putArray("skills").add(skill.deepCopy());
            ((ObjectNode) output.path("preview")).set("creativeSkill", source);
        }
        // Exercise an oversized durable approval receipt without exposing Skill bodies to the model.
        jdbc.sql("update agent_media_approval set target_json=cast(:targets as jsonb) where id=:id")
                .param("targets", targets.toString()).param("id", scenario.approval().id()).update();
        ObjectNode largeReceipt = (ObjectNode) mapper.readTree(jdbc.sql("select result_json::text from tool_execution where run_id=:run")
                .param("run", runId).query(String.class).single());
        largeReceipt.set("data", mapper.valueToTree(currentApproval(scenario)));
        assertThat(mapper.writeValueAsBytes(largeReceipt).length).isGreaterThan(192 * 1024);
        jdbc.sql("update tool_execution set result_json=cast(:receipt as jsonb) where run_id=:run")
                .param("receipt", largeReceipt.toString()).param("run", runId).update();
        String frozenReceipt = jdbc.sql("select result_json::text from tool_execution where run_id=:run")
                .param("run", runId).query(String.class).single();

        decide(scenario, "REJECT", "large-receipt-reject");
        finish(scenario, "REJECTED");

        var modelReceipt = gateway.plan(runId).reply;
        assertThat(mapper.writeValueAsBytes(modelReceipt).length).isLessThan(12 * 1024);
        assertThat(modelReceipt.toString()).doesNotContain("skillMd");
        assertThat(modelReceipt.at("/data/creativeSkills")).hasSize(1);
        assertThat(modelReceipt.at("/data/creativeSkills/0/skillVersionId")).isEqualTo(skill.path("skillVersionId"));
        assertThat(currentApproval(scenario).outputs()).hasSize(6)
                .allSatisfy(output -> assertThat(output.preview().at("/creativeSkill/skills/0")).isEqualTo(skill));
        assertThat(dev.agenvas.shared.crypto.Sha256.hex(jdbc.sql("select result_json::text from tool_execution where run_id=:run")
                .param("run", runId).query(String.class).single()))
                .isEqualTo(dev.agenvas.shared.crypto.Sha256.hex(frozenReceipt));
        assertThat(jdbc.sql("select request_json::text from llm_turn where run_id=:run and step_index=0")
                .param("run", runId).query(String.class).single()).isEqualTo(originalRequest);
        assertThat(mediaTasks(scenario)).isEmpty();
    }

    @Test
    void failedTurnWithPendingApprovalIsNotAutomaticallyEnded() {
        Scenario scenario = propose(outputs("IMAGE"));
        var current = runs.get(owner.userId(), scenario.project().id(), scenario.run().id());
        Task failed = tasks.create(owner.userId(), scenario.project().id(), scenario.run().id(), "agent-turn-" + current.nextStepIndex(),
                Task.Kind.AGENT_TURN, mapper.createObjectNode().put("schemaVersion", 1).put("stepIndex", current.nextStepIndex()), 1);
        // Persist a legacy blocked failure while the approval has no final reply or media Tasks.
        jdbc.sql("update task set status='FAILED', error_code='AGENT_TURN_FAILED', completed_at=now() where id=:id")
                .param("id", failed.id()).update();
        runs.transition(owner.userId(), scenario.project().id(), scenario.run().id(), current.version(), AgentRun.Status.BLOCKED);
        assertThat(runs.get(owner.userId(), scenario.project().id(), scenario.run().id()).status()).isEqualTo(AgentRun.Status.BLOCKED);
        assertThat(tasks.hasSettledAgentFailure(owner.userId(), scenario.project().id(), scenario.run().id(), current.nextStepIndex())).isTrue();
        assertThat(commits.finishBlockedFailures(100)).isZero();
        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.PENDING);
        assertThat(jdbc.sql("select active_run_id from project where id=:id").param("id", scenario.project().id()).query(UUID.class).single()).isEqualTo(scenario.run().id());
        runs.cancel(owner.userId(), scenario.project().id(), scenario.run().id());
    }

    @Test
    void continuationTimeoutRetriesOnlyTheModelAndKeepsTheApprovedImage() throws Exception {
        Scenario scenario = propose(outputs("IMAGE"));
        decide(scenario, "APPROVE", "retry-model-after-image");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        Task generated = mediaTasks(scenario).getFirst();
        assertThat(generated.status()).isEqualTo(Task.Status.SUCCEEDED);
        gateway.plan(scenario.run().id()).failNextCall = true;
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        Task waiting = tasks.listByRun(owner.userId(), scenario.project().id(), scenario.run().id()).stream()
                .filter(task -> task.kind() == Task.Kind.AGENT_TURN && task.status() == Task.Status.READY)
                .findFirst().orElseThrow();
        assertThat(waiting.errorCode()).isEqualTo("LLM_CALL_TIMEOUT");
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.RUNNING);
        // Advance only this retry's due time in the fixture; do not wait or alter media ledgers.
        jdbc.sql("update task set next_action_at=created_at where id=:id")
                .param("id", waiting.id()).update();
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(mediaTasks(scenario)).singleElement().satisfies(task -> {
            assertThat(task.id()).isEqualTo(generated.id());
            assertThat(task.attemptNo()).isEqualTo(1);
            assertThat(task.output()).isEqualTo(generated.output());
        });
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isZero();
        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.SUCCEEDED);
    }

    @Test
    void approvedGeneratedMediaCanBeReadByTheResumedAgent() throws Exception {
        Scenario scenario = propose(outputs("IMAGE"));
        Plan plan = gateway.plan(scenario.run().id());
        plan.readGeneratedOutput = true;
        decide(scenario, "APPROVE", "read-generated-approve");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.SUCCEEDED);
        Task generated = mediaTasks(scenario).getFirst();
        assertThat(generated.status()).isEqualTo(Task.Status.SUCCEEDED);
        UUID artifactId = UUID.fromString(generated.output().path("artifactId").asText());
        UUID itemId = scenario.approval().outputs().getFirst().canvasItemId();
        String versionId = generated.output().path("artifactVersionId").asText();
        assertThat(versionId).isNotBlank();
        assertThat(jdbc.sql("select resource_default_version_id is null from artifact where id=:id")
                .param("id", artifactId)
                .query(Boolean.class).single()).isTrue();

        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(scenario).status())
                .as("Reading the successful approved media must continue the original Run")
                .isEqualTo(AgentRun.Status.RUNNING);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id=:run "
                        + "and tool_name='read_artifacts' and status='COMPLETED'")
                .param("run", scenario.run().id()).query(Long.class).single()).isEqualTo(1);
        assertThat(mediaTasks(scenario)).singleElement().satisfies(task -> {
            assertThat(task.id()).isEqualTo(generated.id());
            assertThat(task.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(task.output().path("artifactVersionId").asText()).isEqualTo(versionId);
        });

        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(scenario).status())
                .as("Refreshing the current canvas must retain its selected node")
                .isEqualTo(AgentRun.Status.RUNNING);
        assertThat(canvas.list(owner.userId(), scenario.project().id()).stream()
                .filter(entry -> entry.item().subjectId().equals(artifactId)).toList())
                .singleElement().satisfies(entry -> {
                    assertThat(entry.item().id()).isEqualTo(itemId);
                    assertThat(entry.item().selectedVersionId()).isEqualTo(UUID.fromString(versionId));
                });

        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(scenario).status())
                .as("Arranging approved media must preserve its node-selected content")
                .isEqualTo(AgentRun.Status.RUNNING);
        assertThat(canvas.list(owner.userId(), scenario.project().id()).stream()
                .filter(entry -> entry.item().id().equals(itemId)).toList())
                .singleElement().satisfies(entry -> {
                    assertThat(entry.item().selectedVersionId()).isEqualTo(UUID.fromString(versionId));
                    assertThat(entry.item().version()).isEqualTo(plan.directoryReply.at("/data/0/itemVersion").asLong() + 1);
                });

        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(plan.calls).hasValue(5);
        assertThat(plan.artifactReply.at("/data/0/versionId").asText()).isEqualTo(versionId);
        assertThat(plan.artifactReply.at("/data/0/kind").asText()).isEqualTo("IMAGE");
        assertThat(plan.directoryReply.at("/data/0/itemId").asText()).isEqualTo(itemId.toString());
        assertThat(plan.arrangementReply.at("/data/0/itemId").asText()).isEqualTo(itemId.toString());
        assertThat(plan.arrangementReply.at("/data/0/itemVersion").asLong())
                .isEqualTo(plan.directoryReply.at("/data/0/itemVersion").asLong() + 1);
        assertThat(mediaTasks(scenario)).singleElement().satisfies(task -> {
            assertThat(task.id()).isEqualTo(generated.id());
            assertThat(task.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(task.attemptNo()).isEqualTo(1);
        });
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isZero();
        assertThat(jdbc.sql("select resource_default_version_id is null from artifact where id=:id")
                .param("id", artifactId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void archivedImageCanFeedTheNextApprovedVideoAndItsResultKeepsTheExactReference() throws Exception {
        Scenario image = propose(outputs("IMAGE"));
        gateway.plan(image.run().id()).imageToVideo = true;
        decide(image, "APPROVE", "director-image-stage");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        Task generatedImage = mediaTasks(image).getFirst();
        assertThat(generatedImage.status()).isEqualTo(Task.Status.SUCCEEDED);
        String imageVersion = generatedImage.output().path("artifactVersionId").asText();
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        ApprovalView next = approvals.list(owner.userId(), image.project().id(), image.run().id()).stream()
                .filter(value -> value.status() == AgentMediaApproval.Status.PENDING).findFirst().orElseThrow();
        Scenario video = new Scenario(image.project(), image.run(), next);
        assertThat(next.outputs()).singleElement().satisfies(output -> {
            assertThat(output.preview().at("/mediaInputs/0/versionId").asText()).isEqualTo(imageVersion);
            assertThat(output.preview().at("/mediaInputs/0/role").asText()).isEqualTo("START_FRAME");
        });
        UUID videoCardId = next.outputs().getFirst().canvasItemId();
        var line = connections.list(owner.userId(), video.project().id()).stream()
                .filter(value -> value.targetCanvasItemId().equals(videoCardId)).findFirst().orElseThrow();
        assertThat(line.relationType()).isEqualTo(CanvasConnection.RelationType.MEDIA_INPUT);
        assertThat(line.sourceCanvasItemId()).isEqualTo(image.approval().outputs().getFirst().canvasItemId());
        assertThat(line.sourceArtifactVersionId().toString()).isEqualTo(imageVersion);
        var preparedDraft = drafts.get(owner.userId(), video.project().id(), videoCardId);
        assertThat(preparedDraft.version()).isEqualTo(next.outputs().getFirst().draftVersion());
        assertThat(preparedDraft.mediaInputs()).singleElement().satisfies(input -> {
            assertThat(input.role()).isEqualTo(MediaDraft.InputRole.START_FRAME);
            assertThat(input.sources()).singleElement().satisfies(source -> {
                assertThat(source.type()).isEqualTo(MediaDraft.SourceType.CONNECTION);
                assertThat(source.connectionId()).isEqualTo(line.id());
            });
        });
        decide(video, "APPROVE", "director-video-stage");
        Task accepted = mediaTasks(video).stream().filter(task -> task.kind() == Task.Kind.VIDEO_GENERATION).findFirst().orElseThrow();
        assertThat(accepted.input().at("/mediaInput/images/0/versionId").asText()).isEqualTo(imageVersion);
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        Task completed = tasks.listByRun(owner.userId(), video.project().id(), video.run().id()).stream()
                .filter(task -> task.id().equals(accepted.id())).findFirst().orElseThrow();
        assertThat(completed.status()).as("Mock image-to-video must archive a usable result").isEqualTo(Task.Status.SUCCEEDED);
        var card = canvas.list(owner.userId(), video.project().id()).stream()
                .filter(value -> value.item().id().equals(next.outputs().getFirst().canvasItemId())).findFirst().orElseThrow();
        assertThat(card.selectedVersion().frozenInput().at("/images/0/versionId").asText()).isEqualTo(imageVersion);
        assertThat(card.selectedVersion().frozenInput().at("/images/0/role").asText()).isEqualTo("START_FRAME");
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(video).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        connections.disconnect(owner.userId(), video.project().id(), line.id(), preparedDraft.version());
        assertThat(drafts.get(owner.userId(), video.project().id(), videoCardId).mediaInputs()).isEmpty();
        assertThat(connections.list(owner.userId(), video.project().id())).noneMatch(value -> value.id().equals(line.id()));
        assertThat(canvas.list(owner.userId(), video.project().id()).stream()
                .filter(value -> value.item().id().equals(videoCardId)).findFirst().orElseThrow()
                .selectedVersion().frozenInput().at("/images/0/versionId").asText()).isEqualTo(imageVersion);
    }

    @Test
    void removingTheLastReferenceNodeRevokesReadAndGenerationSourceAuthority() throws Exception {
        Scenario image = propose(outputs("IMAGE"));
        gateway.plan(image.run().id()).imageToVideo = true;
        decide(image, "APPROVE", "removed-source-image-stage");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        UUID imageVersion = UUID.fromString(mediaTasks(image).getFirst().output().path("artifactVersionId").asText());
        var original = canvas.list(owner.userId(), image.project().id()).getFirst().item();
        canvas.apply(owner.userId(), image.project().id(), List.of(new CanvasService.Remove(original.id(), original.version())));
        assertThatThrownBy(() -> artifacts.requireCanvasVersion(owner.userId(), image.project().id(), imageVersion))
                .isInstanceOfSatisfying(ApiProblemException.class, failure -> assertThat(failure.code()).isEqualTo("INPUT_SCOPE_DENIED"));
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(image).status()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(approvals.list(owner.userId(), image.project().id(), image.run().id()))
                .noneMatch(approval -> approval.status() == AgentMediaApproval.Status.PENDING);
        assertThat(canvas.list(owner.userId(), image.project().id())).isEmpty();
        assertThat(tasks.listByRun(owner.userId(), image.project().id(), image.run().id()))
                .noneMatch(task -> task.kind() == Task.Kind.VIDEO_GENERATION);
    }

    @Test
    void staleCasAndConcurrentApprovalNeverDuplicateMediaTasksOrCostReservations() throws Exception {
        Scenario scenario = propose(outputs("IMAGE"));
        mvc.perform(post(decisionPath(scenario)).with(auth).with(csrf())
                        .header("Idempotency-Key", "wrong-version").contentType("application/json")
                        .content(mapper.createObjectNode().put("expectedVersion", 1).put("decision", "APPROVE").toString()))
                .andExpect(status().isConflict());
        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.PENDING);
        assertThat(mediaTasks(scenario)).isEmpty();
        assertThat(mediaUsage(scenario)).isZero();

        var executor = new ThreadPoolExecutor(CONCURRENT_APPROVERS, CONCURRENT_APPROVERS,
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(CONCURRENT_APPROVERS));
        var ready = new CountDownLatch(CONCURRENT_APPROVERS);
        var start = new CountDownLatch(1);
        List<ApprovalView> responses;
        try {
            Callable<ApprovalView> command = () -> {
                ready.countDown();
                if (!start.await(CONCURRENT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent approval start timed out");
                }
                return approvals.decide(owner.userId(), scenario.project().id(), scenario.run().id(),
                        scenario.approval().id(), 0, AgentMediaApprovalService.Decision.APPROVE, "concurrent-approve");
            };
            Future<ApprovalView> first = executor.submit(command);
            Future<ApprovalView> second = executor.submit(command);
            assertThat(ready.await(CONCURRENT_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            responses = List.of(first.get(CONCURRENT_WAIT_SECONDS, TimeUnit.SECONDS),
                    second.get(CONCURRENT_WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(CONCURRENT_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.status()).isEqualTo(AgentMediaApproval.Status.APPROVED);
            assertThat(response.taskIds()).hasSize(1);
        });
        assertThat(responses.getFirst().taskIds()).isEqualTo(responses.getLast().taskIds());
        UUID taskId = responses.getFirst().taskIds().getFirst();
        assertThat(mediaTasks(scenario)).singleElement().satisfies(task -> {
            assertThat(task.id()).isEqualTo(taskId);
            assertThat(task.status()).isEqualTo(Task.Status.READY);
        });
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id=:id and entry_type='RESERVATION'")
                .param("id", taskId).query(Long.class).single()).isEqualTo(1);
        assertThat(tasks.listProviderAttempts(owner.userId(), scenario.project().id(), taskId)).isEmpty();
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        finish(scenario, "SUCCEEDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "VIDEO", "AUDIO", "IMAGE,VIDEO,AUDIO"})
    void rejectingABatchRemovesItsUnchangedEmptyOutputCards(String kinds) throws Exception {
        Scenario scenario = propose(outputs(kinds.split(",")));
        List<UUID> outputIds = scenario.approval().outputs().stream()
                .map(AgentMediaApprovalService.ApprovalOutput::canvasItemId).toList();
        assertThat(canvas.list(owner.userId(), scenario.project().id()))
                .extracting(view -> view.item().id()).containsAll(outputIds);
        long beforeDecision = events.listAfter(owner.userId(), scenario.project().id(), 0, 200)
                .getLast().seq();

        assertThat(decide(scenario, "REJECT", "reject-empty-outputs").path("status").asText())
                .isEqualTo("REJECTED");
        assertThat(canvas.list(owner.userId(), scenario.project().id()))
                .extracting(view -> view.item().id()).doesNotContainAnyElementsOf(outputIds);
        assertThat(mediaTasks(scenario)).isEmpty();
        assertThat(mediaUsage(scenario)).isZero();
        assertThat(jdbc.sql("select count(*) from media_draft where project_id=:project")
                .param("project", scenario.project().id()).query(Long.class).single()).isZero();
        assertThat(currentApproval(scenario).outputs()).hasSize(outputIds.size());
        assertThat(events.listAfter(owner.userId(), scenario.project().id(), beforeDecision, 200)
                .stream().filter(event -> "canvas.items.changed".equals(event.type())))
                .hasSize(outputIds.size());
        long afterDecision = events.listAfter(owner.userId(), scenario.project().id(), 0, 200)
                .getLast().seq();
        assertThat(decide(scenario, "REJECT", "reject-empty-outputs").path("status").asText())
                .isEqualTo("REJECTED");
        assertThat(events.listAfter(owner.userId(), scenario.project().id(), afterDecision, 200)).isEmpty();
        finish(scenario, "REJECTED");
        assertThat(canvas.list(owner.userId(), scenario.project().id()))
                .extracting(view -> view.item().id()).doesNotContainAnyElementsOf(outputIds);
    }

    @Test
    void rejectingABatchPreservesEditedDraftsAndIndependentGeneration() throws Exception {
        Scenario scenario = propose(outputs("IMAGE", "VIDEO", "AUDIO"));
        var edited = scenario.approval().outputs().get(0);
        var generating = scenario.approval().outputs().get(1);
        var untouched = scenario.approval().outputs().get(2);
        var draft = drafts.get(owner.userId(), scenario.project().id(), edited.canvasItemId());
        drafts.save(owner.userId(), scenario.project().id(), edited.canvasItemId(), draft.version(),
                "Human work to preserve", draft.parameters(), draft.durationSeconds(),
                draft.capabilityId(), draft.videoInputMode(), List.of(), List.of(), null);
        Task independent = directMediaTasks.run(owner.userId(), scenario.project().id(),
                generating.artifactId(), generating.canvasItemId(), generating.draftVersion(),
                "independent-generation");

        decide(scenario, "REJECT", "reject-preserve-work");

        assertThat(canvas.list(owner.userId(), scenario.project().id()))
                .extracting(view -> view.item().id())
                .contains(edited.canvasItemId(), generating.canvasItemId())
                .doesNotContain(untouched.canvasItemId());
        assertThat(drafts.get(owner.userId(), scenario.project().id(), edited.canvasItemId()).prompt())
                .isEqualTo("Human work to preserve");
        assertThat(mediaTasks(scenario)).isEmpty();
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), scenario.project().id(), independent.id()).status())
                .isEqualTo(Task.Status.SUCCEEDED);
        assertThat(canvas.listMediaVersions(owner.userId(), scenario.project().id(), generating.canvasItemId()))
                .hasSize(1);
        finish(scenario, "REJECTED");
    }

    @Test
    void rejectingAReferencedVideoRemovesOnlyItsOutputAndInputConnection() throws Exception {
        Scenario image = propose(outputs("IMAGE"));
        gateway.plan(image.run().id()).imageToVideo = true;
        decide(image, "APPROVE", "reference-source");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        ApprovalView next = approvals.list(owner.userId(), image.project().id(), image.run().id())
                .stream().filter(value -> value.status() == AgentMediaApproval.Status.PENDING)
                .findFirst().orElseThrow();
        Scenario video = new Scenario(image.project(), image.run(), next);
        UUID videoId = next.outputs().getFirst().canvasItemId();
        UUID sourceId = image.approval().outputs().getFirst().canvasItemId();
        assertThat(connections.list(owner.userId(), video.project().id()))
                .anyMatch(connection -> connection.targetCanvasItemId().equals(videoId));

        decide(video, "REJECT", "reject-referenced-output");

        assertThat(canvas.list(owner.userId(), video.project().id()))
                .extracting(view -> view.item().id()).contains(sourceId).doesNotContain(videoId);
        assertThat(connections.list(owner.userId(), video.project().id()))
                .noneMatch(connection -> connection.targetCanvasItemId().equals(videoId));
        assertThat(canvas.listMediaVersions(owner.userId(), video.project().id(), sourceId)).hasSize(1);
        assertThat(mediaTasks(video)).singleElement()
                .satisfies(task -> assertThat(task.kind()).isEqualTo(Task.Kind.IMAGE_GENERATION));
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(video).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(gateway.plan(video.run().id()).videoReply.at("/mediaApproval/status").asText())
                .isEqualTo("REJECTED");
    }

    @Test
    void rejectionAndPlaceholderRemovalRollBackTogether() throws Exception {
        Scenario scenario = propose(outputs("IMAGE"));
        long beforeDecision = events.listAfter(owner.userId(), scenario.project().id(), 0, 200)
                .getLast().seq();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            approvals.decide(owner.userId(), scenario.project().id(), scenario.run().id(),
                    scenario.approval().id(), 0, AgentMediaApprovalService.Decision.REJECT,
                    "rolled-back-rejection");
            assertThat(canvas.list(owner.userId(), scenario.project().id())).isEmpty();
            transaction.setRollbackOnly();
        });

        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.PENDING);
        assertThat(canvas.list(owner.userId(), scenario.project().id()))
                .extracting(view -> view.item().id())
                .contains(scenario.approval().outputs().getFirst().canvasItemId());
        assertThat(events.listAfter(owner.userId(), scenario.project().id(), beforeDecision, 200)).isEmpty();
        assertThat(turnCount(scenario)).isEqualTo(1);
        decide(scenario, "REJECT", "rolled-back-rejection");
        finish(scenario, "REJECTED");
    }

    @Test
    void rejectAndBothExpiryPathsProduceRepliesWithoutGenerationRetry() throws Exception {
        Scenario rejected = propose(outputs("IMAGE"));
        assertThat(decide(rejected, "REJECT", "reject").path("status").asText()).isEqualTo("REJECTED");
        assertThat(mediaTasks(rejected)).isEmpty();
        assertThat(mediaUsage(rejected)).isZero();
        finish(rejected, "REJECTED");

        Scenario pending = propose(outputs("AUDIO"));
        expirePending(pending);
        // The recovery path does not require a browser to decide or send a continue message.
        assertThat(outcomes.recoveryCandidates(null, 100)).anySatisfy(approval ->
                assertThat(approval.id()).isEqualTo(pending.approval().id()));
        outcomes.reconcile(owner.userId(), pending.project().id(), pending.run().id(), pending.approval().id());
        assertThat(currentApproval(pending).status()).isEqualTo(AgentMediaApproval.Status.EXPIRED);
        assertThat(mediaTasks(pending)).isEmpty();
        finish(pending, "EXPIRED");

        Scenario decisionExpired = propose(outputs("IMAGE"));
        expirePending(decisionExpired);
        assertThat(decide(decisionExpired, "APPROVE", "expired-decision").path("status").asText())
                .isEqualTo("EXPIRED");
        assertThat(mediaTasks(decisionExpired)).isEmpty();
        finish(decisionExpired, "EXPIRED");

        Scenario execution = propose(outputs("VIDEO"));
        decide(execution, "APPROVE", "execution-expiry");
        jdbc.sql("update agent_media_approval set execution_deadline=now()-interval '1 hour' where id=:id")
                .param("id", execution.approval().id()).update();
        outcomes.reconcile(owner.userId(), execution.project().id(), execution.run().id(), execution.approval().id());
        assertThat(currentApproval(execution).status()).isEqualTo(AgentMediaApproval.Status.EXPIRED);
        assertThat(mediaTasks(execution)).singleElement().satisfies(task ->
                assertThat(task.status()).isEqualTo(Task.Status.CANCELED));
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isZero();
        finish(execution, "EXPIRED");
    }

    @Test
    void backendPollsThePersistedOriginalRequestAndResumesTheAgentWithoutAnotherSubmission() throws Exception {
        Scenario scenario = propose(outputs("AUDIO"));
        decide(scenario, "APPROVE", "poll-approve");
        Task lease = tasks.claimBoundMedia(MEDIA_WORKER, 1).getFirst();
        UUID requestKey = tasks.beginSubmission(lease, MEDIA_WORKER);
        String originalRequestId = "synthetic-audio-request-" + UUID.randomUUID();
        tasks.waitForProvider(lease, MEDIA_WORKER, originalRequestId, Instant.now().plusSeconds(60));
        Task waiting = tasks.get(owner.userId(), scenario.project().id(), lease.id());
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(waiting.providerRequestId()).isEqualTo(originalRequestId);
        var attempt = tasks.listProviderAttempts(owner.userId(), scenario.project().id(), lease.id()).getFirst();
        assertThat(attempt.requestKey()).isEqualTo(requestKey);
        assertThat(attempt.providerRequestId()).isEqualTo(originalRequestId);
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.WAITING_TASKS);
        assertThat(gateway.plan(scenario.run().id()).calls).hasValue(1);

        // Only backend scheduling advances the original request; the model has no polling round.
        jdbc.sql("update task set next_action_at=now()-interval '1 minute' where id=:id")
                .param("id", lease.id()).update();
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isZero();
        assertThat(mediaWorker.pollOnce(MEDIA_WORKER)).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), scenario.project().id(), lease.id());
        assertThat(completed.id()).isEqualTo(lease.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.providerRequestId()).isEqualTo(originalRequestId);
        assertThat(completed.attemptNo()).isEqualTo(1);
        assertThat(tasks.listProviderAttempts(owner.userId(), scenario.project().id(), lease.id()))
                .singleElement().satisfies(saved -> {
                    assertThat(saved.id()).isEqualTo(attempt.id());
                    assertThat(saved.requestKey()).isEqualTo(requestKey);
                    assertThat(saved.providerRequestId()).isEqualTo(originalRequestId);
                });
        assertThat(jdbc.sql("select count(*) from call_log where task_id=:id and operation='POLL' "
                        + "and status='SUCCEEDED' and mock=true and provider_request_id=:request")
                .param("id", lease.id()).param("request", originalRequestId).query(Long.class).single()).isEqualTo(1);
        assertThat(mediaWorker.pollOnce(MEDIA_WORKER)).isZero();
        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.SUCCEEDED);
        finish(scenario, "SUCCEEDED");
        assertThat(gateway.plan(scenario.run().id()).reply.at("/mediaApproval/tasks/0/artifactVersionId").asText())
                .isEqualTo(completed.output().path("artifactVersionId").asText());
    }

    @Test
    void failedAndUnknownTasksContinueWithExplicitOutcomesAndKeepUnknownAttemptImmutable() throws Exception {
        Scenario failed = propose(outputs("IMAGE", "AUDIO"));
        decide(failed, "APPROVE", "failed-approve");
        Task lease = tasks.claimBoundMedia(MEDIA_WORKER, 1).getFirst();
        tasks.fail(lease, MEDIA_WORKER, "SYNTHETIC_MEDIA_FAILURE");
        assertThat(currentRun(failed).status()).isEqualTo(AgentRun.Status.WAITING_TASKS);
        assertThat(currentApproval(failed).status()).isEqualTo(AgentMediaApproval.Status.APPROVED);
        assertThat(turnCount(failed)).isEqualTo(1);
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(currentApproval(failed).result().path("tasks")).hasSize(2).anySatisfy(result ->
                assertThat(result.path("errorCode").asText()).isEqualTo("SYNTHETIC_MEDIA_FAILURE"))
                .anySatisfy(result -> assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED"));
        replayOutcome(failed);
        finish(failed, "FAILED");

        Scenario unknown = propose(outputs("AUDIO"));
        decide(unknown, "APPROVE", "unknown-approve");
        Task uncertain = tasks.claimBoundMedia(MEDIA_WORKER, 1).getFirst();
        tasks.beginSubmission(uncertain, MEDIA_WORKER);
        assertThat(tasks.markSubmissionUnknown(uncertain, MEDIA_WORKER, "SYNTHETIC_RESULT_UNKNOWN")).isTrue();
        assertThat(currentApproval(unknown).result().at("/tasks/0/status").asText()).isEqualTo("UNKNOWN");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isZero();
        assertThat(mediaTasks(unknown)).singleElement().satisfies(task -> {
            assertThat(task.id()).isEqualTo(uncertain.id());
            assertThat(task.status()).isEqualTo(Task.Status.UNKNOWN);
            assertThat(task.attemptNo()).isEqualTo(1);
        });
        finish(unknown, "FAILED");
        assertThat(gateway.plan(unknown.run().id()).reply.at("/mediaApproval/tasks/0/possibleExternalCost").asBoolean())
                .isTrue();
    }

    @Test
    void recoveryCompensatesForAMissedTaskNotificationWithoutDuplicatingTheNextTurn() throws Exception {
        Scenario scenario = propose(outputs("IMAGE"));
        decide(scenario, "APPROVE", "recover-approve");
        UUID taskId = mediaTasks(scenario).getFirst().id();
        // Standalone SQL simulates a durable resolved state whose in-memory signal was lost.
        jdbc.sql("update task set status='FAILED',error_code='SYNTHETIC_RESTART_FAILURE',completed_at=now() where id=:id")
                .param("id", taskId).update();
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.WAITING_TASKS);
        assertThat(outcomes.recoveryCandidates(null, 100)).anySatisfy(approval ->
                assertThat(approval.id()).isEqualTo(scenario.approval().id()));
        outcomes.reconcile(owner.userId(), scenario.project().id(), scenario.run().id(), scenario.approval().id());
        replayOutcome(scenario);
        assertThat(jdbc.sql("select notification_pending from agent_media_approval where id=:id")
                .param("id", scenario.approval().id()).query(Boolean.class).single()).isFalse();
        finish(scenario, "FAILED");
    }

    @Test
    void cancelKeepsLateArchivedMediaWithoutSelectingItOrResumingTheModel() throws Exception {
        Scenario queued = propose(outputs("IMAGE"));
        decide(queued, "APPROVE", "cancel-queued-approve");
        UUID queuedTask = mediaTasks(queued).getFirst().id();
        runs.cancel(owner.userId(), queued.project().id(), queued.run().id());
        assertThat(mediaTasks(queued)).singleElement().satisfies(task ->
                assertThat(task.status()).isEqualTo(Task.Status.CANCELED));
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id=:id and entry_type='RELEASE'")
                .param("id", queuedTask).query(Long.class).single()).isEqualTo(1);
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isZero();
        assertThat(turnCount(queued)).isEqualTo(1);
        assertThat(gateway.plan(queued.run().id()).calls).hasValue(1);

        Scenario scenario = propose(outputs("IMAGE"));
        decide(scenario, "APPROVE", "cancel-approve");
        Task lease = tasks.claimBoundMedia(MEDIA_WORKER, 1).getFirst();
        UUID requestKey = tasks.beginSubmission(lease, MEDIA_WORKER);
        Submission generated = mockImages.submit(new AttemptContext(lease,
                tasks.mediaBinding(lease).orElseThrow(), owner.userId(), requestKey.toString(), null));
        assertThat(generated).isInstanceOf(Submission.CompletedArtifact.class);
        JsonNode content = ((Submission.CompletedArtifact) generated).content();
        runs.cancel(owner.userId(), scenario.project().id(), scenario.run().id());
        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.CANCELED);
        tasks.succeedWithArtifact(lease, MEDIA_WORKER, content);
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.CANCELED);
        var output = scenario.approval().outputs().getFirst();
        assertThat(canvas.listMediaVersions(owner.userId(), scenario.project().id(), output.canvasItemId())).hasSize(1);
        assertThat(canvas.list(owner.userId(), scenario.project().id())).anySatisfy(view -> {
            assertThat(view.item().id()).isEqualTo(output.canvasItemId());
            assertThat(view.item().selectedVersionId()).isNull();
        });
        assertThat(turnCount(scenario)).isEqualTo(1);
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isZero();
        assertThat(gateway.plan(scenario.run().id()).calls).hasValue(1);
    }

    @Test
    void draftOrCapabilityDriftRejectsApprovalBeforeAnyMediaTaskIsCreated() throws Exception {
        Scenario draftChanged = propose(outputs("IMAGE"));
        var output = draftChanged.approval().outputs().getFirst();
        var draft = drafts.get(owner.userId(), draftChanged.project().id(), output.canvasItemId());
        drafts.save(owner.userId(), draftChanged.project().id(), output.canvasItemId(), draft.version(),
                "Human changed the prompt", draft.parameters(), null, draft.capabilityId(), null, List.of(), List.of(), null);
        mvc.perform(post(decisionPath(draftChanged)).with(auth).with(csrf())
                        .header("Idempotency-Key", "draft-drift").contentType("application/json")
                        .content(decision("APPROVE"))).andExpect(status().isConflict());
        assertThat(mediaTasks(draftChanged)).isEmpty();
        decide(draftChanged, "REJECT", "draft-reject");
        finish(draftChanged, "REJECTED");

        var connection = capabilities.createConnection("Synthetic approval Mock", null);
        var capability = capabilities.publishCapability(connection.id(), "Synthetic image", "MOCK_IMAGE");
        ObjectNode request = outputs("IMAGE");
        ((ObjectNode) request.path("outputs").get(0)).put("capabilityId", capability.id().toString());
        Scenario capabilityChanged = propose(request);
        capabilities.updateCapability(connection.id(), capability.id(), capability.version(),
                capability.name(), false, "MOCK_IMAGE");
        mvc.perform(post(decisionPath(capabilityChanged)).with(auth).with(csrf())
                        .header("Idempotency-Key", "capability-drift").contentType("application/json")
                        .content(decision("APPROVE"))).andExpect(status().isConflict());
        assertThat(mediaTasks(capabilityChanged)).isEmpty();
        assertThat(mediaUsage(capabilityChanged)).isZero();
        decide(capabilityChanged, "REJECT", "capability-reject");
        finish(capabilityChanged, "REJECTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "VIDEO", "AUDIO"})
    void rejectionNeverWithdrawsAnExistingCanvasDestination(String kind) throws Exception {
        Scenario scenario = proposeExisting(kind, false);
        UUID destination = scenario.approval().outputs().getFirst().canvasItemId();
        assertThat(canvas.list(owner.userId(), scenario.project().id())).hasSize(1);
        assertThat(mediaTasks(scenario)).isEmpty();

        decide(scenario, "REJECT", "reject-existing");

        assertThat(canvas.list(owner.userId(), scenario.project().id())).singleElement()
                .satisfies(entry -> assertThat(entry.item().id()).isEqualTo(destination));
        assertThat(drafts.get(owner.userId(), scenario.project().id(), destination).prompt())
                .isEqualTo("Create a labelled synthetic sample");
        assertThat(mediaTasks(scenario)).isEmpty();
        finish(scenario, "REJECTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "VIDEO", "AUDIO"})
    void approvedGenerationUsesTheExistingCanvasDestination(String kind) throws Exception {
        Scenario scenario = proposeExisting(kind, false);
        var output = scenario.approval().outputs().getFirst();
        assertThat(mediaTasks(scenario)).isEmpty();
        decide(scenario, "APPROVE", "approve-existing");
        assertThat(mediaTasks(scenario)).singleElement().satisfies(task ->
                assertThat(task.input().path("canvasItemId").asText()).isEqualTo(output.canvasItemId().toString()));
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(canvas.list(owner.userId(), scenario.project().id())).singleElement().satisfies(entry -> {
            assertThat(entry.item().id()).isEqualTo(output.canvasItemId());
            assertThat(entry.item().selectedVersionId()).isNotNull();
        });
        assertThat(canvas.listMediaVersions(owner.userId(), scenario.project().id(), output.canvasItemId())).hasSize(1);
        finish(scenario, "SUCCEEDED");
    }

    @Test
    void regeneratingAnExistingImageAppendsHistoryAndKeepsItsPreviousResult() throws Exception {
        Scenario scenario = proposeExisting("IMAGE", true);
        var output = scenario.approval().outputs().getFirst();
        UUID previous = canvas.listMediaVersions(owner.userId(), scenario.project().id(), output.canvasItemId())
                .getFirst().id();
        decide(scenario, "APPROVE", "regenerate-existing");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(canvas.list(owner.userId(), scenario.project().id())).singleElement().satisfies(entry ->
                assertThat(entry.item().selectedVersionId()).isNotEqualTo(previous));
        assertThat(canvas.listMediaVersions(owner.userId(), scenario.project().id(), output.canvasItemId()))
                .hasSize(2).anySatisfy(version -> assertThat(version.id()).isEqualTo(previous));
        var selected = canvas.list(owner.userId(), scenario.project().id()).getFirst().item();
        UUID generated = selected.selectedVersionId();
        assertThat(artifacts.requireCanvasVersion(owner.userId(), scenario.project().id(), generated).id()).isEqualTo(generated);
        assertThatThrownBy(() -> artifacts.requireCanvasVersion(owner.userId(), scenario.project().id(), previous))
                .isInstanceOfSatisfying(ApiProblemException.class, failure -> assertThat(failure.code()).isEqualTo("INPUT_SCOPE_DENIED"));
        canvas.selectMediaVersion(owner.userId(), scenario.project().id(), selected.id(), previous, selected.version());
        assertThat(artifacts.requireCanvasVersion(owner.userId(), scenario.project().id(), previous).id()).isEqualTo(previous);
        assertThatThrownBy(() -> artifacts.requireCanvasVersion(owner.userId(), scenario.project().id(), generated))
                .isInstanceOfSatisfying(ApiProblemException.class, failure -> assertThat(failure.code()).isEqualTo("INPUT_SCOPE_DENIED"));
        finish(scenario, "SUCCEEDED");
    }

    @Test
    void existingImageCanReferenceItsOwnSelectedResultWithoutCreatingASelfConnection() throws Exception {
        Scenario seeded = proposeExisting("IMAGE", true);
        UUID targetId = seeded.approval().outputs().getFirst().canvasItemId();
        decide(seeded, "REJECT", "finish-seeded-proposal");
        finish(seeded, "REJECTED");
        var before = canvas.list(owner.userId(), seeded.project().id()).getFirst().item();
        UUID previous = before.selectedVersionId();
        var draft = drafts.get(owner.userId(), seeded.project().id(), targetId);
        AgentRun run = runs.create(owner.userId(), seeded.project().id(), seeded.run().agentInstanceId(),
                "Regenerate using this result as the reference", "self-reference-" + UUID.randomUUID()).run();
        ObjectNode request = outputs("IMAGE");
        ObjectNode output = (ObjectNode) request.path("outputs").get(0);
        output.put("canvasItemId", targetId.toString()).put("expectedItemVersion", before.version())
                .put("expectedDraftVersion", draft.version());
        output.putArray("mediaInputs").addObject().put("versionId", previous.toString()).put("role", "REFERENCE");
        gateway.prepare(run.id(), request.toString());
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        Scenario scenario = new Scenario(seeded.project(), run, approvals.list(owner.userId(), seeded.project().id(), run.id()).getFirst());
        assertThat(scenario.approval().outputs().getFirst().canvasItemId()).isEqualTo(targetId);
        assertThat(mediaTasks(scenario)).isEmpty();
        var line = connections.list(owner.userId(), seeded.project().id()).getFirst();
        assertThat(line.targetCanvasItemId()).isEqualTo(targetId);
        assertThat(line.sourceCanvasItemId()).isNotEqualTo(targetId);
        assertThat(line.sourceArtifactVersionId()).isEqualTo(previous);
        assertThat(canvas.list(owner.userId(), seeded.project().id())).hasSize(2);
        assertThat(canvas.listMediaVersions(owner.userId(), seeded.project().id(), targetId)).hasSize(1);
        decide(scenario, "APPROVE", "approve-self-reference");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(canvas.listMediaVersions(owner.userId(), seeded.project().id(), targetId)).hasSize(2)
                .anySatisfy(version -> assertThat(version.id()).isEqualTo(previous));
        assertThat(canvas.list(owner.userId(), seeded.project().id()).stream()
                .filter(entry -> entry.item().id().equals(line.sourceCanvasItemId())))
                .singleElement().satisfies(entry -> assertThat(entry.item().selectedVersionId()).isEqualTo(previous));
        finish(scenario, "SUCCEEDED");
    }

    @Test
    void lockingAnExistingDestinationInvalidatesApprovalWithoutCreatingATask() throws Exception {
        Scenario scenario = proposeExisting("IMAGE", false);
        var item = canvas.list(owner.userId(), scenario.project().id()).getFirst().item();
        canvas.apply(owner.userId(), scenario.project().id(),
                List.of(new CanvasService.SetLocked(item.id(), item.version(), true)));
        mvc.perform(post(decisionPath(scenario)).with(auth).with(csrf())
                        .header("Idempotency-Key", "existing-target-locked").contentType("application/json")
                        .content(decision("APPROVE"))).andExpect(status().isConflict());
        assertThat(mediaTasks(scenario)).isEmpty();
        decide(scenario, "REJECT", "reject-locked-existing");
        assertThat(canvas.list(owner.userId(), scenario.project().id())).hasSize(1);
        finish(scenario, "REJECTED");
    }

    @Test
    void existingVideoProposalReplacesConnectionInputsWithoutRemovingEitherExistingNode() throws Exception {
        Scenario image = propose(outputs("IMAGE"));
        gateway.plan(image.run().id()).imageToVideo = true;
        decide(image, "APPROVE", "existing-video-source");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        ApprovalView videoApproval = approvals.list(owner.userId(), image.project().id(), image.run().id())
                .stream().filter(value -> value.status() == AgentMediaApproval.Status.PENDING).findFirst().orElseThrow();
        Scenario video = new Scenario(image.project(), image.run(), videoApproval);
        decide(video, "APPROVE", "existing-video-result");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(video).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        UUID targetId = videoApproval.outputs().getFirst().canvasItemId();
        var item = canvas.list(owner.userId(), image.project().id()).stream()
                .filter(entry -> entry.item().id().equals(targetId)).findFirst().orElseThrow().item();
        var draft = drafts.get(owner.userId(), image.project().id(), targetId);
        assertThat(draft.mediaInputs()).hasSize(1);

        AgentRun later = runs.create(owner.userId(), image.project().id(), image.run().agentInstanceId(),
                "Use text only on the existing video node", "existing-text-video-" + UUID.randomUUID()).run();
        ObjectNode request = outputs("VIDEO");
        ((ObjectNode) request.path("outputs").get(0)).put("canvasItemId", targetId.toString())
                .put("expectedItemVersion", item.version()).put("expectedDraftVersion", draft.version())
                .put("videoInputMode", "TEXT").putArray("mediaInputs");
        gateway.prepare(later.id(), mapper.writeValueAsString(request));
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(runs.get(owner.userId(), image.project().id(), later.id()).status()).isEqualTo(AgentRun.Status.WAITING_TASKS);
        var proposal = approvals.list(owner.userId(), image.project().id(), later.id()).getFirst();
        assertThat(drafts.get(owner.userId(), image.project().id(), targetId).mediaInputs()).isEmpty();
        assertThat(connections.list(owner.userId(), image.project().id()))
                .noneMatch(connection -> connection.targetCanvasItemId().equals(targetId));
        Scenario laterScenario = new Scenario(image.project(), later, proposal);
        decide(laterScenario, "REJECT", "reject-text-existing-video");
        assertThat(canvas.list(owner.userId(), image.project().id())).hasSize(2);
        assertThat(canvas.listMediaVersions(owner.userId(), image.project().id(), targetId)).hasSize(1);
        finish(laterScenario, "REJECTED");
    }

    @Test
    void linkedProposalsReturnToUnboundShotsAndArchiveWithoutSelectingAnyResult() throws Exception {
        Scenario scenario = proposeLinked("IMAGE", "IMAGE");
        Storyboard before = storyboards.get(owner.userId(), scenario.project().id());
        assertThat(before.shots()).allSatisfy(shot -> assertThat(shot.productions()).isEmpty());
        assertThat(storyboards.mediaApprovals(owner.userId(), scenario.project().id()))
                .extracting(ApprovalView::id).containsExactly(scenario.approval().id());
        var pending = mapper.readTree(mvc.perform(get("/api/v1/projects/" + scenario.project().id() + "/storyboard/media-approvals")
                .with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pending.at("/items/0/outputs/0/storyboard/shotId").asText()).isEqualTo(before.shots().getFirst().id().toString());
        assertThat(pending.at("/items/0/outputs/1/storyboard/shotId").asText()).isEqualTo(before.shots().get(1).id().toString());
        assertThat(mediaTasks(scenario)).isEmpty();
        assertThat(canvas.list(owner.userId(), scenario.project().id())).allSatisfy(card -> assertThat(card.item().selectedVersionId()).isNull());

        decide(scenario, "APPROVE", "linked-explicit-approve");
        assertThat(mediaTasks(scenario)).hasSize(2).allSatisfy(task ->
                assertThat(task.input().path(Task.REQUIRE_EXPLICIT_SELECTION_PROPERTY).asBoolean()).isTrue());
        // The input remains valid after acceptance; a later edit only affects result review.
        Storyboard removedDuringGeneration = storyboards.save(owner.userId(), scenario.project().id(), new Save(before.version(),
                List.of(shotInput(before.shots().get(1), before.shots().get(1).description(), List.of())), null));
        Storyboard edited = storyboards.save(owner.userId(), scenario.project().id(), new Save(removedDuringGeneration.version(),
                List.of(shotInput(before.shots().getFirst(), "Recreated while generating", List.of()),
                        shotInput(before.shots().get(1), before.shots().get(1).description(), List.of())), null));
        assertThat(edited.shots().getFirst().revision()).isEqualTo(2);
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        ApprovalView completed = currentApproval(scenario);
        assertThat(completed.status()).isEqualTo(AgentMediaApproval.Status.SUCCEEDED);
        for (int index = 0; index < 2; index++) {
            JsonNode outcome = completed.result().path("tasks").get(index);
            assertThat(outcome.path("outputIndex").asInt()).isEqualTo(index);
            assertThat(outcome.path("canvasItemId").asText()).isEqualTo(scenario.approval().outputs().get(index).canvasItemId().toString());
            assertThat(outcome.at("/storyboard/shotId").asText()).isEqualTo(before.shots().get(index).id().toString());
            assertThat(outcome.at("/storyboard/shotRevision").asInt()).isEqualTo(1);
            assertThat(outcome.path("selected").asBoolean()).isFalse();
        }
        assertThat(storyboards.get(owner.userId(), scenario.project().id())).isEqualTo(edited);
        assertThat(canvas.list(owner.userId(), scenario.project().id())).allSatisfy(card -> assertThat(card.item().selectedVersionId()).isNull());
        UUID version = UUID.fromString(completed.result().at("/tasks/0/artifactVersionId").asText());
        assertThat(artifacts.requireAgentReadableVersion(owner.userId(), scenario.project().id(),
                scenario.run().contextSnapshot(), scenario.run().id(), version).id()).isEqualTo(version);
        assertThatThrownBy(() -> artifacts.requireAgentReadableVersion(owner.userId(), scenario.project().id(),
                scenario.run().contextSnapshot(), UUID.randomUUID(), version)).isInstanceOf(ApiProblemException.class);
        var choice = new ProductionInput(Storyboard.Role.KEYFRAME, scenario.approval().outputs().getFirst().canvasItemId(), version, false);
        Storyboard adopted = replaceShot(scenario, 0, edited.shots().getFirst().description(), List.of(choice), false);
        assertThat(adopted.shots().getFirst().productions().getFirst().needsReview()).isTrue();
        assertThat(adopted.shots().getFirst().productions().getFirst().reviewedRevision()).isEqualTo(1);
        assertThatThrownBy(() -> storyboards.save(owner.userId(), scenario.project().id(), new Save(edited.version(),
                adopted.shots().stream().map(shot -> shotInput(shot, shot.description(), shot.productions().stream()
                        .map(value -> new ProductionInput(value.role(), value.canvasItemId(), value.selectedVersionId(), false)).toList())).toList(), null)))
                .isInstanceOfSatisfying(ApiProblemException.class, failure -> assertThat(failure.code()).isEqualTo("STORYBOARD_VERSION_CONFLICT"));
        Storyboard confirmed = replaceShot(scenario, 0, adopted.shots().getFirst().description(),
                List.of(new ProductionInput(choice.role(), choice.canvasItemId(), version, true)), true);
        assertThat(confirmed.shots().getFirst().productions().getFirst().needsReview()).isFalse();
        assertThat(confirmed.shots().getFirst().productions().getFirst().selectedVersionId()).isEqualTo(version);
        finish(scenario, "SUCCEEDED");
    }

    @Test
    void onlyTheLinkedCreativeRevisionInvalidatesApprovalAndRejectionStillWorks() throws Exception {
        Scenario scenario = proposeLinked("IMAGE");
        replaceShot(scenario, 0, "Changed linked shot before approval", List.of(), false);
        Storyboard changed = storyboards.get(owner.userId(), scenario.project().id());
        mvc.perform(post(decisionPath(scenario)).with(auth).with(csrf()).header("Idempotency-Key", "linked-stale")
                .contentType("application/json").content(decision("APPROVE"))).andExpect(status().isConflict());
        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.PENDING);
        assertThat(mediaTasks(scenario)).isEmpty();
        decide(scenario, "REJECT", "linked-stale-reject");
        assertThat(storyboards.get(owner.userId(), scenario.project().id())).isEqualTo(changed);
        assertThat(canvas.list(owner.userId(), scenario.project().id())).isEmpty();
        assertThat(storyboards.mediaApprovals(owner.userId(), scenario.project().id())).singleElement()
                .satisfies(approval -> assertThat(approval.status()).isEqualTo(AgentMediaApproval.Status.REJECTED));
        finish(scenario, "REJECTED");
    }

    @Test
    void editingAnotherShotAndRenamingTheLinkedCodeDoNotInvalidateApproval() throws Exception {
        Scenario scenario = proposeLinked("AUDIO");
        Storyboard before = storyboards.get(owner.userId(), scenario.project().id());
        var first = before.shots().getFirst();
        var renamed = new ShotInput(first.id(), "RENAMED-001", first.scene(), first.title(), first.description(), first.dialogue(),
                first.durationSeconds(), first.sourceVersionId(), first.referenceVersionIds(), List.of());
        storyboards.save(owner.userId(), scenario.project().id(), new Save(before.version(),
                List.of(renamed, shotInput(before.shots().get(1), "Unrelated rewrite", List.of())), null));
        decide(scenario, "APPROVE", "unrelated-shot-approve");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(currentApproval(scenario).result().at("/tasks/0/storyboard/shotCode").asText()).isEqualTo("SHOT-001");
        assertThat(currentApproval(scenario).result().at("/tasks/0/storyboard/role").asText()).isEqualTo("AUDIO");
        finish(scenario, "SUCCEEDED");
    }

    @Test
    void deletingTheLinkedShotBlocksApprovalWithoutLosingTheFrozenProposal() throws Exception {
        Scenario scenario = proposeLinked("VIDEO");
        Storyboard before = storyboards.get(owner.userId(), scenario.project().id());
        storyboards.save(owner.userId(), scenario.project().id(), new Save(before.version(),
                List.of(shotInput(before.shots().get(1), before.shots().get(1).description(), List.of())), null));
        mvc.perform(post(decisionPath(scenario)).with(auth).with(csrf()).header("Idempotency-Key", "removed-shot-approve")
                .contentType("application/json").content(decision("APPROVE"))).andExpect(status().isConflict());
        assertThat(currentApproval(scenario).outputs().getFirst().storyboard().shotId()).isEqualTo(before.shots().getFirst().id());
        assertThat(mediaTasks(scenario)).isEmpty();
        decide(scenario, "REJECT", "removed-shot-reject");
        finish(scenario, "REJECTED");
    }

    @Test
    void linkedUnknownOutcomePreservesItsExactShotAndNeverCreatesAnotherAttempt() throws Exception {
        Scenario scenario = proposeLinked("AUDIO");
        decide(scenario, "APPROVE", "linked-unknown-approve");
        Task uncertain = tasks.claimBoundMedia(MEDIA_WORKER, 1).getFirst();
        tasks.beginSubmission(uncertain, MEDIA_WORKER);
        assertThat(tasks.markSubmissionUnknown(uncertain, MEDIA_WORKER, "SYNTHETIC_LINKED_UNKNOWN")).isTrue();
        JsonNode result = currentApproval(scenario).result().at("/tasks/0");
        assertThat(result.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.path("outputIndex").asInt()).isZero();
        assertThat(result.path("canvasItemId").asText()).isEqualTo(scenario.approval().outputs().getFirst().canvasItemId().toString());
        assertThat(result.at("/storyboard/shotId").asText()).isEqualTo(scenario.approval().outputs().getFirst().storyboard().shotId().toString());
        assertThat(result.path("possibleExternalCost").asBoolean()).isTrue();
        outcomes.reconcile(owner.userId(), scenario.project().id(), scenario.run().id(), scenario.approval().id());
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isZero();
        assertThat(mediaTasks(scenario)).singleElement().satisfies(task -> assertThat(task.attemptNo()).isEqualTo(1));
        assertThat(storyboards.get(owner.userId(), scenario.project().id()).shots()).allSatisfy(shot -> assertThat(shot.productions()).isEmpty());
        finish(scenario, "FAILED");
    }

    @Test
    void partiallyCompletedBatchAndAdditionalOutputsKeepFrozenReviewProvenance() throws Exception {
        Scenario scenario = proposeLinked("IMAGE", "AUDIO");
        decide(scenario, "APPROVE", "linked-extra-approve");
        Storyboard edited = replaceShot(scenario, 0, "Changed while batch is active", List.of(), false);
        Task lease = tasks.claimBoundMedia(MEDIA_WORKER, 1).getFirst();
        assertThat(lease.kind()).isEqualTo(Task.Kind.IMAGE_GENERATION);
        tasks.beginSubmission(lease, MEDIA_WORKER);
        ObjectNode primary = linkedImageContent(scenario, lease, "primary");
        ObjectNode extra = linkedImageContent(scenario, lease, "extra");
        tasks.succeedWithArtifacts(lease, MEDIA_WORKER, List.of(
                new TaskService.ArchivedProviderResult(0, Artifact.Kind.IMAGE, true, primary),
                new TaskService.ArchivedProviderResult(1, Artifact.Kind.IMAGE, false, extra)), null);
        Task completed = tasks.get(owner.userId(), scenario.project().id(), lease.id());
        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.APPROVED);
        assertThat(currentApproval(scenario).result()).isNull();
        assertThat(currentApproval(scenario).archivedResults()).hasSize(1);
        JsonNode extraResult = completed.output().at("/additionalResults/0");
        UUID extraNode = UUID.fromString(extraResult.path("canvasItemId").asText());
        UUID extraVersion = UUID.fromString(extraResult.path("artifactVersionId").asText());
        assertThat(extraResult.path("selected").asBoolean()).isFalse();
        assertThat(canvas.list(owner.userId(), scenario.project().id())).allSatisfy(card -> assertThat(card.item().selectedVersionId()).isNull());
        Storyboard adopted = replaceShot(scenario, 0, edited.shots().getFirst().description(),
                List.of(new ProductionInput(Storyboard.Role.KEYFRAME, extraNode, extraVersion, false)), false);
        assertThat(adopted.shots().getFirst().productions().getFirst().needsReview()).isTrue();
        assertThat(adopted.shots().getFirst().productions().getFirst().reviewedRevision()).isEqualTo(1);
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(approvals.storyboardResultRevision(owner.userId(), scenario.project().id(), edited.shots().getFirst().id(), extraNode, extraVersion)).contains(1);
        finish(scenario, "SUCCEEDED");
    }

    @Test
    void proposalRejectsForgedCrossProjectMissingAndStaleShotLinksBeforeCreatingMedia() {
        Project project = projects.create(owner.userId(), "Invalid linked proposal", Project.AspectRatio.LANDSCAPE_16_9);
        Storyboard board = seedLinkedBoard(project);
        Project other = projects.create(owner.userId(), "Other linked project", Project.AspectRatio.LANDSCAPE_16_9);
        UUID foreignShot = seedLinkedBoard(other).shots().getFirst().id();
        var agent = configureDirector(agents, owner.userId(), project.id(), "Synthetic linked director", "Propose linked media");
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(), "Invalid links", "linked-invalid-" + UUID.randomUUID()).run();
        AgentRun run = runs.transition(owner.userId(), project.id(), queued.id(), queued.version(), AgentRun.Status.RUNNING);
        var trusted = new dev.agenvas.llm.application.TrustedToolContext(owner.userId(), project.id(), run.id());
        List<ObjectNode> invalid = new java.util.ArrayList<>();
        ObjectNode valid = (ObjectNode) outputs("IMAGE").path("outputs").get(0);
        valid.put("shotId", board.shots().getFirst().id().toString()).put("expectedShotRevision", 1);
        invalid.add(valid.deepCopy().put("shotId", foreignShot.toString()));
        invalid.add(valid.deepCopy().put("expectedShotRevision", 2));
        invalid.add(valid.deepCopy().put("expectedShotRevision", 0));
        invalid.add(valid.deepCopy().put("expectedShotRevision", 1.5));
        invalid.add(valid.deepCopy().put("shotCode", "FORGED-CODE"));
        invalid.add(valid.deepCopy().put("storyboard", "forged-link"));
        ObjectNode missingRevision = valid.deepCopy(); missingRevision.remove("expectedShotRevision"); invalid.add(missingRevision);
        ObjectNode missingShot = valid.deepCopy(); missingShot.remove("shotId"); invalid.add(missingShot);
        for (ObjectNode output : invalid) {
            ObjectNode request = mapper.createObjectNode(); request.putArray("outputs").add(output);
            assertThatThrownBy(() -> approvals.propose(trusted, run, UUID.randomUUID(), 0, "invalid-" + UUID.randomUUID(), request.toString()))
                    .isInstanceOfSatisfying(ApiProblemException.class, failure -> assertThat(failure.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
            assertThat(approvals.list(owner.userId(), project.id(), run.id())).isEmpty();
            assertThat(canvas.list(owner.userId(), project.id())).isEmpty();
            assertThat(storyboards.get(owner.userId(), project.id())).isEqualTo(board);
        }
        runs.cancel(owner.userId(), project.id(), run.id());
    }

    @Test
    void deleteRecreateWithTheSameShotIdCannotReuseAnOldApproval() throws Exception {
        Scenario scenario = proposeLinked("IMAGE");
        Storyboard before = storyboards.get(owner.userId(), scenario.project().id());
        storyboards.save(owner.userId(), scenario.project().id(), new Save(before.version(),
                List.of(shotInput(before.shots().get(1), before.shots().get(1).description(), List.of())), null));
        Storyboard removed = storyboards.get(owner.userId(), scenario.project().id());
        Storyboard recreated = storyboards.save(owner.userId(), scenario.project().id(), new Save(removed.version(),
                List.of(shotInput(before.shots().getFirst(), "Entirely different re-created shot", List.of()),
                        shotInput(before.shots().get(1), before.shots().get(1).description(), List.of())), null));
        assertThat(recreated.shots().getFirst().revision()).isEqualTo(2);
        mvc.perform(post(decisionPath(scenario)).with(auth).with(csrf()).header("Idempotency-Key", "aba-shot-approve")
                .contentType("application/json").content(decision("APPROVE"))).andExpect(status().isConflict());
        assertThat(mediaTasks(scenario)).isEmpty();
        decide(scenario, "REJECT", "aba-shot-reject");
        finish(scenario, "REJECTED");
    }

    @Test
    void lateArchivedResultReturnsToTheWorkbenchWithoutChangingTheFinalApprovalReply() throws Exception {
        Scenario scenario = proposeLinked("IMAGE");
        decide(scenario, "APPROVE", "linked-late-approve");
        Task lease = tasks.claimBoundMedia(MEDIA_WORKER, 1).getFirst();
        tasks.beginSubmission(lease, MEDIA_WORKER);
        runs.cancel(owner.userId(), scenario.project().id(), scenario.run().id());
        JsonNode frozenResult = currentApproval(scenario).result().deepCopy();
        assertThat(currentApproval(scenario).status()).isEqualTo(AgentMediaApproval.Status.CANCELED);
        assertThat(currentApproval(scenario).archivedResults()).isEmpty();
        tasks.succeedWithArtifacts(lease, MEDIA_WORKER, List.of(new TaskService.ArchivedProviderResult(0,
                Artifact.Kind.IMAGE, true, linkedImageContent(scenario, lease, "late"))), null);
        ApprovalView late = currentApproval(scenario);
        assertThat(late.result()).isEqualTo(frozenResult);
        assertThat(late.status()).isEqualTo(AgentMediaApproval.Status.CANCELED);
        assertThat(late.archivedResults()).singleElement().satisfies(result -> {
            assertThat(result.path("artifactVersionId").isTextual()).isTrue();
            assertThat(result.path("selected").asBoolean()).isFalse();
            assertThat(result.path("outputIndex").asInt()).isZero();
            assertThat(result.at("/storyboard/shotId").asText()).isEqualTo(scenario.approval().outputs().getFirst().storyboard().shotId().toString());
        });
        assertThat(storyboards.mediaApprovals(owner.userId(), scenario.project().id())).singleElement()
                .satisfies(approval -> assertThat(approval.archivedResults()).hasSize(1));
        assertThat(storyboards.get(owner.userId(), scenario.project().id()).shots()).allSatisfy(shot -> assertThat(shot.productions()).isEmpty());
        assertThat(canvas.list(owner.userId(), scenario.project().id())).allSatisfy(card -> assertThat(card.item().selectedVersionId()).isNull());
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isZero();
        assertThat(turnCount(scenario)).isEqualTo(1);
    }

    private ObjectNode linkedImageContent(Scenario scenario, Task lease, String prompt) {
        ObjectNode content = mapper.createObjectNode().put("assetId",
                dev.agenvas.testing.ImageAssetFixture.archive(assets, owner.userId(), scenario.project().id()).toString())
                .put("prompt", prompt).put("workflowVersion", "synthetic-v1").put("sourceTaskId", lease.id().toString());
        content.putObject("parameters");
        return content;
    }

    private Scenario proposeLinked(String... kinds) {
        Project project = projects.create(owner.userId(), "Synthetic linked storyboard", Project.AspectRatio.LANDSCAPE_16_9);
        Storyboard board = seedLinkedBoard(project);
        var agent = configureDirector(agents, owner.userId(), project.id(), "Synthetic linked director", "Create only approved media");
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(), "Create linked media", "linked-" + UUID.randomUUID()).run();
        ObjectNode request = outputs(kinds);
        for (int index = 0; index < kinds.length; index++) {
            ((ObjectNode) request.path("outputs").get(index)).put("shotId", board.shots().get(index).id().toString()).put("expectedShotRevision", 1);
        }
        gateway.prepare(run.id(), request.toString());
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        return new Scenario(project, run, approvals.list(owner.userId(), project.id(), run.id()).getFirst());
    }

    private Storyboard seedLinkedBoard(Project project) {
        return storyboards.save(owner.userId(), project.id(), new Save(0, List.of(
                new ShotInput(UUID.randomUUID(), "SHOT-001", "SC-1", "First", "First shot", "", BigDecimal.ONE, null, List.of(), List.of()),
                new ShotInput(UUID.randomUUID(), "SHOT-002", "SC-1", "Second", "Second shot", "", BigDecimal.ONE, null, List.of(), List.of())), null));
    }

    private Storyboard replaceShot(Scenario scenario, int index, String description, List<ProductionInput> productions, boolean confirming) {
        Storyboard board = storyboards.get(owner.userId(), scenario.project().id());
        List<ShotInput> inputs = new java.util.ArrayList<>();
        for (int position = 0; position < board.shots().size(); position++) {
            Storyboard.Shot shot = board.shots().get(position);
            inputs.add(shotInput(shot, position == index ? description : shot.description(), position == index ? productions : shot.productions().stream()
                    .map(value -> new ProductionInput(value.role(), value.canvasItemId(), value.selectedVersionId(), false)).toList()));
        }
        return storyboards.save(owner.userId(), scenario.project().id(), new Save(board.version(), List.copyOf(inputs), null));
    }

    private ShotInput shotInput(Storyboard.Shot shot, String description, List<ProductionInput> productions) {
        return new ShotInput(shot.id(), shot.code(), shot.scene(), shot.title(), description, shot.dialogue(), shot.durationSeconds(),
                shot.sourceVersionId(), shot.referenceVersionIds(), productions);
    }

    private Scenario proposeExisting(String kind, boolean seedResult) {
        Project project = projects.create(owner.userId(), "Synthetic existing destination", Project.AspectRatio.LANDSCAPE_16_9);
        var artifact = artifacts.create(owner.userId(), project.id(), Artifact.Kind.valueOf(kind), "Existing sample", null);
        UUID itemId = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(itemId, artifact.artifact().id(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(640), BigDecimal.valueOf(360), 0, null, false)));
        var draft = drafts.get(owner.userId(), project.id(), itemId);
        if (seedResult) {
            draft = drafts.save(owner.userId(), project.id(), itemId, draft.version(), "First synthetic image",
                    mapper.createObjectNode(), null, null, null, List.of(), List.of(), null);
            Task first = directMediaTasks.run(owner.userId(), project.id(), artifact.artifact().id(), itemId,
                    draft.version(), "seed-existing-" + UUID.randomUUID());
            assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
            assertThat(tasks.get(owner.userId(), project.id(), first.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        }
        var agent = configureDirector(agents, owner.userId(), project.id(), "Synthetic canvas director", "Work with the current canvas");
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(), "Generate in the existing node",
                "synthetic-run-" + UUID.randomUUID()).run();
        // Recreate an empty destination after acceptance; Runs use the live project canvas.
        if (!seedResult) {
            var before = canvas.list(owner.userId(), project.id()).getFirst().item();
            canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.Remove(before.id(), before.version())));
            itemId = UUID.randomUUID();
            canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(itemId, artifact.artifact().id(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(640), BigDecimal.valueOf(360), 0, null, false)));
            draft = drafts.get(owner.userId(), project.id(), itemId);
        }
        var item = canvas.list(owner.userId(), project.id()).getFirst().item();
        ObjectNode request = outputs(kind);
        ((ObjectNode) request.path("outputs").get(0)).put("canvasItemId", itemId.toString())
                .put("expectedItemVersion", item.version()).put("expectedDraftVersion", draft.version());
        gateway.prepare(run.id(), mapper.writeValueAsString(request));
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.WAITING_TASKS);
        ApprovalView approval = approvals.list(owner.userId(), project.id(), run.id()).getFirst();
        assertThat(approval.outputs().getFirst().canvasItemId()).isEqualTo(itemId);
        return new Scenario(project, run, approval);
    }

    @Test
    void pendingVideoCanSwitchProviderAndResolutionThenRequiresTheRevisedApprovalVersion() throws Exception {
        Scenario scenario = propose(outputs("VIDEO", "IMAGE"));
        var initial = scenario.approval();
        var target = initial.outputs().getFirst();
        var connection = capabilities.createConnection("revision-" + UUID.randomUUID(), "Synthetic H3 revision", "MINIMAX",
                "https://api.minimax.cn", "synthetic-revision-key");
        var settings = mapper.readTree("""
                {"defaultDurationSeconds":5,"defaultParameters":{"videoResolution":"768p"},
                 "pricingByResolution":{"768p":{"amount":"1","currency":"CNY","unit":"SECOND"},
                 "1440p":{"amount":"2","currency":"CNY","unit":"SECOND"}}}
                """);
        var capability = capabilities.publishCapability(connection.id(), "Synthetic H3", "MINIMAX_H3", settings);
        ObjectNode revision = mapper.createObjectNode().put("expectedVersion", 0).put("outputIndex", 0)
                .put("capabilityId", capability.id().toString()).put("durationSeconds", 5).put("videoInputMode", "TEXT");
        revision.putObject("parameters").put("aspectRatio", "16:9").put("videoResolution", "1440p");
        String path = base(scenario) + "/" + initial.id();
        mvc.perform(put(path).with(auth).contentType("application/json").content(revision.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).with(auth).with(csrf()).contentType("application/json").content(revision.toString()))
                .andExpect(status().isOk());
        var revised = currentApproval(scenario);
        assertThat(revised.version()).isEqualTo(1);
        assertThat(revised.status()).isEqualTo(AgentMediaApproval.Status.PENDING);
        assertThat(revised.expiresAt()).isEqualTo(initial.expiresAt());
        assertThat(revised.outputs().get(1)).isEqualTo(initial.outputs().get(1));
        JsonNode preview = revised.outputs().getFirst().preview();
        assertThat(preview.path("capabilityId").asText()).isEqualTo(capability.id().toString());
        assertThat(preview.at("/parameters/videoResolution").asText()).isEqualTo("1440p");
        assertThat(preview.at("/mediaPricing/amount").asText()).isEqualTo("2");
        assertThat(preview.toString()).doesNotContain("synthetic-revision-key", "api.minimax.cn");
        assertThat(mediaTasks(scenario)).isEmpty();
        assertThat(mediaUsage(scenario)).isZero();
        mvc.perform(post(decisionPath(scenario)).with(auth).with(csrf()).header("Idempotency-Key", "old-revision")
                .contentType("application/json").content(decision("APPROVE"))).andExpect(status().isConflict());
        assertThat(mediaTasks(scenario)).isEmpty();
        mvc.perform(post(decisionPath(scenario)).with(auth).with(csrf()).header("Idempotency-Key", "new-revision")
                .contentType("application/json").content("{\"expectedVersion\":1,\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());
        assertThat(mediaTasks(scenario)).hasSize(2).anySatisfy(task -> {
            assertThat(task.kind()).isEqualTo(Task.Kind.VIDEO_GENERATION);
            assertThat(task.input().at("/mediaInput/capabilityId").asText()).isEqualTo(capability.id().toString());
            assertThat(task.input().at("/mediaInput/parameters/videoResolution").asText()).isEqualTo("1440p");
        });
        revision.put("expectedVersion", 2);
        mvc.perform(put(path).with(auth).with(csrf()).contentType("application/json").content(revision.toString()))
                .andExpect(status().isConflict());
        runs.cancel(owner.userId(), scenario.project().id(), scenario.run().id());
    }

    @Test
    void invalidVideoRevisionRollsBackTheDraftAndApprovalAndCannotRefreshAnExpiredBatch() throws Exception {
        Scenario scenario = propose(outputs("VIDEO"));
        var output = scenario.approval().outputs().getFirst();
        var before = drafts.get(owner.userId(), scenario.project().id(), output.canvasItemId());
        // Mock video accepts 1..30s: preflight rejects an unsupported duration after draft save.
        ObjectNode revision = mapper.createObjectNode().put("expectedVersion", 0).put("outputIndex", 0)
                .put("capabilityId", before.capabilityId().toString()).put("durationSeconds", 60).put("videoInputMode", "TEXT");
        revision.putObject("parameters").put("aspectRatio", "16:9");
        String path = base(scenario) + "/" + scenario.approval().id();
        mvc.perform(put(path).with(auth).with(csrf()).contentType("application/json").content(revision.toString()))
                .andExpect(status().is4xxClientError());
        assertThat(drafts.get(owner.userId(), scenario.project().id(), output.canvasItemId())).isEqualTo(before);
        assertThat(currentApproval(scenario)).isEqualTo(scenario.approval());
        assertThat(mediaTasks(scenario)).isEmpty();
        assertThat(mediaUsage(scenario)).isZero();
        expirePending(scenario);
        revision.put("durationSeconds", 1);
        mvc.perform(put(path).with(auth).with(csrf()).contentType("application/json").content(revision.toString()))
                .andExpect(status().isConflict());
        runs.cancel(owner.userId(), scenario.project().id(), scenario.run().id());
    }

    @Test
    void concurrentVideoRevisionsAcceptExactlyOneWriterAndKeepTasksUnsubmitted() throws Exception {
        Scenario scenario = propose(outputs("VIDEO"));
        var output = scenario.approval().outputs().getFirst();
        UUID capabilityId = UUID.fromString(output.preview().path("capabilityId").asText());
        var start = new CountDownLatch(1);
        Callable<Boolean> revise = () -> {
            start.await(CONCURRENT_WAIT_SECONDS, TimeUnit.SECONDS);
            try {
                approvals.revise(owner.userId(), scenario.project().id(), scenario.run().id(), scenario.approval().id(),
                        0, 0, capabilityId, mapper.createObjectNode().put("aspectRatio", "16:9"), 1, MediaDraft.VideoInputMode.TEXT);
                return true;
            } catch (ApiProblemException conflict) {
                assertThat(conflict.code()).isEqualTo("AGENT_MEDIA_APPROVAL_CONFLICT"); return false;
            }
        };
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(revise); var second = pool.submit(revise); start.countDown();
            assertThat(List.of(first.get(CONCURRENT_WAIT_SECONDS, TimeUnit.SECONDS), second.get(CONCURRENT_WAIT_SECONDS, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(currentApproval(scenario).version()).isEqualTo(1);
        assertThat(mediaTasks(scenario)).isEmpty();
        runs.cancel(owner.userId(), scenario.project().id(), scenario.run().id());
    }

    private Scenario propose(ObjectNode outputs) {
        Project project = projects.create(owner.userId(), "Synthetic approval project", Project.AspectRatio.LANDSCAPE_16_9);
        var agent = configureDirector(agents, owner.userId(), project.id(), "Synthetic Creator", "Create approved media");
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(), "Create the requested media",
                "synthetic-run-" + UUID.randomUUID()).run();
        gateway.prepare(run.id(), mapper.writeValueAsString(outputs));
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.WAITING_TASKS);
        ApprovalView approval = approvals.list(owner.userId(), project.id(), run.id()).getFirst();
        assertThat(approval.status()).isEqualTo(AgentMediaApproval.Status.PENDING);
        return new Scenario(project, run, approval);
    }

    private ObjectNode outputs(String... kinds) {
        ObjectNode request = mapper.createObjectNode();
        var outputs = request.putArray("outputs");
        for (String kind : kinds) {
            var output = outputs.addObject().put("kind", kind).put("title", "Synthetic " + kind)
                    .put("prompt", "Create a labelled synthetic sample");
            output.putObject("parameters");
            if ("VIDEO".equals(kind)) output.put("durationSeconds", 1);
        }
        return request;
    }

    private JsonNode decide(Scenario scenario, String decision, String key) throws Exception {
        return mapper.readTree(mvc.perform(post(decisionPath(scenario)).with(auth).with(csrf())
                        .header("Idempotency-Key", key).contentType("application/json").content(decision(decision)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String decision(String value) {
        return mapper.createObjectNode().put("expectedVersion", 0).put("decision", value).toString();
    }

    private String base(Scenario scenario) {
        return "/api/v1/projects/" + scenario.project().id() + "/runs/" + scenario.run().id() + "/media-approvals";
    }

    private String decisionPath(Scenario scenario) { return base(scenario) + "/" + scenario.approval().id() + "/decision"; }
    private AgentRun currentRun(Scenario scenario) { return runs.get(owner.userId(), scenario.project().id(), scenario.run().id()); }
    private ApprovalView currentApproval(Scenario scenario) {
        return approvals.get(owner.userId(), scenario.project().id(), scenario.run().id(), scenario.approval().id());
    }
    private List<Task> mediaTasks(Scenario scenario) {
        return tasks.listByRun(owner.userId(), scenario.project().id(), scenario.run().id()).stream()
                .filter(task -> task.kind() != Task.Kind.AGENT_TURN).toList();
    }
    private long turnCount(Scenario scenario) {
        return jdbc.sql("select count(*) from task where run_id=:run and kind='AGENT_TURN'")
                .param("run", scenario.run().id()).query(Long.class).single();
    }
    private long mediaUsage(Scenario scenario) {
        return jdbc.sql("select count(*) from usage_ledger where project_id=:project and "
                        + "(coalesce((quantity_json->>'imageCount')::integer,0)>0 or "
                        + "coalesce((quantity_json->>'videoCount')::integer,0)>0 or "
                        + "coalesce((quantity_json->>'audioCount')::integer,0)>0)")
                .param("project", scenario.project().id()).query(Long.class).single();
    }
    private void expirePending(Scenario scenario) {
        jdbc.sql("update agent_media_approval set created_at=now()-interval '2 days',expires_at=now()-interval '1 hour' where id=:id")
                .param("id", scenario.approval().id()).update();
    }
    private void replayOutcome(Scenario scenario) {
        outcomes.reconcile(owner.userId(), scenario.project().id(), scenario.run().id(), scenario.approval().id());
        outcomes.reconcile(owner.userId(), scenario.project().id(), scenario.run().id(), scenario.approval().id());
        events.listAfter(owner.userId(), scenario.project().id(), 0, 200).stream()
                .filter(event -> "task.status.changed".equals(event.type()))
                .filter(event -> mediaTasks(scenario).stream().anyMatch(task -> task.id().equals(event.aggregateId())))
                .forEach(event -> outcomes.onProjectEvent(new ProjectEventRecorded(owner.userId(), event)));
        assertThat(turnCount(scenario)).isEqualTo(2);
    }
    private void finish(Scenario scenario, String approvalStatus) {
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.RUNNING);
        assertThat(turnCount(scenario)).isEqualTo(2);
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        Plan plan = gateway.plan(scenario.run().id());
        assertThat(plan.calls).hasValue(2);
        assertThat(plan.reply.path("awaitingMedia").asBoolean()).isFalse();
        assertThat(plan.reply.at("/mediaApproval/status").asText()).isEqualTo(approvalStatus);
    }
    private record Scenario(Project project, AgentRun run, ApprovalView approval) {}

    @TestConfiguration
    static class FakeConfig {
        @Bean @Primary FakeGateway fakeGateway(ObjectMapper mapper) { return new FakeGateway(mapper); }
        /** The production recovery scheduler has no enable switch; keep this test's recovery explicit. */
        @Bean(name = "taskRecoveryScheduler")
        TaskRecoveryScheduler disabledRecovery(TaskService tasks, TaskProperties properties) {
            return new TaskRecoveryScheduler(tasks, properties) { @Override public void scan() {} };
        }
    }

    private static final class Plan {
        private final String arguments;
        private final String toolCallId;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile JsonNode reply;
        private volatile boolean readGeneratedOutput;
        private volatile boolean failNextCall;
        private volatile boolean imageToVideo;
        private volatile JsonNode artifactReply;
        private volatile JsonNode videoReply;
        private volatile JsonNode directoryReply;
        private volatile JsonNode arrangementReply;
        private Plan(UUID runId, String arguments) { this.arguments = arguments; this.toolCallId = "media-call-" + runId; }
    }

    static class FakeGateway implements ChatGateway {
        private final Map<UUID, Plan> plans = new ConcurrentHashMap<>();
        private final ObjectMapper mapper;
        FakeGateway(ObjectMapper mapper) { this.mapper = mapper; }
        void prepare(UUID runId, String arguments) { plans.put(runId, new Plan(runId, arguments)); }
        Plan plan(UUID runId) { return plans.get(runId); }
        @Override public String configSource() { return "synthetic-approval-model"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context) {
            Plan plan = plans.get(UUID.fromString((String) context.get("runId")));
            assertThat(plan).isNotNull();
            assertThat(tools).anySatisfy(tool -> assertThat(tool.getToolDefinition().name()).isEqualTo("propose_media_generation"));
            if (plan.failNextCall) {
                plan.failNextCall = false;
                throw new java.io.UncheckedIOException(new java.net.SocketTimeoutException("synthetic model timeout"));
            }
            int callIndex = plan.calls.incrementAndGet();
            if (callIndex == 1) {
                AssistantMessage proposal = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(plan.toolCallId, "function",
                                "propose_media_generation", plan.arguments))).build();
                return new Exchange(1, new ChatResponse(List.of(new Generation(proposal))));
            }
            ToolResponseMessage response = messages.stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).toList().getLast();
            var previewMessages = messages.stream().filter(org.springframework.ai.chat.messages.UserMessage.class::isInstance)
                    .map(org.springframework.ai.chat.messages.UserMessage.class::cast)
                    .filter(message -> !message.getMedia().isEmpty()).toList();
            if (plan.readGeneratedOutput && callIndex >= 3) {
                assertThat(previewMessages).singleElement().satisfies(message -> assertThat(message.getMedia()).hasSize(1));
                if (callIndex == 3) {
                    assertThat(messages.getLast()).isSameAs(previewMessages.getFirst());
                    assertThat(messages.indexOf(previewMessages.getFirst())).isGreaterThan(messages.indexOf(response));
                }
            } else {
                assertThat(previewMessages).isEmpty();
            }
            if (plan.imageToVideo && callIndex == 3) {
                plan.videoReply = successfulReply(response, "video-stage-" + context.get("runId"), "propose_media_generation");
                return new Exchange(1, new ChatResponse(List.of(new Generation(new AssistantMessage("The image and video stages are archived.")))));
            }
            if (plan.readGeneratedOutput && callIndex == 3) {
                plan.artifactReply = successfulReply(response, "read-generated-" + context.get("runId"), "read_artifacts");
                return toolCall("read-canvas-" + context.get("runId"), "read_canvas", mapper.createObjectNode(),
                        "Read the current media node and its layout version.");
            }
            if (plan.readGeneratedOutput && callIndex == 4) {
                plan.directoryReply = successfulReply(response, "read-canvas-" + context.get("runId"), "read_canvas");
                ObjectNode arguments = mapper.createObjectNode().put("layout", "HORIZONTAL");
                ObjectNode item = arguments.putArray("items").addObject();
                item.put("itemId", plan.directoryReply.at("/data/0/itemId").asText());
                item.put("versionId", plan.directoryReply.at("/data/0/selectedVersionId").asText());
                item.put("expectedVersion", plan.directoryReply.at("/data/0/itemVersion").asLong());
                return toolCall("arrange-generated-" + context.get("runId"), "arrange_items", arguments,
                        "Arrange the current media node.");
            }
            if (plan.readGeneratedOutput && callIndex == 5) {
                plan.arrangementReply = successfulReply(response, "arrange-generated-" + context.get("runId"), "arrange_items");
                return new Exchange(1, new ChatResponse(List.of(new Generation(
                        new AssistantMessage("The generated media version is verified and arranged.")))));
            }
            assertThat(response.getResponses()).singleElement().satisfies(tool -> {
                assertThat(tool.id()).isEqualTo(plan.toolCallId);
                assertThat(tool.name()).isEqualTo("propose_media_generation");
                plan.reply = mapper.readTree(tool.responseData());
            });
            if (plan.imageToVideo) {
                ObjectNode arguments = mapper.createObjectNode();
                ObjectNode video = arguments.putArray("outputs").addObject().put("kind", "VIDEO")
                        .put("title", "Synthetic director shot").put("prompt", "Animate the referenced synthetic image")
                        .put("durationSeconds", 1).put("videoInputMode", "START_END");
                video.putObject("parameters");
                video.putArray("mediaInputs").addObject().put("versionId", plan.reply.at("/mediaApproval/tasks/0/artifactVersionId").asText())
                        .put("role", "START_FRAME");
                return toolCall("video-stage-" + context.get("runId"), "propose_media_generation", arguments, "Create the next video from the archived image.");
            }
            if (plan.readGeneratedOutput) {
                ObjectNode arguments = mapper.createObjectNode();
                arguments.putArray("versionIds").add(plan.reply.at("/mediaApproval/tasks/0/artifactVersionId").asText());
                return toolCall("read-generated-" + context.get("runId"), "read_artifacts", arguments,
                        "Verify the archived media version.");
            }
            return new Exchange(1, new ChatResponse(List.of(new Generation(new AssistantMessage("The media batch result is recorded.")))));
        }

        private JsonNode successfulReply(ToolResponseMessage response, String callId, String name) {
            assertThat(response.getResponses()).singleElement().satisfies(tool -> {
                assertThat(tool.id()).isEqualTo(callId);
                assertThat(tool.name()).isEqualTo(name);
            });
            JsonNode reply = mapper.readTree(response.getResponses().getFirst().responseData());
            assertThat(reply.path("status").asText()).isEqualTo("SUCCEEDED");
            return reply;
        }

        private Exchange toolCall(String callId, String name, ObjectNode arguments, String text) {
            AssistantMessage response = AssistantMessage.builder().content(text)
                    .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", name, arguments.toString())))
                    .build();
            return new Exchange(1, new ChatResponse(List.of(new Generation(response))));
        }
    }
}
