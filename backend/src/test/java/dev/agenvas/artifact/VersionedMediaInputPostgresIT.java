package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Ordered exact-version media inputs are saved and frozen through the public API seam. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class VersionedMediaInputPostgresIT {
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
    @Autowired private MediaExecutionWorker worker;
    @Autowired private JdbcClient jdbc;

    @Test
    void freezesOrderedStartAndEndFramesWithoutLegacySingularFields() throws Exception {
        AdminPrincipal owner = identities.setup("input-admin", "input-password-123");
        Project project = projects.create(owner.userId(), "Versioned media inputs",
                Project.AspectRatio.LANDSCAPE_16_9);
        MockMvc mvc = webAppContextSetup(context).apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity()).build();
        RequestPostProcessor auth = authentication(
                new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String base = "/api/v1/projects/" + project.id();

        GeneratedImage start = generatedImage(mvc, auth, base, "Start", "warm sunrise", 0);
        GeneratedImage end = generatedImage(mvc, auth, base, "End", "night skyline", 320);

        JsonNode video = createArtifact(mvc, auth, base, "VIDEO", "Transition");
        String videoItemId = place(mvc, auth, base, video.path("id").asText(), 640);
        String draftPath = base + "/canvas-items/" + videoItemId + "/media-draft";
        String input = """
                {"expectedVersion":0,"prompt":"Move from \uFFFC to \uFFFC",\
                "durationSeconds":5,"videoInputMode":"START_END","mediaInputs":[\
                {"versionId":"%s","role":"START_FRAME","color":"#7C3AED"},\
                {"versionId":"%s","role":"END_FRAME","color":"#0EA5E9"}],\
                "mentions":[\
                {"versionId":"%s","role":"START_FRAME"},\
                {"versionId":"%s","role":"END_FRAME"}]}
                """.formatted(start.versionId(), end.versionId(),
                        start.versionId(), end.versionId());
        String staleVersionId = UUID.randomUUID().toString();
        mvc.perform(put(draftPath).with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"prompt\":\"Atomic batch\","
                                + "\"mediaInputs\":[{\"versionId\":\"" + start.versionId()
                                + "\",\"role\":\"START_FRAME\",\"color\":\"#7C3AED\"},"
                                + "{\"versionId\":\"" + staleVersionId
                                + "\",\"role\":\"END_FRAME\",\"color\":\"#0EA5E9\"}]}"))
                .andExpect(status().isBadRequest());
        JsonNode unchangedAfterRejectedBatch = mapper.readTree(mvc.perform(get(draftPath)
                        .with(auth)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertThat(unchangedAfterRejectedBatch.path("version").asLong()).isZero();
        assertThat(unchangedAfterRejectedBatch.path("mediaInputs")).isEmpty();
        mvc.perform(put(draftPath).with(auth).with(csrf())
                        .contentType("application/json")
                        .content(input.replace("Move from \uFFFC to \uFFFC",
                                "Move from @Start Frame to @End Frame")))
                .andExpect(status().isBadRequest());
        JsonNode saved = mapper.readTree(mvc.perform(put(draftPath).with(auth).with(csrf())
                        .contentType("application/json").content(input))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(saved.path("videoInputMode").asText()).isEqualTo("START_END");
        assertThat(saved.path("mediaInputs")).hasSize(2);
        assertThat(saved.path("mediaInputs").get(0).path("versionId").asText())
                .isEqualTo(start.versionId());
        assertThat(saved.path("mediaInputs").get(1).path("role").asText())
                .isEqualTo("END_FRAME");
        assertThat(saved.path("mediaInputs").get(0).path("sources").get(0).path("type").asText())
                .isEqualTo("MANUAL");

        JsonNode connected = mapper.readTree(mvc.perform(post(base + "/canvas/connections")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"sourceCanvasItemId\":\"" + start.canvasItemId()
                                + "\",\"targetCanvasItemId\":\"" + videoItemId
                                + "\",\"sourceVersionId\":\"" + start.versionId()
                                + "\",\"relationType\":\"MEDIA_INPUT\","
                                + "\"expectedTargetDraftVersion\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String connectionId = connected.path("connection").path("id").asText();
        assertThat(connected.path("draft").path("mediaInputs").get(0).path("sources"))
                .hasSize(2);
        assertThat(connected.path("draft").path("mediaInputs").get(0).path("sources").get(1)
                .path("type").asText()).isEqualTo("CONNECTION");
        JsonNode connectionSnapshot = mapper.readTree(mvc.perform(get(base + "/snapshot")
                        .with(auth)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertThat(connectionSnapshot.path("connections")).hasSize(1);
        assertThat(connectionSnapshot.path("connections").get(0).path("id").asText())
                .isEqualTo(connectionId);

        JsonNode disconnected = mapper.readTree(mvc.perform(post(base + "/canvas/connections/"
                        + connectionId + "/disconnect").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedTargetDraftVersion\":2}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(disconnected.path("draft").path("mediaInputs")).hasSize(2);
        assertThat(disconnected.path("draft").path("mediaInputs").get(0).path("sources")).hasSize(1);
        assertThat(disconnected.path("draft").path("mediaInputs").get(0).path("sources").get(0)
                .path("type").asText()).isEqualTo("MANUAL");

        String secondStartItemId = duplicateMediaItem(mvc, auth, base, start, 480);
        JsonNode removalTarget = createArtifact(mvc, auth, base, "IMAGE", "Removal target");
        String removalTargetItemId = place(mvc, auth, base,
                removalTarget.path("id").asText(), 560);
        String removalDraftPath = base + "/canvas-items/" + removalTargetItemId
                + "/media-draft";
        mvc.perform(put(removalDraftPath).with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"prompt\":\"Reference ￼\","
                                + "\"mediaInputs\":[{\"versionId\":\"" + start.versionId()
                                + "\",\"role\":\"REFERENCE\",\"color\":\"#7C3AED\"}],"
                                + "\"mentions\":[{\"versionId\":\"" + start.versionId()
                                + "\",\"role\":\"REFERENCE\"}] }"))
                .andExpect(status().isOk());
        mvc.perform(post(base + "/canvas/connections").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"sourceCanvasItemId\":\"" + start.canvasItemId()
                                + "\",\"targetCanvasItemId\":\"" + removalTargetItemId
                                + "\",\"sourceVersionId\":\"" + start.versionId()
                                + "\",\"relationType\":\"MEDIA_INPUT\","
                                + "\"expectedTargetDraftVersion\":1}"))
                .andExpect(status().isCreated());
        mvc.perform(post(base + "/canvas/connections").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"sourceCanvasItemId\":\"" + secondStartItemId
                                + "\",\"targetCanvasItemId\":\"" + removalTargetItemId
                                + "\",\"sourceVersionId\":\"" + start.versionId()
                                + "\",\"relationType\":\"MEDIA_INPUT\","
                                + "\"expectedTargetDraftVersion\":2}"))
                .andExpect(status().isCreated());
        String removeInputPath = removalDraftPath + "/media-inputs/" + start.versionId()
                + "/remove";
        mvc.perform(post(removeInputPath).with(auth).with(csrf())
                        .contentType("application/json").content("{\"expectedVersion\":2}"))
                .andExpect(status().isConflict());
        JsonNode removedInput = mapper.readTree(mvc.perform(post(removeInputPath)
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":3}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(removedInput.path("mediaInputs")).isEmpty();
        assertThat(removedInput.path("mentions")).isEmpty();
        assertThat(removedInput.path("prompt").asText()).isEqualTo("Reference ");
        JsonNode connectionsAfterInputRemoval = mapper.readTree(mvc.perform(
                        get(base + "/canvas/connections").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(connectionsAfterInputRemoval.path("items")).isEmpty();
        assertThat(jdbc.sql("select count(*) from project_event where project_id=:projectId "
                        + "and type='media.draft.changed' and aggregate_id=:canvasItemId "
                        + "and aggregate_version=4 and payload_json->>'draftVersion'='4' "
                        + "and payload_json->>'removedConnectionCount'='2'")
                .param("projectId", project.id())
                .param("canvasItemId", UUID.fromString(removalTargetItemId))
                .query(Long.class).single()).isEqualTo(1L);

        String duplicateItemId = UUID.randomUUID().toString();
        JsonNode duplicated = mapper.readTree(mvc.perform(post(base + "/canvas/items/"
                        + videoItemId + "/duplicate").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"targetItemId\":\"" + duplicateItemId
                                + "\",\"expectedSourceVersion\":0,"
                                + "\"expectedSourceDraftVersion\":3,\"x\":960,\"y\":0,"
                                + "\"width\":280,\"height\":240,\"zIndex\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(duplicated.path("item").path("id").asText()).isEqualTo(duplicateItemId);
        assertThat(duplicated.path("draft").path("mediaInputs")).hasSize(2);
        assertThat(duplicated.path("draft").path("mediaInputs").get(0).path("color").asText())
                .isEqualTo("#7C3AED");
        assertThat(duplicated.path("draft").path("mediaInputs").get(0).path("sources"))
                .hasSize(1);
        assertThat(duplicated.path("draft").path("mediaInputs").get(0).path("sources").get(0)
                .path("type").asText()).isEqualTo("MANUAL");
        JsonNode duplicateConnections = mapper.readTree(mvc.perform(
                        get(base + "/canvas/connections").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(duplicateConnections.path("items")).isEmpty();

        JsonNode cleanupTarget = createArtifact(mvc, auth, base, "IMAGE", "Cleanup target");
        String cleanupTargetItemId = place(mvc, auth, base,
                cleanupTarget.path("id").asText(), 1280);
        JsonNode cleanupConnection = mapper.readTree(mvc.perform(
                        post(base + "/canvas/connections").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"sourceCanvasItemId\":\"" + start.canvasItemId()
                                + "\",\"targetCanvasItemId\":\"" + cleanupTargetItemId
                                + "\",\"sourceVersionId\":\"" + start.versionId()
                                + "\",\"relationType\":\"MEDIA_INPUT\"," 
                                + "\"expectedTargetDraftVersion\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
        JsonNode autosavedConnectionInput = mapper.readTree(mvc.perform(put(base
                        + "/canvas-items/" + cleanupTargetItemId + "/media-draft")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":1,\"prompt\":\"\",\"mediaInputs\":["
                                + "{\"versionId\":\"" + start.versionId()
                                + "\",\"role\":\"REFERENCE\",\"color\":\""
                                + cleanupConnection.path("draft").path("mediaInputs").get(0)
                                        .path("color").asText()
                                + "\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(autosavedConnectionInput.path("mediaInputs").get(0).path("sources"))
                .hasSize(1);
        assertThat(autosavedConnectionInput.path("mediaInputs").get(0).path("sources").get(0)
                .path("type").asText()).isEqualTo("CONNECTION");
        mvc.perform(post(base + "/canvas/commands").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"commands\":[{\"type\":\"REMOVE\",\"itemId\":\""
                                + start.canvasItemId() + "\",\"expectedVersion\":"
                                + start.canvasItemVersion() + "}]}"))
                .andExpect(status().isOk());
        JsonNode cleanedDraft = mapper.readTree(mvc.perform(get(base + "/canvas-items/"
                        + cleanupTargetItemId + "/media-draft").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cleanedDraft.path("mediaInputs")).isEmpty();

        JsonNode task = mapper.readTree(mvc.perform(post(base + "/artifacts/"
                        + video.path("id").asText() + "/run").with(auth).with(csrf())
                        .header("Idempotency-Key", "versioned-video-run")
                        .contentType("application/json")
                        .content("{\"canvasItemId\":\"" + videoItemId
                                + "\",\"expectedDraftVersion\":3}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode frozen = task.path("input").path("mediaInput");
        assertThat(task.path("input").path("prompt").asText())
                .isEqualTo("Move from @Start Frame to @End Frame");
        assertThat(frozen.path("prompt").asText()).isEqualTo("Move from \uFFFC to \uFFFC");
        assertThat(frozen.path("renderedPrompt").asText())
                .isEqualTo("Move from @Start Frame to @End Frame");
        assertThat(frozen.path("mode").asText()).isEqualTo("START_END");
        assertThat(frozen.path("images")).hasSize(2);
        assertThat(frozen.path("images").get(0).path("versionId").asText())
                .isEqualTo(start.versionId());
        assertThat(frozen.path("images").get(1).path("versionId").asText())
                .isEqualTo(end.versionId());
        assertThat(task.path("input").has("imageVersionId")).isFalse();
        assertThat(task.path("input").has("referenceImageVersionId")).isFalse();

        assertThat(worker.submitOnce("versioned-video-worker")).isEqualTo(1);
        JsonNode versions = mapper.readTree(mvc.perform(get(base + "/artifacts/"
                        + video.path("id").asText() + "/versions").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode result = versions.path("items").get(0);
        assertThat(result.path("baseVersionId").isNull()).isTrue();
        assertThat(result.path("frozenInput")).isEqualTo(frozen);
        assertThat(result.path("inputReferences")).hasSize(2);
        assertThat(result.path("inputReferences").get(0).path("role").asText())
                .isEqualTo("START_FRAME");
        assertThat(result.path("inputReferences").get(1).path("role").asText())
                .isEqualTo("END_FRAME");
        assertThat(result.path("content").has("keyframeVersionId")).isFalse();

        mvc.perform(post(base + "/canvas/connections").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"sourceCanvasItemId\":\"" + end.canvasItemId()
                                + "\",\"targetCanvasItemId\":\"" + videoItemId
                                + "\",\"sourceVersionId\":\"" + end.versionId()
                                + "\",\"relationType\":\"MEDIA_INPUT\"," 
                                + "\"expectedTargetDraftVersion\":3}"))
                .andExpect(status().isCreated());
        JsonNode restored = mapper.readTree(mvc.perform(post(draftPath
                        + "/restore-version-inputs").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"versionId\":\"" + result.path("id").asText()
                                + "\",\"expectedVersion\":4}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(restored.path("prompt").asText())
                .isEqualTo("Move from \uFFFC to \uFFFC");
        assertThat(restored.path("mediaInputs")).hasSize(2);
        assertThat(restored.path("mediaInputs").get(0).path("sources")).hasSize(1);
        assertThat(restored.path("mediaInputs").get(0).path("sources").get(0)
                .path("type").asText()).isEqualTo("MANUAL");
        JsonNode restoredConnections = mapper.readTree(mvc.perform(
                        get(base + "/canvas/connections").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(restoredConnections.path("items")).isEmpty();

        mvc.perform(post(base + "/agents").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"Invalid image agent\","
                                + "\"instruction\":\"Read images\",\"bindings\":[{"
                                + "\"artifactId\":\"" + end.artifactId()
                                + "\",\"selectedVersionId\":\"" + end.versionId() + "\"}]}"))
                .andExpect(status().isBadRequest());
        JsonNode agent = mapper.readTree(mvc.perform(post(base + "/agents")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"Visual agent\",\"instruction\":\"Read images\"," 
                                + "\"bindings\":[]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String agentItemId = placeAgent(mvc, auth, base, agent.path("id").asText(), 1600);
        String secondEndItemId = duplicateMediaItem(mvc, auth, base, end, 1920);
        JsonNode firstAgentLink = mapper.readTree(mvc.perform(post(base + "/canvas/connections")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"sourceCanvasItemId\":\"" + end.canvasItemId()
                                + "\",\"targetCanvasItemId\":\"" + agentItemId
                                + "\",\"sourceVersionId\":\"" + end.versionId()
                                + "\",\"relationType\":\"AGENT_IMAGE_INPUT\"," 
                                + "\"expectedTargetAgentVersion\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(firstAgentLink.path("draft").isNull()).isTrue();
        assertThat(firstAgentLink.path("agent").path("bindings")).hasSize(1);
        var secondAgentResult = mvc.perform(post(base + "/canvas/connections")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"sourceCanvasItemId\":\"" + secondEndItemId
                                + "\",\"targetCanvasItemId\":\"" + agentItemId
                                + "\",\"sourceVersionId\":\"" + end.versionId()
                                + "\",\"relationType\":\"AGENT_IMAGE_INPUT\"," 
                                + "\"expectedTargetAgentVersion\":1}"))
                .andReturn();
        assertThat(secondAgentResult.getResponse().getStatus())
                .as(secondAgentResult.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode secondAgentLink = mapper.readTree(
                secondAgentResult.getResponse().getContentAsString());
        assertThat(secondAgentLink.path("agent").path("bindings")).hasSize(1);
        JsonNode firstAgentDisconnect = mapper.readTree(mvc.perform(post(base
                        + "/canvas/connections/" + firstAgentLink.path("connection").path("id").asText()
                        + "/disconnect").with(auth).with(csrf()).contentType("application/json")
                        .content("{\"expectedTargetAgentVersion\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(firstAgentDisconnect.path("agent").path("bindings")).hasSize(1);
        JsonNode finalAgentDisconnect = mapper.readTree(mvc.perform(post(base
                        + "/canvas/connections/" + secondAgentLink.path("connection").path("id").asText()
                        + "/disconnect").with(auth).with(csrf()).contentType("application/json")
                        .content("{\"expectedTargetAgentVersion\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(finalAgentDisconnect.path("agent").path("bindings")).isEmpty();
    }

    private GeneratedImage generatedImage(MockMvc mvc, RequestPostProcessor auth,
            String base, String title, String prompt, int x) throws Exception {
        JsonNode artifact = createArtifact(mvc, auth, base, "IMAGE", title);
        String artifactId = artifact.path("id").asText();
        String itemId = place(mvc, auth, base, artifactId, x);
        mvc.perform(put(base + "/canvas-items/" + itemId + "/media-draft")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"prompt\":\"" + prompt + "\"}"))
                .andExpect(status().isOk());
        JsonNode task = mapper.readTree(mvc.perform(post(base + "/artifacts/" + artifactId + "/run")
                        .with(auth).with(csrf()).header("Idempotency-Key", "run-" + itemId)
                        .contentType("application/json")
                        .content("{\"canvasItemId\":\"" + itemId
                                + "\",\"expectedDraftVersion\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String resultItemId = task.path("input").path("canvasItemId").asText();
        assertThat(resultItemId).isEqualTo(itemId);
        assertThat(worker.submitOnce("input-worker-" + itemId)).isEqualTo(1);
        JsonNode canvas = mapper.readTree(mvc.perform(get(base + "/canvas/items").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode card = java.util.stream.StreamSupport.stream(
                        canvas.path("items").spliterator(), false)
                .filter(item -> resultItemId.equals(item.path("id").asText()))
                .findFirst().orElseThrow();
        return new GeneratedImage(artifactId, resultItemId,
                card.path("selectedVersionId").asText(),
                card.path("version").asLong());
    }

    private String duplicateMediaItem(MockMvc mvc, RequestPostProcessor auth, String base,
            GeneratedImage source, int x) throws Exception {
        String targetItemId = UUID.randomUUID().toString();
        mvc.perform(post(base + "/canvas/items/" + source.canvasItemId() + "/duplicate")
                        .with(auth).with(csrf()).contentType("application/json")
                        .content("{\"targetItemId\":\"" + targetItemId
                                + "\",\"expectedSourceVersion\":"
                                + source.canvasItemVersion()
                                + ",\"expectedSourceDraftVersion\":1,\"x\":" + x
                                + ",\"y\":0,\"width\":280,\"height\":240,\"zIndex\":1}"))
                .andExpect(status().isCreated());
        return targetItemId;
    }

    private JsonNode createArtifact(MockMvc mvc, RequestPostProcessor auth,
            String base, String kind, String title) throws Exception {
        return mapper.readTree(mvc.perform(post(base + "/artifacts").with(auth).with(csrf())
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"kind\":\"" + kind + "\",\"title\":\"" + title
                                + "\",\"content\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String place(MockMvc mvc, RequestPostProcessor auth,
            String base, String artifactId, int x) throws Exception {
        String itemId = UUID.randomUUID().toString();
        mvc.perform(post(base + "/canvas/commands").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"commands\":[{\"type\":\"PLACE_ARTIFACT\","
                                + "\"itemId\":\"" + itemId + "\",\"artifactId\":\""
                                + artifactId + "\",\"x\":" + x + ",\"y\":0,\"width\":280,"
                                + "\"height\":240,\"zIndex\":0,\"locked\":false}]}"))
                .andExpect(status().isOk());
        return itemId;
    }

    private String placeAgent(MockMvc mvc, RequestPostProcessor auth,
            String base, String agentId, int x) throws Exception {
        String itemId = UUID.randomUUID().toString();
        mvc.perform(post(base + "/canvas/commands").with(auth).with(csrf())
                        .contentType("application/json")
                        .content("{\"commands\":[{\"type\":\"PLACE_AGENT\"," 
                                + "\"itemId\":\"" + itemId + "\",\"agentId\":\""
                                + agentId + "\",\"x\":" + x + ",\"y\":0,\"width\":460,"
                                + "\"height\":600,\"zIndex\":0,\"locked\":false}]}"))
                .andExpect(status().isOk());
        return itemId;
    }

    private record GeneratedImage(String artifactId, String canvasItemId, String versionId,
            long canvasItemVersion) {}
}
