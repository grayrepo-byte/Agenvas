package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.plan.application.ShotKeyframeSelectionService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.domain.GenerationGateway;
import dev.agenvas.provider.domain.GenerationRequest;
import dev.agenvas.provider.domain.GenerationResult;
import dev.agenvas.provider.domain.MockFixture;
import dev.agenvas.provider.infrastructure.MockGenerationGateway;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Fake-model golden paths for database-triggered approval and rejection continuation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, PlanResumeWorkerPostgresIT.FakeConfig.class},
        properties = "agenvas.identity.bootstrap-secret=plan-resume-integration-secret")
class PlanResumeWorkerPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = temporaryRoot();

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assetFiles;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ExecutionPlanService plans;
    @Autowired private ShotKeyframeSelectionService keyframes;
    @Autowired private TaskService tasks;
    @Autowired private AgentTurnWorker worker;
    @Autowired private MediaExecutionWorker mediaWorker;
    @Autowired private FixtureGateway generationGateway;
    @Autowired private FakeGateway gateway;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private LlmTurnCheckpointService checkpoints;
    @Autowired private LlmProtocolCodec codec;
    @Autowired private ToolExecutionService toolExecutor;
    @Autowired private ToolRegistry toolRegistry;

    @Test
    void approvalWaitsForMediaAndRejectionResumesWithoutMedia() throws Exception {
        AdminPrincipal owner = identities.setup("plan-resume-integration-secret",
                "resume-admin", "resume-password-123");
        Fixture approved = fixture(owner.userId(), "Approved project", "approved-run");
        gateway.draftJson = draft(approved.shot()).toString();
        assertThat(worker.runOnce("resume-model-worker")).isEqualTo(1);
        AgentRun awaitingApproval = runs.get(owner.userId(), approved.project().id(),
                approved.run().id());
        assertThat(awaitingApproval.status()).isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        ExecutionPlan imagePlan = plans.listByRun(owner.userId(), approved.project().id(),
                approved.run().id()).getFirst();
        assertThat(worker.runOnce("resume-model-worker")).isZero();

        plans.approve(owner.userId(), approved.project().id(), imagePlan.id(),
                imagePlan.planHash(), plans.get(owner.userId(), approved.project().id(), imagePlan.id()).steps().stream().map(dev.agenvas.plan.application.ExecutionPlan.Step::stepKey).toList());
        assertThat(runs.get(owner.userId(), approved.project().id(), approved.run().id()).status())
                .isEqualTo(AgentRun.Status.WAITING_TASKS);
        assertThat(worker.runOnce("resume-model-worker")).isZero();
        Task media = tasks.listByRun(owner.userId(), approved.project().id(),
                approved.run().id()).stream()
                .filter(task -> task.kind() == Task.Kind.IMAGE_GENERATION).findFirst().orElseThrow();
        assertThat(mediaWorker.submitOnce("resume-media-worker")).isEqualTo(1);
        Task resume = tasks.listByRun(owner.userId(), approved.project().id(),
                approved.run().id()).stream()
                .filter(task -> task.kind() == Task.Kind.AGENT_TURN
                        && task.input().path("stepIndex").asInt(-1) == 1)
                .findFirst().orElseThrow();
        assertThat(resume.status()).isEqualTo(Task.Status.PENDING);
        assertThat(jdbc.sql("select status from provider_attempt where task_id = :taskId")
                .param("taskId", media.id()).query(String.class).single()).isEqualTo("ACCEPTED");
        String versionId = tasks.get(owner.userId(), approved.project().id(), media.id())
                .output().path("artifactVersionId").asText();
        keyframes.select(owner.userId(), approved.project().id(), approved.run().id(),
                approved.shot().artifact().id(), approved.shot().currentVersion().id(),
                UUID.fromString(tasks.get(owner.userId(), approved.project().id(), media.id())
                        .output().path("artifactId").asText()),
                UUID.fromString(versionId), null);
        assertThat(tasks.get(owner.userId(), approved.project().id(), resume.id()).status())
                .isEqualTo(Task.Status.READY);
        var imageVersion = artifacts.get(owner.userId(), approved.project().id(),
                        UUID.fromString(tasks.get(owner.userId(), approved.project().id(),
                                media.id()).output().path("artifactId").asText()))
                .currentVersion();
        assertThat(imageVersion.content().path("parameters").path("mock").booleanValue())
                .isTrue();
        assertThat(imageVersion.content().path("parameters").path("displayLabel").asText())
                .isEqualTo("演示素材");
        String requestKey = jdbc.sql("select request_key::text from provider_attempt "
                        + "where task_id = :taskId")
                .param("taskId", media.id()).query(String.class).single();
        String expectedProviderId = "mock-" + UUID.nameUUIDFromBytes(
                (approved.project().id() + ":" + requestKey)
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(imageVersion.content().path("parameters")
                .path("providerRequestId").asText()).isEqualTo(expectedProviderId);
        String assetId = imageVersion.content().path("assetId").asText();
        byte[] bytes = Files.readAllBytes(assetFiles.get(owner.userId(),
                approved.project().id(), UUID.fromString(assetId)).path());
        assertThat(bytes).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        gateway.expectedResult = versionId;
        assertThat(worker.runOnce("resume-model-worker")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), approved.project().id(), approved.run().id()).status())
                .isEqualTo(AgentRun.Status.SUCCEEDED);

        Fixture rejected = fixture(owner.userId(), "Rejected project", "rejected-run");
        gateway.draftJson = draft(rejected.shot()).toString();
        gateway.expectedResult = "REJECTED";
        assertThat(worker.runOnce("resume-model-worker")).isEqualTo(1);
        ExecutionPlan rejectedPlan = plans.listByRun(owner.userId(), rejected.project().id(),
                rejected.run().id()).getFirst();
        plans.reject(owner.userId(), rejected.project().id(), rejectedPlan.id());
        assertThat(tasks.listByRun(owner.userId(), rejected.project().id(), rejected.run().id()))
                .noneMatch(task -> task.kind() == Task.Kind.IMAGE_GENERATION);
        assertThat(worker.runOnce("resume-model-worker")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), rejected.project().id(), rejected.run().id()).status())
                .isEqualTo(AgentRun.Status.SUCCEEDED);

        Fixture failed = fixture(owner.userId(), "Failed media project", "failed-media-run");
        gateway.draftJson = draft(failed.shot()).toString();
        assertThat(worker.runOnce("resume-model-worker")).isEqualTo(1);
        ExecutionPlan failedPlan = plans.listByRun(owner.userId(), failed.project().id(),
                failed.run().id()).getFirst();
        plans.approve(owner.userId(), failed.project().id(), failedPlan.id(),
                failedPlan.planHash(), plans.get(owner.userId(), failed.project().id(), failedPlan.id()).steps().stream().map(dev.agenvas.plan.application.ExecutionPlan.Step::stepKey).toList());
        generationGateway.setFixture(failed.project().id(), MockFixture.FAILURE);
        assertThat(mediaWorker.submitOnce("failed-media-worker"))
                .isEqualTo(1);
        assertThat(runs.get(owner.userId(), failed.project().id(), failed.run().id()).status())
                .isEqualTo(AgentRun.Status.BLOCKED);
        Task rejectedMedia = tasks.listByRun(owner.userId(), failed.project().id(),
                failed.run().id()).stream().filter(task ->
                        task.kind() == Task.Kind.IMAGE_GENERATION).findFirst().orElseThrow();
        assertThat(rejectedMedia.status()).isEqualTo(Task.Status.FAILED);
        assertThat(jdbc.sql("select status from provider_attempt where task_id = :taskId")
                .param("taskId", rejectedMedia.id()).query(String.class).single())
                .isEqualTo("REJECTED");
        assertThat(worker.runOnce("resume-model-worker")).isZero();

        Fixture unknown = fixture(owner.userId(), "Unknown media project", "unknown-media-run");
        gateway.draftJson = draft(unknown.shot()).toString();
        assertThat(worker.runOnce("resume-model-worker")).isEqualTo(1);
        ExecutionPlan unknownPlan = plans.listByRun(owner.userId(), unknown.project().id(),
                unknown.run().id()).getFirst();
        plans.approve(owner.userId(), unknown.project().id(), unknownPlan.id(),
                unknownPlan.planHash(), plans.get(owner.userId(), unknown.project().id(), unknownPlan.id()).steps().stream().map(dev.agenvas.plan.application.ExecutionPlan.Step::stepKey).toList());
        generationGateway.setFixture(unknown.project().id(), MockFixture.UNKNOWN);
        assertThatThrownBy(() -> mediaWorker.submitOnce("unknown-media-worker"))
                .isInstanceOf(IllegalStateException.class);
        Task ambiguous = tasks.listByRun(owner.userId(), unknown.project().id(),
                unknown.run().id()).stream()
                .filter(task -> task.kind() == Task.Kind.IMAGE_GENERATION)
                .findFirst().orElseThrow();
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :taskId")
                .param("taskId", ambiguous.id()).update();
        assertThat(tasks.recoverExpiredSubmissions(1)).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), unknown.project().id(), ambiguous.id()).status())
                .isEqualTo(Task.Status.UNKNOWN);
        assertThat(runs.get(owner.userId(), unknown.project().id(), unknown.run().id()).status())
                .isEqualTo(AgentRun.Status.BLOCKED);
        assertThat(worker.runOnce("resume-model-worker")).isZero();
        assertThat(gateway.calls.get()).isEqualTo(6);
        verifyChangedToolCallIdCannotDuplicateApprovedMedia(owner);
    }

    /** A new model call ID cannot re-authorize a step whose original proposal was approved. */
    private void verifyChangedToolCallIdCannotDuplicateApprovedMedia(AdminPrincipal owner) {
        Fixture fixture = fixture(owner.userId(), "Duplicate proposal project", "duplicate-plan-run");
        AgentRun running = runs.transition(owner.userId(), fixture.project().id(),
                fixture.run().id(), fixture.run().version(), AgentRun.Status.RUNNING);
        TrustedToolContext context = new TrustedToolContext(owner.userId(),
                fixture.project().id(), fixture.run().id());
        int configVersion = running.policySnapshot().path("modelConfigVersion").asInt();
        String configSource = running.policySnapshot().path("modelConfigSource").asText();
        List<Message> messages = List.of(new UserMessage("Propose one image plan"));
        List<ToolCallback> definitions = toolRegistry.modelDefinitions();
        checkpoints.reserve(owner.userId(), fixture.project().id(), fixture.run().id(),
                0, configVersion, configSource, codec.request(messages, definitions));
        String arguments = draft(fixture.shot()).toString();
        AssistantMessage response = AssistantMessage.builder().content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("proposal-original", "function",
                                "propose_generation_plan", arguments),
                        new AssistantMessage.ToolCall("proposal-new-id", "function",
                                "propose_generation_plan", arguments)))
                .build();
        checkpoints.saveResponse(owner.userId(), fixture.project().id(), fixture.run().id(),
                0, configVersion, codec.response(new ChatResponse(List.of(
                        new Generation(response)))));
        JsonNode original = toolExecutor.execute(context, 0, "proposal-original");
        ExecutionPlan plan = plans.listByRun(owner.userId(), fixture.project().id(),
                fixture.run().id()).getFirst();
        assertThat(original.path("createdIds").get(0).asText()).isEqualTo(plan.id().toString());
        ExecutionPlanService.ApprovalResult approved = plans.approve(owner.userId(),
                fixture.project().id(), plan.id(), plan.planHash(), plans.get(owner.userId(), fixture.project().id(), plan.id()).steps().stream().map(dev.agenvas.plan.application.ExecutionPlan.Step::stepKey).toList());
        assertThat(approved.tasks()).hasSize(1);
        assertThat(toolExecutor.execute(context, 0, "proposal-original")).isEqualTo(original);
        assertThatThrownBy(() -> toolExecutor.execute(context, 0, "proposal-new-id"))
                .isInstanceOf(ApiProblemException.class);
        assertThat(plans.listByRun(owner.userId(), fixture.project().id(),
                fixture.run().id())).hasSize(1);
        assertThat(jdbc.sql("select count(*) from plan_approval where project_id = :projectId")
                .param("projectId", fixture.project().id()).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and kind = 'IMAGE_GENERATION'")
                .param("projectId", fixture.project().id()).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from usage_ledger where project_id = :projectId "
                        + "and task_id is not null and entry_type = 'RESERVATION'")
                .param("projectId", fixture.project().id()).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from tool_execution where project_id = :projectId")
                .param("projectId", fixture.project().id()).query(Long.class).single())
                .isEqualTo(1);
    }

    private Fixture fixture(UUID ownerId, String projectName, String key) {
        Project project = projects.create(ownerId, projectName,
                Project.AspectRatio.LANDSCAPE_16_9);
        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "Ridge");
        scene.put("location", "Ridge");
        scene.put("timeOfDay", "Dawn");
        scene.put("lighting", "Soft");
        scene.put("style", "Cinematic");
        scene.putArray("referenceVersionIds");
        UUID sceneVersion = artifacts.create(ownerId, project.id(), Artifact.Kind.SCENE,
                "Ridge", scene).currentVersion().id();
        ObjectNode shotContent = mapper.createObjectNode();
        shotContent.put("order", 1);
        shotContent.put("durationSeconds", 1);
        shotContent.put("description", "A sunrise");
        shotContent.put("camera", "Wide");
        shotContent.put("action", "Pan");
        shotContent.putArray("characterVersionIds");
        shotContent.put("sceneVersionId", sceneVersion.toString());
        ArtifactService.ArtifactView shot = artifacts.create(ownerId, project.id(),
                Artifact.Kind.SHOT, "Opening", shotContent);
        AgentInstance agent = agents.create(ownerId, project.id(), "Creator",
                "Make an image plan", List.of(new AgentInstanceService.BindingInput(
                        shot.artifact().id(), shot.currentVersion().id())));
        AgentRun run = runs.create(ownerId, project.id(), agent.id(),
                "Make an image then report", key).run();
        return new Fixture(project, shot, run);
    }

    private ObjectNode draft(ArtifactService.ArtifactView shot) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("stage", "IMAGE");
        draft.put("objective", "Opening keyframe");
        ObjectNode step = draft.putArray("steps").addObject();
        step.put("stepKey", "image-1");
        step.put("outputSlotKey", "opening-keyframe");
        step.put("shotArtifactId", shot.artifact().id().toString());
        step.put("shotVersionId", shot.currentVersion().id().toString());
        step.put("prompt", "Sunrise on a ridge");
        step.putArray("dependsOnStepKeys");
        return draft;
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-mock-image-it-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record Fixture(Project project, ArtifactService.ArtifactView shot, AgentRun run) {}

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway() {
            return new FakeGateway();
        }

        @Bean
        @Primary
        FixtureGateway fixtureGateway() {
            return new FixtureGateway();
        }
    }

    static class FixtureGateway implements GenerationGateway {
        private final MockGenerationGateway delegate = new MockGenerationGateway();
        private final Map<UUID, MockFixture> fixtures = new ConcurrentHashMap<>();

        void setFixture(UUID projectId, MockFixture fixture) {
            fixtures.put(projectId, fixture);
        }

        @Override
        public GenerationResult submit(GenerationRequest request) {
            return delegate.submit(new GenerationRequest(request.projectId(),
                    request.requestKey(), fixtures.getOrDefault(request.projectId(),
                            request.fixture())));
        }
    }

    /** Checks that only saved user decisions and media outputs reach continuation. */
    static class FakeGateway implements ChatGateway {
        @Override
        public String configSource() {
            return "test-fake";
        }

        @Override
        public int configVersion() {
            return 1;
        }

        private final AtomicInteger calls = new AtomicInteger();
        private volatile String draftJson;
        private volatile String expectedResult;

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            calls.incrementAndGet();
            String latest = messages.getLast().getText();
            if (latest != null && latest.startsWith("User decision for plan")) {
                assertThat(latest).contains(expectedResult);
                return new Exchange(1, new ChatResponse(List.of(
                        new Generation(new AssistantMessage("Plan result received.")))));
            }
            AssistantMessage proposal = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("propose-image",
                            "function", "propose_generation_plan", draftJson))).build();
            return new Exchange(1, new ChatResponse(List.of(new Generation(proposal))));
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, false, false);
        }
    }
}
