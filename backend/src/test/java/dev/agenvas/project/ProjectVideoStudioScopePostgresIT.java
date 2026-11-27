package dev.agenvas.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.ImageAssetFixture;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Video Studio owns a private media scope, not a user canvas. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class ProjectVideoStudioScopePostgresIT {
    @TempDir
    static Path storage;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> storage.toString());
    }

    @Autowired
    ProjectService projects;
    @Autowired
    AssetService assets;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    WebApplicationContext context;

    @Test
    void concurrentFirstEntryReturnsOneScopePerOwnerAndDoesNotCreateACanvas() throws Exception {
        AdminPrincipal owner = account("video-concurrent-owner");
        CountDownLatch start = new CountDownLatch(1);
        UUID scope;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = IntStream.range(0, 16).mapToObj(ignored -> executor.submit(() -> {
                start.await();
                return projects.ensureVideoStudioScope(owner.userId());
            })).toList();
            start.countDown();
            scope = calls.getFirst().get(30, TimeUnit.SECONDS).id();
            for (var call : calls) {
                Project project = call.get(30, TimeUnit.SECONDS);
                assertThat(project.id()).isEqualTo(scope);
                assertThat(project.ownerId()).isEqualTo(owner.userId());
            }
        }
        assertThat(projects.ensureVideoStudioScope(owner.userId()).id()).isEqualTo(scope);
        assertThat(jdbc.sql("select purpose from project where owner_id=:owner").param("owner", owner.userId())
                .query(String.class).list()).containsExactly("VIDEO_STUDIO");
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:scope").param("scope", scope)
                .query(Integer.class).single()).isZero();
        mvc().perform(get("/api/v1/projects").with(auth(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        assertThat(projects.ensureVideoStudioScope(account("video-concurrent-other").userId()).id())
                .isNotEqualTo(scope);
    }

    @Test
    void bothStudioScopesAreHiddenFromProjectManagementCanvasAndRunEndpoints() throws Exception {
        AdminPrincipal owner = account("video-management-owner");
        UUID video = projects.ensureVideoStudioScope(owner.userId()).id();
        UUID image = projects.ensureImageStudioScope(owner.userId()).id();
        UUID canvas = projects.create(owner.userId(), "Visible canvas", Project.AspectRatio.PORTRAIT_9_16).id();
        assertThat(List.of(video, image, canvas)).doesNotHaveDuplicates();
        projects.requireVideoStudioScope(owner.userId(), video);
        projects.requireImageStudioScope(owner.userId(), image);
        assertNotFound(() -> projects.requireVideoStudioScope(owner.userId(), image));
        assertNotFound(() -> projects.requireVideoStudioScope(owner.userId(), canvas));
        assertNotFound(() -> projects.requireImageStudioScope(owner.userId(), video));
        var mvc = mvc();
        var auth = auth(owner);
        mvc.perform(get("/api/v1/projects").param("includeArchived", "true").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(canvas.toString()));
        for (UUID scope : List.of(video, image)) {
            String base = "/api/v1/projects/" + scope;
            for (String path : List.of("", "/snapshot", "/canvas/items", "/canvas/editor")) {
                mvc.perform(get(base + path).with(auth)).andExpect(status().isNotFound());
            }
            mvc.perform(patch(base).with(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedVersion\":0,\"name\":\"Renamed\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(post(base + "/archive").with(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedVersion\":0}"))
                    .andExpect(status().isNotFound());
            mvc.perform(delete(base).with(auth).with(csrf()).param("expectedVersion", "0"))
                    .andExpect(status().isNotFound());
            mvc.perform(post(base + "/canvas/edits").with(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"commandId":"%s","operation":"GROUP","itemChanges":[],"groupChanges":[
                              {"type":"UPSERT","groupId":"%s","expectedVersion":0,"name":"Hidden","collapsed":false}
                            ],"copies":[],"connectionCopies":[]}
                            """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                    .andExpect(status().isNotFound());
            UUID agent = UUID.randomUUID();
            mvc.perform(get(base + "/runs/preflight").with(auth).param("agentId", agent.toString()))
                    .andExpect(status().isNotFound());
            mvc.perform(post(base + "/runs").with(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "hidden-studio-run")
                    .content("{\"agentId\":\"%s\",\"instruction\":\"Do not start\"}".formatted(agent)))
                    .andExpect(status().isNotFound());
            assertNotFound(() -> projects.requireAvailableRunSlot(owner.userId(), scope));
            assertThat(projects.get(owner.userId(), scope).version()).isZero();
        }
    }

    @Test
    void onlyOwnerCanReadAssetsFromTheirActualVideoScope() throws Exception {
        AdminPrincipal owner = account("video-asset-owner");
        AdminPrincipal other = account("video-asset-other");
        UUID video = projects.ensureVideoStudioScope(owner.userId()).id();
        UUID image = projects.ensureImageStudioScope(owner.userId()).id();
        assertNotFound(() -> projects.requireVideoStudioScope(other.userId(), video));
        UUID asset = ImageAssetFixture.archive(assets, owner.userId(), video);
        String base = "/api/v1/projects/" + video + "/assets";
        String resource = base + "/" + asset;
        var mvc = mvc();
        mvc.perform(get(resource).with(auth(owner))).andExpect(status().isOk());
        var content = mvc.perform(get(resource + "/content").with(auth(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(content)).getWidth()).isEqualTo(2);
        mvc.perform(head(resource + "/content").with(auth(owner))).andExpect(status().isOk());
        mvc.perform(get(resource + "/thumbnail").with(auth(owner))).andExpect(status().isOk());
        for (String suffix : List.of("", "/content", "/thumbnail")) {
            mvc.perform(get(resource + suffix).with(auth(other))).andExpect(status().isNotFound());
            mvc.perform(get(resource + suffix)).andExpect(status().isUnauthorized());
        }
        mvc.perform(head(resource + "/content").with(auth(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/" + image + "/assets/" + asset + "/content").with(auth(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(multipart(base).file("file", content).with(auth(owner)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    private AdminPrincipal account(String login) {
        // Authentication is supplied by MockMvc; the product allows only one active account.
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into app_user(id,login_name,password_hash,status,created_at)
                values (:id,:login,'synthetic-hash','DISABLED',now())
                """).param("id", id).param("login", login).update();
        return new AdminPrincipal(id, login);
    }

    private MockMvc mvc() {
        return webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static RequestPostProcessor auth(AdminPrincipal owner) {
        return authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiProblemException.class,
                error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }
}
