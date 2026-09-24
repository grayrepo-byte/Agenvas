package dev.agenvas.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.AgentTurnCommitService;
import dev.agenvas.llm.application.LlmConversationService;
import dev.agenvas.llm.application.PlanResumeContextService;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.plan.application.ShotKeyframeSelectionService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import dev.agenvas.testing.ImageAssetFixture;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** PostgreSQL approval proof: model proposal never submits media, while exact user approval does. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=plan-approval-integration-secret")
class ExecutionPlanPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private UsageService usage;
    @Autowired private CanvasService canvas;
    @Autowired private ExecutionPlanService plans;
    @Autowired private ShotKeyframeSelectionService keyframeSelections;
    @Autowired private ToolRegistry registry;
    @Autowired private ToolExecutionService toolExecutor;
    @Autowired private LlmTurnCheckpointService checkpoints;
    @Autowired private AgentTurnCommitService turnCommits;
    @Autowired private LlmConversationService conversation;
    @Autowired private PlanResumeContextService resumeContext;
    @Autowired private LlmProtocolCodec codec;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private WebApplicationContext webContext;
    @Autowired private PlatformTransactionManager transactionManager;
    private UUID mediaOwnerId;
    private UUID mediaProjectId;

    @Test
    void twoStageProposalRequiresExactAuthenticatedApprovalAndCreatesOneDag() throws Exception {
        MockMvc mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        AdminPrincipal owner = identities.setup("plan-approval-integration-secret",
                "plan-admin", "plan-password-123");
        Project project = projects.create(owner.userId(), "Plan project",
                Project.AspectRatio.LANDSCAPE_16_9);
        mediaOwnerId = owner.userId();
        mediaProjectId = project.id();
        List<ArtifactService.ArtifactView> shots = createShots(owner.userId(), project.id());
        UUID sceneVersionId = UUID.fromString(shots.getFirst().currentVersion()
                .content().path("sceneVersionId").asText());
        UUID sceneArtifactId = jdbc.sql("select artifact_id from artifact_version where id = :id")
                .param("id", sceneVersionId).query(UUID.class).single();
        List<AgentInstanceService.BindingInput> bindings = new ArrayList<>(shots.stream()
                .map(shot -> new AgentInstanceService.BindingInput(shot.artifact().id(),
                        shot.currentVersion().id())).toList());
        bindings.add(new AgentInstanceService.BindingInput(sceneArtifactId, sceneVersionId));
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                bindings);
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Make three shot keyframes", "plan-run").run();
        AgentRun running = runs.transition(owner.userId(), project.id(), queued.id(), 0,
                AgentRun.Status.RUNNING);
        Task initialLease = tasks.claimAgentTurns("plan-model-worker", 1).getFirst();
        turnCommits.start(initialLease, "plan-model-worker");
        TrustedToolContext context = new TrustedToolContext(owner.userId(), project.id(), running.id());

        ObjectNode cyclic = draft("IMAGE", shots, List.of());
        ((ObjectNode) cyclic.path("steps").get(0)).putArray("dependsOnStepKeys").add("step-2");
        ((ObjectNode) cyclic.path("steps").get(1)).putArray("dependsOnStepKeys").add("step-1");
        assertThatThrownBy(() -> plans.propose(context, cyclic))
                .isInstanceOf(ApiProblemException.class);
        assertThat(count("execution_plan")).isZero();

        ObjectNode wrongType = draft("IMAGE", shots, List.of());
        ((ObjectNode) wrongType.path("steps").get(0))
                .put("shotArtifactId", sceneArtifactId.toString())
                .put("shotVersionId", sceneVersionId.toString());
        assertThatThrownBy(() -> plans.propose(context, wrongType))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.code()).isEqualTo("PLAN_INVALID"));

        Project foreignProject = projects.create(owner.userId(), "Foreign plan inputs",
                Project.AspectRatio.LANDSCAPE_16_9);
        ArtifactService.ArtifactView foreignShot = createShots(owner.userId(),
                foreignProject.id()).getFirst();
        ObjectNode foreignInput = draft("IMAGE", shots, List.of());
        ((ObjectNode) foreignInput.path("steps").get(0))
                .put("shotArtifactId", foreignShot.artifact().id().toString())
                .put("shotVersionId", foreignShot.currentVersion().id().toString());
        assertThatThrownBy(() -> plans.propose(context, foreignInput))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        assertThat(count("execution_plan")).isZero();

        ObjectNode imageDraft = draft("IMAGE", shots, List.of());
        ((ObjectNode) imageDraft.path("steps").get(1))
                .putArray("dependsOnStepKeys").add("step-1");
        checkpoints.reserve(owner.userId(), project.id(), running.id(), 0, 1, "mock",
                codec.request(List.of(new UserMessage("Propose keyframes")),
                        registry.modelDefinitions()));
        AssistantMessage proposed = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-plan-image", "function",
                        "propose_generation_plan", imageDraft.toString()))).build();
        checkpoints.saveResponse(owner.userId(), project.id(), running.id(), 0, 1,
                codec.response(new ChatResponse(List.of(new Generation(proposed)))));
        JsonNode proposalResult = toolExecutor.executeLeased(context, 0, "call-plan-image",
                initialLease, "plan-model-worker");
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :taskId")
                .param("taskId", initialLease.id()).update();
        Task recoveredLease = tasks.claimAgentTurns("plan-recovery-worker", 1).getFirst();
        assertThat(recoveredLease.leaseEpoch()).isGreaterThan(initialLease.leaseEpoch());
        assertThat(turnCommits.complete(recoveredLease, "plan-recovery-worker"))
                .isEqualTo(AgentTurnCommitService.Decision.WAIT_APPROVAL);
        UUID imagePlanId = UUID.fromString(proposalResult.at("/createdIds/0").asText());
        ExecutionPlan imagePlan = plans.get(owner.userId(), project.id(), imagePlanId);
        assertThat(imagePlan.status()).isEqualTo(ExecutionPlan.Status.PENDING);
        assertThat(runs.get(owner.userId(), project.id(), running.id()).status())
                .isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        assertThat(mediaTasks(owner.userId(), project.id(), running.id())).isEmpty();
        assertThat(registry.modelDefinitions()).extracting(callback -> callback.getToolDefinition().name())
                .doesNotContain("approve_plan");

        String planPath = "/api/v1/projects/" + project.id() + "/plans/" + imagePlanId;
        mvc.perform(get(planPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(planPath).with(authentication(asUser(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planHash").value(imagePlan.planHash()));
        mvc.perform(get("/api/v1/projects/" + project.id() + "/runs/" + running.id()
                + "/plans").with(authentication(asUser(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(imagePlanId.toString()));
        mvc.perform(post(planPath + "/approve").with(authentication(asUser(owner)))
                .contentType("application/json")
                .content("{\"planHash\":\"" + imagePlan.planHash() + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(mediaTasks(owner.userId(), project.id(), running.id())).isEmpty();

        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceAgent(
                UUID.randomUUID(), agent.id(), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("320"), new BigDecimal("200"), 0, null, false)));
        assertThatThrownBy(() -> plans.approve(owner.userId(), project.id(), imagePlanId,
                "0".repeat(64))).isInstanceOf(ApiProblemException.class);
        mvc.perform(get(planPath).with(authentication(asUser(
                        new AdminPrincipal(UUID.randomUUID(), "not-owner")))))
                .andExpect(status().isNotFound());
        mvc.perform(post(planPath + "/approve").with(authentication(asUser(owner)))
                .with(csrf()).contentType("application/json")
                .content("{\"planHash\":\"" + "0".repeat(64) + "\"}"))
                .andExpect(status().isConflict());
        assertThat(count("plan_approval")).isZero();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(20)) {
            List<Future<ExecutionPlanService.ApprovalResult>> requests = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                requests.add(pool.submit(() -> {
                    start.await();
                    return plans.approve(owner.userId(), project.id(), imagePlanId,
                            imagePlan.planHash());
                }));
            }
            start.countDown();
            List<ExecutionPlanService.ApprovalResult> results = new ArrayList<>();
            for (Future<ExecutionPlanService.ApprovalResult> request : requests) {
                results.add(request.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).hasSize(20).allSatisfy(result -> {
                assertThat(result.approvalId()).isEqualTo(results.getFirst().approvalId());
                assertThat(result.tasks()).hasSize(imagePlan.steps().size());
            });
            assertThat(results.stream().filter(result -> !result.replayed()).count()).isEqualTo(1);
        }
        assertThat(count("plan_approval")).isEqualTo(1);
        assertThat(imagePlan.estimate().path("imageCount").intValue())
                .isEqualTo(imagePlan.steps().size());
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and plan_id = :planId and kind = 'IMAGE_GENERATION'")
                .param("projectId", project.id()).param("planId", imagePlanId)
                .query(Integer.class).single()).isEqualTo(imagePlan.steps().size());
        assertThat(jdbc.sql("select count(*) from usage_ledger where project_id = :projectId "
                        + "and task_id is not null and entry_type = 'RESERVATION'")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from usage_ledger where project_id = :projectId "
                        + "and task_id is not null and cost_status = 'UNKNOWN' and estimated_cost is null "
                        + "and actual_cost is null")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(3);
        mvc.perform(get("/api/v1/projects/" + project.id() + "/usage")
                        .with(authentication(asUser(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].costStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$[0].actualCost").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/projects/" + project.id() + "/usage"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/projects/" + project.id() + "/usage")
                        .with(authentication(asUser(new AdminPrincipal(
                                UUID.randomUUID(), "foreign")))))
                .andExpect(status().isNotFound());
        assertThat(mediaTasks(owner.userId(), project.id(), running.id())).hasSize(3);
        assertThat(runs.get(owner.userId(), project.id(), running.id()).status())
                .isEqualTo(AgentRun.Status.WAITING_TASKS);
        Task pendingResume = tasks.listByRun(owner.userId(), project.id(), running.id()).stream()
                .filter(task -> task.kind() == Task.Kind.AGENT_TURN
                        && task.input().path("stepIndex").asInt(-1) == 1)
                .findFirst().orElseThrow();
        assertThat(pendingResume.status()).isEqualTo(Task.Status.PENDING);
        assertThat(pendingResume.input().path("resumePlanId").asText())
                .isEqualTo(imagePlanId.toString());
        Task secondImage = mediaTasks(owner.userId(), project.id(), running.id()).stream()
                .filter(task -> "step-2".equals(task.stepKey())).findFirst().orElseThrow();
        assertThat(secondImage.status()).isEqualTo(Task.Status.PENDING);

        AtomicReference<Task> completedLease = new AtomicReference<>();
        AtomicReference<ObjectNode> completedContent = new AtomicReference<>();
        TaskWorker worker = new TaskWorker(tasks);
        while (mediaTasks(owner.userId(), project.id(), running.id()).stream()
                .filter(task -> imagePlanId.equals(task.planId()))
                .anyMatch(task -> task.status() != Task.Status.SUCCEEDED)) {
            assertThat(worker.runOnce("plan-worker", 3,
                    lease -> {
                        ObjectNode content = media(lease.id());
                        if (completedLease.get() == null) {
                            completedLease.set(lease);
                            completedContent.set(content);
                        }
                        return new TaskWorker.GeneratedArtifact(content);
                    })).isPositive();
        }
        List<Task> images = mediaTasks(owner.userId(), project.id(), running.id());
        assertThat(jdbc.sql("select count(*) from usage_ledger where project_id = :projectId "
                        + "and task_id is not null and entry_type = 'SETTLEMENT'")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(3);
        Task replayedLease = completedLease.get();
        ObjectNode replayedContent = completedContent.get();
        UUID replayedArtifactId = UUID.fromString(tasks.get(owner.userId(), project.id(),
                replayedLease.id()).output().path("artifactId").asText());
        assertThatThrownBy(() -> tasks.succeedWithArtifact(replayedLease, "plan-worker",
                replayedContent)).isInstanceOf(ApiProblemException.class);
        assertThat(jdbc.sql("select count(*) from artifact_version where artifact_id = :artifactId")
                .param("artifactId", replayedArtifactId).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'SETTLEMENT'")
                .param("taskId", replayedLease.id()).query(Integer.class).single())
                .isEqualTo(1);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(ignored -> usage.settleMediaTask(owner.userId(),
                        images.getFirst()));
        assertThat(jdbc.sql("select count(*) from usage_ledger where project_id = :projectId "
                        + "and task_id is not null and entry_type = 'SETTLEMENT'")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(3);
        mvc.perform(get("/api/v1/projects/" + project.id() + "/runs/" + running.id()
                + "/tasks").with(authentication(asUser(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].kind").value("IMAGE_GENERATION"));
        List<ArtifactService.ArtifactView> keyframes = new ArrayList<>();
        for (Task image : images) {
            keyframes.add(artifacts.get(owner.userId(), project.id(),
                    UUID.fromString(image.output().path("artifactId").asText())));
        }
        assertThat(tasks.get(owner.userId(), project.id(), pendingResume.id()).status())
                .isEqualTo(Task.Status.PENDING);
        assertThat(tasks.claimAgentTurns("image-resume-worker", 1)).isEmpty();
        ObjectNode videoDraft = draft("VIDEO", shots, keyframes);
        assertThatThrownBy(() -> plans.propose(context, videoDraft))
                .isInstanceOf(ApiProblemException.class);
        String selectionPath = "/api/v1/projects/" + project.id() + "/runs/" + running.id()
                + "/shots/" + shots.get(0).artifact().id() + "/keyframe-selection";
        String selectBody = "{\"shotVersionId\":\"" + shots.get(0).currentVersion().id()
                + "\",\"imageArtifactId\":\"" + keyframes.get(0).artifact().id()
                + "\",\"imageVersionId\":\"" + keyframes.get(0).currentVersion().id()
                + "\",\"expectedVersion\":null}";
        mvc.perform(get(selectionPath).with(authentication(asUser(owner))))
                .andExpect(status().isNotFound());
        mvc.perform(put(selectionPath).with(authentication(asUser(owner)))
                .contentType("application/json").content(selectBody))
                .andExpect(status().isForbidden());
        mvc.perform(put(selectionPath).with(authentication(asUser(owner))).with(csrf())
                .contentType("application/json").content(selectBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageVersionId")
                        .value(keyframes.get(0).currentVersion().id().toString()));
        mvc.perform(get(selectionPath).with(authentication(asUser(owner))))
                .andExpect(status().isOk());
        assertThat(tasks.get(owner.userId(), project.id(), pendingResume.id()).status())
                .isEqualTo(Task.Status.PENDING);
        assertThatThrownBy(() -> keyframeSelections.select(owner.userId(), project.id(),
                running.id(), shots.get(0).artifact().id(),
                shots.get(0).currentVersion().id(), keyframes.get(0).artifact().id(),
                keyframes.get(0).currentVersion().id(), 99L))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> keyframeSelections.select(owner.userId(), project.id(),
                running.id(), shots.get(1).artifact().id(),
                shots.get(1).currentVersion().id(), keyframes.get(0).artifact().id(),
                keyframes.get(0).currentVersion().id(), null))
                .isInstanceOf(ApiProblemException.class);
        for (int index = 1; index < shots.size(); index++) {
            keyframesServiceSelect(owner.userId(), project.id(), running.id(),
                    shots.get(index), keyframes.get(index));
            assertThat(tasks.get(owner.userId(), project.id(), pendingResume.id()).status())
                    .isEqualTo(index == shots.size() - 1
                            ? Task.Status.READY : Task.Status.PENDING);
        }
        assertThat(tasks.get(owner.userId(), project.id(), pendingResume.id()).status())
                .isEqualTo(Task.Status.READY);
        Task resumeLease = tasks.claimAgentTurns("image-resume-worker", 1).getFirst();
        assertThat(resumeLease.id()).isEqualTo(pendingResume.id());
        assertThat(turnCommits.start(resumeLease, "image-resume-worker").status())
                .isEqualTo(AgentRun.Status.RUNNING);
        assertThat(resumeContext.append(owner.userId(), project.id(), running.id(),
                resumeLease, conversation.afterToolRound(owner.userId(), project.id(),
                        running.id(), 0)).getLast().getText())
                .contains("APPROVED", "Authenticated human keyframe choices",
                        images.getFirst().output().path("artifactVersionId").asText());
        ExecutionPlan videoPlan = plans.propose(context, videoDraft);
        assertThat(videoPlan.stage()).isEqualTo(ExecutionPlan.Stage.VIDEO);
        assertThat(videoPlan.estimate().path("videoCount").intValue()).isEqualTo(3);
        var videoApproval = plans.approve(owner.userId(), project.id(), videoPlan.id(),
                videoPlan.planHash());
        assertThat(videoApproval.tasks()).hasSize(3);
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and plan_id = :planId and kind = 'VIDEO_GENERATION'")
                .param("projectId", project.id()).param("planId", videoPlan.id())
                .query(Integer.class).single())
                .isEqualTo(videoPlan.estimate().path("videoCount").intValue());
        assertThat(videoApproval.tasks()).allSatisfy(task -> assertThat(task.input()
                .path("imageVersionId").asText()).isNotBlank());
        assertThat(videoApproval.tasks()).allSatisfy(task -> assertThat(task.input()
                .path("durationMs").asInt()).isPositive());
        assertThat(jdbc.sql("select count(*) from usage_ledger where project_id = :projectId "
                        + "and entry_type = 'RESERVATION' and quantity_json ->> 'videoCount' = '1'")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(3);
        assertThat(count("plan_approval")).isEqualTo(2);

        Project changedProject = projects.create(owner.userId(), "Stale approval project",
                Project.AspectRatio.LANDSCAPE_16_9);
        List<ArtifactService.ArtifactView> changedShots = createShots(owner.userId(),
                changedProject.id());
        AgentInstance changedAgent = agents.create(owner.userId(), changedProject.id(),
                "Stale Creator", "Create", changedShots.stream()
                        .map(shot -> new AgentInstanceService.BindingInput(
                                shot.artifact().id(), shot.currentVersion().id())).toList());
        AgentRun changedQueued = runs.create(owner.userId(), changedProject.id(),
                changedAgent.id(), "Propose then edit", "stale-plan-run").run();
        AgentRun changedRunning = runs.transition(owner.userId(), changedProject.id(),
                changedQueued.id(), changedQueued.version(), AgentRun.Status.RUNNING);
        TrustedToolContext changedContext = new TrustedToolContext(owner.userId(),
                changedProject.id(), changedRunning.id());
        ObjectNode foreignDraft = draft("IMAGE", changedShots, List.of());
        ((ObjectNode) foreignDraft.path("steps").get(0))
                .put("shotArtifactId", shots.get(0).artifact().id().toString())
                .put("shotVersionId", shots.get(0).currentVersion().id().toString());
        assertThatThrownBy(() -> plans.propose(changedContext, foreignDraft))
                .isInstanceOf(ApiProblemException.class);
        ExecutionPlan stalePlan = plans.propose(changedContext,
                draft("IMAGE", changedShots, List.of()));
        ObjectNode edited = (ObjectNode) changedShots.get(0).currentVersion().content().deepCopy();
        edited.put("description", "Edited after proposal");
        CountDownLatch editHeld = new CountDownLatch(1);
        CountDownLatch approvalStarted = new CountDownLatch(1);
        CountDownLatch releaseEdit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> edit = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        artifacts.revise(owner.userId(), changedProject.id(),
                                changedShots.get(0).artifact().id(),
                                changedShots.get(0).artifact().version(), null, edited);
                        editHeld.countDown();
                        try {
                            if (!releaseEdit.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Approval race test timed out");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    }));
            Future<?> approval = pool.submit(() -> {
                editHeld.await();
                approvalStarted.countDown();
                return plans.approve(owner.userId(), changedProject.id(),
                        stalePlan.id(), stalePlan.planHash());
            });
            assertThat(approvalStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseEdit.countDown();
            edit.get();
            assertThatThrownBy(approval::get).hasCauseInstanceOf(ApiProblemException.class);
        } finally {
            releaseEdit.countDown();
        }
        assertThat(mediaTasks(owner.userId(), changedProject.id(), changedRunning.id()))
                .isEmpty();
        assertThat(plans.reject(owner.userId(), changedProject.id(), stalePlan.id()).status())
                .isEqualTo(ExecutionPlan.Status.REJECTED);
        assertThat(runs.get(owner.userId(), changedProject.id(), changedRunning.id()).status())
                .isEqualTo(AgentRun.Status.RUNNING);

        Project budgetProject = projects.create(owner.userId(), "Media budget project",
                Project.AspectRatio.LANDSCAPE_16_9);
        List<ArtifactService.ArtifactView> budgetShots = createShots(owner.userId(),
                budgetProject.id());
        AgentInstance budgetAgent = agents.create(owner.userId(), budgetProject.id(),
                "Budget Creator", "Create", budgetShots.stream()
                        .map(shot -> new AgentInstanceService.BindingInput(
                                shot.artifact().id(), shot.currentVersion().id())).toList());
        AgentRun budgetQueued = runs.create(owner.userId(), budgetProject.id(),
                budgetAgent.id(), "Check image quota", "budget-plan-run").run();
        AgentRun budgetRunning = runs.transition(owner.userId(), budgetProject.id(),
                budgetQueued.id(), budgetQueued.version(), AgentRun.Status.RUNNING);
        TrustedToolContext budgetContext = new TrustedToolContext(owner.userId(),
                budgetProject.id(), budgetRunning.id());
        ObjectNode sixImages = draft("IMAGE", budgetShots, List.of());
        ArrayNode sixSteps = (ArrayNode) sixImages.path("steps");
        for (int index = 0; index < 3; index++) {
            ObjectNode repeated = (ObjectNode) sixSteps.get(index).deepCopy();
            repeated.put("stepKey", "step-" + (index + 4));
            repeated.put("outputSlotKey", "slot-" + (index + 4));
            sixSteps.add(repeated);
        }
        ExecutionPlan firstBudgetPlan = plans.propose(budgetContext, sixImages);
        plans.approve(owner.userId(), budgetProject.id(), firstBudgetPlan.id(),
                firstBudgetPlan.planHash());
        AgentRun budgetWaiting = runs.get(owner.userId(), budgetProject.id(), budgetRunning.id());
        runs.transition(owner.userId(), budgetProject.id(), budgetRunning.id(),
                budgetWaiting.version(), AgentRun.Status.RUNNING);
        ExecutionPlan overBudget = plans.propose(budgetContext,
                draft("IMAGE", budgetShots, List.of()));
        assertThatThrownBy(() -> plans.approve(owner.userId(), budgetProject.id(),
                overBudget.id(), overBudget.planHash()))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.code()).isEqualTo("PLAN_CONFLICT"));
        assertThat(mediaTasks(owner.userId(), budgetProject.id(), budgetRunning.id()))
                .hasSize(6);
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into usage_ledger (id, project_id, run_id, task_id,
                            operation_key, entry_type, quantity_json, cost_status,
                            cost_source, created_at)
                        select :id, :foreignProject, run_id, task_id, :operationKey,
                            entry_type, quantity_json, cost_status, cost_source, now()
                        from usage_ledger where project_id = :originalProject limit 1
                        """)
                .param("id", UUID.randomUUID())
                .param("foreignProject", budgetProject.id())
                .param("operationKey", "cross-project-test-" + UUID.randomUUID())
                .param("originalProject", project.id()).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        AgentRun claimable = runs.get(owner.userId(), budgetProject.id(), budgetRunning.id());
        runs.transition(owner.userId(), budgetProject.id(), budgetRunning.id(),
                claimable.version(), AgentRun.Status.RUNNING);
        Task neverSubmitted = tasks.claimImagesDue("preflight-failure-worker", 1).getFirst();
        assertThat(neverSubmitted.runId()).isEqualTo(budgetRunning.id());
        tasks.fail(neverSubmitted, "preflight-failure-worker", "PROVIDER_CONFIG_CHANGED");
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :taskId")
                .param("taskId", neverSubmitted.id()).query(Integer.class).single()).isZero();
        assertThat(mediaReleaseCount(neverSubmitted.id())).isEqualTo(1);
        Task claimedButUnsent = tasks.claimImagesDue("interrupted-preflight-worker", 1)
                .getFirst();
        runs.cancel(owner.userId(), budgetProject.id(), budgetRunning.id());
        assertThat(mediaReleaseCount(claimedButUnsent.id())).isZero();
        assertThat(jdbc.sql("select count(*) from usage_ledger "
                        + "where project_id = :projectId and entry_type = 'RELEASE' "
                        + "and operation_key like 'media:%'")
                .param("projectId", budgetProject.id()).query(Integer.class).single())
                .isEqualTo(5);
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :taskId")
                .param("taskId", claimedButUnsent.id()).update();
        assertThat(tasks.recoverExpiredCancellations(16)).isEqualTo(1);
        assertThat(mediaReleaseCount(claimedButUnsent.id())).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from usage_ledger "
                        + "where project_id = :projectId and entry_type = 'RELEASE' "
                        + "and operation_key like 'media:%'")
                .param("projectId", budgetProject.id()).query(Integer.class).single())
                .isEqualTo(6);
        runs.cancel(owner.userId(), budgetProject.id(), budgetRunning.id());
        assertThat(jdbc.sql("select count(*) from usage_ledger "
                        + "where project_id = :projectId and entry_type = 'RELEASE' "
                        + "and operation_key like 'media:%'")
                .param("projectId", budgetProject.id()).query(Integer.class).single())
                .isEqualTo(6);
        assertThat(jdbc.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                .query(String.class).single()).isEqualTo("35");
    }

    /** A no-submission terminal media task closes exactly one unknown-cost reservation. */
    private int mediaReleaseCount(UUID taskId) {
        return jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'RELEASE' and cost_status = 'UNKNOWN' "
                        + "and actual_cost is null")
                .param("taskId", taskId).query(Integer.class).single();
    }

    private void keyframesServiceSelect(UUID ownerId, UUID projectId, UUID runId,
            ArtifactService.ArtifactView shot, ArtifactService.ArtifactView image) {
        keyframeSelections.select(ownerId, projectId, runId, shot.artifact().id(),
                shot.currentVersion().id(), image.artifact().id(),
                image.currentVersion().id(), null);
    }

    private List<Task> mediaTasks(UUID ownerId, UUID projectId, UUID runId) {
        return tasks.listByRun(ownerId, projectId, runId).stream()
                .filter(task -> task.kind() == Task.Kind.IMAGE_GENERATION
                        || task.kind() == Task.Kind.VIDEO_GENERATION)
                .toList();
    }

    private UsernamePasswordAuthenticationToken asUser(AdminPrincipal owner) {
        return new UsernamePasswordAuthenticationToken(owner, null, List.of());
    }

    private List<ArtifactService.ArtifactView> createShots(UUID ownerId, UUID projectId) {
        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "Ridge");
        scene.put("location", "Ridge");
        scene.put("timeOfDay", "Dawn");
        scene.put("lighting", "Soft");
        scene.put("style", "Cinematic");
        scene.putArray("referenceVersionIds");
        UUID sceneVersion = artifacts.create(ownerId, projectId, Artifact.Kind.SCENE,
                "Ridge", scene).currentVersion().id();
        List<ArtifactService.ArtifactView> result = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            ObjectNode content = mapper.createObjectNode();
            content.put("order", index);
            content.put("durationMs", 1_000);
            content.put("description", "Shot " + index);
            content.put("camera", "Wide");
            content.put("action", "Move");
            content.putArray("characterVersionIds");
            content.put("sceneVersionId", sceneVersion.toString());
            result.add(artifacts.create(ownerId, projectId, Artifact.Kind.SHOT,
                    "Shot " + index, content));
        }
        return result;
    }

    private ObjectNode draft(String stage, List<ArtifactService.ArtifactView> shots,
            List<ArtifactService.ArtifactView> images) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("stage", stage);
        draft.put("objective", "Three-shot generation");
        ArrayNode steps = draft.putArray("steps");
        for (int index = 0; index < shots.size(); index++) {
            ObjectNode step = steps.addObject();
            step.put("stepKey", "step-" + (index + 1));
            step.put("outputSlotKey", "slot-" + (index + 1));
            step.put("shotArtifactId", shots.get(index).artifact().id().toString());
            step.put("shotVersionId", shots.get(index).currentVersion().id().toString());
            if ("VIDEO".equals(stage)) {
                step.put("imageArtifactId", images.get(index).artifact().id().toString());
                step.put("imageVersionId", images.get(index).currentVersion().id().toString());
            }
            step.put("prompt", "Frame " + index);
            step.putArray("dependsOnStepKeys");
        }
        return draft;
    }

    private ObjectNode media(UUID taskId) {
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", ImageAssetFixture.archive(assets, mediaOwnerId,
                mediaProjectId).toString());
        content.put("prompt", "Mock frame");
        content.put("providerConfigVersion", 1);
        content.put("workflowVersion", "mock-image-v1");
        content.putObject("parameters");
        content.put("sourceTaskId", taskId.toString());
        return content;
    }

    private long count(String table) {
        return switch (table) {
            case "execution_plan" -> jdbc.sql("select count(*) from execution_plan")
                    .query(Long.class).single();
            case "plan_approval" -> jdbc.sql("select count(*) from plan_approval")
                    .query(Long.class).single();
            default -> throw new IllegalArgumentException("Unsupported test table");
        };
    }
}
