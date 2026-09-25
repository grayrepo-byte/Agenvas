package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fake-model, real-PostgreSQL proof of scoped immutable Agent revisions. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentRevisionPostgresIT.FakeConfig.class},
        properties = "agenvas.identity.bootstrap-secret=agent-revision-integration-secret")
class AgentRevisionPostgresIT {

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
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ArtifactService artifacts;
    @Autowired private AgentTurnWorker worker;
    @Autowired private FakeGateway gateway;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void revisesRunOutputButCannotReviseUnboundProjectArtifactOrBypassCas() {
        AdminPrincipal owner = identities.setup("agent-revision-integration-secret",
                "revision-admin", "revision-password-123");
        Project project = projects.create(owner.userId(), "Revision project",
                Project.AspectRatio.LANDSCAPE_16_9);
        ObjectNode privateContent = mapper.createObjectNode();
        privateContent.put("format", "PLAIN_TEXT");
        privateContent.put("text", "User private note");
        ArtifactService.ArtifactView unbound = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.TEXT, "Unbound", privateContent);
        gateway.unboundId = unbound.artifact().id();
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator",
                "Make and revise a draft", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Create then revise", "revision-run").run();

        assertThat(worker.runOnce("revision-worker")).isEqualTo(1);
        assertThat(worker.runOnce("revision-worker")).isEqualTo(1);
        UUID createdId = gateway.createdId;
        assertThat(createdId).isNotNull();
        ArtifactService.ArtifactView revised = artifacts.get(owner.userId(), project.id(), createdId);
        assertThat(revised.artifact().version()).isEqualTo(1);
        assertThat(revised.currentVersion().content().path("text").asText())
                .isEqualTo("Revised draft");
        assertThat(revised.currentVersion().createdByKind())
                .isEqualTo(ArtifactVersion.CreatedByKind.AGENT);
        assertThat(revised.currentVersion().runId()).isEqualTo(run.id());
        assertThat(artifacts.listVersions(owner.userId(), project.id(), createdId))
                .hasSize(2);
        assertThatThrownBy(() -> artifacts.reviseFromAgent(owner.userId(), project.id(),
                run.id(), run.contextSnapshot(), createdId, 0, null, privateContent))
                .isInstanceOf(ApiProblemException.class)
                .extracting(failure -> ((ApiProblemException) failure).code())
                .isEqualTo("ARTIFACT_VERSION_CONFLICT");
        assertThatThrownBy(() -> artifacts.reviseFromAgent(owner.userId(), project.id(),
                run.id(), run.contextSnapshot(), unbound.artifact().id(), 100,
                null, privateContent))
                .isInstanceOf(ApiProblemException.class)
                .extracting(failure -> ((ApiProblemException) failure).code())
                .isEqualTo("INPUT_SCOPE_DENIED");

