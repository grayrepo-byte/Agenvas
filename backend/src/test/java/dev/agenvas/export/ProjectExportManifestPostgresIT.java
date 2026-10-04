package dev.agenvas.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.agent.application.AgentInstanceService;
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
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.library.domain.LibraryEntry;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.settings.application.LlmProviderConfigService;
import dev.agenvas.settings.application.MediaStyleService;
import dev.agenvas.skill.application.SkillRunService;
import dev.agenvas.skill.application.SkillService;
import dev.agenvas.skill.domain.SkillContent;
import dev.agenvas.task.application.DirectMediaTaskService;
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
        "agenvas.library.worker-enabled=false",
        "agenvas.skill.worker-enabled=false",
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
    @Autowired private MediaStyleService styles;
    @Autowired private AgentInstanceService agents;
    @Autowired private SkillService skills;
    @Autowired private SkillRunService skillRuns;
    @Autowired private LibraryService library;
    @Autowired private DirectMediaTaskService directMedia;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext context;

    @Test
    void ownerDownloadsAllVersionsAndMediaMetadataWithoutConfigurationOrSignedLinks()
            throws Exception {
        AdminPrincipal owner = identities.setup("manifest-admin", "manifest-password-123");
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
        var style = styles.create("Exported watercolor", "Synthetic", "Soft paper texture", true);
        mediaDrafts.save(owner.userId(), project.id(), targetItemId, 0,
                "Animate the exact frame", mapper.createObjectNode(), 5, null,
                MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(
                        revisedImage.resourceDefaultVersion().id(),
                        MediaDraft.InputRole.REFERENCE, "#7C3AED")), List.of(), style.id());
        connections.connect(owner.userId(), project.id(), sourceItemId, targetItemId,
                revisedImage.resourceDefaultVersion().id(),
                CanvasConnection.RelationType.MEDIA_INPUT, 1);

        var librarySource = library.source(owner.userId(), project.id(), sourceItemId);
        var libraryCommand = library.save(owner.userId(), project.id(), sourceItemId,
                librarySource.versionId(), librarySource.expectedSelectionEpoch(), librarySource.expectedArtifactVersion(),
                "Synthetic watercolor reference", LibraryEntry.Category.OTHER, "manifest-library-save");
        assertThat(library.processNext()).isTrue();
        UUID libraryEntryId = UUID.fromString(library.command(owner.userId(), libraryCommand.id())
                .result().path("entryId").asText());
        var skill = skills.create(owner.userId(), "Exported creative method", "Synthetic export fixture");
        var emptyDraft = skills.getDraft(owner.userId(), skill.id());
        var firstDraft = skills.saveDraft(owner.userId(), skill.id(), emptyDraft.version(),
                new SkillContent.DraftContent(SkillContent.SCHEMA_VERSION, emptyDraft.skillMd(),
                        emptyDraft.outputKinds(), emptyDraft.inputSlots(),
                        List.of(new SkillContent.Resource("references/style-guide.md", "Use the warm watercolor palette.")),
                        List.of(new SkillContent.DraftAsset("style-reference", libraryEntryId, 0L, null, null, null,
                                SkillContent.Usage.PROVIDER_REFERENCE, true, "Synthetic palette reference"))));
        var publication = skills.publish(owner.userId(), skill.id(), firstDraft.version(), "manifest-skill-publish");
        assertThat(skills.processNext()).isTrue();
        UUID skillVersionId = skills.getOperation(owner.userId(), publication.id()).resultVersionId();
        var agent = agents.create(owner.userId(), project.id(), "Export Creator", "Use the selected method", List.of());
        skillRuns.saveBinding(owner.userId(), project.id(), agent.id(), agent.version(), skill.id(), skillVersionId,
                "manifest-agent-binding");
        skillRuns.install(owner.userId(), project.id(), agent.id(), skill.id(), skillVersionId, "manifest-skill-install");
        assertThat(skillRuns.processNext()).isTrue();
        var secondDraft = skills.saveDraft(owner.userId(), skill.id(), firstDraft.version(),
                new SkillContent.DraftContent(SkillContent.SCHEMA_VERSION, firstDraft.skillMd() + "\nUse the cold palette.\n",
                        firstDraft.outputKinds(), firstDraft.inputSlots(),
                        List.of(new SkillContent.Resource("references/style-guide.md", "Use the cold watercolor palette.")),
                        firstDraft.assets()));
        var secondPublication = skills.publish(owner.userId(), skill.id(), secondDraft.version(), "manifest-skill-publish-next");
        assertThat(skills.processNext()).isTrue();
        UUID uninstalledVersionId = skills.getOperation(owner.userId(), secondPublication.id()).resultVersionId();
        var uninstalledAgent = agents.create(owner.userId(), project.id(), "Future Creator", "Use a different fixed version", List.of());
        skillRuns.saveBinding(owner.userId(), project.id(), uninstalledAgent.id(), uninstalledAgent.version(),
                skill.id(), uninstalledVersionId, "manifest-uninstalled-agent-binding");
        assertThat(skillRuns.preview(owner.userId(), project.id(), agent.id(), null).installed()).isTrue();
        assertThat(skillRuns.preview(owner.userId(), project.id(), uninstalledAgent.id(), null).installed()).isFalse();
        var otherProject = projects.create(owner.userId(), "Other project", Project.AspectRatio.LANDSCAPE_16_9);
        var otherAgent = agents.create(owner.userId(), otherProject.id(), "Other Creator", "Keep this binding private to the other project", List.of());
        skillRuns.saveBinding(owner.userId(), otherProject.id(), otherAgent.id(), otherAgent.version(),
                skill.id(), uninstalledVersionId, "other-project-agent-binding");
        ObjectNode skillSource = mapper.createObjectNode().put("schemaVersion", 1)
                .put("skillId", skill.id().toString()).put("skillVersionId", skillVersionId.toString())
                .put("bundleHash", skills.getVersion(owner.userId(), skill.id(), skillVersionId).bundleHash());
        long draftVersion = mediaDrafts.get(owner.userId(), project.id(), targetItemId).version();
        var styledPreflight = directMedia.preflightApproved(owner.userId(), project.id(), video.artifact().id(),
                targetItemId, draftVersion, skillSource);
        assertThat(styledPreflight.safeSummary().path("styleId").asText()).isEqualTo(style.id().toString());
        assertThat(styledPreflight.safeSummary().path("creativeSkill")).isEqualTo(skillSource);

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
        assertThat(manifest.path("schemaVersion").asInt()).isEqualTo(6);
        assertThat(manifest.path("project").path("id").asText())
                .isEqualTo(project.id().toString());
        assertThat(manifest.path("project").has("ownerId")).isFalse();
        JsonNode versionHistory = manifest.path("artifacts").get(0).path("versions");
        assertThat(versionHistory.size()).isEqualTo(2);
        assertThat(versionHistory.get(0).path("content").path("prompt").asText())
                .isEqualTo("Initial frame");
        assertThat(versionHistory.get(1).path("content").path("workflowVersion").asText())
                .isEqualTo("image-v1-test");
        assertThat(versionHistory.get(1).path("content").has("providerConfigVersion")).isFalse();
        assertThat(versionHistory.get(1).path("baseVersionId").asText())
                .isEqualTo(versionHistory.get(0).path("id").asText());
        assertThat(manifest.path("canvasItems").size()).isEqualTo(2);
        JsonNode sourceCard = findById(manifest.path("canvasItems"), sourceItemId);
        assertThat(sourceCard.path("mediaVersionIds").size()).isEqualTo(1);
        assertThat(sourceCard.path("mediaVersionIds").get(0).asText())
                .isEqualTo(revisedImage.resourceDefaultVersion().id().toString());
        JsonNode targetCard = findById(manifest.path("canvasItems"), targetItemId);
        assertThat(targetCard.path("mediaVersionIds").isEmpty()).isTrue();
        assertThat(targetCard.path("selectedVersionId").isNull()).isTrue();
        assertThat(targetCard.path("mediaDraft").path("styleId").asText()).isEqualTo(style.id().toString());
        assertThat(targetCard.path("mediaDraft").path("style").path("promptSuffix").asText())
                .isEqualTo("Soft paper texture");
        assertThat(manifest.path("creativeSkills").size()).isEqualTo(2);
        JsonNode installedSkill = findSkillVersion(manifest.path("creativeSkills"), skillVersionId);
        assertThat(installedSkill.path("version").path("skillMd").asText()).isEqualTo(firstDraft.skillMd());
        assertThat(installedSkill.path("version").path("resources").get(0).path("content").asText())
                .isEqualTo("Use the warm watercolor palette.");
        assertSkillBinding(installedSkill, agent.id(), skill.id(), skillVersionId);
        assertThat(installedSkill.path("mapping").path("schemaVersion").asInt()).isEqualTo(SkillContent.SCHEMA_VERSION);
        assertThat(installedSkill.path("mapping").path("assets").size()).isEqualTo(1);
        JsonNode installedMapping = installedSkill.path("mapping").path("assets").get(0);
        assertThat(installedMapping.path("alias").asText()).isEqualTo("style-reference");
        assertThat(artifacts.requireVersion(owner.userId(), project.id(),
                UUID.fromString(installedMapping.path("artifactId").asText()),
                UUID.fromString(installedMapping.path("artifactVersionId").asText()))
                .content().path("sourceType").asText()).isEqualTo("SKILL_IMPORT");
        JsonNode uninstalledSkill = findSkillVersion(manifest.path("creativeSkills"), uninstalledVersionId);
        assertThat(uninstalledSkill.path("version").path("skillMd").asText()).isEqualTo(secondDraft.skillMd());
        assertThat(uninstalledSkill.path("version").path("resources").get(0).path("content").asText())
                .isEqualTo("Use the cold watercolor palette.");
        assertSkillBinding(uninstalledSkill, uninstalledAgent.id(), skill.id(), uninstalledVersionId);
        assertThat(uninstalledSkill.path("mapping").path("schemaVersion").asInt()).isEqualTo(SkillContent.SCHEMA_VERSION);
        assertThat(uninstalledSkill.path("mapping").path("assets").isArray()).isTrue();
        assertThat(uninstalledSkill.path("mapping").path("assets").isEmpty()).isTrue();
        for (JsonNode exportedSkill : manifest.path("creativeSkills")) {
            assertThat(exportedSkill.path("version").path("assets").size()).isEqualTo(1);
            assertThat(exportedSkill.path("version").path("assets").get(0).has("contentUrl")).isFalse();
            assertThat(exportedSkill.path("version").path("assets").get(0).has("thumbnailUrl")).isFalse();
            assertThat(exportedSkill.path("version").path("assets").get(0).has("media")).isFalse();
        }
        JsonNode input = targetCard.path("mediaDraft").path("mediaInputs").get(0);
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
                "ownerId", "session", "libraryEntryId", "preparedAssets", "\"pin\"",
                otherProject.id().toString(), otherAgent.id().toString());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(authentication(asUser(new AdminPrincipal(
                        UUID.randomUUID(), "foreign")))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/" + UUID.randomUUID() + "/export-manifest")
                        .with(authentication(asUser(owner))))
                .andExpect(status().isNotFound());
        styles.update(style.id(), style.version(), style.name(), style.category(), "Charcoal texture", true);
        var changedStyle = directMedia.preflightApproved(owner.userId(), project.id(), video.artifact().id(),
                targetItemId, draftVersion, skillSource);
        assertThat(changedStyle.frozenInputHash()).isNotEqualTo(styledPreflight.frozenInputHash());
        assertThat(changedStyle.safeSummary().path("creativeSkill")).isEqualTo(skillSource);
        ObjectNode changedSource = skillSource.deepCopy().put("bundleHash", "synthetic-other-bundle");
        assertThat(directMedia.preflightApproved(owner.userId(), project.id(), video.artifact().id(),
                targetItemId, draftVersion, changedSource).frozenInputHash()).isNotEqualTo(changedStyle.frozenInputHash());
    }

    private ObjectNode mediaContent(UUID assetId, String prompt) {
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", assetId.toString());
        content.put("prompt", prompt);
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

    private JsonNode findSkillVersion(JsonNode values, UUID id) {
        for (JsonNode value : values) {
            if (id.toString().equals(value.path("version").path("id").asText())) return value;
        }
        throw new AssertionError("Missing Skill version " + id);
    }

    private void assertSkillBinding(JsonNode skill, UUID agentId, UUID skillId, UUID versionId) {
        assertThat(skill.path("agentBindings").size()).isEqualTo(1);
        JsonNode binding = skill.path("agentBindings").get(0);
        assertThat(binding.path("agentId").asText()).isEqualTo(agentId.toString());
        assertThat(binding.path("skillId").asText()).isEqualTo(skillId.toString());
        assertThat(binding.path("skillVersionId").asText()).isEqualTo(versionId.toString());
    }

    private static UsernamePasswordAuthenticationToken asUser(AdminPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }
}
