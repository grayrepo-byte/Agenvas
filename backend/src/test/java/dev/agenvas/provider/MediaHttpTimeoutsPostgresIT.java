package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.http.HttpTimeouts;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL publication/CAS/task bindings; no request is sent to a provider. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class MediaHttpTimeoutsPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired private MediaCapabilityService catalog;
    @Autowired private TaskService tasks;
    @Autowired private DirectMediaTaskService direct;
    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private CanvasService canvas;
    @Autowired private MediaDraftService drafts;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private WebApplicationContext context;

    @Test
    void explicitPoliciesPublishNewHistoryWhileExistingTaskBindingsAndLegacyMetadataRemainUnchanged() throws Exception {
        var owner = identities.setup("timeout-history-admin", "timeout-history-password-123");
        var project = projects.create(owner.userId(), "Timeout versions", Project.AspectRatio.SQUARE_1_1);
        var connection = catalog.createConnection("timeout-connection", "Synthetic OpenAI", "OPENAI", null,
                "synthetic-timeout-key");
        var legacy = catalog.publishCapability(connection.id(), "Legacy policy", "OPENAI_GPT_IMAGE_2");
        var oldTaskBinding = accept(owner, project, legacy.id(), "legacy-timeout-task");
        var legacySpec = catalog.pinnedSnapshot(oldTaskBinding).specJson();
        var renamed = catalog.updateCapability(connection.id(), legacy.id(), legacy.version(), "Renamed legacy", true,
                "OPENAI_GPT_IMAGE_2", null);
        assertThat(renamed.currentVersion()).isEqualTo(1);
        assertThat(catalog.pinnedSnapshot(oldTaskBinding).specJson()).isEqualTo(legacySpec);
        assertThat(catalog.httpTimeouts(oldTaskBinding)).isNull();

        var settings = mapper.createObjectNode();
        settings.set("httpTimeouts", mapper.valueToTree(new HttpTimeouts(7, 40, 50, 60)));
        var configured = catalog.updateCapability(connection.id(), legacy.id(), renamed.version(), "Policy one", true,
                "OPENAI_GPT_IMAGE_2", settings);
        var first = accept(owner, project, configured.id(), "first-timeout-task");
        assertThat(first.capabilityVersion()).isEqualTo(2);
        assertThat(catalog.httpTimeouts(first)).isEqualTo(new HttpTimeouts(7, 40, 50, 60));
        var firstSpec = catalog.pinnedSnapshot(first).specJson();

        settings.set("httpTimeouts", mapper.valueToTree(new HttpTimeouts(8, 70, 80, 90)));
        var updated = catalog.updateCapability(connection.id(), legacy.id(), configured.version(), "Policy two", true,
                "OPENAI_GPT_IMAGE_2", settings);
        assertThatThrownBy(() -> catalog.updateCapability(connection.id(), legacy.id(), configured.version(),
                "Stale policy", true, "OPENAI_GPT_IMAGE_2", settings)).isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("MEDIA_CAPABILITY_CONFLICT"));
        var second = accept(owner, project, updated.id(), "second-timeout-task");
        assertThat(second.capabilityVersion()).isEqualTo(3);
        assertThat(catalog.httpTimeouts(second)).isEqualTo(new HttpTimeouts(8, 70, 80, 90));
        assertThat(catalog.httpTimeouts(first)).isEqualTo(new HttpTimeouts(7, 40, 50, 60));
        assertThat(catalog.pinnedSnapshot(first).specJson()).isEqualTo(firstSpec);
        assertThat(catalog.httpTimeouts(oldTaskBinding)).isNull();
        assertThat(jdbc.sql("select count(*) from media_capability_version where capability_id=:id")
                .param("id", legacy.id()).query(Integer.class).single()).isEqualTo(3);

        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var response = webAppContextSetup(context).apply(springSecurity()).build()
                .perform(get("/api/v1/settings/media-connections").with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.httpTimeoutDefaults.OPENAI_GPT_IMAGE_2.connectSeconds").value(10))
                .andExpect(jsonPath("$.httpTimeoutDefaults.OPENAI_GPT_IMAGE_2.writeSeconds").value(300))
                .andExpect(jsonPath("$.httpTimeoutDefaults.MOCK_IMAGE").doesNotExist())
                .andExpect(jsonPath("$.httpTimeoutDefaults.LOCAL_IMAGE_PROCESSOR").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-timeout-key");
        assertThat(mapper.readTree(response).path("connections").valueStream()
                .filter(value -> connection.id().toString().equals(value.path("id").asText())).findFirst()
                .orElseThrow().at("/capabilities/0/settings/httpTimeouts/callSeconds").asInt()).isEqualTo(90);
    }

    private MediaCapabilityBinding accept(AdminPrincipal owner, Project project, UUID capability, String key) {
        var artifact = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, key, null).artifact();
        var card = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifact.id());
        var draft = CanvasMediaFixture.save(drafts, owner.userId(), project.id(), card, 0, "Synthetic image", null,
                null, capability);
        var task = direct.run(owner.userId(), project.id(), artifact.id(), card, draft.version(), key);
        return tasks.mediaBinding(task).orElseThrow();
    }
}
