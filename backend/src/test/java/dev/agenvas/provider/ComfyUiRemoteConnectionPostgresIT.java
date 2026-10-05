package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.settings.application.CredentialCipher;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class ComfyUiRemoteConnectionPostgresIT {

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
    @Autowired private CredentialCipher cipher;
    @Autowired private JdbcClient jdbc;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper mapper;

    @Test
    void savesFullEndpointEncryptedAndPreservesRedactedEditsAndHistoricalVersions() throws Exception {
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var admin = identities.setup("admin", "test-password-123");
        var auth = authentication(new UsernamePasswordAuthenticationToken(admin, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        String endpoint = "https://comfy.example.com/proxy/synthetic-path-key";
        var request = mapper.createObjectNode().put("name", "Remote Comfy")
                .put("platform", "COMFYUI").put("origin", endpoint + "/");
        String created = mvc.perform(post("/api/v1/settings/media-connections").with(auth).with(csrf())
                .header("Idempotency-Key", "remote-comfy").contentType("application/json").content(request.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain("synthetic-path-key");
        var connection = mapper.readTree(created).path("connections").valueStream()
                .filter(node -> node.path("name").asText().equals("Remote Comfy")).findFirst().orElseThrow();
        UUID id = UUID.fromString(connection.path("id").asText());
        assertThat(connection.path("origin").asText()).isEqualTo("https://comfy.example.com/[configured-path]");
        assertThat(decrypt(id, 1)).isEqualTo(endpoint);
        assertThat(jdbc.sql("select origin from media_provider_connection_version where connection_id=:id")
                .param("id", id).query(String.class).single()).doesNotContain("synthetic-path-key");
        mvc.perform(post("/api/v1/settings/media-connections").with(auth).with(csrf())
                .header("Idempotency-Key", "remote-comfy").contentType("application/json").content(request.toString()))
                .andExpect(status().isOk());
        var edit = mapper.createObjectNode().put("name", "Renamed").put("enabled", false)
                .put("expectedVersion", connection.path("version").asLong()).put("origin", connection.path("origin").asText());
        mvc.perform(put("/api/v1/settings/media-connections/" + id).with(auth).with(csrf())
                .contentType("application/json").content(edit.toString())).andExpect(status().isOk());
        assertThat(decrypt(id, 1)).isEqualTo(endpoint);
        assertThat(jdbc.sql("select count(*) from media_provider_connection_version where connection_id=:id")
                .param("id", id).query(Integer.class).single()).isEqualTo(1);
        edit.put("expectedVersion", connection.path("version").asLong() + 1)
                .put("origin", "https://comfy.example.com/other/synthetic-replacement-key");
        String updated = mvc.perform(put("/api/v1/settings/media-connections/" + id).with(auth).with(csrf())
                .contentType("application/json").content(edit.toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(updated).doesNotContain("synthetic-replacement-key", "synthetic-path-key");
        assertThat(decrypt(id, 2)).isEqualTo("https://comfy.example.com/other/synthetic-replacement-key");
        assertThat(decrypt(id, 1)).isEqualTo(endpoint);
        request.put("origin", "https://169.254.169.254/proxy/synthetic-path-key");
        mvc.perform(post("/api/v1/settings/media-connections").with(auth).with(csrf())
                .header("Idempotency-Key", "blocked-comfy").contentType("application/json").content(request.toString()))
                .andExpect(status().isUnprocessableEntity());
    }

    private String decrypt(UUID id, int version) {
        var encrypted = jdbc.sql("select credential_ciphertext, credential_nonce, credential_key_version "
                + "from media_provider_connection_version where connection_id=:id and version=:version")
                .param("id", id).param("version", version).query((rs, row) -> new CredentialCipher.Encrypted(
                        rs.getBytes(1), rs.getBytes(2), rs.getInt(3))).single();
        return cipher.decryptMedia(id, version, encrypted);
    }
}
