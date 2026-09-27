package dev.agenvas.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.settings.application.LlmProviderConfigService;
import dev.agenvas.testing.ImageAssetFixture;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL/HTTP evidence that manifest history is complete but internal fields are absent. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=manifest-integration-secret",
        "agenvas.settings.llm.allow-loopback-http=true"})
class ProjectExportManifestPostgresIT {

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
    @Autowired private ProjectService projects;
    @Autowired private AssetService assets;
    @Autowired private ArtifactService artifacts;
    @Autowired private CanvasService canvas;
    @Autowired private MediaDraftService mediaDrafts;
    @Autowired private CanvasConnectionService connections;
    @Autowired private LlmProviderConfigService llmConfigs;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext context;

    @Test
    void ownerDownloadsAllVersionsAndMediaMetadataWithoutConfigurationOrSignedLinks()
            throws Exception {
        AdminPrincipal owner = identities.setup("manifest-integration-secret",
                "manifest-admin", "manifest-password-123");
        String actualKey = "manifest-private-provider-key-123";
        llmConfigs.replace(0, "http://127.0.0.1:18080/v1",
                "manifest-private-model", actualKey);
        Project project = projects.create(owner.userId(), "Manifest project",
                Project.AspectRatio.LANDSCAPE_16_9);
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        ObjectNode content = mediaContent(assetId, "Initial frame");
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Frame", content);
        var revisedImage = artifacts.revise(owner.userId(), project.id(), image.artifact().id(),
                image.artifact().version(), "Frame revised",
                mediaContent(assetId, "Revised frame"));
        var video = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO,
                "Video", null);
        UUID sourceItemId = UUID.randomUUID();
        UUID targetItemId = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(
                new CanvasService.PlaceArtifact(sourceItemId, image.artifact().id(),
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"),
                        new BigDecimal("240"), 0, null, false),
                new CanvasService.PlaceArtifact(targetItemId, video.artifact().id(),
                        new BigDecimal("400"), BigDecimal.ZERO, new BigDecimal("320"),
                        new BigDecimal("240"), 1, null, false)));
        mediaDrafts.save(owner.userId(), project.id(), targetItemId, 0,
                "Animate the exact frame", mapper.createObjectNode(), 5, null,
                MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveImageInput(
                        revisedImage.resourceDefaultVersion().id(),
                        MediaDraft.InputRole.REFERENCE, "#7C3AED")), List.of());
        connections.connect(owner.userId(), project.id(), sourceItemId, targetItemId,
                revisedImage.resourceDefaultVersion().id(),
                CanvasConnection.RelationType.MEDIA_INPUT, 1);

        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        String path = "/api/v1/projects/" + project.id() + "/export-manifest";
        MockHttpSession session = new MockHttpSession(null,
                "manifest-private-session-id-789");
        session.setAttribute("privateSessionMarker", "manifest-session-secret-456");
        String json = mvc.perform(get(path).session(session)
                        .with(authentication(asUser(owner))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"agenvas-project-" + project.id() + ".json\""))
                .andReturn().getResponse().getContentAsString();
        JsonNode manifest = mapper.readTree(json);
        assertThat(manifest.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(manifest.path("project").path("id").asText())
                .isEqualTo(project.id().toString());
        assertThat(manifest.path("project").has("ownerId")).isFalse();
        JsonNode versionHistory = manifest.path("artifacts").get(0).path("versions");
        assertThat(versionHistory.size()).isEqualTo(2);
        assertThat(versionHistory.get(0).path("content").path("prompt").asText())
                .isEqualTo("Initial frame");
        assertThat(versionHistory.get(1).path("content").path("workflowVersion").asText())
                .isEqualTo("image-v1-test");
        assertThat(versionHistory.get(1).path("content").path("providerConfigVersion").asInt())
                .isEqualTo(3);
        assertThat(versionHistory.get(1).path("baseVersionId").asText())
                .isEqualTo(versionHistory.get(0).path("id").asText());
        assertThat(manifest.path("canvasItems").size()).isEqualTo(2);
        JsonNode targetCard = findById(manifest.path("canvasItems"), targetItemId);
        assertThat(targetCard.path("selectedVersionId").isNull()).isTrue();
        JsonNode input = targetCard.path("mediaDraft").path("imageInputs").get(0);
        assertThat(input.path("versionId").asText())
                .isEqualTo(revisedImage.resourceDefaultVersion().id().toString());
        assertThat(input.path("sources").size()).isEqualTo(2);
        assertThat(manifest.path("connections").size()).isEqualTo(1);
        assertThat(manifest.path("connections").get(0)
                .path("sourceArtifactVersionId").asText())
                .isEqualTo(revisedImage.resourceDefaultVersion().id().toString());
        assertThat(manifest.path("assets").get(0).path("id").asText())
                .isEqualTo(assetId.toString());
        assertThat(json).doesNotContain(actualKey, "manifest-private-model",
                "127.0.0.1:18080", "manifest-session-secret-456", session.getId(),
                "TOP_SECRET_KEY", "signedUrl",
                "sourceTaskId", "providerRequestId", "objectKey", "thumbnailKey",
                "ownerId", "session");
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(authentication(asUser(new AdminPrincipal(
                        UUID.randomUUID(), "foreign")))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/" + UUID.randomUUID() + "/export-manifest")
                        .with(authentication(asUser(owner))))
                .andExpect(status().isNotFound());
    }

    private ObjectNode mediaContent(UUID assetId, String prompt) {
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", assetId.toString());
        content.put("prompt", prompt);
        content.put("providerConfigVersion", 3);
        content.put("workflowVersion", "image-v1-test");
        content.put("sourceTaskId", UUID.randomUUID().toString());
        ObjectNode parameters = content.putObject("parameters");
        parameters.put("privateMarker", "TOP_SECRET_KEY");
        parameters.put("signedUrl", "https://example.test/reusable-token");
        parameters.put("providerRequestId", "internal-request");
        return content;
    }

    private JsonNode findById(JsonNode values, UUID id) {
        for (JsonNode value : values) {
            if (id.toString().equals(value.path("id").asText())) return value;
        }
        throw new AssertionError("Missing manifest entry " + id);
    }

    private static UsernamePasswordAuthenticationToken asUser(AdminPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }
}
