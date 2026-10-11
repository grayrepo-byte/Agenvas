package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Synthetic preview: proves project/version boundaries, bounded reads, stream release and public-only prompts. */
class ImagePromptInputServiceTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lXcAAAAASUVORK5CYII=");
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), artifact = UUID.randomUUID();
    private final UUID version = UUID.randomUUID(), assetId = UUID.randomUUID();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final Task task = mock(Task.class);
    private final Asset asset = mock(Asset.class);
    private final ArtifactVersion source = mock(ArtifactVersion.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ImagePromptInputService service = new ImagePromptInputService(artifacts, assets, tasks);
    private final tools.jackson.databind.node.ObjectNode input = mapper.createObjectNode().put("schemaVersion", 3)
            .put("systemPrompt", "Frozen image reconstruction instruction").put("language", "zh")
            .put("artifactId", artifact.toString()).put("sourceVersionId", version.toString());

    @BeforeEach void setup() {
        when(task.isImagePrompt()).thenReturn(true); when(task.input()).thenReturn(input); when(task.projectId()).thenReturn(project);
        when(tasks.ownerForWorker(task)).thenReturn(owner);
        when(artifacts.requireImageVersionForTask(owner, project, version)).thenReturn(source);
        when(source.artifactId()).thenReturn(artifact);
        when(source.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(assets.metadata(owner, project, assetId)).thenReturn(asset); when(asset.mediaKind()).thenReturn(Asset.MediaKind.IMAGE);
    }

    @ParameterizedTest @CsvSource({"zh,Chinese", "en,English", "ru,Russian", "ja,Japanese"})
    void keepsFrozenCreativeInstructionSeparateFromLanguageAndUntrustedImageRules(String locale, String language) {
        input.put("language", locale);
        var messages = service.messages(task);
        assertThat(messages.getFirst().getText()).isEqualTo("Frozen image reconstruction instruction");
        assertThat(messages.get(1).getText()).contains(language, "untrusted", "Do not call tools", "private reasoning");
        assertThat(((UserMessage) messages.getLast()).getMedia()).isEmpty();
        verifyNoInteractions(artifacts, assets, tasks);
    }

    @Test void hydratesOnlyThePinnedPreviewAndClosesItsStreamWithoutMutatingTheCheckpoint() throws Exception {
        var preview = new AssetService.AssetContent(asset, "synthetic-preview", PNG.length, "image/png");
        when(assets.content(owner, project, assetId, true)).thenReturn(preview);
        var closed = new AtomicBoolean();
        when(assets.open(preview, 0, PNG.length)).thenReturn(new ByteArrayInputStream(PNG) {
            @Override public void close() { closed.set(true); }
        });
        var messages = service.messages(task);
        var hydrated = service.hydrate(task, messages);
        assertThat(((UserMessage) hydrated.getLast()).getMedia().getFirst().getDataAsByteArray()).isEqualTo(PNG);
        assertThat(((UserMessage) messages.getLast()).getMedia()).isEmpty();
        assertThat(new LlmProtocolCodec(mapper).request(messages, java.util.List.of()).toString())
                .doesNotContain(Base64.getEncoder().encodeToString(PNG), "data:");
        assertThat(closed).isTrue();
    }

    @ParameterizedTest @ValueSource(longs = {0, 10485761})
    void refusesEmptyOrOversizedPreviewsBeforeOpeningTheirStreams(long size) throws Exception {
        when(assets.content(owner, project, assetId, true)).thenReturn(new AssetService.AssetContent(asset, "preview", size, "image/png"));
        assertThatThrownBy(() -> service.hydrate(task, service.messages(task))).isInstanceOf(IllegalArgumentException.class);
        verify(assets, never()).open(any(), anyLong(), anyLong());
    }

    @Test void refusesAChangedArtifactBeforeReadingAnyMedia() {
        when(source.artifactId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.hydrate(task, service.messages(task))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(assets);
    }

    @Test void closesTheStreamWhenThePreviewHasFewerBytesThanItsDeclaredSize() throws Exception {
        var preview = new AssetService.AssetContent(asset, "preview", PNG.length + 1, "image/png");
        when(assets.content(owner, project, assetId, true)).thenReturn(preview);
        var closed = new AtomicBoolean();
        when(assets.open(preview, 0, PNG.length + 1)).thenReturn(new ByteArrayInputStream(PNG) {
            @Override public void close() { closed.set(true); }
        });
        assertThatThrownBy(() -> service.hydrate(task, service.messages(task))).isInstanceOf(IllegalArgumentException.class);
        assertThat(closed).isTrue();
    }

    @Test void refusesDispatchInsideADatabaseTransaction() {
        var messages = service.messages(task);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.hydrate(task, messages)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(artifacts, assets, tasks);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
