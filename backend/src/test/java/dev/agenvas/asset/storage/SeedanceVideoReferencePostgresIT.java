package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.infrastructure.ArkSeedanceClient;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL/FFmpeg and explicitly fake HTTP providers, using only synthetic media and credentials. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = { AgenvasApplication.class, SeedanceVideoReferencePostgresIT.Transport.class }, properties = { "agenvas.tasks.scheduler-enabled=false",
        "agenvas.library.worker-enabled=false" })
class SeedanceVideoReferencePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();
    static final Map<String, String> HASHES = new ConcurrentHashMap<>();
    static final AtomicInteger PUTS = new AtomicInteger(), CREATES = new AtomicInteger();
    static volatile JsonNode lastRequest;
    static volatile boolean failNextRelayPut;
    static final HttpServer STORE = objectServer(), ARK = arkServer();
    static final Path ROOT = temporaryRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agenvas.storage.root", ROOT::toString);
        r.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    @Autowired dev.agenvas.library.application.LibraryService library;
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AssetService assets;
    @Autowired ArtifactService artifacts;
    @Autowired CanvasService canvas;
    @Autowired dev.agenvas.canvas.application.CanvasConnectionService connections;
    @Autowired MediaDraftService drafts;
    @Autowired DirectMediaTaskService direct;
    @Autowired MediaCapabilityService capabilities;
    @Autowired MediaExecutionWorker worker;
    @Autowired TaskService tasks;
    @Autowired StorageSettingsService settings;
    @Autowired MediaRelayService relay;
    @Autowired MediaToolRunner tools;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @AfterAll static void stop() throws Exception {
        STORE.stop(0); ARK.stop(0);
        try (var files = Files.walk(ROOT)) { for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
    @TestConfiguration static class Transport {
        @Bean @Primary ArkSeedanceClient fakeArk(ObjectMapper mapper) {
            return new ArkSeedanceClient(mapper, URI.create("http://127.0.0.1:" + ARK.getAddress().getPort()));
        }
        @Bean @Primary ObjectStorageClient fakeObjects(StorageSettingsService settings, Clock clock) {
            var client = new OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
                    .addInterceptor(chain -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        var url = chain.request().url().newBuilder().scheme("http").host("127.0.0.1")
                                .port(STORE.getAddress().getPort()).build();
                        return chain.proceed(chain.request().newBuilder().url(url).build());
                    }).build();
            return new ObjectStorageClient(settings, clock, client) {
                // Fake HTTP server is loopback; production signed input URLs require public DNS.
                @Override public void requirePublic(StorageProfile profile) {}
            };
        }
    }
    @Test void localRelayAndCloudDirectSigningUseFrozenVersionsWithoutLeakingOrResubmitting() throws Exception {
        var owner = identities.setup("relay-admin", "strong-test-password");
        UUID project = projects.create(owner.userId(), "Synthetic video reference", Project.AspectRatio.LANDSCAPE_16_9).id();
        var connection = capabilities.createConnection("ark-relay-test", "Ark test", "ARK", null, "fake-ark-key");
        var capability = capabilities.publishCapability(connection.id(), "Seedance test", "ARK_SEEDANCE_2_I2V");
        capabilities.setDefault(Task.Kind.VIDEO_GENERATION, 0, capability.id());
        Path video = ROOT.resolve("synthetic.mp4");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                "color=c=blue:s=1280x720:r=24:d=4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", video.toString()));
        Files.setPosixFilePermissions(video, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var localAsset = archive(owner.userId(), project, video);
        var local = draft(owner.userId(), project, localAsset.id());
        assertThatThrownBy(() -> run(owner.userId(), project, local)).isInstanceOf(ApiProblemException.class)
                .satisfies(f -> assertThat(((ApiProblemException) f).code()).isEqualTo("MEDIA_RELAY_REQUIRED"));
        assertThat(CREATES).hasValue(0);
        assertThat(PUTS).hasValue(0);
        // Library reference is a draft operation: it can complete before an administrator enables relay.
        var librarySource = artifacts.create(owner.userId(), project, Artifact.Kind.VIDEO, "Library video",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", localAsset.id().toString()));
        UUID libraryCard = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project, librarySource.artifact().id());
        var saveCommand = library.save(owner.userId(), project, libraryCard, librarySource.resourceDefaultVersion().id(),
                0, null, "Synthetic video", dev.agenvas.library.domain.LibraryEntry.Category.OTHER, "save-video-reference");
        assertThat(library.processNext()).isTrue();
        var savedLibrary = library.command(owner.userId(), saveCommand.id());
        assertThat(savedLibrary.status()).isEqualTo(dev.agenvas.library.domain.LibraryCommand.Status.SUCCEEDED);
        var libraryOutput = artifacts.create(owner.userId(), project, Artifact.Kind.VIDEO, "Library target", null);
        UUID libraryTarget = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project, libraryOutput.artifact().id());
        var referenceCommand = library.reference(owner.userId(), project, libraryTarget,
                UUID.fromString(savedLibrary.result().path("entryId").asText()), 0,
                new dev.agenvas.library.application.LibraryService.ReferenceDraft(0, "Video 1", mapper.createObjectNode(),
                        4, capability.id(), MediaDraft.VideoInputMode.GENERAL_REFERENCE, List.of(), List.of(), null),
                MediaDraft.InputRole.VIDEO_REFERENCE, "#F15CAF", "add-library-video-reference");
        assertThat(library.processNext()).isTrue();
        assertThat(library.command(owner.userId(), referenceCommand.id()).status())
                .isEqualTo(dev.agenvas.library.domain.LibraryCommand.Status.SUCCEEDED);
        assertThat(drafts.get(owner.userId(), project, libraryTarget).mediaInputs())
                .singleElement().satisfies(input -> assertThat(input.role()).isEqualTo(MediaDraft.InputRole.VIDEO_REFERENCE));
        var libraryDraft = drafts.get(owner.userId(), project, libraryTarget);
        var linked = connections.connect(owner.userId(), project, libraryCard, libraryTarget,
                librarySource.resourceDefaultVersion().id(), dev.agenvas.canvas.domain.CanvasConnection.RelationType.MEDIA_INPUT,
                libraryDraft.version());
        assertThat(linked.draft().mediaInputs()).hasSize(2).allSatisfy(input ->
                assertThat(input.role()).isEqualTo(MediaDraft.InputRole.VIDEO_REFERENCE));
        var disconnected = connections.disconnect(owner.userId(), project, linked.connection().id(), linked.draft().version(), null);
        assertThat(disconnected.draft().mediaInputs()).hasSize(1);
        assertThat(PUTS).hasValue(0);
        assertThat(CREATES).hasValue(0);
        UUID first = profile("relay-first"), second = profile("archive-second");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var admin = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(owner, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        var visitor = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(owner, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        String relayPath = "/api/v1/settings/storage/relay";
        String body = mapper.writeValueAsString(Map.of("expectedVersion", settings.status().version(), "profileId", first));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(relayPath).contentType("application/json")
                .content(body).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(relayPath).contentType("application/json")
                .content(body).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(visitor))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(relayPath).contentType("application/json")
                .content(body).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(admin)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var saved = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(relayPath).contentType("application/json")
                .content(body).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(admin))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(saved).doesNotContain("synthetic-secret-value", "synthetic-access-id");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(relayPath).contentType("application/json")
                .content(body).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(admin))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        assertThat(settings.status().activeProfileId()).isNull();
        Task localTask = run(owner.userId(), project, local);
        assertThat(localTask.input().path("mediaInput").path("videos").path(0).path("relayProfileId").asText()).isEqualTo(first.toString());
        settings.activateRelay(settings.status().version(), second);
        worker.submitOnce("local-relay-worker");
        assertThat(tasks.get(owner.userId(), project, localTask.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(PUTS).hasValue(1);
        assertThat(CREATES).hasValue(1);
        var videoInput = lastRequest.path("content").path(1);
        assertThat(videoInput.path("role").asText()).isEqualTo("reference_video");
        var signed = okhttp3.HttpUrl.get(videoInput.path("video_url").path("url").asText());
        assertThat(signed.encodedPath()).contains("relay-first/media-relay/");
        assertThat(signed.queryParameter("X-Amz-Expires")).isEqualTo("259200");
        assertThat(signed.queryParameter("X-Amz-Signature")).hasSize(64);
        assertThat(localAsset.objectKey()).doesNotStartWith("objects/");
        assertThat(jdbc.sql("select count(*) from media_relay_object").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from task where input_json::text like '%X-Amz-Signature%'").query(Integer.class).single()).isZero();

        settings.activate(settings.status().version(), second);
        var cloudAsset = archive(owner.userId(), project, video);
        assertThat(cloudAsset.objectKey()).startsWith("objects/");
        settings.activate(settings.status().version(), null);
        settings.activateRelay(settings.status().version(), null);
        var cloudDraft = draft(owner.userId(), project, cloudAsset.id());
        Task cloudTask = run(owner.userId(), project, cloudDraft);
        int before = PUTS.get();
        worker.submitOnce("cloud-direct-worker");
        assertThat(PUTS).hasValue(before);
        assertThat(CREATES).hasValue(2);
        assertThat(tasks.get(owner.userId(), project, cloudTask.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        signed = okhttp3.HttpUrl.get(lastRequest.path("content").path(1).path("video_url").path("url").asText());
        assertThat(signed.encodedPath()).contains("archive-second/" + second + "/").doesNotContain("media-relay");
        assertThat(signed.queryParameter("X-Amz-Signature")).hasSize(64);
        assertThat(jdbc.sql("select count(*) from media_relay_object").query(Integer.class).single()).isEqualTo(1);
        settings.activateRelay(settings.status().version(), first);
        var failedDraft = draft(owner.userId(), project, localAsset.id());
        Task failedTask = run(owner.userId(), project, failedDraft);
        failNextRelayPut = true;
        worker.submitOnce("relay-failure-worker");
        var failed = tasks.get(owner.userId(), project, failedTask.id());
        assertThat(failed.status()).isEqualTo(Task.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("MEDIA_REFERENCE_PREPARATION_FAILED");
        assertThat(CREATES).hasValue(2);
        // Codec/FPS checks occur before creating a provider attempt or uploading a relay copy.
        Path slow = ROOT.resolve("slow.mp4");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                "color=c=blue:s=1280x720:r=12:d=4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", slow.toString()));
        Files.setPosixFilePermissions(slow, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var slowAsset = archive(owner.userId(), project, slow);
        Task slowTask = run(owner.userId(), project, draft(owner.userId(), project, slowAsset.id()));
        int putsBeforeInvalid = PUTS.get();
        worker.submitOnce("invalid-fps-worker");
        assertThat(tasks.get(owner.userId(), project, slowTask.id()).status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(tasks.get(owner.userId(), project, slowTask.id()).errorCode()).isEqualTo("SEEDANCE_VIDEO_REFERENCE_INVALID");
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id=:task and entry_type='RELEASE'")
                .param("task", slowTask.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(PUTS).hasValue(putsBeforeInvalid);
        assertThat(CREATES).hasValue(2);
        // Expired copies are deleted independently of local and permanent cloud archives.
        jdbc.sql("update media_relay_object set created_at=now()-interval '8 days', expires_at=now()-interval '1 day'").update();
        relay.cleanup();
        assertThat(jdbc.sql("select count(*) from media_relay_object").query(Integer.class).single()).isZero();
        assertThat(OBJECTS.keySet()).noneMatch(key -> key.contains("media-relay"));
        assertThat(OBJECTS.keySet()).anyMatch(key -> key.contains(cloudAsset.id().toString()));
        assertThat(Files.exists(assets.get(owner.userId(), project, localAsset.id()).path())).isTrue();
        assertThat(worker.submitOnce("after-accepted")).isZero();
        assertThat(CREATES).hasValue(2);
    }
    private UUID profile(String name) {
        var result = settings.create(settings.status().version(), name, StorageProfile.Provider.S3, "https://s3.example.com",
                "us-east-1", "synthetic-bucket", name, true, "synthetic-access-id", "synthetic-secret-value");
        return result.profiles().stream().filter(profile -> profile.name().equals(name)).findFirst().orElseThrow().id();
    }
    private dev.agenvas.asset.domain.Asset archive(UUID owner, UUID project, Path file) throws Exception {
        try (var input = Files.newInputStream(file)) { return assets.archiveVideo(owner, project, input); }
    }
    private record Draft(UUID artifact, UUID card, long version) {}
    private Draft draft(UUID owner, UUID project, UUID asset) {
        var input = artifacts.create(owner, project, Artifact.Kind.VIDEO, "Reference", mapper.createObjectNode()
                .put("sourceType", "UPLOAD").put("assetId", asset.toString()));
        var output = artifacts.create(owner, project, Artifact.Kind.VIDEO, "Output", null);
        UUID card = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner, project, output.artifact().id());
        var saved = drafts.save(owner, project, card, 0, "Reference Video 1", mapper.createObjectNode(), 4, null,
                MediaDraft.VideoInputMode.GENERAL_REFERENCE, List.of(new MediaDraftService.SaveMediaInput(
                        input.resourceDefaultVersion().id(), MediaDraft.InputRole.VIDEO_REFERENCE, "#67C7F3")), List.of(), null);
        return new Draft(output.artifact().id(), card, saved.version());
    }
    private Task run(UUID owner, UUID project, Draft draft) {
        return direct.run(owner, project, draft.artifact(), draft.card(), draft.version(), "synthetic-" + UUID.randomUUID());
    }
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-seedance-relay-it-"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static HttpServer arkServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v3/contents/generations/tasks", exchange -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                lastRequest = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                CREATES.incrementAndGet();
                byte[] response = ("{\"id\":\"cgt-synthetic-" + CREATES.get() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            });
            server.start(); return server;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static HttpServer objectServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                switch (exchange.getRequestMethod()) {
                    case "PUT" -> {
                        if (failNextRelayPut && path.contains("media-relay")) {
                            failNextRelayPut = false;
                            exchange.sendResponseHeaders(503, -1); exchange.close(); return;
                        }
                        OBJECTS.put(path, exchange.getRequestBody().readAllBytes());
                        HASHES.put(path, exchange.getRequestHeaders().getFirst("x-amz-meta-sha256"));
                        PUTS.incrementAndGet(); exchange.sendResponseHeaders(200, -1);
                    }
                    case "HEAD" -> {
                        byte[] bytes = OBJECTS.get(path);
                        if (bytes == null) exchange.sendResponseHeaders(404, -1);
                        else {
                            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                            exchange.getResponseHeaders().set("x-amz-meta-sha256", HASHES.get(path));
                            exchange.sendResponseHeaders(200, -1);
                        }
                    }
                    case "GET" -> {
                        byte[] bytes = OBJECTS.get(path);
                        String[] range = exchange.getRequestHeaders().getFirst("Range").substring("bytes=".length()).split("-");
                        int start = Integer.parseInt(range[0]), end = Integer.parseInt(range[1]);
                        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
                        exchange.sendResponseHeaders(206, end - start + 1);
                        try (var output = exchange.getResponseBody()) { output.write(bytes, start, end - start + 1); }
                    }
                    case "DELETE" -> { OBJECTS.remove(path); HASHES.remove(path); exchange.sendResponseHeaders(204, -1); }
                    default -> exchange.sendResponseHeaders(405, -1);
                }
                exchange.close();
            });
            server.start(); return server;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
