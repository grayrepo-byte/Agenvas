package dev.agenvas.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and HTTP proof for private byte storage and bounded image validation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=asset-integration-secret")
class AssetPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = temporaryRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AssetService assets;
    @Autowired private AgentInstanceService agents;
    @Autowired private LocalAssetStorage storage;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext context;

    @Test
    void uploadValidatesBytesAndPrivateRangeReadsStayInsideProject() throws Exception {
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        AdminPrincipal owner = identities.setup("asset-integration-secret",
                "asset-admin", "asset-password-123");
        Project project = projects.create(owner.userId(), "Asset project",
                Project.AspectRatio.LANDSCAPE_16_9);
        byte[] png = tinyPng();
        String path = "/api/v1/projects/" + project.id() + "/assets";
        MvcResult uploaded = mvc.perform(multipart(path)
                        .file(new MockMultipartFile("file", "evil.html", "text/html", png))
                        .with(authentication(asUser(owner))).with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(uploaded.getResponse().getContentAsString())
                .path("id").asText());
        AssetService.AssetFile archived = assets.get(owner.userId(), project.id(), id);
        assertThat(archived.asset().contentType()).isEqualTo("image/png");
        assertThat(archived.asset().durationMs()).isNull();
        assertThat(archived.asset().byteSize()).isEqualTo(png.length);
        assertThat(Files.readAllBytes(archived.path())).containsExactly(png);
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from project_event where project_id = :projectId "
                        + "and type = 'asset.ready'")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(1);
        AssetService.ThumbnailFile preview = assets.getThumbnail(owner.userId(),
                project.id(), id);
        assertThat(preview.asset().thumbnailByteSize()).isGreaterThan(0);
        assertThat(ImageIO.read(preview.path().toFile()).getWidth()).isEqualTo(3);

        MvcResult thumbnail = mvc.perform(get(path + "/" + id + "/thumbnail")
                        .with(authentication(asUser(owner))))
                .andExpect(status().isOk()).andReturn();
        assertThat(thumbnail.getRequest().isAsyncStarted()).isFalse();
        assertThat(thumbnail.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(ImageIO.read(new ByteArrayInputStream(
                thumbnail.getResponse().getContentAsByteArray())).getWidth())
                .isEqualTo(3);
        mvc.perform(get(path + "/" + id + "/thumbnail")
                        .with(authentication(asUser(new AdminPrincipal(
                                UUID.randomUUID(), "foreign")))))
                .andExpect(status().isNotFound());
        mvc.perform(get(path + "/" + id + "/thumbnail"))
                .andExpect(status().isUnauthorized());

        String content = path + "/" + id + "/content";
        MvcResult ranged = mvc.perform(get(content).header(HttpHeaders.RANGE, "bytes=1-4")
                        .with(authentication(asUser(owner))))
                .andExpect(status().isPartialContent()).andReturn();
        assertThat(ranged.getRequest().isAsyncStarted()).isFalse();
        assertThat(ranged.getResponse().getContentAsByteArray())
                .containsExactly(png[1], png[2], png[3], png[4]);
        assertThat(ranged.getResponse().getHeader(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes 1-4/" + png.length);
        mvc.perform(request(HttpMethod.HEAD, content).with(authentication(asUser(owner))))
                .andExpect(status().isOk());
        mvc.perform(get(content).header(HttpHeaders.RANGE, "bytes=999999-")
                        .with(authentication(asUser(owner))))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(result -> assertThat(result.getResponse()
                        .getHeader(HttpHeaders.CONTENT_RANGE))
                        .isEqualTo("bytes */" + png.length));
        mvc.perform(get(content).with(authentication(asUser(
                        new AdminPrincipal(UUID.randomUUID(), "foreign")))))
                .andExpect(status().isNotFound());
        mvc.perform(get(content)).andExpect(status().isUnauthorized());

        mvc.perform(multipart(path)
                        .file(new MockMultipartFile("file", "bad.png", "image/png",
                                "<svg>not an image</svg>".getBytes()))
                        .with(authentication(asUser(owner))).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(1);
        Asset larger = assets.archiveImage(owner.userId(), project.id(),
                new ByteArrayInputStream(imagePng(1000, 500)));
        BufferedImage scaled = ImageIO.read(assets.getThumbnail(owner.userId(), project.id(),
                larger.id()).path().toFile());
        assertThat(scaled.getWidth()).isEqualTo(480);
        assertThat(scaled.getHeight()).isEqualTo(240);
        assertThatThrownBy(() -> storage.checkedPath("../escape.png"))
                .isInstanceOf(IllegalStateException.class);
        Path outside = Files.createTempDirectory("asset-symlink-outside-");
        UUID symlinkProjectId = UUID.randomUUID();
        Path alias = STORAGE_ROOT.resolve(symlinkProjectId.toString());
        try {
            Files.createSymbolicLink(alias, outside);
            assertThatThrownBy(() -> storage.checkedPath(
                    alias.getFileName() + "/outside.png"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("symbolic link");
            assertThatThrownBy(() -> storage.storeImage(symlinkProjectId,
                    UUID.randomUUID(), new ByteArrayInputStream(png)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> storage.storeVideo(symlinkProjectId,
                    UUID.randomUUID(), new ByteArrayInputStream(png)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> storage.withTaskImageLock(symlinkProjectId,
                    UUID.randomUUID(), () -> null))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> storage.createExportWorkDirectory(symlinkProjectId))
                    .isInstanceOf(IllegalStateException.class);
            try (var outsideFiles = Files.list(outside)) {
                assertThat(outsideFiles.toList()).isEmpty();
            }
        } finally {
            Files.deleteIfExists(alias);
            Files.deleteIfExists(outside);
        }
        byte[] tooLarge = new byte[20 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> assets.archiveImage(owner.userId(), project.id(),
                new ByteArrayInputStream(tooLarge)))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.code()).isEqualTo("ASSET_TOO_LARGE"));
        assertThatThrownBy(() -> assets.archiveImage(owner.userId(), project.id(),
                new ByteArrayInputStream(pngWithOversizedHeader(png))))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.code()).isEqualTo("ASSET_TOO_MANY_PIXELS"));
        assertThatThrownBy(() -> assets.archiveImage(owner.userId(), project.id(),
                interruptedUpload(png))).isInstanceOf(IllegalStateException.class);
        try (var projectFiles = Files.list(STORAGE_ROOT.resolve(project.id().toString()))) {
            assertThat(projectFiles.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith(".ingest-")).toList()).isEmpty();
        }
        assertThatThrownBy(() -> assets.archiveVideo(owner.userId(), project.id(),
                new ByteArrayInputStream("not an MP4".getBytes())))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.code()).isEqualTo("ASSET_INVALID_VIDEO"));
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(2);
        verifyWebpUpload(mvc, owner, project);
        verifyVideoAsset(mvc, owner, project);
        verifyTaskImageCrashRecovery(owner, project, png);
        verifyDatabaseFailureAfterImageInstall(owner, project, png);
    }

    /** A failed READY insert discards user uploads but retains task-keyed bytes for reconciliation. */
    private void verifyDatabaseFailureAfterImageInstall(AdminPrincipal owner, Project project,
            byte[] png) throws Exception {
        Path directory = STORAGE_ROOT.resolve(project.id().toString());
        List<String> filesBefore;
        try (var files = Files.list(directory)) {
            filesBefore = files.map(path -> path.getFileName().toString()).sorted().toList();
        }
        long rowsBefore = jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single();
        long eventsBefore = jdbc.sql("select count(*) from project_event where project_id = :projectId "
                        + "and type = 'asset.ready'")
                .param("projectId", project.id()).query(Long.class).single();
        UUID taskId = UUID.randomUUID();
        UUID taskAssetId = AssetService.taskImageAssetId(taskId);
        jdbc.sql("""
                create function reject_asset_ready_for_test() returns trigger as $$
                begin raise exception 'injected asset insert failure'; end;
                $$ language plpgsql
                """).update();
        jdbc.sql("""
                create trigger reject_asset_ready_for_test before insert on asset
                for each row execute function reject_asset_ready_for_test()
                """).update();
        try {
            assertThatThrownBy(() -> assets.archiveImage(owner.userId(), project.id(),
                    new ByteArrayInputStream(png)))
                    .hasMessageContaining("injected asset insert failure");
            try (var files = Files.list(directory)) {
                assertThat(files.map(path -> path.getFileName().toString()).sorted().toList())
                        .containsExactlyElementsOf(filesBefore);
            }
            assertThatThrownBy(() -> assets.archiveTaskImage(owner.userId(), project.id(),
                    taskId, () -> new ByteArrayInputStream(png)))
                    .hasMessageContaining("injected asset insert failure");
            assertThat(storage.recoverImage(project.id(), taskAssetId)).isPresent();
            assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                    .param("projectId", project.id()).query(Long.class).single())
                    .isEqualTo(rowsBefore);
            assertThat(jdbc.sql("select count(*) from project_event "
                            + "where project_id = :projectId and type = 'asset.ready'")
                    .param("projectId", project.id()).query(Long.class).single())
                    .isEqualTo(eventsBefore);
        } finally {
            jdbc.sql("drop trigger reject_asset_ready_for_test on asset").update();
            jdbc.sql("drop function reject_asset_ready_for_test()").update();
        }
        AtomicInteger downloads = new AtomicInteger();
        Asset recovered = assets.archiveTaskImage(owner.userId(), project.id(), taskId,
                () -> {
                    downloads.incrementAndGet();
                    return new ByteArrayInputStream(png);
                });
        assertThat(recovered.id()).isEqualTo(taskAssetId);
        assertThat(downloads).hasValue(0);
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single())
                .isEqualTo(rowsBefore + 1);
        assertThat(jdbc.sql("select count(*) from project_event "
                        + "where project_id = :projectId and type = 'asset.ready'")
                .param("projectId", project.id()).query(Long.class).single())
                .isEqualTo(eventsBefore + 1);
    }

    /** A real WebP payload is accepted by decoded bytes, not its filename or claimed MIME. */
    private void verifyWebpUpload(MockMvc mvc, AdminPrincipal owner, Project project)
            throws Exception {
        // Fixed 4x4 VP8 WebP fixture; no encoder or remote image is needed in CI.
        byte[] webp = Base64.getDecoder().decode(
                "UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoEAAQAAkA4JaQAA3AA/vucwAA=");
        String path = "/api/v1/projects/" + project.id() + "/assets";
        MvcResult uploaded = mvc.perform(multipart(path)
                        .file(new MockMultipartFile("file", "misleading.png",
                                "image/png", webp))
                        .with(authentication(asUser(owner))).with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(uploaded.getResponse().getContentAsString())
                .path("id").asText());
        AssetService.AssetFile file = assets.get(owner.userId(), project.id(), id);
        assertThat(file.asset().contentType()).isEqualTo("image/webp");
        assertThat(file.path().getFileName().toString()).endsWith(".webp");
        assertThat(Files.readAllBytes(file.path())).containsExactly(webp);
        assertThat(ImageIO.read(assets.getThumbnail(owner.userId(), project.id(), id)
                .path().toFile())).isNotNull();
        MvcResult content = mvc.perform(get(path + "/" + id + "/content")
                        .with(authentication(asUser(owner))))
                .andExpect(status().isOk()).andReturn();
        assertThat(content.getResponse().getContentType()).isEqualTo("image/webp");
        assertThat(content.getResponse().getContentAsByteArray()).containsExactly(webp);
        MvcResult artifactCreated = mvc.perform(post("/api/v1/projects/" + project.id()
                        + "/artifacts").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("""
                                {"kind":"IMAGE","title":"Product reference",
                                 "content":{"sourceType":"UPLOAD","assetId":"%s"}}
                                """.formatted(id))
                        .with(authentication(asUser(owner))).with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        var artifact = mapper.readTree(artifactCreated.getResponse().getContentAsString());
        assertThat(artifact.path("resourceDefaultVersion").path("content").path("sourceType").asText())
                .isEqualTo("UPLOAD");
        assertThat(artifact.path("resourceDefaultVersion").path("content").has("sourceTaskId"))
                .isFalse();
        UUID artifactId = UUID.fromString(artifact.path("id").asText());
        UUID versionId = UUID.fromString(artifact.path("resourceDefaultVersionId").asText());
        var agent = agents.create(owner.userId(), project.id(), "Reference creator",
                "Use only the selected input", List.of(new AgentInstanceService.BindingInput(
                        artifactId, versionId)));
        assertThat(agent.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.artifactId()).isEqualTo(artifactId);
            assertThat(binding.selectedVersionId()).isEqualTo(versionId);
        });
        mvc.perform(post("/api/v1/projects/" + project.id() + "/canvas/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commands":[{"type":"PLACE_ARTIFACT","itemId":"%s",
                                  "artifactId":"%s","x":80,"y":80,"width":280,
                                  "height":240,"zIndex":0,"locked":false}]}
                                """.formatted(UUID.randomUUID(), artifactId))
                        .with(authentication(asUser(owner))).with(csrf()))
                .andExpect(status().isOk());
        MvcResult canvas = mvc.perform(get("/api/v1/projects/" + project.id()
                        + "/canvas/items").with(authentication(asUser(owner))))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(canvas.getResponse().getContentAsString())
                .path("items").path(0).path("artifact").path("resourceDefaultVersion")
                .path("content").path("assetId").asText()).isEqualTo(id.toString());

        mvc.perform(multipart(path)
                        .file(new MockMultipartFile("file", "bad.webp", "image/webp",
                                "not a WebP".getBytes()))
                        .with(authentication(asUser(owner))).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(multipart(path)
                        .file(new MockMultipartFile("file", "truncated.webp", "image/webp",
                                java.util.Arrays.copyOf(webp, webp.length - 5)))
                        .with(authentication(asUser(owner))).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.sql("select count(*) from asset where project_id = :projectId")
                .param("projectId", project.id()).query(Integer.class).single()).isEqualTo(3);
    }

    /** Simulates a crash after the stable original moves but before its READY row exists. */
    private void verifyTaskImageCrashRecovery(AdminPrincipal owner, Project project, byte[] png)
            throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID assetId = AssetService.taskImageAssetId(taskId);
        var staged = storage.storeImage(project.id(), assetId, new ByteArrayInputStream(png));
        Files.delete(storage.checkedPath(staged.thumbnailKey()));
        assertThat(jdbc.sql("select count(*) from asset where id = :id")
                .param("id", assetId).query(Integer.class).single()).isZero();
        AtomicInteger downloads = new AtomicInteger();
        Asset recovered = assets.archiveTaskImage(owner.userId(), project.id(), taskId,
                () -> {
                    downloads.incrementAndGet();
                    return new ByteArrayInputStream(png);
                });
        assertThat(recovered.id()).isEqualTo(assetId);
        assertThat(recovered.sha256()).isEqualTo(staged.sha256());
        assertThat(downloads).hasValue(0);
        assertThat(ImageIO.read(assets.getThumbnail(owner.userId(), project.id(), assetId)
                .path().toFile())).isNotNull();
        Asset replay = assets.archiveTaskImage(owner.userId(), project.id(), taskId,
                () -> {
                    downloads.incrementAndGet();
                    return new ByteArrayInputStream(png);
                });
        assertThat(replay).isEqualTo(recovered);
        assertThat(downloads).hasValue(0);
        assertThat(jdbc.sql("select count(*) from asset where id = :id")
                .param("id", assetId).query(Integer.class).single()).isEqualTo(1);
        verifyConcurrentTaskArchive(owner, project, png);
    }

    /** Two workers racing for the same task must publish and download only once. */
    private void verifyConcurrentTaskArchive(AdminPrincipal owner, Project project, byte[] png)
            throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID assetId = AssetService.taskImageAssetId(taskId);
        AtomicInteger downloads = new AtomicInteger();
        CountDownLatch firstDownloading = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> assets.archiveTaskImage(owner.userId(),
                    project.id(), taskId, () -> {
                        downloads.incrementAndGet();
                        firstDownloading.countDown();
                        try {
                            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Test archive gate timed out");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Test archive gate interrupted", failure);
                        }
                        return new ByteArrayInputStream(png);
                    }));
            assertThat(firstDownloading.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> assets.archiveTaskImage(owner.userId(),
                    project.id(), taskId, () -> {
                        downloads.incrementAndGet();
                        return new ByteArrayInputStream(png);
                    }));
            releaseFirst.countDown();
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(first.get(10, TimeUnit.SECONDS));
        }
        assertThat(downloads).hasValue(1);
        assertThat(jdbc.sql("select count(*) from asset where id = :id")
                .param("id", assetId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from project_event where project_id = :projectId "
                        + "and type = 'asset.ready' and aggregate_id = :assetId")
                .param("projectId", project.id()).param("assetId", assetId)
                .query(Integer.class).single()).isEqualTo(1);
    }

    /** Proves private HTTP Range/HEAD behavior against real MP4 bytes and a decoded poster. */
    private void verifyVideoAsset(MockMvc mvc, AdminPrincipal owner, Project project)
            throws Exception {
        Path image = Files.createTempFile(STORAGE_ROOT, "video-source-", ".png");
        Path video = Files.createTempFile(STORAGE_ROOT, "video-output-", ".mp4");
        try {
            Files.write(image, tinyPng());
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-loop", "1", "-framerate", "24", "-i", image.toString(),
                    "-t", "1", "-vf", "scale=640:360,format=yuv420p", "-an",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "25",
                    "-y", video.toString()));
            Asset archived = assets.archiveVideo(owner.userId(), project.id(),
                    Files.newInputStream(video));
            assertThat(archived.contentType()).isEqualTo("video/mp4");
            assertThat(archived.durationMs()).isEqualTo(1_000);
            assertThat(ImageIO.read(assets.getThumbnail(owner.userId(), project.id(),
                    archived.id()).path().toFile())).isNotNull();
            String path = "/api/v1/projects/" + project.id() + "/assets/" + archived.id();
            MvcResult metadata = mvc.perform(get(path).with(authentication(asUser(owner))))
                    .andExpect(status().isOk()).andReturn();
            assertThat(mapper.readTree(metadata.getResponse().getContentAsString())
                    .path("durationMs").asInt()).isEqualTo(1_000);
            assertThat(mapper.readTree(metadata.getResponse().getContentAsString())
                    .has("objectKey")).isFalse();
            mvc.perform(get(path).with(authentication(asUser(new AdminPrincipal(
                            UUID.randomUUID(), "foreign")))))
                    .andExpect(status().isNotFound());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            MvcResult range = mvc.perform(get(path + "/content")
                            .header(HttpHeaders.RANGE, "bytes=4-7")
                            .with(authentication(asUser(owner))))
                    .andExpect(status().isPartialContent()).andReturn();
            assertThat(range.getRequest().isAsyncStarted()).isFalse();
            assertThat(range.getResponse().getContentType()).isEqualTo("video/mp4");
            assertThat(range.getResponse().getContentAsString()).isEqualTo("ftyp");
            mvc.perform(request(HttpMethod.HEAD, path + "/content")
                            .with(authentication(asUser(owner))))
                    .andExpect(status().isOk());
            mvc.perform(get(path + "/content"))
                    .andExpect(status().isUnauthorized());
            verifyTaskVideoCrashRecovery(owner, project, video);
        } finally {
            Files.deleteIfExists(image);
            Files.deleteIfExists(video);
        }
    }

    /** A completed Provider MP4 survives poster/DB interruption without another download. */
    private void verifyTaskVideoCrashRecovery(AdminPrincipal owner, Project project, Path video)
            throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID assetId = AssetService.taskVideoAssetId(taskId);
        LocalAssetStorage.StoredVideo staged;
        try (var input = Files.newInputStream(video)) {
            staged = storage.storeVideo(project.id(), assetId, input);
        }
        Files.delete(storage.checkedPath(staged.thumbnailKey()));
        AtomicInteger downloads = new AtomicInteger();
        Asset recovered = assets.archiveTaskVideo(owner.userId(), project.id(), taskId,
                () -> {
                    downloads.incrementAndGet();
                    try {
                        return Files.newInputStream(video);
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
        assertThat(recovered.id()).isEqualTo(assetId);
        assertThat(recovered.sha256()).isEqualTo(staged.sha256());
        assertThat(downloads).hasValue(0);
        assertThat(ImageIO.read(assets.getThumbnail(owner.userId(), project.id(), assetId)
                .path().toFile())).isNotNull();
        assertThat(assets.archiveTaskVideo(owner.userId(), project.id(), taskId,
                () -> {
                    downloads.incrementAndGet();
                    throw new AssertionError("READY video must not download again");
                })).isEqualTo(recovered);
        assertThat(downloads).hasValue(0);
        assertThat(jdbc.sql("select count(*) from asset where id = :id")
                .param("id", assetId).query(Integer.class).single()).isEqualTo(1);
        verifyConcurrentTaskVideoArchive(owner, project, video);
    }

    /** Two same-task video archivers share one download, file install and READY event. */
    private void verifyConcurrentTaskVideoArchive(AdminPrincipal owner, Project project, Path video)
            throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID assetId = AssetService.taskVideoAssetId(taskId);
        AtomicInteger downloads = new AtomicInteger();
        CountDownLatch firstDownloading = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> assets.archiveTaskVideo(owner.userId(),
                    project.id(), taskId, () -> {
                        downloads.incrementAndGet();
                        firstDownloading.countDown();
                        try {
                            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Test video archive gate timed out");
                            }
                            return Files.newInputStream(video);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    }));
            assertThat(firstDownloading.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> assets.archiveTaskVideo(owner.userId(),
                    project.id(), taskId, () -> {
                        downloads.incrementAndGet();
                        throw new AssertionError("Second video archiver must not download");
                    }));
            releaseFirst.countDown();
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(first.get(20, TimeUnit.SECONDS));
        }
        assertThat(downloads).hasValue(1);
        assertThat(jdbc.sql("select count(*) from asset where id = :id")
                .param("id", assetId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from project_event where project_id = :projectId "
                        + "and type = 'asset.ready' and aggregate_id = :assetId")
                .param("projectId", project.id()).param("assetId", assetId)
                .query(Integer.class).single()).isEqualTo(1);
    }

    private static UsernamePasswordAuthenticationToken asUser(AdminPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static byte[] tinyPng() throws Exception {
        return imagePng(3, 2);
    }

    /** A valid IHDR CRC advertises 49 MP without allocating a giant decoded bitmap. */
    private static byte[] pngWithOversizedHeader(byte[] original) {
        byte[] changed = original.clone();
        ByteBuffer header = ByteBuffer.wrap(changed);
        header.putInt(16, 7_000);
        header.putInt(20, 7_000);
        CRC32 checksum = new CRC32();
        checksum.update(changed, 12, 17);
        header.putInt(29, (int) checksum.getValue());
        return changed;
    }

    /** Simulates a broken upload stream after temporary bytes were written. */
    private static FilterInputStream interruptedUpload(byte[] source) {
        return new FilterInputStream(new ByteArrayInputStream(source)) {
            private boolean firstRead = true;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (!firstRead) throw new IOException("Injected upload interruption");
                firstRead = false;
                return super.read(buffer, offset, Math.min(length, 16));
            }
        };
    }

    private static byte[] imagePng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        }
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-asset-it-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
