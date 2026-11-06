package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.*;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.referencecatalog.application.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import dev.agenvas.referencecatalog.infrastructure.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Only original synthetic PNG bytes, in the real source metadata shape; never upstream image bytes. */
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=AgenvasApplication.class,properties="agenvas.reference-catalog.cache.worker-enabled=false")
class ReferenceCatalogPrivateImagePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path STORAGE=temp();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("agenvas.storage.root",STORAGE::toString);}
    @Autowired ReferenceCatalogService catalog;@Autowired ReferenceCatalogPrivateImageService privateImages;@Autowired ReferenceCatalogImportService imports;@Autowired ReferenceCatalogCacheService shared;
    @Autowired ProjectService projects;@Autowired IdentityService identities;@Autowired JdbcClient jdbc;
    @MockitoBean ReferenceCatalogGitHubClient github;
    static AdminPrincipal owner,other;
    static Path temp(){try{return Files.createTempDirectory("reference-private-it-");}catch(Exception e){throw new IllegalStateException(e);}}
    @BeforeEach void setup(){if(owner==null){owner=identities.setup("private-admin","synthetic-reference-password");other=new AdminPrincipal(UUID.randomUUID(),"private-other");jdbc.sql("insert into app_user(id,login_name,password_hash,status,created_at) values (:id,'private-other','synthetic','DISABLED',now())").param("id",other.userId()).update();}jdbc.sql("update reference_catalog_private_image set enabled=false").update();shared.clear(null,null,List.of());}
    Source source() throws Exception {
        Source source=catalog.create("private-"+UUID.randomUUID(),"Synthetic metadata shape","hongye007","open-pose-costume","d".repeat(40),null,Format.OPEN_POSE_COSTUME,false);
        when(github.resolve(argThat(s->s!=null&&s.id().equals(source.id())))).thenReturn("d".repeat(40));
        String metadata="""
                {"items":[{"id":"synthetic_pose","kind":"pose","code":"P1","title":{"zh":"原创合成站姿","en":"Original synthetic pose"},"category":{"id":"standing","zh":"站姿","en":"Standing"},"series":{"zh":"测试","en":"Test"},"extended":false,"keywords":{"zh":"测试","en":"test"},"source":"https://ai-pose-gallery.pages.dev/","variants":[{"id":"synthetic_front","angle":"front","label":{"zh":"正面","en":"Front"},"original":"https://ai-pose-gallery.pages.dev/img/synthetic_front.png"}]}]}
                """;
        when(github.file(argThat(s->s!=null&&s.id().equals(source.id())),any(),eq("public/data/catalog.json"),anyInt())).thenReturn(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] bytes=Files.readAllBytes(Path.of("../examples/reference-pack/images/neutral-front.png"));String blob=ReferenceCatalogPrivateImageService.gitBlobSha(bytes);
        when(github.inspectFile(argThat(s->s!=null&&s.id().equals(source.id())),any(),eq("pose/originals/synthetic_front.png"))).thenReturn(new ReferenceCatalogGitHubClient.GitFile(blob,bytes.length));
        when(github.file(argThat(s->s!=null&&s.id().equals(source.id())),any(),eq("pose/originals/synthetic_front.png"),anyInt())).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return bytes;});
        assertThat(catalog.sync(source.id(),0).state()).isEqualTo("SUCCESS");return catalog.source(source.id());
    }
    ReferenceCatalogPrivateImageService.PrivateImage acquire(Source s,UUID who,String key){return privateImages.acquire(who,s.id(),s.currentSnapshotId(),"synthetic_pose","synthetic_front",key);}
    @Test void oneExplicitImageIsOwnerScopedOfflineReadableAndImportIsIndependentOfClear() throws Exception {
        Source s=source();var image=acquire(s,owner.userId(),"select-one");assertThat(image.cached()).isTrue();assertThat(image.sha256()).hasSize(64);
        assertThatThrownBy(()->jdbc.sql("update reference_catalog_private_image set sha256=repeat('0',64) where id=:id").param("id",image.id()).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(privateImages.list(other.userId(),s.id(),s.currentSnapshotId())).isEmpty();assertThatThrownBy(()->privateImages.read(other.userId(),image.id())).extracting("code").isEqualTo("REFERENCE_CATALOG_NOT_FOUND");
        when(github.file(argThat(x->x!=null&&x.id().equals(s.id())),any(),eq("pose/originals/synthetic_front.png"),anyInt())).thenThrow(new IllegalStateException("offline"));
        byte[] cached=privateImages.read(owner.userId(),image.id());assertThat(cached).isNotEmpty();
        var project=projects.create(owner.userId(),"Offline private import",Project.AspectRatio.LANDSCAPE_16_9);
        var imported=imports.importPrivateImage(owner.userId(),project.id(),image.id(),"import-one",null);
        String key=jdbc.sql("select object_key from asset where id=:id").param("id",imported.assetId()).query(String.class).single();byte[] copy=Files.readAllBytes(STORAGE.resolve(key));
        assertThat(shared.clear(null,null,List.of()).stats().protectedFileCount()).isGreaterThanOrEqualTo(1);assertThat(privateImages.read(owner.userId(),image.id())).containsExactly(cached);
        assertThat(privateImages.clear(owner.userId(),image.id()).cached()).isFalse();assertThatThrownBy(()->privateImages.read(owner.userId(),image.id())).extracting("code").isEqualTo("REFERENCE_CATALOG_CACHE_MISS");
        assertThat(Files.readAllBytes(STORAGE.resolve(key))).containsExactly(copy);assertThat(imports.importPrivateImage(owner.userId(),project.id(),image.id(),"import-one",null)).isEqualTo(imported);
        assertThatThrownBy(()->acquire(s,owner.userId(),"select-one")).isInstanceOf(IllegalStateException.class);
        byte[] original=Files.readAllBytes(Path.of("../examples/reference-pack/images/neutral-front.png"));
        when(github.file(argThat(x->x!=null&&x.id().equals(s.id())),any(),eq("pose/originals/synthetic_front.png"),anyInt())).thenReturn(original);
        var rehydrated=acquire(s,owner.userId(),"select-one");assertThat(rehydrated.id()).isEqualTo(image.id());assertThat(rehydrated.sha256()).isEqualTo(image.sha256());assertThat(rehydrated.cached()).isTrue();
    }
    @Test void concurrentReplayFetchesOnceAndSeparateOwnerIndexIsProtectedFromOtherOwnerClear() throws Exception {
        Source s=source();try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->acquire(s,owner.userId(),"repeat"));var b=pool.submit(()->acquire(s,owner.userId(),"repeat"));assertThat(a.get(30,TimeUnit.SECONDS).id()).isEqualTo(b.get(30,TimeUnit.SECONDS).id());
        }
        var mine=acquire(s,owner.userId(),"repeat");var theirs=acquire(s,other.userId(),"their-selection");
        verify(github,times(1)).file(argThat(x->x!=null&&x.id().equals(s.id())),any(),eq("pose/originals/synthetic_front.png"),anyInt());
        privateImages.clear(owner.userId(),mine.id());assertThat(privateImages.read(other.userId(),theirs.id())).isNotEmpty();
        catalog.update(s.id(),s.version(),false,false);assertThatThrownBy(()->privateImages.read(other.userId(),theirs.id())).extracting("code").isEqualTo("REFERENCE_CATALOG_SOURCE_DISABLED");
    }
    @Test void clearSerializesWithReacquisitionAndPreservesOtherOwnersAndProjectCopies() throws Exception {
        Source s=source();var mine=acquire(s,owner.userId(),"race-owner");var theirs=acquire(s,other.userId(),"race-other");
        var project=projects.create(owner.userId(),"Private clear race",Project.AspectRatio.LANDSCAPE_16_9);
        var imported=imports.importPrivateImage(owner.userId(),project.id(),mine.id(),"race-project",null);
        String key=jdbc.sql("select object_key from asset where id=:id").param("id",imported.assetId()).query(String.class).single();byte[] projectCopy=Files.readAllBytes(STORAGE.resolve(key));
        privateImages.clear(owner.userId(),mine.id());Files.delete(STORAGE.resolve(".reference-catalog-cache").resolve(mine.sha256()+".image"));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);var clearThread=new java.util.concurrent.atomic.AtomicReference<Thread>();
        when(github.file(argThat(x->x!=null&&x.id().equals(s.id())),any(),eq("pose/originals/synthetic_front.png"),anyInt())).thenAnswer(i->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return Files.readAllBytes(Path.of("../examples/reference-pack/images/neutral-front.png"));});
        try(var pool=Executors.newFixedThreadPool(2)){
            var acquiring=pool.submit(()->acquire(s,owner.userId(),"race-owner"));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var clearing=pool.submit(()->{clearThread.set(Thread.currentThread());return privateImages.clear(owner.userId(),mine.id());});
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->clearThread.get()!=null && clearThread.get().getState()==Thread.State.BLOCKED);
            release.countDown();acquiring.get(15,TimeUnit.SECONDS);assertThat(clearing.get(15,TimeUnit.SECONDS).cached()).isFalse();
        }finally{release.countDown();}
        assertThat(jdbc.sql("select enabled from reference_catalog_private_image where id=:id").param("id",mine.id()).query(Boolean.class).single()).isFalse();
        assertThat(privateImages.read(other.userId(),theirs.id())).isNotEmpty();assertThat(Files.readAllBytes(STORAGE.resolve(key))).containsExactly(projectCopy);
    }
    @Test void mismatchedRepositoryBlobNeverPublishesPrivateIntegrity() throws Exception {
        Source s=source();when(github.inspectFile(argThat(x->x!=null&&x.id().equals(s.id())),any(),any())).thenReturn(new ReferenceCatalogGitHubClient.GitFile("0".repeat(40),1404));
        assertThatThrownBy(()->acquire(s,owner.userId(),"wrong-blob")).extracting("code").isEqualTo("REFERENCE_CATALOG_INVALID");
        assertThat(privateImages.list(owner.userId(),s.id(),s.currentSnapshotId())).isEmpty();
    }
}
