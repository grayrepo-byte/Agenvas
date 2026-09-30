package dev.agenvas.artifact;

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
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.task.application.TaskService;
import org.springframework.jdbc.core.simple.JdbcClient;
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

/** The project API keeps an empty media Artifact and its editable draft across reads. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=media-draft-bootstrap-secret-2026")
class MediaDraftPostgresIT {
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
    @Autowired private MediaExecutionWorker worker;
    @Autowired private TaskService taskService;

    @Test
    void emptyImageCardKeepsDraftAndRejectsStaleSave() throws Exception {
        AdminPrincipal owner = identities.setup("media-draft-bootstrap-secret-2026", "draft-admin",
                "draft-password-123");
        Project project = projects.create(owner.userId(), "Draft workspace",
                Project.AspectRatio.LANDSCAPE_16_9);
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String base = "/api/v1/projects/" + project.id();
        JsonNode created = mapper.readTree(mvc.perform(post(base + "/artifacts")
                        .with(auth).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "draft-image-create")
                        .content("{\"kind\":\"IMAGE\",\"title\":\"Concept\",\"content\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String artifactId = created.path("id").asText();
        assertThat(UUID.fromString(artifactId)).isNotNull();
        assertThat(created.path("resourceDefaultVersion").isNull()).isTrue();
        String canvasItemId = place(mvc, auth, base, artifactId);
        String draftPath = base + "/canvas-items/" + canvasItemId + "/media-draft";
        JsonNode initial = mapper.readTree(mvc.perform(get(draftPath).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(initial.path("prompt").asText()).isEmpty();
        assertThat(initial.path("version").asLong()).isZero();

        String save = "{\"expectedVersion\":0,\"prompt\":\"A red kite over a lake\","
                + "\"parameters\":{},\"videoInputMode\":null,\"mediaInputs\":[],\"mentions\":[]}";
        mvc.perform(put(draftPath).with(auth).with(csrf()).contentType("application/json")
                .content(save)).andExpect(status().isOk());
        mvc.perform(put(draftPath).with(auth).with(csrf()).contentType("application/json")
                .content(save)).andExpect(status().isConflict());
        JsonNode restored = mapper.readTree(mvc.perform(get(draftPath).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(restored.path("prompt").asText()).isEqualTo("A red kite over a lake");
        assertThat(restored.path("version").asLong()).isEqualTo(1);
        mvc.perform(get(base + "/artifacts/" + artifactId).with(auth))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/artifacts/" + artifactId + "/versions").with(auth))
                .andExpect(status().isOk());

        String runPath = base + "/artifacts/" + artifactId + "/run";
        String runPayload = "{\"canvasItemId\":\"" + canvasItemId
                + "\",\"expectedDraftVersion\":1}";
        JsonNode run = mapper.readTree(mvc.perform(post(runPath).with(auth).with(csrf())
                        .header("Idempotency-Key", "direct-image-1")
                        .contentType("application/json").content(runPayload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        UUID taskId = UUID.fromString(run.path("id").asText());
        assertThat(run.path("runId").isNull()).isTrue();
        // 收缩后任务上不再有计划字段，直连任务响应里连该字段都不再出现。
        assertThat(run.has("planId")).isFalse();
        assertThat(jdbc.sql("select origin from task where id=:id").param("id", taskId)
                .query(String.class).single()).isEqualTo("USER_DIRECT");
        JsonNode duplicate = mapper.readTree(mvc.perform(post(runPath).with(auth).with(csrf())
                        .header("Idempotency-Key", "direct-image-1")
                        .contentType("application/json").content(runPayload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(duplicate.path("id").asText()).isEqualTo(taskId.toString());
        String resultCanvasItemId = run.path("input").path("canvasItemId").asText();
        assertThat(resultCanvasItemId).isEqualTo(canvasItemId);
        String resultDraftPath = base + "/canvas-items/" + resultCanvasItemId + "/media-draft";
        JsonNode queue = mapper.readTree(mvc.perform(get(base + "/tasks/" + taskId + "/queue")
                        .with(auth)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(queue.path("reason").asText()).isEqualTo("WAITING_WORKER");
        assertThat(queue.path("waitingAhead").asLong()).isZero();
        assertThat(worker.submitOnce("draft-integration-worker")).isEqualTo(1);
        JsonNode completed = mapper.readTree(mvc.perform(get(base + "/tasks/" + taskId)
                        .with(auth)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode image = mapper.readTree(mvc.perform(get(base + "/artifacts/" + artifactId)
                        .with(auth)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(image.path("resourceDefaultVersion").isNull()).isTrue();
        JsonNode completedCanvas = mapper.readTree(mvc.perform(get(base + "/canvas/items")
                        .with(auth)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        JsonNode completedCard = java.util.stream.StreamSupport.stream(
                        completedCanvas.path("items").spliterator(), false)
                .filter(item -> resultCanvasItemId.equals(item.path("id").asText()))
                .findFirst().orElseThrow();
        assertThat(completedCard.path("selectedVersionId").isTextual()).isTrue();
        assertThat(completedCard.path("selectedVersion").path("createdByKind").asText())
                .isEqualTo("TASK");
        JsonNode resultDraft = mapper.readTree(mvc.perform(get(resultDraftPath).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(resultDraft.path("displayMode").asText()).isEqualTo("RESULT");
        mvc.perform(put(draftPath).with(auth).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":1,\"prompt\":\"Second concept\","
                        + "\"parameters\":{},\"videoInputMode\":null,\"mediaInputs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk());
        JsonNode edited = mapper.readTree(mvc.perform(get(draftPath).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(edited.path("displayMode").asText()).isEqualTo("RESULT");
        assertThat(edited.path("prompt").asText()).isEqualTo("Second concept");
        JsonNode retainedResult = mapper.readTree(mvc.perform(put(draftPath)
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":" + edited.path("version").asLong()
                                + ",\"prompt\":\"Next concept\",\"parameters\":{},"
                                + "\"videoInputMode\":null,\"mediaInputs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(retainedResult.path("displayMode").asText()).isEqualTo("RESULT");
        JsonNode reloadedResult = mapper.readTree(mvc.perform(get(draftPath).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(reloadedResult.path("displayMode").asText()).isEqualTo("RESULT");
        assertThat(reloadedResult.path("prompt").asText()).isEqualTo("Next concept");
        assertThat(reloadedResult.path("version").asLong())
                .isEqualTo(retainedResult.path("version").asLong());

        // Only an explicit run switches the card face; the previous result stays selected.
        JsonNode nextRun = mapper.readTree(mvc.perform(post(runPath).with(auth).with(csrf())
                        .header("Idempotency-Key", "direct-image-next-concept")
                        .contentType("application/json").content("{\"canvasItemId\":\""
                                + canvasItemId + "\",\"expectedDraftVersion\":"
                                + reloadedResult.path("version").asLong() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode runningDraft = mapper.readTree(mvc.perform(get(draftPath).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(runningDraft.path("displayMode").asText()).isEqualTo("DRAFT");
        JsonNode retainedImage = mapper.readTree(mvc.perform(get(base + "/artifacts/" + artifactId)
                        .with(auth)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(retainedImage.path("resourceDefaultVersionId").isNull()).isTrue();
        mvc.perform(post(base + "/tasks/" + nextRun.path("id").asText() + "/cancel-queued")
                .with(auth).with(csrf())).andExpect(status().isOk());

        long canvasCountBeforeBatch = jdbc.sql("select count(*) from canvas_item where project_id=:projectId")
                .param("projectId", project.id()).query(Long.class).single();
        JsonNode batchDraft = mapper.readTree(mvc.perform(put(draftPath).with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":" + runningDraft.path("version").asLong()
                                + ",\"prompt\":\"Two portrait studies\",\"parameters\":{"
                                + "\"aspectRatio\":\"9:16\",\"resolution\":\"2K\","
                                + "\"quality\":\"high\",\"transparentBackground\":false,"
                                + "\"generationCount\":2},"
                                + "\"videoInputMode\":null,\"mediaInputs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode batchPrimary = mapper.readTree(mvc.perform(post(runPath).with(auth).with(csrf())
                        .header("Idempotency-Key", "direct-image-two-new-nodes")
                        .contentType("application/json").content("{\"canvasItemId\":\""
                                + canvasItemId + "\",\"expectedDraftVersion\":"
                                + batchDraft.path("version").asLong() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<UUID> batchTaskIds = jdbc.sql("select id from task where project_id=:projectId "
                        + "and input_json->>'sourceCanvasItemId'=:source "
                        + "and input_json->>'generationCount'='2'")
                .param("projectId", project.id()).param("source", canvasItemId)
                .query(UUID.class).list();
        assertThat(batchTaskIds).hasSize(2).contains(UUID.fromString(batchPrimary.path("id").asText()));
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:projectId")
                .param("projectId", project.id()).query(Long.class).single())
                .isEqualTo(canvasCountBeforeBatch + 1);
        for (UUID batchTaskId : batchTaskIds) {
            mvc.perform(post(base + "/tasks/" + batchTaskId + "/cancel-queued")
                    .with(auth).with(csrf())).andExpect(status().isOk());
        }

        JsonNode sameCardBatchDraft = mapper.readTree(mvc.perform(put(draftPath).with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":" + batchDraft.path("version").asLong()
                                + ",\"prompt\":\"Two alternatives in one history\",\"parameters\":{"
                                + "\"aspectRatio\":\"1:1\",\"resolution\":\"1K\","
                                + "\"quality\":\"medium\",\"transparentBackground\":false,"
                                + "\"generationCount\":2},"
                                + "\"videoInputMode\":null,\"mediaInputs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String sameCardKey = "direct-image-two-same-card";
        String sameCardBody = "{\"canvasItemId\":\"" + canvasItemId
                + "\",\"expectedDraftVersion\":"
                + sameCardBatchDraft.path("version").asLong() + "}";
        JsonNode sameCardPrimary = mapper.readTree(mvc.perform(post(runPath).with(auth).with(csrf())
                        .header("Idempotency-Key", sameCardKey).contentType("application/json")
                        .content(sameCardBody)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        JsonNode sameCardReplay = mapper.readTree(mvc.perform(post(runPath).with(auth).with(csrf())
                        .header("Idempotency-Key", sameCardKey).contentType("application/json")
                        .content(sameCardBody)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(sameCardReplay.path("id").asText())
                .isEqualTo(sameCardPrimary.path("id").asText());
        List<UUID> sameCardTaskIds = jdbc.sql("select id from task where project_id=:projectId "
                        + "and input_json->>'sourceCanvasItemId'=:source "
                        + "and input_json->>'generationCount'='2' "
                        + "and input_json->>'prompt'='Two alternatives in one history'")
                .param("projectId", project.id()).param("source", canvasItemId)
                .query(UUID.class).list();
        assertThat(sameCardTaskIds).hasSize(2)
                .contains(UUID.fromString(sameCardPrimary.path("id").asText()));
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:projectId")
                .param("projectId", project.id()).query(Long.class).single())
                .isEqualTo(canvasCountBeforeBatch + 2);
        for (UUID sameCardTaskId : sameCardTaskIds) {
            mvc.perform(post(base + "/tasks/" + sameCardTaskId + "/cancel-queued")
                    .with(auth).with(csrf())).andExpect(status().isOk());
        }

        JsonNode video = mapper.readTree(mvc.perform(post(base + "/artifacts")
                        .with(auth).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "draft-video-create")
                        .content("{\"kind\":\"VIDEO\",\"title\":\"Clip\",\"content\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String videoId = video.path("id").asText();
        String videoItemId = place(mvc, auth, base, videoId);
        String videoDraftPath = base + "/canvas-items/" + videoItemId + "/media-draft";
        mvc.perform(put(videoDraftPath).with(auth).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"prompt\":\"Camera pans left\","
                        + "\"parameters\":{},\"videoInputMode\":\"START_END\","
                        + "\"mediaInputs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(post(base + "/artifacts/" + videoId + "/run")
                .with(auth).with(csrf()).contentType("application/json")
                .header("Idempotency-Key", "draft-video-incomplete")
                .content("{\"canvasItemId\":\"" + videoItemId
                        + "\",\"expectedDraftVersion\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(videoDraftPath).with(auth).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":1,\"prompt\":\"Camera pans left\","
                        + "\"parameters\":{},\"videoInputMode\":\"START_END\","
                        + "\"mediaInputs\":[{\"versionId\":\""
                        + completedCard.path("selectedVersionId").asText()
                        + "\",\"role\":\"START_FRAME\",\"color\":\"#7C3AED\"}],"
                        + "\"mentions\":[],\"durationSeconds\":5}"))
                .andExpect(status().isOk());
        JsonNode videoTask = mapper.readTree(mvc.perform(post(base + "/artifacts/" + videoId
                        + "/run").with(auth).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "draft-video-ready")
                        .content("{\"canvasItemId\":\"" + videoItemId
                                + "\",\"expectedDraftVersion\":2}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(videoTask.path("input").path("mediaInput").path("images").get(0)
                .path("versionId").asText())
                .isEqualTo(completedCard.path("selectedVersionId").asText());
        UUID videoTaskId = UUID.fromString(videoTask.path("id").asText());
        mvc.perform(post(base + "/tasks/" + videoTaskId + "/cancel-queued")
                .with(auth).with(csrf())).andExpect(status().isOk());
        assertThat(jdbc.sql("select status from task where id=:id")
                .param("id", videoTaskId).query(String.class).single()).isEqualTo("CANCELED");

        UUID lastQueued = null;
        for (int index = 0; index < 4; index++) {
            String name = "parallel-card-" + index;
            JsonNode card = mapper.readTree(mvc.perform(post(base + "/artifacts")
                            .with(auth).with(csrf()).contentType("application/json")
                            .header("Idempotency-Key", name)
                            .content("{\"kind\":\"IMAGE\",\"title\":\"Parallel\",\"content\":null}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
            String cardId = card.path("id").asText();
            String itemId = place(mvc, auth, base, cardId);
            mvc.perform(put(base + "/canvas-items/" + itemId + "/media-draft")
                    .with(auth).with(csrf()).contentType("application/json")
                    .content("{\"expectedVersion\":0,\"prompt\":\"Parallel concept\","
                            + "\"parameters\":{},\"videoInputMode\":null,"
                            + "\"mediaInputs\":[],\"mentions\":[]}"))
                    .andExpect(status().isOk());
            JsonNode accepted = mapper.readTree(mvc.perform(post(base + "/artifacts/"
                            + cardId + "/run").with(auth).with(csrf())
                            .header("Idempotency-Key", name + "-run")
                            .contentType("application/json")
                            .content("{\"canvasItemId\":\"" + itemId
                                    + "\",\"expectedDraftVersion\":1}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            lastQueued = UUID.fromString(accepted.path("id").asText());
        }
        JsonNode waiting = mapper.readTree(mvc.perform(get(base + "/tasks/" + lastQueued
                        + "/queue").with(auth)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(waiting.path("reason").asText()).isEqualTo("WAITING_WORKER");
        assertThat(waiting.path("waitingAhead").asInt()).isEqualTo(3);
        for (int index = 0; index < 4; index++) {
            assertThat(taskService.claimBoundMedia("capacity-worker-" + index, 1))
                    .hasSize(1);
        }
        assertThat(taskService.claimBoundMedia("capacity-worker-last", 1)).isEmpty();
        JsonNode snapshot = mapper.readTree(mvc.perform(get(base + "/snapshot")
                        .with(auth)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(snapshot.path("activeRun").isNull()).isTrue();
        assertThat(snapshot.path("activeTasks").size()).isEqualTo(4);

        Project otherProject = projects.create(owner.userId(), "Capability queue",
                Project.AspectRatio.LANDSCAPE_16_9);
        String otherBase = "/api/v1/projects/" + otherProject.id();
        JsonNode otherCard = mapper.readTree(mvc.perform(post(otherBase + "/artifacts")
                        .with(auth).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "capability-limited-card")
                        .content("{\"kind\":\"IMAGE\",\"title\":\"Limited\",\"content\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String otherId = otherCard.path("id").asText();
        String otherItemId = place(mvc, auth, otherBase, otherId);
        mvc.perform(put(otherBase + "/canvas-items/" + otherItemId + "/media-draft")
                .with(auth).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"prompt\":\"A quiet lake\","
                        + "\"parameters\":{},\"videoInputMode\":null,"
                        + "\"mediaInputs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk());
        JsonNode limitedTask = mapper.readTree(mvc.perform(post(otherBase + "/artifacts/"
                        + otherId + "/run").with(auth).with(csrf())
                        .header("Idempotency-Key", "capability-limited-run")
                        .contentType("application/json")
                        .content("{\"canvasItemId\":\"" + otherItemId
                                + "\",\"expectedDraftVersion\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(taskService.claimBoundMedia("capacity-worker-global", 1)).singleElement()
                .extracting(task -> task.id().toString())
                .isEqualTo(limitedTask.path("id").asText());
    }

    private String place(MockMvc mvc,
            org.springframework.test.web.servlet.request.RequestPostProcessor auth,
            String base, String artifactId) throws Exception {
        String itemId = UUID.randomUUID().toString();
        mvc.perform(post(base + "/canvas/commands").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"commands\":[{\"type\":\"PLACE_ARTIFACT\","
                                + "\"itemId\":\"" + itemId + "\",\"artifactId\":\""
                                + artifactId + "\",\"x\":0,\"y\":0,\"width\":280,"
                                + "\"height\":240,\"zIndex\":0,\"locked\":false}]}"))
                .andExpect(status().isOk());
        return itemId;
    }
}
