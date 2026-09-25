package dev.agenvas.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.export.application.ExportProposal;
import dev.agenvas.export.application.ExportProposalService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** End-to-end fake-model proof that export suggestions require authenticated approval. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, ExportProposalPostgresIT.FakeConfig.class},
        properties = {"agenvas.identity.bootstrap-secret=export-proposal-integration-secret",
                "agenvas.llm.scheduler-enabled=false"})
class ExportProposalPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = storageRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AssetService assets;
    @Autowired private ArtifactService artifacts;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private AgentTurnWorker worker;
    @Autowired private ToolExecutionService tools;
    @Autowired private ExportProposalService proposals;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private FakeGateway gateway;
    @Autowired private WebApplicationContext webContext;

    @Test
    void modelProposalDoesNotStartExportAndHumanApprovalIsReplaySafe() throws Exception {
        AdminPrincipal owner = identities.setup("export-proposal-integration-secret",
                "proposal-admin", "proposal-password-123");
        Project project = projects.create(owner.userId(), "Proposal project",
                Project.AspectRatio.LANDSCAPE_16_9);
        Path clip = Files.createTempFile(STORAGE_ROOT, "proposal-clip-", ".mp4");
        UUID assetId;
        try {
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "lavfi", "-i", "color=c=blue:s=640x360:r=24", "-t", "1",
                    "-an", "-c:v", "libx264", "-preset", "veryfast", "-y",
                    clip.toString()));
            try (var stream = Files.newInputStream(clip)) {
                assetId = assets.archiveVideo(owner.userId(), project.id(), stream).id();
            }
        } finally {
            Files.deleteIfExists(clip);
        }
        ObjectNode videoContent = mapper.createObjectNode();
        videoContent.put("assetId", assetId.toString());
        videoContent.put("prompt", "Fixture clip");
        videoContent.put("providerConfigVersion", 1);
        videoContent.put("workflowVersion", "test-video-v1");
        videoContent.putObject("parameters").put("mock", true);
        videoContent.put("sourceTaskId", UUID.randomUUID().toString());
        var video = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO,
                "Clip", videoContent);
        ObjectNode sceneContent = mapper.createObjectNode();
        sceneContent.put("name", "Studio");
        sceneContent.put("location", "Stage");
        sceneContent.put("timeOfDay", "Day");
        sceneContent.put("lighting", "Soft");
        sceneContent.put("style", "Minimal");
        sceneContent.putArray("referenceVersionIds");
        var scene = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SCENE,
                "Studio", sceneContent);
        ObjectNode shotContent = mapper.createObjectNode();
        shotContent.put("order", 1);
        shotContent.put("durationSeconds", 1);
        shotContent.put("description", "Opening");
        shotContent.put("camera", "Wide");
        shotContent.put("action", "Introduce the room");
        shotContent.putArray("characterVersionIds");
        shotContent.put("sceneVersionId", scene.currentVersion().id().toString());
        var shot = artifacts.create(owner.userId(), project.id(), Artifact.Kind.SHOT,
                "Opening", shotContent);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Exporter",
                "Suggest one silent export",
                List.of(new AgentInstanceService.BindingInput(shot.artifact().id(),
                                shot.currentVersion().id()),
                        new AgentInstanceService.BindingInput(video.artifact().id(),
                                video.currentVersion().id())));
        ObjectNode input = proposalInput(shot, video);
        gateway.arguments = input.toString();
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Suggest an ordered export", "proposal-run").run();
        TrustedToolContext context = new TrustedToolContext(owner.userId(), project.id(), run.id());

        assertThat(worker.runOnce("proposal-worker")).isEqualTo(1);
        assertThat(gateway.sawTool).isTrue();
        List<ExportProposal> saved = proposals.list(owner.userId(), project.id());
        assertThat(saved).hasSize(1);
        ExportProposal proposal = saved.getFirst();
        MockMvc mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        String proposalPath = "/api/v1/projects/" + project.id()
                + "/export-proposals/" + proposal.id();
        assertThat(proposal.status()).isEqualTo(ExportProposal.Status.PENDING);
        assertThat(proposal.input().path("segments").get(0).path("shotArtifactId").asText())
                .isEqualTo(shot.artifact().id().toString());
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and kind = 'MEDIA_EXPORT'")
                .param("projectId", project.id()).query(Long.class).single()).isZero();
        var replay = tools.execute(context, 0, "export-1");
        assertThat(replay.path("createdIds").get(0).asText()).isEqualTo(proposal.id().toString());
        assertThat(proposals.list(owner.userId(), project.id())).hasSize(1);

        mvc.perform(get(proposalPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(proposalPath).with(authentication(asUser(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalHash").value(proposal.proposalHash()));
        mvc.perform(get(proposalPath).with(authentication(asUser(
                        new AdminPrincipal(UUID.randomUUID(), "not-owner")))))
                .andExpect(status().isNotFound());
        mvc.perform(post(proposalPath + "/approve").with(authentication(asUser(owner)))
                .contentType("application/json")
                .content("{\"proposalHash\":\"" + proposal.proposalHash() + "\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post(proposalPath + "/approve").with(authentication(asUser(owner)))
                .with(csrf()).contentType("application/json")
                .content("{\"proposalHash\":\"" + "0".repeat(64) + "\"}"))
                .andExpect(status().isConflict());
        String approvedBody = mvc.perform(post(proposalPath + "/approve")
                        .with(authentication(asUser(owner))).with(csrf())
                        .contentType("application/json")
                        .content("{\"proposalHash\":\"" + proposal.proposalHash() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("APPROVED"))
                .andExpect(jsonPath("$.task.kind").value("MEDIA_EXPORT"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        UUID approvedTaskId = UUID.fromString(mapper.readTree(approvedBody)
                .path("task").path("id").asText());
        Task approvedTask = tasks.get(owner.userId(), project.id(), approvedTaskId);
        assertThat(approvedTask.status()).isEqualTo(Task.Status.READY);
        assertThat(approvedTask.input()).isEqualTo(proposal.input());
        ExportProposalService.Approval approvedReplay = proposals.approve(owner.userId(),
                project.id(), proposal.id(), proposal.proposalHash());
        assertThat(approvedReplay.replayed()).isTrue();
        assertThat(approvedReplay.task().id()).isEqualTo(approvedTaskId);
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and kind = 'MEDIA_EXPORT'")
                .param("projectId", project.id()).query(Long.class).single()).isEqualTo(1);

        AgentRun currentRun = runs.get(owner.userId(), project.id(), run.id());
        ExportProposal rejected = proposals.propose(context, currentRun, input);
        mvc.perform(post("/api/v1/projects/" + project.id() + "/export-proposals/"
                        + rejected.id() + "/reject")
                        .with(authentication(asUser(owner))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(proposals.reject(owner.userId(), project.id(), rejected.id()).status())
                .isEqualTo(ExportProposal.Status.REJECTED);
        assertThatThrownBy(() -> proposals.approve(owner.userId(), project.id(),
                rejected.id(), rejected.proposalHash()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("EXPORT_PROPOSAL_CONFLICT"));
        ExportProposal parallel = proposals.propose(context, currentRun, input);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstApproval = executor.submit(() -> proposals.approve(owner.userId(),
                    project.id(), parallel.id(), parallel.proposalHash()));
            var secondApproval = executor.submit(() -> proposals.approve(owner.userId(),
                    project.id(), parallel.id(), parallel.proposalHash()));
            assertThat(firstApproval.get(15, TimeUnit.SECONDS).task().id())
                    .isEqualTo(secondApproval.get(15, TimeUnit.SECONDS).task().id());
        }
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and kind = 'MEDIA_EXPORT'")
                .param("projectId", project.id()).query(Long.class).single()).isEqualTo(2);
        var hiddenVideo = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO,
                "Unbound clip", videoContent);
        assertThatThrownBy(() -> proposals.propose(context, currentRun,
                proposalInput(shot, hiddenVideo)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("INPUT_SCOPE_DENIED"));
        ObjectNode wrongRatio = input.deepCopy();
        wrongRatio.put("aspectRatio", "PORTRAIT_9_16");
        assertThatThrownBy(() -> proposals.propose(context, currentRun, wrongRatio))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
        ObjectNode badRange = input.deepCopy();
        ((ObjectNode) badRange.path("segments").get(0)).put("endMs", 70_000);
        assertThatThrownBy(() -> proposals.propose(context, currentRun, badRange))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
        ExportProposal stale = proposals.propose(context, currentRun, input);
        artifacts.revise(owner.userId(), project.id(), shot.artifact().id(),
                shot.artifact().version(), "Opening revised", shotContent);
        assertThatThrownBy(() -> proposals.approve(owner.userId(), project.id(),
                stale.id(), stale.proposalHash()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("EXPORT_PROPOSAL_CONFLICT"));
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and kind = 'MEDIA_EXPORT'")
                .param("projectId", project.id()).query(Long.class).single()).isEqualTo(2);
    }

    private ObjectNode proposalInput(ArtifactService.ArtifactView shot,
            ArtifactService.ArtifactView video) {
        ObjectNode input = mapper.createObjectNode();
        input.put("aspectRatio", "LANDSCAPE_16_9");
        ObjectNode segment = input.putArray("segments").addObject();
        segment.put("shotArtifactId", shot.artifact().id().toString());
        segment.put("shotVersionId", shot.currentVersion().id().toString());
        segment.put("videoArtifactId", video.artifact().id().toString());
        segment.put("videoVersionId", video.currentVersion().id().toString());
        segment.put("startMs", 0);
        segment.put("endMs", 900);
        return input;
    }

    private UsernamePasswordAuthenticationToken asUser(AdminPrincipal owner) {
        return new UsernamePasswordAuthenticationToken(owner, null, List.of());
    }

    private static Path storageRoot() {
        try {
            return Files.createTempDirectory("agenvas-export-proposal-it-");
        } catch (java.io.IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean @Primary
        FakeGateway fakeGateway() { return new FakeGateway(); }
    }

    /** One fake model turn proposes a real persisted export through the normal tool ledger. */
    static class FakeGateway implements ChatGateway {
        private volatile String arguments;
        private volatile boolean sawTool;

        @Override public String configSource() { return "test-fake"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            sawTool = tools.stream().anyMatch(tool ->
                    "propose_export".equals(tool.getToolDefinition().name()));
            AssistantMessage answer = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("export-1", "function",
                            "propose_export", arguments))).build();
            return new Exchange(1, new ChatResponse(List.of(new Generation(answer))));
        }
    }
}
