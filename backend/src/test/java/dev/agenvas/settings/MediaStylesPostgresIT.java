package dev.agenvas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.export.application.ProjectExportManifestService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.settings.application.MediaStyleService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.support.CanvasMediaFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL verifies preset CAS and immutable inputs; generation uses the explicit Mock adapter. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=synthetic-media-style-bootstrap",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.comfyui.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class MediaStylesPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> storage.toString());
        registry.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired CanvasService canvas;
    @Autowired MediaDraftService drafts;
    @Autowired MediaStyleService styles;
    @Autowired DirectMediaTaskService direct;
    @Autowired MediaExecutionWorker worker;
    @Autowired MediaCapabilityService capabilities;
    @Autowired dev.agenvas.task.application.TaskService tasks;
    @Autowired dev.agenvas.task.application.ManualUnknownRetryService retries;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired ProjectExportManifestService exports;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    static AdminPrincipal owner;
    MockMvc mvc;
    RequestPostProcessor admin;
    RequestPostProcessor user;

    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("synthetic-media-style-bootstrap", "synthetic-style-admin", "synthetic-style-password-123");
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        admin = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        user = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test void presetPreviewsAndAdministratorCasUploadAreAuthenticated() throws Exception {
        mvc.perform(get("/api/v1/media-styles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/settings/media-styles").with(user)).andExpect(status().isForbidden());
        var catalog = mapper.readTree(mvc.perform(get("/api/v1/media-styles").with(user)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(catalog.size()).isGreaterThanOrEqualTo(8);
        for (var preset : catalog) {
            assertThat(preset.has("promptSuffix")).isFalse();
            if (preset.path("builtIn").asBoolean()) {
                var response = mvc.perform(get(preset.path("thumbnailUrl").asText()).with(user))
                        .andExpect(status().isOk()).andReturn().getResponse();
                assertThat(response.getContentType()).isEqualTo("image/webp");
                assertThat(ImageIO.read(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))).isNotNull();
            }
        }
        String create = "{\"name\":\"Synthetic custom\",\"category\":\"绘画\",\"promptSuffix\":\"Soft watercolor texture\",\"enabled\":true}";
        mvc.perform(post("/api/v1/settings/media-styles").with(admin).contentType("application/json").content(create))
                .andExpect(status().isForbidden());
        var style = mapper.readTree(mvc.perform(post("/api/v1/settings/media-styles").with(admin).with(csrf())
                        .contentType("application/json").content(create)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(style.path("thumbnailUrl").isNull()).isTrue();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12, 16, BufferedImage.TYPE_INT_RGB), "png", png);
        String upload = "/api/v1/settings/media-styles/" + style.path("id").asText() + "/thumbnail";
        var file = new MockMultipartFile("file", "synthetic-preview.jpg", "text/plain", png.toByteArray());
        var saved = mapper.readTree(mvc.perform(multipart(upload).file(file).param("expectedVersion", "0").with(admin).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        mvc.perform(multipart(upload).file(file).param("expectedVersion", "0").with(admin).with(csrf())).andExpect(status().isConflict());
        mvc.perform(multipart(upload).file(new MockMultipartFile("file", "bad.png", "image/png", new byte[] {1,2,3}))
                .param("expectedVersion", "1").with(admin).with(csrf())).andExpect(status().isBadRequest());
        assertThat(styles.settings().stream().filter(value -> value.id().toString().equals(style.path("id").asText())).toList())
                .singleElement().extracting(MediaStyleService.Style::version).isEqualTo(1L);
    }

    @Test void imageTasksFreezeStyleWhileUserPromptCopyRestoreAndExportRemainSeparate() throws Exception {
        var style = styles.create("Synthetic film", "写实", "soft film light", true);
        var fixture = fixture(Artifact.Kind.IMAGE);
        MediaDraft draft = save(fixture, "A paper boat", style.id(), mapper.createObjectNode().put("generationCount", 2));
        var preview = direct.preflight(owner.userId(), fixture.project().id(), fixture.artifact().id(), fixture.card(), draft.version());
        assertThat(preview.safeSummary().path("prompt").asText()).isEqualTo("A paper boat");
        assertThat(preview.safeSummary().path("styleName").asText()).isEqualTo(style.name());
        var task = direct.run(owner.userId(), fixture.project().id(), fixture.artifact().id(), fixture.card(), draft.version(), "synthetic-style-image-batch");
        assertThat(task.input().path("prompt").asText()).isEqualTo("A paper boat\n\nVisual style: soft film light");
        assertThat(task.input().path("mediaInput").path("prompt").asText()).isEqualTo("A paper boat");
        assertThat(task.input().path("mediaInput").path("style").path("version").asLong()).isZero();
        assertThat(drafts.get(owner.userId(), fixture.project().id(), fixture.card()).prompt()).isEqualTo("A paper boat");
        var changed = styles.update(style.id(), style.version(), style.name(), style.category(), "neon night", false);
        assertThat(changed.enabled()).isFalse();
        assertThat(worker.submitOnce("synthetic-styles-worker")).isEqualTo(1);
        assertThat(worker.submitOnce("synthetic-styles-worker")).isEqualTo(1);
        var version = canvas.listMediaVersions(owner.userId(), fixture.project().id(), fixture.card()).getFirst();
        assertThat(version.content().path("prompt").asText()).isEqualTo("A paper boat");
        assertThat(version.frozenInput().path("style").path("promptSuffix").asText()).isEqualTo("soft film light");
        var selected = drafts.get(owner.userId(), fixture.project().id(), fixture.card());
        var cleared = drafts.save(owner.userId(), fixture.project().id(), fixture.card(), selected.version(), "New user text",
                mapper.createObjectNode(), null, selected.capabilityId(), null, List.of(), List.of(), null);
        var restored = drafts.restoreVersionInputs(owner.userId(), fixture.project().id(), fixture.card(), version.id(), cleared.version());
        assertThat(restored.prompt()).isEqualTo("A paper boat");
        assertThat(restored.styleId()).isEqualTo(style.id());
        var sourceItem = canvas.list(owner.userId(), fixture.project().id()).stream()
                .filter(item -> item.item().id().equals(fixture.card())).findFirst().orElseThrow().item();
        var copied = canvas.duplicate(owner.userId(), fixture.project().id(), fixture.card(), UUID.randomUUID(),
                sourceItem.version(), restored.version(), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("280"), new java.math.BigDecimal("240"), 0);
        assertThat(copied.draft().styleId()).isEqualTo(style.id());
        for (var item : canvas.list(owner.userId(), fixture.project().id())) {
            assertThat(drafts.get(owner.userId(), fixture.project().id(), item.item().id()).styleId()).isEqualTo(style.id());
        }
        var export = exports.build(owner.userId(), fixture.project().id());
        assertThat(export.schemaVersion()).isEqualTo(6);
        assertThat(export.canvasItems().getFirst().mediaDraft().styleId()).isEqualTo(style.id());
        assertThat(export.canvasItems().getFirst().mediaDraft().style().promptSuffix()).isEqualTo("neon night");
        assertThatThrownBy(() -> direct.run(owner.userId(), fixture.project().id(), fixture.artifact().id(), fixture.card(),
                restored.version(), "synthetic-style-disabled"))
                .isInstanceOf(ApiProblemException.class).satisfies(error -> assertThat(((ApiProblemException) error).code()).isEqualTo("MEDIA_STYLE_UNAVAILABLE"));
    }

    @Test void videoReceivesStyleAndAudioRejectsItWithoutChangingTheDraft() {
        var style = styles.create("Synthetic video", "3D", "clay texture", true);
        var video = fixture(Artifact.Kind.VIDEO);
        var draft = save(video, "A cloud moves", style.id(), mapper.createObjectNode());
        var task = direct.run(owner.userId(), video.project().id(), video.artifact().id(), video.card(), draft.version(), "synthetic-video-style");
        assertThat(task.input().path("prompt").asText()).contains("A cloud moves", "Visual style: clay texture");
        direct.cancelQueued(owner.userId(), video.project().id(), task.id());
        var audio = fixture(Artifact.Kind.AUDIO);
        assertThatThrownBy(() -> save(audio, "Hello", style.id(), mapper.createObjectNode())).isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code()).isEqualTo("MEDIA_STYLE_KIND_UNSUPPORTED"));
        assertThat(drafts.get(owner.userId(), audio.project().id(), audio.card()).version()).isZero();
    }

    @Test void runningHubStylesUsePromptDefaultsEnforceFinalLimitsAndRejectPromptlessCapabilities() {
        var style = styles.create("Synthetic dynamic", "绘画", "ink", true);
        var fixture = fixture(Artifact.Kind.IMAGE);
        var connection = capabilities.createConnection("synthetic-style-runninghub", "Synthetic RunningHub", "RUNNINGHUB", "https://api.runninghub.cn", "synthetic-unused-key");
        ObjectNode settings = mapper.createObjectNode();
        ObjectNode definition = settings.putObject("runningHub").put("schemaVersion", 1).put("protocolVersion", "V2")
                .put("targetType", "WORKFLOW").put("targetId", "123");
        definition.putArray("outputs").addObject().put("kind", "IMAGE").put("primary", true).put("maxCount", 1);
        var prompt = definition.putArray("fields").addObject().put("key", "prompt").put("label", "Prompt")
                .put("nodeId", "1").put("fieldName", "text").put("type", "STRING").put("source", "PROMPT")
                .put("required", true).put("defaultValue", "A default landscape").put("maxLength", 80);
        var capability = capabilities.publishCapability(connection.id(), "Synthetic prompt", "RUNNINGHUB_IMAGE", settings);
        var draft = drafts.save(owner.userId(), fixture.project().id(), fixture.card(), 0, "", mapper.createObjectNode(), null,
                capability.id(), null, List.of(), List.of(), style.id());
        var task = direct.run(owner.userId(), fixture.project().id(), fixture.artifact().id(), fixture.card(), draft.version(), "synthetic-default-style");
        assertThat(task.input().path("mediaInput").path("parameters").path("dynamicValues").path("prompt").asText())
                .isEqualTo("A default landscape\n\nVisual style: ink");
        direct.cancelQueued(owner.userId(), fixture.project().id(), task.id());
        styles.update(style.id(), style.version(), style.name(), style.category(), "x".repeat(80), true);
        assertThatThrownBy(() -> direct.preflight(owner.userId(), fixture.project().id(), fixture.artifact().id(), fixture.card(), draft.version()))
                .isInstanceOf(ApiProblemException.class);
        definition.putArray("fields");
        capabilities.updateCapability(connection.id(), capability.id(), capability.version(), "Synthetic promptless", true, "RUNNINGHUB_IMAGE", settings);
        assertThatThrownBy(() -> direct.run(owner.userId(), fixture.project().id(), fixture.artifact().id(), fixture.card(), draft.version(), "synthetic-promptless-style"))
                .isInstanceOf(ApiProblemException.class).satisfies(error -> assertThat(((ApiProblemException) error).code()).isEqualTo("MEDIA_STYLE_PROMPT_UNSUPPORTED"));
        assertThat(drafts.get(owner.userId(), fixture.project().id(), fixture.card()).styleId()).isEqualTo(style.id());
    }

    @Test void mentionsRenderBeforeStyleAndResultsKeepOnlyRenderedUserText() {
        var fixture = fixture(Artifact.Kind.IMAGE);
        var initial = save(fixture, "Synthetic reference", null, mapper.createObjectNode());
        direct.run(owner.userId(), fixture.project().id(), fixture.artifact().id(), fixture.card(), initial.version(), "synthetic-reference-base");
        assertThat(worker.submitOnce("synthetic-reference-worker")).isEqualTo(1);
        var reference = canvas.listMediaVersions(owner.userId(), fixture.project().id(), fixture.card()).getFirst();
        var target = artifacts.create(owner.userId(), fixture.project().id(), Artifact.Kind.IMAGE, "Synthetic mentioned", null).artifact();
        UUID card = CanvasMediaFixture.place(canvas, owner.userId(), fixture.project().id(), target.id());
        var style = styles.create("Synthetic mention style", "绘画", "watercolor", true);
        var inputs = List.of(new MediaDraftService.SaveMediaInput(reference.id(), MediaDraft.InputRole.REFERENCE, "#7C3AED"));
        var mentions = List.of(new MediaDraft.PromptMention(reference.id(), MediaDraft.InputRole.REFERENCE));
        var draft = drafts.save(owner.userId(), fixture.project().id(), card, 0, "\uFFFC in rain", mapper.createObjectNode(), null,
                null, null, inputs, mentions, style.id());
        var task = direct.run(owner.userId(), fixture.project().id(), target.id(), card, draft.version(), "synthetic-styled-mention");
        assertThat(task.input().path("prompt").asText()).isEqualTo("@Image 1 in rain\n\nVisual style: watercolor");
        assertThat(task.input().path("mediaInput").path("prompt").asText()).isEqualTo("\uFFFC in rain");
        assertThat(worker.submitOnce("synthetic-reference-worker")).isEqualTo(1);
        assertThat(canvas.listMediaVersions(owner.userId(), fixture.project().id(), card).getFirst().content().path("prompt").asText())
                .isEqualTo("@Image 1 in rain");
        var plain = drafts.save(owner.userId(), fixture.project().id(), card, draft.version(), "\uFFFC in rain", mapper.createObjectNode(), null,
                null, null, inputs, mentions, null);
        direct.run(owner.userId(), fixture.project().id(), target.id(), card, plain.version(), "synthetic-plain-mention");
        assertThat(worker.submitOnce("synthetic-reference-worker")).isEqualTo(1);
        assertThat(canvas.listMediaVersions(owner.userId(), fixture.project().id(), card).getFirst().content().path("prompt").asText())
                .isEqualTo("@Image 1 in rain");
    }

    @Test void explicitUnknownRetryKeepsAcceptedStyleAfterAdministratorDisablesIt() {
        var style = styles.create("Synthetic unknown style", "绘画", "ink wash", true);
        var fixture = fixture(Artifact.Kind.IMAGE);
        var draft = save(fixture, "A simple form", style.id(), mapper.createObjectNode());
        var original = direct.run(owner.userId(), fixture.project().id(), fixture.artifact().id(), fixture.card(), draft.version(), "synthetic-style-unknown");
        // Synthetic UNKNOWN state, matching the independent SQL fixture in ManualUnknownRetryPostgresIT.
        jdbc.sql("update task set status='UNKNOWN', version=version+1 where id=:id").param("id", original.id()).update();
        var unknown = tasks.get(owner.userId(), fixture.project().id(), original.id());
        styles.update(style.id(), style.version(), style.name(), style.category(), "changed style", false);
        var replacement = retries.create(owner.userId(), fixture.project().id(), original.id(), unknown.version(), "synthetic-style-retry");
        assertThat(replacement.input().path("prompt")).isEqualTo(original.input().path("prompt"));
        assertThat(mapper.treeToValue(replacement.input().path("mediaInput").path("style"), MediaStyleService.Snapshot.class))
                .isEqualTo(mapper.treeToValue(original.input().path("mediaInput").path("style"), MediaStyleService.Snapshot.class));
        assertThat(replacement.id()).isNotEqualTo(original.id());
        direct.cancelQueued(owner.userId(), fixture.project().id(), replacement.id());
    }

    private record Fixture(Project project, Artifact artifact, UUID card) {}
    private Fixture fixture(Artifact.Kind kind) {
        Project project = projects.create(owner.userId(), "Synthetic styles", Project.AspectRatio.SQUARE_1_1);
        Artifact artifact = artifacts.create(owner.userId(), project.id(), kind, "Synthetic card", null).artifact();
        return new Fixture(project, artifact, CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifact.id()));
    }
    private MediaDraft save(Fixture fixture, String prompt, UUID style, ObjectNode parameters) {
        boolean video = fixture.artifact().kind() == Artifact.Kind.VIDEO;
        return drafts.save(owner.userId(), fixture.project().id(), fixture.card(), 0, prompt, parameters, video ? 5 : null,
                null, video ? MediaDraft.VideoInputMode.TEXT : null, List.of(), List.of(), style);
    }
}
