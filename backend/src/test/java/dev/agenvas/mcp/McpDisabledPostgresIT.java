package dev.agenvas.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Turning off the protocol leaves browser authentication and the existing Task services operational. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agenvas.mcp.enabled=false", "server.servlet.session.cookie.secure=false",
                "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class McpDisabledPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path assets;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.storage.root", () -> assets.toString());
    }

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired IdentityService identities;
    @Autowired McpTokenService tokens;
    @Autowired ObjectMapper mapper;

    @Test
    void disabledEndpointHasNoProtocolRouteOrSessionBypassWhileAuthAndTaskBeansRemainAvailable() throws Exception {
        var owner = identities.setup("mcp-disabled-admin", "synthetic-disabled-password");
        var credential = tokens.create(owner.userId(), new McpTokenService.Create("Disabled fixture",
                Set.of(McpScope.READ, McpScope.CONTENT_WRITE, McpScope.MEDIA_GENERATE), 30));
        assertThat(context.getBeansOfType(McpStatelessSyncServer.class)).isEmpty();
        assertThat(context.getBeansOfType(WebMvcStatelessServerTransport.class)).isEmpty();
        assertThat(context.getBeansOfType(TaskService.class)).hasSize(1);
        assertThat(context.getBeansOfType(DirectMediaTaskService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MediaExecutionWorker.class)).hasSize(1);

        try (var anonymous = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                var browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            var status = anonymous.send(get("/api/v1/auth/setup-status"), HttpResponse.BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(status.body()).path("setupRequired").asBoolean()).isFalse();
            var missing = anonymous.send(mcp(credential.token()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(mapper.readTree(missing.body()).path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(missing.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(missing.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(missing.body()).doesNotContain("protocolVersion", "serverInfo", credential.token());
            assertThat(anonymous.send(mcp(null).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

            JsonNode csrf = csrf(browser);
            var login = browser.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "loginName", "mcp-disabled-admin", "password", "synthetic-disabled-password"))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(browser.send(get("/api/v1/auth/me"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            JsonNode currentCsrf = csrf(browser); // Login rotates the CSRF cookie; use an actually valid new value.
            var cookieOnly = browser.send(mcp(null)
                    .header(currentCsrf.path("headerName").asText(), currentCsrf.path("token").asText()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(cookieOnly.statusCode()).isEqualTo(401);
            assertThat(mapper.readTree(cookieOnly.body()).path("code").asText()).isEqualTo("MCP_UNAUTHENTICATED");
            assertThat(browser.send(get("/api/v1/auth/me"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
    }

    private JsonNode csrf(HttpClient client) throws Exception {
        var response = client.send(get("/api/v1/auth/csrf"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).GET().build();
    }

    private HttpRequest.Builder mcp(String token) throws Exception {
        String body = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "disabled-http-fixture", "version", "1.0"))));
        var request = HttpRequest.newBuilder(uri("/mcp")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
