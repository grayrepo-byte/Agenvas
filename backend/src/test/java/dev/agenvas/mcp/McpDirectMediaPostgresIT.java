package dev.agenvas.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.application.McpToolService;
import dev.agenvas.mcp.application.McpToolPolicy;
import dev.agenvas.mcp.application.McpToolResponse;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.ManualUnknownRetryService;
import dev.agenvas.task.domain.Task;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Mock Provider execution over real PostgreSQL: no approvals, complete batch and no Agent continuation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class McpDirectMediaPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired McpToolService tools;
    @Autowired McpToolPolicy contract;
    @Autowired McpTokenService tokens;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired ManualUnknownRetryService manualRetry;
    @Autowired CanvasService canvas;
    @Autowired MediaExecutionWorker worker;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    UUID user;
    Project project;
    McpPrincipal principal;
    @BeforeEach void fixture() {
        jdbc.sql("update app_user set status='DISABLED' where status='ACTIVE'").update();
        user = UUID.randomUUID(); OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("insert into app_user(id,login_name,password_hash,status,created_at,password_changed_at,version) values (:id,:name,'unused-test-hash','ACTIVE',:now,:now,0)")
                .param("id", user).param("name", "mcp-media-" + user).param("now", now).update();
        project = projects.create(user, "External media", Project.AspectRatio.SQUARE_1_1);
        principal = tokens.authenticate(tokens.create(user, new McpTokenService.Create("Media client",
                Set.of(McpScope.READ, McpScope.CONTENT_WRITE, McpScope.MEDIA_GENERATE), 30)).token());
    }

    @Test void batchPinsAllTasksWithoutApprovalAndArchivesEveryVersionWithSource() {
        JsonNode node = createImage("batch", 2);
        String item = node.path("canvasItemId").asText();
        ObjectNode run = command("generate-batch").put("canvasItemId", item).put("expectedDraftVersion", 1);
        // Layout uses another CAS; moving the card cannot invalidate the pinned draft version.
        call(principal, "manage_canvas_node", command("move-before-run").put("canvasItemId", item)
                .put("action", "MOVE").put("expectedVersion", node.path("expectedVersion").asLong()).put("x", 400).put("y", 500));
        JsonNode accepted = call(principal, "run_media", run).data();
        assertThat(accepted.path("tasks")).hasSize(2);
        assertThat(jdbc.sql("select count(*) from mcp_operation_task where operation_id=:id")
                .param("id", UUID.fromString(accepted.path("operationId").asText())).query(Long.class).single()).isEqualTo(2);
        for (JsonNode result : accepted.path("tasks")) {
            UUID taskId = UUID.fromString(result.path("taskId").asText());
            var task = tasks.get(user, project.id(), taskId);
            assertThat(task.runId()).isNull();
            assertThat(task.approvedMedia()).isFalse();
            assertThat(task.input().path("initiator").asText()).isEqualTo("EXTERNAL_MCP");
            assertThat(task.input().path("mcpOperationId").asText()).isEqualTo(accepted.path("operationId").asText());
            assertThat(task.input().has("agentApprovalId")).isFalse();
            assertThat(result.path("source").asText()).isEqualTo("EXTERNAL_MCP");
            assertThat(result.toString()).doesNotContain("providerParameters", "providerRequestId", "\"prompt\":", "mcpTokenId");
        }
        assertThat(call(principal, "run_media", run).data()).isEqualTo(accepted);
        assertCode("CANVAS_ITEM_BUSY", () -> call(principal, "run_media", run.deepCopy().put("commandId", "different-run")));
        assertCode("CANVAS_ITEM_BUSY", () -> call(principal, "update_media_draft", command("busy-update")
                .put("canvasItemId", item).put("expectedDraftVersion", 1)
                .set("patch", mapper.createObjectNode().put("prompt", "changed while generating"))));
        tokens.revoke(user, principal.tokenId(), 0);
        assertThat(worker.submitOnce("mcp-batch-worker")).isEqualTo(1);
        assertThat(worker.submitOnce("mcp-batch-worker")).isEqualTo(1);
        McpPrincipal reader = tokens.authenticate(tokens.create(user, new McpTokenService.Create("Reader", Set.of(McpScope.READ), 30)).token());
        ObjectNode read = read(); var ids = read.putArray("taskIds");
        for (JsonNode result : accepted.path("tasks")) ids.add(result.path("taskId").asText());
        JsonNode states = call(reader, "read_task_status", read).data();
        assertThat(states.path("items")).hasSize(2);
        for (JsonNode state : states.path("items")) assertThat(state.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(canvas.list(user, project.id())).hasSize(2).allSatisfy(entry -> assertThat(entry.item().selectedVersionId()).isNotNull());
        assertThat(jdbc.sql("select count(*) from mcp_operation_version where operation_id=:id")
                .param("id", UUID.fromString(accepted.path("operationId").asText())).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from agent_media_approval where project_id=:id").param("id", project.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from agent_run where project_id=:id").param("id", project.id()).query(Long.class).single()).isZero();
        ObjectNode readImage = read(); readImage.putArray("versionIds").add(states.at("/items/0/artifactVersionId").asText());
        var image = call(reader, "read_artifacts", readImage);
        assertThat(image.images()).hasSize(1);
        assertThat(image.images().getFirst().mimeType()).startsWith("image/");
        assertThat(image.images().getFirst().data()).isNotBlank();
        assertThat(image.data().toString()).doesNotContain("objectKey", "thumbnailKey", "http://", "https://", "providerRequestId");
        McpPrincipal writer = tokens.authenticate(tokens.create(user, new McpTokenService.Create("Reference editor",
                Set.of(McpScope.READ, McpScope.CONTENT_WRITE), 30)).token());
        JsonNode referenceTarget = call(writer, "create_media_node", command("reference-node").put("kind", "IMAGE").put("title", "Reference target")).data();
        ObjectNode referencePatch = mapper.createObjectNode().put("prompt", "Use this exact image");
        referencePatch.putArray("mediaInputs").addObject().put("versionId", states.at("/items/0/artifactVersionId").asText())
                .put("role", "REFERENCE").put("color", "#abcdef");
        ObjectNode referenceUpdate = command("reference-draft").put("canvasItemId", referenceTarget.at("/node/canvasItemId").asText()).put("expectedDraftVersion", 0);
        referenceUpdate.set("patch", referencePatch);
        assertThat(call(writer, "update_media_draft", referenceUpdate).data().at("/draft/mediaInputs/0/color").asText()).isEqualTo("#ABCDEF");
    }

    @Test void unknownQueriesNeverCreateAttemptsAndConflictingCommandCannotAdoptWebTask() {
        JsonNode node = createImage("unknown", 1);
        ObjectNode run = command("unknown-run").put("canvasItemId", node.path("canvasItemId").asText()).put("expectedDraftVersion", 1);
        JsonNode accepted = call(principal, "run_media", run).data();
        UUID id = UUID.fromString(accepted.at("/tasks/0/taskId").asText());
        jdbc.sql("update task set status='UNKNOWN',error_code='PROVIDER_SUBMISSION_UNKNOWN' where id=:id").param("id", id).update();
        ObjectNode read = read(); read.putArray("taskIds").add(id.toString());
        assertThat(call(principal, "read_task_status", read).data().at("/items/0/status").asText()).isEqualTo("UNKNOWN");
        assertThat(call(principal, "run_media", run).data()).isEqualTo(accepted);
        assertCode("CANVAS_ITEM_BUSY", () -> call(principal, "run_media", run.deepCopy().put("commandId", "new-attempt")));
        assertThat(jdbc.sql("select count(*) from task where project_id=:id").param("id", project.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id=:id").param("id", id).query(Long.class).single()).isZero();
    }

    @Test void publishesSafeCapabilitiesAndDraftsWithIndependentCas() {
        JsonNode capabilities = call(principal, "list_media_capabilities", read().put("kind", "IMAGE")).data();
        assertThat(capabilities.path("items")).isNotEmpty();
        assertThat(capabilities.toString()).doesNotContain("apiKey", "origin", "workflowJson", "importSource", "nodeOptions");
        JsonNode node = createImage("draft", 1);
        JsonNode directory = call(principal, "read_canvas", read().put("limit", 6).put("includeDrafts", true)).data();
        assertThat(directory.at("/items/0/mediaDraft/prompt").asText()).isEqualTo("MCP controlled mock image");
        String item = node.path("canvasItemId").asText();
        ObjectNode update = command("draft-update").put("canvasItemId", item).put("expectedDraftVersion", 1);
        update.set("patch", mapper.createObjectNode().put("prompt", "New prompt"));
        var changed = call(principal, "update_media_draft", update).data();
        assertThat(changed.at("/draft/version").asLong()).isEqualTo(2);
        assertThat(call(principal, "update_media_draft", update).data()).isEqualTo(changed);
        assertCode("VERSION_CONFLICT", () -> call(principal, "update_media_draft", update.deepCopy().put("commandId", "stale-draft")));
    }

    @Test void webExplicitNewAttemptBecomesUserDirectAndRetainsReplacementLineage() {
        JsonNode node = createImage("web-retry", 1);
        JsonNode accepted = call(principal, "run_media", command("retry-original")
                .put("canvasItemId", node.path("canvasItemId").asText()).put("expectedDraftVersion", 1)).data();
        UUID originalId = UUID.fromString(accepted.at("/tasks/0/taskId").asText());
        jdbc.sql("update task set status='UNKNOWN',error_code='PROVIDER_SUBMISSION_UNKNOWN' where id=:id").param("id", originalId).update();
        var original = tasks.get(user, project.id(), originalId);
        tokens.revoke(user, principal.tokenId(), 0);
        Task replacement = manualRetry.create(user, project.id(), originalId, original.version(), "explicit-web-retry");
        assertThat(replacement.initiator()).isEqualTo(Task.Initiator.USER_DIRECT);
        assertThat(replacement.input().has("mcpTokenId")).isFalse();
        assertThat(replacement.input().has("mcpOperationId")).isFalse();
        assertThat(replacement.input().path("mediaInput")).isEqualTo(original.input().path("mediaInput"));
        assertThat(jdbc.sql("select count(*) from task_manual_replacement where original_task_id=:original and replacement_task_id=:replacement")
                .param("original", originalId).param("replacement", replacement.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(worker.submitOnce("mcp-web-retry-worker")).isEqualTo(1);
        assertThat(tasks.get(user, project.id(), replacement.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(jdbc.sql("select count(*) from mcp_operation_version where operation_id=:id")
                .param("id", UUID.fromString(accepted.path("operationId").asText())).query(Long.class).single()).isZero();
        assertThat(tasks.get(user, project.id(), originalId).status()).isEqualTo(Task.Status.UNKNOWN);
    }

    private JsonNode createImage(String id, int count) {
        JsonNode node = call(principal, "create_media_node", command(id + "-create").put("kind", "IMAGE").put("title", "Image")).data().path("node");
        ObjectNode patch = mapper.createObjectNode().put("prompt", "MCP controlled mock image");
        patch.set("parameters", mapper.createObjectNode().put("generationCount", count));
        ObjectNode update = command(id + "-draft").put("canvasItemId", node.path("canvasItemId").asText()).put("expectedDraftVersion", 0);
        update.set("patch", patch); call(principal, "update_media_draft", update);
        return node;
    }
    private McpToolResponse call(McpPrincipal actor, String name, JsonNode arguments) {
        McpToolResponse result = tools.execute(actor, name, arguments);
        contract.validateOutput(name, result.data());
        return result;
    }

    private ObjectNode read() { return mapper.createObjectNode().put("projectId", project.id().toString()); }
    private ObjectNode command(String id) { return read().put("commandId", id); }
    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code()).isEqualTo(code);
    }
}
