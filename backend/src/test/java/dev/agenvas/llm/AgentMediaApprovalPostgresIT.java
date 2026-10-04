package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.MediaDraftService;
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
import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskRecoveryScheduler;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
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
                "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false", "agenvas.export.scheduler-enabled=false",
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
    @Autowired private AgentTurnWorker modelWorker;
    @Autowired private MediaExecutionWorker mediaWorker;
    @Autowired private AgentMediaApprovalService approvals;
    @Autowired private AgentMediaOutcomeService outcomes;
    @Autowired private MediaDraftService drafts;
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

    @Test
    void mediaCardsExposeApprovedAgentTasksThroughTheirTaskQuery() throws Exception {
        Scenario scenario = propose(outputs("IMAGE", "VIDEO", "AUDIO"));
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
                .as("Placing approved media must reuse its existing selected node")
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
                    assertThat(entry.item().version()).isEqualTo(plan.placementReply.at("/data/0/itemVersion").asLong() + 1);
                });

        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(plan.calls).hasValue(5);
        assertThat(plan.artifactReply.at("/data/0/versionId").asText()).isEqualTo(versionId);
        assertThat(plan.artifactReply.at("/data/0/kind").asText()).isEqualTo("IMAGE");
        assertThat(plan.placementReply.at("/data/0/itemId").asText()).isEqualTo(itemId.toString());
        assertThat(plan.placementReply.at("/data/0/created").asBoolean()).isFalse();
        assertThat(plan.arrangementReply.at("/data/0/itemId").asText()).isEqualTo(itemId.toString());
        assertThat(plan.arrangementReply.at("/data/0/itemVersion").asLong())
                .isEqualTo(plan.placementReply.at("/data/0/itemVersion").asLong() + 1);
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
        connections.disconnect(owner.userId(), video.project().id(), line.id(), preparedDraft.version(), null);
        assertThat(drafts.get(owner.userId(), video.project().id(), videoCardId).mediaInputs()).isEmpty();
        assertThat(connections.list(owner.userId(), video.project().id())).noneMatch(value -> value.id().equals(line.id()));
        assertThat(canvas.list(owner.userId(), video.project().id()).stream()
                .filter(value -> value.item().id().equals(videoCardId)).findFirst().orElseThrow()
                .selectedVersion().frozenInput().at("/images/0/versionId").asText()).isEqualTo(imageVersion);
    }

    @Test
    void missingReferenceCardIsPlacedAtTheExactVersionAndDisconnectInvalidatesApproval() throws Exception {
        Scenario image = propose(outputs("IMAGE"));
        gateway.plan(image.run().id()).imageToVideo = true;
        decide(image, "APPROVE", "missing-source-image-stage");
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        String imageVersion = mediaTasks(image).getFirst().output().path("artifactVersionId").asText();
        UUID originalCardId = image.approval().outputs().getFirst().canvasItemId();
        var original = canvas.list(owner.userId(), image.project().id()).stream()
                .filter(value -> value.item().id().equals(originalCardId)).findFirst().orElseThrow().item();
        canvas.apply(owner.userId(), image.project().id(), List.of(new CanvasService.Remove(original.id(), original.version())));
        assertThat(modelWorker.runOnce(MODEL_WORKER)).isEqualTo(1);
        ApprovalView next = approvals.list(owner.userId(), image.project().id(), image.run().id()).stream()
                .filter(value -> value.status() == AgentMediaApproval.Status.PENDING).findFirst().orElseThrow();
        UUID targetId = next.outputs().getFirst().canvasItemId();
        var line = connections.list(owner.userId(), image.project().id()).stream()
                .filter(value -> value.targetCanvasItemId().equals(targetId)).findFirst().orElseThrow();
        assertThat(line.sourceCanvasItemId()).isNotEqualTo(originalCardId);
        var source = canvas.list(owner.userId(), image.project().id()).stream()
                .filter(value -> value.item().id().equals(line.sourceCanvasItemId())).findFirst().orElseThrow();
        assertThat(source.item().selectedVersionId().toString()).isEqualTo(imageVersion);
        assertThat(source.artifact().artifact().resourceDefaultVersionId()).isNull();
        connections.disconnect(owner.userId(), image.project().id(), line.id(), next.outputs().getFirst().draftVersion(), null);
        assertThat(drafts.get(owner.userId(), image.project().id(), targetId).mediaInputs()).isEmpty();
        mvc.perform(post(decisionPath(new Scenario(image.project(), image.run(), next))).with(auth).with(csrf())
                .header("Idempotency-Key", "removed-reference-approval").contentType("application/json")
                .content(mapper.createObjectNode().put("expectedVersion", next.version()).put("decision", "APPROVE").toString()))
                .andExpect(status().isConflict());
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

    private Scenario propose(ObjectNode outputs) {
        Project project = projects.create(owner.userId(), "Synthetic approval project", Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Synthetic Creator", "Create approved media", List.of());
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
        private volatile JsonNode placementReply;
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
                successfulReply(response, "video-stage-" + context.get("runId"), "propose_media_generation");
                return new Exchange(1, new ChatResponse(List.of(new Generation(new AssistantMessage("The image and video stages are archived.")))));
            }
            if (plan.readGeneratedOutput && callIndex == 3) {
                plan.artifactReply = successfulReply(response, "read-generated-" + context.get("runId"), "read_artifacts");
                ObjectNode arguments = mapper.createObjectNode().put("group", "AGENT_OUTPUT");
                arguments.putArray("versionIds").add(plan.artifactReply.at("/data/0/versionId").asText());
                return toolCall("place-generated-" + context.get("runId"), "place_artifacts", arguments,
                        "Place the verified media in my output group.");
            }
            if (plan.readGeneratedOutput && callIndex == 4) {
                plan.placementReply = successfulReply(response, "place-generated-" + context.get("runId"), "place_artifacts");
                ObjectNode arguments = mapper.createObjectNode().put("layout", "HORIZONTAL");
                ObjectNode item = arguments.putArray("items").addObject();
                item.put("itemId", plan.placementReply.at("/data/0/itemId").asText());
                item.put("versionId", plan.placementReply.at("/data/0/versionId").asText());
                item.put("expectedVersion", plan.placementReply.at("/data/0/itemVersion").asLong());
                return toolCall("arrange-generated-" + context.get("runId"), "arrange_items", arguments,
                        "Arrange the existing media output node.");
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
