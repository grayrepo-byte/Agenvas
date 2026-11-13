package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.ArkMediaDownloadPolicy;
import dev.agenvas.provider.infrastructure.ArkSeedanceClient;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Adapter-level fake downloads and probes verify that generation never strips provider audio. */
class ArkSeedance2AdapterTest {
    private static final String KEY = "synthetic-ark-key";
    private static final String REQUEST_ID = "synthetic-original-task";
    private static final long MAX_VIDEO_BYTES = 500L * 1024 * 1024;
    private static final URI RESULT_URL = URI.create("https://ark-acg-cn-beijing.tos-cn-beijing.volces.com/result.mp4");
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArkSeedanceClient client = mock(ArkSeedanceClient.class);
    private final JooqMediaCapabilityRepository catalog = mock(JooqMediaCapabilityRepository.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final ArkMediaDownloadPolicy downloads = mock(ArkMediaDownloadPolicy.class);
    private final MediaToolRunner mediaTools = mock(MediaToolRunner.class);
    private final AudioReferenceLoader audioReferences = mock(AudioReferenceLoader.class);
    private final SeedanceVideoReferenceLoader videoReferences = mock(SeedanceVideoReferenceLoader.class);
    private final MediaCapabilityBinding binding = new MediaCapabilityBinding(UUID.randomUUID(), 1,
            UUID.randomUUID(), 1, "ARK_SEEDANCE_2_I2V", "synthetic-mapping-hash");
    private final Task task = mock(Task.class);
    private final AttemptContext context = new AttemptContext(task, binding, UUID.randomUUID(),
            "synthetic-request-key", REQUEST_ID);
    private final ArkSeedance2Adapter adapter = new ArkSeedance2Adapter(catalog, cipher, null, null,
            null, client, downloads, mediaTools, mapper, audioReferences, videoReferences);

    ArkSeedance2AdapterTest() {
        var input = mapper.createObjectNode().put("prompt", "A synthetic landscape").put("durationSeconds", 5);
        var mediaInput = input.putObject("mediaInput").put("mode", "TEXT");
        mediaInput.putArray("images");
        mediaInput.putArray("videos");
        mediaInput.putArray("audios");
        mediaInput.putObject("providerParameters").put("aspectRatio", "16:9");
        when(task.input()).thenReturn(input);
        var version = new JooqMediaCapabilityRepository.ConnectionVersion(binding.connectionId(), 1,
                "https://ark.cn-beijing.volces.com", "synthetic-origin-hash", new byte[]{1}, new byte[]{2}, 1,
                "masked");
        when(catalog.snapshotAt(binding.capabilityId(), 1, binding.connectionId(), 1))
                .thenReturn(Optional.of(new JooqMediaCapabilityRepository.Snapshot(null, null, version,
                        binding.adapterId(), binding.mappingSha256(), null)));
        when(cipher.decryptMedia(eq(binding.connectionId()), eq(1), any())).thenReturn(KEY);
    }

    @TempDir
    Path images;
    private final ProjectService projects = mock(ProjectService.class);

    @ParameterizedTest
    @CsvSource({"PORTRAIT_9_16,720,1280", "SQUARE_1_1,1024,1024", "LANDSCAPE_16_9,1280,720"})
    void normalizesPinnedFramesUsingOneProjectReadPerImage(Project.AspectRatio ratio, int width, int height)
            throws Exception {
        var imageAdapter = imageAdapter(ratio);
        when(client.create(eq(KEY), any(), org.mockito.ArgumentMatchers.<List<ArkSeedanceClient.Reference>>any(), eq(5),
                eq("16:9")))
                .thenReturn(REQUEST_ID);

        assertThat(imageAdapter.submit(context)).isEqualTo(new Submission.Accepted(REQUEST_ID));

        ArgumentCaptor<List<ArkSeedanceClient.Reference>> references = ArgumentCaptor.captor();
        verify(client).create(eq(KEY), eq("A synthetic landscape"), references.capture(), eq(5), eq("16:9"));
        assertThat(references.getValue()).hasSize(1);
        var frame = references.getValue().getFirst();
        assertThat(frame.role()).isEqualTo("first_frame");
        assertThat(frame.contentType()).isEqualTo("image/png");
        var decoded = ImageIO.read(new ByteArrayInputStream(frame.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(width);
        assertThat(decoded.getHeight()).isEqualTo(height);
        verify(projects).get(context.ownerId(), task.projectId());
    }

    @Test
    void imagePreflightDoesNotCreateAPaidRequest() throws Exception {
        assertThat(imageAdapter(Project.AspectRatio.LANDSCAPE_16_9).preflightFailure(context)).isNull();
        verify(projects).get(context.ownerId(), task.projectId());
        verifyNoMoreInteractions(client);
    }

    private ArkSeedance2Adapter imageAdapter(Project.AspectRatio ratio) throws Exception {
        UUID projectId = UUID.randomUUID(), artifactId = UUID.randomUUID(), versionId = UUID.randomUUID(),
                assetId = UUID.randomUUID();
        when(task.projectId()).thenReturn(projectId);
        var input = (tools.jackson.databind.node.ObjectNode) task.input().path("mediaInput");
        input.put("mode", "START_END");
        ((tools.jackson.databind.node.ArrayNode) input.path("images")).addObject()
                .put("artifactId", artifactId.toString()).put("versionId", versionId.toString())
                .put("role", "START_FRAME").put("order", 0);
        var project = mock(Project.class);
        when(project.aspectRatio()).thenReturn(ratio);
        when(projects.get(context.ownerId(), projectId)).thenReturn(project);
        var artifacts = mock(ArtifactService.class);
        var version = mock(ArtifactVersion.class);
        when(version.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireVersion(context.ownerId(), projectId, artifactId, versionId)).thenReturn(version);
        Path image = images.resolve("synthetic.png");
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        var assets = mock(AssetService.class);
        var asset = mock(Asset.class);
        when(asset.mediaKind()).thenReturn(Asset.MediaKind.IMAGE);
        when(assets.get(context.ownerId(), projectId, assetId)).thenReturn(new AssetService.AssetFile(asset, image));
        when(audioReferences.load(context.ownerId(), task, false)).thenReturn(List.of());
        when(videoReferences.load(context)).thenReturn(List.of());
        return new ArkSeedance2Adapter(catalog, cipher, artifacts, assets, projects,
                client, downloads, mediaTools, mapper, audioReferences, videoReferences);
    }

    @Test
    void textModeSubmitsWithoutAudioReferencesOrAnAudioGenerationOverride() {
        when(audioReferences.load(context.ownerId(), task, false)).thenReturn(List.of());
        when(videoReferences.load(context)).thenReturn(List.of());
        when(client.create(KEY, "A synthetic landscape", List.of(), 5, "16:9")).thenReturn(REQUEST_ID);

        assertThat(adapter.submit(context)).isEqualTo(new Submission.Accepted(REQUEST_ID));

        verify(client).create(KEY, "A synthetic landscape", List.of(), 5, "16:9");
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void noAudioReferencesPreserveDownloadedBytesWhetherProviderReturnsAudioOrSilence(boolean hasAudio)
            throws Exception {
        byte[] original = "synthetic original provider result".getBytes(StandardCharsets.UTF_8);
        when(client.query(KEY, REQUEST_ID))
                .thenReturn(new ArkSeedanceClient.TaskState("succeeded", RESULT_URL.toString()));
        when(mediaTools.ffprobe(any())).thenReturn(probe(hasAudio));
        download(RESULT_URL, original);

        assertOriginalResult(adapter.reconcile(context), original);

        verify(mediaTools, never()).ffmpeg(any());
        verify(client).query(KEY, REQUEST_ID);
        verifyNoMoreInteractions(client);
    }

    @Test
    void expiredUrlRefreshesOnlyTheOriginalTaskAndPreservesNativeAudioWithoutReferences() throws Exception {
        URI fresh = URI.create("https://ark-acg-cn-beijing.tos-cn-beijing.volces.com/refreshed.mp4");
        byte[] original = "synthetic provider result with native audio".getBytes(StandardCharsets.UTF_8);
        when(client.query(KEY, REQUEST_ID)).thenReturn(
                new ArkSeedanceClient.TaskState("succeeded", RESULT_URL.toString()),
                new ArkSeedanceClient.TaskState("succeeded", fresh.toString()));
        when(downloads.download(eq(RESULT_URL), any(), eq(MAX_VIDEO_BYTES)))
                .thenThrow(new ArkMediaDownloadPolicy.Expired());
        when(mediaTools.ffprobe(any())).thenReturn(probe(true));
        download(fresh, original);

        assertOriginalResult(adapter.reconcile(context), original);

        verify(mediaTools, never()).ffmpeg(any());
        verify(client, times(2)).query(KEY, REQUEST_ID);
        verifyNoMoreInteractions(client);
    }

    private void download(URI url, byte[] bytes) {
        doAnswer(call -> {
            OutputStream sink = call.getArgument(1);
            sink.write(bytes);
            return (long) bytes.length;
        }).when(downloads).download(eq(url), any(), eq(MAX_VIDEO_BYTES));
    }

    private void assertOriginalResult(Submission submission, byte[] original) throws Exception {
        assertThat(submission).isInstanceOf(Submission.Completed.class);
        try (var payload = ((Submission.Completed) submission).payload()) {
            assertThat(payload.declaredContentType()).isEqualTo("video/mp4");
            assertThat(payload.stream().readAllBytes()).isEqualTo(original);
        }
    }

    private String probe(boolean hasAudio) {
        var details = mapper.createObjectNode();
        details.putObject("format").put("format_name", "mov,mp4,m4a,3gp,3g2,mj2").put("duration", "5.0");
        var streams = details.putArray("streams");
        streams.addObject().put("codec_type", "video");
        if (hasAudio)
            streams.addObject().put("codec_type", "audio");
        return mapper.writeValueAsString(details);
    }
}
