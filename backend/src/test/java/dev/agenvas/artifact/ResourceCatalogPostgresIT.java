package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.ResourceCatalogService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.testing.ImageAssetFixture;
import dev.agenvas.testing.AudioAssetFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Synthetic content, real PostgreSQL and authenticated HTTP; no external Provider calls. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class ResourceCatalogPostgresIT {
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
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaToolRunner mediaTools;
    @Autowired ResourceCatalogService resources;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;

    @Test void browsesAllExactResultsWithoutCanvasLibraryOrDefaultSelectionAndEnforcesOwnership() throws Exception {
        var owner = identities.setup("resource-admin", "synthetic-password-123");
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var firstProject = projects.create(owner.userId(), "First project", Project.AspectRatio.SQUARE_1_1);
        var secondProject = projects.create(owner.userId(), "Second project", Project.AspectRatio.SQUARE_1_1);
        var text = artifacts.create(owner.userId(), firstProject.id(), Artifact.Kind.TEXT, "Notes",
                mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", "Initial text"));
        artifacts.revise(owner.userId(), firstProject.id(), text.artifact().id(), 0, null,
                mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", "Revised text"));
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), secondProject.id());
        var image = artifacts.create(owner.userId(), secondProject.id(), Artifact.Kind.IMAGE, "Sample image",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", assetId.toString()));
        var oldImage = artifacts.revise(owner.userId(), secondProject.id(), image.artifact().id(), 0, null,
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", assetId.toString()));
        UUID audioAssetId = AudioAssetFixture.archive(assets, mediaTools, owner.userId(), secondProject.id());
        var audio = artifacts.create(owner.userId(), secondProject.id(), Artifact.Kind.AUDIO, "Sample audio",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", audioAssetId.toString()));
        artifacts.createSkillImport(owner.userId(), secondProject.id(), "Internal Skill material", assetId);
        artifacts.create(owner.userId(), firstProject.id(), Artifact.Kind.IMAGE, "Empty draft", null);
        // No canvas or personal library entries are created. Clearing the resource default must not hide history.
        jdbc.sql("update artifact set resource_default_version_id = null where id = :id")
                .param("id", image.artifact().id()).update();
        projects.archive(owner.userId(), secondProject.id(), secondProject.version());
        var page = resources.list(owner.userId(), null, "", null, 1);
        assertThat(page.items()).hasSize(1);
        var next = resources.list(owner.userId(), null, "", page.nextCursor(), 1);
        assertThat(next.items()).hasSize(1);
        var last = resources.list(owner.userId(), null, "", next.nextCursor(), 1);
        assertThat(last.items()).hasSize(1);
        assertThat(last.nextCursor()).isNull();
        assertThat(java.util.stream.Stream.of(page, next, last).flatMap(part -> part.items().stream()).map(row -> row.versionId()))
                .containsExactlyInAnyOrder(oldImage.resourceDefaultVersion().id(), image.resourceDefaultVersion().id(), audio.resourceDefaultVersion().id());
        assertThat(resources.list(UUID.randomUUID(), null, "", null, null).items()).isEmpty();
        assertThat(resources.list(owner.userId(), Artifact.Kind.IMAGE, "Second", null, null).items())
                .extracting(row -> row.versionId()).containsExactlyInAnyOrder(oldImage.resourceDefaultVersion().id(), image.resourceDefaultVersion().id());
        assertThat(resources.list(owner.userId(), null, "%", null, null).items()).isEmpty();
        var response = mvc.perform(get("/api/v1/resources").with(auth).param("kind", "IMAGE"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString());
        assertThat(json.path("items").size()).isEqualTo(2);
        assertThat(json.path("items").get(0).path("versionId").asText()).isEqualTo(oldImage.resourceDefaultVersion().id().toString());
        assertThat(json.path("items").get(0).path("content").path("assetId").asText()).isEqualTo(assetId.toString());
        assertThat(artifacts.listVersions(owner.userId(), firstProject.id(), text.artifact().id())).hasSize(2);
        mvc.perform(get("/api/v1/resources").with(auth).param("kind", "TEXT")).andExpect(status().isBadRequest());
        var audioResponse = mvc.perform(get("/api/v1/resources").with(auth).param("kind", "AUDIO"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var audioJson = mapper.readTree(audioResponse.getContentAsString());
        assertThat(audioJson.path("items").size()).isEqualTo(1);
        assertThat(audioJson.path("items").get(0).path("kind").asText()).isEqualTo("AUDIO");
        assertThat(audioJson.path("items").get(0).path("content").path("assetId").asText()).isEqualTo(audioAssetId.toString());
        mvc.perform(get("/api/v1/resources")).andExpect(status().isUnauthorized());
        var invalid = mvc.perform(get("/api/v1/resources").with(auth).param("cursor", "!"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(mapper.readTree(invalid.getContentAsString()).path("code").asText()).isEqualTo("RESOURCE_QUERY_INVALID");
        mvc.perform(get("/api/v1/resources").with(auth).param("limit", "0")).andExpect(status().isBadRequest());
    }

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-resource-catalog-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
