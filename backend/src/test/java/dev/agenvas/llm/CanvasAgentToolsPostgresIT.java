package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.llm.application.*;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL, synthetic content, no model or Provider call: canvas-wide scope and safe commands. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class CanvasAgentToolsPostgresIT {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path storage;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("agenvas.storage.root", () -> storage.toString());
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired dev.agenvas.asset.application.AssetService assets;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired CanvasService canvas;
    @Autowired dev.agenvas.canvas.application.CanvasConnectionService connections;
    @Autowired MediaDraftService drafts;
    @Autowired CanvasAgentToolService tools;
    @Autowired CreativeArtifactToolService creative;
    @Autowired ReadToolService reader;
    @Autowired InitialModelContextService initial;
    @Autowired AgentTurnCommitService commits;
    @Autowired TaskService tasks;
    @Autowired LlmTurnCheckpointService checkpoints;
    @Autowired LlmProtocolCodec codec;
    @Autowired ToolRegistry registry;
    @Autowired ToolExecutionService executor;
    @Autowired ToolExecutionRepository ledger;
    @Autowired ObjectMapper mapper;

    @Test void pagedCanvasScopeCommandsAndHistoricalScopeRemainIsolated() {
        var owner = identities.setup("canvas-assistant-admin", "canvas-assistant-password-123");
        var project = projects.create(owner.userId(), "Canvas assistant", Project.AspectRatio.SQUARE_1_1);
        var text = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, "Canvas text",
                text("CANVAS_BODY_MUST_NOT_BE_SENT_INITIALLY"));
        var hidden = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, "Off canvas", text("Hidden"));
        var agent = agents.create(owner.userId(), project.id(), "Director", "Help on the canvas");
        List<CanvasService.CanvasCommand> placements = new ArrayList<>();
        List<UUID> textItems = new ArrayList<>();
        for (int index = 0; index < 43; index++) {
            UUID itemId = UUID.randomUUID(); textItems.add(itemId);
            placements.add(place(itemId, text.artifact().id(), index * 400L));
        }
        List<UUID> mediaItems = new ArrayList<>();
        for (Artifact.Kind kind : List.of(Artifact.Kind.IMAGE, Artifact.Kind.VIDEO, Artifact.Kind.AUDIO)) {
            var media = artifacts.create(owner.userId(), project.id(), kind, kind.name(), null);
            UUID itemId = UUID.randomUUID(); mediaItems.add(itemId);
            placements.add(place(itemId, media.artifact().id(), 18000L + mediaItems.size() * 400L));
        }
        UUID assetId = dev.agenvas.testing.ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var reference = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Reference",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", assetId.toString()));
        UUID referenceItemId = UUID.randomUUID();
        placements.add(place(referenceItemId, reference.artifact().id(), 22000));
        canvas.apply(owner.userId(), project.id(), placements);
        AgentRun queued = runs.create(owner.userId(), project.id(), agent.id(), "Edit this canvas", "canvas-scope-run", null, textItems).run();
        var trusted = new TrustedToolContext(owner.userId(), project.id(), queued.id());
        assertThat(queued.contextSnapshot().at("/canvasScope/items").size()).isEqualTo(47);
        assertThat(queued.contextSnapshot().path("bindings")).isEmpty();
        assertThat(initial.assemble(owner.userId(), project.id(), queued.id()).toString())
                .contains("47 nodes", "read_canvas").doesNotContain("CANVAS_BODY_MUST_NOT_BE_SENT_INITIALLY");
        var first = tools.readCanvas(trusted, queued, UUID.randomUUID(), "{\"limit\":40}");
        var last = tools.readCanvas(trusted, queued, UUID.randomUUID(), "{\"offset\":40,\"limit\":40}");
        assertThat(first.path("data").size()).isEqualTo(40);
        assertThat(first.path("nextOffset").asInt()).isEqualTo(40);
        assertThat(last.path("data").size()).isEqualTo(7);
        assertThat(last.path("nextOffset").isNull()).isTrue();
        for (var node : first.path("data")) if (node.path("mediaDraft").isObject())
            assertThat(node.path("mediaDraft").has("prompt")).isFalse();
        int mediaOffset = 0;
        for (var node : queued.contextSnapshot().at("/canvasScope/items")) {
            if (node.path("mediaDraft").isObject()) break;
            mediaOffset++;
        }
        var fullDraft = tools.readCanvas(trusted, queued, UUID.randomUUID(), mapper.createObjectNode()
                .put("offset", mediaOffset).put("limit", 1).put("includeDrafts", true).toString());
        assertThat(fullDraft.at("/data/0/mediaDraft").has("prompt")).isTrue();
        var selection = reader.selection(queued, UUID.randomUUID(), "{\"offset\":40,\"limit\":40}");
        assertThat(selection.path("total").asInt()).isEqualTo(43);
        assertThat(selection.path("data").size()).isEqualTo(3);
        assertThat(reader.artifacts(trusted, queued, UUID.randomUUID(), versions(text)).at("/data/0/content/text").asText())
                .isEqualTo("CANVAS_BODY_MUST_NOT_BE_SENT_INITIALLY");
        assertCode(() -> reader.artifacts(trusted, queued, UUID.randomUUID(), versions(hidden)), "INPUT_SCOPE_DENIED");

        ObjectNode oldSnapshot = (ObjectNode) queued.contextSnapshot().deepCopy(); oldSnapshot.remove("canvasScope");
        AgentRun historical = new AgentRun(queued.id(), queued.projectId(), queued.agentInstanceId(), queued.conversationId(),
                queued.conversationTurn(), queued.userId(), queued.status(), queued.instruction(), oldSnapshot,
                queued.policySnapshot(), queued.profileVersion(), queued.nextStepIndex(), queued.version(), queued.createdAt(), queued.updatedAt(), queued.completedAt());
        assertCode(() -> reader.artifacts(trusted, historical, UUID.randomUUID(), versions(text)), "INPUT_SCOPE_DENIED");
        assertCode(() -> tools.readCanvas(trusted, historical, UUID.randomUUID(), "{}"), "INPUT_SCOPE_DENIED");

        UUID itemId = textItems.getFirst();
        var rename = command("RENAME", itemId, 0).put("title", "Renamed");
        assertThat(tools.manageNode(trusted, queued, UUID.randomUUID(), rename.toString()).at("/data/title").asText()).isEqualTo("Renamed");
        assertCode(() -> tools.manageNode(trusted, queued, UUID.randomUUID(), rename.toString()), "CANVAS_VERSION_CONFLICT");
        tools.manageNode(trusted, queued, UUID.randomUUID(), command("MOVE", itemId, 1).put("x", 50).put("y", 75).toString());
        tools.manageNode(trusted, queued, UUID.randomUUID(), command("RESIZE", itemId, 2).put("width", 400).put("height", 240).toString());
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.SetLocked(itemId, 3, true)));
        assertCode(() -> tools.manageNode(trusted, queued, UUID.randomUUID(), command("REMOVE", itemId, 4).toString()), "CANVAS_ITEM_LOCKED");
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.SetLocked(itemId, 4, false)));

        for (UUID mediaId : mediaItems) {
            var update = mapper.createObjectNode().put("itemId", mediaId.toString()).put("expectedVersion", 0).put("prompt", "Edited working draft");
            var saved = tools.updateMediaDraft(trusted, queued, UUID.randomUUID(), update.toString());
            assertThat(saved.at("/data/prompt").asText()).isEqualTo("Edited working draft");
            assertThat(saved.at("/data/version").asLong()).isEqualTo(1);
            assertCode(() -> tools.updateMediaDraft(trusted, queued, UUID.randomUUID(), update.toString()), "VERSION_CONFLICT");
        }
        UUID mentionedVideo = mediaItems.get(1);
        UUID referenceVersion = reference.resourceDefaultVersion().id();
        drafts.save(owner.userId(), project.id(), mentionedVideo, 1, "Use \uFFFC", mapper.createObjectNode(), null, null,
                dev.agenvas.artifact.domain.MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(referenceVersion, dev.agenvas.artifact.domain.MediaDraft.InputRole.REFERENCE, "#7C3AED")),
                List.of(new dev.agenvas.artifact.domain.MediaDraft.PromptMention(referenceVersion, dev.agenvas.artifact.domain.MediaDraft.InputRole.REFERENCE)), null);
        var plainPrompt = mapper.createObjectNode().put("itemId", mentionedVideo.toString()).put("expectedVersion", 2).put("prompt", "Plain replacement");
        assertThat(tools.updateMediaDraft(trusted, queued, UUID.randomUUID(), plainPrompt.toString()).at("/data/mentions")).isEmpty();
        var structured = plainPrompt.deepCopy().put("expectedVersion", 3).put("prompt", "Use \uFFFC again");
        structured.putArray("mentions").addObject().put("versionId", referenceVersion.toString()).put("role", "REFERENCE");
        assertThat(tools.updateMediaDraft(trusted, queued, UUID.randomUUID(), structured.toString()).at("/data/mentions").size()).isEqualTo(1);
        var connected = connections.connect(owner.userId(), project.id(), referenceItemId, mentionedVideo, referenceVersion,
                dev.agenvas.canvas.domain.CanvasConnection.RelationType.MEDIA_INPUT, 4L);
        var clearInputs = mapper.createObjectNode().put("itemId", mentionedVideo.toString()).put("expectedVersion", connected.draft().version());
        clearInputs.putArray("mediaInputs");
        var cleared = tools.updateMediaDraft(trusted, queued, UUID.randomUUID(), clearInputs.toString());
        assertThat(cleared.at("/data/mediaInputs")).isEmpty();
        assertThat(cleared.at("/data/mentions")).isEmpty();
        assertThat(cleared.at("/data/prompt").asText()).doesNotContain("\uFFFC");
        assertThat(connections.list(owner.userId(), project.id())).noneMatch(connection -> connection.targetCanvasItemId().equals(mentionedVideo));
        var arrangement = mapper.createObjectNode().put("layout", "HORIZONTAL");
        for (UUID mediaId : mediaItems) arrangement.withArray("items").addObject().put("itemId", mediaId.toString()).put("expectedVersion", 0);
        creative.arrangeItems(trusted, queued, UUID.randomUUID(), arrangement.toString());

        var revision = mapper.createObjectNode().put("artifactId", text.artifact().id().toString()).put("expectedVersion", 0);
        revision.set("content", text("Agent revision"));
        creative.reviseArtifact(trusted, queued, UUID.randomUUID(), revision.toString());
        var current = artifacts.get(owner.userId(), project.id(), text.artifact().id());
        artifacts.revise(owner.userId(), project.id(), text.artifact().id(), current.artifact().version(), null, text("Concurrent human revision"));
        revision.put("expectedVersion", 2);
        assertCode(() -> creative.reviseArtifact(trusted, queued, UUID.randomUUID(), revision.toString()), "INPUT_SCOPE_DENIED");
        var latestText = artifacts.get(owner.userId(), project.id(), text.artifact().id());
        var refreshed = tools.readCanvas(trusted, queued, UUID.randomUUID(), "{\"limit\":40}");
        var refreshedTail = tools.readCanvas(trusted, queued, UUID.randomUUID(), "{\"offset\":40,\"limit\":40}");
        UUID refreshedItemId = textItems.get(1);
        var refreshedNode = java.util.stream.Stream.concat(java.util.stream.StreamSupport.stream(refreshed.path("data").spliterator(), false),
                java.util.stream.StreamSupport.stream(refreshedTail.path("data").spliterator(), false))
                .filter(node -> refreshedItemId.toString().equals(node.path("itemId").asText())).findFirst().orElseThrow();
        assertThat(refreshedNode.path("selectedVersionId").asText()).isEqualTo(latestText.resourceDefaultVersion().id().toString());
        assertThat(refreshedNode.path("selectedVersionReadable").asBoolean()).isFalse();
        assertThat(refreshedNode.path("frozenSelectedVersionId").asText()).isEqualTo(text.resourceDefaultVersion().id().toString());
        assertCode(() -> reader.artifacts(trusted, queued, UUID.randomUUID(), versions(latestText)), "INPUT_SCOPE_DENIED");
        var freshLayout = mapper.createObjectNode().put("layout", "VERTICAL");
        freshLayout.putArray("items").addObject().put("itemId", refreshedItemId.toString())
                .put("versionId", latestText.resourceDefaultVersion().id().toString()).put("expectedVersion", refreshedNode.path("itemVersion").asLong());
        creative.arrangeItems(trusted, queued, UUID.randomUUID(), freshLayout.toString());
        assertThat(artifacts.get(owner.userId(), project.id(), text.artifact().id()).resourceDefaultVersion().id())
                .isEqualTo(latestText.resourceDefaultVersion().id());


        // Record a real committed tool call: a retried copy returns the original node, and that node is a Run output.
        String workerId = "canvas-assistant-test";
        var lease = tasks.claimAgentTurns(workerId, 1).getFirst();
        AgentRun running = commits.start(lease, workerId);
        var copy = command("DUPLICATE", mediaItems.getFirst(), 1).put("expectedDraftVersion", 1);
        var definitions = registry.modelDefinitions(running.policySnapshot());
        int configVersion = running.policySnapshot().path("modelConfigVersion").asInt();
        checkpoints.reserveLeased(owner.userId(), project.id(), queued.id(), 0, configVersion,
                running.policySnapshot().path("modelConfigSource").asText(), codec.request(List.of(new UserMessage("Copy")), definitions), lease, workerId);
        tasks.startAgentStream(lease, workerId);
        var response = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("copy-node", "function", "manage_canvas_node", copy.toString()),
                new AssistantMessage.ToolCall("create-text-node", "function", "create_text", mapper.createObjectNode()
                        .put("title", "New Run text").put("format", "PLAIN_TEXT").put("text", "Synthetic new output").toString()))).build();
        checkpoints.saveResponseLeased(owner.userId(), project.id(), queued.id(), 0, configVersion,
                codec.response(new ChatResponse(List.of(new Generation(response)))), lease, workerId);
        var copied = executor.execute(trusted, 0, "copy-node");
        assertThat(executor.execute(trusted, 0, "copy-node")).isEqualTo(copied);
        UUID copiedId = UUID.fromString(copied.at("/createdIds/0").asText());
        var arrangeCopy = mapper.createObjectNode().put("layout", "VERTICAL");
        arrangeCopy.putArray("items").addObject().put("itemId", copiedId.toString()).put("expectedVersion", 0);
        creative.arrangeItems(trusted, running, UUID.randomUUID(), arrangeCopy.toString());
        tools.manageNode(trusted, running, UUID.randomUUID(), command("MOVE", copiedId, 1).put("x", 25).put("y", 25).toString());
        tools.manageNode(trusted, running, UUID.randomUUID(), command("REMOVE", copiedId, 2).toString());
        var createdText = executor.execute(trusted, 0, "create-text-node");
        assertThat(executor.execute(trusted, 0, "create-text-node")).isEqualTo(createdText);
        UUID textNode = UUID.fromString(createdText.at("/data/0/itemId").asText());
        assertThat(createdText.path("createdCanvasItemIds")).singleElement()
                .satisfies(id -> assertThat(id.asText()).isEqualTo(textNode.toString()));
        assertThat(ledger.hasCreatedCanvasItem(project.id(), running.id(), textNode)).isTrue();
        var outputDirectory = tools.readCanvas(trusted, running, UUID.randomUUID(), "{\"offset\":47,\"limit\":40}");
        assertThat(outputDirectory.path("total").asInt()).isEqualTo(48);
        assertThat(outputDirectory.path("data")).singleElement().satisfies(node -> {
            assertThat(node.path("itemId").asText()).isEqualTo(textNode.toString());
            assertThat(node.path("selectedVersionId").asText()).isEqualTo(createdText.at("/data/0/versionId").asText());
        });
        var arrangeText = mapper.createObjectNode().put("layout", "VERTICAL");
        arrangeText.putArray("items").addObject().put("itemId", textNode.toString())
                .put("versionId", createdText.at("/data/0/versionId").asText()).put("expectedVersion", 0);
        creative.arrangeItems(trusted, running, UUID.randomUUID(), arrangeText.toString());
        tools.manageNode(trusted, running, UUID.randomUUID(), command("MOVE", textNode, 1).put("x", 50).put("y", 50).toString());
        assertCode(() -> creative.arrangeItems(trusted, running, UUID.randomUUID(), arrangeText.toString()), "CANVAS_VERSION_CONFLICT");
        assertThat(canvas.list(owner.userId(), project.id()).stream().filter(entry -> entry.item().id().equals(textNode)))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.item().version()).isEqualTo(2);
                    assertThat(entry.item().groupId()).isNull();
                });

        // Generic createdIds also lists artifact IDs; only typed node result fields confer node authority.
        UUID evidenceId = UUID.randomUUID();
        ledger.insertExecuting(evidenceId, project.id(), running.id(), 0, "typed-node-proof",
                "propose_media_generation", "a".repeat(64), java.time.Instant.now());
        var evidence = mapper.createObjectNode().put("status", "SUCCEEDED");
        evidence.putArray("createdIds").add(hidden.artifact().id().toString()).add(mediaItems.getFirst().toString());
        evidence.putObject("data").putArray("outputs").addObject().put("canvasItemId", mediaItems.getFirst().toString());
        ledger.complete(evidenceId, evidence, java.time.Instant.now());
        assertThat(ledger.hasCreatedCanvasItem(project.id(), running.id(), mediaItems.getFirst())).isTrue();
        assertThat(ledger.hasCreatedCanvasItem(project.id(), running.id(), hidden.artifact().id())).isFalse();
        canvas.apply(owner.userId(), project.id(), List.of(place(hidden.artifact().id(), hidden.artifact().id(), 30000)));
        assertCode(() -> tools.manageNode(trusted, running, UUID.randomUUID(), command("REMOVE", hidden.artifact().id(), 0).toString()), "INPUT_SCOPE_DENIED");
        assertThat(tools.readCanvas(trusted, running, UUID.randomUUID(), "{\"offset\":47,\"limit\":40}")
                .path("total").asInt()).isEqualTo(48);

        assertThat(artifacts.get(owner.userId(), project.id(), mediaArtifact(owner.userId(), project.id(), mediaItems.getFirst()))).isNotNull();

        var other = projects.create(owner.userId(), "Other project", Project.AspectRatio.SQUARE_1_1);
        var foreign = artifacts.create(owner.userId(), other.id(), Artifact.Kind.TEXT, "Foreign", text("Foreign"));
        assertCode(() -> reader.artifacts(trusted, queued, UUID.randomUUID(), versions(foreign)), "RESOURCE_NOT_FOUND");
    }
    private UUID mediaArtifact(UUID ownerId, UUID projectId, UUID itemId) {
        return canvas.list(ownerId, projectId).stream().filter(entry -> entry.item().id().equals(itemId)).findFirst().orElseThrow().item().subjectId();
    }
    private CanvasService.PlaceArtifact place(UUID itemId, UUID artifactId, long x) {
        return new CanvasService.PlaceArtifact(itemId, artifactId, BigDecimal.valueOf(x), BigDecimal.ZERO,
                BigDecimal.valueOf(320), BigDecimal.valueOf(200), 0, null, false);
    }
    private ObjectNode command(String action, UUID itemId, long version) {
        return mapper.createObjectNode().put("action", action).put("itemId", itemId.toString()).put("expectedVersion", version);
    }
    private ObjectNode text(String value) { return mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", value); }
    private String versions(ArtifactService.ArtifactView view) {
        var input = mapper.createObjectNode(); input.putArray("versionIds").add(view.resourceDefaultVersion().id().toString()); return input.toString();
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
