package dev.agenvas.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL evidence for credential hashing, user boundaries, CSRF and revocation locks. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class McpTokenPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired McpTokenService tokens;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    private MockMvc mvc;
    private UUID userId;

    @BeforeEach
    void uniqueUserAndHttp() {
        userId = createUser();
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void oneTimeSecretIsNeverReturnedInMetadataAndAccessIsUserScoped() throws Exception {
        var response = mvc.perform(post("/api/v1/account/mcp-tokens")
                        .with(authentication(webUser(userId))).with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"External director\",\"scopes\":[\"READ\",\"MEDIA_GENERATE\"]}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.credential.version").value(0))
                .andReturn().getResponse().getContentAsString();
        var created = mapper.readTree(response);
        String rawToken = created.path("token").asText();
        UUID tokenId = UUID.fromString(created.path("credential").path("id").asText());
        assertThat(rawToken).isNotBlank();
        String hash = jdbc.sql("select secret_hash from mcp_access_token where id=:id")
                .param("id", tokenId).query(String.class).single();
        assertThat(hash).hasSize(64).doesNotContain(rawToken);
        var list = mvc.perform(get("/api/v1/account/mcp-tokens").with(authentication(webUser(userId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(tokenId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(rawToken, hash, "secretHash", "secret_hash");
        var principal = tokens.authenticate(rawToken);
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.scopes()).containsExactly(McpScope.READ, McpScope.MEDIA_GENERATE);
        assertThat(tokens.list(userId).getFirst().lastUsedAt()).isNotNull();
        UUID otherUserId = createUser();
        assertThat(tokens.list(otherUserId)).isEmpty();
        assertCode("RESOURCE_NOT_FOUND", () -> tokens.revoke(otherUserId, tokenId, 0));
        // The product permits one active administrator; switch the disposable fixture back.
        jdbc.sql("update app_user set status='DISABLED' where status='ACTIVE'").update();
        jdbc.sql("update app_user set status='ACTIVE' where id=:id").param("id", userId).update();
        mvc.perform(post("/api/v1/account/mcp-tokens/" + tokenId + "/revoke")
                        .with(authentication(webUser(userId))).with(csrf())
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/v1/account/mcp-tokens/" + tokenId + "/revoke")
                        .with(authentication(webUser(userId))).with(csrf())
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        assertCode("MCP_UNAUTHENTICATED", () -> tokens.authenticate(rawToken));
        assertCode("MCP_UNAUTHENTICATED", () -> tokens.requireValidForRead(principal));
    }

    @Test
    void sessionAndBasicCannotAuthenticateMcpAndBearerCannotManageAccount() throws Exception {
        var created = createToken();
        mvc.perform(post("/mcp").with(authentication(webUser(userId)))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MCP_UNAUTHENTICATED"));
        mvc.perform(post("/mcp").header("Authorization", "Basic synthetic")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/account/mcp-tokens").header("Authorization", "Bearer " + created.token()))
                .andExpect(status().isUnauthorized());
        // No broad /mcp/** CSRF exemption: only the exact transport endpoint has its own chain.
        mvc.perform(post("/mcp/other").with(authentication(webUser(userId)))
                        .header("Authorization", "Bearer " + created.token()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/account/mcp-tokens").with(authentication(webUser(userId)))
                        .contentType("application/json").content("{\"name\":\"test\",\"scopes\":[\"READ\"]}"))
                .andExpect(status().isForbidden());
        var tokenPrincipal = tokens.authenticate(created.token());
        Authentication fabricated = UsernamePasswordAuthenticationToken.authenticated(tokenPrincipal, null,
                List.of(new SimpleGrantedAuthority("ROLE_MCP")));
        mvc.perform(get("/api/v1/account/mcp-tokens").with(authentication(fabricated)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAndDisabledUserTokensCannotReadOrWriteAndWriteRequiresExistingTransaction() {
        var created = createToken();
        var principal = tokens.authenticate(created.token());
        assertThatThrownBy(() -> tokens.requireValidForWrite(principal))
                .isInstanceOf(IllegalTransactionStateException.class);
        jdbc.sql("update mcp_access_token set created_at=now()-interval '2 days', expires_at=now()-interval '1 day' where id=:id")
                .param("id", principal.tokenId()).update();
        assertCode("MCP_UNAUTHENTICATED", () -> tokens.authenticate(created.token()));
        assertCode("MCP_UNAUTHENTICATED", () -> tokens.requireValidForRead(principal));
        assertCode("MCP_UNAUTHENTICATED", () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> tokens.requireValidForWrite(principal)));
        var second = createToken();
        jdbc.sql("update app_user set status='DISABLED' where id=:id").param("id", userId).update();
        assertCode("MCP_UNAUTHENTICATED", () -> tokens.authenticate(second.token()));
    }

    @Test
    void revokeWaitsForAuthorizedWriteThenActsAsBarrierForAllFollowingWrites() throws Exception {
        var created = createToken();
        McpPrincipal principal = tokens.authenticate(created.token());
        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var write = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                tokens.requireValidForWrite(principal);
                authorized.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(authorized.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.sql("set local lock_timeout='250ms'").update();
                    tokens.revoke(userId, principal.tokenId(), 0);
                })).isInstanceOf(DataAccessException.class);
            } finally {
                release.countDown();
            }
            write.get(10, TimeUnit.SECONDS);
            assertThat(tokens.revoke(userId, principal.tokenId(), 0).revokedAt()).isNotNull();
            assertCode("MCP_UNAUTHENTICATED", () -> new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> tokens.requireValidForWrite(principal)));
        } finally {
            release.countDown();
        }
    }

    private McpTokenService.Created createToken() {
        return tokens.create(userId, new McpTokenService.Create("Synthetic fixture", Set.of(McpScope.READ), 30));
    }

    private Authentication webUser(UUID id) {
        return UsernamePasswordAuthenticationToken.authenticated(new AdminPrincipal(id, "fixture-" + id), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private UUID createUser() {
        // Preserve the production single-active-admin invariant while exercising distinct owners.
        jdbc.sql("update app_user set status='DISABLED' where status='ACTIVE'").update();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                insert into app_user(id,login_name,password_hash,status,created_at,password_changed_at,version)
                values (:id,:name,'synthetic-unused-password-hash','ACTIVE',:now,:now,0)
                """)
                .param("id", id).param("name", "fixture-" + id).param("now", now).update();
        return id;
    }

    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code()).isEqualTo(code);
    }
}
