package dev.agenvas.settings;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.settings.application.*;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {"agenvas.llm.mode=configured"})
class LlmModelPolicyPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired LlmProviderConfigService configs;
    @Autowired LlmProviderConfigRepository repository;
    @Autowired dev.agenvas.llm.application.ChatGateway gateway;
    @Autowired dev.agenvas.identity.application.IdentityService identities;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    @Test void metadataEndpointRequiresAdminCsrfAndNeverSavesOrExposesTheCredential() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/v1/models", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"data\":[{\"id\":\"synthetic-model\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); http.start();
        try {
            var admin = identities.setup("synthetic-metadata-admin", "synthetic-password-123");
            var granted = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(admin, null,
                            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
            var viewer = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(admin, null,
                            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_VIEWER"))));
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                    .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
            String body = "{\"provider\":\"COMPATIBLE\",\"credentialMode\":\"DRAFT\",\"expectedVersion\":0,\"endpoint\":\"http://127.0.0.1:"
                    + http.getAddress().getPort()+"/v1\",\"apiKey\":\"synthetic-key\",\"modelId\":\"synthetic-model\"}";
            int before = configs.status().version();
            var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/settings/llm/models");
            mvc.perform(post.with(granted).contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/settings/llm/models").with(viewer)
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/settings/llm/models").with(granted)
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.models[0].contextWindowTokens").isEmpty())
                    .andReturn().getResponse();
            assertThat(result.getHeader("Cache-Control")).contains("no-store");
            assertThat(result.getContentAsString()).doesNotContain("synthetic-key", "credentialCiphertext", "apiKey");
            assertThat(configs.status().version()).isEqualTo(before); assertThat(requests).hasValue(1);
        } finally { http.stop(0); }
    }
    @Test void capacityIsImmutableAndLegacyConnectionsUseRuntimeDefaultsWithoutBeingRewritten() {
        configs.replace(0, "https://10.0.0.5/v1", "synthetic-model", "synthetic-policy-key");
        var old = repository.findVersion(1).orElseThrow();
        assertThat(old.modelPolicy()).isNull();
        var selection = new LlmModelDiscoveryService.Selection(ModelCatalog.Provider.COMPATIBLE,
                LlmModelDiscoveryService.Mode.MANUAL, null, 16384, null, 2048, LlmModelPolicy.OutputParameter.MAX_TOKENS);
        var result = configs.replace(1, "https://10.0.0.5/v1", "synthetic-model", "synthetic-policy-key", selection);
        assertThat(result.modelPolicy().contextWindowTokens()).isEqualTo(16384);
        assertThat(repository.findVersion(2).orElseThrow().modelPolicy().maxOutputTokens()).isEqualTo(2048);
        var fixed = new dev.agenvas.llm.application.ChatGateway.ConfigIdentity("stored", 2);
        assertThat(gateway.requestBudgetFor(fixed).contextWindowTokens()).isEqualTo(16384);
        assertThat(gateway.requestBudgetFor(fixed).responseReserveTokens()).isEqualTo(2048);
        var legacyBudget = gateway.requestBudgetFor(new dev.agenvas.llm.application.ChatGateway.ConfigIdentity("stored", 1));
        assertThat(legacyBudget.contextWindowTokens()).isEqualTo(32768);
        assertThat(legacyBudget.responseReserveTokens()).isEqualTo(4096);
        assertThat(legacyBudget.inputBudget()).isEqualTo(28672);
        assertThat(legacyBudget.outputParameter()).isEqualTo(LlmModelPolicy.OutputParameter.MAX_TOKENS);
        assertThat(repository.findVersion(1).orElseThrow()).usingRecursiveComparison().ignoringFields("active").isEqualTo(old);
        assertThatThrownBy(() -> configs.replace(1, "https://10.0.0.5/v1", "synthetic-model", "synthetic-policy-key", selection))
                .isInstanceOf(ApiProblemException.class);
    }
}
