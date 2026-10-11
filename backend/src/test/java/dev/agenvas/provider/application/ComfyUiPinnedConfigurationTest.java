package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiHistory;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Capability;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Connection;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.ConnectionVersion;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.settings.application.CredentialCipher;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

/** Adapter calls reuse the real catalog's historical identity checks before any client or graph is used. */
class ComfyUiPinnedConfigurationTest {
    private static final String PROMPT_ID = "synthetic-original-0007";
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(AdapterKind.class)
    void reconcileReadsOnlyThePinnedVersionsEvenWhenCurrentConfigurationIsDisabled(AdapterKind kind) {
        Fixture fixture = fixture(kind);
        stubPinned(fixture, Optional.of(fixture.snapshot()));
        var version = fixture.snapshot().connectionVersion();
        doReturn(fixture.client()).when(fixture.clients()).create(version);
        pending(kind, fixture.client());

        assertThat(fixture.adapter().reconcile(fixture.context())).isInstanceOf(Submission.Pending.class);
        var binding = fixture.context().binding();
        verify(fixture.repository()).snapshotAt(binding.capabilityId(), 2, binding.connectionId(), 3);
        verify(fixture.repository(), never()).snapshot(binding.capabilityId());
        verify(fixture.clients()).create(version);
        verifyNoInteractions(fixture.published());
        if (kind == AdapterKind.IMAGE) verify(fixture.client()).imageStatus(PROMPT_ID, ComfyUiImageWorkflow.OUTPUT_NODE_ID);
        else verify(fixture.client()).videoStatus(PROMPT_ID, ComfyUiVideoWorkflow.OUTPUT_NODE_ID);
    }

    @ParameterizedTest
    @MethodSource("invalidBindings")
    void missingHistoryAndChangedIdentitiesFailBeforeSubmitOrQuery(AdapterKind kind, InvalidBinding invalid) {
        Fixture fixture = fixture(kind);
        Snapshot original = fixture.snapshot();
        Snapshot changed = new Snapshot(original.connection(), original.capability(), original.connectionVersion(),
                invalid == InvalidBinding.ADAPTER ? "MOCK_IMAGE" : original.adapterId(),
                invalid == InvalidBinding.MAPPING ? "changed-mapping" : original.mappingSha256(), original.specJson());
        stubPinned(fixture, invalid == InvalidBinding.MISSING ? Optional.empty() : Optional.of(changed));

        assertThatThrownBy(() -> fixture.adapter().submit(fixture.context())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.adapter().reconcile(fixture.context())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(fixture.clients(), fixture.client(), fixture.published());
    }

    @ParameterizedTest
    @EnumSource(AdapterKind.class)
    void configurationFailureEscapesWhileHistoryProtocolFailureStillBlocks(AdapterKind kind) {
        Fixture fixture = fixture(kind);
        stubPinned(fixture, Optional.of(fixture.snapshot()));
        var version = fixture.snapshot().connectionVersion();
        when(fixture.clients().create(version)).thenThrow(new IllegalStateException("Pinned ComfyUI origin fingerprint differs"));

        assertThatThrownBy(() -> fixture.adapter().submit(fixture.context()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Pinned ComfyUI origin fingerprint differs");
        assertThatThrownBy(() -> fixture.adapter().reconcile(fixture.context()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Pinned ComfyUI origin fingerprint differs");
        verifyNoInteractions(fixture.client(), fixture.published());

        doReturn(fixture.client()).when(fixture.clients()).create(version);
        if (kind == AdapterKind.IMAGE) {
            when(fixture.client().imageStatus(PROMPT_ID, ComfyUiImageWorkflow.OUTPUT_NODE_ID))
                    .thenThrow(new ComfyUiClient.ProtocolFailure("Synthetic image history protocol failure"));
        } else {
            when(fixture.client().videoStatus(PROMPT_ID, ComfyUiVideoWorkflow.OUTPUT_NODE_ID))
                    .thenThrow(new ComfyUiClient.ProtocolFailure("Synthetic video history protocol failure"));
        }
        assertThat(fixture.adapter().reconcile(fixture.context()))
                .isEqualTo(new Submission.Blocked("PROVIDER_PROTOCOL_INVALID"));
    }

    private Fixture fixture(AdapterKind kind) {
        var repository = mock(JooqMediaCapabilityRepository.class);
        var cipher = mock(CredentialCipher.class);
        var catalog = new MediaCapabilityService(repository, new MediaAdapterRegistry(List.of()), cipher,
                Clock.systemUTC(), mapper, new ProviderModeProperties(ProviderModeProperties.Mode.CONFIGURED));
        var clients = mock(ComfyUiClientFactory.class);
        var published = mock(ComfyUiPublishedWorkflow.class);
        var client = mock(ComfyUiClient.class);
        String adapterId = kind == AdapterKind.IMAGE ? "COMFY_IMAGE_V1" : "COMFY_VIDEO_V1";
        var binding = new MediaCapabilityBinding(UUID.randomUUID(), 3, UUID.randomUUID(), 2, adapterId, "pinned-mapping");
        // Live enabled/deleted flags do not authorize another generation, but cannot erase accepted history.
        var connection = new Connection(binding.connectionId(), "Disabled current connection", MediaPlatform.COMFYUI, false, 8, 3);
        var capability = new Capability(binding.capabilityId(), binding.connectionId(), "Deleted current capability", false, 9, 2, true);
        var version = new ConnectionVersion(binding.connectionId(), 3, "https://comfy.example.com", "pinned-origin-hash", null, null, null, null);
        var snapshot = new Snapshot(connection, capability, version, adapterId, binding.mappingSha256(), "{\"settings\":{}}");
        MediaAdapter adapter = kind == AdapterKind.IMAGE
                ? new ComfyUiImageAdapter(catalog, null, null, null, mapper, clients, published)
                : new ComfyUiVideoAdapter(catalog, null, null, null, mapper, clients, published);
        return new Fixture(adapter, new AttemptContext(null, binding, UUID.randomUUID(), "unused-request-key", PROMPT_ID),
                snapshot, repository, clients, published, client);
    }

    private void stubPinned(Fixture fixture, Optional<Snapshot> snapshot) {
        var binding = fixture.context().binding();
        when(fixture.repository().snapshotAt(binding.capabilityId(), 2, binding.connectionId(), 3)).thenReturn(snapshot);
    }

    private void pending(AdapterKind kind, ComfyUiClient client) {
        if (kind == AdapterKind.IMAGE) {
            when(client.imageStatus(PROMPT_ID, ComfyUiImageWorkflow.OUTPUT_NODE_ID)).thenReturn(new ComfyUiHistory.Pending());
        } else {
            when(client.videoStatus(PROMPT_ID, ComfyUiVideoWorkflow.OUTPUT_NODE_ID)).thenReturn(new ComfyUiHistory.VideoPending());
        }
    }

    static Stream<Arguments> invalidBindings() {
        return Stream.of(AdapterKind.values()).flatMap(kind -> Stream.of(InvalidBinding.values())
                .map(invalid -> Arguments.of(kind, invalid)));
    }

    private record Fixture(MediaAdapter adapter, AttemptContext context, Snapshot snapshot,
            JooqMediaCapabilityRepository repository, ComfyUiClientFactory clients,
            ComfyUiPublishedWorkflow published, ComfyUiClient client) {}
    enum AdapterKind { IMAGE, VIDEO }
    enum InvalidBinding { MISSING, ADAPTER, MAPPING }
}
