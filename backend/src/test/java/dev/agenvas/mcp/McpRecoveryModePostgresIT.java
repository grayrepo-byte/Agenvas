package dev.agenvas.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
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

/** Real HTTP POST reads stay available in recovery while semantic writes commit no business state. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agenvas.recovery-mode=true", "agenvas.mcp.enabled=true",
                "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class McpRecoveryModePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path assets;
    private static boolean backedUpSchemaPrepared;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        if (!backedUpSchemaPrepared) {
            // Recovery boot deliberately cannot migrate an empty database; model an existing backup.
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            backedUpSchemaPrepared = true;
        }
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.storage.root", () -> assets.toString());
    }

    @LocalServerPort int port;
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired CanvasService canvas;
    @Autowired MediaDraftService drafts;
    @Autowired McpTokenService tokens;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void postReadsWorkButContentAndMediaWritesRemainReadOnlyWithoutLedgerTasksOrEvents() throws Exception {
        var owner = identities.setup("mcp-recovery-admin", "synthetic-recovery-password");
        Project project = projects.create(owner.userId(), "Restored MCP project", Project.AspectRatio.SQUARE_1_1);
        // Application fixtures represent restored content; no HTTP business-write route is used to create them.
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Restored draft", null);
        UUID canvasItemId = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(canvasItemId,
                image.artifact().id(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(280), BigDecimal.valueOf(240), 0, null, false)));
        drafts.save(owner.userId(), project.id(), canvasItemId, 0,
                "Synthetic restored media draft", mapper.createObjectNode(), null, null, null,
                List.of(), List.of(), null);
        var draft = drafts.get(owner.userId(), project.id(), canvasItemId);
        var credential = tokens.create(owner.userId(), new McpTokenService.Create("Recovery client",
                Set.of(McpScope.READ, McpScope.CONTENT_WRITE, McpScope.MEDIA_GENERATE), 30));
        Map<String, Long> before = businessCounts(project.id());

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            Map<String, Object> initialization = Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "recovery-http-fixture", "version", "1.0"));
            JsonNode initialized = request(client, credential.token(), "initialize", initialization);
            assertThat(initialized.at("/result/protocolVersion").asText()).isEqualTo("2025-11-25");
            String initializationBody = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1,
                    "method", "initialize", "params", initialization));
            for (String alias : List.of("/%6dcp", "/mcp/")) {
                var aliasResponse = send(client, credential.token(), alias, initializationBody);
                assertThat(aliasResponse.statusCode()).as("only exact /mcp is an MCP transport: %s", alias)
                        .isBetween(400, 499);
            }
            JsonNode listed = tool(client, credential.token(), "list_projects", Map.of());
            assertThat(listed.at("/result/isError").asBoolean()).isFalse();
            assertThat(listed.at("/result/structuredContent/items/0/projectId").asText())
                    .isEqualTo(project.id().toString());
            JsonNode read = tool(client, credential.token(), "read_canvas", Map.of("projectId", project.id().toString()));
            assertThat(read.at("/result/isError").asBoolean()).isFalse();
            assertThat(read.at("/result/structuredContent/items/0/canvasItemId").asText())
                    .isEqualTo(canvasItemId.toString());

            JsonNode create = tool(client, credential.token(), "create_text", Map.of(
                    "projectId", project.id().toString(), "commandId", "recovery-denied-text",
                    "title", "Must not be created", "text", "Synthetic denied write"));
            assertReadOnly(create);
            JsonNode run = tool(client, credential.token(), "run_media", Map.of(
                    "projectId", project.id().toString(), "commandId", "recovery-denied-generation",
                    "canvasItemId", canvasItemId.toString(), "expectedDraftVersion", draft.version()));
            assertReadOnly(run);
            assertThat(businessCounts(project.id())).isEqualTo(before);
            assertThat(drafts.get(owner.userId(), project.id(), canvasItemId)).isEqualTo(draft);
        }
    }

    private void assertReadOnly(JsonNode response) {
        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/structuredContent/code").asText()).isEqualTo("RECOVERY_MODE_READ_ONLY");
        assertThat(response.at("/result/structuredContent/status").asInt()).isEqualTo(503);
    }

    private JsonNode tool(HttpClient client, String token, String name, Map<String, Object> arguments) throws Exception {
        return request(client, token, "tools/call", Map.of("name", name, "arguments", arguments));
    }

    private JsonNode request(HttpClient client, String token, String method, Map<String, Object> params) throws Exception {
        String body = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        var response = send(client, token, "/mcp", body);
        assertThat(response.statusCode()).as("MCP %s HTTP status", method).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode parsed = mapper.readTree(response.body());
        assertThat(parsed.has("error")).isFalse();
        return parsed;
    }

    private HttpResponse<String> send(HttpClient client, String token, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Long> businessCounts(UUID projectId) {
        return Map.of("operations", count("mcp_operation", projectId), "tasks", count("task", projectId),
                "artifacts", count("artifact", projectId), "versions", count("artifact_version", projectId),
                "nodes", count("canvas_item", projectId), "events", count("project_event", projectId),
                "approvals", count("agent_media_approval", projectId), "runs", count("agent_run", projectId));
    }

    private long count(String table, UUID projectId) {
        // The table names are fixed test-owned literals, never client input.
        return jdbc.sql("select count(*) from " + table + " where project_id=:project")
                .param("project", projectId).query(Long.class).single();
    }
}
