package dev.agenvas.subtitle.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.identity.application.AdminPrincipal;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and admin HTTP; model bytes are deliberately invalid controlled fixtures. */
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=AgenvasApplication.class,properties={"agenvas.subtitles.worker-enabled=false","agenvas.subtitles.download-worker-enabled=false","agenvas.llm.scheduler-enabled=false","agenvas.provider.media.scheduler-enabled=false"})
class ManagedAsrPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path root;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("agenvas.storage.root",()->root.resolve("assets").toString());r.add("agenvas.subtitles.managed.root",()->root.resolve("asr").toString());
        r.add("agenvas.subtitles.local-asr.model",()->root.resolve("asr/ggml-tiny-q5_1.bin").toString());
        r.add("agenvas.credentials.master-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired ManagedAsrService service; @Autowired JdbcClient jdbc; @Autowired WebApplicationContext context;
    @Autowired IdentityService identity; @Autowired ObjectMapper mapper;
    @MockitoBean AsrModelSource source;
    static AdminPrincipal admin;
    @BeforeEach void setup()throws Exception{
        if(admin==null)admin=identity.setup("managed-asr-admin","synthetic-asr-password-123");
        jdbc.sql("UPDATE local_asr_installation SET enabled=false,version=1,state='MISSING',operation_id=null,downloaded_bytes=0,error_code=null,lease_owner=null,lease_until=null").update();
        when(source.open(eq(ManagedAsrCatalog.MODEL),any(Runnable.class))).thenAnswer(invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ((Runnable)invocation.getArgument(1)).run(); return new ByteArrayInputStream(new byte[]{1,2,3});
        });
    }
    @Test void enableNeverDownloadsAndExplicitAttemptFailureNeedsExplicitRetry()throws Exception{
        var mvc=webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/settings/local-asr").with(authentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(admin,null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))))).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(put("/api/v1/settings/local-asr").with(authentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(admin,null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))).with(csrf()).contentType("application/json").content("{\"enabled\":true,\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        verifyNoInteractions(source);
        var queued=service.download(2);assertThat(queued.state()).isEqualTo(ManagedAsrService.State.QUEUED);verifyNoInteractions(source);
        service.runNext("fixture-worker");
        assertThat(service.get().state()).isEqualTo(ManagedAsrService.State.FAILED);assertThat(service.get().errorCode()).isEqualTo("INVALID_DOWNLOAD");
        service.runNext("fixture-worker");verify(source,times(1)).open(eq(ManagedAsrCatalog.MODEL),any(Runnable.class));
        var retried=service.download(service.get().version());assertThat(retried.operationId()).isNotEqualTo(queued.operationId());
        assertThat(service.cancel(retried.operationId()).state()).isEqualTo(ManagedAsrService.State.CANCELED);
        service.runNext("fixture-worker");verify(source,times(1)).open(eq(ManagedAsrCatalog.MODEL),any(Runnable.class));
        assertThat(service.check(service.get().version()).state()).isEqualTo(ManagedAsrService.State.MISSING);
    }
    @Test void staleSettingsAndParallelCommandsHaveOneWinnerAndRequireAdminAndCsrf()throws Exception{
        var mvc=webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/settings/local-asr")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/settings/local-asr").with(user("ordinary").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/settings/local-asr").with(authentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(admin,null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))).contentType("application/json").content("{\"enabled\":true,\"expectedVersion\":1}"))
                .andExpect(status().isForbidden());
        var start=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
        try(var executor=Executors.newFixedThreadPool(2)){
            for(int i=0;i<2;i++)results.add(executor.submit(()->{start.await();try{service.update(true,1);return true;}catch(dev.agenvas.shared.error.ApiProblemException conflict){assertThat(conflict.code()).isEqualTo("VERSION_CONFLICT");return false;}}));
            start.countDown();int winners=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))winners++;assertThat(winners).isEqualTo(1);
        }
        assertThat(service.get().version()).isEqualTo(2);verifyNoInteractions(source);
    }
    @Test void expiredDownloadFailsWithoutResumingNetworkAndDisableCancelsQueuedWork()throws Exception{
        service.update(true,1);var queued=service.download(2);
        jdbc.sql("UPDATE local_asr_installation SET state='DOWNLOADING',lease_owner='dead',lease_until=now()-interval '1 minute'").update();
        service.runNext("replacement");
        assertThat(service.get().state()).isEqualTo(ManagedAsrService.State.FAILED);assertThat(service.get().errorCode()).isEqualTo("INTERRUPTED");verifyNoInteractions(source);
        var retry=service.download(service.get().version());assertThat(retry.operationId()).isNotEqualTo(queued.operationId());
        var disabled=service.update(false,retry.version());assertThat(disabled.state()).isEqualTo(ManagedAsrService.State.CANCELED);
        service.runNext("replacement");verifyNoInteractions(source);assertThat(service.get().readiness()).isEqualTo(ManagedAsrService.Readiness.DISABLED);
    }
    @Autowired org.jooq.DSLContext db;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired ManagedAsrFiles files;
    @Autowired ManagedAsrProperties properties;
    @Test void explicitlyConfiguredNativeManualInstallationDoesNotNeedDockerOrManagedWeights()throws Exception{
        Path model=java.nio.file.Files.writeString(root.resolve("operator-model.bin"),"operator model fixture, not ASR");
        var manual=new ManagedAsrService(db,transactions,files,source,
                new dev.agenvas.subtitle.application.LocalAsrProperties(Path.of("/usr/bin/true"),model,null,null),
                new AsrMemoryBudget(),java.time.Clock.systemUTC(),false,properties);
        assertThat(manual.get().readiness()).isEqualTo(ManagedAsrService.Readiness.DISABLED);
        var enabled=manual.update(true,1);assertThat(enabled.mode()).isEqualTo("MANUAL");
        assertThat(enabled.readiness()).isEqualTo(ManagedAsrService.Readiness.AVAILABLE);manual.requireReady();
        assertThat(enabled.sha256()).isNull();assertThat(enabled.license()).isEqualTo("OPERATOR_PROVIDED");
        assertThatThrownBy(()->manual.download(enabled.version())).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        verifyNoInteractions(source);assertThat(java.nio.file.Files.readString(model)).contains("operator model fixture");
    }

    @Test void cancelAnActiveDownloadKeepsTheCancelFenceAndRemovesPrivatePartialFiles()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(source.open(eq(ManagedAsrCatalog.MODEL),any(Runnable.class))).thenAnswer(invocation->new java.io.InputStream(){
            boolean first=true;
            @Override public int read(){return -1;}
            @Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{
                if(first){first=false;java.util.Arrays.fill(bytes,offset,offset+16,(byte)1);return 16;}
                entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("fixture timeout");}catch(InterruptedException stopped){throw new java.io.IOException(stopped);}
                return -1;
            }
        });
        service.update(true,1);var queued=service.download(2);
        try(var executor=Executors.newSingleThreadExecutor()){
            var running=executor.submit(()->service.runNext("active-fixture"));
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(service.cancel(queued.operationId()).state()).isEqualTo(ManagedAsrService.State.CANCELED);}
            finally{release.countDown();}
            running.get(5,TimeUnit.SECONDS);
        }
        assertThat(service.get().state()).isEqualTo(ManagedAsrService.State.CANCELED);
        try(var paths=java.nio.file.Files.list(root.resolve("asr"))){assertThat(paths.map(path->path.getFileName().toString())).doesNotContain("ggml-tiny-q5_1.bin").noneMatch(name->name.endsWith(".part"));}
    }

}
