package dev.agenvas.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.plan.application.PlanProviderProperties;
import dev.agenvas.plan.application.PlanWorkflowPolicy;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real-database proof that plan approval is bound to server-owned media versions. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=plan-version-integration-secret")
class PlanApprovalVersionPostgresIT {

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
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ExecutionPlanService plans;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlanProviderProperties provider;
    @Autowired private PlanWorkflowPolicy workflows;
    @Autowired private MediaCapabilityService capabilities;

    @Test
    void disabledBoundConnectionRejectsApprovalWithoutSideEffects() {
        AdminPrincipal owner = identities.setup("plan-version-integration-secret",
                "plan-version-admin", "plan-password-123");
        Project project = projects.create(owner.userId(), "Plan version project",
                Project.AspectRatio.LANDSCAPE_16_9);
        ArtifactService.ArtifactView shot = createShot(owner.userId(), project.id());
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of(new AgentInstanceService.BindingInput(shot.artifact().id(),
                        shot.currentVersion().id())));
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Generate one keyframe", "plan-version-run").run();
        AgentRun running = runs.transition(owner.userId(), project.id(), queued.id(),
                queued.version(), AgentRun.Status.RUNNING);
        ExecutionPlan plan = plans.propose(new TrustedToolContext(owner.userId(), project.id(),
                running.id()), draft(shot));
        assertThat(plan.providerConfigVersion()).isEqualTo(provider.configVersion());
        assertThat(plan.workflowVersion()).isEqualTo(workflows.version(ExecutionPlan.Stage.IMAGE));

        UUID connectionId = plan.steps().getFirst().binding().connectionId();
        var connection = capabilities.getConnection(connectionId);
        capabilities.setConnectionEnabled(connectionId, connection.version(), false);
        assertRejectedWithoutSideEffects(owner.userId(), project.id(), running.id(), plan);
        capabilities.setConnectionEnabled(connectionId,
                capabilities.getConnection(connectionId).version(), true);

        assertThat(plans.approve(owner.userId(), project.id(), plan.id(), plan.planHash(), plans.get(owner.userId(), project.id(), plan.id()).steps().stream().map(dev.agenvas.plan.application.ExecutionPlan.Step::stepKey).toList())
                .tasks()).hasSize(1);
    }

    /** A stale approval remains pending and cannot reserve quota or enqueue media. */
    private void assertRejectedWithoutSideEffects(UUID ownerId, UUID projectId, UUID runId,
            ExecutionPlan plan) {
        assertThatThrownBy(() -> plans.approve(ownerId, projectId, plan.id(), plan.planHash(), plans.get(ownerId, projectId, plan.id()).steps().stream().map(dev.agenvas.plan.application.ExecutionPlan.Step::stepKey).toList()))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.code()).isEqualTo("PLAN_CONFLICT"));
        assertThat(plans.get(ownerId, projectId, plan.id()).status())
                .isEqualTo(ExecutionPlan.Status.PENDING);
        assertThat(runs.get(ownerId, projectId, runId).status())
                .isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        assertThat(tasks.listByRun(ownerId, projectId, runId)).noneMatch(task ->
                task.planId() != null);
        assertThat(jdbc.sql("select count(*) from plan_approval where project_id = :projectId")
                .param("projectId", projectId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from usage_ledger where project_id = :projectId "
                        + "and task_id is not null and entry_type = 'RESERVATION'")
                .param("projectId", projectId).query(Long.class).single()).isZero();
    }

    /** Creates valid same-project scene and shot inputs without a media Provider. */
    private ArtifactService.ArtifactView createShot(UUID ownerId, UUID projectId) {
        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "Ridge");
        scene.put("location", "Ridge");
        scene.put("timeOfDay", "Dawn");
        scene.put("lighting", "Soft");
        scene.put("style", "Cinematic");
        scene.putArray("referenceVersionIds");
        UUID sceneVersion = artifacts.create(ownerId, projectId, Artifact.Kind.SCENE,
                "Ridge", scene).currentVersion().id();
        ObjectNode content = mapper.createObjectNode();
        content.put("order", 1);
        content.put("durationSeconds", 1);
        content.put("description", "One shot");
        content.put("camera", "Wide");
        content.put("action", "Move");
        content.putArray("characterVersionIds");
        content.put("sceneVersionId", sceneVersion.toString());
        return artifacts.create(ownerId, projectId, Artifact.Kind.SHOT, "Shot 1", content);
    }

    /** The model controls only proposal fields, not configuration versions. */
    private ObjectNode draft(ArtifactService.ArtifactView shot) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("stage", "IMAGE");
        draft.put("objective", "One keyframe");
        ObjectNode step = draft.putArray("steps").addObject();
        step.put("stepKey", "shot-1-image");
        step.put("outputSlotKey", "shot-1-image-output");
        step.put("shotArtifactId", shot.artifact().id().toString());
        step.put("shotVersionId", shot.currentVersion().id().toString());
        step.put("prompt", "A wide dawn ridge frame");
        step.putArray("dependsOnStepKeys");
        return draft;
    }
}
