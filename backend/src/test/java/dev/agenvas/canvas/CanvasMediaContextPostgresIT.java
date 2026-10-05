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
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.testing.ImageAssetFixture;
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
@SpringBootTest(classes = AgenvasApplication.class)
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
    @Autowired private AssetService assets;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;

    @Test
    void twoCardsKeepIndependentDraftsAndDisplayedVersions() throws Exception {
        AdminPrincipal owner = identities.setup("branch-admin",
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
        UUID firstResultCard = UUID.fromString(firstTask.path("input").path("canvasItemId").asText());
        UUID secondResultCard = UUID.fromString(secondTask.path("input").path("canvasItemId").asText());
        assertThat(firstResultCard).isEqualTo(firstCard);
        assertThat(secondResultCard).isEqualTo(secondCard);
        assertThat(secondTask.path("id").asText()).isNotEqualTo(firstTask.path("id").asText());
        assertThat(taskIds(read(mvc, auth, base + "/artifacts/" + artifactId
                + "/run?canvasItemId=" + firstResultCard))).containsExactly(firstTask.path("id").asText());
        assertThat(taskIds(read(mvc, auth, base + "/artifacts/" + artifactId
                + "/run?canvasItemId=" + secondResultCard))).containsExactly(secondTask.path("id").asText());
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
        JsonNode snapshot = read(mvc, auth, base + "/snapshot");
        assertThat(selectedVersion(snapshot.path("canvas"), firstCard))
                .isEqualTo(firstVersion.toString());
        assertThat(selectedVersion(snapshot.path("canvas"), secondCard))
                .isEqualTo(secondVersion.toString());
        JsonNode unchangedResource = read(mvc, auth, base + "/artifacts/" + artifactId);
        assertThat(unchangedResource.path("resourceDefaultVersionId").asText())
                .isEqualTo(secondVersion.toString());

        assertThat(jdbc.sql("select count(*) from project_event where project_id=:projectId "
                        + "and type='media.draft.changed' and payload_json->>'canvasItemId'=:itemId")
                .param("projectId", project.id()).param("itemId", firstCard.toString())
                .query(Integer.class).single()).isEqualTo(1);
        UUID uploadAssetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        UUID uploadTarget = UUID.randomUUID();
        JsonNode sourceBeforeUpload = read(mvc, auth, firstDraft);
        JsonNode uploaded = mapper.readTree(mvc.perform(post(base + "/canvas-items/"
                        + firstCard + "/upload-version")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"targetItemId\":\"" + uploadTarget
                                + "\",\"expectedVersion\":0,\"content\":{\"sourceType\":\"UPLOAD\","
                                + "\"assetId\":\"" + uploadAssetId + "\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID uploadedVersion = UUID.fromString(uploaded.path("selectedVersionId").asText());
        assertThat(uploaded.path("title").asText()).isEqualTo("Shared image · 上传");
        assertThat(uploadedVersion).isNotEqualTo(firstVersion);
        JsonNode uploadDraft = read(mvc, auth, base + "/canvas-items/" + uploadTarget
                + "/media-draft");
        assertThat(uploadDraft.path("prompt").asText()).isEmpty();
        assertThat(uploadDraft.path("parameters").size()).isZero();
        assertThat(uploadDraft.path("capabilityId").isNull()).isTrue();
        assertThat(uploadDraft.path("durationSeconds").isNull()).isTrue();
        assertThat(uploadDraft.path("videoInputMode").isNull()).isTrue();
        assertThat(uploadDraft.path("mediaInputs").isArray()).isTrue();
        assertThat(uploadDraft.path("mediaInputs").size()).isZero();
        assertThat(uploadDraft.path("mentions").isArray()).isTrue();
        assertThat(uploadDraft.path("mentions").size()).isZero();
        assertThat(uploadDraft.path("displayMode").asText()).isEqualTo("RESULT");
        assertThat(read(mvc, auth, firstDraft)).isEqualTo(sourceBeforeUpload);
        JsonNode replayedUpload = mapper.readTree(mvc.perform(post(base + "/canvas-items/"
                        + firstCard + "/upload-version")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"targetItemId\":\"" + uploadTarget
                                + "\",\"expectedVersion\":0,\"content\":{\"sourceType\":\"UPLOAD\","
                                + "\"assetId\":\"" + uploadAssetId + "\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(replayedUpload.path("selectedVersionId").asText())
                .isEqualTo(uploadedVersion.toString());
        assertThat(replayedUpload.path("id").asText()).isEqualTo(uploadTarget.toString());
        assertThat(replayedUpload.path("title").asText()).isEqualTo(uploaded.path("title").asText());
        assertThat(jdbc.sql("select count(*) from artifact_version where artifact_id=:id")
                .param("id", artifactId).query(Integer.class).single()).isEqualTo(3);
        assertThat(read(mvc, auth, base + "/artifacts/" + artifactId)
                .path("resourceDefaultVersionId").asText()).isEqualTo(secondVersion.toString());

        JsonNode emptyResource = mapper.readTree(mvc.perform(post(base + "/artifacts")
                        .with(auth).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "empty-upload-image")
                        .content("{\"kind\":\"IMAGE\",\"title\":\"Empty upload\",\"content\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID emptyArtifactId = UUID.fromString(emptyResource.path("id").asText());
        UUID emptyCard = UUID.randomUUID();
        place(mvc, auth, base, emptyCard, emptyArtifactId, 3);
        UUID emptyUploadAssetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        String emptyUploadBody = "{\"targetItemId\":\"" + emptyCard
                + "\",\"expectedVersion\":0,\"content\":{\"sourceType\":\"UPLOAD\","
                + "\"assetId\":\"" + emptyUploadAssetId + "\"}}";
        JsonNode filledEmptyCard = mapper.readTree(mvc.perform(post(base + "/canvas-items/"
                        + emptyCard + "/upload-version")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content(emptyUploadBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(filledEmptyCard.path("id").asText()).isEqualTo(emptyCard.toString());
        assertThat(filledEmptyCard.path("selectedVersionId").isTextual()).isTrue();
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:projectId "
                        + "and subject_id=:artifactId")
                .param("projectId", project.id()).param("artifactId", emptyArtifactId)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from canvas_connection where project_id=:projectId "
                        + "and relation_type='MEDIA_DERIVATION' "
                        + "and source_canvas_item_id=:itemId")
                .param("projectId", project.id()).param("itemId", emptyCard)
                .query(Integer.class).single()).isZero();
        JsonNode replayedEmptyUpload = mapper.readTree(mvc.perform(post(base + "/canvas-items/"
                        + emptyCard + "/upload-version")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content(emptyUploadBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(replayedEmptyUpload.path("selectedVersionId").asText())
                .isEqualTo(filledEmptyCard.path("selectedVersionId").asText());

        // A local markup saves user bytes, immutable provenance and one independent node; no Task.
        UUID markupTarget = UUID.randomUUID();
        String markupBody = mapper.createObjectNode().put("targetItemId", markupTarget.toString())
                .put("expectedVersion", 0).put("purpose", "BRUSH_MARKUP")
                .put("sourceVersionId", firstVersion.toString())
                .set("content", mapper.createObjectNode().put("sourceType", "UPLOAD")
                        .put("assetId", uploadAssetId.toString())).toString();
        Integer taskCount = jdbc.sql("select count(*) from task where project_id=:id")
                .param("id", project.id()).query(Integer.class).single();
        JsonNode marked = mapper.readTree(mvc.perform(post(base + "/canvas-items/"
                        + firstCard + "/upload-version").with(auth).with(csrf())
                        .contentType("application/json").content(markupBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(marked.path("title").asText()).isEqualTo("Shared image · 画笔标注");
        assertThat(marked.path("selectedVersion").path("createdByKind").asText()).isEqualTo("USER");
        assertThat(marked.path("selectedVersion").path("baseVersionId").asText())
                .isEqualTo(firstVersion.toString());
        assertThat(marked.path("selectedVersion").path("frozenInput").path("operation").asText())
                .isEqualTo("BRUSH_MARKUP");
        assertThat(read(mvc, auth, base + "/canvas-items/" + markupTarget + "/media-draft")
                .path("prompt").asText()).isEmpty();
        JsonNode markupReplay = mapper.readTree(mvc.perform(post(base + "/canvas-items/"
                        + firstCard + "/upload-version").with(auth).with(csrf())
                        .contentType("application/json").content(markupBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(markupReplay.path("selectedVersionId")).isEqualTo(marked.path("selectedVersionId"));
        assertThat(jdbc.sql("select count(*) from task where project_id=:id")
                .param("id", project.id()).query(Integer.class).single()).isEqualTo(taskCount);
        assertThat(selectedVersion(read(mvc, auth, base + "/canvas/items"), firstCard))
                .isEqualTo(firstVersion.toString());
        assertThat(read(mvc, auth, firstDraft)).isEqualTo(sourceBeforeUpload);
        // Same target with a different intent cannot replay an unrelated upload.
        mvc.perform(post(base + "/canvas-items/" + firstCard + "/upload-version")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content(markupBody.replace("BRUSH_MARKUP", "UPLOAD")))
                .andExpect(status().isBadRequest());
        String staleMarkup = markupBody.replace(markupTarget.toString(), UUID.randomUUID().toString())
                .replace(firstVersion.toString(), secondVersion.toString());
        mvc.perform(post(base + "/canvas-items/" + firstCard + "/upload-version")
                        .with(auth).with(csrf()).contentType("application/json").content(staleMarkup))
                .andExpect(status().isConflict());
        mvc.perform(post(base + "/artifacts/" + artifactId + "/image-operations")
                        .with(auth).with(csrf()).header("Idempotency-Key", "old-ai-markup")
                        .contentType("application/json")
                        .content("{\"canvasItemId\":\"" + firstCard + "\",\"sourceVersionId\":\""
                                + firstVersion + "\",\"expectedCanvasItemVersion\":0,"
                                + "\"operation\":\"BRUSH_MARKUP\",\"parameters\":{}}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post(base + "/canvas/commands").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"commands\":[{\"type\":\"REMOVE\",\"itemId\":\""
                                + firstCard + "\",\"expectedVersion\":0}]}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select canvas_item_id from task_artifact_target where task_id=:id")
                .param("id", UUID.fromString(firstTask.path("id").asText()))
                .query(UUID.class).optional()).isEmpty();
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
