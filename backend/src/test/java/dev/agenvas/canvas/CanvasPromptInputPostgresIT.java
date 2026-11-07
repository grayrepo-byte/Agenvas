package dev.agenvas.canvas;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.*;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.*;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.identity.application.*;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.artifact.domain.MediaDraft;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

/** Real PostgreSQL and Mock media acceptance; no paid provider requests. */
@Testcontainers @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class CanvasPromptInputPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agenvas.storage.root", () -> storage.toString());
        r.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired IdentityService identities; @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts; @Autowired CanvasService canvas;
    @Autowired CanvasConnectionService connections; @Autowired MediaDraftService drafts;
    @Autowired DirectMediaTaskService direct; @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context; @Autowired JdbcClient jdbc;
    @Autowired MediaCapabilityService capabilities; @Autowired MediaExecutionWorker worker;
    @Autowired MediaDraftRestoreService restores;
    static AdminPrincipal owner; Project project; MockMvc mvc;
    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("synthetic-prompts", "synthetic-password-123");
        project = projects.create(owner.userId(), "Public prompts", Project.AspectRatio.LANDSCAPE_16_9);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }
    @ParameterizedTest @EnumSource(value = Artifact.Kind.class, names = {"IMAGE", "VIDEO", "AUDIO"})
    void connectsSavedTextViaHttpAndRestoresTopology(Artifact.Kind kind) throws Exception {
        var text = text("Common", "Shared lighting"); UUID source = place(text.artifact().id());
        var media = artifacts.create(owner.userId(), project.id(), kind, "Target", null); UUID target = place(media.artifact().id());
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String path = "/api/v1/projects/" + project.id() + "/canvas/connections";
        String body = mapper.createObjectNode().put("sourceCanvasItemId", source.toString()).put("targetCanvasItemId", target.toString())
                .put("sourceVersionId", text.artifact().resourceDefaultVersionId().toString()).put("relationType", "PROMPT_INPUT").toString();
        mvc.perform(post(path).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        var result = mapper.readTree(mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(result.path("draft").isNull()).isTrue();
        var restored = mapper.readTree(mvc.perform(get(path).with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(restored.at("/items/0/relationType").asText()).isEqualTo("PROMPT_INPUT");
        assertThat(drafts.get(owner.userId(), project.id(), target).prompt()).isEmpty();
        assertThat(drafts.get(owner.userId(), project.id(), target).mediaInputs()).isEmpty();
        var saved = drafts.save(owner.userId(), project.id(), target, 0, "", mapper.createObjectNode(),
                kind == Artifact.Kind.VIDEO ? 5 : null, null,
                kind == Artifact.Kind.VIDEO ? MediaDraft.VideoInputMode.TEXT : null, List.of(), List.of(), null);
        assertThat(direct.preflight(owner.userId(), project.id(), media.artifact().id(), target, saved.version()).safeSummary().path("prompt").asText())
                .isEqualTo("Shared lighting");
    }
    @Test void freezesOrderedSavedSourcesAndPreservesLocalPromptAcrossEditDisconnectAndReplay() {
        var first = text("Style", "  Shared style  "); UUID source = place(first.artifact().id());
        var second = text("Lighting", "Soft lighting"); UUID source2 = place(second.artifact().id());
        var blank = text("Empty", "  "); UUID source3 = place(blank.artifact().id());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Scene", null); UUID target = place(image.artifact().id());
        var saved = CanvasMediaFixture.save(drafts, owner.userId(), project.id(), target, 0, "Local subject", null, null, null);
        var a = connect(source, target, first.artifact().resourceDefaultVersionId());
        var b = connect(source2, target, second.artifact().resourceDefaultVersionId());
        connect(source3, target, blank.artifact().resourceDefaultVersionId());
        jdbc.sql("update canvas_connection set created_at='2026-01-01T00:00:00Z' where project_id=:p").param("p", project.id()).update();
        String expected = a.connection().id().toString().compareTo(b.connection().id().toString()) < 0
                ? "Shared style\n\nSoft lighting\n\nLocal subject" : "Soft lighting\n\nShared style\n\nLocal subject";
        var before = direct.preflight(owner.userId(), project.id(), image.artifact().id(), target, saved.version());
        var task = direct.run(owner.userId(), project.id(), image.artifact().id(), target, saved.version(), "synthetic-frozen");
        assertThat(task.input().path("prompt").asText()).isEqualTo(expected);
        assertThat(task.input().at("/mediaInput/prompt").asText()).isEqualTo("Local subject");
        assertThat(task.input().at("/mediaInput/promptInputs").size()).isEqualTo(3);
        var changed = artifacts.revise(owner.userId(), project.id(), first.artifact().id(), first.artifact().version(), "Style", content("New style"));
        connections.disconnect(owner.userId(), project.id(), b.connection().id(), b.connection().version(), null);
        var replay = direct.run(owner.userId(), project.id(), image.artifact().id(), target, saved.version(), "synthetic-frozen");
        assertThat(replay.id()).isEqualTo(task.id()); assertThat(replay.input().path("prompt").asText()).isEqualTo(expected);
        assertThat(drafts.get(owner.userId(), project.id(), target).prompt()).isEqualTo("Local subject");
        direct.cancelQueued(owner.userId(), project.id(), task.id());
        var after = direct.preflight(owner.userId(), project.id(), image.artifact().id(), target, saved.version());
        assertThat(after.frozenInputHash()).isNotEqualTo(before.frozenInputHash());
        var next = direct.run(owner.userId(), project.id(), image.artifact().id(), target, saved.version(), "synthetic-next");
        assertThat(next.input().path("prompt").asText()).isEqualTo("New style\n\nLocal subject");
        assertThat(next.input().path("mediaInput").path("promptInputs").toString())
                .contains(changed.artifact().resourceDefaultVersionId().toString());
        direct.cancelQueued(owner.userId(), project.id(), next.id());
    }
    @Test void allowsUpstreamOnlyAndRejectsDuplicateWrongTypesCrossProjectAndExcessLength() {
        var text = text("Common", "Only upstream"); UUID source = place(text.artifact().id());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Scene", null); UUID target = place(image.artifact().id());
        connect(source, target, text.artifact().resourceDefaultVersionId());
        assertThat(direct.preflight(owner.userId(), project.id(), image.artifact().id(), target, 0).safeSummary().path("prompt").asText()).isEqualTo("Only upstream");
        assertThatThrownBy(() -> connect(source, target, text.artifact().resourceDefaultVersionId())).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> connect(target, source, text.artifact().resourceDefaultVersionId())).isInstanceOf(ApiProblemException.class);
        var other = projects.create(owner.userId(), "Other", Project.AspectRatio.LANDSCAPE_16_9);
        var otherImage = artifacts.create(owner.userId(), other.id(), Artifact.Kind.IMAGE, "Elsewhere", null);
        UUID otherTarget = CanvasMediaFixture.place(canvas, owner.userId(), other.id(), otherImage.artifact().id());
        assertThatThrownBy(() -> connect(source, otherTarget, text.artifact().resourceDefaultVersionId())).isInstanceOf(ApiProblemException.class);
        artifacts.revise(owner.userId(), project.id(), text.artifact().id(), text.artifact().version(), "Common", content("x".repeat(20000)));
        var extra = text("More", "y");
        connect(place(extra.artifact().id()), target, extra.artifact().resourceDefaultVersionId());
        assertThatThrownBy(() -> direct.preflight(owner.userId(), project.id(), image.artifact().id(), target, 0)).isInstanceOf(ApiProblemException.class);
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.Remove(source, 0)));
        assertThat(direct.preflight(owner.userId(), project.id(), image.artifact().id(), target, 0).safeSummary().path("prompt").asText()).isEqualTo("y");
        assertThat(drafts.get(owner.userId(), project.id(), target).mediaInputs()).isEmpty();
    }
    @Test void usesWorkflowDefaultAfterSharedTextAndEnforcesPublishedLimitsAndPromptAvailability() {
        var text = text("Common", "Shared"); UUID source = place(text.artifact().id());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Workflow", null); UUID target = place(image.artifact().id());
        connect(source, target, text.artifact().resourceDefaultVersionId());
        var connection = capabilities.createConnection("synthetic-prompt-workflow", "Synthetic RunningHub", "RUNNINGHUB", "https://api.runninghub.cn", "synthetic-unused-key");
        var settings = mapper.createObjectNode();
        var definition = settings.putObject("runningHub").put("schemaVersion", 1).put("protocolVersion", "V2").put("targetType", "WORKFLOW").put("targetId", "123");
        definition.putArray("outputs").addObject().put("kind", "IMAGE").put("primary", true).put("maxCount", 1);
        var field = definition.putArray("fields").addObject().put("key", "prompt").put("label", "Prompt").put("nodeId", "1").put("fieldName", "text")
                .put("type", "STRING").put("source", "PROMPT").put("required", true).put("defaultValue", "Default scene").put("maxLength", 100);
        var detail = ((tools.jackson.databind.node.ArrayNode) definition.path("fields")).addObject().put("key", "detail").put("label", "Detail")
                .put("nodeId", "2").put("fieldName", "text").put("type", "STRING").put("required", true);
        detail.putObject("enabledWhen").put("field", "prompt").put("value", "Shared\n\nDefault scene");
        var capability = capabilities.publishCapability(connection.id(), "Prompt workflow", "RUNNINGHUB_IMAGE", settings);
        var parameters = mapper.createObjectNode(); parameters.putObject("dynamicValues").put("detail", "Conditional detail");
        var saved = drafts.save(owner.userId(), project.id(), target, 0, "", parameters, null, capability.id(), null, List.of(), List.of(), null);
        var task = direct.run(owner.userId(), project.id(), image.artifact().id(), target, saved.version(), "synthetic-default");
        assertThat(task.input().path("prompt").asText()).isEqualTo("Shared\n\nDefault scene");
        assertThat(task.input().at("/mediaInput/parameters/dynamicValues/prompt").asText()).isEqualTo("Shared\n\nDefault scene");
        assertThat(task.input().at("/mediaInput/userRenderedPrompt").asText()).isEqualTo("Shared\n\nDefault scene");
        assertThat(task.input().at("/mediaInput/parameters/dynamicValues/detail").asText()).isEqualTo("Conditional detail");
        direct.cancelQueued(owner.userId(), project.id(), task.id());
        field.put("maxLength", 18);
        detail.remove("enabledWhen");
        var limited = capabilities.updateCapability(connection.id(), capability.id(), capability.version(), capability.name(), true, "RUNNINGHUB_IMAGE", settings);
        assertThatThrownBy(() -> direct.preflight(owner.userId(), project.id(), image.artifact().id(), target, saved.version())).isInstanceOf(ApiProblemException.class);
        definition.putArray("fields").add(detail);
        capabilities.updateCapability(connection.id(), capability.id(), limited.version(), capability.name(), true, "RUNNINGHUB_IMAGE", settings);
        assertThatThrownBy(() -> direct.preflight(owner.userId(), project.id(), image.artifact().id(), target, saved.version()))
                .isInstanceOf(ApiProblemException.class).satisfies(e -> assertThat(((ApiProblemException)e).code()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test void mockExecutionArchivesFrozenSourcesAndExplicitHistoryRestoreMaterializesThem() {
        var text = text("Common", "Frozen public"); UUID source = place(text.artifact().id());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Image", null); UUID target = place(image.artifact().id());
        connect(source, target, text.artifact().resourceDefaultVersionId());
        var saved = CanvasMediaFixture.save(drafts, owner.userId(), project.id(), target, 0, "Local", null, null, null);
        var task = direct.run(owner.userId(), project.id(), image.artifact().id(), target, saved.version(), "synthetic-render");
        assertThat(worker.submitOnce("synthetic-public-prompts")).isEqualTo(1);
        var result = canvas.listMediaVersions(owner.userId(), project.id(), target).getFirst();
        assertThat(result.content().path("prompt").asText()).isEqualTo("Frozen public\n\nLocal");
        assertThat(result.frozenInput().path("promptInputs").size()).isEqualTo(1);
        artifacts.revise(owner.userId(), project.id(), text.artifact().id(), text.artifact().version(), "Common", content("Changed public"));
        var restored = restores.restore(owner.userId(), project.id(), target, result.id(), drafts.get(owner.userId(), project.id(), target).version());
        assertThat(restored.prompt()).isEqualTo("Frozen public\n\nLocal");
        assertThat(connections.list(owner.userId(), project.id())).noneMatch(edge -> edge.targetCanvasItemId().equals(target));
        assertThat(task.input().path("prompt").asText()).isEqualTo("Frozen public\n\nLocal");
    }

    private ArtifactService.ArtifactView text(String title, String body) { return artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, title, content(body)); }
    private JsonNode content(String body) { return mapper.createObjectNode().put("text", body).put("format", "PLAIN_TEXT"); }
    private UUID place(UUID id) { return CanvasMediaFixture.place(canvas, owner.userId(), project.id(), id); }
    private CanvasConnectionService.ConnectionResult connect(UUID source, UUID target, UUID version) {
        return connections.connect(owner.userId(), project.id(), source, target, version, CanvasConnection.RelationType.valueOf("PROMPT_INPUT"), null);
    }
}
