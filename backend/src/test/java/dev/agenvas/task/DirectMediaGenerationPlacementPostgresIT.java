package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaExecutionWorker;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Regeneration adds immutable versions to the same node without adding lineage edges. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=direct-placement-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.comfyui.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class DirectMediaGenerationPlacementPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private CanvasService canvas;
    @Autowired private CanvasConnectionService connections;
    @Autowired private MediaExecutionWorker worker;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper mapper;

    @Test
    void fillsAnEmptyCardThenRegeneratesInTheSameCard() throws Exception {
        AdminPrincipal owner = identities.setup("direct-placement-integration-secret",
                "placement-admin", "placement-password-123");
        Project project = projects.create(owner.userId(), "Direct placement",
                Project.AspectRatio.LANDSCAPE_16_9);
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String base = "/api/v1/projects/" + project.id();
        JsonNode artifact = mapper.readTree(mvc.perform(post(base + "/artifacts")
                        .with(auth).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "placement-image-create")
                        .content("{\"kind\":\"IMAGE\",\"title\":\"Concept\",\"content\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String artifactId = artifact.path("id").asText();
        String sourceItemId = UUID.randomUUID().toString();
        mvc.perform(post(base + "/canvas/commands").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"commands\":[{\"type\":\"PLACE_ARTIFACT\",\"itemId\":\""
                                + sourceItemId + "\",\"artifactId\":\"" + artifactId
                                + "\",\"x\":0,\"y\":0,\"width\":280,\"height\":240,"
                                + "\"zIndex\":0,\"locked\":false}]}"))
                .andExpect(status().isOk());
        String draftPath = base + "/canvas-items/" + sourceItemId + "/media-draft";
        JsonNode firstDraft = mapper.readTree(mvc.perform(put(draftPath).with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"prompt\":\"First image\","
                                + "\"parameters\":{},\"videoInputMode\":null,"
                                + "\"mediaInputs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String runPath = base + "/artifacts/" + artifactId + "/run";
        JsonNode firstTask = mapper.readTree(mvc.perform(post(runPath).with(auth).with(csrf())
                        .header("Idempotency-Key", "placement-first-run")
                        .contentType("application/json")
                        .content("{\"canvasItemId\":\"" + sourceItemId
                                + "\",\"expectedDraftVersion\":"
                                + firstDraft.path("version").asLong() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(firstTask.at("/input/canvasItemId").asText()).isEqualTo(sourceItemId);
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(1);
        assertThat(connections.list(owner.userId(), project.id())).isEmpty();

        assertThat(worker.submitOnce("direct-placement-worker")).isEqualTo(1);
        JsonNode currentDraft = mapper.readTree(mvc.perform(get(draftPath).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode revisedDraft = mapper.readTree(mvc.perform(put(draftPath).with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":" + currentDraft.path("version").asLong()
                                + ",\"prompt\":\"Revised image\",\"parameters\":{},"
                                + "\"videoInputMode\":null,\"mediaInputs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode secondTask = mapper.readTree(mvc.perform(post(runPath).with(auth).with(csrf())
                        .header("Idempotency-Key", "placement-second-run")
                        .contentType("application/json")
                        .content("{\"canvasItemId\":\"" + sourceItemId
                                + "\",\"expectedDraftVersion\":"
                                + revisedDraft.path("version").asLong() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(secondTask.at("/input/canvasItemId").asText()).isEqualTo(sourceItemId);
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(1);
        assertThat(connections.list(owner.userId(), project.id())).isEmpty();
        assertThat(worker.submitOnce("direct-placement-worker")).isEqualTo(1);
        assertThat(canvas.listMediaVersions(owner.userId(), project.id(), UUID.fromString(sourceItemId)))
                .hasSize(2);
        assertThat(canvas.list(owner.userId(), project.id()).getFirst().selectedVersion().versionNo())
                .isEqualTo(2);
    }
}
