package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL/API/CAS validation with synthetic keys; no cloud or model requests. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {"agenvas.tasks.scheduler-enabled=false",
        "agenvas.library.worker-enabled=false", "agenvas.llm.scheduler-enabled=false"})
class StorageProfileManagementPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64",() -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("agenvas.storage.root", () -> System.getProperty("java.io.tmpdir") + "/agenvas-profile-it-" + POSTGRES.getContainerId());
    }
    @Autowired StorageSettingsService settings;
    @Autowired StorageRepository storage;
    @Autowired MediaRelayRepository copies;
    @Autowired TaskRepository tasks;
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;

    @Test void apiCrudReferenceGuardsAndConcurrentFreezingKeepExistingDestinations() throws Exception {
        var owner = identities.setup("profile-admin","strong-profile-password");
        var auth = UsernamePasswordAuthenticationToken.authenticated(owner,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var request = "{\"expectedVersion\":0,\"name\":\"synthetic\",\"provider\":\"ALIYUN_OSS\","
                + "\"endpoint\":\"https://oss-cn-chengdu.aliyuncs.com\",\"region\":\"cn-chengdu\",\"bucket\":\"test-bucket\","
                + "\"keyPrefix\":\"agenvas\",\"pathStyle\":false,\"accessKeyId\":\"test-access-id\",\"secretAccessKey\":\"test-secret-value\"}";
        mvc.perform(post("/api/v1/settings/storage/profiles").contentType(MediaType.APPLICATION_JSON).content(request)
                .with(authentication(auth)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.profiles[0].inUse").value(false));
        UUID id = settings.status().profiles().getFirst().id();
        settings.activate(1,id); settings.activateRelay(2,id);
        String update = updateBody(3,"edited","cn-beijing");
        mvc.perform(put("/api/v1/settings/storage/profiles/{id}",id).contentType(MediaType.APPLICATION_JSON).content(update).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/settings/storage/profiles/{id}",id).contentType(MediaType.APPLICATION_JSON).content(update)
                .with(authentication(auth))).andExpect(status().isForbidden());
        var visitor = UsernamePasswordAuthenticationToken.authenticated(owner,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mvc.perform(delete("/api/v1/settings/storage/profiles/{id}",id).param("expectedVersion","3")
                .with(authentication(visitor)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/settings/storage/profiles/{id}",id).contentType(MediaType.APPLICATION_JSON).content(update)
                .with(authentication(auth)).with(csrf())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.version").value(4)).andExpect(jsonPath("$.profiles[0].region").value("cn-beijing"))
                .andExpect(jsonPath("$.activeProfileId").value(id.toString())).andExpect(jsonPath("$.relayProfileId").value(id.toString()));
        assertThat(settings.credentials(settings.requireProfile(id))).containsExactly("test-access-id","test-secret-value");
        mvc.perform(delete("/api/v1/settings/storage/profiles/{id}",id).param("expectedVersion","3")
                .with(authentication(auth)).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORAGE_VERSION_CONFLICT"));
        mvc.perform(delete("/api/v1/settings/storage/profiles/{id}",id).param("expectedVersion","4")
                .with(authentication(auth))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/settings/storage/profiles/{id}",id).param("expectedVersion","-1")
                .with(authentication(auth)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/settings/storage/profiles/{id}",id).param("expectedVersion","4")
                .with(authentication(auth)).with(csrf())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.version").value(5)).andExpect(jsonPath("$.activeProfileId").isEmpty())
                .andExpect(jsonPath("$.relayProfileId").isEmpty()).andExpect(jsonPath("$.profiles").isEmpty());
        mvc.perform(put("/api/v1/settings/storage/profiles/{id}",id).contentType(MediaType.APPLICATION_JSON).content(updateBody(5,"missing","cn-chengdu"))
                .with(authentication(auth)).with(csrf())).andExpect(status().isNotFound());

        UUID project = projects.create(owner.userId(),"synthetic storage",dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9).id();
        UUID archive = createProfile("archive"); settings.activate(settings.status().version(),archive);
        race(() -> storage.pin(project,UUID.randomUUID(),"IMAGE"),
                () -> renameRegion(archive,"cn-beijing"));
        assertThat(settings.requireProfile(archive).region()).isEqualTo("cn-chengdu");
        assertReferenced(archive);
        settings.update(settings.status().version(),archive,"renamed",StorageProfile.Provider.ALIYUN_OSS,
                "https://oss-cn-chengdu.aliyuncs.com","cn-chengdu","test-bucket","agenvas",false,null,null);
        assertThat(settings.requireProfile(archive).name()).isEqualTo("renamed");
        assertThat(settings.status().profiles().stream().filter(p -> p.id().equals(archive)).findFirst().orElseThrow().inUse()).isTrue();

        UUID relay = createProfile("relay"); settings.activateRelay(settings.status().version(),relay);
        race(() -> {
            UUID pinned = settings.relayProfileId();
            var input = mapper.createObjectNode(); input.putArray("videos").addObject().put("relayProfileId",pinned.toString());
            Instant now = Instant.now();
            tasks.create(new Task(UUID.randomUUID(),project,null,"relay-reference",Task.Kind.VIDEO_GENERATION,Task.Status.READY,false,input,
                    "a".repeat(64),null,null,1,now,null,null,0,0,null,now,now,null));
            return pinned;
        }, () -> { settings.delete(settings.status().version(),relay); return null; });
        assertReferenced(relay);

        UUID copyProfile = createProfile("cleanup");
        copies.register(new MediaRelayRepository.Copy(UUID.randomUUID(),copyProfile,"media-relay/synthetic.mp4",Instant.now().plusSeconds(600)),Instant.now());
        assertReferenced(copyProfile);
        UUID unused = createProfile("unrelated");
        UUID activeBefore = settings.status().activeProfileId(), relayBefore = settings.status().relayProfileId();
        settings.delete(settings.status().version(),unused);
        assertThat(settings.status().activeProfileId()).isEqualTo(activeBefore);
        assertThat(settings.status().relayProfileId()).isEqualTo(relayBefore);
        assertThat(settings.status().version()).isGreaterThan(5);
    }

    /** Hold acceptance's transaction open while a management write attempts to acquire the same settings lock. */
    private void race(Callable<?> freeze, Callable<?> management) throws Exception {
        var tx = new TransactionTemplate(transactions);
        var pinned = new CountDownLatch(1); var release = new CountDownLatch(1); var attempted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> acceptance = pool.submit(() -> tx.execute(status -> {
                try { var result = freeze.call(); pinned.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return result; }
                catch (Exception e) { throw new IllegalStateException(e); }
            }));
            assertThat(pinned.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> change = pool.submit(() -> { attempted.countDown(); return management.call(); });
            try {
                assertThat(attempted.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> change.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            acceptance.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(() -> change.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .satisfies(e -> assertThat(e.getCause()).isInstanceOf(ApiProblemException.class))
                    .satisfies(e -> assertThat(((ApiProblemException)e.getCause()).code()).isEqualTo("STORAGE_PROFILE_IN_USE"));
        } finally { release.countDown(); }
    }
    private UUID createProfile(String name) {
        settings.create(settings.status().version(),name,StorageProfile.Provider.ALIYUN_OSS,"https://oss-cn-chengdu.aliyuncs.com",
                "cn-chengdu","test-bucket","agenvas",false,"test-access-id","test-secret-value");
        return settings.status().profiles().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow().id();
    }
    private StorageSettingsService.Status renameRegion(UUID id,String region) {
        return settings.update(settings.status().version(),id,"updated",StorageProfile.Provider.ALIYUN_OSS,
                "https://oss-cn-chengdu.aliyuncs.com",region,"test-bucket","agenvas",false,null,null);
    }
    private void assertReferenced(UUID id) {
        int version = settings.status().version();
        assertThatThrownBy(() -> settings.delete(version,id)).isInstanceOf(ApiProblemException.class)
                .satisfies(e -> assertThat(((ApiProblemException)e).code()).isEqualTo("STORAGE_PROFILE_IN_USE"));
        assertThat(settings.status().version()).isEqualTo(version);
        assertThatThrownBy(() -> renameRegion(id,"cn-beijing")).isInstanceOf(ApiProblemException.class);
    }
    private String updateBody(int version,String name,String region) {
        return "{\"expectedVersion\":"+version+",\"name\":\""+name+"\",\"provider\":\"ALIYUN_OSS\","
                +"\"endpoint\":\"https://oss-cn-chengdu.aliyuncs.com\",\"region\":\""+region+"\",\"bucket\":\"test-bucket\","
                +"\"keyPrefix\":\"agenvas\",\"pathStyle\":false}";
    }
}
