package dev.agenvas.settings;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.settings.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.concurrent.*;
import dev.agenvas.shared.error.ApiProblemException;

/** Real PostgreSQL exercises connection identity, credential preservation and active selection CAS. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {"agenvas.llm.mode=configured"})
class LlmConnectionsPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired WebApplicationContext web;
    @Autowired IdentityService identities;
    @Autowired LlmProviderConfigRepository repository;
    @Autowired CredentialCipher cipher;
    @Autowired ChatGateway gateway;
    @Autowired LlmProviderConfigService configs;
    @Autowired JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void resetConnections() {
        jdbc.sql("delete from llm_connection").update();
        jdbc.sql("delete from llm_provider_config").update();
        jdbc.sql("update llm_provider_config_counter set current_version=0").update();
    }

    @Test void concurrentCreateEditAndActivationEachHaveExactlyOneWinner() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertThat(race(() -> configs.saveConnection(first, "First", 0, "https://10.0.0.5/v1", "model", "synthetic-key", null),
                () -> configs.saveConnection(first, "Other", 0, "https://10.0.0.5/v1", "model", "synthetic-key", null))).isEqualTo(1);
        int version = configs.connection(first).version();
        assertThat(race(() -> configs.saveConnection(first, "Renamed", version, "https://10.0.0.5/v1", "model", null, null),
                () -> configs.saveConnection(first, "Other rename", version, "https://10.0.0.5/v1", "model", null, null))).isEqualTo(1);
        int edited = configs.connection(first).version();
        var other = configs.saveConnection(second, "Second", 0, "https://10.0.0.6/v1", "other-model", "other-synthetic-key", null);
        assertThat(race(() -> configs.activate(first, edited, 0), () -> configs.activate(second, other.version(), 0))).isEqualTo(1);
        assertThat(configs.connections().items()).hasSize(2);
        assertThat(jdbc.sql("select count(*) from llm_provider_config where active").query(Integer.class).single()).isEqualTo(1);
    }

    @Test void renamingWithAnOmittedKeyKeepsCapacityButChangingModelClearsIt() {
        UUID id = UUID.randomUUID();
        var policy = new LlmModelDiscoveryService.Selection(ModelCatalog.Provider.COMPATIBLE, LlmModelDiscoveryService.Mode.MANUAL,
                null, 64000, null, 4096, LlmModelPolicy.OutputParameter.MAX_TOKENS);
        var saved = configs.saveConnection(id, "First", 0, "https://10.0.0.5/v1", "model", "synthetic-key", policy);
        var renamed = configs.saveConnection(id, "Renamed", saved.version(), saved.endpoint(), saved.modelId(), null, null);
        assertThat(renamed.modelPolicy()).isEqualTo(saved.modelPolicy());
        var changed = configs.saveConnection(id, "Changed", renamed.version(), renamed.endpoint(), "other-model", null, null);
        assertThat(changed.modelPolicy()).isNull();
    }

    @Test void createRequiresAValidKeyAndAllEmptyEditFormsPreserveOnlyTheSelectedConnectionsKey() {
        for (String missing : new String[] {null, "", " \t"}) {
            assertThatThrownBy(() -> configs.saveConnection(UUID.randomUUID(), "Missing", 0, "https://10.0.0.5/v1", "model", missing, null))
                    .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.status().value()).isEqualTo(400));
        }
        UUID id = UUID.randomUUID();
        var saved = configs.saveConnection(id, "Key", 0, "https://10.0.0.5/v1", "model", "12345678", null);
        for (String invalid : List.of("short", "has space", "line\nbreak", "nonascii密钥123", "x".repeat(4097))) {
            int version = saved.version();
            assertThatThrownBy(() -> configs.saveConnection(id, "Invalid", version, "https://10.0.0.5/v1", "model", invalid, null))
                    .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.status().value()).isEqualTo(400));
        }
        for (String missing : new String[] {null, "", " \t"}) {
            saved = configs.saveConnection(id, "Kept", saved.version(), "https://10.0.0.5/v1", "model", missing, null);
            assertThat(secret(repository.findVersion(saved.version()).orElseThrow())).isEqualTo("12345678");
        }
    }

    @Test void renamePreservesVerifiedRuntimeWhileModelKeyAndPolicyChangesRequireFreshVerification() {
        UUID id = UUID.randomUUID();
        var selection = new LlmModelDiscoveryService.Selection(ModelCatalog.Provider.COMPATIBLE, LlmModelDiscoveryService.Mode.MANUAL,
                null, 64000, null, 4096, LlmModelPolicy.OutputParameter.MAX_TOKENS);
        var saved = configs.saveConnection(id, "Before", 0, "https://10.0.0.5/v1", "model", "synthetic-key", selection);
        assertThat(repository.markConnectionToolCallingVerified(id, saved.version())).isTrue();
        int activeVersion = configs.activate(id, saved.version(), 0).activeVersion();
        var renamed = configs.saveConnection(id, "After", activeVersion, saved.endpoint(), saved.modelId(), null, null);
        assertThat(renamed.toolCallingVerified()).isTrue();
        assertThatCode(() -> gateway.requireToolCalling(gateway.configIdentity())).doesNotThrowAnyException();
        var changedModel = configs.saveConnection(id, "After", renamed.version(), saved.endpoint(), "other-model", null, null);
        assertThat(changedModel.toolCallingVerified()).isFalse();
        assertThat(repository.markConnectionToolCallingVerified(id, changedModel.version())).isTrue();
        var changedKey = configs.saveConnection(id, "After", changedModel.version(), saved.endpoint(), "other-model", "different-synthetic-key", null);
        assertThat(changedKey.toolCallingVerified()).isFalse();
        assertThat(repository.markConnectionToolCallingVerified(id, changedKey.version())).isTrue();
        var changedPolicy = configs.saveConnection(id, "After", changedKey.version(), saved.endpoint(), "other-model", null, selection);
        assertThat(changedPolicy.toolCallingVerified()).isFalse();
    }

    @Test void legacyFirstSaveAcceptsTheFullModelIdLengthAndUsesABoundedDefaultConnectionName() {
        var saved = configs.replace(0, "https://10.0.0.5/v1", "m".repeat(160), "synthetic-key");
        assertThat(saved.modelId()).hasSize(160);
        assertThat(configs.connections().items()).singleElement().satisfies(item -> assertThat(item.name()).hasSize(120));
    }

    private int race(Callable<?> left, Callable<?> right) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> a = () -> { start.await(); try { left.call(); return true; } catch (ApiProblemException conflict) { assertThat(conflict.status().value()).isEqualTo(409); return false; } };
            Callable<Boolean> b = () -> { start.await(); try { right.call(); return true; } catch (ApiProblemException conflict) { assertThat(conflict.status().value()).isEqualTo(409); return false; } };
            var x = pool.submit(a); var y = pool.submit(b); start.countDown();
            return (x.get() ? 1 : 0) + (y.get() ? 1 : 0);
        }
    }

    @Test void independentConnectionsKeepTheirOwnKeysAndExplicitActivationFreezesOldVersions() throws Exception {
        MockMvc mvc = webAppContextSetup(web).apply(springSecurity()).build();
        var admin = identities.setup("connections-admin", "synthetic-password-123");
        var auth = authentication(new UsernamePasswordAuthenticationToken(admin, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var viewer = authentication(new UsernamePasswordAuthenticationToken(admin, null,
                List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
        mvc.perform(get("/api/v1/settings/llm/connections")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/settings/llm/connections").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/settings/llm/connections").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeVersion").value(0));
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        mvc.perform(put("/api/v1/settings/llm/connections/" + first).with(auth).contentType("application/json")
                .content(body(0, "First", "model-a", "synthetic-first-key"))).andExpect(status().isForbidden());
        var a = save(mvc, auth, first, 0, "First", "model-a", "synthetic-first-key");
        var b = save(mvc, auth, second, 0, "Second", "model-b", "synthetic-second-key");
        mvc.perform(post("/api/v1/settings/llm/connections/" + first + "/activate").with(auth).with(csrf())
                .contentType("application/json").content("{\"expectedVersion\":" + a.path("version").asInt() + "}"))
                .andExpect(status().isBadRequest());
        int av = a.path("version").asInt(), bv = b.path("version").asInt();
        assertThat(repository.active()).isEmpty();
        var activated = activate(mvc, auth, first, av, 0);
        int activeVersion = activated.path("activeVersion").asInt();
        assertThat(activeVersion).isGreaterThan(bv);
        assertThat(gateway.configIdentity()).isEqualTo(new ChatGateway.ConfigIdentity("stored", activeVersion));
        var original = repository.findVersion(activeVersion).orElseThrow();
        var edited = save(mvc, auth, second, bv, "Second renamed", "model-b-edited", " ");
        var editedConfig = repository.findVersion(edited.path("version").asInt()).orElseThrow();
        assertThat(secret(editedConfig)).isEqualTo("synthetic-second-key");
        assertThat(editedConfig.credentialNonce()).isNotEqualTo(repository.findVersion(bv).orElseThrow().credentialNonce());
        assertThat(repository.active().orElseThrow().version()).isEqualTo(activeVersion);
        // Legacy writes compare against the active snapshot, not the allocator advanced by inactive edits.
        mvc.perform(put("/api/v1/settings/llm").with(auth).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":" + activeVersion + ",\"endpoint\":\"https://10.0.0.5/v1\",\"modelId\":\"model-a-edit\",\"apiKey\":\"\"}"))
                .andExpect(status().isOk());
        int updatedActive = repository.active().orElseThrow().version();
        assertThat(secret(repository.findVersion(updatedActive).orElseThrow())).isEqualTo("synthetic-first-key");
        assertThat(repository.findVersion(activeVersion).orElseThrow()).usingRecursiveComparison().ignoringFields("active").isEqualTo(original);
        var switched = activate(mvc, auth, second, edited.path("version").asInt(), updatedActive);
        int switchedVersion = switched.path("activeVersion").asInt();
        var back = activate(mvc, auth, first, updatedActive, switchedVersion);
        assertThat(back.path("activeVersion").asInt()).isGreaterThan(switchedVersion);
        mvc.perform(post("/api/v1/settings/llm/connections/" + second + "/activate").with(auth).with(csrf())
                .contentType("application/json").content("{\"expectedVersion\":" + switchedVersion + ",\"expectedActiveVersion\":" + activeVersion + "}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/settings/llm/connections/" + UUID.randomUUID()).with(auth).with(csrf())
                .contentType("application/json").content(body(0, "Missing key", "model", null))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/settings/llm/connections/" + second).with(auth).with(csrf())
                .contentType("application/json").content(body(0, "Retry old create", "model", "synthetic-key"))).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/settings/llm/connections").with(auth))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-first-key"))));
    }
    private JsonNode save(MockMvc mvc, RequestPostProcessor auth, UUID id, int version, String name, String model, String key) throws Exception {
        return json.readTree(mvc.perform(put("/api/v1/settings/llm/connections/" + id).with(auth).with(csrf()).contentType("application/json")
                .content(body(version, name, model, key))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode activate(MockMvc mvc, RequestPostProcessor auth, UUID id, int version, int active) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/settings/llm/connections/" + id + "/activate").with(auth).with(csrf())
                .contentType("application/json").content("{\"expectedVersion\":" + version + ",\"expectedActiveVersion\":" + active + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private String body(int version, String name, String model, String key) {
        var body = json.createObjectNode().put("expectedVersion", version).put("name", name)
                .put("endpoint", "https://10.0.0.5/v1").put("modelId", model);
        if (key != null) body.put("apiKey", key);
        return body.toString();
    }
    private String secret(LlmProviderConfig config) {
        return cipher.decrypt(config.id(), config.version(), new CredentialCipher.Encrypted(config.credentialCiphertext(), config.credentialNonce(), config.keyVersion()));
    }
}
