package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.*;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.referencecatalog.application.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import dev.agenvas.referencecatalog.infrastructure.*;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class ReferenceCatalogPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path STORAGE = temp();
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agenvas.storage.root", STORAGE::toString);
    }
    @Autowired
    ReferenceCatalogService catalog;
    @Autowired
    ReferenceCatalogImportService imports;
    @Autowired
    ProjectService projects;
    @Autowired
    IdentityService identities;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    WebApplicationContext context;
    @MockitoBean
    ReferenceCatalogGitHubClient github;
    @MockitoSpyBean
    ReferenceCatalogRepository repository;
    static AdminPrincipal owner;
    MockMvc mvc;
    static Path temp() {
        try {
            return Files.createTempDirectory("reference-catalog-it-");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    @BeforeEach
    void setup() {
        if (owner == null)
            owner = identities.setup("reference-admin", "synthetic-reference-password");
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }
    Source source(boolean approved) throws Exception {
        Source source = catalog.create("fixture-" + UUID.randomUUID(), "Original synthetic pack", "fixture", "repo",
                "a".repeat(40), "reference-pack.json", Format.AGENVAS_REFERENCE_PACK, approved, SourceType.GITHUB);
        when(github.resolve(argThat(s -> s != null && s.id().equals(source.id())))).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "a".repeat(40);
        });
        byte[] manifest = Files.readAllBytes(Path.of("../examples/reference-pack/reference-pack.json"));
        when(github.file(argThat(s -> s != null && s.id().equals(source.id())), eq("a".repeat(40)),
                eq("reference-pack.json"), anyInt())).thenAnswer(i -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return manifest;
                });
        when(github.file(argThat(s -> s != null && s.id().equals(source.id())), eq("a".repeat(40)),
                startsWith("images/"), anyInt())).thenAnswer(i -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return Files.readAllBytes(
                            Path.of("../examples/reference-pack").resolve(i.getArgument(2, String.class)));
                });
        assertThat(catalog.sync(source.id(), 0).state()).isEqualTo("SUCCESS");
        return catalog.source(source.id());
    }
    ReferenceCatalogImportService.Input input(Source s, String key) {
        var e = catalog.list(Kind.POSE, s.id(), "", null, null, null, null, null, null, 0, 10).items().getFirst();
        var v = e.variants().getFirst();
        return new ReferenceCatalogImportService.Input(s.id(), e.snapshotId(), e.id(), v.id(), v.sha256(), key, null);
    }
    @Test
    void importsExactlyOneIndependentImageAndReplaysAfterSourceDisable() throws Exception {
        Source s = source(true);
        var p = projects.create(owner.userId(), "Synthetic import", Project.AspectRatio.LANDSCAPE_16_9);
        var request = input(s, "fixed");
        var result = imports.importImage(owner.userId(), p.id(), request);
        assertThat(result.versionId()).isNotNull();
        assertThat(result.assetId()).isNotNull();
        assertThat(result.artifactId()).isNotNull();
        catalog.update(s.id(), s.version(), false, false);
        assertThat(imports.importImage(owner.userId(), p.id(), request)).isEqualTo(result);
        assertThat(jdbc.sql("select count(*) from asset where project_id=:p").param("p", p.id()).query(Long.class)
                .single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from artifact_version where project_id=:p").param("p", p.id())
                .query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> imports.importImage(UUID.randomUUID(), p.id(), request))
                .isInstanceOf(ApiProblemException.class);
        var changed = new ReferenceCatalogImportService.Input(request.sourceId(), request.snapshotId(),
                request.entryId(), request.variantId(), "0".repeat(64), request.commandKey(), null);
        assertThatThrownBy(() -> imports.importImage(owner.userId(), p.id(), changed))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        projects.archive(owner.userId(), p.id(), p.version());
        assertThatThrownBy(() -> imports.importImage(owner.userId(), p.id(), request))
                .isInstanceOf(ApiProblemException.class);
    }
    @Test
    void concurrentSameCommandPublishesOneVersionAndOptionalCanvasPlacement() throws Exception {
        Source s = source(true);
        var p = projects.create(owner.userId(), "Synthetic concurrency", Project.AspectRatio.LANDSCAPE_16_9);
        var base = input(s, "concurrent");
        var request = new ReferenceCatalogImportService.Input(base.sourceId(), base.snapshotId(), base.entryId(),
                base.variantId(), base.sha256(), base.commandKey(),
                new ReferenceCatalogImportService.Placement(12, 34));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> imports.importImage(owner.userId(), p.id(), request));
            var b = pool.submit(() -> imports.importImage(owner.userId(), p.id(), request));
            var result = a.get(30, TimeUnit.SECONDS);
            assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(result.canvasItemId()).isNotNull();
        }
        assertThat(jdbc.sql("select count(*) from artifact_version where project_id=:p").param("p", p.id())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:p").param("p", p.id()).query(Long.class)
                .single()).isEqualTo(1);
    }
    @Test
    void failedPublicationRollsBackAndSameCommandRecoversCleanly() throws Exception {
        Source s = source(true);
        var p = projects.create(owner.userId(), "Synthetic rollback", Project.AspectRatio.LANDSCAPE_16_9);
        var request = input(s, "retry");
        doThrow(new IllegalStateException("synthetic failure")).doCallRealMethod().when(repository).complete(any(),
                any());
        assertThatThrownBy(() -> imports.importImage(owner.userId(), p.id(), request))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("select count(*) from asset where project_id=:p").param("p", p.id()).query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("select count(*) from artifact_version where project_id=:p").param("p", p.id())
                .query(Long.class).single()).isZero();
        var recovered = imports.importImage(owner.userId(), p.id(), request);
        assertThat(recovered.versionId()).isNotNull();
        assertThat(imports.importImage(owner.userId(), p.id(), request)).isEqualTo(recovered);
    }
    @Test
    void missingRightsPreventPreviewImportAndAnyImageDownload() throws Exception {
        Source s = source(false);
        var p = projects.create(owner.userId(), "Synthetic rights", Project.AspectRatio.LANDSCAPE_16_9);
        var request = input(s, "no-rights");
        assertThatThrownBy(() -> imports.importImage(owner.userId(), p.id(), request))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("REFERENCE_CATALOG_RIGHTS_REQUIRED");
        verify(github, never()).file(any(), any(), startsWith("images/"), anyInt());
        assertThat(jdbc.sql("select count(*) from reference_catalog_import where project_id=:p").param("p", p.id())
                .query(Long.class).single()).isZero();
    }
    @Test
    void failedSyncRetainsPreviousSnapshotAndSettingsVersionFencesPublication() throws Exception {
        Source s = source(true);
        UUID ready = s.currentSnapshotId();
        when(github.file(argThat(x -> x != null && x.id().equals(s.id())), any(), eq("reference-pack.json"), anyInt()))
                .thenReturn("bad".getBytes());
        assertThat(catalog.sync(s.id(), s.version()).state()).isEqualTo("FAILED");
        assertThat(catalog.source(s.id()).currentSnapshotId()).isEqualTo(ready);
        assertThat(catalog.list(null, s.id(), "", null, null, null, null, null, null, 0, 20).total()).isEqualTo(3);
        assertThatThrownBy(() -> catalog.sync(s.id(), 0)).isInstanceOf(ApiProblemException.class);
    }
    @Test
    void adminConfirmedUpstreamRemainsLinkOnlyAndSupportsBilingualTokenSearch() throws Exception {
        Source source = catalog.create("metadata-" + UUID.randomUUID(), "Synthetic upstream", "hongye007",
                "open-pose-costume", "b".repeat(40), null, Format.OPEN_POSE_COSTUME, true, SourceType.GITHUB);
        when(github.resolve(argThat(x -> x != null && x.id().equals(source.id())))).thenReturn("b".repeat(40));
        byte[] metadata = """
                {"items":[{"id":"synthetic_pose","kind":"pose","code":"P1","title":{"zh":"合成站姿","en":"Synthetic standing"},
                "category":{"id":"standing","zh":"站姿","en":"Standing"},"series":{"zh":"原作","en":"Original"},
                "extended":false,"keywords":{"zh":"测试","en":"test"},"source":"https://example.com/","tags":[{"id":"female","zh":"女性","en":"Female"}],
                "variants":[{"id":"synthetic_front","angle":"front","label":{"zh":"正面","en":"Front"},"original":"https://example.com/original.png"}]}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(github.file(argThat(x -> x != null && x.id().equals(source.id())), any(), eq("public/data/catalog.json"),
                anyInt())).thenReturn(metadata);
        assertThat(catalog.sync(source.id(), 0).state()).isEqualTo("SUCCESS");
        var page = catalog.list(Kind.POSE, source.id(), "ＦＥＭＡＬＥ standing", null, "female", null, "builtin", false,
                "front", 0, 10);
        assertThat(page.total()).isEqualTo(1);
        var entry = page.items().getFirst();
        assertThat(entry.importAllowed()).isFalse();
        assertThat(entry.disabledReason()).isEqualTo("REFERENCE_CATALOG_LINK_ONLY");
        assertThat(entry.variants().getFirst().previewUrl()).isNull();
        assertThat(entry.variants().getFirst().sha256()).isNull();
        assertThat(catalog.list(Kind.POSE, source.id(), "女性 站姿", null, null, null, null, null, null, 0, 10).total())
                .isEqualTo(1);
        assertThatThrownBy(
                () -> catalog.selection(source.id(), entry.snapshotId(), entry.id(), entry.variants().getFirst().id()))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("REFERENCE_CATALOG_LINK_ONLY");
        verify(github, times(1)).file(argThat(x -> x != null && x.id().equals(source.id())), any(), any(), anyInt());
    }
    @Test
    void disableFencesInflightSyncAndLeavesTheLastReadySnapshot() throws Exception {
        Source s = source(true);
        UUID ready = s.currentSnapshotId();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        byte[] manifest = Files.readAllBytes(Path.of("../examples/reference-pack/reference-pack.json"));
        when(github.file(argThat(x -> x != null && x.id().equals(s.id())), any(), eq("reference-pack.json"), anyInt()))
                .thenAnswer(i -> {
                    entered.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return manifest;
                });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var syncing = pool.submit(() -> catalog.sync(s.id(), s.version()));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(catalog.sync(s.id(), s.version()).state()).isEqualTo("RUNNING");
            catalog.update(s.id(), s.version(), false, false);
            release.countDown();
            assertThat(syncing.get(10, TimeUnit.SECONDS).state()).isEqualTo("SUPERSEDED");
        } finally {
            release.countDown();
        }
        assertThat(catalog.source(s.id()).currentSnapshotId()).isEqualTo(ready);
        assertThat(catalog.source(s.id()).lastSyncStatus()).isNotEqualTo("RUNNING");
    }
    @Test
    void sourceAdministrationRequiresAdminAndCsrf() throws Exception {
        mvc.perform(post("/api/v1/settings/reference-catalog/sources").with(user("viewer").roles("USER")).with(csrf())
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/settings/reference-catalog/sources").with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
}
