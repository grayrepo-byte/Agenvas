package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Model text cannot promote identity, approval or budget into trusted tool authority. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=tool-authority-integration-secret")
class ToolAuthorityPostgresIT {

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
    @Autowired private LlmTurnCheckpointService checkpoints;
    @Autowired private LlmProtocolCodec codec;
    @Autowired private ToolRegistry registry;
    @Autowired private ToolExecutionService executor;
    @Autowired private ExecutionPlanService plans;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void forgedToolFieldsAndApprovalNameCannotCreateMediaWork() {
        AdminPrincipal owner = identities.setup("tool-authority-integration-secret",
                "tool-authority-admin", "tool-password-123");
        Project project = projects.create(owner.userId(), "Tool authority project",
                Project.AspectRatio.LANDSCAPE_16_9);
        ArtifactService.ArtifactView shot = createShot(owner.userId(), project.id());
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "Create",
                List.of(new AgentInstanceService.BindingInput(shot.artifact().id(),
                        shot.currentVersion().id())));
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(),
                "Propose one image", "authority-run").run();
        AgentRun running = runs.transition(owner.userId(), project.id(), queued.id(),
                queued.version(), AgentRun.Status.RUNNING);
        TrustedToolContext trusted = new TrustedToolContext(owner.userId(), project.id(),
                running.id());
        ObjectNode validDraft = draft(shot);

        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String field : List.of("ownerId", "userId", "projectId", "approved",
                "maxImages", "maxVideos", "budget")) {
            ObjectNode forged = validDraft.deepCopy();
            if (field.startsWith("max")) forged.put(field, 999);
            else if ("approved".equals(field)) forged.put(field, true);
            else forged.put(field, UUID.randomUUID().toString());
            calls.add(new AssistantMessage.ToolCall("forged-" + field, "function",
                    "propose_generation_plan", forged.toString()));
        }
        calls.add(new AssistantMessage.ToolCall("forged-approval", "function",
                "approve_plan", "{\"approved\":true}"));
        calls.add(new AssistantMessage.ToolCall("valid-proposal", "function",
                "propose_generation_plan", validDraft.toString()));
        int configVersion = running.policySnapshot().path("modelConfigVersion").asInt();
        String configSource = running.policySnapshot().path("modelConfigSource").asText();
        checkpoints.reserve(owner.userId(), project.id(), running.id(), 0,
                configVersion, configSource,
                codec.request(List.of(new UserMessage("Propose one image")),
                        registry.modelDefinitions()));
        AssistantMessage response = AssistantMessage.builder().content("")
                .toolCalls(calls).build();
        checkpoints.saveResponse(owner.userId(), project.id(), running.id(), 0,
                configVersion, codec.response(new ChatResponse(List.of(new Generation(response)))));

        for (String field : List.of("ownerId", "userId", "projectId", "approved",
                "maxImages", "maxVideos", "budget")) {
            assertThatThrownBy(() -> executor.execute(trusted, 0, "forged-" + field))
                    .as(field).isInstanceOfSatisfying(ApiProblemException.class, error ->
                            assertThat(error.code()).isEqualTo("PLAN_INVALID"));
        }
        assertThatThrownBy(() -> executor.execute(trusted, 0, "forged-approval"))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
        assertNoMediaAuthority(project.id(), running.id());

        UUID planId = UUID.fromString(executor.execute(trusted, 0, "valid-proposal")
                .at("/createdIds/0").asText());
        ExecutionPlan plan = plans.get(owner.userId(), project.id(), planId);
        assertThat(plan.projectId()).isEqualTo(project.id());
        assertThat(plan.runId()).isEqualTo(running.id());
        assertThat(plan.status()).isEqualTo(ExecutionPlan.Status.PENDING);
        assertThat(jdbc.sql("select count(*) from execution_plan where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", running.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from plan_approval where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and kind in ('IMAGE_GENERATION', 'VIDEO_GENERATION')")
                .param("projectId", project.id()).query(Long.class).single()).isZero();
    }

    /** Rejected model authority leaves neither approval nor paid work behind. */
    private void assertNoMediaAuthority(UUID projectId, UUID runId) {
        for (String table : List.of("execution_plan", "plan_approval", "tool_execution")) {
            long count = switch (table) {
                case "execution_plan", "plan_approval" -> jdbc.sql(
                                "select count(*) from " + table + " where project_id = :projectId")
                        .param("projectId", projectId).query(Long.class).single();
                case "tool_execution" -> jdbc.sql(
                                "select count(*) from tool_execution where run_id = :runId")
                        .param("runId", runId).query(Long.class).single();
                default -> throw new IllegalStateException("Unexpected table");
            };
            assertThat(count).as(table).isZero();
        }
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and kind in ('IMAGE_GENERATION', 'VIDEO_GENERATION')")
                .param("projectId", projectId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from usage_ledger where project_id = :projectId "
                        + "and task_id is not null and entry_type = 'RESERVATION'")
                .param("projectId", projectId).query(Long.class).single()).isZero();
    }

    /** Produces one valid, same-project version-pinned shot fixture. */
    private ArtifactService.ArtifactView createShot(UUID ownerId, UUID projectId) {
        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "Studio");
        scene.put("location", "Studio");
        scene.put("timeOfDay", "Night");
        scene.put("lighting", "Soft");
        scene.put("style", "Cinematic");
        scene.putArray("referenceVersionIds");
        UUID sceneVersion = artifacts.create(ownerId, projectId, Artifact.Kind.SCENE,
                "Studio", scene).currentVersion().id();
        ObjectNode content = mapper.createObjectNode();
        content.put("order", 1);
        content.put("durationMs", 1_000);
        content.put("description", "One shot");
        content.put("camera", "Wide");
        content.put("action", "Move");
        content.putArray("characterVersionIds");
        content.put("sceneVersionId", sceneVersion.toString());
        return artifacts.create(ownerId, projectId, Artifact.Kind.SHOT, "Shot 1", content);
    }

    /** The model-visible shape deliberately contains no authority fields. */
    private ObjectNode draft(ArtifactService.ArtifactView shot) {
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("stage", "IMAGE");
        proposal.put("objective", "One safe keyframe");
        ObjectNode step = proposal.putArray("steps").addObject();
        step.put("stepKey", "shot-1-image");
        step.put("outputSlotKey", "shot-1-output");
        step.put("shotArtifactId", shot.artifact().id().toString());
        step.put("shotVersionId", shot.currentVersion().id().toString());
        step.put("prompt", "A cinematic studio frame");
        step.putArray("dependsOnStepKeys");
        return proposal;
    }
}
