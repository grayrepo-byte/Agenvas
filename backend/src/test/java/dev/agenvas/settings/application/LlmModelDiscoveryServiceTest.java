package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.agenvas.settings.infrastructure.ModelMetadataClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmModelDiscoveryServiceTest {
    private final ModelMetadataClient client = mock(ModelMetadataClient.class);
    private final LlmProviderConfigRepository repository = mock(LlmProviderConfigRepository.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final LlmModelDiscoveryService service = new LlmModelDiscoveryService(client, repository, cipher,
            new LlmEndpointPolicy(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private final String endpoint = "https://10.0.0.5/v1";

    @Test
    void expiredPreviewCannotBeRelabelledAsFreshAutomaticMetadata() {
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        var expiring = new LlmModelDiscoveryService(client, repository, cipher,
                new LlmEndpointPolicy(), clock);
        when(client.fetch(ModelCatalog.Provider.OPENROUTER, endpoint, "synthetic-key", "model"))
                .thenReturn(new ModelCatalog(
                        List.of(new ModelCatalog.Model("model", 32000, null, 4096, null, null, null)), false));
        var preview = expiring.preview(new LlmModelDiscoveryService.PreviewRequest(ModelCatalog.Provider.OPENROUTER,
                LlmModelDiscoveryService.CredentialMode.DRAFT, 0, endpoint, "synthetic-key", "model"));
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(601));
        var selection = new LlmModelDiscoveryService.Selection(ModelCatalog.Provider.OPENROUTER,
                LlmModelDiscoveryService.Mode.AUTO, preview.id(), null, null, 2048,
                LlmModelPolicy.OutputParameter.MAX_TOKENS);
        assertThatThrownBy(() -> expiring.resolve(0, endpoint, "model", "synthetic-key", selection))
                .hasMessage("MODEL_PREVIEW_EXPIRED");
    }

    @Test
    void automaticSelectionIsBoundToProviderEndpointModelAndCredential() {
        when(client.fetch(ModelCatalog.Provider.OPENROUTER, endpoint, "synthetic-key", "model"))
                .thenReturn(new ModelCatalog(
                        List.of(new ModelCatalog.Model("model", 64000, null, 4096, null, null, 32000)), false));
        var preview = service.preview(new LlmModelDiscoveryService.PreviewRequest(ModelCatalog.Provider.OPENROUTER,
                LlmModelDiscoveryService.CredentialMode.DRAFT, 0, endpoint, "synthetic-key", "model"));
        var selection = new LlmModelDiscoveryService.Selection(ModelCatalog.Provider.OPENROUTER,
                LlmModelDiscoveryService.Mode.AUTO, preview.id(), null, null, 2048,
                LlmModelPolicy.OutputParameter.MAX_TOKENS);
        assertThat(service.resolve(0, endpoint, "model", "synthetic-key", selection).contextWindowTokens())
                .isEqualTo(32000);
        assertThatThrownBy(() -> service.resolve(0, "https://10.0.0.6/v1", "model", "synthetic-key", selection))
                .hasMessage("MODEL_PREVIEW_EXPIRED");
        assertThatThrownBy(() -> service.resolve(0, endpoint, "other", "synthetic-key", selection))
                .hasMessage("MODEL_PREVIEW_EXPIRED");
        assertThatThrownBy(() -> service.resolve(0, endpoint, "model", "different-key", selection))
                .hasMessage("MODEL_PREVIEW_EXPIRED");
        assertThatThrownBy(() -> service.resolve(1, endpoint, "model", "synthetic-key", selection))
                .hasMessage("MODEL_PREVIEW_EXPIRED");
    }

    @Test
    void savedCredentialModeNeverUsesAnEditedEndpoint() {
        var saved = new LlmProviderConfig(UUID.randomUUID(), 7, endpoint, "model", new byte[]{1}, new byte[]{2}, 1,
                "masked", false, true, Instant.EPOCH);
        when(repository.active()).thenReturn(Optional.of(saved));
        when(cipher.decrypt(eq(saved.id()), eq(7), any())).thenReturn("synthetic-key");
        when(client.fetch(ModelCatalog.Provider.COMPATIBLE, endpoint, "synthetic-key", "model"))
                .thenReturn(new ModelCatalog(List.of(), false));
        assertThatCode(
                () -> service.preview(new LlmModelDiscoveryService.PreviewRequest(ModelCatalog.Provider.COMPATIBLE,
                        LlmModelDiscoveryService.CredentialMode.SAVED, 7, null, null, "model")))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                () -> service.preview(new LlmModelDiscoveryService.PreviewRequest(ModelCatalog.Provider.COMPATIBLE,
                        LlmModelDiscoveryService.CredentialMode.SAVED, 7, "https://10.0.0.6/v1", null, "model")))
                .hasMessage("MODEL_PREVIEW_INVALID");
        verify(client, times(1)).fetch(any(), eq(endpoint), eq("synthetic-key"), any());
    }

    @Test
    void manuallyConfirmedCapacityNeedsNoRemoteMetadata() {
        var selection = new LlmModelDiscoveryService.Selection(ModelCatalog.Provider.COMPATIBLE,
                LlmModelDiscoveryService.Mode.MANUAL, null, 16384, null, 2048,
                LlmModelPolicy.OutputParameter.MAX_TOKENS);
        assertThat(service.resolve(0, endpoint, "custom-model", "synthetic-key", selection).source())
                .isEqualTo(LlmModelPolicy.Source.MANUAL);
        verifyNoInteractions(client);
    }

    @Test
    void namedConnectionPreviewUsesOnlyItsOwnRetainedKeyAndBindsTheResultToThatConnection() {
        UUID id = UUID.randomUUID();
        var saved = new LlmProviderConfig(UUID.randomUUID(), 7, endpoint, "model", new byte[]{1}, new byte[]{2}, 1,
                "masked", false, false, Instant.EPOCH);
        when(repository.findConnection(id))
                .thenReturn(Optional.of(new LlmConnection(id, "Saved", 7, Instant.EPOCH, Instant.EPOCH)));
        when(repository.findVersion(7)).thenReturn(Optional.of(saved));
        when(cipher.decrypt(eq(saved.id()), eq(7), any())).thenReturn("connection-specific-key");
        String changedEndpoint = "https://10.0.0.6/v1";
        when(client.fetch(ModelCatalog.Provider.COMPATIBLE, changedEndpoint, "connection-specific-key", "model"))
                .thenReturn(new ModelCatalog(
                        List.of(new ModelCatalog.Model("model", 32000, null, 4096, null, null, null)), false));
        var preview = service.preview(new LlmModelDiscoveryService.PreviewRequest(ModelCatalog.Provider.COMPATIBLE,
                LlmModelDiscoveryService.CredentialMode.DRAFT, 7, changedEndpoint, " ", "model", id));
        var selection = new LlmModelDiscoveryService.Selection(ModelCatalog.Provider.COMPATIBLE,
                LlmModelDiscoveryService.Mode.AUTO, preview.id(), null, null, 2048,
                LlmModelPolicy.OutputParameter.MAX_TOKENS);
        assertThat(service.resolve(id, 7, changedEndpoint, "model", "connection-specific-key", selection)
                .contextWindowTokens()).isEqualTo(32000);
        assertThatThrownBy(() -> service.resolve(UUID.randomUUID(), 7, changedEndpoint, "model",
                "connection-specific-key", selection)).hasMessage("MODEL_PREVIEW_EXPIRED");
        assertThatThrownBy(
                () -> service.preview(new LlmModelDiscoveryService.PreviewRequest(ModelCatalog.Provider.COMPATIBLE,
                        LlmModelDiscoveryService.CredentialMode.DRAFT, 6, changedEndpoint, null, "model", id)))
                .hasMessage("MODEL_PREVIEW_INVALID");
        assertThatThrownBy(
                () -> service.preview(new LlmModelDiscoveryService.PreviewRequest(ModelCatalog.Provider.COMPATIBLE,
                        LlmModelDiscoveryService.CredentialMode.DRAFT, 0, changedEndpoint, null, "model",
                        UUID.randomUUID())))
                .hasMessage("MODEL_PREVIEW_INVALID");
    }
}
