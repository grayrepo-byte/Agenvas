package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.CreativeArtifactToolService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import java.math.BigDecimal;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fake-model, real-PostgreSQL proof of bounded read tools and Run-visible inputs. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, ReadToolsPostgresIT.FakeConfig.class},
        properties = "agenvas.identity.bootstrap-secret=read-tools-integration-secret")
class ReadToolsPostgresIT {

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
    @Autowired private CanvasService canvas;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private AgentTurnWorker worker;
    @Autowired private CreativeArtifactToolService creativeTools;
    @Autowired private ToolExecutionService toolExecutions;
    @Autowired private FakeGateway gateway;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void readsOnlyPinnedVersionAndNeverLeaksUnboundProjectContent() {
        AdminPrincipal owner = identities.setup("read-tools-integration-secret",
                "reader-admin", "reader-password-123");
        Project project = projects.create(owner.userId(), "Reader project",
                Project.AspectRatio.LANDSCAPE_16_9);
        ObjectNode content = mapper.createObjectNode();
        content.put("format", "PLAIN_TEXT");
        content.put("text", "Allowed brief");
        ArtifactService.ArtifactView bound = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.TEXT, "Bound brief", content);
        String longBrief = "Long bound context ".repeat(900);
        content.put("text", longBrief);
        ArtifactService.ArtifactView largeBound = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.TEXT, "Large brief", content);
        content.put("text", "PRIVATE UNBOUND CONTENT");
        ArtifactService.ArtifactView unbound = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.TEXT, "Unbound", content);
        gateway.boundVersionId = bound.currentVersion().id();
        gateway.largeVersionId = largeBound.currentVersion().id();
        gateway.longBrief = longBrief;
        gateway.unboundVersionId = unbound.currentVersion().id();
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Reader",
                "Read the brief", List.of(new AgentInstanceService.BindingInput(
                        bound.artifact().id(), bound.currentVersion().id()),
                        new AgentInstanceService.BindingInput(largeBound.artifact().id(),
                                largeBound.currentVersion().id())));
        UUID selectedItemId = UUID.randomUUID();
        UUID unboundSelectedItemId = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(
                        selectedItemId, bound.artifact().id(), BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("320"), new BigDecimal("200"), 0, null, false),
                new CanvasService.PlaceArtifact(unboundSelectedItemId, unbound.artifact().id(),
                        new BigDecimal("400"), BigDecimal.ZERO,
                        new BigDecimal("320"), new BigDecimal("200"), 1, null, false)));
        assertThatThrownBy(() -> runs.create(owner.userId(), project.id(), agent.id(),
                "Summarize the brief", "invalid-selection", null,
                List.of(UUID.randomUUID()))).isInstanceOf(ApiProblemException.class);
        gateway.selectedItemId = selectedItemId;
        gateway.unboundSelectedItemId = unboundSelectedItemId;
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Summarize the brief", "read-tools-run", null,
                List.of(selectedItemId, unboundSelectedItemId)).run();
        assertThat(run.contextSnapshot().at("/selection/0/itemId").asText())
                .isEqualTo(selectedItemId.toString());
        assertThat(run.contextSnapshot().at("/selection/0/versionId").asText())
                .isEqualTo(bound.currentVersion().id().toString());
        assertThat(run.contextSnapshot().at("/selection/1/versionId").asText())
                .isEqualTo(unbound.currentVersion().id().toString());
        assertThatThrownBy(() -> runs.create(owner.userId(), project.id(), agent.id(),
                "Summarize the brief", "read-tools-run", null,
                List.of(selectedItemId)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> runs.create(owner.userId(), project.id(), agent.id(),
                "Summarize the brief", "duplicate-selection", null,
                List.of(selectedItemId, selectedItemId)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("VALIDATION_ERROR");
        gateway.taskId = tasks.listByRun(owner.userId(), project.id(), run.id()).getFirst().id();

        assertThat(worker.runOnce("reader-worker")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.RUNNING);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("select count(*) from artifact where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single()).isEqualTo(3);
        TrustedToolContext trusted = new TrustedToolContext(owner.userId(), project.id(), run.id());
        JsonNode firstRead = toolExecutions.execute(trusted, 0, "read-bound");
        assertThat(firstRead.at("/data/1/content/text").asText()).isEqualTo(longBrief);
        assertThat(toolExecutions.execute(trusted, 0, "read-bound")).isEqualTo(firstRead);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(6);
        assertThatThrownBy(() -> creativeTools.placeArtifacts(trusted, run,
                UUID.randomUUID(), gateway.placeRequest(unbound.currentVersion().id())))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("INPUT_SCOPE_DENIED");
        assertThatThrownBy(() -> creativeTools.placeArtifacts(trusted, run,
                UUID.randomUUID(), "{\"versionIds\":[\""
                        + largeBound.currentVersion().id() + "\"],\"group\":\"PRIVATE\"}"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("TOOL_ARGUMENT_INVALID");
        assertThat(canvas.list(owner.userId(), project.id()).stream()
                .filter(entry -> entry.item().subjectId().equals(largeBound.artifact().id()))
                .filter(entry -> agent.outputGroupId().equals(entry.item().groupId()))
                .count()).isEqualTo(1);

        assertThat(worker.runOnce("reader-worker")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.RUNNING);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(8);
        var arranged = canvas.list(owner.userId(), project.id()).stream()
                .map(CanvasService.CanvasEntry::item)
                .filter(item -> item.id().equals(gateway.placedItemId))
                .findFirst().orElseThrow();
        assertThat(arranged.version()).isEqualTo(1);
        assertThat(arranged.x()).isEqualByComparingTo(new BigDecimal("800"));
        assertThat(artifacts.get(owner.userId(), project.id(), largeBound.artifact().id())
                .currentVersion().id()).isEqualTo(largeBound.currentVersion().id());
        assertThatThrownBy(() -> creativeTools.arrangeItems(trusted, run,
                UUID.randomUUID(), gateway.arrangeRequest(unboundSelectedItemId,
                        unbound.currentVersion().id(), 0, "GRID")))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("INPUT_SCOPE_DENIED");
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.UpdateLayout(
                arranged.id(), 1, new BigDecimal("900"), arranged.y(),
                arranged.width(), arranged.height(), arranged.zIndex(), arranged.groupId())));
        assertThatThrownBy(() -> creativeTools.arrangeItems(trusted, run,
                UUID.randomUUID(), gateway.arrangeRequest(arranged.id(),
                        largeBound.currentVersion().id(), 0, "HORIZONTAL")))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("CANVAS_VERSION_CONFLICT");
        content.put("text", "Changed large brief");
        artifacts.revise(owner.userId(), project.id(), largeBound.artifact().id(),
                largeBound.artifact().version(), null, content);
        assertThatThrownBy(() -> creativeTools.placeArtifacts(trusted, run,
                UUID.randomUUID(), gateway.placeRequest(largeBound.currentVersion().id())))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("ARTIFACT_VERSION_CONFLICT");
        assertThatThrownBy(() -> creativeTools.arrangeItems(trusted, run,
                UUID.randomUUID(), gateway.arrangeRequest(arranged.id(),
                        largeBound.currentVersion().id(), 2, "GRID")))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("ARTIFACT_VERSION_CONFLICT");
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.SetLocked(
                arranged.id(), 2, true)));
        assertThatThrownBy(() -> creativeTools.arrangeItems(trusted, run,
                UUID.randomUUID(), gateway.arrangeRequest(arranged.id(),
                        largeBound.currentVersion().id(), 3, "GRID")))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("CANVAS_ITEM_LOCKED");

        assertThat(worker.runOnce("reader-worker")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.BLOCKED);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(8);
        assertThat(gateway.calls.get()).isEqualTo(3);
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway(ObjectMapper mapper) {
            return new FakeGateway(mapper);
        }
    }

    /** Inspects the persisted tool replies before attempting an out-of-scope version. */
    static class FakeGateway implements ChatGateway {
        private final ObjectMapper mapper;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile UUID boundVersionId;
        private volatile UUID largeVersionId;
        private volatile UUID unboundVersionId;
        private volatile UUID taskId;
        private volatile UUID selectedItemId;
        private volatile UUID unboundSelectedItemId;
        private volatile UUID placedItemId;
        private volatile String longBrief;

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
                    .contains("read_project_summary", "read_selection",
                            "read_artifacts", "read_task_status", "place_artifacts",
                            "arrange_items");
            int step = calls.incrementAndGet();
            if (step == 1) {
                String firstPrompt = messages.stream().map(Message::getText)
                        .filter(value -> value != null)
                        .reduce("", (left, right) -> left + right);
                assertThat(firstPrompt).contains("contentTruncated=true")
                        .contains(selectedItemId.toString())
                        .contains("read_artifacts for full content")
                        .doesNotContain(longBrief);
            }
            if (step == 2) {
                ToolResponseMessage results = (ToolResponseMessage) messages.getLast();
                assertThat(results.getResponses()).hasSize(6);
                JsonNode summary = mapper.readTree(results.getResponses().get(0).responseData());
                JsonNode selection = mapper.readTree(results.getResponses().get(1).responseData());
                JsonNode artifact = mapper.readTree(results.getResponses().get(2).responseData());
                JsonNode task = mapper.readTree(results.getResponses().get(3).responseData());
                JsonNode firstPlace = mapper.readTree(results.getResponses().get(4).responseData());
                JsonNode secondPlace = mapper.readTree(results.getResponses().get(5).responseData());
                assertThat(summary.at("/data/name").asText()).isEqualTo("Reader project");
                assertThat(summary.at("/data/runLimits/maxToolExecutions").asInt())
                        .isEqualTo(40);
                assertThat(selection.at("/data/0/itemId").asText())
                        .isEqualTo(selectedItemId.toString());
                assertThat(selection.at("/data/1/itemId").asText())
                        .isEqualTo(unboundSelectedItemId.toString());
                assertThat(artifact.at("/data/0/content/text").asText())
                        .isEqualTo("Allowed brief");
                assertThat(artifact.at("/data/0/expectedVersion").asLong()).isZero();
                assertThat(artifact.at("/data/1/content/text").asText())
                        .isEqualTo(longBrief);
                assertThat(task.at("/data/0/taskId").asText()).isEqualTo(taskId.toString());
                assertThat(task.at("/data/0/kind").asText()).isEqualTo("AGENT_TURN");
                assertThat(task.at("/data/0/status").asText()).isEqualTo("RUNNING");
                assertThat(task.toString()).doesNotContain("providerRequestId", "inputHash");
                assertThat(firstPlace.at("/data/0/created").asBoolean()).isTrue();
                assertThat(secondPlace.at("/data/0/created").asBoolean()).isFalse();
                assertThat(secondPlace.at("/data/0/itemId").asText())
                        .isEqualTo(firstPlace.at("/data/0/itemId").asText());
                assertThat(firstPlace.at("/data/0/itemVersion").asLong()).isZero();
                placedItemId = UUID.fromString(firstPlace.at("/data/0/itemId").asText());
                assertThat(results.getResponses().toString())
                        .doesNotContain("PRIVATE UNBOUND CONTENT");
            }
            if (step == 3) {
                ToolResponseMessage results = (ToolResponseMessage) messages.getLast();
                assertThat(results.getResponses()).hasSize(2);
                JsonNode arranged = mapper.readTree(results.getResponses().getFirst()
                        .responseData());
                JsonNode replayed = mapper.readTree(results.getResponses().get(1)
                        .responseData());
                assertThat(arranged.at("/data/0/itemId").asText())
                        .isEqualTo(placedItemId.toString());
                assertThat(arranged.at("/data/0/itemVersion").asLong()).isEqualTo(1);
                assertThat(replayed.at("/data/0/itemVersion").asLong()).isEqualTo(1);
                assertThat(replayed.at("/data/0/x").decimalValue())
                        .isEqualByComparingTo(arranged.at("/data/0/x").decimalValue());
                assertThat(replayed.path("updatedIds")).isEmpty();
            }
            List<AssistantMessage.ToolCall> toolCalls = step == 1
                    ? List.of(new AssistantMessage.ToolCall("read-summary", "function",
                            "read_project_summary", "{}"),
                            new AssistantMessage.ToolCall("read-selection", "function",
                                    "read_selection", "{}"),
                            new AssistantMessage.ToolCall("read-bound", "function",
                                    "read_artifacts", versionRequest(boundVersionId,
                                            largeVersionId)),
                            new AssistantMessage.ToolCall("read-own-task", "function",
                                    "read_task_status", taskRequest(taskId)),
                            new AssistantMessage.ToolCall("place-bound-first", "function",
                                    "place_artifacts", placeRequest(largeVersionId)),
                            new AssistantMessage.ToolCall("place-bound-again", "function",
                                    "place_artifacts", placeRequest(largeVersionId)))
                    : step == 2
                            ? List.of(new AssistantMessage.ToolCall("arrange-output", "function",
                                    "arrange_items", arrangeRequest(placedItemId,
                                            largeVersionId, 0, "HORIZONTAL")),
                                    new AssistantMessage.ToolCall("arrange-again", "function",
                                            "arrange_items", arrangeRequest(placedItemId,
                                                    largeVersionId, 0, "HORIZONTAL")))
                            : List.of(new AssistantMessage.ToolCall("read-unbound", "function",
                                    "read_artifacts", versionRequest(unboundVersionId)));
            AssistantMessage response = AssistantMessage.builder().content("")
                    .toolCalls(toolCalls).build();
            return new Exchange(1, new ChatResponse(List.of(new Generation(response))));
        }

        private String versionRequest(UUID... versionIds) {
            ObjectNode input = mapper.createObjectNode();
            var values = input.putArray("versionIds");
            for (UUID versionId : versionIds) values.add(versionId.toString());
            return input.toString();
        }

        private String taskRequest(UUID... taskIds) {
            ObjectNode input = mapper.createObjectNode();
            var values = input.putArray("taskIds");
            for (UUID requestedId : taskIds) values.add(requestedId.toString());
            return input.toString();
        }

        private String placeRequest(UUID versionId) {
            ObjectNode input = mapper.createObjectNode();
            input.putArray("versionIds").add(versionId.toString());
            input.put("group", "AGENT_OUTPUT");
            return input.toString();
        }

        private String arrangeRequest(UUID itemId, UUID versionId, long itemVersion,
                String layout) {
            ObjectNode input = mapper.createObjectNode();
            input.put("layout", layout);
            ObjectNode item = input.putArray("items").addObject();
            item.put("itemId", itemId.toString());
            item.put("versionId", versionId.toString());
            item.put("expectedVersion", itemVersion);
            return input.toString();
        }
    }
}
