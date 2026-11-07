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
import java.util.concurrent.atomic.AtomicInteger;
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

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=AgenvasApplication.class,properties="agenvas.reference-catalog.cache.worker-enabled=false")
class ReferenceCatalogCachePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path STORAGE=temp();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("agenvas.storage.root",STORAGE::toString);}
    @Autowired ReferenceCatalogService catalog;@Autowired ReferenceCatalogCacheService jobs;@Autowired ReferenceCatalogImportService imports;
    @Autowired ReferenceCatalogImageCache files;@Autowired ProjectService projects;@Autowired IdentityService identities;@Autowired JdbcClient jdbc;
    @MockitoBean ReferenceCatalogGitHubClient github;
    static AdminPrincipal owner;
    static Path temp(){try{return Files.createTempDirectory("reference-cache-it-");}catch(Exception e){throw new IllegalStateException(e);}}
    @BeforeEach void setup(){if(owner==null)owner=identities.setup("cache-admin","synthetic-reference-password");jobs.clear(null,null,List.of());}
    Source source() throws Exception {
        Source source=catalog.create("cache-"+UUID.randomUUID(),"Original fixture","fixture","repo","c".repeat(40),"reference-pack.json",Format.AGENVAS_REFERENCE_PACK,true, SourceType.GITHUB);
        when(github.resolve(argThat(s->s!=null&&s.id().equals(source.id())))).thenReturn("c".repeat(40));
        when(github.file(argThat(s->s!=null&&s.id().equals(source.id())),eq("c".repeat(40)),eq("reference-pack.json"),anyInt())).thenReturn(Files.readAllBytes(Path.of("../examples/reference-pack/reference-pack.json")));
        when(github.file(argThat(s->s!=null&&s.id().equals(source.id())),eq("c".repeat(40)),startsWith("images/"),anyInt())).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return Files.readAllBytes(Path.of("../examples/reference-pack").resolve(i.getArgument(2,String.class)));});
        assertThat(catalog.sync(source.id(),0).state()).isEqualTo("SUCCESS");return catalog.source(source.id());
    }
    @Test void durableProgressRetriesOnlyFailuresThenOfflineImportAndClearPreservesProjectCopy() throws Exception {
        Source s=source();AtomicInteger attempt=new AtomicInteger();
        when(github.file(argThat(x->x!=null&&x.id().equals(s.id())),any(),eq("images/neutral-front.png"),anyInt())).thenAnswer(i->{if(attempt.incrementAndGet()==1)throw new IllegalStateException("synthetic offline");return Files.readAllBytes(Path.of("../examples/reference-pack/images/neutral-front.png"));});
        var job=jobs.create(owner.userId(),s.id(),s.currentSnapshotId(),List.of(),"prefetch");assertThat(job.totalCount()).isEqualTo(7);
        assertThat(jobs.processNext()).isTrue();var partial=jobs.get(job.id());assertThat(partial.state()).isEqualTo("PARTIAL_FAILED");assertThat(partial.readyCount()).isEqualTo(6);assertThat(partial.failedCount()).isEqualTo(1);
        assertThat(jobs.retry(job.id()).readyCount()).isEqualTo(6);assertThat(jobs.processNext()).isTrue();var ready=jobs.get(job.id());assertThat(ready.state()).isEqualTo("SUCCEEDED");assertThat(ready.readyBytes()).isEqualTo(ready.totalBytes());
        verify(github,times(2)).file(argThat(x->x!=null&&x.id().equals(s.id())),any(),eq("images/neutral-front.png"),anyInt());
        verify(github,times(1)).file(argThat(x->x!=null&&x.id().equals(s.id())),any(),eq("images/neutral-high.png"),anyInt());
        when(github.file(argThat(x->x!=null&&x.id().equals(s.id())),any(),startsWith("images/"),anyInt())).thenThrow(new IllegalStateException("offline"));
        var entry=catalog.list(Kind.POSE,s.id(),"neutral",null,null,null,null,null,null,0,10).items().getFirst();var variant=entry.variants().getFirst();assertThat(variant.cached()).isTrue();
        assertThat(catalog.download(catalog.selection(s.id(),entry.snapshotId(),entry.id(),variant.id()))).isNotEmpty();
        var p=projects.create(owner.userId(),"Offline cached import",Project.AspectRatio.LANDSCAPE_16_9);
        var imported=imports.importImage(owner.userId(),p.id(),new ReferenceCatalogImportService.Input(s.id(),entry.snapshotId(),entry.id(),variant.id(),variant.sha256(),"offline-import",null));
        String key=jdbc.sql("select object_key from asset where id=:id").param("id",imported.assetId()).query(String.class).single();byte[] projectBytes=Files.readAllBytes(STORAGE.resolve(key));
        assertThat(jobs.clear(null,null,List.of()).removedFiles()).isEqualTo(7);assertThat(files.stats().fileCount()).isZero();
        assertThat(Files.readAllBytes(STORAGE.resolve(key))).containsExactly(projectBytes);
        assertThat(jdbc.sql("select count(*) from artifact_version where project_id=:id").param("id",p.id()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void simultaneousWorkersClaimOnceAndCancelledJobsCannotOverwriteTheirState() throws Exception {
        Source s=source();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(github.file(argThat(x->x!=null&&x.id().equals(s.id())),any(),eq("images/neutral-front.png"),anyInt())).thenAnswer(i->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return Files.readAllBytes(Path.of("../examples/reference-pack/images/neutral-front.png"));});
        var job=jobs.create(owner.userId(),s.id(),s.currentSnapshotId(),List.of("synthetic-neutral"),"concurrent");
        try(var pool=Executors.newSingleThreadExecutor()){
            var worker=pool.submit(jobs::processNext);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThat(jobs.processNext()).isFalse();
            jobs.cancel(job.id());release.countDown();worker.get(15,TimeUnit.SECONDS);
        }finally{release.countDown();}
        assertThat(jobs.get(job.id()).state()).isEqualTo("CANCELLED");
        assertThat(files.stats().fileCount()).isZero();
        assertThat(jobs.create(owner.userId(),s.id(),s.currentSnapshotId(),List.of("synthetic-neutral"),"concurrent").id()).isEqualTo(job.id());
        assertThatThrownBy(()->jobs.create(owner.userId(),s.id(),s.currentSnapshotId(),List.of(),"concurrent")).extracting("code").isEqualTo("REFERENCE_CATALOG_CACHE_JOB_CONFLICT");
    }
    @Test void expiredWorkerClaimRecoversDurableProgressWithoutDownloadingReadyImagesAgain() throws Exception {
        Source s=source();var job=jobs.create(owner.userId(),s.id(),s.currentSnapshotId(),List.of("synthetic-neutral"),"restart");
        var first=catalog.selections(s.id(),s.currentSnapshotId(),List.of("synthetic-neutral")).getFirst();catalog.download(first);
        jdbc.sql("update reference_catalog_cache_job set state='RUNNING',lease_token=:token,lease_until=now()-interval '1 second',items_json=jsonb_set(items_json,'{items,0,state}','\"READY\"') where id=:id")
                .param("token",UUID.randomUUID()).param("id",job.id()).update();
        when(github.file(argThat(x->x!=null&&x.id().equals(s.id())),any(),eq("images/neutral-front.png"),anyInt())).thenThrow(new AssertionError("READY progress must skip download"));
        assertThat(jobs.processNext()).isTrue();assertThat(jobs.get(job.id()).state()).isEqualTo("SUCCEEDED");assertThat(jobs.get(job.id()).readyCount()).isEqualTo(5);
    }
    @Test void cachedBytesRemainBlockedAfterSourcePermissionRevocation() throws Exception {
        Source s=source();var selection=catalog.selections(s.id(),s.currentSnapshotId(),List.of("synthetic-neutral")).getFirst();catalog.download(selection);
        catalog.update(s.id(),s.version(),true,false);
        assertThatThrownBy(()->catalog.download(selection)).extracting("code").isEqualTo("REFERENCE_CATALOG_RIGHTS_REQUIRED");
        assertThatThrownBy(()->jobs.create(owner.userId(),s.id(),s.currentSnapshotId(),List.of(),"forbidden")).extracting("code").isEqualTo("REFERENCE_CATALOG_RIGHTS_REQUIRED");
    }
}