        ObjectNode sceneContent = mapper.createObjectNode();
        sceneContent.put("name", "Stage");
        sceneContent.put("location", "Studio");
        sceneContent.put("timeOfDay", "Day");
        sceneContent.put("lighting", "Soft");
        sceneContent.put("style", "Minimal");
        sceneContent.putArray("referenceVersionIds");
        ArtifactService.ArtifactView visibleScene = artifacts.createFromAgent(owner.userId(),
                project.id(), run.id(), Artifact.Kind.SCENE, "Visible scene", sceneContent);
        ArtifactService.ArtifactView hiddenScene = artifacts.create(owner.userId(),
                project.id(), Artifact.Kind.SCENE, "Unbound scene", sceneContent);
        ObjectNode shotContent = mapper.createObjectNode();
        shotContent.put("order", 1);
        shotContent.put("durationSeconds", 5);
        shotContent.put("description", "Opening");
        shotContent.put("camera", "Wide");
        shotContent.put("action", "Show product");
        shotContent.putArray("characterVersionIds");
        shotContent.put("sceneVersionId", visibleScene.currentVersion().id().toString());
        ArtifactService.ArtifactView shot = artifacts.createFromAgent(owner.userId(),
                project.id(), run.id(), Artifact.Kind.SHOT, "Shot", shotContent);
        ObjectNode forgedMediaChoice = shotContent.deepCopy();
        forgedMediaChoice.put("selectedImageVersionId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> artifacts.reviseFromAgent(owner.userId(), project.id(),
                run.id(), run.contextSnapshot(), shot.artifact().id(), 0,
                null, forgedMediaChoice))
                .isInstanceOf(ApiProblemException.class)
                .extracting(failure -> ((ApiProblemException) failure).code())
                .isEqualTo("TOOL_ARGUMENT_INVALID");
        shotContent.put("sceneVersionId", hiddenScene.currentVersion().id().toString());
        assertThatThrownBy(() -> artifacts.reviseFromAgent(owner.userId(), project.id(),
                run.id(), run.contextSnapshot(), shot.artifact().id(), 0,
                null, shotContent))
                .isInstanceOf(ApiProblemException.class)
                .extracting(failure -> ((ApiProblemException) failure).code())
                .isEqualTo("INPUT_SCOPE_DENIED");
        assertThat(artifacts.listVersions(owner.userId(), project.id(), shot.artifact().id()))
                .hasSize(1);

        assertThat(worker.runOnce("revision-worker")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.BLOCKED);
        assertThat(artifacts.listVersions(owner.userId(), project.id(),
                unbound.artifact().id())).hasSize(1);
        assertThat(artifacts.get(owner.userId(), project.id(), unbound.artifact().id())
                .currentVersion().content().path("text").asText()).isEqualTo("User private note");
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(2);
        assertThat(gateway.calls.get()).isEqualTo(3);

        Project boundProject = projects.create(owner.userId(), "Bound revision project",
                Project.AspectRatio.LANDSCAPE_16_9);
        ArtifactService.ArtifactView bound = artifacts.create(owner.userId(),
                boundProject.id(), Artifact.Kind.TEXT, "Bound draft", privateContent);
        AgentInstance boundAgent = agents.create(owner.userId(), boundProject.id(),
                "Bound creator", "Revise the bound draft", List.of(
                        new AgentInstanceService.BindingInput(bound.artifact().id(),
                                bound.currentVersion().id())));
        AgentRun boundRun = runs.create(owner.userId(), boundProject.id(), boundAgent.id(),
                "Revise", "bound-revision-run").run();
        assertThat(boundRun.contextSnapshot().path("bindings").path(0)
                .path("expectedVersion").longValue()).isZero();
        ObjectNode changed = privateContent.deepCopy();
        changed.put("text", "Agent update");
        ArtifactService.ArtifactView boundRevision = artifacts.reviseFromAgent(owner.userId(),
                boundProject.id(), boundRun.id(), boundRun.contextSnapshot(),
                bound.artifact().id(), 0, null, changed);
        assertThat(boundRevision.currentVersion().runId()).isEqualTo(boundRun.id());
        assertThat(boundRevision.artifact().version()).isEqualTo(1);
        changed.put("text", "User update");
        artifacts.revise(owner.userId(), boundProject.id(), bound.artifact().id(),
                1, null, changed);
        assertThatThrownBy(() -> artifacts.reviseFromAgent(owner.userId(),
                boundProject.id(), boundRun.id(), boundRun.contextSnapshot(),
                bound.artifact().id(), 2, null, privateContent))
                .isInstanceOf(ApiProblemException.class)
                .extracting(failure -> ((ApiProblemException) failure).code())
                .isEqualTo("INPUT_SCOPE_DENIED");
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway(ObjectMapper mapper) {
            return new FakeGateway(mapper);
        }
    }

    /** Pulls the created ID from the committed prior tool reply, never inventing one. */
    static class FakeGateway implements ChatGateway {
        private final ObjectMapper mapper;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile UUID unboundId;
        private volatile UUID createdId;
        private volatile long createdExpectedVersion;

        FakeGateway(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String configSource() { return "test-fake"; }

        @Override
        public int configVersion() { return 1; }

        @Override
        public Capabilities capabilities() { return new Capabilities(true, false, false); }

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            assertThat(tools.stream().map(tool -> tool.getToolDefinition().name()))
                    .contains("revise_artifact");
            int turn = calls.incrementAndGet();
            String arguments;
            String name;
            if (turn == 1) {
                name = "create_text";
                arguments = "{\"title\":\"Draft\",\"text\":\"Original draft\","
                        + "\"format\":\"PLAIN_TEXT\"}";
            } else {
                name = "revise_artifact";
                if (turn == 2) {
                    ToolResponseMessage reply = (ToolResponseMessage) messages.getLast();
                    var result = mapper.readTree(reply.getResponses().getFirst().responseData());
                    createdId = UUID.fromString(result.path("createdIds").path(0).asText());
                    createdExpectedVersion = result.path("artifactVersions")
                            .path(createdId.toString()).longValue();
                    assertThat(createdExpectedVersion).isZero();
                }
                ObjectNode content = mapper.createObjectNode();
                content.put("format", "PLAIN_TEXT");
                content.put("text", turn == 2 ? "Revised draft" : "Stolen rewrite");
                ObjectNode revision = mapper.createObjectNode();
                revision.put("artifactId", (turn == 2 ? createdId : unboundId).toString());
                revision.put("expectedVersion", turn == 2 ? createdExpectedVersion : 0);
                revision.set("content", content);
                arguments = revision.toString();
            }
            AssistantMessage response = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("revision-call-" + turn,
                            "function", name, arguments))).build();
            return new Exchange(1, new ChatResponse(List.of(new Generation(response))));
        }
    }
}
