package dev.agenvas.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.application.McpToolService;
import dev.agenvas.mcp.application.McpToolPolicy;
import dev.agenvas.mcp.application.McpToolResponse;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.task.application.DirectTextTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.llm.application.DirectTextGenerationWorker;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.ImageAssetFixture;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Real database evidence for independent MCP commands, shared CAS and authorized replay. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class McpToolsPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired
    McpToolService tools;
    @Autowired
    McpToolPolicy contract;
    @Autowired
    McpTokenService tokens;
    @Autowired
    ProjectService projects;
    @Autowired
    CanvasService canvas;
    @Autowired
    DirectTextTaskService directText;
    @Autowired
    DirectTextGenerationWorker textWorker;
    @Autowired
    TaskService tasks;
    @Autowired
    ArtifactService artifacts;
    @Autowired
    AssetService assets;
    @Autowired
    MediaDraftService drafts;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcClient jdbc;
    UUID user;
    Project project;
    McpPrincipal principal;
    @BeforeEach
    void fixture() {
        user = createUser();
        project = projects.create(user, "MCP project", Project.AspectRatio.LANDSCAPE_16_9);
        principal = token(user, Set.of(McpScope.READ, McpScope.CONTENT_WRITE, McpScope.MEDIA_GENERATE));
    }

    @Autowired
    dev.agenvas.character.application.CharacterService characters;
    @Autowired
    dev.agenvas.canvas.application.CanvasCharacterNodeService characterNodes;

    @Test
    void longTextPagesPreserveUnicodeAndTheExactVersionAcrossRevisions() {
        String text = "剧😀".repeat(4500) + "结尾";
        JsonNode created = call(principal, "create_text",
                command("long-text").put("title", "Synthetic script").put("text", text)).data();
        ObjectNode request = read();
        request.putArray("versionIds").add(created.at("/artifact/versionId").asText());
        assertThat(call(principal, "read_artifacts", request).data().at("/items/0/contentTruncated").asBoolean())
                .isTrue();
        StringBuilder restored = new StringBuilder();
        int offset = 0;
        do {
            JsonNode page = call(principal, "read_artifacts",
                    request.deepCopy().put("textOffset", offset).put("textLimit", 3999))
                    .data().at("/items/0");
            assertThat(page.at("/textPage/offset").asInt()).isEqualTo(offset);
            assertThat(page.at("/textPage/total").asInt()).isEqualTo(text.codePointCount(0, text.length()));
            assertThat(page.path("versionId").asText()).isEqualTo(created.at("/artifact/versionId").asText());
            restored.append(page.at("/content/text").asText());
            if (offset == 0)
                call(principal, "revise_artifact", command("revise-long-text")
                        .put("artifactId", created.at("/artifact/artifactId").asText())
                        .put("expectedVersion", created.at("/artifact/expectedVersion").asLong())
                        .put("text", "New revision"));
            if (page.at("/textPage/nextOffset").isNull())
                break;
            int next = page.at("/textPage/nextOffset").asInt();
            assertThat(next).isGreaterThan(offset);
            offset = next;
        } while (true);
        assertThat(restored.toString()).isEqualTo(text);
        JsonNode end = call(principal, "read_artifacts", request.deepCopy().put("textOffset", 9002)).data()
                .at("/items/0");
        assertThat(end.at("/content/text").asText()).isEmpty();
        assertThat(end.at("/textPage/nextOffset").isNull()).isTrue();
        assertCode("TOOL_ARGUMENT_INVALID",
                () -> call(principal, "read_artifacts", request.deepCopy().put("textOffset", 9003)));
        ObjectNode batch = request.deepCopy().put("textOffset", 0);
        ((ArrayNode) batch.path("versionIds")).add(UUID.randomUUID().toString());
        assertCode("TOOL_ARGUMENT_INVALID", () -> call(principal, "read_artifacts", batch));
        var foreign = token(createUser(), Set.of(McpScope.READ));
        assertCode("RESOURCE_NOT_FOUND",
                () -> call(foreign, "read_artifacts", request.deepCopy().put("textOffset", 3999)));
    }

    @Test
    void repeatedPromptMentionsRoundTripAndLargeExistingDraftsMatchTheContract() {
        UUID asset = ImageAssetFixture.archive(assets, user, project.id());
        var source = artifacts.create(user, project.id(), Artifact.Kind.IMAGE, "Synthetic reference",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.toString()));
        ObjectNode imagePage = read().put("textOffset", 0);
        imagePage.putArray("versionIds").add(source.resourceDefaultVersion().id().toString());
        assertCode("TOOL_ARGUMENT_INVALID", () -> call(principal, "read_artifacts", imagePage));
        JsonNode node = call(principal, "create_media_node",
                command("mentions-node").put("kind", "IMAGE").put("title", "Repeated tags")).data();
        UUID item = UUID.fromString(node.at("/node/canvasItemId").asText());
        var role = MediaDraft.InputRole.REFERENCE;
        UUID version = source.resourceDefaultVersion().id();
        var mentions = IntStream.range(0, 101)
                .mapToObj(index -> new MediaDraft.PromptMention(version, role)).toList();
        var saved = drafts.save(user, project.id(), item, 0, "\uFFFC ".repeat(101), mapper.createObjectNode(), null,
                null, null,
                List.of(new MediaDraftService.SaveMediaInput(version, role, "#7C3AED")), mentions, null);
        assertThat(call(principal, "read_canvas", read().put("includeDrafts", true).put("limit", 6))
                .data().at("/items/0/mediaDraft/mentions")).hasSize(101);
        ObjectNode patch = mapper.createObjectNode().put("prompt", saved.prompt());
        patch.set("mentions", mapper.valueToTree(mentions));
        ObjectNode update = command("repeat-mentions").put("canvasItemId", item.toString()).put("expectedDraftVersion",
                saved.version());
        update.set("patch", patch);
        JsonNode result = call(principal, "update_media_draft", update).data();
        assertThat(result.at("/draft/mentions")).hasSize(101);
        assertThat(call(principal, "update_media_draft", update).data()).isEqualTo(result);
    }

    @Test
    void readsCharacterCardsAsBoundedMetadataWithoutMediaReadPermission() {
        UUID characterId = UUID.randomUUID(), lookId = UUID.randomUUID(), itemId = UUID.randomUUID();
        var look = new dev.agenvas.character.application.CharacterModels.CharacterLook(lookId, "Winter", "Blue coat",
                "", "", List.of());
        var content = new dev.agenvas.character.application.CharacterModels.CharacterContent("Aster", "Private persona",
                List.of(look), null);
        characters.save(user, project.id(), characterId,
                new dev.agenvas.character.application.CharacterModels.SaveCharacter(UUID.randomUUID(), 0, content));
        var selection = new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Selection(characterId, 1, lookId,
                List.of(), true, false);
        characterNodes.create(user, project.id(), itemId,
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.CreateNode(selection,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.valueOf(320),
                        java.math.BigDecimal.valueOf(320), 0, null, false));

        var result = call(principal, "read_canvas", read()).data();
        assertThat(result.at("/items/0/kind").asText()).isEqualTo("CHARACTER");
        assertThat(result.at("/items/0/characterId").asText()).isEqualTo(characterId.toString());
        assertThat(result.at("/items/0/characterVersion").asLong()).isEqualTo(1);
        assertThat(result.at("/items/0/characterNodeVersion").asLong()).isZero();
        assertThat(result.at("/items/0/lookName").asText()).isEqualTo("Winter");
        assertThat(result.at("/items/0/artifactId").isMissingNode()).isTrue();
        assertThat(result.toString()).doesNotContain("Private persona", "Blue coat", "mediaInputs", "speakerId");
    }

    @Test
    void createsAndRevisesTextWithAtomicPlacementAndExternalProvenance() {
        ObjectNode create = command("create-text").put("title", "Storyboard").put("text", "First scene");
        JsonNode created = call(principal, "create_text", create).data();
        UUID artifact = UUID.fromString(created.at("/artifact/artifactId").asText());
        UUID item = UUID.fromString(created.at("/node/canvasItemId").asText());
        assertThat(created.at("/artifact/createdByKind").asText()).isEqualTo("EXTERNAL_TOOL");
        assertThat(canvas.list(user, project.id())).hasSize(1);
        assertThat(call(principal, "create_text", create).data()).isEqualTo(created);
        assertThat(canvas.list(user, project.id())).hasSize(1);
        ObjectNode revise = command("revise-text").put("artifactId", artifact.toString())
                .put("expectedVersion", created.at("/artifact/expectedVersion").asLong()).put("text", "Second scene");
        JsonNode revised = call(principal, "revise_artifact", revise).data();
        assertThat(revised.at("/artifact/content/text").asText()).isEqualTo("Second scene");
        assertThat(artifacts.listVersions(user, project.id(), artifact)).hasSize(2);
        assertThat(call(principal, "revise_artifact", revise).data()).isEqualTo(revised);
        assertCode("ARTIFACT_VERSION_CONFLICT",
                () -> call(principal, "revise_artifact", revise.deepCopy().put("commandId", "stale")));
        ObjectNode move = command("move").put("canvasItemId", item.toString()).put("action", "MOVE")
                .put("expectedVersion", created.at("/node/expectedVersion").asLong()).put("x", 100).put("y", 200);
        JsonNode moved = call(principal, "manage_canvas_node", move).data();
        assertThat(moved.at("/node/x").asInt()).isEqualTo(100);
        assertThat(artifacts.get(user, project.id(), artifact).artifact().version())
                .isEqualTo(revised.at("/artifact/expectedVersion").asLong());
        assertThat(call(principal, "create_text", create).data()).isEqualTo(created);
        assertThat(jdbc.sql("select count(*) from agent_run where project_id=:id").param("id", project.id())
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql(
                "select count(*) from mcp_operation_version where operation_id in (select id from mcp_operation where project_id=:id)")
                .param("id", project.id()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void deniesCrossUserProjectsVersionsAndTasksAndScopeEscalation() {
        var created = call(principal, "create_text",
                command("private").put("title", "Private").put("text", "secret note")).data();
        var foreign = token(createUser(), Set.of(McpScope.READ, McpScope.CONTENT_WRITE, McpScope.MEDIA_GENERATE));
        ObjectNode read = read().set("versionIds",
                mapper.createArrayNode().add(created.at("/artifact/versionId").asText()));
        assertCode("RESOURCE_NOT_FOUND", () -> call(foreign, "read_artifacts", read));
        ObjectNode tasks = read().set("taskIds", mapper.createArrayNode().add(UUID.randomUUID().toString()));
        assertCode("RESOURCE_NOT_FOUND", () -> call(foreign, "read_task_status", tasks));
        var listed = call(foreign, "list_projects", mapper.createObjectNode()).data();
        assertThat(listed.path("items")).isEmpty();
        jdbc.sql("update app_user set status='DISABLED' where status='ACTIVE'").update();
        jdbc.sql("update app_user set status='ACTIVE' where id=:id").param("id", user).update();
        var readonly = token(user, Set.of(McpScope.READ));
        assertCode("MCP_SCOPE_DENIED",
                () -> call(readonly, "create_text", command("denied").put("title", "x").put("text", "x")));
        assertCode("MCP_SCOPE_DENIED", () -> call(readonly, "run_media", command("denied-run")
                .put("canvasItemId", UUID.randomUUID().toString()).put("expectedDraftVersion", 0)));
        var summary = call(principal, "read_project_summary", read()).data();
        assertThat(summary.path("artifactCount").asInt()).isEqualTo(1);
        assertThat(summary.toString()).doesNotContain("secret note", "instruction", "contextSnapshot", "input");
    }

    @Test
    void commandHashIsCanonicalAndConflictingPayloadsRollback() {
        ObjectNode create = command("canonical").put("title", "Canonical").put("text", "content");
        JsonNode first = call(principal, "create_text", create).data();
        ObjectNode reordered = mapper.createObjectNode().put("text", "content").put("title", "Canonical")
                .put("projectId", project.id().toString()).put("commandId", "canonical");
        assertThat(call(principal, "create_text", reordered).data()).isEqualTo(first);
        assertCode("MCP_COMMAND_CONFLICT",
                () -> call(principal, "create_text", create.deepCopy().put("text", "changed")));
        Project other = projects.create(user, "Other", Project.AspectRatio.SQUARE_1_1);
        assertCode("MCP_COMMAND_CONFLICT",
                () -> call(principal, "create_text", create.deepCopy().put("projectId", other.id().toString())));
        ObjectNode invalidPlacement = command("rollback").put("title", "Bad placement").put("text", "never persisted");
        invalidPlacement.set("placement",
                mapper.createObjectNode().put("x", 1000001).put("y", 0).put("width", 300).put("height", 300));
        assertCode("VALIDATION_ERROR", () -> call(principal, "create_text", invalidPlacement));
        assertThat(canvas.list(user, project.id())).hasSize(1);
        assertThat(jdbc.sql("select count(*) from mcp_operation where user_id=:id and command_id='rollback'")
                .param("id", user).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from artifact where project_id=:id").param("id", project.id())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void simultaneousCommandsCreateOneNodeAndTokenRotationCanReplayButRevocationCannot() throws Exception {
        ObjectNode create = command("parallel").put("title", "One node").put("text", "once");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> call(principal, "create_text", create).data());
            var second = executor.submit(() -> call(principal, "create_text", create).data());
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(canvas.list(user, project.id())).hasSize(1);
        JsonNode result = call(principal, "create_text", create).data();
        McpPrincipal rotated = token(user, Set.of(McpScope.READ, McpScope.CONTENT_WRITE));
        assertThat(call(rotated, "create_text", create).data()).isEqualTo(result);
        tokens.revoke(user, principal.tokenId(), 0);
        assertCode("MCP_UNAUTHENTICATED", () -> call(principal, "create_text", create));
        assertThat(call(rotated, "create_text", create).data()).isEqualTo(result);
    }

    @Test
    void archivedProjectRemainsReadableButRejectsNewWrites() {
        var created = call(principal, "create_text", command("archived-first").put("title", "x").put("text", "x"))
                .data();
        projects.archive(user, project.id(), project.version());
        assertThat(call(principal, "read_canvas", read()).data().path("total").asInt()).isEqualTo(1);
        assertCode("PROJECT_ARCHIVED",
                () -> call(principal, "create_text", command("archived-second").put("title", "x").put("text", "x")));
        assertThat(created.path("operationId").asText()).isNotBlank();
    }

    @Test
    void sameProjectTextTaskReturnsItsPublicVersionWithoutPrivateResponseCheckpoint() {
        JsonNode created = call(principal, "create_text",
                command("text-task-create").put("title", "Text generation").put("text", "Original public text")).data();
        UUID artifact = UUID.fromString(created.at("/artifact/artifactId").asText());
        UUID version = UUID.fromString(created.at("/artifact/versionId").asText());
        var accepted = directText.run(user, project.id(), artifact, "Develop this scene",
                created.at("/artifact/expectedVersion").asLong(), version, "web-text-generation");
        ObjectNode readTask = read();
        readTask.putArray("taskIds").add(accepted.id().toString());
        assertThat(call(principal, "read_task_status", readTask).data().at("/items/0/artifactVersionId").isNull())
                .isTrue();
        assertThat(textWorker.runOnce("mcp-text-read-worker")).isEqualTo(1);
        var finished = tasks.get(user, project.id(), accepted.id());
        assertThat(finished.output().path("result").path("artifactVersionId").asText()).isNotBlank();
        JsonNode safe = call(principal, "read_task_status", readTask).data();
        assertThat(safe.at("/items/0/artifactVersionId").asText())
                .isEqualTo(finished.output().path("result").path("artifactVersionId").asText());
        assertThat(safe.toString()).doesNotContain("currentText", "systemPrompt", "checkpoint", "rawResponse",
                "message", "privateReasoning");
    }

    private McpToolResponse call(McpPrincipal actor, String name, JsonNode arguments) {
        McpToolResponse result = tools.execute(actor, name, arguments);
        contract.validateOutput(name, result.data());
        return result;
    }

    ObjectNode read() {
        return mapper.createObjectNode().put("projectId", project.id().toString());
    }
    ObjectNode command(String id) {
        return read().put("commandId", id);
    }
    McpPrincipal token(UUID owner, Set<McpScope> scopes) {
        return tokens.authenticate(tokens.create(owner, new McpTokenService.Create("Test client", scopes, 30)).token());
    }
    UUID createUser() {
        jdbc.sql("update app_user set status='DISABLED' where status='ACTIVE'").update();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql(
                "insert into app_user(id,login_name,password_hash,status,created_at,password_changed_at,version) values (:id,:name,'unused-test-hash','ACTIVE',:now,:now,0)")
                .param("id", id).param("name", "mcp-fixture-" + id).param("now", now).update();
        return id;
    }
    void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code()).isEqualTo(code);
    }
}
