package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentConversationService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.ImageAssetFixture;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Same-conversation output inheritance preserves exact versions, owner scope and manual-edit CAS. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=conversation-artifact-secret", "agenvas.llm.mode=mock",
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class ConversationArtifactInputsPostgresIT {
    private static final int INPUT_LIMIT = 40;
    private static final int FOUR_IMAGE_REFERENCES = 4;
    private static final BigDecimal AGENT_WIDTH = new BigDecimal("460");
    private static final BigDecimal AGENT_HEIGHT = new BigDecimal("600");
    private static final Path STORAGE = privateStorage();
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.storage.root", () -> STORAGE.toString());
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ArtifactService artifacts;
    @Autowired private MediaDraftService drafts;
    @Autowired private ObjectMapper mapper;
    @Autowired private CanvasService canvas;
    @Autowired private AssetService assets;
    @Autowired private ProjectEventService events;
    @Autowired private JdbcClient jdbc;
    @Autowired private AgentConversationService conversations;
    @Autowired private CanvasConnectionService connections;
    @Autowired private MediaToolRunner mediaTools;

    private AdminPrincipal owner() {
        return jdbc.sql("select id from app_user where login_name = 'conversation-artifact-admin'")
                .query(UUID.class).optional()
                .map(id -> new AdminPrincipal(id, "conversation-artifact-admin"))
                .orElseGet(() -> identities.setup("conversation-artifact-secret", "conversation-artifact-admin",
                        "conversation-artifact-password"));
    }

    @Test
    void inheritsSelectedConversationOutputsButNeverForeignOrManuallyReplacedVersions() throws Exception {
        var owner = owner();
        var project = projects.create(owner.userId(), "Conversation inputs", Project.AspectRatio.SQUARE_1_1);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create text", List.of());
        var first = runs.create(owner.userId(), project.id(), agent.id(), "Write a scene", "first").run();
        var editable = artifacts.createFromAgent(owner.userId(), project.id(), first.id(), Artifact.Kind.TEXT,
                "Scene", mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"First draft\"}"));
        var editedByUser = artifacts.createFromAgent(owner.userId(), project.id(), first.id(), Artifact.Kind.TEXT,
                "Another scene", mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Old draft\"}"));
        var manual = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, "Private manual input",
                mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Do not inherit\"}"));
        var foreignProject = projects.create(owner.userId(), "Foreign", Project.AspectRatio.SQUARE_1_1);
        var foreignAgent = agents.create(owner.userId(), foreignProject.id(), "Foreign", "Create text", List.of());
        var foreignRun = runs.create(owner.userId(), foreignProject.id(), foreignAgent.id(), "Write", "foreign").run();
        artifacts.createFromAgent(owner.userId(), foreignProject.id(), foreignRun.id(), Artifact.Kind.TEXT,
                "Foreign", mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Foreign scope\"}"));

        var inputs = artifacts.conversationInputs(owner.userId(), project.id(), List.of(first.id(), foreignRun.id()), List.of(), Set.of());
        assertThat(inputs).extracting(ArtifactService.ConversationInput::artifactId)
                .containsExactlyInAnyOrder(editable.artifact().id(), editedByUser.artifact().id())
                .doesNotContain(manual.artifact().id());
        assertThat(inputs).allSatisfy(input -> assertThat(input.expectedVersion()).isZero());
        assertThat(artifacts.conversationInputs(owner.userId(), project.id(), List.of(), List.of(), Set.of())).isEmpty();
        assertThatThrownBy(() -> artifacts.conversationInputs(UUID.randomUUID(), project.id(), List.of(first.id()), List.of(), Set.of()))
                .isInstanceOf(ApiProblemException.class);

        runs.cancel(owner.userId(), project.id(), first.id());
        var second = runs.create(owner.userId(), project.id(), agent.id(), "Continue that scene", "second").run();
        assertThat(artifacts.requireAgentVisibleVersion(owner.userId(), project.id(), second.id(),
                editable.resourceDefaultVersion().id(), second.contextSnapshot()).id()).isEqualTo(editable.resourceDefaultVersion().id());
        var revised = artifacts.reviseFromAgent(owner.userId(), project.id(), second.id(), second.contextSnapshot(),
                editable.artifact().id(), editable.artifact().version(), "Scene continued",
                mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Continued draft\"}"));
        assertThat(revised.resourceDefaultVersion().runId()).isEqualTo(second.id());

        var userRevision = artifacts.revise(owner.userId(), project.id(), editedByUser.artifact().id(),
                editedByUser.artifact().version(), "User changed scene",
                mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"User decision\"}"));
        assertThat(artifacts.conversationInputs(owner.userId(), project.id(), List.of(first.id(), second.id()), List.of(), Set.of()))
                .extracting(ArtifactService.ConversationInput::artifactId).containsExactly(editable.artifact().id());
        assertThatThrownBy(() -> artifacts.requireAgentVisibleVersion(owner.userId(), project.id(), second.id(),
                userRevision.resourceDefaultVersion().id(), second.contextSnapshot())).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> artifacts.reviseFromAgent(owner.userId(), project.id(), second.id(), second.contextSnapshot(),
                editedByUser.artifact().id(), userRevision.artifact().version(), "Unsafe overwrite",
                mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Do not overwrite\"}")))
                .isInstanceOf(ApiProblemException.class);
        assertThat(artifacts.get(owner.userId(), project.id(), editedByUser.artifact().id()).resourceDefaultVersion().id())
                .isEqualTo(userRevision.resourceDefaultVersion().id());
    }

    @Test
    void nextRunCanReadAllSelectedImageOutputsWithoutSettingResourceDefaults() {
        var owner = owner();
        var project = projects.create(owner.userId(), "Selected media continuation", Project.AspectRatio.SQUARE_1_1);
        var agent = agents.create(owner.userId(), project.id(), "Director", "Continue with generated references", List.of());
        var first = runs.create(owner.userId(), project.id(), agent.id(), "Create four references", "images-first").run();
        List<UUID> versions = java.util.stream.IntStream.rangeClosed(1, FOUR_IMAGE_REFERENCES)
                .mapToObj(index -> selectedImage(owner.userId(), project.id(), agent.id(), first.id(), "Reference " + index))
                .toList();
        runs.cancel(owner.userId(), project.id(), first.id());

        var second = runs.create(owner.userId(), project.id(), agent.id(), "Continue", "images-second").run();

        assertThat(second.contextSnapshot().path("bindings")).hasSize(FOUR_IMAGE_REFERENCES);
        for (UUID versionId : versions) {
            var visible = artifacts.requireAgentVisibleVersion(owner.userId(), project.id(), second.id(),
                    versionId, second.contextSnapshot());
            assertThat(visible.id()).isEqualTo(versionId);
            assertThat(artifacts.get(owner.userId(), project.id(), visible.artifactId()).resourceDefaultVersion()).isNull();
        }
        assertThat(second.contextSnapshot().path("bindings")).allSatisfy(binding -> {
            assertThat(binding.path("source").asText()).isEqualTo("CONVERSATION_OUTPUT");
            assertThat(binding.has("expectedVersion")).isFalse();
        });
    }

    @ParameterizedTest
    @EnumSource(value = Artifact.Kind.class, names = {"IMAGE", "VIDEO", "AUDIO"})
    void freezesDistinctNodeVersionsAndIgnoresIndependentMediaResourceDefaults(Artifact.Kind kind) throws IOException {
        var owner = owner();
        var project = projects.create(owner.userId(), "Distinct " + kind + " outputs", Project.AspectRatio.SQUARE_1_1);
        var agent = agents.create(owner.userId(), project.id(), "Director", "Read selected results", List.of());
        var first = runs.create(owner.userId(), project.id(), agent.id(), "Create media", "media-first").run();
        var artifact = artifacts.create(owner.userId(), project.id(), kind, "Shared media identity", null).artifact();
        UUID assetId = syntheticAsset(owner.userId(), project.id(), kind);
        var older = selectedOutput(owner.userId(), project.id(), agent.id(), first.id(), artifact, assetId);
        var newer = selectedOutput(owner.userId(), project.id(), agent.id(), first.id(), artifact, assetId);
        var olderCard = card(owner.userId(), project.id(), older.itemId()).item();
        canvas.duplicate(owner.userId(), project.id(), older.itemId(), UUID.randomUUID(), olderCard.version(),
                drafts.get(owner.userId(), project.id(), older.itemId()).version(), BigDecimal.ZERO,
                BigDecimal.ZERO, olderCard.width(), olderCard.height(), olderCard.zIndex());
        UUID libraryOnlyVersion = appendGeneratedVersion(owner.userId(), project.id(), first.id(), artifact, assetId);
        artifacts.setResourceDefaultVersion(owner.userId(), project.id(), artifact.id(), libraryOnlyVersion, artifact.version());
        runs.cancel(owner.userId(), project.id(), first.id());

        assertThat(runs.preflight(owner.userId(), project.id(), agent.id()).bindings())
                .extracting(AgentRunService.PreflightBinding::selectedVersionId)
                .containsExactlyInAnyOrder(older.versionId(), newer.versionId());
        var second = runs.create(owner.userId(), project.id(), agent.id(), "Continue", "media-second").run();
        assertThat(second.contextSnapshot().path("bindings")).hasSize(2);
        for (var binding : second.contextSnapshot().path("bindings")) {
            assertThat(binding.path("artifactId").asText()).isEqualTo(artifact.id().toString());
            assertThat(binding.has("expectedVersion")).isFalse();
        }
        for (UUID versionId : List.of(older.versionId(), newer.versionId())) {
            assertThat(artifacts.requireAgentVisibleVersion(owner.userId(), project.id(), second.id(), versionId,
                    second.contextSnapshot()).id()).isEqualTo(versionId);
        }
        assertThatThrownBy(() -> artifacts.requireAgentVisibleVersion(owner.userId(), project.id(), second.id(),
                libraryOnlyVersion, second.contextSnapshot())).isInstanceOf(ApiProblemException.class);
        assertThat(artifacts.get(owner.userId(), project.id(), artifact.id()).resourceDefaultVersion().id())
                .isEqualTo(libraryOnlyVersion);

        // Later library choices cannot widen or rewrite the already frozen Run.
        var latest = artifacts.get(owner.userId(), project.id(), artifact.id()).artifact();
        artifacts.setResourceDefaultVersion(owner.userId(), project.id(), artifact.id(), newer.versionId(), latest.version());
        assertThat(artifacts.requireAgentVisibleVersion(owner.userId(), project.id(), second.id(), older.versionId(),
                second.contextSnapshot()).id()).isEqualTo(older.versionId());
    }

    @Test
    void excludesManualSelectionsOtherAgentsOtherConversationsAndOtherProjects() {
        var owner = owner();
        var project = projects.create(owner.userId(), "Private continuation scope", Project.AspectRatio.SQUARE_1_1);
        var agent = agents.create(owner.userId(), project.id(), "Director", "Continue only this conversation", List.of());
        var first = runs.create(owner.userId(), project.id(), agent.id(), "Create image", "scope-first").run();
        var artifact = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Replaced by user", null).artifact();
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var original = selectedOutput(owner.userId(), project.id(), agent.id(), first.id(), artifact, assetId);
        UUID manualVersion = events.recordChange(owner.userId(), project.id(), () -> {
            var uploaded = artifacts.appendUserMediaVersionWithinChange(owner.userId(), project.id(), artifact.id(),
                    mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", assetId.toString()), original.versionId(), null);
            canvas.recordTaskMediaVersionWithinChange(owner.userId(), project.id(), original.itemId(), artifact.id(), uploaded.id());
            assertThat(canvas.selectTaskResultWithinChange(owner.userId(), project.id(), original.itemId(), artifact.id(),
                    original.versionId(), uploaded.id(), null, null)).isTrue();
            return ProjectEventService.Change.unchanged(uploaded.id());
        }).value();
        runs.cancel(owner.userId(), project.id(), first.id());

        var otherAgent = agents.create(owner.userId(), project.id(), "Other Agent", "Separate inputs", List.of());
        var otherRun = runs.create(owner.userId(), project.id(), otherAgent.id(), "Create image", "other-agent").run();
        UUID otherAgentVersion = selectedImage(owner.userId(), project.id(), otherAgent.id(), otherRun.id(), "Other Agent image");
        runs.cancel(owner.userId(), project.id(), otherRun.id());
        var newConversation = conversations.create(owner.userId(), project.id(), agent.id(), "new-conversation");
        var newRun = runs.create(owner.userId(), project.id(), agent.id(), "Fresh request", "fresh-conversation").run();
        assertThat(newRun.conversationId()).isEqualTo(newConversation.id());
        assertThat(newRun.contextSnapshot().path("bindings")).isEmpty();
        UUID otherConversationVersion = selectedImage(owner.userId(), project.id(), agent.id(), newRun.id(), "Other conversation image");
        runs.cancel(owner.userId(), project.id(), newRun.id());

        var foreignProject = projects.create(owner.userId(), "Separate project", Project.AspectRatio.SQUARE_1_1);
        var foreignAgent = agents.create(owner.userId(), foreignProject.id(), "Foreign", "Separate scope", List.of());
        var foreignRun = runs.create(owner.userId(), foreignProject.id(), foreignAgent.id(), "Create image", "foreign-project").run();
        UUID foreignVersion = selectedImage(owner.userId(), foreignProject.id(), foreignAgent.id(), foreignRun.id(), "Foreign image");
        runs.cancel(owner.userId(), foreignProject.id(), foreignRun.id());

        var resumed = runs.create(owner.userId(), project.id(), agent.id(), "Continue original conversation", "scope-resume",
                null, List.of(), null, null, null, first.conversationId(), null).run();
        assertThat(resumed.contextSnapshot().path("bindings")).isEmpty();
        for (UUID denied : List.of(original.versionId(), manualVersion, otherAgentVersion, otherConversationVersion)) {
            assertThatThrownBy(() -> artifacts.requireAgentVisibleVersion(owner.userId(), project.id(), resumed.id(),
                    denied, resumed.contextSnapshot())).isInstanceOfSatisfying(ApiProblemException.class,
                            problem -> assertThat(problem.code()).isEqualTo("INPUT_SCOPE_DENIED"));
        }
        assertThatThrownBy(() -> artifacts.requireAgentVisibleVersion(owner.userId(), project.id(), resumed.id(),
                foreignVersion, resumed.contextSnapshot())).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> canvas.selectedMediaVersionIds(UUID.randomUUID(), project.id()))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void deduplicatesAliasesAndExcludesExplicitArtifactsBeforeTheInputLimit() {
        var owner = owner();
        var project = projects.create(owner.userId(), "Bounded continuation inputs", Project.AspectRatio.SQUARE_1_1);
        var agent = agents.create(owner.userId(), project.id(), "Director", "Use exact inputs", List.of());
        var first = runs.create(owner.userId(), project.id(), agent.id(), "Create references", "limit-first").run();
        UUID olderEligibleVersion = selectedImage(owner.userId(), project.id(), agent.id(), first.id(), "Earlier reference");
        var artifact = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Multiple node versions", null).artifact();
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        List<SelectedOutput> outputs = java.util.stream.IntStream.rangeClosed(0, INPUT_LIMIT)
                .mapToObj(index -> selectedOutput(owner.userId(), project.id(), agent.id(), first.id(), artifact, assetId)).toList();
        var last = outputs.getLast();
        var lastCard = card(owner.userId(), project.id(), last.itemId()).item();
        canvas.duplicate(owner.userId(), project.id(), last.itemId(), UUID.randomUUID(), lastCard.version(),
                drafts.get(owner.userId(), project.id(), last.itemId()).version(), BigDecimal.ZERO,
                BigDecimal.ZERO, lastCard.width(), lastCard.height(), lastCard.zIndex());
        runs.cancel(owner.userId(), project.id(), first.id());
        var second = runs.create(owner.userId(), project.id(), agent.id(), "Continue", "limit-second").run();
        var bindings = second.contextSnapshot().path("bindings");
        assertThat(bindings).hasSize(INPUT_LIMIT);
        assertThat(java.util.stream.StreamSupport.stream(bindings.spliterator(), false)
                .map(binding -> binding.path("selectedVersionId").asText()).toList())
                .doesNotHaveDuplicates().contains(last.versionId().toString());
        assertThat(second.contextSnapshot().at("/conversationMemory/truncated").asBoolean()).isTrue();
        runs.cancel(owner.userId(), project.id(), second.id());

        var explicit = outputs.getFirst();
        UUID agentNodeId = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceAgent(agentNodeId, agent.id(),
                BigDecimal.ZERO, BigDecimal.ZERO, AGENT_WIDTH, AGENT_HEIGHT, 0, null, false)));
        connections.connect(owner.userId(), project.id(), explicit.itemId(), agentNodeId, explicit.versionId(),
                CanvasConnection.RelationType.AGENT_IMAGE_INPUT, null, agent.version());
        var third = runs.create(owner.userId(), project.id(), agent.id(), "Use explicit input", "limit-explicit").run();
        assertThat(third.contextSnapshot().path("bindings")).hasSize(2);
        assertThat(third.contextSnapshot().at("/bindings/0/selectedVersionId").asText()).isEqualTo(explicit.versionId().toString());
        assertThat(third.contextSnapshot().at("/bindings/0").has("source")).isFalse();
        assertThat(third.contextSnapshot().at("/bindings/1/selectedVersionId").asText()).isEqualTo(olderEligibleVersion.toString());
        assertThat(third.contextSnapshot().at("/bindings/1/source").asText()).isEqualTo("CONVERSATION_OUTPUT");
    }

    private UUID selectedImage(UUID ownerId, UUID projectId, UUID agentId, UUID runId, String title) {
        var artifact = artifacts.create(ownerId, projectId, Artifact.Kind.IMAGE, title, null).artifact();
        UUID assetId = ImageAssetFixture.archive(assets, ownerId, projectId);
        return selectedOutput(ownerId, projectId, agentId, runId, artifact, assetId).versionId();
    }

    private SelectedOutput selectedOutput(UUID ownerId, UUID projectId, UUID agentId, UUID runId,
            Artifact artifact, UUID assetId) {
        return events.recordChange(ownerId, projectId, () -> {
            var output = canvas.placeGeneratedArtifactWithinChange(ownerId, projectId, agentId, artifact.id());
            UUID versionId = appendGeneratedVersion(ownerId, projectId, runId, artifact, assetId);
            canvas.recordTaskMediaVersionWithinChange(ownerId, projectId, output.id(), artifact.id(), versionId);
            assertThat(canvas.selectTaskResultWithinChange(ownerId, projectId, output.id(), artifact.id(),
                    output.selectedVersionId(), versionId, null, null)).isTrue();
            return ProjectEventService.Change.unchanged(new SelectedOutput(output.id(), versionId));
        }).value();
    }

    private UUID appendGeneratedVersion(UUID ownerId, UUID projectId, UUID runId, Artifact artifact, UUID assetId) {
        var content = mapper.createObjectNode().put("assetId", assetId.toString())
                .put("prompt", "Synthetic media reference").put("workflowVersion", "mock-v1")
                .put("sourceTaskId", UUID.randomUUID().toString());
        content.putObject("parameters");
        return events.recordChange(ownerId, projectId, () -> {
            var version = artifacts.appendTaskVersionWithinChange(ownerId, projectId, artifact.id(), runId,
                    null, artifact.version(), content, null, false);
            return ProjectEventService.Change.unchanged(version.versionId());
        }).value();
    }

    private CanvasService.CanvasEntry card(UUID ownerId, UUID projectId, UUID itemId) {
        return canvas.list(ownerId, projectId).stream().filter(entry -> entry.item().id().equals(itemId))
                .findFirst().orElseThrow();
    }

    private UUID syntheticAsset(UUID ownerId, UUID projectId, Artifact.Kind kind) throws IOException {
        if (kind == Artifact.Kind.IMAGE) return ImageAssetFixture.archive(assets, ownerId, projectId);
        Path source = Files.createTempFile(STORAGE, "synthetic-media-", kind == Artifact.Kind.AUDIO ? ".wav" : ".mp4");
        try {
            List<String> arguments = kind == Artifact.Kind.AUDIO
                    ? List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                            "anullsrc=r=8000:cl=mono", "-t", "1", "-c:a", "pcm_s16le", "-y", source.toString())
                    : List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                            "color=c=black:s=16x16:r=1", "-t", "1", "-vf", "format=yuv420p", "-an",
                            "-c:v", "libx264", "-movflags", "+faststart", "-y", source.toString());
            mediaTools.ffmpeg(arguments);
            try (var input = Files.newInputStream(source)) {
                return kind == Artifact.Kind.AUDIO ? assets.archiveAudio(ownerId, projectId, input).id()
                        : assets.archiveVideo(ownerId, projectId, input).id();
            }
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private record SelectedOutput(UUID itemId, UUID versionId) {}

    private static Path privateStorage() {
        try {
            return Files.createTempDirectory("agenvas-conversation-inputs-it-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @AfterAll
    static void deleteOnlyThisTestsMedia() throws IOException {
        try (var paths = Files.walk(STORAGE)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
