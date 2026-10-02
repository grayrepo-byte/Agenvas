package dev.agenvas.mediatemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.mediatemplate.application.MediaTemplateService;
import dev.agenvas.mediatemplate.domain.MediaTemplate.Scope;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.infrastructure.MediaTemplateRepository;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.ImageAssetFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and decoder-validated synthetic PNGs; no model or provider calls. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=media-template-synthetic-bootstrap")
class MediaTemplatePostgresIT {
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
    @Autowired MediaTemplateService templates;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    @MockitoSpyBean MediaTemplateRepository repository;
    private static AdminPrincipal owner;
    private static AdminPrincipal other;
    private MockMvc mvc;

    @BeforeEach void setup() {
        if (owner == null) {
        owner = identities.setup("media-template-synthetic-bootstrap", "template-admin", "template-synthetic-password");
        // The deployment permits one active administrator; a synthetic disabled identity
        // with explicit test authentication still exercises owner and authority boundaries.
        other = new AdminPrincipal(UUID.randomUUID(), "template-other");
        jdbc.sql("insert into app_user(id,login_name,password_hash,status,created_at) values (:id,:name,'synthetic-unused-hash','DISABLED',:created)")
                .param("id", other.userId()).param("name", other.loginName()).param("created", java.sql.Timestamp.from(Instant.now())).update();
        }
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void personalCatalogueAndImageReadsAreScopedAndTypesAreRestricted() throws Exception {
        JsonNode image = upload(owner);
        JsonNode personal = create(owner, false, "水彩风格", "IMAGE", "水彩，柔和光影", List.of(image.path("id").asText()));
        mvc.perform(get("/api/v1/media-templates/" + personal.path("id").asText()).with(auth(other, false))).andExpect(status().isNotFound());
        mvc.perform(get(image.path("contentUrl").asText()).with(auth(other, false))).andExpect(status().isNotFound());
        JsonNode foreignList = read(mvc.perform(get("/api/v1/media-templates?scope=PERSONAL").with(auth(other, false))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(foreignList.path("items").size()).isZero();
        mvc.perform(post("/api/v1/media-templates").with(auth(other, false)).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(write("不可引用", "IMAGE", "提示", List.of(image.path("id").asText()))))).andExpect(status().isNotFound());
        for (String unsupported : List.of("AUDIO", "TEXT")) mvc.perform(post("/api/v1/media-templates").with(auth(owner, false)).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(write("错误类型", unsupported, "提示", List.of())))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/media-templates").with(auth(owner, false)).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(write("重复图片", "IMAGE", "提示", List.of(image.path("id").asText(), image.path("id").asText()))))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/media-templates/images/" + image.path("id").asText()).with(auth(owner, false)).with(csrf())).andExpect(status().isConflict());
        assertThat(personal.toString()).doesNotContain("ownerId", "objectKey", "sha256", "storageKey");
        mvc.perform(get("/api/v1/media-templates")).andExpect(status().isUnauthorized());
    }

