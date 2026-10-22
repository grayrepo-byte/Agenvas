package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.MediaToolsProperties;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class AgentMediaSampleServiceTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), artifact = UUID.randomUUID(),
            versionId = UUID.randomUUID(), assetId = UUID.randomUUID();
    private final MediaToolRunner tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));
    private final AgentMediaSampleService service = new AgentMediaSampleService(artifacts, assets, tools);
    private final tools.jackson.databind.node.ObjectNode snapshot = mapper.createObjectNode();

    private AgentMediaSampleService.Sample sample(List<Integer> frames, Integer start, Integer duration) {
        return new AgentMediaSampleService.Sample(artifact, versionId, frames, start, duration);
    }
    private Asset authorized() {
        ArtifactVersion version = mock(ArtifactVersion.class);
        when(version.artifactId()).thenReturn(artifact);
        when(version.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireAgentReadableVersion(owner, project, snapshot, versionId)).thenReturn(version);
        Asset asset = mock(Asset.class);
        when(asset.id()).thenReturn(assetId); when(asset.projectId()).thenReturn(project);
        when(asset.durationMs()).thenReturn(2000); when(asset.mediaKind()).thenReturn(Asset.MediaKind.VIDEO);
        when(assets.metadata(owner, project, assetId)).thenReturn(asset);
        return asset;
    }
    @Test void rejectsOutOfRangeSamplesBeforeOpeningAnyMedia() {
        authorized();
        assertThatThrownBy(() -> service.authorize(owner, project, snapshot, sample(List.of(2000), null, null)))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.authorize(owner, project, snapshot, sample(List.of(), 1500, 1000)))
                .isInstanceOf(ApiProblemException.class);
        verify(assets, never()).get(any(), any(), any());
    }
    @Test void enforcesStrictSampleShapeAndAudioBudget() {
        String prefix = "{\"artifactId\":\"" + artifact + "\",\"versionId\":\"" + versionId + "\",";
        for (String invalid : List.of("\"timestampsMs\":[0,0]}", "\"timestampsMs\":[0,1,2,3,4]}",
                "\"audioStartMs\":0}", "\"audioStartMs\":0,\"audioDurationMs\":15001}", "\"timestampsMs\":[0.5]}", "\"url\":\"https://example.org\"}"))
            assertThatThrownBy(() -> AgentMediaSampleService.parse(mapper.readTree(prefix + invalid)))
                    .isInstanceOf(ApiProblemException.class);
    }
    @Test void refusesDecodingInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.hydrate(owner, project, snapshot, sample(List.of(0), null, null)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
            verifyNoInteractions(assets, artifacts);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void realFfmpegProducesTimestampedFramesAndBoundedAudioFromOnlyTheAuthorizedVersion() throws Exception {
        Asset asset = authorized();
        Path source = temporary.resolve("synthetic.mp4");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=12:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", "-y", source.toString()));
        when(asset.byteSize()).thenReturn(Files.size(source));
        when(asset.sha256()).thenReturn(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        when(assets.get(owner, project, assetId)).thenReturn(new AssetService.AssetFile(asset, source));
        var result = service.hydrate(owner, project, snapshot, sample(List.of(0, 1000), 0, 1000));
        assertThat(result).hasSize(2);
        var sheet = ImageIO.read(new ByteArrayInputStream(result.getFirst().getDataAsByteArray()));
        assertThat(sheet.getWidth()).isEqualTo(1024);
        assertThat(sheet.getHeight()).isEqualTo(540);
        assertThat(result.get(1).getMimeType().toString()).isEqualTo("audio/wav");
        byte[] audio = result.get(1).getDataAsByteArray();
        assertThat(audio).hasSizeBetween(32000, 33000);
        assertThat(new String(audio, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        verify(artifacts).requireAgentReadableVersion(owner, project, snapshot, versionId);
        byte[] corrupted = Files.readAllBytes(source);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(source, corrupted);
        assertThatThrownBy(() -> service.hydrate(owner, project, snapshot, sample(List.of(0), null, null)))
                .isInstanceOf(ApiProblemException.class);
    }
}
