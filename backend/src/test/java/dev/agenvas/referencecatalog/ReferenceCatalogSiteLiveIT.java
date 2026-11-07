package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.*;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.referencecatalog.application.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import dev.agenvas.referencecatalog.infrastructure.*;
import java.nio.file.*;
import java.util.Comparator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Opt-in real public JSON and exactly the user-selected PNG. Never calls a model or touches a deployed database. */
@EnabledIfEnvironmentVariable(named="AGENVAS_REFERENCE_SITE_LIVE",matches="true")
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=AgenvasApplication.class,properties={"agenvas.reference-catalog.cache.worker-enabled=false","agenvas.media-templates.sync-enabled=false"})
class ReferenceCatalogSiteLiveIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path STORAGE=temp();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("agenvas.storage.root",STORAGE::toString);}
    @Autowired ReferenceCatalogService catalog;@Autowired ReferenceCatalogPrivateImageService images;@Autowired ReferenceCatalogImportService imports;
    @Autowired IdentityService identities;@Autowired ProjectService projects;
    @MockitoSpyBean ReferenceCatalogGitHubClient github;@MockitoSpyBean ReferenceCatalogSiteClient site;
    static Path temp(){try{return Files.createTempDirectory("reference-site-live-");}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
    @AfterAll static void cleanup() throws Exception {try(var files=Files.walk(STORAGE)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    @Test void specifiedWebsitePoseLoadsAndImportsWithNoGithubRequests() {
        var owner=identities.setup("site-live-admin","synthetic-reference-password");
        var source=catalog.create("site-live","Omilic","hongye007","open-pose-costume","main",null,Format.OPEN_POSE_COSTUME,false,SourceType.OMILIC);
        assertThat(catalog.sync(source.id(),0).state()).isEqualTo("SUCCESS");source=catalog.source(source.id());
        var entry=catalog.list(Kind.POSE,source.id(),"custom_pose_grid_20261105_seated_05",null,null,null,null,null,null,0,50).items().stream().filter(e->e.id().equals("custom_pose_grid_20261105_seated_05")).findFirst().orElseThrow();
        var variant=entry.variants().stream().filter(v->v.angle().equals("diag")).findFirst().orElseThrow();
        assertThat(variant.externalUrl()).isEqualTo("https://omilic.com/custom/pose/originals/custom_pose_grid_20261105_seated_05_diag.png");assertThat(variant.privateCacheAllowed()).isTrue();
        var image=images.acquire(owner.userId(),source.id(),source.currentSnapshotId(),entry.id(),variant.id(),"selected-site-image");assertThat(image.cached()).isTrue();assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.byteSize()).isPositive();assertThat(image.width()).isEqualTo(1024);assertThat(image.height()).isEqualTo(1536);
        doThrow(new IllegalStateException("Network disabled after live acquisition")).when(site).image(anyString());
        doThrow(new IllegalStateException("Network disabled after live acquisition")).when(site).catalog(anyString());
        assertThat(images.read(owner.userId(),image.id())).isNotEmpty();
        assertThat(images.acquire(owner.userId(),source.id(),source.currentSnapshotId(),entry.id(),variant.id(),"selected-site-image").id()).isEqualTo(image.id());
        var project=projects.create(owner.userId(),"Synthetic live reference import",Project.AspectRatio.LANDSCAPE_16_9);
        assertThat(imports.importPrivateImage(owner.userId(),project.id(),image.id(),"site-place",new ReferenceCatalogImportService.Placement(80,80)).canvasItemId()).isNotNull();
        verifyNoInteractions(github);
        verify(site,times(1)).catalog("data/catalog.json");verify(site,times(1)).image(variant.externalUrl());
    }
}
