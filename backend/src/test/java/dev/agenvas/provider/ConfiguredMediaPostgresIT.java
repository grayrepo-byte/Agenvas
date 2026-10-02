package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.ProviderModeProperties;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.settings.application.SystemDiagnosticsService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
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
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL startup and catalog validation; no external Provider calls are made. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=configured-media-synthetic-bootstrap",
        "agenvas.provider.mode=configured",
        "agenvas.llm.mode=configured",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class ConfiguredMediaPostgresIT {
    private static final UUID MOCK_IMAGE = UUID.fromString("00000000-0000-4000-8000-000000000102");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MediaCapabilityService catalog;
    @Autowired private SystemDiagnosticsService diagnostics;
    @Autowired private ProviderModeProperties mode;
    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void deploymentStartsWithoutMockDefaultsAndKeepsHistoryWhileUsingPublishedRealCapabilities() throws Exception {
        assertThat(mode.mode()).isEqualTo(ProviderModeProperties.Mode.CONFIGURED);
        assertThat(context.getBeansOfType(ComfyUiClient.class)).isEmpty();
        assertThat(context.getBeansOfType(ComfyUiClientRegistry.class)).isEmpty();
        assertThat(catalog.publishedCandidates()).isEmpty();
        assertThat(diagnostics.snapshot().mediaMode()).isEqualTo("CONFIGURED");
        assertThat(diagnostics.snapshot().imageConfigured()).isFalse();
        assertThat(diagnostics.snapshot().videoConfigured()).isFalse();

        var historical = catalog.capabilitySnapshot(MOCK_IMAGE);
        var pinned = new MediaCapabilityBinding(historical.connection().id(), 1, MOCK_IMAGE, 1,
                historical.adapterId(), historical.mappingSha256());
        assertThat(catalog.pinnedSnapshot(pinned)).isEqualTo(historical);
        assertThatThrownBy(() -> catalog.defaultFor(Task.Kind.IMAGE_GENERATION))
                .isInstanceOf(ApiProblemException.class);

        var admin = identities.setup("configured-media-synthetic-bootstrap", "configured-media-admin",
                "synthetic-media-password-123");
        var auth = authentication(new UsernamePasswordAuthenticationToken(admin, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        JsonNode initialSettings = mapper.readTree(mvc.perform(get("/api/v1/settings/media-connections").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode connection : initialSettings.path("connections"))
            assertThat(connection.path("platform").asText()).isNotEqualTo("MOCK");
        for (JsonNode mediaDefault : initialSettings.path("defaults")) {
            assertThat(mediaDefault.has("capabilityId")).isTrue();
            assertThat(mediaDefault.path("capabilityId").isNull()).isTrue();
        }

        Project project = projects.create(admin.userId(), "Configured media workspace", Project.AspectRatio.LANDSCAPE_16_9);
        String base = "/api/v1/projects/" + project.id();
        for (String kind : List.of("IMAGE", "VIDEO", "AUDIO")) {
            JsonNode artifact = mapper.readTree(mvc.perform(post(base + "/artifacts").with(auth).with(csrf())
                            .contentType("application/json").header("Idempotency-Key", "configured-empty-" + kind)
                            .content("{\"kind\":\"" + kind + "\",\"title\":\"Empty media\",\"content\":null}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
            UUID itemId = UUID.randomUUID();
            mvc.perform(post(base + "/canvas/commands").with(auth).with(csrf()).contentType("application/json")
                            .content("{\"commands\":[{\"type\":\"PLACE_ARTIFACT\",\"itemId\":\"" + itemId
                                    + "\",\"artifactId\":\"" + artifact.path("id").asText()
                                    + "\",\"x\":0,\"y\":0,\"width\":280,\"height\":240,\"zIndex\":0,\"locked\":false}]}"))
                    .andExpect(status().isOk());
            JsonNode draft = mapper.readTree(mvc.perform(get(base + "/canvas-items/" + itemId + "/media-draft").with(auth))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(draft.path("capabilityId").isNull()).isTrue();
        }

        var connection = catalog.createConnection("Configured ComfyUI", "http://127.0.0.1:8188");
        var image = catalog.publishCapability(connection.id(), "Configured image", "COMFY_IMAGE_V1",
                mapper.readTree("{\"checkpoint\":\"image.safetensors\"}"));
        var video = catalog.publishCapability(connection.id(), "Configured video", "COMFY_VIDEO_V1",
                mapper.readTree("{\"diffusionModel\":\"video.safetensors\",\"textEncoder\":\"text.safetensors\","
                        + "\"vae\":\"vae.safetensors\",\"clipVision\":\"vision.safetensors\"}"));
        catalog.setDefault(Task.Kind.IMAGE_GENERATION, catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), image.id());
        catalog.setDefault(Task.Kind.VIDEO_GENERATION, catalog.defaultVersion(Task.Kind.VIDEO_GENERATION), video.id());
        assertThat(catalog.defaultFor(Task.Kind.IMAGE_GENERATION).adapterId()).isEqualTo("COMFY_IMAGE_V1");
        assertThat(catalog.defaultFor(Task.Kind.VIDEO_GENERATION).adapterId()).isEqualTo("COMFY_VIDEO_V1");
        assertThat(diagnostics.snapshot().imageConfigured()).isTrue();
        assertThat(diagnostics.snapshot().videoConfigured()).isTrue();
        assertThat(catalog.pinnedSnapshot(pinned)).isEqualTo(historical);
        assertThat(jdbc.sql("select enabled from media_provider_connection where id=:id")
                .param("id", historical.connection().id()).query(Boolean.class).single()).isTrue();
        catalog.setConnectionEnabled(connection.id(), connection.version(), false);
        assertThat(diagnostics.snapshot().imageConfigured()).isFalse();
        assertThat(diagnostics.snapshot().videoConfigured()).isFalse();
    }
}
