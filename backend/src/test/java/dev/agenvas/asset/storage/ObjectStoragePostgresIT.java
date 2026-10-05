package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL and a labelled fake object server; no real cloud account/provider is contacted. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = { AgenvasApplication.class, ObjectStoragePostgresIT.Transport.class }, properties = { "agenvas.tasks.scheduler-enabled=false",
    "agenvas.library.worker-enabled=false" })
class ObjectStoragePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path ROOT = temporaryRoot();
    static final FakeStore STORE = new FakeStore();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", ROOT::toString);
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired StorageSettingsService settings;
    @Autowired StorageRepository routes;
    @Autowired AssetService assets;
    @Autowired ProjectService projects;
    @Autowired IdentityService identities;
    @Autowired LocalAssetStorage local;
    @Autowired MediaToolRunner mediaTools;
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired dev.agenvas.library.application.LibraryService library;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @AfterAll static void stop() { STORE.server.stop(0); }
    static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-object-store-it-"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @TestConfiguration static class Transport {
        @Bean @Primary ObjectStorageClient fakeTransport(StorageSettingsService settings, Clock clock) {
            var client = new OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
                    .addInterceptor(chain -> {
                        if (TransactionSynchronizationManager.isActualTransactionActive()) STORE.networkInsideTransaction.set(true);
                        // Explicit test-only rewrite. Production keeps HTTPS, real DNS validation and no redirects.
                        var target = chain.request().url().newBuilder().scheme("http").host("127.0.0.1").port(STORE.server.getAddress().getPort()).build();
                        return chain.proceed(chain.request().newBuilder().url(target).build());
                    }).build();
            return new ObjectStorageClient(settings, clock, client);
        }
    }
    @Test void switchDestinationsPreservesLocalAndCloudAssetsRangeAuthorizationAndRecovery() throws Exception {
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        AdminPrincipal owner = identities.setup("storage-admin", "strong-storage-password");
        var auth = UsernamePasswordAuthenticationToken.authenticated(owner, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        UUID project = projects.create(owner.userId(), "storage-test", dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9).id();
        byte[] png = png();
        assertThat(settings.status().activeProfileId()).isNull();
        var original = assets.archiveImage(owner.userId(), project, new ByteArrayInputStream(png));
        assertThat(original.objectKey()).startsWith(project.toString());
        for (StorageProfile.Provider provider : StorageProfile.Provider.values()) {
            int beforeCreate = settings.status().version();
            var saved = settings.create(beforeCreate, provider.name(), provider, "https://storage.example.com", "us-east-1",
                    "test-bucket", "agenvas", provider == StorageProfile.Provider.S3, "test-access-id", "test-access-secret");
            assertThat(saved.activeProfileId()).isNull();
            UUID destination = saved.profiles().getLast().id();
            settings.activate(saved.version(), destination);
            var remote = assets.archiveImage(owner.userId(), project, new ByteArrayInputStream(png));
            assertThat(remote.objectKey()).startsWith("objects/" + destination + "/");
            assertThat(Files.exists(local.checkedPath(project + "/" + remote.id() + ".png"))).isFalse();
            mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content", project, remote.id()).with(authentication(auth)))
                    .andExpect(status().isOk()).andExpect(content().bytes(png));
            mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content", project, remote.id()).header("Range", "bytes=2-5")
                    .with(authentication(auth))).andExpect(status().isPartialContent())
                    .andExpect(header().string("Content-Range", "bytes 2-5/" + png.length))
                    .andExpect(content().bytes(java.util.Arrays.copyOfRange(png, 2, 6)));
            int reads = STORE.gets.get();
            mvc.perform(head("/api/v1/projects/{p}/assets/{a}/content", project, remote.id()).with(authentication(auth)))
                    .andExpect(status().isOk()).andExpect(header().longValue("Content-Length", png.length));
            assertThat(STORE.gets.get()).isEqualTo(reads);
            var descriptor = assets.content(owner.userId(), project, remote.id(), false);
            try (var rangeStream = assets.open(descriptor, 2, 4)) {
                assertThat(rangeStream.skip(100)).isEqualTo(4);
                assertThat(rangeStream.read()).isEqualTo(-1);
                assertThat(rangeStream.available()).isZero();
            }
            mvc.perform(get("/api/v1/projects/{p}/assets/{a}/thumbnail", project, remote.id()).with(authentication(auth)))
                    .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
            var cached = assets.get(owner.userId(), project, remote.id()).path();
            assertThat(Files.readAllBytes(cached)).isEqualTo(png);
            settings.rotate(settings.status().version(), destination, "rotated-id", "rotated-secret");
            settings.activate(settings.status().version(), null);
            mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content", project, remote.id()).with(authentication(auth)))
                    .andExpect(status().isOk()).andExpect(content().bytes(png));
            mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content", project, original.id()).with(authentication(auth)))
                    .andExpect(status().isOk()).andExpect(content().bytes(png));
            var localAgain = assets.archiveImage(owner.userId(), project, new ByteArrayInputStream(png));
            assertThat(localAgain.objectKey()).doesNotStartWith("objects/");
        }
        var serialized = mvc.perform(get("/api/v1/settings/storage").with(authentication(auth))).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn().getResponse().getContentAsString();
        assertThat(serialized).doesNotContain("test-access-secret", "rotated-secret", "test-access-id", "rotated-id", "credentialCiphertext");
        assertThat(jdbc.sql("select encode(credential_ciphertext, 'escape') from storage_profile limit 1").query(String.class).single())
                .doesNotContain("test-access-secret", "rotated-secret");
        mvc.perform(put("/api/v1/settings/storage/active").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"profileId\":null}").with(authentication(auth)).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/settings/storage/active").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"profileId\":null}").with(authentication(auth)))
                .andExpect(status().isForbidden());
        var visitor = UsernamePasswordAuthenticationToken.authenticated(owner, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mvc.perform(get("/api/v1/settings/storage").with(authentication(visitor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/settings/storage")).andExpect(status().isUnauthorized());
        var stranger = new AdminPrincipal(UUID.randomUUID(), "stranger");
        var strangerAuth = UsernamePasswordAuthenticationToken.authenticated(stranger, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        int calls = STORE.requests.get();
        mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content", project, original.id()).with(authentication(strangerAuth)))
                .andExpect(status().isNotFound());
        assertThat(STORE.requests.get()).isEqualTo(calls);

        // Partial remote archive retains the downloaded result and pinned destination across switching.
        UUID cloudId = settings.status().profiles().getLast().id();
        settings.activate(settings.status().version(), cloudId);
        UUID task = UUID.randomUUID(); AtomicInteger downloads = new AtomicInteger();
        STORE.failThumbnail.set(true);
        assertThatThrownBy(() -> assets.archiveTaskImage(owner.userId(), project, task, () -> {
            downloads.incrementAndGet(); return new ByteArrayInputStream(png);
        })).isInstanceOf(ApiProblemException.class);
        settings.activate(settings.status().version(), null);
        var recovered = assets.archiveTaskImage(owner.userId(), project, task, () -> {
            downloads.incrementAndGet(); throw new AssertionError("Must not redownload/regenerate");
        });
        assertThat(downloads.get()).isEqualTo(1);
        assertThat(recovered.objectKey()).startsWith("objects/" + cloudId + "/");
        assertThat(assets.archiveTaskImage(owner.userId(), project, task, () -> { throw new AssertionError("Already archived"); })).isEqualTo(recovered);
        assertThat(STORE.networkInsideTransaction.get()).isFalse();
        assertThat(STORE.authorizations.values()).anyMatch(value -> value.startsWith("OSS4-HMAC-SHA256"))
                .anyMatch(value -> value.startsWith("AWS4-HMAC-SHA256"));

        // Generated and uploaded video/audio share exactly the same configured archive boundary.
        settings.activate(settings.status().version(), cloudId);
        verifyCloudLibraryTransfers(mvc, auth, owner, png);
        verifyPlannedSkillCloudCleanup(owner, project, png);
        Path video = ROOT.resolve("fixture.mp4"), audio = ROOT.resolve("fixture.wav");
        mediaTools.ffmpeg(java.util.List.of("-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=red:s=64x64:r=10",
                "-t", "0.3", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", video.toString()));
        mediaTools.ffmpeg(java.util.List.of("-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.3", "-y", audio.toString()));
        try (var input = Files.newInputStream(video)) {
            var response = mvc.perform(multipart("/api/v1/projects/{p}/assets/video", project)
                    .file(new org.springframework.mock.web.MockMultipartFile("file", "misleading.bin", "application/octet-stream", input))
                    .with(authentication(auth)).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            UUID clipId = UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(response).path("id").asText());
            var clip = assets.metadata(owner.userId(), project, clipId);
            mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content", project, clip.id()).header("Range", "bytes=-8")
                    .with(authentication(auth))).andExpect(status().isPartialContent()).andExpect(content().bytes(
                            java.util.Arrays.copyOfRange(Files.readAllBytes(video), (int) clip.byteSize() - 8, (int) clip.byteSize())));
        }
        try (var input = Files.newInputStream(audio)) {
            var sound = assets.archiveAudio(owner.userId(), project, input);
            mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content", project, sound.id()).with(authentication(auth)))
                    .andExpect(status().isOk()).andExpect(content().bytes(Files.readAllBytes(audio)));
        }
    }

    /** Failed Skill installation cleanup deletes the pinned destination without completing its upload. */
    private void verifyPlannedSkillCloudCleanup(AdminPrincipal owner, UUID project, byte[] png) throws Exception {
        Path source = Files.write(ROOT.resolve("synthetic-planned-skill.png"), png);
        UUID plannedAsset = UUID.randomUUID();
        STORE.failThumbnail.set(true);
        assertThatThrownBy(() -> assets.prepareLibraryImport(owner.userId(), project, plannedAsset,
                dev.agenvas.asset.domain.Asset.MediaKind.IMAGE, source)).isInstanceOf(ApiProblemException.class);
        assertThat(STORE.objects.keySet()).anyMatch(key -> key.contains(plannedAsset.toString()));
        int putsBeforeCleanup = STORE.puts.get();
        assertThat(assets.cleanupPlannedSkillImport(owner.userId(), project, plannedAsset)).isTrue();
        assertThat(assets.cleanupPlannedSkillImport(owner.userId(), project, plannedAsset)).isTrue();
        assertThat(STORE.puts).hasValue(putsBeforeCleanup);
        assertThat(STORE.objects.keySet()).noneMatch(key -> key.contains(plannedAsset.toString()));
        assertThat(Files.exists(local.checkedPath(project + "/" + plannedAsset + ".png"))).isFalse();
        assertThat(Files.exists(local.checkedPath(project + "/" + plannedAsset + ".thumb.png"))).isFalse();

        var registered = assets.archiveImage(owner.userId(), project, new ByteArrayInputStream(png));
        int requestsBeforeCleanup = STORE.requests.get();
        assertThat(assets.cleanupPlannedSkillImport(owner.userId(), project, registered.id())).isTrue();
        assertThat(STORE.requests).hasValue(requestsBeforeCleanup);
        assertThat(Files.readAllBytes(assets.get(owner.userId(), project, registered.id()).path())).containsExactly(png);
        Files.delete(source);
    }

    /** Real HTTP catalogue operations with fake cloud bytes exercise both storage lifetimes. */
    private void verifyCloudLibraryTransfers(org.springframework.test.web.servlet.MockMvc mvc,
            org.springframework.security.core.Authentication auth, AdminPrincipal owner, byte[] png) throws Exception {
        UUID source = projects.create(owner.userId(), "cloud-library-source", dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9).id();
        UUID target = projects.create(owner.userId(), "cloud-library-target", dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9).id();
        var media = assets.archiveImage(owner.userId(), source, new ByteArrayInputStream(png));
        var artifact = libraryPost(mvc, auth, "/api/v1/projects/" + source + "/artifacts", Map.of(
                "kind", "IMAGE", "title", "云端来源", "content", Map.of("sourceType", "UPLOAD", "assetId", media.id())), 201);
        UUID item = UUID.randomUUID();
        libraryPost(mvc, auth, "/api/v1/projects/" + source + "/canvas/commands", Map.of("commands", java.util.List.of(Map.of(
                "type", "PLACE_ARTIFACT", "itemId", item, "artifactId", artifact.path("id").asText(),
                "x", 0, "y", 0, "width", 320, "height", 320, "zIndex", 0, "locked", false))), 200);
        var save = libraryPost(mvc, auth, "/api/v1/projects/" + source + "/canvas-items/" + item + "/library-saves", Map.of(
                "versionId", artifact.path("resourceDefaultVersion").path("id").asText(), "expectedSelectionEpoch", 0,
                "name", "云端场景", "category", "SCENE", "commandKey", "cloud-library-save"), 202);
        assertThat(library.processNext()).isTrue();
        var saved = libraryRead(mvc, auth, "/api/v1/library/commands/" + save.path("id").asText());
        assertThat(saved.path("status").asText()).isEqualTo("SUCCEEDED");
        String entry = saved.path("result").path("entryId").asText();
        // Removing the source cloud objects/cache cannot remove the independent account snapshot.
        var sourceCache = assets.get(owner.userId(), source, media.id()).path();
        STORE.objects.keySet().removeIf(key -> key.contains(media.id().toString()));
        Files.delete(sourceCache);
        mvc.perform(get("/api/v1/library/entries/" + entry + "/content").with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(content().bytes(png));

        var transfer = libraryPost(mvc, auth, "/api/v1/projects/" + target + "/library-imports", Map.of(
                "entryId", entry, "expectedVersion", 0, "x", 20, "y", 30, "commandKey", "cloud-library-import"), 202);
        assertThat(library.processNext()).isTrue();
        var imported = libraryRead(mvc, auth, "/api/v1/library/commands/" + transfer.path("id").asText());
        assertThat(imported.path("status").asText()).isEqualTo("SUCCEEDED");
        var content = libraryRead(mvc, auth, "/api/v1/projects/" + target + "/artifacts/" + imported.path("result").path("artifactId").asText());
        UUID importedAsset = UUID.fromString(content.path("resourceDefaultVersion").path("content").path("assetId").asText());
        assertThat(assets.metadata(owner.userId(), target, importedAsset).objectKey()).startsWith("objects/");

        // A draft conflict after cloud upload must clean the unregistered cloud copy and keep READY content.
        String targetItem = imported.path("result").path("canvasItemId").asText();
        String draftPath = "/api/v1/projects/" + target + "/canvas-items/" + targetItem + "/media-draft";
        var draft = libraryRead(mvc, auth, draftPath);
        var frozen = Map.of("expectedVersion", draft.path("version").asLong(), "prompt", "保留草稿", "parameters", Map.of(),
                "mediaInputs", java.util.List.of(), "mentions", java.util.List.of());
        var reference = libraryPost(mvc, auth, "/api/v1/projects/" + target + "/canvas-items/" + targetItem + "/library-references", Map.of(
                "entryId", entry, "expectedVersion", 0, "commandKey", "cloud-library-conflict", "draft", frozen,
                "role", "REFERENCE", "color", "#67C7F3"), 202);
        mvc.perform(put(draftPath).with(authentication(auth)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(frozen))).andExpect(status().isOk());
        var objectsBeforeFailure = java.util.Set.copyOf(STORE.objects.keySet());
        assertThat(library.processNext()).isTrue();
        var rejected = libraryRead(mvc, auth, "/api/v1/library/commands/" + reference.path("id").asText());
        assertThat(rejected.path("errorCode").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(STORE.objects.size()).isGreaterThan(objectsBeforeFailure.size());
        for (int i = 0; i < 10; i++) library.cleanupNext();
        assertThat(STORE.objects.keySet()).containsExactlyInAnyOrderElementsOf(objectsBeforeFailure);
        libraryPost(mvc, auth, "/api/v1/library/entries/" + entry + "/trash", Map.of("expectedVersion", 0), 200);
        mvc.perform(delete("/api/v1/library/entries/" + entry + "?expectedVersion=1").with(authentication(auth)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content", target, importedAsset).with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(content().bytes(png));
        assertThat(STORE.networkInsideTransaction.get()).isFalse();
    }
    private tools.jackson.databind.JsonNode libraryPost(org.springframework.test.web.servlet.MockMvc mvc,
            org.springframework.security.core.Authentication auth, String path, Object body, int expected) throws Exception {
        return mapper.readTree(mvc.perform(post(path).with(authentication(auth)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private tools.jackson.databind.JsonNode libraryRead(org.springframework.test.web.servlet.MockMvc mvc,
            org.springframework.security.core.Authentication auth, String path) throws Exception {
        return mapper.readTree(mvc.perform(get(path).with(authentication(auth))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
    static byte[] png() throws Exception {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }
    static final class FakeStore {
        record Blob(byte[] bytes, String hash, boolean oss) {}
        final Map<String, Blob> objects = new ConcurrentHashMap<>();
        final Map<Integer, String> authorizations = new ConcurrentHashMap<>();
        final AtomicInteger requests = new AtomicInteger(), gets = new AtomicInteger(), puts = new AtomicInteger();
        final AtomicBoolean failThumbnail = new AtomicBoolean(), networkInsideTransaction = new AtomicBoolean();
        final HttpServer server;
        FakeStore() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> {
                    try (exchange) {
                        int n = requests.incrementAndGet();
                        String auth = exchange.getRequestHeaders().getFirst("Authorization"); authorizations.put(n, auth == null ? "missing" : auth);
                        boolean oss = auth != null && auth.startsWith("OSS4-");
                        String key = exchange.getRequestURI().getPath();
                        String method = exchange.getRequestMethod();
                        if (method.equals("PUT")) {
                            puts.incrementAndGet();
                            if (key.endsWith(".thumb.png") && failThumbnail.compareAndSet(true, false)) { exchange.sendResponseHeaders(503, -1); return; }
                            byte[] bytes = exchange.getRequestBody().readAllBytes();
                            String hash = exchange.getRequestHeaders().getFirst(oss ? "x-oss-meta-sha256" : "x-amz-meta-sha256");
                            if (!ObjectStorageSigner.hash(bytes).equals(hash)) { exchange.sendResponseHeaders(400, -1); return; }
                            objects.put(key, new Blob(bytes, hash, oss)); exchange.sendResponseHeaders(200, -1); return;
                        }
                        if (method.equals("DELETE")) { objects.remove(key); exchange.sendResponseHeaders(204, -1); return; }
                        Blob blob = objects.get(key);
                        if (blob == null) { exchange.sendResponseHeaders(404, -1); return; }
                        exchange.getResponseHeaders().set(blob.oss() ? "x-oss-meta-sha256" : "x-amz-meta-sha256", blob.hash());
                        if (method.equals("HEAD")) { exchange.getResponseHeaders().set("Content-Length", Integer.toString(blob.bytes().length)); exchange.sendResponseHeaders(200, -1); return; }
                        gets.incrementAndGet();
                        String range = exchange.getRequestHeaders().getFirst("Range");
                        String[] ends = range.substring(6).split("-"); int start = Integer.parseInt(ends[0]), end = Integer.parseInt(ends[1]);
                        byte[] bytes = java.util.Arrays.copyOfRange(blob.bytes(), start, end + 1);
                        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + blob.bytes().length);
                        exchange.sendResponseHeaders(206, bytes.length); exchange.getResponseBody().write(bytes);
                    }
                }); server.start();
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }
}
