package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.PrivateMediaArchive;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.skill.application.SkillService;
import dev.agenvas.skill.domain.SkillContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real synthetic PNG bytes at dispatch; persisted model checkpoints contain only authorized references. */
class AgentSkillImageInputServiceTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lXcAAAAASUVORK5CYII=");
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), run = UUID.randomUUID();
    private final UUID skill = UUID.randomUUID(), version = UUID.randomUUID();
    private final String hash = "d56519cbd3a1f830e3174bf4a0b5ce369f3ef5c389cd3e09b4b367ac5e3dd243";
    private final JsonMapper mapper = new JsonMapper();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final SkillService skills = mock(SkillService.class);
    private final ToolExecutionRepository ledger = mock(ToolExecutionRepository.class);
    private final AgentImageInputService service = new AgentImageInputService(artifacts, assets, mapper, skills, ledger);
    private final AgentImageInputService.SkillInput input = new AgentImageInputService.SkillInput(skill, version, "style", hash);
    @TempDir Path directory;

    private ObjectNode snapshot() {
        var snapshot = mapper.createObjectNode();
        var selected = snapshot.putArray("creativeSkills").addObject().put("skillId", skill.toString())
                .put("skillVersionId", version.toString()).put("assetDelivery", "LLM_CONTEXT");
        selected.putArray("assets").addObject().put("alias", "style").put("kind", "IMAGE").put("contentHash", hash);
        return snapshot;
    }
    private ObjectNode assetRead() {
        return mapper.createObjectNode().put("skillId", skill.toString()).put("skillVersionId", version.toString())
                .put("alias", "style").put("kind", "IMAGE").put("contentHash", hash)
                .put(AgentImageInputService.PREVIEW_REQUEST_KEY, true);
    }
    private void authorize(long size) throws Exception {
        var media = new PrivateMediaArchive.Media(1, UUID.randomUUID(), owner, Asset.MediaKind.IMAGE,
                "private-original-key", "image/png", PNG.length, hash, 1, 1, null,
                "private-thumbnail-key", size, hash);
        var bundle = new SkillContent.Bundle(1, "style", "Style reference", "Instructions", List.of(Artifact.Kind.IMAGE),
                List.of(), List.of(), List.of(new SkillContent.PublishedAsset("style", Artifact.Kind.IMAGE,
                "Style reference", hash, SkillContent.Usage.PROVIDER_REFERENCE, true, "Style", media)));
        when(skills.getBundle(owner, skill, version)).thenReturn(new SkillContent.Version(version, owner, skill, 1, "bundle-hash", bundle, Instant.now()));
        Path preview = directory.resolve("preview.png");
        Files.write(preview, PNG);
        when(skills.file(owner, skill, version, "style", true)).thenReturn(new LibraryService.MediaFile(preview, "image/png", size));
        when(ledger.skillReads(project, run)).thenReturn(List.of(mapper.createObjectNode()
                .put("skillVersionId", version.toString()).put("path", "SKILL.md"), assetRead()));
    }
    private UserMessage checkpointMessage() {
        return UserMessage.builder().text("Requested Skill image")
                .metadata(Map.of(AgentImageInputService.SKILL_METADATA_KEY, List.of(input))).build();
    }

    @Test void committedImageReadHydratesRealPixelsOnEveryRecoveryWithoutImportingProjectAssets() throws Exception {
        authorize(PNG.length);
        var codec = new LlmProtocolCodec(mapper);
        var saved = codec.request(List.of(checkpointMessage()), List.of());
        assertThat(saved.toString()).contains("agentSkillImageInputs", version.toString(), "style", hash)
                .doesNotContain("private-original-key", "private-thumbnail-key", Base64.getEncoder().encodeToString(PNG));
        for (int recovery = 0; recovery < 2; recovery++) {
            var messages = service.hydrate(owner, project, run, snapshot(), codec.requestMessages(saved));
            var attached = (UserMessage) messages.getFirst();
            assertThat(attached.getMedia()).hasSize(1);
            assertThat(attached.getMedia().getFirst().getDataAsByteArray()).isEqualTo(PNG);
            assertThat(attached.getMetadata()).doesNotContainKey(AgentImageInputService.SKILL_METADATA_KEY);
        }
        verifyNoInteractions(artifacts, assets);
    }

    @Test void selectingOrActivatingSkillDoesNotAutoloadImagesAndRejectedReadsDoNotAppend() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("Skill metadata")));
        var main = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("main", "function", "read_skill", "{}"))).build();
        service.appendReadPreviews(history, main, Map.of("main", mapper.createObjectNode().put("status", "SUCCEEDED").set("data", assetRead())));
        var read = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("image", "function", "read_skill_asset", "{}"))).build();
        service.appendReadPreviews(history, read, Map.of("image", mapper.createObjectNode().put("status", "REJECTED").set("data", assetRead())));
        assertThat(service.hydrate(owner, project, run, snapshot(), history)).isEqualTo(history);
        verifyNoInteractions(skills, ledger, artifacts, assets);
        service.appendReadPreviews(history, read, Map.of("image", mapper.createObjectNode().put("status", "SUCCEEDED").set("data", assetRead())));
        service.appendReadPreviews(history, read, Map.of("image", mapper.createObjectNode().put("status", "SUCCEEDED").set("data", assetRead())));
        assertThat(history).hasSize(2);
    }

    @Test void frozenHashAliasAndCommittedReadAreRequiredBeforeOpeningTheSkillFile() throws Exception {
        authorize(PNG.length);
        when(ledger.skillReads(project, run)).thenReturn(List.of(assetRead()));
        assertThatThrownBy(() -> service.hydrate(owner, project, run, snapshot(), List.of(checkpointMessage())))
                .isInstanceOf(ApiProblemException.class);
        verify(skills, never()).file(any(), any(), any(), any(), anyBoolean());
        when(ledger.skillReads(project, run)).thenReturn(List.of(mapper.createObjectNode()
                .put("skillVersionId", version.toString()).put("path", "SKILL.md"), assetRead()));
        var changed = snapshot();
        ((ObjectNode) changed.path("creativeSkills").path(0).path("assets").path(0)).put("contentHash", "different-hash");
        assertThatThrownBy(() -> service.hydrate(owner, project, run, changed, List.of(checkpointMessage())))
                .isInstanceOf(ApiProblemException.class);
        verify(skills, never()).file(any(), any(), any(), any(), anyBoolean());
    }

    @Test void projectAndSkillReferencesShareTheImageCountAndByteLimits() throws Exception {
        var projectRefs = java.util.stream.IntStream.range(0, AgentImageInputService.MAX_IMAGES)
                .mapToObj(i -> new AgentImageInputService.Input(UUID.randomUUID(), UUID.randomUUID())).toList();
        var bound = UserMessage.builder().text("Project references")
                .metadata(Map.of(AgentImageInputService.METADATA_KEY, projectRefs)).build();
        assertThatThrownBy(() -> service.hydrate(owner, project, run, snapshot(), List.of(bound, checkpointMessage())))
                .isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(artifacts, assets, skills, ledger);

        authorize(AgentImageInputService.MAX_IMAGE_BYTES);
        var fourRefs = new ArrayList<AgentImageInputService.Input>();
        for (int i = 0; i < 4; i++) {
            UUID artifactId = UUID.randomUUID(), versionId = UUID.randomUUID(), assetId = UUID.randomUUID();
            var visible = mock(ArtifactVersion.class);
            when(visible.artifactId()).thenReturn(artifactId);
            when(visible.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
            when(artifacts.requireAgentVisibleVersion(owner, project, run, versionId, snapshot())).thenReturn(visible);
            var asset = mock(Asset.class);
            when(asset.projectId()).thenReturn(project);
            when(asset.mediaKind()).thenReturn(Asset.MediaKind.IMAGE);
            when(asset.thumbnailByteSize()).thenReturn(AgentImageInputService.MAX_IMAGE_BYTES);
            when(assets.metadata(owner, project, assetId)).thenReturn(asset);
            fourRefs.add(new AgentImageInputService.Input(artifactId, versionId));
        }
        var four = UserMessage.builder().text("Four project references")
                .metadata(Map.of(AgentImageInputService.METADATA_KEY, fourRefs)).build();
        assertThatThrownBy(() -> service.hydrate(owner, project, run, snapshot(), List.of(four, checkpointMessage())))
                .isInstanceOf(ApiProblemException.class);
        verify(assets, never()).content(any(), any(), any(), anyBoolean());
    }

    @Test void oversizedSkillPreviewIsRejectedAndUnavailableStorageCannotFallBackToMetadata() throws Exception {
        authorize(AgentImageInputService.MAX_IMAGE_BYTES + 1);
        assertThatThrownBy(() -> service.hydrate(owner, project, run, snapshot(), List.of(checkpointMessage())))
                .isInstanceOf(ApiProblemException.class);
        when(skills.file(owner, skill, version, "style", true))
                .thenReturn(new LibraryService.MediaFile(directory.resolve("missing.png"), "image/png", PNG.length));
        assertThatThrownBy(() -> service.hydrate(owner, project, run, snapshot(), List.of(checkpointMessage())))
                .isInstanceOf(IllegalStateException.class).hasMessage("Agent image preview could not be read");
    }
}
