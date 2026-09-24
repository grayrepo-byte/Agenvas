package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.ShotRedoService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import dev.agenvas.llm.application.TrustedToolContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** PostgreSQL proof that a revised shot fences old work before and after provider submission. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=stale-shot-integration-secret")
class TaskStaleShotPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ArtifactService artifacts;
    @Autowired private ShotRedoService redo;
    @Autowired private TaskService tasks;
    @Autowired private ExecutionPlanService plans;
    @Autowired private AssetService assets;
    @Autowired private CanvasService canvas;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;

    @Test
    void staleUnsubmittedTaskNeverCallsProviderAndLateResultStaysHistorical() {
        AdminPrincipal owner = identities.setup("stale-shot-integration-secret",
                "stale-admin", "stale-password-123");
        Project project = projects.create(owner.userId(), "Stale shot",
                Project.AspectRatio.LANDSCAPE_16_9);
        ObjectNode sceneContent = mapper.createObjectNode();
        sceneContent.put("name", "Studio");
        sceneContent.put("location", "Shanghai");
        sceneContent.put("timeOfDay", "Day");
        sceneContent.put("lighting", "Soft");
        sceneContent.put("style", "Minimal");
        sceneContent.putArray("referenceVersionIds");
        var scene = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SCENE,
                "Scene", sceneContent);
        ObjectNode shotContent = mapper.createObjectNode();
        shotContent.put("order", 1);
        shotContent.put("durationMs", 3000);
        shotContent.put("description", "Original");
        shotContent.put("camera", "Wide");
        shotContent.put("action", "Walk");
        shotContent.putArray("characterVersionIds");
        shotContent.put("sceneVersionId", scene.currentVersion().id().toString());
        var shot = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SHOT,
                "Shot", shotContent);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Create", "stale-run").run();
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);

        Task old = tasks.createMediaTaskForNewOutput(owner.userId(), project.id(), run.id(),
                null, "old-image", Task.Kind.IMAGE_GENERATION,
                input(shot.artifact().id(), shot.currentVersion().id()), null, 1,
                List.of(), "old-image-output");
        var revised = revise(owner.userId(), project.id(), shot, "Changed");
        AtomicInteger submissions = new AtomicInteger();
        new TaskWorker(tasks).runImagesOnce("stale-worker", 1, (task, requestKey) -> {
            submissions.incrementAndGet();
            return new TaskWorker.Failed("UNEXPECTED_SUBMISSION");
        });
        assertThat(submissions).hasValue(0);
        Task rejected = tasks.get(owner.userId(), project.id(), old.id());
        assertThat(rejected.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(rejected.errorCode()).isEqualTo("TASK_INPUT_STALE");
        assertThat(rejected.completedAt()).isNull();
        assertThat(rejected.providerRequestId()).isNull();

        Task submitted = tasks.createMediaTaskForNewOutput(owner.userId(), project.id(),
                run.id(), null, "late-image", Task.Kind.IMAGE_GENERATION,
                input(shot.artifact().id(), revised.currentVersion().id()), null, 1,
                List.of(), "late-image-output");
        Task dependent = tasks.create(owner.userId(), project.id(), run.id(), null,
                "late-dependent", Task.Kind.IMAGE_GENERATION, mapper.createObjectNode(),
                null, 1, List.of(submitted.id()));
        Task lease = tasks.claimImagesDue("late-worker", 1).getFirst();
        tasks.beginSubmission(lease, "late-worker");
        var newest = revise(owner.userId(), project.id(), revised, "Changed again");
        ObjectNode lateContent = mapper.createObjectNode();
        lateContent.put("assetId", ImageAssetFixture.archive(assets, owner.userId(),
                project.id()).toString());
        lateContent.put("prompt", "Old version");
        lateContent.put("providerConfigVersion", 1);
        lateContent.put("workflowVersion", "mock-image-v1");
        lateContent.putObject("parameters");
        lateContent.put("sourceTaskId", submitted.id().toString());
        var archived = tasks.succeedWithArtifact(lease, "late-worker", lateContent);
        assertThat(archived.selected()).isFalse();
        assertThat(tasks.get(owner.userId(), project.id(), submitted.id()).status())
                .isEqualTo(Task.Status.SUCCEEDED);
        assertThat(tasks.get(owner.userId(), project.id(), dependent.id()).status())
                .isEqualTo(Task.Status.PENDING);
        UUID artifactId = UUID.fromString(tasks.get(owner.userId(), project.id(),
                submitted.id()).output().path("artifactId").asText());
        assertThat(canvas.list(owner.userId(), project.id())).noneMatch(entry ->
                entry.item().subjectType() == CanvasItem.SubjectType.ARTIFACT
                        && entry.item().subjectId().equals(artifactId));

        Task async = tasks.createMediaTaskForNewOutput(owner.userId(), project.id(),
                run.id(), null, "async-image", Task.Kind.IMAGE_GENERATION,
                input(shot.artifact().id(), newest.currentVersion().id()), null, 1,
                List.of(), "async-image-output");
        Task submitting = tasks.claimImagesDue("async-submitter", 1).getFirst();
        tasks.beginSubmission(submitting, "async-submitter");
        UUID externalPromptId = UUID.randomUUID();
        tasks.waitForProvider(submitting, "async-submitter", externalPromptId.toString(),
                Instant.now().plusSeconds(60));
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                .param("id", async.id()).update();
        ObjectNode completed = mapper.createObjectNode();
        completed.put("assetId", ImageAssetFixture.archive(assets, owner.userId(),
                project.id()).toString());
        completed.put("prompt", "Current version");
        completed.put("providerConfigVersion", 1);
        completed.put("workflowVersion", "image-v1");
        completed.putObject("parameters");
        completed.put("sourceTaskId", async.id().toString());
        assertThat(new TaskWorker(tasks).runProviderPollsOnce("async-poller", 1, poll -> {
            assertThat(poll.providerRequestId()).isEqualTo(externalPromptId.toString());
            return new TaskWorker.PollGenerated(completed);
        })).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), async.id()).status())
                .isEqualTo(Task.Status.SUCCEEDED);
        assertThat(tasks.get(owner.userId(), project.id(), async.id()).output()
                .path("selected").booleanValue()).isTrue();
        assertThat(tasks.get(owner.userId(), project.id(), dependent.id()).status())
                .isEqualTo(Task.Status.PENDING);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", async.id()).query(Integer.class).single()).isEqualTo(1);

        Task canceledAwaiting = tasks.createMediaTaskForNewOutput(owner.userId(),
                project.id(), run.id(), null, "canceled-awaiting", Task.Kind.IMAGE_GENERATION,
                input(shot.artifact().id(), newest.currentVersion().id()), null, 1,
                List.of(), "canceled-output");
        Task canceledSubmit = tasks.claimImagesDue("cancel-submitter", 1).getFirst();
        tasks.beginSubmission(canceledSubmit, "cancel-submitter");
        tasks.waitForProvider(canceledSubmit, "cancel-submitter", UUID.randomUUID().toString(),
                Instant.now().plusSeconds(60));
        runs.cancel(owner.userId(), project.id(), run.id());
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                .param("id", canceledAwaiting.id()).update();
        assertThat(tasks.get(owner.userId(), project.id(), canceledAwaiting.id()).status())
                .isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(jdbc.sql("select count(*) from task where id = :id "
                        + "and provider_request_id is not null "
                        + "and status = 'WAITING_PROVIDER' and next_action_at <= now()")
                .param("id", canceledAwaiting.id()).query(Integer.class).single()).isEqualTo(1);
        ObjectNode canceledOutput = completed.deepCopy();
        canceledOutput.put("sourceTaskId", canceledAwaiting.id().toString());
        canceledOutput.put("assetId", ImageAssetFixture.archive(assets, owner.userId(),
                project.id()).toString());
        assertThat(new TaskWorker(tasks).runProviderPollsOnce("canceled-poller", 1,
                poll -> new TaskWorker.PollGenerated(canceledOutput))).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), canceledAwaiting.id()).status())
                .isEqualTo(Task.Status.CANCELED);
        assertThat(jdbc.sql("select count(*) from task_late_result where task_id = :id")
                .param("id", canceledAwaiting.id()).query(Integer.class).single()).isEqualTo(1);

        Project plannedProject = projects.create(owner.userId(), "Stale approved plan",
                Project.AspectRatio.LANDSCAPE_16_9);
        var plannedScene = artifacts.create(owner.userId(), plannedProject.id(),
                Artifact.Kind.SCENE, "Scene", sceneContent);
        List<ArtifactService.ArtifactView> plannedShots = new java.util.ArrayList<>();
        for (int order = 1; order <= 3; order++) {
            ObjectNode content = shotContent.deepCopy();
            content.put("order", order);
            content.put("sceneVersionId", plannedScene.currentVersion().id().toString());
            plannedShots.add(artifacts.create(owner.userId(), plannedProject.id(),
                    Artifact.Kind.SHOT, "Shot " + order, content));
        }
        AgentInstance plannedAgent = agents.create(owner.userId(), plannedProject.id(),
                "Planned Creator", "Create", plannedShots.stream()
                        .map(item -> new AgentInstanceService.BindingInput(
                                item.artifact().id(), item.currentVersion().id())).toList());
        AgentRun plannedRun = runs.create(owner.userId(), plannedProject.id(),
                plannedAgent.id(), "Generate keyframes", "planned-stale-run").run();
        runs.transition(owner.userId(), plannedProject.id(), plannedRun.id(),
                plannedRun.version(), AgentRun.Status.RUNNING);
        ExecutionPlan imagePlan = plans.propose(new TrustedToolContext(owner.userId(),
                plannedProject.id(), plannedRun.id()), imageDraft(plannedShots));
        var approval = plans.approve(owner.userId(), plannedProject.id(), imagePlan.id(),
                imagePlan.planHash());
        assertThat(approval.tasks()).hasSize(3);
        var obsoleteShot = plannedShots.getFirst();
        var replacement = revise(owner.userId(), plannedProject.id(), obsoleteShot,
                "Edited after approval");
        Task obsoleteLease = tasks.claimImagesDue("planned-stale-worker", 3).stream()
                .filter(item -> item.input().path("shotArtifactId").asText()
                        .equals(obsoleteShot.artifact().id().toString()))
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> tasks.beginSubmission(obsoleteLease, "planned-stale-worker"))
                .isInstanceOfSatisfying(dev.agenvas.shared.error.ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("TASK_INPUT_STALE"));
        tasks.blockStaleInput(obsoleteLease, "planned-stale-worker");
        Task blocked = tasks.get(owner.userId(), plannedProject.id(), obsoleteLease.id());
        assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(blocked.providerRequestId()).isNull();
        assertThat(runs.get(owner.userId(), plannedProject.id(), plannedRun.id()).status())
                .isEqualTo(AgentRun.Status.BLOCKED);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                        + "and entry_type = 'RELEASE'")
                .param("taskId", obsoleteLease.id()).query(Integer.class).single())
                .isEqualTo(1);

        runs.cancel(owner.userId(), plannedProject.id(), plannedRun.id());
        AgentInstance rebound = agents.update(owner.userId(), plannedProject.id(),
                plannedAgent.id(), plannedAgent.version(), plannedAgent.name(),
                plannedAgent.instruction(), List.of(
                        new AgentInstanceService.BindingInput(replacement.artifact().id(),
                                replacement.currentVersion().id()),
                        new AgentInstanceService.BindingInput(plannedShots.get(1).artifact().id(),
                                plannedShots.get(1).currentVersion().id()),
                        new AgentInstanceService.BindingInput(plannedShots.get(2).artifact().id(),
                                plannedShots.get(2).currentVersion().id())));
        assertThat(rebound.version()).isGreaterThan(plannedAgent.version());
        AgentRun fresh = runs.create(owner.userId(), plannedProject.id(), plannedAgent.id(),
                "Regenerate from edited shot", "fresh-stale-run").run();
        runs.transition(owner.userId(), plannedProject.id(), fresh.id(), fresh.version(),
                AgentRun.Status.RUNNING);
        ExecutionPlan freshPlan = plans.propose(new TrustedToolContext(owner.userId(),
                plannedProject.id(), fresh.id()),
                imageDraft(List.of(replacement, plannedShots.get(1), plannedShots.get(2))));
        assertThat(tasks.listByRun(owner.userId(), plannedProject.id(), fresh.id())).noneMatch(
                item -> item.kind() == Task.Kind.IMAGE_GENERATION);
        var freshApproval = plans.approve(owner.userId(), plannedProject.id(), freshPlan.id(),
                freshPlan.planHash());
        assertThat(freshApproval.tasks()).hasSize(3);
        assertThat(freshApproval.tasks()).anySatisfy(item -> assertThat(item.input()
                .path("shotVersionId").asText())
                .isEqualTo(replacement.currentVersion().id().toString()));

        // Archiving an approved project must release every still-unsubmitted reservation.
        projects.archive(owner.userId(), plannedProject.id(),
                projects.get(owner.userId(), plannedProject.id()).version());
        AtomicInteger archivedSubmissions = new AtomicInteger();
        assertThat(new TaskWorker(tasks).runImagesOnce("archived-plan-worker", 3,
                (task, requestKey) -> {
                    archivedSubmissions.incrementAndGet();
                    return new TaskWorker.Failed("UNEXPECTED_SUBMISSION");
                })).isEqualTo(3);
        assertThat(archivedSubmissions).hasValue(0);
        for (Task approvedTask : freshApproval.tasks()) {
            Task archivedTask = tasks.get(owner.userId(), plannedProject.id(), approvedTask.id());
            assertThat(archivedTask.status()).isEqualTo(Task.Status.BLOCKED);
            assertThat(archivedTask.errorCode()).isEqualTo("TASK_PROJECT_ARCHIVED");
            assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :taskId")
                    .param("taskId", approvedTask.id()).query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("select count(*) from usage_ledger where task_id = :taskId "
                            + "and entry_type = 'RELEASE'")
                    .param("taskId", approvedTask.id()).query(Integer.class).single())
                    .isEqualTo(1);
        }
    }

    /** Three exact shots are the P0 approval boundary, even when one was locally revised. */
    private ObjectNode imageDraft(List<ArtifactService.ArtifactView> shots) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("stage", "IMAGE");
        draft.put("objective", "Three-shot keyframes");
        var steps = draft.putArray("steps");
        for (int index = 0; index < shots.size(); index++) {
            var step = steps.addObject();
            step.put("stepKey", "step-" + (index + 1));
            step.put("outputSlotKey", "slot-" + (index + 1));
            step.put("shotArtifactId", shots.get(index).artifact().id().toString());
            step.put("shotVersionId", shots.get(index).currentVersion().id().toString());
            step.put("prompt", "Frame " + index);
            step.putArray("dependsOnStepKeys");
        }
        return draft;
    }

    private ObjectNode input(UUID shotId, UUID shotVersionId) {
        ObjectNode input = mapper.createObjectNode();
        input.put("shotArtifactId", shotId.toString());
        input.put("shotVersionId", shotVersionId.toString());
        return input;
    }

    private ArtifactService.ArtifactView revise(UUID ownerId, UUID projectId,
            ArtifactService.ArtifactView shot, String description) {
        return redo.revise(ownerId, projectId, shot.artifact().id(),
                new ShotRedoService.Request(shot.currentVersion().id(),
                        shot.artifact().version(), description, "Close", "Walk", null, null))
                .shot();
    }
}