    @Test void systemTemplatesRequireAdminAndAllowAnotherAdminToPreserveExistingImages() throws Exception {
        JsonNode image = upload(owner);
        mvc.perform(post("/api/v1/settings/media-templates").with(auth(owner, false)).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(write("系统模板", "VIDEO", "镜头缓慢推进", List.of())))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/settings/media-templates").with(auth(owner, true)).contentType("application/json")
                .content(mapper.writeValueAsString(write("系统模板", "VIDEO", "镜头缓慢推进", List.of())))).andExpect(status().isForbidden());
        JsonNode shared = create(owner, true, "共享运镜", "VIDEO", "镜头缓慢推进", List.of(image.path("id").asText()));
        mvc.perform(get(image.path("thumbnailUrl").asText()).with(auth(other, false))).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/media-templates/" + shared.path("id").asText()).with(auth(owner, true)).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(update("共享运镜", "VIDEO", "镜头推进", List.of(), 0)))).andExpect(status().isNotFound());
        JsonNode changed = read(mvc.perform(patch("/api/v1/settings/media-templates/" + shared.path("id").asText()).with(auth(other, true)).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(update("共享运镜更新", "VIDEO", "镜头推进", List.of(image.path("id").asText()), 0))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(changed.path("version").asLong()).isEqualTo(1);
        mvc.perform(patch("/api/v1/settings/media-templates/" + shared.path("id").asText()).with(auth(owner, true)).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(update("过期更新", "VIDEO", "提示", List.of(), 0)))).andExpect(status().isConflict());
        JsonNode privateOther = upload(other);
        mvc.perform(patch("/api/v1/settings/media-templates/" + shared.path("id").asText()).with(auth(owner, true)).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(update("无权图片", "VIDEO", "提示", List.of(privateOther.path("id").asText()), 1)))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/settings/media-templates/" + shared.path("id").asText()).param("expectedVersion", "1").with(auth(other, true)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(image.path("contentUrl").asText()).with(auth(other, false))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/media-templates/images/" + image.path("id").asText()).with(auth(owner, false)).with(csrf())).andExpect(status().isNoContent());
    }

    @Test void importCopiesExactVersionsReplaysAndSurvivesSourceAndTemplateDeletion() throws Exception {
        Project source = projects.create(owner.userId(), "Synthetic source", Project.AspectRatio.LANDSCAPE_16_9);
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), source.id());
        var artifact = artifacts.create(owner.userId(), source.id(), Artifact.Kind.IMAGE, "合成参考",
                mapper.valueToTree(Map.of("sourceType", "UPLOAD", "assetId", assetId)));
        byte[] original = Files.readAllBytes(assets.get(owner.userId(), source.id(), assetId).path());
        JsonNode image = read(mvc.perform(post("/api/v1/media-templates/images/from-version").with(auth(owner, false)).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("projectId", source.id(), "versionId", artifact.resourceDefaultVersion().id()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        Files.delete(assets.get(owner.userId(), source.id(), assetId).path());
        JsonNode preset = create(owner, false, "参考运镜", "VIDEO", "参考图中的主体缓慢旋转", List.of(image.path("id").asText()));
        Project target = projects.create(owner.userId(), "Synthetic target", Project.AspectRatio.LANDSCAPE_16_9);
        String path = "/api/v1/projects/" + target.id() + "/media-templates/" + preset.path("id").asText() + "/import";
        JsonNode imported = importPreset(path, "same-template-key", 0);
        assertThat(imported.path("targetKind").asText()).isEqualTo("VIDEO");
        assertThat(imported.path("prompt").asText()).isEqualTo("参考图中的主体缓慢旋转");
        UUID importedVersion = UUID.fromString(imported.path("images").get(0).path("versionId").asText());
        UUID importedAsset = UUID.fromString(imported.path("images").get(0).path("assetId").asText());
        assertThat(artifacts.requireImageVersionForTask(owner.userId(), target.id(), importedVersion).content().path("sourceType").asText()).isEqualTo("LIBRARY_IMPORT");
        assertThat(count("canvas_item", target.id())).isZero();
        assertThat(count("task", target.id())).isZero();
        assertThat(count("media_template_import_image", target.id())).isEqualTo(1);
        assertThat(importPreset(path, "same-template-key", 0)).isEqualTo(imported);
        mvc.perform(post(path).with(auth(owner, false)).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("expectedTemplateVersion", 1, "commandKey", "same-template-key")))).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/media-templates/" + preset.path("id").asText()).param("expectedVersion", "0").with(auth(owner, false)).with(csrf())).andExpect(status().isNoContent());
        assertThat(importPreset(path, "same-template-key", 0)).isEqualTo(imported);
        mvc.perform(delete("/api/v1/media-templates/images/" + image.path("id").asText()).with(auth(owner, false)).with(csrf())).andExpect(status().isConflict());
        Files.delete(templates.file(owner.userId(), UUID.fromString(image.path("id").asText()), false).path());
        assertThat(Files.readAllBytes(assets.get(owner.userId(), target.id(), importedAsset).path())).containsExactly(original);
        assertThat(imported.toString()).doesNotContain("objectKey", "ownerId", "sourceProjectId", "sha256");
    }

    @Test void importPublishesAllMetadataAndEventsAtomicallyAndRetriesTheSamePreparedBytes() throws Exception {
        JsonNode image = upload(owner);
        JsonNode preset = create(owner, false, "原子导入", "IMAGE", "柔和灯光", List.of(image.path("id").asText()));
        Project target = projects.create(owner.userId(), "Atomic target", Project.AspectRatio.LANDSCAPE_16_9);
        UUID presetId = UUID.fromString(preset.path("id").asText());
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic commit failure"))
                .when(repository).complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try {
            assertThatThrownBy(() -> templates.importTemplate(owner.userId(), target.id(), presetId, 0, "atomic-import"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(count("asset", target.id())).isZero();
            assertThat(count("artifact", target.id())).isZero();
            assertThat(count("media_template_import_image", target.id())).isZero();
            assertThat(count("project_event", target.id())).isZero();
            assertThat(count("media_template_import_command", target.id())).isEqualTo(1);
        } finally { org.mockito.Mockito.reset(repository); }
        var completed = templates.importTemplate(owner.userId(), target.id(), presetId, 0, "atomic-import");
        assertThat(completed.images()).hasSize(1);
        assertThat(count("asset", target.id())).isEqualTo(1);
        assertThat(count("artifact", target.id())).isEqualTo(1);
        assertThat(count("project_event", target.id())).isEqualTo(2);
    }

    @Test void promptOnlyImportReturnsThePromptWithoutCreatingProjectContentOrTasks() throws Exception {
        JsonNode preset = create(owner, false, "纯提示词风格", "IMAGE", "水彩笔触，柔和光影", List.of());
        Project target = projects.create(owner.userId(), "Prompt-only target", Project.AspectRatio.LANDSCAPE_16_9);
        UUID id = UUID.fromString(preset.path("id").asText());
        var first = templates.importTemplate(owner.userId(), target.id(), id, 0, "prompt-only");
        assertThat(first.targetKind()).isEqualTo(TargetKind.IMAGE);
        assertThat(first.prompt()).isEqualTo("水彩笔触，柔和光影");
        assertThat(first.images()).isEmpty();
        assertThat(templates.importTemplate(owner.userId(), target.id(), id, 0, "prompt-only")).isEqualTo(first);
        assertThat(count("asset", target.id())).isZero();
        assertThat(count("artifact", target.id())).isZero();
        assertThat(count("canvas_item", target.id())).isZero();
        assertThat(count("task", target.id())).isZero();
        assertThat(count("project_event", target.id())).isZero();
    }

    @Test void concurrentReplayCreatesOneSetOfIndependentProjectVersions() throws Exception {
        JsonNode image = upload(owner);
        JsonNode preset = create(owner, false, "并发导入", "VIDEO", "转动主体", List.of(image.path("id").asText()));
        Project target = projects.create(owner.userId(), "Concurrent target", Project.AspectRatio.LANDSCAPE_16_9);
        UUID id = UUID.fromString(preset.path("id").asText());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> templates.importTemplate(owner.userId(), target.id(), id, 0, "concurrent-import"));
            var second = pool.submit(() -> templates.importTemplate(owner.userId(), target.id(), id, 0, "concurrent-import"));
            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(count("asset", target.id())).isEqualTo(1);
        assertThat(count("artifact", target.id())).isEqualTo(1);
    }

    private JsonNode upload(AdminPrincipal principal) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return read(mvc.perform(multipart("/api/v1/media-templates/images")
                .file(new MockMultipartFile("file", "synthetic.png", "image/png", output.toByteArray())).with(auth(principal, false)).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode create(AdminPrincipal principal, boolean system, String name, String kind, String prompt, List<String> images) throws Exception {
        return read(mvc.perform(post(system ? "/api/v1/settings/media-templates" : "/api/v1/media-templates").with(auth(principal, system)).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(write(name, kind, prompt, images))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode importPreset(String path, String key, long expected) throws Exception {
        return read(mvc.perform(post(path).with(auth(owner, false)).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("expectedTemplateVersion", expected, "commandKey", key))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private Map<String, Object> write(String name, String kind, String prompt, List<String> images) {
        return Map.of("name", name, "targetKind", kind, "prompt", prompt, "imageIds", images);
    }
    private Map<String, Object> update(String name, String kind, String prompt, List<String> images, long expected) {
        var value = new java.util.HashMap<>(write(name, kind, prompt, images)); value.put("expectedVersion", expected); return value;
    }
    private RequestPostProcessor auth(AdminPrincipal principal, boolean admin) {
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, admin ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN")) : List.of()));
    }
    private JsonNode read(String json) { return mapper.readTree(json); }
    private int count(String table, UUID project) {
        // Fixed test-only table names; production SQL exclusively uses typed jOOQ.
        return jdbc.sql("select count(*) from " + table + " where project_id=:project").param("project", project).query(Integer.class).single();
    }
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-template-it-"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
