package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.storage.MediaRelayService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.OpenAiImage2Client;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class OpenAiImage2AdapterTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"MEDIA_RELAY_PREPARATION_FAILED", "MEDIA_RELAY_PUBLIC_ENDPOINT_REQUIRED"})
    void relayPreflightClassifiesPublicDnsAndOtherPreparationFailuresBeforeCallingTheProvider(String expected) throws Exception {
        var mapper = new ObjectMapper();
        var catalog = mock(JooqMediaCapabilityRepository.class);
        var cipher = mock(CredentialCipher.class);
        var artifacts = mock(ArtifactService.class);
        var assets = mock(AssetService.class);
        var client = mock(OpenAiImage2Client.class);
        var relay = mock(MediaRelayService.class);
        var binding = new MediaCapabilityBinding(UUID.randomUUID(), 1, UUID.randomUUID(), 1,
                MediaAdapterRegistry.OPENAI_GPT_IMAGE_2, "synthetic-hash");
        var snapshot = mock(JooqMediaCapabilityRepository.Snapshot.class);
        when(catalog.snapshotAt(binding.capabilityId(), 1, binding.connectionId(), 1)).thenReturn(Optional.of(snapshot));
        when(snapshot.adapterId()).thenReturn(binding.adapterId());
        when(snapshot.mappingSha256()).thenReturn(binding.mappingSha256());
        when(snapshot.connectionVersion()).thenReturn(new JooqMediaCapabilityRepository.ConnectionVersion(
                binding.connectionId(), 1, "https://example.com/v1", "synthetic-hash", new byte[] {1}, new byte[] {2}, 1, "masked"));
        when(cipher.decryptMedia(any(), anyInt(), any())).thenReturn("synthetic-key");
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), artifact = UUID.randomUUID();
        UUID version = UUID.randomUUID(), assetId = UUID.randomUUID(), profile = UUID.randomUUID();
        var input = mapper.createObjectNode().put("imageRelayProfileId", profile.toString());
        var media = input.putObject("mediaInput");
        media.putObject("parameters").put("aspectRatio", "1:1");
        media.putArray("images").addObject().put("artifactId", artifact.toString())
                .put("versionId", version.toString()).put("order", 0).put("role", "REFERENCE");
        Task task = mock(Task.class);
        when(task.projectId()).thenReturn(project);
        when(task.input()).thenReturn(input);
        var source = mock(ArtifactVersion.class);
        when(source.artifactId()).thenReturn(artifact);
        when(source.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireImageVersionForTask(owner, project, version)).thenReturn(source);
        var asset = mock(Asset.class);
        when(asset.mediaKind()).thenReturn(Asset.MediaKind.IMAGE);
        Path png = temporary.resolve("synthetic.png");
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        when(assets.get(owner, project, assetId)).thenReturn(new AssetService.AssetFile(asset, png));
        RuntimeException failure = ProviderFailureCodes.MEDIA_RELAY_PREPARATION_FAILED.equals(expected)
                ? new IllegalStateException("synthetic preparation failure")
                : new ApiProblemException(HttpStatus.BAD_REQUEST, expected, ApiMessage.of("api.media-relay.unavailable"),
                        ApiMessage.of("api.media-relay.public-endpoint-required"), false);
        doThrow(failure).when(relay).preflightImage(profile);
        var adapter = new OpenAiImage2Adapter(catalog, cipher, artifacts, assets,
                mock(ProjectService.class), client, mapper, relay);

        assertThat(adapter.preflightFailure(new AttemptContext(task, binding, owner, null, null))).isEqualTo(expected);
        verify(relay).preflightImage(profile);
        verifyNoInteractions(client);
    }

    @Test
    void mapsAtomicRatiosAndResolutionLabelsToBoundedGptImageDimensions() {
        assertThat(OpenAiImage2Adapter.openAiSize("9:16", "1K")).isEqualTo("720x1280");
        assertThat(OpenAiImage2Adapter.openAiSize("1:1", "2K")).isEqualTo("2048x2048");
        assertThat(OpenAiImage2Adapter.openAiSize("16:9", "4K")).isEqualTo("3840x2160");
        assertThat(OpenAiImage2Adapter.openAiSize("21:9", "4K")).isEqualTo("3808x1632");
    }

    @Test
    void publishesExplicitMaskSupportOnlyForTheOpenAiImageAdapter() {
        MediaAdapterRegistry registry = new MediaAdapterRegistry(List.of());
        assertThat(registry.declaration(MediaAdapterRegistry.OPENAI_GPT_IMAGE_2)
                .supportsImageMask()).isTrue();
        assertThat(registry.declaration(MediaAdapterRegistry.GOOGLE_NANO_BANANA_2)
                .supportsImageMask()).isFalse();
    }
}
