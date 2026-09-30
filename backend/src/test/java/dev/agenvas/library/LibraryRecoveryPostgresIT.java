package dev.agenvas.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.PrivateMediaArchive;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.testing.ImageAssetFixture;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Crash fixtures manipulate local files/leases; all behaviour assertions use authenticated HTTP. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=library-recovery-test-secret", "agenvas.library.worker-enabled=false"})
class LibraryRecoveryPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path ROOT = temporaryRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", ROOT::toString);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired ProjectEventService events;
    @Autowired LibraryService library;
    @Autowired PrivateMediaArchive archive;
    @Autowired dev.agenvas.asset.infrastructure.MediaToolRunner tools;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    static AdminPrincipal owner;
    RequestPostProcessor auth;
    MockMvc mvc;
    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("library-recovery-test-secret", "recovery-admin", "library-password-123");
        auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }
    record Card(UUID project, UUID item, UUID artifact, UUID version, UUID asset) {}
    Card image() throws Exception {
        var project = projects.create(owner.userId(), "Recovery", Project.AspectRatio.LANDSCAPE_16_9);
        UUID asset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var content = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "恢复图片",
                mapper.valueToTree(Map.of("sourceType", "UPLOAD", "assetId", asset)));
        UUID item = place(project.id(), content.artifact().id());
        return new Card(project.id(), item, content.artifact().id(), content.resourceDefaultVersion().id(), asset);
    }
    UUID place(UUID project, UUID artifact) throws Exception {
        UUID item = UUID.randomUUID();
        postJson("/api/v1/projects/" + project + "/canvas/commands", Map.of("commands", List.of(Map.of(
                "type", "PLACE_ARTIFACT", "itemId", item, "artifactId", artifact, "x", 0, "y", 0, "width", 320,
                "height", 320, "zIndex", 0, "locked", false))), 200);
        return item;
    }
    String source(Card card) { return "/api/v1/projects/" + card.project() + "/canvas-items/" + card.item() + "/library-saves"; }
    Map<String, Object> saveRequest(Card card, String key) {
        return Map.of("versionId", card.version(), "expectedSelectionEpoch", 0, "name", "恢复图片", "category", "OTHER", "commandKey", key);
    }
    JsonNode postJson(String path, Object value, int expected) throws Exception {
        var result = mvc.perform(post(path).with(auth).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(value)))
                .andExpect(status().is(expected)).andReturn().getResponse();
        return mapper.readTree(result.getContentAsString());
    }
    JsonNode read(String path) throws Exception {
        return mapper.readTree(mvc.perform(get(path).with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode command(String id) throws Exception { return read("/api/v1/library/commands/" + id); }

    @Test void resumesInstalledFilesAfterAnExpiredLeaseAndReplaysTheOriginalCommand() throws Exception {
        Card card = image();
        var request = saveRequest(card, "recover-installed");
        JsonNode accepted = postJson(source(card), request, 202);
        String id = accepted.path("id").asText();
        // Simulate a crash after immutable file installation but before the final database transaction.
        archive.archive(owner.userId(), UUID.fromString(id), dev.agenvas.asset.domain.Asset.MediaKind.IMAGE,
                assets.get(owner.userId(), card.project(), card.asset()).path());
        jdbc.sql("UPDATE library_command SET status='ARCHIVING', epoch=3, lease_until=now()-interval '1 second' WHERE id=:id")
                .param("id", UUID.fromString(id)).update();
        library.processNext();
        JsonNode finished = command(id);
        assertThat(finished.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(postJson(source(card), request, 202).path("id").asText()).isEqualTo(id);
        var changed = new java.util.HashMap<>(request); changed.put("name", "另一载荷");
        assertThat(postJson(source(card), changed, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        String entry = finished.path("result").path("entryId").asText();
        JsonNode duplicate = postJson(source(card), saveRequest(card, "same-version-another-key"), 202);
        library.processNext();
        assertThat(command(duplicate.path("id").asText()).path("result").path("entryId").asText()).isEqualTo(entry);
        assertThat(command(duplicate.path("id").asText()).path("result").path("alreadySaved").asBoolean()).isTrue();
        library.cleanupNext();
        mvc.perform(get("/api/v1/library/entries/" + entry + "/content").with(auth)).andExpect(status().isOk());
        assertThat(read("/api/v1/projects/" + card.project() + "/artifacts/" + card.artifact() + "/run?canvasItemId=" + card.item()).size()).isZero();
    }

    @Test void rollsBackReferenceAndEventsWhenTheTargetDraftChangesAfterAcceptance() throws Exception {
        Card card = image();
        String savedCommand = postJson(source(card), saveRequest(card, "conflict-save"), 202).path("id").asText();
        library.processNext();
        String entry = command(savedCommand).path("result").path("entryId").asText();
        String draftPath = "/api/v1/projects/" + card.project() + "/canvas-items/" + card.item() + "/media-draft";
        JsonNode draft = read(draftPath);
        Map<String,Object> input = Map.of("expectedVersion", draft.path("version").asLong(), "prompt", "原输入",
                "parameters", Map.of(), "mediaInputs", List.of(), "mentions", List.of());
        JsonNode accepted = postJson("/api/v1/projects/" + card.project() + "/canvas-items/" + card.item() + "/library-references",
                Map.of("entryId", entry, "expectedVersion", 0, "commandKey", "conflict-reference", "draft", input,
                        "role", "REFERENCE", "color", "#67C7F3"), 202);
        mvc.perform(put(draftPath).with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isOk());
        long seq = read("/api/v1/projects/" + card.project() + "/snapshot").path("snapshotSeq").asLong();
        library.processNext();
        assertThat(command(accepted.path("id").asText()).path("status").asText()).isEqualTo("FAILED");
        assertThat(command(accepted.path("id").asText()).path("errorCode").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(read(draftPath).path("mediaInputs").size()).isZero();
        assertThat(read("/api/v1/projects/" + card.project() + "/artifacts").path("items").size()).isEqualTo(1);
        assertThat(read("/api/v1/projects/" + card.project() + "/snapshot").path("snapshotSeq").asLong()).isEqualTo(seq);
    }

    @Test void savesTheNodeVersionRatherThanTheResourceDefaultAndIgnoresLayoutChanges() throws Exception {
        Card card = image();
        UUID nextAsset = ImageAssetFixture.archive(assets, owner.userId(), card.project());
        var revised = artifacts.revise(owner.userId(), card.project(), card.artifact(), 0, "第二版",
                mapper.valueToTree(Map.of("sourceType", "UPLOAD", "assetId", nextAsset)));
        // Fixture attaches a result as task completion would; actual selection and save use HTTP.
        events.recordChange(owner.userId(), card.project(), () -> {
            canvas.recordTaskMediaVersionWithinChange(owner.userId(), card.project(), card.item(), card.artifact(), revised.resourceDefaultVersion().id());
            return ProjectEventService.Change.unchanged(null);
        });
        postJson("/api/v1/projects/" + card.project() + "/canvas/items/" + card.item() + "/select-media-version",
                Map.of("versionId", revised.resourceDefaultVersion().id(), "expectedVersion", 0), 200);
        artifacts.setResourceDefaultVersion(owner.userId(), card.project(), card.artifact(), card.version(), revised.artifact().version());
        JsonNode fixed = read(source(card));
        assertThat(fixed.path("versionId").asText()).isEqualTo(revised.resourceDefaultVersion().id().toString());
        assertThat(fixed.path("versionNo").asInt()).isEqualTo(2);
        var node = read("/api/v1/projects/" + card.project() + "/canvas/items").path("items").get(0);
        canvas.apply(owner.userId(), card.project(), List.of(new CanvasService.UpdateLayout(card.item(), node.path("version").asLong(),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.valueOf(320), BigDecimal.valueOf(320), 0, null)));
        JsonNode accepted = postJson(source(card), Map.of("versionId", fixed.path("versionId").asText(), "expectedSelectionEpoch", fixed.path("expectedSelectionEpoch").asLong(),
                "name", "第二版", "category", "SCENE", "commandKey", "save-node-v2"), 202);
        library.processNext();
        String entry = command(accepted.path("id").asText()).path("result").path("entryId").asText();
        assertThat(read("/api/v1/library/entries/" + entry).path("source").path("versionId").asText()).isEqualTo(fixed.path("versionId").asText());
        JsonNode currentNode = read("/api/v1/projects/" + card.project() + "/canvas/items").path("items").get(0);
        postJson("/api/v1/projects/" + card.project() + "/canvas/items/" + card.item() + "/select-media-version",
                Map.of("versionId", card.version(), "expectedVersion", currentNode.path("version").asLong()), 200);
        postJson(source(card), Map.of("versionId", fixed.path("versionId").asText(), "expectedSelectionEpoch", fixed.path("expectedSelectionEpoch").asLong(),
                "name", "过期结果", "category", "OTHER", "commandKey", "stale-selection"), 409);
    }
    @Test void preservesTextFormatAndEnforcesTheTextCasAndEmptyContentRules() throws Exception {
        Project project = projects.create(owner.userId(), "Text library", Project.AspectRatio.LANDSCAPE_16_9);
        var text = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, "角色说明", mapper.valueToTree(Map.of("format", "MARKDOWN", "text", "")));
        UUID item = place(project.id(), text.artifact().id());
        String path = "/api/v1/projects/" + project.id() + "/canvas-items/" + item + "/library-saves";
        mvc.perform(get(path).with(auth)).andExpect(status().isUnprocessableEntity());
        var revised = artifacts.revise(owner.userId(), project.id(), text.artifact().id(), 0, "角色说明", mapper.valueToTree(Map.of("format", "MARKDOWN", "text", "# 角色\n勇敢的旅行者")));
        var request = Map.of("versionId", revised.resourceDefaultVersion().id(), "expectedSelectionEpoch", 0,
                "expectedArtifactVersion", 1, "name", "角色说明", "category", "CHARACTER", "commandKey", "text-save");
        var stale = new java.util.HashMap<>(request); stale.put("expectedArtifactVersion", 0); stale.put("commandKey", "text-stale");
        postJson(path, stale, 409);
        String commandId = postJson(path, request, 202).path("id").asText(); library.processNext();
        String entry = command(commandId).path("result").path("entryId").asText();
        assertThat(read("/api/v1/library/entries/" + entry).path("textContent").path("text").asText()).isEqualTo("# 角色\n勇敢的旅行者");
        Project target = projects.create(owner.userId(), "Imported text", Project.AspectRatio.SQUARE_1_1);
        String imported = postJson("/api/v1/projects/" + target.id() + "/library-imports", Map.of("entryId", entry, "expectedVersion", 0,
                "commandKey", "text-import", "x", 10, "y", 20), 202).path("id").asText(); library.processNext();
        assertThat(command(imported).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode importedText = read("/api/v1/projects/" + target.id() + "/canvas/items").path("items").get(0).path("artifact").path("resourceDefaultVersion").path("content");
        assertThat(importedText.path("format").asText()).isEqualTo("MARKDOWN");
        assertThat(importedText.path("text").asText()).isEqualTo("# 角色\n勇敢的旅行者");
        mvc.perform(get("/api/v1/library/entries?cursor=eyJ2YWx1ZSI6IngiLCJpZCI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMCJ9").with(auth))
                .andExpect(status().isBadRequest());
    }

    @Test void decodesAudioAndVideoUploadsAndImportsTheirOwnProjectFiles() throws Exception {
        Path wav = Files.createTempFile(ROOT, "library-audio-", ".wav");
        Path mp4 = Files.createTempFile(ROOT, "library-video-", ".mp4");
        try {
            tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2", "-c:a", "pcm_s16le", "-y", wav.toString()));
            tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=black:s=320x180:r=24", "-t", "1", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", mp4.toString()));
            Project project = projects.create(owner.userId(), "Media imports", Project.AspectRatio.SQUARE_1_1);
            for (var media : Map.of("AUDIO", wav, "VIDEO", mp4).entrySet()) {
                byte[] bytes = Files.readAllBytes(media.getValue());
                String kind = media.getKey();
                JsonNode accepted = mapper.readTree(mvc.perform(multipart("/api/v1/library/uploads")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", media.getValue().getFileName().toString(), "application/octet-stream", bytes))
                        .param("kind", kind).param("name", "媒体" + kind).param("category", "OTHER").param("commandKey", "upload-" + kind).with(auth).with(csrf()))
                        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
                library.processNext();
                assertThat(command(accepted.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
                String entry = command(accepted.path("id").asText()).path("result").path("entryId").asText();
                assertThat(read("/api/v1/library/entries/" + entry).path("durationMs").asInt()).isGreaterThan(500);
                byte[] archived = mvc.perform(get("/api/v1/library/entries/" + entry + "/content").with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                assertThat(archived).containsExactly(bytes);
                if (kind.equals("VIDEO")) mvc.perform(get("/api/v1/library/entries/" + entry + "/thumbnail").with(auth)).andExpect(status().isOk());
                String imported = postJson("/api/v1/projects/" + project.id() + "/library-imports", Map.of("entryId", entry, "expectedVersion", 0,
                        "commandKey", "import-" + kind, "x", 0, "y", 0), 202).path("id").asText(); library.processNext();
                assertThat(command(imported).path("status").asText()).isEqualTo("SUCCEEDED");
                String forged = "/api/v1/projects/" + project.id() + "/artifacts";
                JsonNode item = read("/api/v1/projects/" + project.id() + "/canvas/items").path("items").get(0);
                postJson(forged, Map.of("kind", kind, "title", "伪造来源", "content", Map.of("sourceType", "LIBRARY_IMPORT", "assetId",
                        item.path("selectedVersion").path("content").path("assetId").asText())), 422);
            }
            mvc.perform(multipart("/api/v1/library/uploads").file(new org.springframework.mock.web.MockMultipartFile("file", "fake.png", "image/png", "not a PNG".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .param("kind", "IMAGE").param("name", "伪装文件").param("category", "OTHER").param("commandKey", "invalid-file").with(auth).with(csrf()))
                    .andExpect(status().isUnprocessableEntity());
        } finally { Files.deleteIfExists(wav); Files.deleteIfExists(mp4); }
    }

    @Test void keepsPinnedInputOnStorageFailureAndRetriesWithoutCreatingASecondEntry() throws Exception {
        Card card = image();
        String id = postJson(source(card), saveRequest(card, "storage-failure"), 202).path("id").asText();
        Path blocked = ROOT.resolve("library").resolve(owner.userId().toString()).resolve(id + ".png");
        Files.createDirectory(blocked);
        library.processNext();
        assertThat(command(id).path("status").asText()).isEqualTo("FAILED");
        Files.delete(blocked);
        postJson("/api/v1/library/commands/" + id + "/retry", Map.of(), 202);
        library.processNext();
        assertThat(command(id).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(command(id).path("result").path("entryId").asText()).isEqualTo(id);
    }

    @Test void usesStableCursorPagesAndServerSearchRatherThanLoadedPageCounts() throws Exception {
        Project project = projects.create(owner.userId(), "Pagination", Project.AspectRatio.SQUARE_1_1);
        for (int index = 0; index < 31; index++) {
            var text = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, "分页资产", mapper.valueToTree(Map.of("format", "PLAIN_TEXT", "text", "正文" + index)));
            UUID item = place(project.id(), text.artifact().id());
            postJson("/api/v1/projects/" + project.id() + "/canvas-items/" + item + "/library-saves", Map.of("versionId", text.resourceDefaultVersion().id(),
                    "expectedSelectionEpoch", 0, "expectedArtifactVersion", 0, "name", "分页资产", "category", "OTHER", "commandKey", "page-" + index), 202);
            library.processNext();
        }
        JsonNode first = read("/api/v1/library/entries?query=分页资产&sort=NAME&kind=TEXT");
        assertThat(first.path("items").size()).isEqualTo(30);
        assertThat(first.path("total").asInt()).isEqualTo(31);
        JsonNode second = read("/api/v1/library/entries?query=分页资产&sort=NAME&kind=TEXT&cursor=" + first.path("nextCursor").asText());
        assertThat(second.path("items").size()).isEqualTo(1);
        java.util.Set<String> ids = new java.util.HashSet<>();
        first.path("items").forEach(item -> ids.add(item.path("id").asText()));
        assertThat(ids.add(second.path("items").get(0).path("id").asText())).isTrue();
        assertThat(second.path("nextCursor").isNull()).isTrue();
    }

    @Test void concurrentSavesOfOneVersionProduceOneEntry() throws Exception {
        Card card = image();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> postJson(source(card), saveRequest(card, "concurrent-one"), 202));
            var two = executor.submit(() -> postJson(source(card), saveRequest(card, "concurrent-two"), 202));
            String first = one.get().path("id").asText(); String second = two.get().path("id").asText();
            library.processNext(); library.processNext();
            assertThat(command(first).path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(command(second).path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(command(first).path("result").path("entryId").asText()).isEqualTo(command(second).path("result").path("entryId").asText());
        }
    }

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-library-recovery-"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
