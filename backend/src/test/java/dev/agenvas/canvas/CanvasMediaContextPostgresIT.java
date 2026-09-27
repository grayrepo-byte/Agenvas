package dev.agenvas.canvas;

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
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
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

/** Real PostgreSQL proof that media work belongs to one CanvasItem, not its Artifact. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=canvas-media-context-secret")
class CanvasMediaContextPostgresIT {
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
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;

    @Test
    void twoCardsKeepIndependentDraftsAndDisplayedVersions() throws Exception {
        AdminPrincipal owner = identities.setup("canvas-media-context-secret", "branch-admin",
                "branch-password-123");
        Project project = projects.create(owner.userId(), "Branches",
                Project.AspectRatio.LANDSCAPE_16_9);
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String base = "/api/v1/projects/" + project.id();

        JsonNode resource = mapper.readTree(mvc.perform(post(base + "/artifacts")
                        .with(auth).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "branch-image")
                        .content("{\"kind\":\"IMAGE\",\"title\":\"Shared image\",\"content\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID artifactId = UUID.fromString(resource.path("id").asText());
        UUID firstVersion = addVersion(project.id(), artifactId, 1);
        UUID secondVersion = addVersion(project.id(), artifactId, 2);
        setResourceDefault(artifactId, firstVersion);

        UUID firstCard = UUID.randomUUID();
        place(mvc, auth, base, firstCard, artifactId, 0);
        setResourceDefault(artifactId, secondVersion);
        UUID secondCard = UUID.randomUUID();
        place(mvc, auth, base, secondCard, artifactId, 1);

        String firstDraft = base + "/canvas-items/" + firstCard + "/media-draft";
        String secondDraft = base + "/canvas-items/" + secondCard + "/media-draft";
        mvc.perform(put(firstDraft).with(auth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"prompt\":\"first branch\"}"))
                .andExpect(status().isOk());
        mvc.perform(put(secondDraft).with(auth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"prompt\":\"second branch\"}"))
                .andExpect(status().isOk());
        mvc.perform(put(firstDraft).with(auth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"prompt\":\"stale overwrite\"}"))
                .andExpect(status().isConflict());

        JsonNode first = read(mvc, auth, firstDraft);
        JsonNode second = read(mvc, auth, secondDraft);
        assertThat(first.path("canvasItemId").asText()).isEqualTo(firstCard.toString());
        assertThat(first.path("prompt").asText()).isEqualTo("first branch");
        assertThat(second.path("prompt").asText()).isEqualTo("second branch");

        JsonNode firstTask = run(mvc, auth, base, artifactId, firstCard,
                "first-card-run");
        JsonNode secondTask = run(mvc, auth, base, artifactId, secondCard,
                "second-card-run");
        assertThat(secondTask.path("id").asText()).isNotEqualTo(firstTask.path("id").asText());
        assertThat(taskIds(read(mvc, auth, base + "/artifacts/" + artifactId
                + "/run?canvasItemId=" + firstCard))).containsExactly(firstTask.path("id").asText());
        assertThat(taskIds(read(mvc, auth, base + "/artifacts/" + artifactId
                + "/run?canvasItemId=" + secondCard))).containsExactly(secondTask.path("id").asText());
        assertThat(jdbc.sql("select expected_current_version_id from task_artifact_target "
                        + "where task_id=:taskId")
                .param("taskId", UUID.fromString(firstTask.path("id").asText()))
                .query(UUID.class).single()).isEqualTo(firstVersion);
        assertThat(jdbc.sql("select expected_current_version_id from task_artifact_target "
                        + "where task_id=:taskId")
                .param("taskId", UUID.fromString(secondTask.path("id").asText()))
                .query(UUID.class).single()).isEqualTo(secondVersion);
        cancel(mvc, auth, base, firstTask.path("id").asText());
        cancel(mvc, auth, base, secondTask.path("id").asText());

        JsonNode canvas = read(mvc, auth, base + "/canvas/items");
        assertThat(selectedVersion(canvas, firstCard)).isEqualTo(firstVersion.toString());
        assertThat(selectedVersion(canvas, secondCard)).isEqualTo(secondVersion.toString());

        mvc.perform(post(base + "/canvas-items/" + secondCard + "/select-version")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"versionId\":\"" + firstVersion
                                + "\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        JsonNode selected = read(mvc, auth, base + "/canvas/items");
        assertThat(selectedVersion(selected, firstCard)).isEqualTo(firstVersion.toString());
        assertThat(selectedVersion(selected, secondCard)).isEqualTo(firstVersion.toString());
        JsonNode snapshot = read(mvc, auth, base + "/snapshot");
        assertThat(selectedVersion(snapshot.path("canvas"), firstCard))
                .isEqualTo(firstVersion.toString());
        assertThat(selectedVersion(snapshot.path("canvas"), secondCard))
                .isEqualTo(firstVersion.toString());
        JsonNode unchangedResource = read(mvc, auth, base + "/artifacts/" + artifactId);
        assertThat(unchangedResource.path("resourceDefaultVersionId").asText())
                .isEqualTo(secondVersion.toString());

        assertThat(jdbc.sql("select count(*) from project_event where project_id=:projectId "
                        + "and type='media.draft.changed' and payload_json->>'canvasItemId'=:itemId")
                .param("projectId", project.id()).param("itemId", firstCard.toString())
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from project_event where project_id=:projectId "
                        + "and type='canvas.item.selected_version.changed' "
                        + "and payload_json->>'canvasItemId'=:itemId")
                .param("projectId", project.id()).param("itemId", secondCard.toString())
                .query(Integer.class).single()).isEqualTo(1);
    }

    private UUID addVersion(UUID projectId, UUID artifactId, int versionNo) {
        UUID versionId = UUID.randomUUID();
        jdbc.sql("insert into artifact_version(id,project_id,artifact_id,version_no,"
                        + "schema_version,content_json,input_refs_json,created_by_kind,created_at) "
                        + "values (:id,:projectId,:artifactId,:versionNo,1,cast(:content as jsonb),"
                        + "cast('[]' as jsonb),'USER',now())")
                .param("id", versionId).param("projectId", projectId)
                .param("artifactId", artifactId).param("versionNo", versionNo)
                .param("content", "{\"assetId\":\"" + UUID.randomUUID()
                        + "\",\"sourceType\":\"UPLOAD\"}")
                .update();
        return versionId;
    }

    private void setResourceDefault(UUID artifactId, UUID versionId) {
        jdbc.sql("update artifact set resource_default_version_id=:versionId where id=:artifactId")
                .param("versionId", versionId).param("artifactId", artifactId).update();
    }

    private void place(MockMvc mvc,
            org.springframework.test.web.servlet.request.RequestPostProcessor auth,
            String base, UUID itemId, UUID artifactId, int zIndex) throws Exception {
        mvc.perform(post(base + "/canvas/commands").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"commands\":[{\"type\":\"PLACE_ARTIFACT\",\"itemId\":\""
                                + itemId + "\",\"artifactId\":\"" + artifactId
                                + "\",\"x\":0,\"y\":0,\"width\":280,\"height\":240,"
                                + "\"zIndex\":" + zIndex + ",\"locked\":false}]}"))
                .andExpect(status().isOk());
    }

    private JsonNode run(MockMvc mvc,
            org.springframework.test.web.servlet.request.RequestPostProcessor auth,
            String base, UUID artifactId, UUID itemId, String key) throws Exception {
        return mapper.readTree(mvc.perform(post(base + "/artifacts/" + artifactId + "/run")
                        .with(auth).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", key)
                        .content("{\"canvasItemId\":\"" + itemId
                                + "\",\"expectedDraftVersion\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void cancel(MockMvc mvc,
            org.springframework.test.web.servlet.request.RequestPostProcessor auth,
            String base, String taskId) throws Exception {
        mvc.perform(post(base + "/tasks/" + taskId + "/cancel-queued")
                        .with(auth).with(csrf()))
                .andExpect(status().isOk());
    }

    private JsonNode read(MockMvc mvc,
            org.springframework.test.web.servlet.request.RequestPostProcessor auth, String path)
            throws Exception {
        return mapper.readTree(mvc.perform(get(path).with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String selectedVersion(JsonNode canvas, UUID itemId) {
        for (JsonNode item : canvas.path("items")) {
            if (itemId.toString().equals(item.path("id").asText())) {
                return item.path("selectedVersionId").asText();
            }
        }
        throw new AssertionError("CanvasItem missing: " + itemId);
    }

    private List<String> taskIds(JsonNode tasks) {
        return java.util.stream.StreamSupport.stream(tasks.spliterator(), false)
                .map(task -> task.path("id").asText()).toList();
    }
}
