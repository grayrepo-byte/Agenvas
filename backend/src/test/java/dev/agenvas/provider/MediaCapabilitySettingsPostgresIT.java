package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
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

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=media-settings-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.comfyui.scheduler-enabled=false"})
class MediaCapabilitySettingsPostgresIT {

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

    @Test
    void administratorCreatesMaskedVersionedConnectionAndCapabilities() throws Exception {
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        AdminPrincipal admin = identities.setup("media-settings-integration-secret",
                "media-admin", "media-password-123");
        var adminAuth = authentication(new UsernamePasswordAuthenticationToken(admin, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var userAuth = authentication(new UsernamePasswordAuthenticationToken(admin, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        mvc.perform(get("/api/v1/settings/media-connections")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/settings/media-connections").with(userAuth))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/settings/media-defaults/IMAGE_GENERATION")
                        .with(userAuth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"capabilityId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());

        String key = "secret-media-provider-7890";
        String cloud = "{\"name\":\"OpenAI main\",\"platform\":\"OPENAI\","
                + "\"apiKey\":\"" + key + "\"}";
        String created = mvc.perform(post("/api/v1/settings/media-connections")
                        .with(adminAuth).with(csrf()).header("Idempotency-Key", "cloud-create-1")
                        .contentType("application/json").content(cloud))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections.length()").value(2))
                .andExpect(jsonPath("$.connections[1].keyMask").value("••••7890"))
                .andExpect(jsonPath("$.connections[1].realGenerationTested").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain(key, "ciphertext", "nonce", "apiKey");
        mvc.perform(post("/api/v1/settings/media-connections")
                        .with(adminAuth).with(csrf()).header("Idempotency-Key", "cloud-create-1")
                        .contentType("application/json").content(cloud))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections.length()").value(2));
        mvc.perform(post("/api/v1/settings/media-connections")
                        .with(adminAuth).with(csrf()).header("Idempotency-Key", "cloud-create-1")
                        .contentType("application/json")
                        .content(cloud.replace("OpenAI main", "Different")))
                .andExpect(status().isConflict());
        UUID cloudId = jdbc.sql("select id from media_provider_connection where platform='OPENAI'")
                .query(UUID.class).single();
        var encrypted = jdbc.sql("select credential_ciphertext,credential_nonce,"
                + "credential_key_version from media_provider_connection_version "
                + "where connection_id=:id and version=1")
                .param("id", cloudId).query((rs, row) -> new CredentialCipher.Encrypted(
                        rs.getBytes(1), rs.getBytes(2), rs.getInt(3))).single();
        assertThat(cipher.decryptMedia(cloudId, 1, encrypted)).isEqualTo(key);
        assertThatThrownBy(() -> cipher.decrypt(cloudId, 1, encrypted))
                .isInstanceOf(IllegalStateException.class);

        String comfy = "{\"name\":\"Local Comfy\",\"platform\":\"COMFYUI\","
                + "\"origin\":\"http://127.0.0.1:8188\"}";
        mvc.perform(post("/api/v1/settings/media-connections")
                        .with(adminAuth).with(csrf()).header("Idempotency-Key", "comfy-create-1")
                        .contentType("application/json").content(comfy))
                .andExpect(status().isOk());
        UUID comfyId = jdbc.sql("select id from media_provider_connection where platform='COMFYUI'")
                .query(UUID.class).single();
        mvc.perform(post("/api/v1/settings/media-connections/" + comfyId + "/capabilities")
                        .with(adminAuth).with(csrf()).header("Idempotency-Key", "comfy-image-1")
                        .contentType("application/json")
                        .content("{\"name\":\"Image\",\"adapterId\":\"COMFY_IMAGE_V1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/settings/media-connections/" + comfyId + "/capabilities")
                        .with(adminAuth).with(csrf()).header("Idempotency-Key", "comfy-video-1")
                        .contentType("application/json")
                        .content("{\"name\":\"Video\",\"adapterId\":\"COMFY_VIDEO_V1\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/settings/media-connections").with(adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections[2].capabilities.length()").value(2));
        UUID imageId = jdbc.sql("select id from media_capability where connection_id=:id "
                + "and name='Image'").param("id", comfyId).query(UUID.class).single();
        mvc.perform(put("/api/v1/settings/media-connections/" + comfyId
                        + "/capabilities/" + imageId).with(adminAuth).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"Hero image\","
                                + "\"enabled\":true,\"adapterId\":\"COMFY_IMAGE_V1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections[2].capabilities[0].name").value("Hero image"));
        mvc.perform(put("/api/v1/settings/media-connections/" + comfyId
                        + "/capabilities/" + imageId).with(adminAuth).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"Stale\","
                                + "\"enabled\":true,\"adapterId\":\"COMFY_IMAGE_V1\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/settings/media-defaults/IMAGE_GENERATION")
                        .with(adminAuth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"capabilityId\":\""
                                + imageId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults[0].capabilityId").value(imageId.toString()));
        mvc.perform(post("/api/v1/settings/media-connections")
                        .with(adminAuth).with(csrf()).header("Idempotency-Key", "bad-origin-1")
                        .contentType("application/json")
                        .content(comfy.replace("127.0.0.1", "169.254.169.254")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/settings/media-connections/" + comfyId)
                        .with(adminAuth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"Renamed\","
                                + "\"enabled\":false,\"origin\":\"http://127.0.0.1:8188\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/settings/media-connections/" + comfyId)
                        .with(adminAuth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"Stale\","
                                + "\"enabled\":true,\"origin\":\"http://127.0.0.1:8188\"}"))
                .andExpect(status().isConflict());
    }
}
