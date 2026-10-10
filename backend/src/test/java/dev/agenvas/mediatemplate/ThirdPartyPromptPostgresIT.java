package dev.agenvas.mediatemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptImportService;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptService;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptService.SyncState;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.*;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptHttpClient;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptRepository;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL, mocked public feeds and decoder-validated synthetic images; no generation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class ThirdPartyPromptPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired ThirdPartyPromptService prompts;
    @Autowired ThirdPartyPromptImportService imports;
    @Autowired ProjectService projects;
    @Autowired IdentityService identities;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    @Autowired MediaToolRunner mediaTools;
    @MockitoBean ThirdPartyPromptHttpClient http;
    @MockitoSpyBean ThirdPartyPromptRepository repository;
    private static AdminPrincipal owner;
    private static AdminPrincipal other;
    private MockMvc mvc;
    @BeforeEach void setup() {
        if (owner == null) {
            owner = identities.setup("third-party-admin", "synthetic-template-password");
            other = new AdminPrincipal(UUID.randomUUID(), "third-party-other");
            jdbc.sql("insert into app_user(id,login_name,password_hash,status,created_at) values (:id,:name,'synthetic','DISABLED',now())")
                    .param("id", other.userId()).param("name", other.loginName()).update();
        }
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }
    private String source(TargetKind kind) {
        String id = "fixture-" + UUID.randomUUID();
        prompts.create(id, "Synthetic native feed", kind, "https://example.com/" + id + ".json");
        return id;
    }
    private Image image(String source, String id, String prompt, List<String> refs) {
        return new Image(source + ":" + id, source, "Synthetic " + id, prompt, "", "https://example.com/cover.png", refs,
                List.of("fixture"), "Synthetic author", "https://example.com/original", "2026-10-08", refs.isEmpty() ? ImageMode.generate : ImageMode.edit, "image-model");
    }
    private void feed(String source, Object... entries) {
        when(http.feed(prompts.source(source).url())).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return mapper.writeValueAsString(List.of(entries));
        });
    }
    @Test void updatesExistingAddsNewRetainsMissingAndDoesNotBumpUnchangedVersions() {
        String source = source(TargetKind.IMAGE);
        feed(source, image(source, "a", "Old", List.of()), image(source, "b", "Retained", List.of()));
        assertThat(prompts.sync(source).inserted()).isEqualTo(2);
        var oldSuccess = prompts.source(source).lastSyncedAt();
        feed(source, image(source, "a", "Updated", List.of()), image(source, "c", "New", List.of()));
        var synced = prompts.sync(source); assertThat(synced.state()).isEqualTo(SyncState.SUCCESS);
        assertThat(synced.inserted()).isEqualTo(1); assertThat(synced.updated()).isEqualTo(1);
        assertThat(prompts.list(TargetKind.IMAGE, source, "", 0, 100).total()).isEqualTo(3);
        assertThat(prompts.get(source + ":a").version()).isEqualTo(1);
        assertThat(prompts.get(source + ":b").image().prompt()).isEqualTo("Retained");
        assertThat(prompts.sync(source).updated()).isZero();
        assertThat(prompts.get(source + ":a").version()).isEqualTo(1);
        assertThat(java.time.Duration.between(prompts.source(source).lastSyncedAt(), prompts.source(source).nextSyncAt()).toHours()).isEqualTo(24);
        when(http.feed(prompts.source(source).url())).thenReturn("[]");
        assertThat(prompts.sync(source).state()).isEqualTo(SyncState.FAILED);
        assertThat(prompts.source(source).lastSyncedAt()).isAfterOrEqualTo(oldSuccess);
        assertThat(prompts.list(TargetKind.IMAGE, source, "", 0, 100).total()).isEqualTo(3);
        assertThat(prompts.source(source).lastError()).isEqualTo("THIRD_PARTY_SYNC_FAILED");
        var configured = prompts.source(source); prompts.enabled(source, false, configured.version());
        assertThat(prompts.sync(source).state()).isEqualTo(SyncState.DISABLED);
        assertThat(prompts.get(source + ":b").image().prompt()).isEqualTo("Retained");
        assertThatThrownBy(() -> prompts.enabled(source, true, configured.version())).isInstanceOf(RuntimeException.class);
    }
    @Test void concurrentSyncClaimsOnceAndDisablingFencesInFlightPublication() throws Exception {
        String source = source(TargetKind.IMAGE);
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        when(http.feed(prompts.source(source).url())).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return mapper.writeValueAsString(List.of(image(source, "a", "Synthetic", List.of())));
        });
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> prompts.sync(source)); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(prompts.sync(source).state()).isEqualTo(SyncState.RUNNING);
            prompts.enabled(source, false, prompts.source(source).version()); release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).state()).isEqualTo(SyncState.SUPERSEDED);
        } finally { release.countDown(); }
        verify(http, times(1)).feed(prompts.source(source).url());
        assertThat(prompts.list(TargetKind.IMAGE, source, "", 0, 10).items()).isEmpty();
    }
    @Test void videoSchemaRetainsInputRolesAndDoesNotAppearInImageCatalogue() {
        String source = source(TargetKind.VIDEO);
        var video = new Video(source + ":v", source, "Synthetic video", "Orbit camera", "", "", List.of(), "", "", "",
                VideoMode.image_to_video, "video-model", List.of(new Reference(MediaKind.IMAGE, Role.START_FRAME, "https://example.com/start.png")), null);
        feed(source, video); assertThat(prompts.sync(source).state()).isEqualTo(SyncState.SUCCESS);
        assertThat(prompts.get(video.id()).video()).isEqualTo(video);
        assertThat(prompts.list(TargetKind.IMAGE, source, "", 0, 10).items()).isEmpty();
    }
    @Test void combinedVideoCatalogueDeduplicatesEqualInputsButKeepsSourceCachesAndDifferentReferences() {
        String first = source(TargetKind.VIDEO), second = source(TargetKind.VIDEO);
        String text = "Synthetic unique video " + UUID.randomUUID();
        var a = new Video(first + ":same", first, "First source", text, "", "", List.of(), "Author A", "", "",
                VideoMode.text_to_video, "model", List.of(), null);
        var b = new Video(second + ":same", second, "Second source", text, "", "", List.of(), "Author B", "", "",
                VideoMode.text_to_video, "model", List.of(), null, "https://example.com/preview.mp4", List.of());
        var different = new Video(second + ":different", second, "Different input", text, "", "", List.of(), "", "", "",
                VideoMode.image_to_video, "model", List.of(new Reference(MediaKind.IMAGE, Role.START_FRAME, "https://example.com/input.png")), null);
        feed(first, a); feed(second, b, different);
        assertThat(prompts.sync(first).state()).isEqualTo(SyncState.SUCCESS);
        assertThat(prompts.sync(second).state()).isEqualTo(SyncState.SUCCESS);
        assertThat(prompts.list(TargetKind.VIDEO, null, text, 0, 10).total()).isEqualTo(2);
        assertThat(prompts.list(TargetKind.VIDEO, null, text, 0, 1).items()).hasSize(1);
        assertThat(prompts.list(TargetKind.VIDEO, first, text, 0, 10).total()).isEqualTo(1);
        assertThat(prompts.list(TargetKind.VIDEO, second, text, 0, 10).total()).isEqualTo(2);
        assertThat(prompts.get(b.id()).video().author()).isEqualTo("Author B");
    }
    @Test void missingInputFilesAreCachedButImportIsRejectedBeforeAnyCommandOrMediaWrites() throws Exception {
        String source = source(TargetKind.VIDEO);
        var video = new Video(source + ":missing", source, "Synthetic missing input", "Animate @Image1", "", "", List.of(), "", "", "",
                VideoMode.image_to_video, "model", List.of(), null, "https://example.com/output.mp4",
                List.of(new MissingReference(MediaKind.IMAGE, "IMAGE 1")));
        feed(source, video); assertThat(prompts.sync(source).state()).isEqualTo(SyncState.SUCCESS);
        var project = projects.create(owner.userId(), "Synthetic incomplete prompt", Project.AspectRatio.LANDSCAPE_16_9);
        mvc.perform(post("/api/v1/projects/" + project.id() + "/media-templates/third-party/import").with(auth(true)).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("promptId", video.id(), "expectedVersion", 0, "commandKey", "missing-input"))))
                .andExpect(status().isConflict()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("THIRD_PARTY_REFERENCES_REQUIRED"));
        assertThat(count("asset", project.id())).isZero(); assertThat(count("task", project.id())).isZero();
        assertThat(count("third_party_prompt_import", project.id())).isZero();
        verify(http, never()).download(anyString(), anyInt());
    }
    @Test void fiveBuiltinVideoSourcesUseTheirSpecificAdaptersAndCacheThroughTheDailySyncService() {
        var sources = prompts.sources().stream().filter(s -> s.targetKind() == TargetKind.VIDEO && !s.id().startsWith("fixture-")).toList();
        assertThat(sources).extracting(dev.agenvas.mediatemplate.application.ThirdPartyPromptSource::id)
                .containsExactlyInAnyOrder("youmind-seedance-2-0", "beatapi-minimax-h3", "ipg-seedance-2-0", "ipg-seedance-2-5", "ipg-minimax-h3");
        for (var configured : sources) {
            if (configured.format() == Format.GITHUB_MARKDOWN)
                when(http.feed(java.net.URI.create(configured.url()).resolve("video-urls.json").toString()))
                        .thenReturn("{\"prompts\":{\"999999\":\"https://example.com/output.mp4\"}}");
            String body = switch (configured.format()) {
                case GITHUB_MARKDOWN -> "### Synthetic clip\n#### Prompt\n```\nCamera pans slowly.\n```\n[Watch Video](https://youmind.com/seedance-2-0-prompts?id=999999)\n";
                case BEATAPI_JSON -> "{\"prompts\":[{\"slug\":\"synthetic-clip\",\"title\":{\"en\":\"Synthetic clip\"},\"mode\":\"text-to-video\",\"prompt\":\"Camera pans slowly.\"}]}";
                case IMAGE_PROMPT_GALLERY_JSON -> "{\"prompts\":[{\"id\":\"synthetic-clip\",\"domain\":\"video\",\"model\":\"" + configured.model() + "\",\"title\":\"Synthetic clip\",\"prompt\":\"Camera pans slowly.\",\"generationMode\":\"text-to-video\"}]}";
                default -> throw new AssertionError("Unexpected video source format");
            };
            when(http.feed(configured.url())).thenAnswer(ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return body;
            });
            assertThat(prompts.sync(configured.id()).state()).isEqualTo(SyncState.SUCCESS);
            assertThat(prompts.list(TargetKind.VIDEO, configured.id(), "", 0, 10).total()).isEqualTo(1);
            assertThat(java.time.Duration.between(prompts.source(configured.id()).lastSyncedAt(), prompts.source(configured.id()).nextSyncAt()).toHours()).isEqualTo(24);
        }
    }
    @Test void longVideoPromptIsCachedInFullButCannotBeImportedIntoTheEditor() throws Exception {
        String source = source(TargetKind.VIDEO);
        String fullPrompt = "Synthetic ".repeat(2500);
        var video = new Video(source + ":long", source, "Synthetic long video", fullPrompt, "", "", List.of(), "", "", "",
                VideoMode.text_to_video, "model", List.of(), null);
        feed(source, video); assertThat(prompts.sync(source).state()).isEqualTo(SyncState.SUCCESS);
        assertThat(prompts.get(video.id()).video().prompt()).isEqualTo(fullPrompt);
        var project = projects.create(owner.userId(), "Synthetic long prompt", Project.AspectRatio.LANDSCAPE_16_9);
        mvc.perform(post("/api/v1/projects/" + project.id() + "/media-templates/third-party/import").with(auth(true)).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("promptId", video.id(), "expectedVersion", 0, "commandKey", "long-prompt"))))
                .andExpect(status().isConflict()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("THIRD_PARTY_PROMPT_TOO_LONG"));
        assertThat(count("third_party_prompt_import", project.id())).isZero(); assertThat(count("task", project.id())).isZero();
    }
    @Test void importsAllImagesOnceRetainsSnapshotAcrossSyncAndPublishesAtomically() throws Exception {
        String source = source(TargetKind.IMAGE);
        var prompt = image(source, "a", "Frozen", List.of("https://example.com/input.png")); feed(source, prompt); prompts.sync(source);
        var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        when(http.download(eq("https://example.com/input.png"), anyInt())).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return output.toByteArray();
        });
        Project project = projects.create(owner.userId(), "Synthetic import", Project.AspectRatio.LANDSCAPE_16_9);
        doThrow(new IllegalStateException("Synthetic commit failure")).when(repository).complete(any(), any());
        try {
            assertThatThrownBy(() -> imports.importPrompt(owner.userId(), project.id(), prompt.id(), 0, "retry-key")).isInstanceOf(RuntimeException.class);
            assertThat(count("artifact", project.id())).isZero(); assertThat(count("project_event", project.id())).isZero();
        } finally { reset(repository); }
        feed(source, image(source, "a", "Changed", List.of())); prompts.sync(source);
        when(http.download(eq("https://example.com/input.png"), anyInt())).thenThrow(new IllegalStateException("Upstream no longer available"));
        var imported = imports.importPrompt(owner.userId(), project.id(), prompt.id(), 0, "retry-key");
        assertThat(imported.prompt()).isEqualTo("Frozen"); assertThat(imported.references()).hasSize(1);
        assertThat(imports.importPrompt(owner.userId(), project.id(), prompt.id(), 0, "retry-key")).isEqualTo(imported);
        assertThat(count("asset", project.id())).isEqualTo(1); assertThat(count("artifact", project.id())).isEqualTo(1);
        assertThat(count("task", project.id())).isZero(); assertThat(count("canvas_item", project.id())).isZero();
        assertThatThrownBy(() -> imports.importPrompt(owner.userId(), project.id(), prompt.id(), 1, "retry-key")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> imports.importPrompt(other.userId(), project.id(), prompt.id(), 0, "foreign-key")).isInstanceOf(RuntimeException.class);
    }
    @Test void promptOnlyImportHasNoMediaSideEffectsAndSourceMutationsRequireAdminAndCsrf() throws Exception {
        String source = source(TargetKind.IMAGE); var image = image(source, "a", "Prompt only", List.of()); feed(source, image); prompts.sync(source);
        Project project = projects.create(owner.userId(), "Synthetic prompt", Project.AspectRatio.LANDSCAPE_16_9);
        var imported = imports.importPrompt(owner.userId(), project.id(), image.id(), 0, "prompt-only");
        assertThat(imported.references()).isEmpty(); assertThat(count("asset", project.id())).isZero();
        assertThat(count("task", project.id())).isZero(); assertThat(count("project_event", project.id())).isZero();
        mvc.perform(get("/api/v1/media-templates/third-party").param("targetKind", "IMAGE")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/settings/media-template-sources/" + source + "/sync").with(auth(false)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/settings/media-template-sources/" + source + "/sync").with(auth(true))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/settings/media-template-sources/" + source + "/sync").with(auth(true)).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/media-templates/third-party").param("targetKind", "IMAGE").param("sourceId", source).with(auth(false))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/media-templates/third-party").param("targetKind", "IMAGE").param("limit", "101").with(auth(false))).andExpect(status().isBadRequest());
    }
    @Test void importsVideoStartFramesAndOmniImageVideoAudioInputsWithTheirExactRoles() throws Exception {
        var png = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        var videoFile = java.nio.file.Files.createTempFile("agenvas-reference-video-", ".mp4");
        var audioFile = java.nio.file.Files.createTempFile("agenvas-reference-audio-", ".wav");
        try {
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                    "color=c=black:s=16x16:d=1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", videoFile.toString()));
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                    "anullsrc=r=8000:cl=mono", "-t", "1", "-c:a", "pcm_s16le", "-y", audioFile.toString()));
            when(http.download(eq("https://example.com/input.png"), anyInt())).thenReturn(png.toByteArray());
            when(http.download(eq("https://example.com/input.mp4"), anyInt())).thenReturn(java.nio.file.Files.readAllBytes(videoFile));
            when(http.download(eq("https://example.com/input.wav"), anyInt())).thenReturn(java.nio.file.Files.readAllBytes(audioFile));
            Project project = projects.create(owner.userId(), "Synthetic video inputs", Project.AspectRatio.LANDSCAPE_16_9);
            for (VideoMode mode : List.of(VideoMode.image_to_video, VideoMode.omni_reference)) {
                String source = source(TargetKind.VIDEO);
                var refs = mode == VideoMode.image_to_video ? List.of(new Reference(MediaKind.IMAGE, Role.START_FRAME, "https://example.com/input.png"))
                        : List.of(new Reference(MediaKind.IMAGE, Role.REFERENCE, "https://example.com/input.png"),
                                new Reference(MediaKind.VIDEO, Role.VIDEO_REFERENCE, "https://example.com/input.mp4"),
                                new Reference(MediaKind.AUDIO, Role.AUDIO_REFERENCE, "https://example.com/input.wav"));
                var prompt = new Video(source + ":v", source, "Synthetic motion", "Orbit camera", "", "", List.of(), "", "", "", mode, "video-model", refs, null);
                feed(source, prompt); prompts.sync(source);
                var result = imports.importPrompt(owner.userId(), project.id(), prompt.id(), 0, "video-" + mode);
                assertThat(result.videoInputMode()).isEqualTo(mode == VideoMode.image_to_video ? "START_END" : "GENERAL_REFERENCE");
                assertThat(result.references().stream().map(ThirdPartyPromptImportService.ImportedReference::role).toList())
                        .isEqualTo(refs.stream().map(Reference::role).toList());
                assertThat(result.images()).hasSize(1);
                assertThat(imports.importPrompt(owner.userId(), project.id(), prompt.id(), 0, "video-" + mode)).isEqualTo(result);
            }
            assertThat(count("artifact", project.id())).isEqualTo(4); assertThat(count("task", project.id())).isZero();
        } finally { java.nio.file.Files.deleteIfExists(videoFile); java.nio.file.Files.deleteIfExists(audioFile); }
    }
    private int count(String table, UUID project) {
        return jdbc.sql("select count(*) from " + table + " where project_id=:project").param("project", project).query(Integer.class).single();
    }
    private RequestPostProcessor auth(boolean admin) {
        return authentication(new UsernamePasswordAuthenticationToken(owner, null, admin ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN")) : List.of()));
    }
}
