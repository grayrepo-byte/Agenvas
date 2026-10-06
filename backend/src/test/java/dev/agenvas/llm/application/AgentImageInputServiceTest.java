package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import java.util.ArrayList;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Synthetic one-pixel PNG; no user media or Provider calls are used. */
class AgentImageInputServiceTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lXcAAAAASUVORK5CYII=");
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), run = UUID.randomUUID();
    private final UUID artifact = UUID.randomUUID(), version = UUID.randomUUID(), assetId = UUID.randomUUID();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final dev.agenvas.skill.application.SkillService skills = mock(dev.agenvas.skill.application.SkillService.class);
    private final ToolExecutionRepository ledger = mock(ToolExecutionRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentImageInputService service = new AgentImageInputService(artifacts, assets, mapper, skills, ledger);
    private final AgentImageInputService.Input input = new AgentImageInputService.Input(artifact, version);

    private tools.jackson.databind.node.ObjectNode snapshot() {
        var snapshot = mapper.createObjectNode();
        snapshot.putArray("bindings").addObject().put("kind", "IMAGE")
                .put("artifactId", artifact.toString()).put("selectedVersionId", version.toString());
        return snapshot;
    }
    private UserMessage message() {
        return UserMessage.builder().text("Synthetic reference image")
                .metadata(Map.of(AgentImageInputService.METADATA_KEY, List.of(input))).build();
    }
    private Asset fixture(long size, Asset.MediaKind kind) {
        ArtifactVersion bound = mock(ArtifactVersion.class);
        when(bound.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(bound.artifactId()).thenReturn(artifact);
        when(artifacts.requireAgentVisibleVersion(owner, project, run, version, snapshot())).thenReturn(bound);
        Asset asset = mock(Asset.class);
        when(asset.id()).thenReturn(assetId);
        when(asset.projectId()).thenReturn(project);
        when(asset.mediaKind()).thenReturn(kind);
        when(asset.thumbnailByteSize()).thenReturn(size);
        when(assets.metadata(owner, project, assetId)).thenReturn(asset);
        return asset;
    }

    @Test void checkpointAndContinuationKeepOnlyReferencesWhileEachDispatchReceivesImageBytes() throws Exception {
        Asset asset = fixture(PNG.length, Asset.MediaKind.IMAGE);
        var content = new AssetService.AssetContent(asset, "synthetic-preview", PNG.length, "image/png");
        when(assets.content(owner, project, assetId, true)).thenReturn(content);
        AtomicBoolean closed = new AtomicBoolean();
        when(assets.open(content, 0, PNG.length)).thenAnswer(invocation -> new ByteArrayInputStream(PNG) {
            @Override public void close() { closed.set(true); }
        });
        var codec = new LlmProtocolCodec(mapper);
        var saved = codec.request(List.of(message()), List.of());
        assertThat(saved.toString()).contains(version.toString()).doesNotContain(Base64.getEncoder().encodeToString(PNG));
        for (int round = 0; round < 2; round++) {
            var messages = service.hydrate(owner, project, run, snapshot(), codec.requestMessages(saved));
            var dispatched = (UserMessage) messages.getFirst();
            assertThat(dispatched.getMedia()).hasSize(1);
            assertThat(dispatched.getMedia().getFirst().getDataAsByteArray()).isEqualTo(PNG);
            assertThat(dispatched.getMetadata()).doesNotContainKey(AgentImageInputService.METADATA_KEY);
            assertThat(closed).isTrue();
        }
    }
    private AssistantMessage readCall(String name) {
        return AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("read", "function", name, "{}"))).build();
    }
    private tools.jackson.databind.node.ObjectNode readResult(String status) {
        var result = mapper.createObjectNode().put("status", status);
        result.putArray("data").addObject().put("artifactId", artifact.toString())
                .put("versionId", version.toString()).put("kind", "IMAGE")
                .put(AgentImageInputService.PREVIEW_REQUEST_KEY, true);
        return result;
    }
    @Test void repeatsAndAliasesOfTheSameReadVersionDoNotAppendDuplicateAttachments() {
        List<Message> history = new ArrayList<>();
        var result = readResult("SUCCEEDED");
        result.withArray("data").add(result.path("data").get(0).deepCopy());
        service.appendReadPreviews(history, readCall("read_artifacts"), Map.of("read", result));
        service.appendReadPreviews(history, readCall("read_artifacts"), Map.of("read", result));
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getMetadata().get(AgentImageInputService.METADATA_KEY))
                .isEqualTo(List.of(input));
        verifyNoInteractions(artifacts, assets);
    }
    @Test void nineSequentialReadsRetainObservationsAndOnlyTheCurrentImageAcrossCheckpointRecovery() throws Exception {
        var references = new ArrayList<AgentImageInputService.Input>();
        var previewIds = new ArrayList<UUID>();
        for (int i = 0; i < 9; i++) {
            UUID artifactId = UUID.randomUUID(), versionId = UUID.randomUUID(), previewId = UUID.randomUUID();
            var visible = mock(ArtifactVersion.class);
            when(visible.artifactId()).thenReturn(artifactId);
            when(visible.content()).thenReturn(mapper.createObjectNode().put("assetId", previewId.toString()));
            when(artifacts.requireAgentVisibleVersion(owner, project, run, versionId, snapshot())).thenReturn(visible);
            var asset = mock(Asset.class);
            when(asset.id()).thenReturn(previewId);
            when(asset.projectId()).thenReturn(project);
            when(asset.mediaKind()).thenReturn(Asset.MediaKind.IMAGE);
            when(asset.thumbnailByteSize()).thenReturn((long) PNG.length);
            when(assets.metadata(owner, project, previewId)).thenReturn(asset);
            var content = new AssetService.AssetContent(asset, "synthetic-preview-" + i, PNG.length, "image/png");
            when(assets.content(owner, project, previewId, true)).thenReturn(content);
            when(assets.open(content, 0, PNG.length)).thenAnswer(ignored -> new ByteArrayInputStream(PNG));
            references.add(new AgentImageInputService.Input(artifactId, versionId));
            previewIds.add(previewId);
        }
        var codec = new LlmProtocolCodec(mapper);
        List<Message> history = new ArrayList<>();
        tools.jackson.databind.JsonNode firstCheckpoint = null;
        for (var reference : references) {
            var result = mapper.createObjectNode().put("status", "SUCCEEDED");
            result.putArray("data").addObject().put("kind", "IMAGE")
                    .put("artifactId", reference.artifactId().toString())
                    .put("versionId", reference.versionId().toString())
                    .put(AgentImageInputService.PREVIEW_REQUEST_KEY, true)
                    .put(AgentImageInputService.SEQUENTIAL_PREVIEW_KEY, true);
            var assistant = readCall("read_artifacts");
            if (!history.isEmpty()) history.add(new AssistantMessage("Observed identity anchors of the previous image"));
            history.add(assistant);
            history.add(codec.toolResults(assistant, Map.of("read", result)));
            service.appendReadPreviews(history, assistant, Map.of("read", result));
            var saved = codec.request(history, List.of());
            if (firstCheckpoint == null) firstCheckpoint = saved.deepCopy();
            history = new ArrayList<>(codec.requestMessages(saved));
            var dispatched = service.hydrate(owner, project, run, snapshot(), history);
            assertThat(dispatched.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .mapToInt(message -> message.getMedia().size()).sum()).isEqualTo(1);
            assertThat(((UserMessage) dispatched.getLast()).getMedia()).hasSize(1);
            assertThat(saved.toString()).doesNotContain(Base64.getEncoder().encodeToString(PNG));
        }
        assertThat(history.stream().filter(AssistantMessage.class::isInstance)
                .filter(message -> message.getText().startsWith("Observed identity anchors"))).hasSize(8);
        assertThat(firstCheckpoint.toString()).contains(references.getFirst().versionId().toString())
                .doesNotContain(references.getLast().versionId().toString());
        tools.jackson.databind.JsonNode actualReferences = mapper.valueToTree(
                history.getLast().getMetadata().get(AgentImageInputService.METADATA_KEY));
        tools.jackson.databind.JsonNode expectedReferences = mapper.valueToTree(List.of(references.getLast()));
        assertThat(actualReferences).isEqualTo(expectedReferences);
        service.hydrate(owner, project, run, snapshot(), codec.requestMessages(codec.request(history, List.of())));
        verify(assets, org.mockito.Mockito.times(1)).content(owner, project, previewIds.getFirst(), true);
        verify(assets, org.mockito.Mockito.times(2)).content(owner, project, previewIds.getLast(), true);
    }
    @Test void doesNotSendImagesWithoutAReadOrForRejectedAndOtherToolResults() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("Bound image metadata")));
        service.appendReadPreviews(history, readCall("read_artifacts"), Map.of("read", readResult("REJECTED")));
        service.appendReadPreviews(history, readCall("read_selection"), Map.of("read", readResult("SUCCEEDED")));
        var metadataOnly = readResult("SUCCEEDED");
        ((tools.jackson.databind.node.ObjectNode) metadataOnly.path("data").get(0))
                .remove(AgentImageInputService.PREVIEW_REQUEST_KEY);
        service.appendReadPreviews(history, readCall("read_artifacts"), Map.of("read", metadataOnly));
        assertThat(service.hydrate(owner, project, run, snapshot(), history)).isEqualTo(history);
        verifyNoInteractions(artifacts, assets);
    }
    @Test void rejectsUnboundReferencesWithoutReadingMedia() {
        UUID foreign = UUID.randomUUID();
        var denied = new ApiProblemException(org.springframework.http.HttpStatus.NOT_FOUND,
                "INPUT_SCOPE_DENIED", dev.agenvas.shared.i18n.ApiMessage.of("api.read-tool-service.task-does-not-exist"),
                dev.agenvas.shared.i18n.ApiMessage.of("api.read-tool-service.task-does-not-exist"), false);
        when(artifacts.requireAgentVisibleVersion(owner, project, run, foreign, snapshot())).thenThrow(denied);
        var wrong = UserMessage.builder().text("Synthetic reference").metadata(Map.of(
                AgentImageInputService.METADATA_KEY,
                List.of(new AgentImageInputService.Input(artifact, foreign)))).build();
        assertThatThrownBy(() -> service.hydrate(owner, project, run, snapshot(), List.of(wrong)))
                .isSameAs(denied);
        verifyNoInteractions(assets);
    }
    @Test void acceptsRunVisibleImageVersionsWithoutRequiringAnOriginalBinding() {
        fixture(PNG.length, Asset.MediaKind.IMAGE);
        var visible = artifacts.requireAgentVisibleVersion(owner, project, run, version, snapshot());
        var emptySnapshot = mapper.createObjectNode();
        when(artifacts.requireAgentVisibleVersion(owner, project, run, version, emptySnapshot)).thenReturn(visible);
        service.validateInputs(owner, project, run, emptySnapshot, List.of(input));
        verify(assets, never()).content(any(), any(), any(), anyBoolean());
    }
    @Test void rejectsMismatchedArtifactIdentityBeforeAccessingStorage() {
        fixture(PNG.length, Asset.MediaKind.IMAGE);
        assertThatThrownBy(() -> service.validateInputs(owner, project, run, snapshot(),
                List.of(new AgentImageInputService.Input(UUID.randomUUID(), version))))
                .isInstanceOf(ApiProblemException.class);
        verify(assets, never()).metadata(any(), any(), any());
    }
    @Test void enforcesImageCountBeforeAnyAssetRead() {
        assertThatThrownBy(() -> service.validateInputs(owner, project, run, snapshot(),
                java.util.Collections.nCopies(AgentImageInputService.MAX_IMAGES + 1, input)))
                .isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(artifacts, assets);
    }
    @Test void enforcesPreviewByteLimitBeforeOpeningStorage() {
        fixture(AgentImageInputService.MAX_IMAGE_BYTES + 1, Asset.MediaKind.IMAGE);
        assertThatThrownBy(() -> service.validateInputs(owner, project, run, snapshot(), List.of(input)))
                .isInstanceOf(ApiProblemException.class);
        verify(assets, never()).content(any(), any(), any(), anyBoolean());
    }
    @Test void enforcesTotalByteLimitBeforeOpeningStorage() {
        fixture(AgentImageInputService.MAX_IMAGE_BYTES, Asset.MediaKind.IMAGE);
        int count = Math.toIntExact(AgentImageInputService.MAX_TOTAL_BYTES / AgentImageInputService.MAX_IMAGE_BYTES) + 1;
        assertThatThrownBy(() -> service.validateInputs(owner, project, run, snapshot(), java.util.Collections.nCopies(count, input)))
                .isInstanceOf(ApiProblemException.class);
        verify(assets, never()).content(any(), any(), any(), anyBoolean());
    }
    @Test void rejectsDispatchInsideDatabaseTransactions() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.hydrate(owner, project, run, snapshot(), List.of(message())))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("database transaction");
            verifyNoInteractions(artifacts, assets);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void rejectsOtherMediaTypesAndForeignProjectAssets() {
        Asset asset = fixture(PNG.length, Asset.MediaKind.VIDEO);
        assertThatThrownBy(() -> service.validateInputs(owner, project, run, snapshot(), List.of(input))).isInstanceOf(ApiProblemException.class);
        when(asset.mediaKind()).thenReturn(Asset.MediaKind.IMAGE);
        when(asset.projectId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.validateInputs(owner, project, run, snapshot(), List.of(input))).isInstanceOf(ApiProblemException.class);
    }
    @Test void closesAStorageStreamWhenBytesAreMissing() throws Exception {
        Asset asset = fixture(PNG.length, Asset.MediaKind.IMAGE);
        var content = new AssetService.AssetContent(asset, "synthetic-preview", PNG.length, "image/png");
        when(assets.content(owner, project, assetId, true)).thenReturn(content);
        AtomicBoolean closed = new AtomicBoolean();
        when(assets.open(content, 0, PNG.length)).thenReturn(new ByteArrayInputStream(new byte[0]) {
            @Override public void close() { closed.set(true); }
        });
        assertThatThrownBy(() -> service.hydrate(owner, project, run, snapshot(), List.of(message())))
                .isInstanceOf(ApiProblemException.class);
        assertThat(closed).isTrue();
    }
    @Test void unavailableStorageDoesNotSilentlyFallBackToMetadata() throws Exception {
        Asset asset = fixture(PNG.length, Asset.MediaKind.IMAGE);
        var content = new AssetService.AssetContent(asset, "synthetic-preview", PNG.length, "image/png");
        when(assets.content(owner, project, assetId, true)).thenReturn(content);
        when(assets.open(content, 0, PNG.length)).thenThrow(new IOException("Synthetic failure"));
        assertThatThrownBy(() -> service.hydrate(owner, project, run, snapshot(), List.of(message())))
                .isInstanceOf(IllegalStateException.class).hasMessage("Agent image preview could not be read");
    }
}
