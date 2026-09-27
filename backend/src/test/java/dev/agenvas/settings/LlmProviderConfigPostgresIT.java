package dev.agenvas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.settings.application.CredentialProperties;
import dev.agenvas.settings.application.LlmProviderConfigRepository;
import dev.agenvas.settings.application.LlmProviderConfigService;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Administrator API and real PostgreSQL proof for encrypted, versioned LLM credentials. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=llm-settings-integration-secret",
        "agenvas.llm.mode=configured",
        "agenvas.credentials.key-version=7",
        "agenvas.settings.llm.allow-loopback-http=true"})
class LlmProviderConfigPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired private IdentityService identities;
    @Autowired private LlmProviderConfigService configs;
    @Autowired private LlmProviderConfigRepository repository;
    @Autowired private ChatGateway gateway;
    @Autowired private CredentialCipher cipher;
    @Autowired private JdbcClient jdbc;
    @Autowired private WebApplicationContext context;

    @Test
    void adminOnlyEncryptedRotationIsMaskedAndVersioned() throws Exception {
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        AdminPrincipal admin = identities.setup("llm-settings-integration-secret",
                "settings-admin", "settings-password-123");
        var auth = authentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
        mvc.perform(get("/api/v1/settings/llm"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/settings/llm").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.version").value(0));

        String firstKey = "local-provider-secret-A123";
        String firstRequest = request(0, "http://127.0.0.1:18080/v1", firstKey);
        mvc.perform(put("/api/v1/settings/llm").with(auth)
                        .contentType("application/json").content(firstRequest))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/settings/llm").with(auth).with(csrf())
                        .contentType("application/json")
                        .content(request(0, "http://169.254.169.254/latest", firstKey)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/settings/llm").with(auth).with(csrf())
                        .contentType("application/json").content(firstRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.keyMask").value("••••A123"))
                .andExpect(jsonPath("$.toolCallingVerified").value(false));
        String getBody = mvc.perform(get("/api/v1/settings/llm").with(auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(getBody).doesNotContain(firstKey, "ciphertext", "credentialNonce", "apiKey");
        mvc.perform(get("/api/v1/settings/diagnostics"))
                .andExpect(status().isUnauthorized());
        String diagnostics = mvc.perform(get("/api/v1/settings/diagnostics").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("AVAILABLE"))
                .andExpect(jsonPath("$.storage").value("AVAILABLE"))
                .andExpect(jsonPath("$.llmMode").value("CONFIGURED"))
                .andExpect(jsonPath("$.llmConfigured").value(true))
                .andExpect(jsonPath("$.llmToolCallingVerified").value(false))
                .andExpect(jsonPath("$.mediaMode").value("MOCK"))
                .andExpect(jsonPath("$.recentErrors.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(diagnostics).doesNotContain(firstKey, "127.0.0.1", "ciphertext",
                "credentialNonce", "apiKey", "storage.root", "providerRequestId");
        assertThat(jdbc.sql("select count(*) from provider_attempt")
                .query(Integer.class).single()).isZero();
        assertThat(configs.status().keyMask()).isEqualTo("••••A123");
        var first = repository.findVersion(1).orElseThrow();
        assertThat(first.keyVersion()).isEqualTo(7);
        assertThat(first.credentialNonce()).hasSize(12);
        assertThat(new String(first.credentialCiphertext(), StandardCharsets.ISO_8859_1))
                .doesNotContain(firstKey);
        assertThat(cipher.decrypt(first.id(), first.version(),
                new CredentialCipher.Encrypted(first.credentialCiphertext(),
                        first.credentialNonce(), first.keyVersion()))).isEqualTo(firstKey);
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), first.version(),
                new CredentialCipher.Encrypted(first.credentialCiphertext(),
                        first.credentialNonce(), first.keyVersion())))
                .isInstanceOf(IllegalStateException.class);

        String secondKey = "rotated-provider-secret-B456";
        mvc.perform(put("/api/v1/settings/llm").with(auth).with(csrf())
                        .contentType("application/json")
                        .content(request(1, "http://127.0.0.1:18080/v1", secondKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.keyMask").value("••••B456"));
        mvc.perform(put("/api/v1/settings/llm").with(auth).with(csrf())
                        .contentType("application/json")
                        .content(request(1, "http://127.0.0.1:18080/v1", secondKey)))
                .andExpect(status().isConflict());
        assertThat(repository.findVersion(1)).isPresent();
        assertThat(repository.findVersion(1).orElseThrow().active()).isFalse();
        assertThat(repository.active().orElseThrow().version()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from llm_provider_config")
                .query(Integer.class).single()).isEqualTo(2);

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> left = pool.submit(() -> attempt(start, "parallel-key-left-1234"));
            Future<Boolean> right = pool.submit(() -> attempt(start, "parallel-key-right-5678"));
            start.countDown();
            assertThat(List.of(left.get(), right.get()).stream().filter(Boolean::booleanValue)
                    .count()).isEqualTo(1);
        }
        assertThat(repository.active().orElseThrow().version()).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from llm_provider_config")
                .query(Integer.class).single()).isEqualTo(3);
        assertThat(gateway.configVersion()).isEqualTo(3);
        assertThat(gateway.modelDetails().modelId()).isEqualTo("test-tool-model");
        assertThat(gateway.modelDetails().providerAdapter()).isEqualTo("OpenAI-compatible");
        assertThat(gateway.capabilities().toolCalling()).isFalse();
        // The Agent path refuses an unverified tool protocol before any request is sent; direct text
        // generation may still use the stored endpoint, so the guard sits on requireToolCalling.
        assertThatThrownBy(() -> gateway.requireToolCalling(gateway.configIdentity()))
                .isInstanceOf(ApiProblemException.class);

        verifyCredentialBackupRestore();
    }

    /** Restores a real pg_dump into an empty PostgreSQL instance, then opens rows with a fresh keyring. */
    private void verifyCredentialBackupRestore() throws Exception {
        Path backup = Files.createTempFile("agenvas-credential-backup-", ".dump");
        try (PostgreSQLContainer restored = new PostgreSQLContainer("postgres:17.11-alpine")) {
            var dump = POSTGRES.execInContainer("pg_dump", "-U", POSTGRES.getUsername(),
                    "-d", POSTGRES.getDatabaseName(), "-Fc", "-f", "/tmp/agenvas.dump");
            assertThat(dump.getExitCode()).isZero();
            POSTGRES.copyFileFromContainer("/tmp/agenvas.dump", backup.toString());
            assertThat(Files.size(backup)).isGreaterThan(0);

            restored.start();
            restored.copyFileToContainer(MountableFile.forHostPath(backup),
                    "/tmp/agenvas.dump");
            var restore = restored.execInContainer("pg_restore", "-U", restored.getUsername(),
                    "-d", restored.getDatabaseName(), "--no-owner", "--no-privileges",
                    "--clean", "--if-exists", "/tmp/agenvas.dump");
            assertThat(restore.getExitCode()).isZero();

            String originalKey = Base64.getEncoder().encodeToString(new byte[32]);
            byte[] nextKeyBytes = new byte[32];
            java.util.Arrays.fill(nextKeyBytes, (byte) 1);
            String nextKey = Base64.getEncoder().encodeToString(nextKeyBytes);
            CredentialCipher restoredCipher = new CredentialCipher(new CredentialProperties(
                    originalKey, 7, ""));
            CredentialCipher rotatedCipher = new CredentialCipher(new CredentialProperties(
                    nextKey, 8, "7=" + originalKey));
            CredentialCipher missingPrevious = new CredentialCipher(new CredentialProperties(
                    nextKey, 8, ""));
            CredentialCipher missingCipher = new CredentialCipher(new CredentialProperties(
                    "", 7, ""));
            try (var connection = DriverManager.getConnection(restored.getJdbcUrl(),
                    restored.getUsername(), restored.getPassword());
                    var statement = connection.prepareStatement("""
                            select id, version, credential_ciphertext, credential_nonce,
                                   key_version from llm_provider_config order by version
                            """);
                    var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    UUID configId = rows.getObject("id", UUID.class);
                    int version = rows.getInt("version");
                    var encrypted = new CredentialCipher.Encrypted(
                            rows.getBytes("credential_ciphertext"),
                            rows.getBytes("credential_nonce"), rows.getInt("key_version"));
                    String decrypted = restoredCipher.decrypt(configId, version, encrypted);
                    assertThat(rotatedCipher.decrypt(configId, version, encrypted))
                            .isEqualTo(decrypted);
                    switch (version) {
                        case 1 -> assertThat(decrypted).isEqualTo("local-provider-secret-A123");
                        case 2 -> assertThat(decrypted).isEqualTo("rotated-provider-secret-B456");
                        case 3 -> assertThat(decrypted).isIn("parallel-key-left-1234",
                                "parallel-key-right-5678");
                        default -> throw new AssertionError("Unexpected restored config version");
                    }
                    assertThatThrownBy(() -> missingCipher.decrypt(configId, version, encrypted))
                            .isInstanceOf(ApiProblemException.class)
                            .satisfies(problem -> assertThat(((ApiProblemException) problem).code())
                                    .isEqualTo("CREDENTIAL_KEY_VERSION_MISSING"));
                    assertThatThrownBy(() -> missingPrevious.decrypt(
                            configId, version, encrypted))
                            .isInstanceOf(ApiProblemException.class)
                            .satisfies(problem -> assertThat(((ApiProblemException) problem).code())
                                    .isEqualTo("CREDENTIAL_KEY_VERSION_MISSING"));
                    count++;
                }
                assertThat(count).isEqualTo(3);
            }
        } finally {
            Files.deleteIfExists(backup);
        }
    }

    private boolean attempt(CountDownLatch start, String key) throws Exception {
        start.await();
        try {
            configs.replace(2, "http://127.0.0.1:18080/v1", "test-tool-model", key);
            return true;
        } catch (ApiProblemException conflict) {
            assertThat(conflict.code()).isEqualTo("PROVIDER_CONFIG_VERSION_CONFLICT");
            return false;
        }
    }

    private String request(int expectedVersion, String endpoint, String key) {
        return "{\"expectedVersion\":" + expectedVersion + ",\"endpoint\":\"" + endpoint
                + "\",\"modelId\":\"test-tool-model\",\"apiKey\":\"" + key + "\"}";
    }
}
