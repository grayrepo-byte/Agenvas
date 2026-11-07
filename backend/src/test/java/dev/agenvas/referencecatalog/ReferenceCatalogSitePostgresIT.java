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
import dev.agenvas.shared.http.PublicDownloadHttpClients;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Original synthetic metadata and images; the site transport runs against an in-process HTTP interceptor. */
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes={AgenvasApplication.class,ReferenceCatalogSitePostgresIT.Transport.class},properties="agenvas.reference-catalog.cache.worker-enabled=false")
class ReferenceCatalogSitePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path STORAGE=temp();
    static final List<String> REQUESTS=new CopyOnWriteArrayList<>();
    static volatile boolean offline;
    static final String METADATA="""
            {"items":[{"id":"synthetic_pose","kind":"pose","title":{"zh":"原创合成","en":"Original synthetic"},"category":{"id":"standing","zh":"站姿","en":"Standing"},"series":{"zh":"测试","en":"Test"},"keywords":{"zh":"测试","en":"test"},"extended":false,"source":"custom","collection":"custom","variants":[{"id":"synthetic_front","angle":"front","label":{"zh":"正面","en":"Front"},"original":"custom/pose/originals/synthetic.png"}]}],"stats":{}}
            """;
    @TestConfiguration static class Transport {
        @Bean @Primary PublicDownloadHttpClients syntheticDownloads(){
            var clients=mock(PublicDownloadHttpClients.class);
            var http=new OkHttpClient.Builder().addInterceptor(chain->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var url=chain.request().url();REQUESTS.add(url.toString());
                if(offline)throw new java.io.IOException("Synthetic network offline");
                assertThat(url.host()).isIn("omilic.com","raw.githubusercontent.com");
                byte[] body=switch(url.encodedPath()){
                    case "/data/catalog.json","/hongye007/open-pose-costume/main/public/data/catalog.json"->METADATA.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    case "/custom/pose/originals/synthetic.png","/hongye007/open-pose-costume/main/public/custom/pose/originals/synthetic.png"->Files.readAllBytes(Path.of("../examples/reference-pack/images/neutral-front.png"));
                    default->throw new AssertionError("Unexpected synthetic path");
                };
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,okhttp3.MediaType.get("application/octet-stream"))).build();
            }).build();
            when(clients.create(any(Duration.class),any(Duration.class),any(Duration.class))).thenReturn(http);return clients;
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("agenvas.storage.root",STORAGE::toString);}
    @Autowired ReferenceCatalogService catalog;@Autowired ReferenceCatalogPrivateImageService images;@Autowired ReferenceCatalogImportService imports;
    @Autowired IdentityService identities;@Autowired ProjectService projects;@Autowired JdbcClient jdbc;@Autowired WebApplicationContext context;
    static AdminPrincipal owner;MockMvc mvc;
    static Path temp(){try{return Files.createTempDirectory("reference-site-it-");}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
    @BeforeEach void setup(){offline=false;REQUESTS.clear();if(owner==null)owner=identities.setup("site-admin","synthetic-reference-password");mvc=webAppContextSetup(context).apply(springSecurity()).build();}
    Source source() throws Exception {return source("OMILIC");}
    Source source(String type) throws Exception {
        String id="omilic-"+UUID.randomUUID();
        var auth=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(owner,"unused",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        mvc.perform(post("/api/v1/settings/reference-catalog/sources").with(authentication(auth)).with(csrf()).contentType("application/json").content("""
                {"id":"%s","name":"Synthetic reference source","owner":"hongye007","repository":"open-pose-costume","ref":"main","format":"OPEN_POSE_COSTUME","sourceType":"%s","imageDistributionApproved":false}
                """.formatted(id,type))).andExpect(status().isCreated()).andExpect(jsonPath("$.sourceType").value(type));
        assertThat(catalog.sync(id,0).state()).isEqualTo("SUCCESS");return catalog.source(id);
    }
    @Test void oneJsonLoadsTheCatalogAndItsRelativeImageWithoutGithubRequests() throws Exception {
        Source s=source();assertThat(s.resolvedCommit()).isNull();
        var page=catalog.list(Kind.POSE,s.id(),"",null,null,null,null,null,null,0,10);
        assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().variants().getFirst().privateCacheAllowed()).isTrue();
        var image=images.acquire(owner.userId(),s.id(),s.currentSnapshotId(),"synthetic_pose","synthetic_front","one");
        assertThat(image.cached()).isTrue();assertThat(image.sha256()).hasSize(64);
        assertThat(REQUESTS).containsExactly("https://omilic.com/data/catalog.json","https://omilic.com/custom/pose/originals/synthetic.png");
        offline=true;assertThat(images.read(owner.userId(),image.id())).isNotEmpty();
        assertThat(images.acquire(owner.userId(),s.id(),s.currentSnapshotId(),"synthetic_pose","synthetic_front","one").id()).isEqualTo(image.id());
        var project=projects.create(owner.userId(),"Synthetic site import",Project.AspectRatio.LANDSCAPE_16_9);
        var imported=imports.importPrivateImage(owner.userId(),project.id(),image.id(),"import-one",new ReferenceCatalogImportService.Placement(80,80));
        assertThat(imported.canvasItemId()).isNotNull();images.clear(owner.userId(),image.id());
        String key=jdbc.sql("select object_key from asset where id=:id").param("id",imported.assetId()).query(String.class).single();assertThat(Files.readAllBytes(STORAGE.resolve(key))).isNotEmpty();
        assertThat(REQUESTS).hasSize(2);
    }
    @Test void refreshingIdenticalJsonKeepsTheSameSnapshotAndExistingImageCache() throws Exception {
        Source s=source();var image=images.acquire(owner.userId(),s.id(),s.currentSnapshotId(),"synthetic_pose","synthetic_front","cached");
        var refreshed=catalog.sync(s.id(),s.version());assertThat(refreshed.state()).isEqualTo("SUCCESS");assertThat(refreshed.snapshotId()).isEqualTo(s.currentSnapshotId());
        offline=true;assertThat(images.acquire(owner.userId(),s.id(),refreshed.snapshotId(),"synthetic_pose","synthetic_front","after-refresh").id()).isEqualTo(image.id());
        assertThat(REQUESTS).hasSize(3);
    }
    @Test void githubChoiceReadsJsonAndItsImageFromRawWithoutCommitOrTreeApiCalls() throws Exception {
        Source s=source("GITHUB");
        var image=images.acquire(owner.userId(),s.id(),s.currentSnapshotId(),"synthetic_pose","synthetic_front","github-image");assertThat(image.cached()).isTrue();
        assertThat(REQUESTS).containsExactly("https://raw.githubusercontent.com/hongye007/open-pose-costume/main/public/data/catalog.json","https://raw.githubusercontent.com/hongye007/open-pose-costume/main/public/custom/pose/originals/synthetic.png");
    }
}
