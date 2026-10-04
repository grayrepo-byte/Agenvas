package dev.agenvas.canvas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.library.domain.LibraryEntry;
import dev.agenvas.library.domain.LibraryCommand;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.support.ComfyWorkflowFixture;
import dev.agenvas.testing.ImageAssetFixture;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL and synthetic assets/contracts; no provider submission or paid generation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false",
        "agenvas.library.scheduler-enabled=false" })
class WorkflowConnectionsPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> storage.toString());
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired CanvasConnectionService connections;
    @Autowired MediaDraftService drafts;
    @Autowired MediaCapabilityService catalog;
    @Autowired LibraryService library;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;

    @Test
    void workflowConnectionsFillNamedSlotsInOrderAndRollbackRejectedRelationsAndImports() throws Exception {
        AdminPrincipal owner = identities.setup("workflow-connection-admin", "synthetic-password-123");
        RequestPostProcessor auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        MockMvc mvc = webAppContextSetup(context).apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var runningHub = catalog.createConnection("synthetic-runninghub-connection", "Synthetic RunningHub",
                "RUNNINGHUB", "https://www.runninghub.ai", "synthetic-unusable-key");
        ObjectNode settings = mapper.createObjectNode();
        ObjectNode definition = settings.putObject("runningHub");
        definition.put("schemaVersion", 1).put("protocolVersion", "V2")
                .put("targetType", "WORKFLOW").put("targetId", "123");
        var fields = definition.putArray("fields");
        for (int index = 0; index < 2; index++) fields.addObject()
                .put("key", "image" + index).put("label", "Synthetic reference " + index)
                .put("type", "IMAGE").put("required", true).put("nodeId", String.valueOf(index + 1))
                .put("fieldName", "image");
        definition.putArray("outputs").addObject().put("kind", "VIDEO").put("primary", true).put("maxCount", 1);
        var runningHubCapability = catalog.publishCapability(runningHub.id(), "Synthetic video", "RUNNINGHUB_VIDEO", settings);
        verifyConnections(owner, auth, mvc, runningHubCapability.id(), List.of("image0", "image1"));

        var comfy = catalog.createConnection("Synthetic ComfyUI", "http://127.0.0.1:8188");
        ObjectNode comfySettings = ComfyWorkflowFixture.settings(mapper, true, true);
        var workflow = comfySettings.withObject("comfyWorkflow");
        workflow.withObject("graph").putObject("15").put("class_type", "LoadImage")
                .putObject("inputs").put("image", "synthetic-second.png");
        workflow.withObject("graph").withObject("14").withObject("inputs").putArray("secondReference").add("15").add(0);
        workflow.withArray("bindings").addObject().put("nodeId", "15").put("inputName", "image")
                .put("source", "REFERENCE_IMAGE").put("referenceIndex", 1);
        var comfyCapability = catalog.publishCapability(comfy.id(), "Synthetic Comfy video", "COMFY_VIDEO_V1", comfySettings);
        verifyConnections(owner, auth, mvc, comfyCapability.id(), List.of("reference_0", "reference_1"));

        verifyLibrarySlot(owner, auth, mvc, runningHubCapability.id());
        assertThat(jdbc.sql("select count(*) from task").query(Long.class).single()).isZero();
    }

    private void verifyConnections(AdminPrincipal owner, RequestPostProcessor auth, MockMvc mvc,
            UUID capability, List<String> slots) throws Exception {
        Project project = projects.create(owner.userId(), "Synthetic workflow connections", Project.AspectRatio.SQUARE_1_1);
        UUID target = emptyVideo(owner, project, capability);
        Source first = source(owner, project);
        Source second = source(owner, project);
        Source overflow = source(owner, project);
        String base = "/api/v1/projects/" + project.id() + "/canvas/connections";
        JsonNode created = connect(mvc, auth, base, target, first, 1);
        assertThat(created.path("draft").path("videoInputMode").asText()).isEqualTo("GENERAL_REFERENCE");
        assertThat(created.path("draft").path("parameters").path("dynamicValues").path(slots.getFirst()).asText())
                .isEqualTo(first.version().toString());
        assertThat(created.path("draft").path("mediaInputs").get(0).path("sources").get(0).path("type").asText()).isEqualTo("CONNECTION");
        mvc.perform(post(base).with(auth).with(csrf()).contentType("application/json")
                .content(command(target, first, 2))).andExpect(status().isConflict());
        mvc.perform(post(base).with(auth).with(csrf()).contentType("application/json")
                .content(command(target, second, 1))).andExpect(status().isConflict());
        assertThat(connections.list(owner.userId(), project.id())).hasSize(1);
        assertThat(drafts.get(owner.userId(), project.id(), target).version()).isEqualTo(2);
        var filled = connect(mvc, auth, base, target, second, 2);
        assertThat(filled.path("draft").path("parameters").path("dynamicValues").path(slots.get(1)).asText())
                .isEqualTo(second.version().toString());
        mvc.perform(post(base).with(auth).with(csrf()).contentType("application/json")
                .content(command(target, overflow, 3))).andExpect(status().isBadRequest());
        assertThat(connections.list(owner.userId(), project.id())).hasSize(2);
        assertThat(drafts.get(owner.userId(), project.id(), target).version()).isEqualTo(3);
        mvc.perform(post(base + "/" + created.path("connection").path("id").asText() + "/disconnect")
                .with(auth).with(csrf()).contentType("application/json").content("{\"expectedTargetDraftVersion\":3}"))
                .andExpect(status().isOk());
        var disconnected = drafts.get(owner.userId(), project.id(), target);
        assertThat(disconnected.parameters().path("dynamicValues").has(slots.getFirst())).isFalse();
        assertThat(disconnected.parameters().path("dynamicValues").path(slots.get(1)).asText()).isEqualTo(second.version().toString());
        assertThat(disconnected.mediaInputs()).singleElement().satisfies(input -> assertThat(input.versionId()).isEqualTo(second.version()));
    }

    private void verifyLibrarySlot(AdminPrincipal owner, RequestPostProcessor auth, MockMvc mvc,
            UUID capability) throws Exception {
        Project project = projects.create(owner.userId(), "Synthetic library slot", Project.AspectRatio.SQUARE_1_1);
        UUID target = emptyVideo(owner, project, capability);
        Source image = source(owner, project);
        var sourceCard = canvas.list(owner.userId(), project.id()).stream().filter(item -> item.item().id().equals(image.item())).findFirst().orElseThrow().item();
        var saved = library.save(owner.userId(), project.id(), image.item(), image.version(),
                sourceCard.version(), null, "Synthetic saved image", LibraryEntry.Category.OTHER, "workflow-image-save");
        finish(owner, saved.id());
        UUID entry = UUID.fromString(library.command(owner.userId(), saved.id()).result().path("entryId").asText());
        String route = "/api/v1/projects/" + project.id() + "/canvas-items/" + target + "/library-references";
        String body = mapper.writeValueAsString(Map.of("entryId", entry, "expectedVersion", 0,
                "slotKey", "image1", "role", "REFERENCE", "color", "#7C3AED", "commandKey", "workflow-library-reference",
                "draft", Map.of("expectedVersion", 1, "prompt", "Synthetic prompt", "parameters", Map.of(),
                        "capabilityId", capability, "videoInputMode", "TEXT", "mediaInputs", List.of(), "mentions", List.of())));
        JsonNode accepted = mapper.readTree(mvc.perform(post(route).with(auth).with(csrf()).contentType("application/json")
                .content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        finish(owner, UUID.fromString(accepted.path("id").asText()));
        var applied = drafts.get(owner.userId(), project.id(), target);
        assertThat(applied.videoInputMode()).isEqualTo(MediaDraft.VideoInputMode.GENERAL_REFERENCE);
        assertThat(applied.mediaInputs()).hasSize(1);
        assertThat(applied.parameters().path("dynamicValues").path("image1").asText()).isEqualTo(applied.mediaInputs().getFirst().versionId().toString());
        assertThat(applied.parameters().path("dynamicValues").has("image0")).isFalse();
        assertThat(applied.mediaInputs().getFirst().versionId()).isNotEqualTo(image.version());
        assertThat(connections.list(owner.userId(), project.id())).isEmpty();
        mvc.perform(post(route).with(auth).with(csrf()).contentType("application/json")
                .content(body.replace("\"image1\"", "\"image0\""))).andExpect(status().isConflict());

        // A slot replacement removes the old exclusive version, its connection and its mentions.
        var oldInput = applied.mediaInputs().getFirst();
        UUID importedCard = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), oldInput.artifactId());
        var linked = connections.connect(owner.userId(), project.id(), importedCard, target,
                oldInput.versionId(), dev.agenvas.canvas.domain.CanvasConnection.RelationType.MEDIA_INPUT,
                applied.version()).draft();
        var mentioned = drafts.save(owner.userId(), project.id(), target, linked.version(),
                "Synthetic \uFFFC prompt", linked.parameters(), null, capability,
                MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(oldInput.versionId(), oldInput.role(), oldInput.color())),
                List.of(new MediaDraft.PromptMention(oldInput.versionId(), oldInput.role())), null);
        var replacement = libraryReference(mvc, auth, route, entry, "workflow-library-replace", "image1", mentioned);
        finish(owner, replacement);
        var replaced = drafts.get(owner.userId(), project.id(), target);
        assertThat(replaced.mediaInputs()).hasSize(1);
        assertThat(replaced.mediaInputs().getFirst().versionId()).isNotEqualTo(oldInput.versionId());
        assertThat(replaced.mentions()).isEmpty();
        assertThat(replaced.prompt()).isEqualTo("Synthetic  prompt");
        assertThat(connections.list(owner.userId(), project.id())).isEmpty();

        // Sharing one version across slots keeps that version and its line when only one slot changes.
        var sharedInput = replaced.mediaInputs().getFirst();
        var sharedParameters = (ObjectNode) replaced.parameters().deepCopy();
        sharedParameters.withObject("dynamicValues").put("image0", sharedInput.versionId().toString());
        var shared = drafts.save(owner.userId(), project.id(), target, replaced.version(), replaced.prompt(),
                sharedParameters, null, capability, MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(sharedInput.versionId(), sharedInput.role(), sharedInput.color())),
                List.of(), null);
        UUID sharedCard = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), sharedInput.artifactId());
        var sharedLinked = connections.connect(owner.userId(), project.id(), sharedCard, target,
                sharedInput.versionId(), dev.agenvas.canvas.domain.CanvasConnection.RelationType.MEDIA_INPUT,
                shared.version()).draft();
        finish(owner, libraryReference(mvc, auth, route, entry, "workflow-library-shared-replace", "image1", sharedLinked));
        var sharedReplaced = drafts.get(owner.userId(), project.id(), target);
        assertThat(sharedReplaced.mediaInputs()).hasSize(2);
        assertThat(sharedReplaced.parameters().path("dynamicValues").path("image0").asText()).isEqualTo(sharedInput.versionId().toString());
        assertThat(sharedReplaced.parameters().path("dynamicValues").path("image1").asText()).isNotEqualTo(sharedInput.versionId().toString());
        assertThat(connections.list(owner.userId(), project.id())).hasSize(1);

        // CAS failure after acceptance rolls back the imported project artifact and any input cleanup.
        long artifactCount = jdbc.sql("select count(*) from artifact where project_id=:project")
                .param("project", project.id()).query(Long.class).single();
        UUID stale = libraryReference(mvc, auth, route, entry, "workflow-library-stale-replace", "image0", sharedReplaced);
        drafts.save(owner.userId(), project.id(), target, sharedReplaced.version(), "New user edit",
                sharedReplaced.parameters(), null, capability, sharedReplaced.videoInputMode(),
                sharedReplaced.mediaInputs().stream().map(reference -> new MediaDraftService.SaveMediaInput(
                        reference.versionId(), reference.role(), reference.color())).toList(), List.of(), null);
        for (int attempts = 0; attempts < 4 && library.command(owner.userId(), stale).status() != LibraryCommand.Status.FAILED; attempts++) library.processNext();
        assertThat(library.command(owner.userId(), stale).status()).isEqualTo(LibraryCommand.Status.FAILED);
        assertThat(jdbc.sql("select count(*) from artifact where project_id=:project")
                .param("project", project.id()).query(Long.class).single()).isEqualTo(artifactCount);
        assertThat(connections.list(owner.userId(), project.id())).hasSize(1);
        assertThat(drafts.get(owner.userId(), project.id(), target).prompt()).isEqualTo("New user edit");
    }

    private UUID libraryReference(MockMvc mvc, RequestPostProcessor auth, String route, UUID entry,
            String key, String slotKey, MediaDraft draft) throws Exception {
        var request = mapper.createObjectNode().put("entryId", entry.toString()).put("expectedVersion", 0)
                .put("slotKey", slotKey).put("role", "REFERENCE").put("color", "#7C3AED").put("commandKey", key);
        var fields = request.putObject("draft").put("expectedVersion", draft.version()).put("prompt", draft.prompt())
                .put("capabilityId", draft.capabilityId().toString()).put("videoInputMode", draft.videoInputMode().name());
        fields.set("parameters", draft.parameters());
        var inputs = fields.putArray("mediaInputs");
        draft.mediaInputs().forEach(input -> inputs.addObject().put("versionId", input.versionId().toString())
                .put("role", input.role().name()).put("color", input.color()));
        fields.set("mentions", mapper.valueToTree(draft.mentions()));
        return UUID.fromString(mapper.readTree(mvc.perform(post(route).with(auth).with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .path("id").asText());
    }

    private void finish(AdminPrincipal owner, UUID command) {
        for (int attempts = 0; attempts < 4 && library.command(owner.userId(), command).status() != LibraryCommand.Status.SUCCEEDED; attempts++)
            library.processNext();
        assertThat(library.command(owner.userId(), command).status()).isEqualTo(LibraryCommand.Status.SUCCEEDED);
    }

    private UUID emptyVideo(AdminPrincipal owner, Project project, UUID capability) {
        var artifact = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO, "Synthetic video", null);
        UUID target = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifact.artifact().id());
        drafts.save(owner.userId(), project.id(), target, 0, "Synthetic prompt", mapper.createObjectNode(),
                null, capability, MediaDraft.VideoInputMode.TEXT, List.of(), List.of(), null);
        return target;
    }

    private Source source(AdminPrincipal owner, Project project) {
        UUID asset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Synthetic reference",
                mapper.createObjectNode().put("assetId", asset.toString()).put("sourceType", "UPLOAD"));
        return new Source(CanvasMediaFixture.place(canvas, owner.userId(), project.id(), image.artifact().id()),
                image.resourceDefaultVersion().id());
    }

    private JsonNode connect(MockMvc mvc, RequestPostProcessor auth, String route, UUID target,
            Source source, long version) throws Exception {
        return mapper.readTree(mvc.perform(post(route).with(auth).with(csrf()).contentType("application/json")
                .content(command(target, source, version))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String command(UUID target, Source source, long expected) {
        return mapper.writeValueAsString(Map.of("sourceCanvasItemId", source.item(), "targetCanvasItemId", target,
                "sourceVersionId", source.version(), "relationType", "MEDIA_INPUT", "expectedTargetDraftVersion", expected));
    }

    private record Source(UUID item, UUID version) {}
}
