package dev.agenvas.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.llm.application.InitialModelContextService;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.UUID;
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

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=media-revision-integration-secret",
        "agenvas.llm.scheduler-enabled=false"})
class MediaPlanRevisionPostgresIT {

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
    @Autowired private MediaCapabilityService capabilities;
    @Autowired private InitialModelContextService initialContext;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void revisedStepPinsAnotherCapabilityAndMissingVideoInputCannotBeApproved() {
        AdminPrincipal owner = identities.setup("media-revision-integration-secret",
                "revision-admin", "revision-password-123");
        Project project = projects.create(owner.userId(), "Image plan revision",
                Project.AspectRatio.LANDSCAPE_16_9);
        var shot = shot(owner.userId(), project.id());
        AgentRun running = running(owner, project, shot, "image-run");
        ObjectNode draft = draft("IMAGE", shot);
        ObjectNode step = (ObjectNode) draft.path("steps").get(0);
        step.put("endpoint", "https://attacker.example");
        assertThatThrownBy(() -> plans.propose(new TrustedToolContext(owner.userId(),
                project.id(), running.id()), draft)).isInstanceOf(ApiProblemException.class);
        step.remove("endpoint");
        step.put("capabilityVersion", 999);
        assertThatThrownBy(() -> plans.propose(new TrustedToolContext(owner.userId(),
                project.id(), running.id()), draft)).isInstanceOf(ApiProblemException.class);
        step.remove("capabilityVersion");

        ExecutionPlan original = plans.propose(new TrustedToolContext(owner.userId(),
                project.id(), running.id()), draft);
        assertThat(original.steps().getFirst().binding()).isNotNull();
        UUID mockConnection = original.steps().getFirst().binding().connectionId();
        UUID alternative = capabilities.publishCapability(mockConnection, "Alternative image",
                "MOCK_IMAGE").id();
        assertThat(initialContext.assemble(owner.userId(), project.id(), running.id())
                .stream().map(message -> message.getText()).toList())
                .anySatisfy(text -> assertThat(text).contains("capabilityId=" + alternative,
                        "kind=IMAGE_GENERATION", "Alternative image")
                        .doesNotContain("credential", "127.0.0.1"));
        ExecutionPlan revised = plans.reviseStep(owner.userId(), project.id(), original.id(),
                "shot-1", alternative, mapper.createObjectNode(), original.planHash());
        assertThat(revised.id()).isNotEqualTo(original.id());
        assertThat(revised.planHash()).isNotEqualTo(original.planHash());
        assertThat(revised.revision()).isEqualTo(original.revision() + 1);
        assertThat(revised.steps().getFirst().binding().capabilityId()).isEqualTo(alternative);
        assertThat(plans.get(owner.userId(), project.id(), original.id()).status())
                .isEqualTo(ExecutionPlan.Status.STALE);
        assertThatThrownBy(() -> plans.approve(owner.userId(), project.id(), revised.id(),
                revised.planHash(), List.of())).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> plans.approve(owner.userId(), project.id(), revised.id(),
                original.planHash(), List.of("shot-1"))).isInstanceOf(ApiProblemException.class);
        assertThat(jdbc.sql("select count(*) from task where plan_id=:id")
                .param("id", revised.id()).query(Integer.class).single()).isZero();

        Project videoProject = projects.create(owner.userId(), "Video needs input",
                Project.AspectRatio.LANDSCAPE_16_9);
        var videoShot = shot(owner.userId(), videoProject.id());
        AgentRun videoRun = running(owner, videoProject, videoShot, "video-run");
        ExecutionPlan missing = plans.propose(new TrustedToolContext(owner.userId(),
                videoProject.id(), videoRun.id()), draft("VIDEO", videoShot));
        assertThat(missing.status()).isEqualTo(ExecutionPlan.Status.NEEDS_INPUT);
        assertThat(missing.steps().getFirst().input().path("durationSeconds").intValue())
                .isEqualTo(4);
        assertThat(plans.candidates(owner.userId(), videoProject.id(), missing.id(),
                "shot-1")).isNotEmpty();
        assertThatThrownBy(() -> plans.approve(owner.userId(), videoProject.id(), missing.id(),
                missing.planHash(), List.of("shot-1"))).isInstanceOf(ApiProblemException.class);
        assertThat(jdbc.sql("select count(*) from task where plan_id=:id")
                .param("id", missing.id()).query(Integer.class).single()).isZero();
    }

    private AgentRun running(AdminPrincipal owner, Project project,
            ArtifactService.ArtifactView shot, String key) {
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of(new AgentInstanceService.BindingInput(shot.artifact().id(),
                        shot.currentVersion().id())));
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Generate media", key).run();
        return runs.transition(owner.userId(), project.id(), queued.id(), queued.version(),
                AgentRun.Status.RUNNING);
    }

    private ArtifactService.ArtifactView shot(UUID ownerId, UUID projectId) {
        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "Street");
        scene.put("location", "Shanghai");
        scene.put("timeOfDay", "Dawn");
        scene.put("lighting", "Soft");
        scene.put("style", "Cinematic");
        scene.putArray("referenceVersionIds");
        UUID sceneVersion = artifacts.create(ownerId, projectId, Artifact.Kind.SCENE,
                "Scene", scene).currentVersion().id();
        ObjectNode content = mapper.createObjectNode();
        content.put("order", 1);
        content.put("durationSeconds", 4);
        content.put("description", "One shot");
        content.put("camera", "Wide");
        content.put("action", "Walk");
        content.putArray("characterVersionIds");
        content.put("sceneVersionId", sceneVersion.toString());
        return artifacts.create(ownerId, projectId, Artifact.Kind.SHOT, "Shot", content);
    }

    private ObjectNode draft(String stage, ArtifactService.ArtifactView shot) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("stage", stage);
        draft.put("objective", "One media step");
        ObjectNode step = draft.putArray("steps").addObject();
        step.put("stepKey", "shot-1");
        step.put("outputSlotKey", "shot-1-output");
        step.put("shotArtifactId", shot.artifact().id().toString());
        step.put("shotVersionId", shot.currentVersion().id().toString());
        step.put("prompt", "A cinematic Shanghai street at dawn");
        step.putArray("dependsOnStepKeys");
        return draft;
    }
}
