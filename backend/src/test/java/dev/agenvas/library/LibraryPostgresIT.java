package dev.agenvas.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.testing.ImageAssetFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Public HTTP acceptance tests; fixtures use existing application boundaries. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=library-integration-test-secret")
class LibraryPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = temporaryRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AssetService assets;
    @Autowired ArtifactService artifacts;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;

    @Test void savesTheDisplayedImageAsAnIndependentClassifiedAsset() throws Exception {
        AdminPrincipal owner = identities.setup("library-integration-test-secret", "library-admin", "library-password-123");
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        Project source = projects.create(owner.userId(), "Source", Project.AspectRatio.LANDSCAPE_16_9);
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), source.id());
        var artifact = artifacts.create(owner.userId(), source.id(), Artifact.Kind.IMAGE, "海边旅馆",
                mapper.valueToTree(Map.of("sourceType", "UPLOAD", "assetId", assetId)));
        UUID itemId = UUID.randomUUID();
        mvc.perform(post("/api/v1/projects/" + source.id() + "/canvas/commands")
                .with(auth).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("commands", List.of(Map.of(
                        "type", "PLACE_ARTIFACT", "itemId", itemId, "artifactId", artifact.artifact().id(),
                        "x", 0, "y", 0, "width", 320, "height", 320, "zIndex", 0, "locked", false))))))
                .andExpect(status().isOk());
        String savePath = "/api/v1/projects/" + source.id() + "/canvas-items/" + itemId + "/library-saves";
        JsonNode accepted = mapper.readTree(mvc.perform(post(savePath).with(auth).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of(
                        "versionId", artifact.resourceDefaultVersion().id(), "expectedSelectionEpoch", 0,
                        "name", "海边旅馆", "category", "SCENE", "commandKey", "save-hotel"))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat(accepted.path("id").asText()).isNotBlank();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode list = mapper.readTree(mvc.perform(get("/api/v1/library/entries?category=SCENE").with(auth))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(list.path("items").size()).isEqualTo(1);
            assertThat(list.path("items").get(0).path("name").asText()).isEqualTo("海边旅馆");
            assertThat(list.path("items").get(0).path("kind").asText()).isEqualTo("IMAGE");
        });
        JsonNode saved = awaitCommand(mvc, owner, accepted.path("id").asText());
        String entryId = saved.path("result").path("entryId").asText();
        Path original = assets.get(owner.userId(), source.id(), assetId).path();
        byte[] originalBytes = Files.readAllBytes(original);
        Files.delete(original); // Simulate physical cleanup of the old project's immutable file.
        byte[] retained = mvc.perform(get("/api/v1/library/entries/" + entryId + "/content").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(retained).containsExactly(originalBytes);
        mvc.perform(get("/api/v1/library/entries/" + entryId + "/content").with(auth).header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent());
        Project target = projects.create(owner.userId(), "Target", Project.AspectRatio.LANDSCAPE_16_9);
        JsonNode imported = mapper.readTree(mvc.perform(post("/api/v1/projects/" + target.id() + "/library-imports")
                .with(auth).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("entryId", entryId, "expectedVersion", 0,
                        "commandKey", "place-hotel", "x", 10, "y", 20))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        awaitCommand(mvc, owner, imported.path("id").asText());
        JsonNode targetCanvas = mapper.readTree(mvc.perform(get("/api/v1/projects/" + target.id() + "/canvas/items").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(targetCanvas.path("items").size()).isEqualTo(1);
        JsonNode targetItem = targetCanvas.path("items").get(0);
        assertThat(targetItem.path("subjectId").asText()).isNotEqualTo(artifact.artifact().id().toString());
        assertThat(targetItem.path("selectedVersion").path("content").path("sourceType").asText()).isEqualTo("LIBRARY_IMPORT");
        JsonNode draft = mapper.readTree(mvc.perform(get("/api/v1/projects/" + target.id() + "/canvas-items/"
                + targetItem.path("id").asText() + "/media-draft").with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(draft.path("prompt").asText()).isEmpty();
        assertThat(draft.path("mediaInputs").size()).isZero();
        JsonNode history = mapper.readTree(mvc.perform(get("/api/v1/projects/" + target.id() + "/canvas/items/"
                + targetItem.path("id").asText() + "/media-versions").with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(history.path("items").size()).isEqualTo(1);
        JsonNode uploaded = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/library/uploads")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "hotel.png", "image/png", originalBytes))
                .param("kind", "IMAGE").param("name", "直接上传").param("category", "CHARACTER").param("commandKey", "upload-hotel")
                .with(auth).with(csrf())).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        awaitCommand(mvc, owner, uploaded.path("id").asText());
        String referencePath = "/api/v1/projects/" + target.id() + "/canvas-items/"
                + targetItem.path("id").asText() + "/library-references";
        Map<String, Object> reference = Map.of("entryId", entryId, "expectedVersion", 0, "commandKey", "hotel-reference",
                "role", "REFERENCE", "color", "#3B82F6", "draft", Map.of("expectedVersion", draft.path("version").asLong(),
                        "prompt", "海边酒店", "parameters", Map.of(), "mediaInputs", List.of(), "mentions", List.of(),
                        "styleId", "00000000-0000-4000-8000-000000000301"));
        JsonNode referenceCommand = mapper.readTree(mvc.perform(post(referencePath).with(auth).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(reference)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        awaitCommand(mvc, owner, referenceCommand.path("id").asText());
        JsonNode withReference = mapper.readTree(mvc.perform(get("/api/v1/projects/" + target.id() + "/canvas-items/"
                + targetItem.path("id").asText() + "/media-draft").with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(withReference.path("mediaInputs").size()).isEqualTo(1);
        assertThat(withReference.path("prompt").asText()).isEqualTo("海边酒店");
        assertThat(withReference.path("styleId").asText()).isEqualTo("00000000-0000-4000-8000-000000000301");
        JsonNode afterReferenceCanvas = mapper.readTree(mvc.perform(get("/api/v1/projects/" + target.id() + "/canvas/items").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(afterReferenceCanvas.path("items").size()).isEqualTo(1);
        String entryPath = "/api/v1/library/entries/" + entryId;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(entryPath)
                .with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of(
                        "expectedVersion", 0, "name", "旅馆夜景", "category", "PROP", "favorite", true))))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(entryPath)
                .with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of(
                        "expectedVersion", 0, "name", "过期修改", "category", "OTHER", "favorite", false))))
                .andExpect(status().isConflict());
        mvc.perform(post(entryPath + "/trash").with(auth).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/projects/" + target.id() + "/library-imports").with(auth).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("entryId", entryId,
                        "expectedVersion", 2, "commandKey", "trash-import", "x", 0, "y", 0))))
                .andExpect(status().isConflict());
        mvc.perform(post(entryPath + "/restore").with(auth).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":2}")).andExpect(status().isOk());
        var stranger = authentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "stranger"), null, List.of()));
        for (String path : List.of(entryPath, entryPath + "/content", entryPath + "/thumbnail",
                "/api/v1/library/commands/" + accepted.path("id").asText())) {
            mvc.perform(get(path).with(stranger)).andExpect(status().isNotFound());
        }
        mvc.perform(get(entryPath)).andExpect(status().isUnauthorized());
        mvc.perform(post(entryPath + "/trash").with(auth).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":3}")).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(entryPath)
                .with(auth).with(csrf()).param("expectedVersion", "4")).andExpect(status().isNoContent());
        mvc.perform(get(entryPath).with(auth)).andExpect(status().isNotFound());
        // Imported bytes belong to the target project, even after permanent library removal.
        UUID targetAsset = UUID.fromString(targetItem.path("selectedVersion").path("content").path("assetId").asText());
        assertThat(Files.readAllBytes(assets.get(owner.userId(), target.id(), targetAsset).path())).containsExactly(originalBytes);
    }

    private JsonNode awaitCommand(MockMvc mvc, AdminPrincipal owner, String id) {
        JsonNode[] result = new JsonNode[1];
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            result[0] = mapper.readTree(mvc.perform(get("/api/v1/library/commands/" + id)
                    .with(authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(result[0].path("status").asText()).isEqualTo("SUCCEEDED");
        });
        return result[0];
    }

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-library-test-"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
