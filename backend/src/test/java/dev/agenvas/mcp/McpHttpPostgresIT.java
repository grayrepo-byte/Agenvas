package dev.agenvas.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaExecutionWorker;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** An actual official SDK client over TCP, Spring Security and PostgreSQL; media uses the Mock Provider. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class McpHttpPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path assets;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.storage.root", () -> assets.toString());
    }
    @LocalServerPort int port;
    @Autowired McpTokenService tokens;
    @Autowired ProjectService projects;
    @Autowired MediaExecutionWorker worker;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    UUID user;
    Project project;
    McpTokenService.Created credential;

    @BeforeEach void fixture() {
        jdbc.sql("update app_user set status='DISABLED' where status='ACTIVE'").update();
        user = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                insert into app_user(id,login_name,password_hash,status,created_at,password_changed_at,version)
                values (:id,:name,'synthetic-unused-hash','ACTIVE',:now,:now,0)
                """).param("id", user).param("name", "mcp-http-" + user).param("now", now).update();
        project = projects.create(user, "SDK project", Project.AspectRatio.SQUARE_1_1);
        credential = tokens.create(user, new McpTokenService.Create("SDK fixture",
                Set.of(McpScope.READ, McpScope.CONTENT_WRITE, McpScope.MEDIA_GENERATE), 30));
    }

    @Test void officialClientRunsAllTwelveToolsIncludingDirectMockMediaAndImageContent() {
        try (var client = client(credential.token())) {
            var initialized = client.initialize();
            assertThat(initialized.protocolVersion()).isEqualTo("2025-11-25");
            assertThat(initialized.capabilities().tools()).isNotNull();
            assertThat(initialized.capabilities().resources()).isNull();
            var definitions = client.listTools().tools();
            assertThat(definitions).hasSize(12);
            assertThat(definitions).allSatisfy(tool -> {
                assertThat(McpJsonDefaults.getSchemaValidator().validateSchema(tool.outputSchema()).valid()).isTrue();
                assertThat(tool.inputSchema().get("additionalProperties")).isEqualTo(false);
            });
            assertThat(call(client, "list_projects", Map.of()).path("items")).hasSize(1);
            assertThat(call(client, "read_project_summary", read()).path("artifactCount").asInt()).isZero();
            JsonNode created = call(client, "create_text", command("create", Map.of("title", "SDK text", "text", "First")));
            JsonNode node = created.path("node");
            assertThat(created.at("/artifact/createdByKind").asText()).isEqualTo("EXTERNAL_TOOL");
            assertThat(call(client, "create_text", command("create", Map.of("title", "SDK text", "text", "First"))))
                    .isEqualTo(created);
            var revised = call(client, "revise_artifact", command("revise", Map.of("artifactId", node.path("artifactId").asText(),
                    "expectedVersion", created.at("/artifact/expectedVersion").asLong(), "text", "Revised")));
            call(client, "manage_canvas_node", command("rename", Map.of("canvasItemId", node.path("canvasItemId").asText(),
                    "expectedVersion", node.path("expectedVersion").asLong(), "action", "RENAME", "title", "Renamed")));
            assertThat(call(client, "read_canvas", read()).path("items")).hasSize(1);
            assertThat(call(client, "read_artifacts", Map.of("projectId", project.id().toString(),
                    "versionIds", java.util.List.of(revised.at("/artifact/versionId").asText())))
                    .at("/items/0/content/text").asText()).isEqualTo("Revised");
            assertThat(call(client, "list_media_capabilities", read()).path("items")).isNotEmpty();
            JsonNode media = call(client, "create_media_node", command("image", Map.of("kind", "IMAGE", "title", "Image")));
            String mediaItem = media.at("/node/canvasItemId").asText();
            call(client, "update_media_draft", command("draft", Map.of("canvasItemId", mediaItem,
                    "expectedDraftVersion", 0, "patch", Map.of("prompt", "Synthetic SDK mock image"))));
            Map<String, Object> run = command("generate", Map.of("canvasItemId", mediaItem, "expectedDraftVersion", 1));
            JsonNode accepted = call(client, "run_media", run);
            assertThat(accepted.path("tasks")).hasSize(1);
            assertThat(call(client, "run_media", run)).isEqualTo(accepted);
            assertThat(worker.submitOnce("sdk-mock-worker")).isEqualTo(1);
            JsonNode states = call(client, "read_task_status", Map.of("projectId", project.id().toString(),
                    "taskIds", java.util.List.of(accepted.at("/tasks/0/taskId").asText())));
            assertThat(states.at("/items/0/status").asText()).isEqualTo("SUCCEEDED");
            var preview = client.callTool(new McpSchema.CallToolRequest("read_artifacts", Map.of("projectId", project.id().toString(),
                    "versionIds", java.util.List.of(states.at("/items/0/artifactVersionId").asText()))));
            assertThat(preview.isError()).isFalse();
            assertThat(preview.content()).anySatisfy(content -> assertThat(content).isInstanceOf(McpSchema.ImageContent.class));
            assertThat(mapper.valueToTree(preview.structuredContent()).toString())
                    .doesNotContain("objectKey", "thumbnailKey", "mcpTokenId", credential.token());
            assertThat(jdbc.sql("select count(*) from agent_run where project_id=:id").param("id", project.id())
                    .query(Long.class).single()).isZero();
            assertThat(jdbc.sql("select count(*) from agent_media_approval where project_id=:id").param("id", project.id())
                    .query(Long.class).single()).isZero();
        }
    }

    @Test void listIsFilteredAndCallingAnUnlistedWriteStillChecksCurrentScope() {
        var reader = tokens.create(user, new McpTokenService.Create("Reader", Set.of(McpScope.READ), 30));
        try (var client = client(reader.token())) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                    "list_projects", "read_project_summary", "read_canvas", "read_artifacts", "list_media_capabilities", "read_task_status");
            var denied = client.callTool(new McpSchema.CallToolRequest("create_text", command("denied", Map.of("title", "x", "text", "x"))));
            assertThat(denied.isError()).isTrue();
            assertThat(mapper.valueToTree(denied.structuredContent()).path("code").asText()).isEqualTo("MCP_SCOPE_DENIED");
            var invalid = client.callTool(new McpSchema.CallToolRequest("read_canvas", Map.of("projectId", "private-invalid-value")));
            assertThat(invalid.isError()).isTrue();
            assertThat(mapper.valueToTree(invalid).toString()).doesNotContain("private-invalid-value");
        }
    }

    @Test void httpRejectsMissingInvalidAndRevokedBearerAndForeignOriginWithoutCreatingSession() throws Exception {
        assertThat(request(null, null, "{}").statusCode()).isEqualTo(401);
        var forged = request("invalid", null, "{}");
        assertThat(forged.statusCode()).isEqualTo(401);
        assertThat(forged.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(forged.headers().allValues("Set-Cookie")).isEmpty();
        var noStream = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/mcp"))
                .header("Authorization", "Bearer " + credential.token()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noStream.statusCode()).isEqualTo(405);
        assertThat(noStream.headers().firstValue("Allow")).hasValue("POST");
        assertThat(request(credential.token(), "https://foreign.example", "{}").statusCode()).isEqualTo(403);
        var malformed = request(credential.token(), null, "malformed-json-synthetic-private");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.body()).doesNotContain("synthetic-private", "Exception", "stackTrace");
        var version = request(credential.token(), null,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "future-unsupported");
        assertThat(version.statusCode()).isEqualTo(400);
        try (var client = client(credential.token())) {
            client.initialize();
            assertThat(client.listTools().tools()).hasSize(12);
            tokens.revoke(user, credential.credential().id(), 0);
            assertThat(request(credential.token(), null, "{}").statusCode()).isEqualTo(401);
        }
    }

    private McpSyncClient client(String token) {
        var transport = HttpClientStreamableHttpTransport.builder(base()).endpoint("/mcp")
                .openConnectionOnStartup(false).resumableStreams(false)
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token)).build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).initializationTimeout(Duration.ofSeconds(20))
                .clientInfo(new McpSchema.Implementation("agenvas-integration", "1.0"))
                .enableCallToolSchemaCaching(true).build();
    }

    private JsonNode call(McpSyncClient client, String name, Map<String, Object> arguments) {
        var result = client.callTool(new McpSchema.CallToolRequest(name, arguments));
        assertThat(result.isError()).as("tool %s result %s", name, result.structuredContent()).isFalse();
        return mapper.valueToTree(result.structuredContent());
    }
    private Map<String, Object> read() { return Map.of("projectId", project.id().toString()); }
    private Map<String, Object> command(String command, Map<String, Object> fields) {
        var result = new java.util.HashMap<String, Object>(fields);
        result.put("projectId", project.id().toString()); result.put("commandId", command);
        return result;
    }
    private String base() { return "http://127.0.0.1:" + port; }
    private HttpResponse<String> request(String token, String origin, String body) throws Exception {
        return request(token, origin, body, "2025-11-25");
    }
    private HttpResponse<String> request(String token, String origin, String body, String version) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + "/mcp")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", version).POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (origin != null) request.header("Origin", origin);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
