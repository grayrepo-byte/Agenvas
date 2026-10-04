package dev.agenvas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.testing.ImageAssetFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** HTTP and PostgreSQL proof that guessed resource IDs do not expand project scope. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class ResourceScopePostgresIT {

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
    @Autowired private AgentInstanceService agents;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private AgentRunService runs;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext context;

    @Test
    void guessedIdsDoNotExposeArtifactAssetRunOrEventStream() throws Exception {
        AdminPrincipal owner = identities.setup("scope-admin", "scope-password-123");
        AdminPrincipal foreign = new AdminPrincipal(UUID.randomUUID(), "foreign-principal");
        Project target = projects.create(owner.userId(), "Private target",
                Project.AspectRatio.LANDSCAPE_16_9);
        Project other = projects.create(owner.userId(), "Different project",
                Project.AspectRatio.SQUARE_1_1);
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), target.id());
        UUID artifactId = artifacts.create(owner.userId(), target.id(), Artifact.Kind.TEXT,
                "Private title", mapper.readTree("{\"format\":\"MARKDOWN\",\"text\":\"SECRET_SCOPE_MARKER\"}"))
                .artifact().id();
        AgentInstance agent = agents.create(owner.userId(), target.id(),
                "Creator", "Create", List.of());
        UUID runId = runs.create(owner.userId(), target.id(), agent.id(),
                "SECRET_SCOPE_MARKER", "resource-scope-run").run().id();

        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        String base = "/api/v1/projects/" + target.id();
        String otherBase = "/api/v1/projects/" + other.id();
        mvc.perform(get(base + "/artifacts/" + artifactId)
                        .with(authentication(asUser(owner))))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("SECRET_SCOPE_MARKER"));
        mvc.perform(get(base + "/assets/" + assetId)
                        .with(authentication(asUser(owner))))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/runs/" + runId)
                        .with(authentication(asUser(owner))))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("SECRET_SCOPE_MARKER"));
        String[] targetPaths = {
                base + "/artifacts/" + artifactId,
                base + "/artifacts/" + artifactId + "/versions",
                base + "/assets/" + assetId,
                base + "/assets/" + assetId + "/content",
                base + "/assets/" + assetId + "/thumbnail",
                base + "/runs/" + runId,
                base + "/events?after=0"
        };
        String[] misplacedPaths = {
                otherBase + "/artifacts/" + artifactId,
                otherBase + "/artifacts/" + artifactId + "/versions",
                otherBase + "/assets/" + assetId,
                otherBase + "/assets/" + assetId + "/content",
                otherBase + "/assets/" + assetId + "/thumbnail",
                otherBase + "/runs/" + runId
        };
        for (int index = 0; index < targetPaths.length; index++) {
            String requested = targetPaths[index];
            mvc.perform(get(requested).accept(MediaType.TEXT_EVENT_STREAM)
                            .with(authentication(asUser(foreign))))
                    .andExpect(status().isNotFound())
                    .andExpect(result -> assertNoPrivateContent(result.getResponse().getContentAsString()));
            // A different project event stream belongs to this same owner and is valid;
            // only the resource-specific paths above can be "misplaced" by an ID.
            if (index < misplacedPaths.length) {
                mvc.perform(get(misplacedPaths[index]).accept(MediaType.TEXT_EVENT_STREAM)
                                .with(authentication(asUser(owner))))
                        .andExpect(status().isNotFound())
                        .andExpect(result -> assertNoPrivateContent(result.getResponse().getContentAsString()));
            }
            mvc.perform(get(requested).accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isUnauthorized())
                    .andExpect(result -> assertNoPrivateContent(result.getResponse().getContentAsString()));
        }
    }

    /** Verifies that problem responses never include private content or storage coordinates. */
    private static void assertNoPrivateContent(String body) {
        assertThat(body).doesNotContain("SECRET_SCOPE_MARKER", "Private title", "objectKey");
    }

    /** Supplies a synthetic authenticated principal to isolate owner checks from login checks. */
    private static UsernamePasswordAuthenticationToken asUser(AdminPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-resource-scope-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
