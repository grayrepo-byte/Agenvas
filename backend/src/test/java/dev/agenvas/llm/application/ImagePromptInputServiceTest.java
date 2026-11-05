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

/** Synthetic original: proves project/version boundaries, bounded reads, stream release and public-only prompts. */
class ImagePromptInputServiceTest {
    private static final byte[] PNG = dev.agenvas.testing.ImageAssetFixture.encoded("png", 1600, 900);
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
        when(asset.byteSize()).thenReturn((long) PNG.length); when(asset.contentType()).thenReturn("image/png");
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

    @Test void hydratesOnlyThePinnedOriginalAndClosesItsStreamWithoutMutatingTheCheckpoint() throws Exception {
        var original = new AssetService.AssetContent(asset, "synthetic-original", PNG.length, "image/png");
        when(assets.content(owner, project, assetId, false)).thenReturn(original);
        var closed = new AtomicBoolean();
        when(assets.open(original, 0, PNG.length)).thenReturn(new ByteArrayInputStream(PNG) {
            @Override public void close() { closed.set(true); }
        });
        var messages = service.messages(task);
        var hydrated = service.hydrate(task, messages);
        byte[] sent = ((UserMessage) hydrated.getLast()).getMedia().getFirst().getDataAsByteArray();
        assertThat(sent).isEqualTo(PNG);
        var decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(sent));
        assertThat(decoded.getWidth()).isEqualTo(1600); assertThat(decoded.getHeight()).isEqualTo(900);
        verify(assets, never()).content(owner, project, assetId, true);
        assertThat(((UserMessage) messages.getLast()).getMedia()).isEmpty();
        assertThat(new LlmProtocolCodec(mapper).request(messages, java.util.List.of()).toString())
                .doesNotContain(Base64.getEncoder().encodeToString(PNG), "data:");
        assertThat(closed).isTrue();
    }

    @ParameterizedTest @CsvSource({"0,IMAGE_PROMPT_INPUT_UNAVAILABLE", "10485761,IMAGE_PROMPT_INPUT_TOO_LARGE"})
    void refusesEmptyOrOversizedOriginalsBeforeAccessingStorage(long size, String code) throws Exception {
        when(asset.byteSize()).thenReturn(size);
        assertThatThrownBy(() -> service.hydrate(task, service.messages(task)))
                .isInstanceOf(ImagePromptInputService.Failure.class).hasMessage(code);
        verify(assets, never()).content(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(assets, never()).open(any(), anyLong(), anyLong());
    }

    @ParameterizedTest @ValueSource(strings = {"image/gif", "image/svg+xml", "image/avif", "text/html"})
    void rejectsUnsupportedOriginalsWithoutUsingAThumbnail(String type) {
        when(asset.contentType()).thenReturn(type);
        assertThatThrownBy(() -> service.hydrate(task, service.messages(task)))
                .isInstanceOf(ImagePromptInputService.Failure.class).hasMessage(ImagePromptInputService.UNSUPPORTED);
        verify(assets, never()).content(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @ParameterizedTest @ValueSource(strings = {"image/jpeg", "image/webp"})
    void retainsOriginalEncodingAndBytes(String type) throws Exception {
        byte[] bytes = type.equals("image/jpeg") ? dev.agenvas.testing.ImageAssetFixture.encoded("jpeg", 960, 640)
                : Base64.getDecoder().decode("UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoEAAQAAkA4JaQAA3AA/vucwAA=");
        when(asset.byteSize()).thenReturn((long) bytes.length); when(asset.contentType()).thenReturn(type);
        var original = new AssetService.AssetContent(asset, "original", bytes.length, type);
        when(assets.content(owner, project, assetId, false)).thenReturn(original);
        when(assets.open(original, 0, bytes.length)).thenReturn(new ByteArrayInputStream(bytes));
        var media = ((UserMessage) service.hydrate(task, service.messages(task)).getLast()).getMedia().getFirst();
        assertThat(media.getMimeType().toString()).isEqualTo(type);
        assertThat(media.getDataAsByteArray()).isEqualTo(bytes);
    }

    @Test void rejectsUnauthorizedPinnedVersionsBeforeReadingAnyAsset() {
        when(artifacts.requireImageVersionForTask(owner, project, version)).thenThrow(new IllegalArgumentException("Denied"));
        assertThatThrownBy(() -> service.hydrate(task, service.messages(task))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(assets);
    }

    @Test void refusesAChangedArtifactBeforeReadingAnyMedia() {
        when(source.artifactId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.hydrate(task, service.messages(task))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(assets);
    }

    @Test void closesTheStreamWhenTheOriginalHasFewerBytesThanItsDeclaredSize() throws Exception {
        when(asset.byteSize()).thenReturn((long) PNG.length + 1);
        var original = new AssetService.AssetContent(asset, "original", PNG.length + 1, "image/png");
        when(assets.content(owner, project, assetId, false)).thenReturn(original);
        var closed = new AtomicBoolean();
        when(assets.open(original, 0, PNG.length + 1)).thenReturn(new ByteArrayInputStream(PNG) {
            @Override public void close() { closed.set(true); }
        });
        assertThatThrownBy(() -> service.hydrate(task, service.messages(task))).isInstanceOf(IllegalArgumentException.class);
        assertThat(closed).isTrue();
    }

    @Test void failsSafelyWhenOriginalCannotBeReadWithoutFallingBackToThumbnail() throws Exception {
        var original = new AssetService.AssetContent(asset, "original", PNG.length, "image/png");
        when(assets.content(owner, project, assetId, false)).thenReturn(original);
        when(assets.open(original, 0, PNG.length)).thenThrow(new java.io.IOException("private path and signed URL"));
        assertThatThrownBy(() -> service.hydrate(task, service.messages(task)))
                .isInstanceOf(ImagePromptInputService.Failure.class).hasMessage(ImagePromptInputService.UNAVAILABLE)
                .hasNoCause();
        verify(assets, never()).content(owner, project, assetId, true);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void storageVerificationFailuresAreSafeAndNeverReadTheThumbnail(boolean remote) {
        RuntimeException unavailable = remote ? new dev.agenvas.shared.error.ApiProblemException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "OBJECT_STORAGE_UNAVAILABLE",
                dev.agenvas.shared.i18n.ApiMessage.of("api.image-prompt.invalid"),
                dev.agenvas.shared.i18n.ApiMessage.of("api.image-prompt.invalid"), true)
                : new IllegalStateException("private path", new java.io.IOException("private URL"));
        when(assets.content(owner, project, assetId, false)).thenThrow(unavailable);
        assertThatThrownBy(() -> service.hydrate(task, service.messages(task)))
                .isInstanceOf(ImagePromptInputService.Failure.class).hasMessage(ImagePromptInputService.UNAVAILABLE).hasNoCause();
        verify(assets, never()).content(owner, project, assetId, true);
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
