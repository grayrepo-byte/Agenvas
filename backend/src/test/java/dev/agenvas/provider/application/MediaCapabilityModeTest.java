package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Capability;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Connection;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.ConnectionVersion;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Deployment admission excludes Mock while immutable historical task bindings remain readable. */
class MediaCapabilityModeTest {
    private final JooqMediaCapabilityRepository repository = mock(JooqMediaCapabilityRepository.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final MediaAdapterRegistry registry = new MediaAdapterRegistry(List.of());
    private final MediaCapabilityService configured = service(ProviderModeProperties.Mode.CONFIGURED);

    @Test
    void clearedDefaultsAreProjectedWithoutLookingUpAnAbsentCapability() {
        when(repository.defaultCapabilityId(Task.Kind.IMAGE_GENERATION.name())).thenReturn(null);
        assertThat(configured.defaultCapabilityId(Task.Kind.IMAGE_GENERATION)).isNull();
        assertThat(service(ProviderModeProperties.Mode.MOCK).defaultCapabilityId(Task.Kind.IMAGE_GENERATION)).isNull();
    }

    @Test
    void configuredCatalogExcludesMockConnectionsCapabilitiesAndGenerationCandidates() {
        Snapshot mockImage = snapshot(MediaPlatform.MOCK, "MOCK_IMAGE");
        Snapshot realImage = snapshot(MediaPlatform.OPENAI, MediaAdapterRegistry.OPENAI_GPT_IMAGE_2);
        stub(mockImage);
        stub(realImage);
        when(repository.connections()).thenReturn(List.of(mockImage.connection(), realImage.connection()));

        assertThat(configured.connections()).containsExactly(realImage.connection());
        assertThat(configured.capabilities(mockImage.connection().id())).isEmpty();
        assertThat(configured.candidates(Task.Kind.IMAGE_GENERATION, 0))
                .extracting(candidate -> candidate.binding().adapterId())
                .containsExactly(MediaAdapterRegistry.OPENAI_GPT_IMAGE_2);
        assertThat(configured.publishedCandidates()).hasSize(1);
    }

    @Test
    void configuredRejectsExplicitMockSelectionAndImplicitDefaultAndProjectsNoDefault() {
        for (String adapter : List.of("MOCK_IMAGE", "MOCK_VIDEO", "MOCK_AUDIO")) {
            Snapshot snapshot = snapshot(MediaPlatform.MOCK, adapter);
            stub(snapshot);
            Task.Kind kind = registry.declaration(adapter).kind();
            when(repository.defaultCapabilityId(kind.name())).thenReturn(snapshot.capability().id());
            assertThatThrownBy(() -> configured.resolve(snapshot.capability().id(), kind, 5))
                    .isInstanceOfSatisfying(ApiProblemException.class, error ->
                            assertThat(error.code()).isEqualTo("PROVIDER_UNSUPPORTED_CAPABILITY"));
            assertThatThrownBy(() -> configured.forDraft(null, kind)).isInstanceOf(ApiProblemException.class);
            assertThatThrownBy(() -> configured.setDefault(kind, 0, snapshot.capability().id()))
                    .isInstanceOf(ApiProblemException.class);
            assertThat(configured.defaultCapabilityId(kind)).isNull();
        }
    }

    @Test
    void configuredCannotCreateNewMockConfiguration() {
        assertThatThrownBy(() -> configured.createConnection("fixture", null))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> configured.createConnection("new-fixture", "Fixture", "MOCK", null, null))
                .isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(repository, cipher);
    }

    @Test
    void configuredRejectsMockPublishingAndEnablingWithoutDeletingHistory() {
        Snapshot snapshot = snapshot(MediaPlatform.MOCK, "MOCK_IMAGE");
        stub(snapshot);
        assertThatThrownBy(() -> configured.publishCapability(snapshot.connection().id(), "Fixture", "MOCK_IMAGE"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> configured.setConnectionEnabled(snapshot.connection().id(), 0, true))
                .isInstanceOf(ApiProblemException.class);
        assertThat(configured.getConnectionVersion(snapshot.connection().id(), 1)).isPresent();
    }

    @Test
    void configuredStillReadsPinnedMockHistoryAndDoesNotApproveItForANewAttempt() {
        Snapshot snapshot = snapshot(MediaPlatform.MOCK, "MOCK_IMAGE");
        stub(snapshot);
        var binding = binding(snapshot);
        when(repository.snapshotAt(binding.capabilityId(), 1, binding.connectionId(), 1))
                .thenReturn(Optional.of(snapshot));
        assertThat(configured.pinnedSnapshot(binding)).isSameAs(snapshot);
        assertThat(configured.settings(binding).isEmpty()).isTrue();
        assertThat(configured.isCurrentBinding(binding, Task.Kind.IMAGE_GENERATION, 0)).isFalse();
    }

    @Test
    void mockAndLegacyComfyModesKeepMockSelectionAndPublishedDefaults() {
        Snapshot snapshot = snapshot(MediaPlatform.MOCK, "MOCK_IMAGE");
        stub(snapshot);
        when(repository.connections()).thenReturn(List.of(snapshot.connection()));
        when(repository.defaultCapabilityId(Task.Kind.IMAGE_GENERATION.name()))
                .thenReturn(snapshot.capability().id());
        for (var mode : List.of(ProviderModeProperties.Mode.MOCK)) {
            var catalog = service(mode);
            assertThat(catalog.connections()).containsExactly(snapshot.connection());
            assertThat(catalog.defaultCapabilityId(Task.Kind.IMAGE_GENERATION)).isEqualTo(snapshot.capability().id());
            assertThat(catalog.defaultFor(Task.Kind.IMAGE_GENERATION)).isEqualTo(binding(snapshot));
            assertThat(catalog.candidates(Task.Kind.IMAGE_GENERATION, 0)).hasSize(1);
        }
    }

    @Test
    void deletionClearsSelectionsOnlyAfterSuccessfulCas() {
        Snapshot snapshot = snapshot(MediaPlatform.OPENAI, MediaAdapterRegistry.OPENAI_GPT_IMAGE_2);
        stub(snapshot);
        when(repository.deleteCapability(eq(snapshot.capability().id()), eq(0L), any())).thenReturn(true);
        configured.deleteCapability(snapshot.connection().id(), snapshot.capability().id(), 0);
        verify(repository).lockCapability(snapshot.capability().id());
        verify(repository).clearSelectionsForCapability(eq(snapshot.capability().id()), any());
    }

    @Test
    void staleDeletionDoesNotClearSelections() {
        Snapshot snapshot = snapshot(MediaPlatform.OPENAI, MediaAdapterRegistry.OPENAI_GPT_IMAGE_2);
        stub(snapshot);
        assertThatThrownBy(() -> configured.deleteCapability(snapshot.connection().id(), snapshot.capability().id(), 1))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("MEDIA_CAPABILITY_CONFLICT"));
        verify(repository, never()).clearSelectionsForCapability(any(), any());
    }

    @Test
    void deletedCapabilityRejectsNewSelectionButPreservesPinnedSnapshot() {
        Snapshot live = snapshot(MediaPlatform.OPENAI, MediaAdapterRegistry.OPENAI_GPT_IMAGE_2);
        var capability = live.capability();
        Snapshot deleted = new Snapshot(live.connection(),
                new Capability(capability.id(), capability.connectionId(), capability.name(), false, 1, 1, true),
                live.connectionVersion(), live.adapterId(), live.mappingSha256(), live.specJson());
        stub(deleted);
        when(repository.snapshotAt(capability.id(), 1, live.connection().id(), 1)).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> configured.forDraft(capability.id(), Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
        assertThat(configured.pinnedSnapshot(binding(live)).specJson()).isEqualTo(live.specJson());
    }

    private MediaCapabilityService service(ProviderModeProperties.Mode mode) {
        return new MediaCapabilityService(repository, registry, cipher, Clock.systemUTC(),
                new ObjectMapper(), new ProviderModeProperties(mode));
    }

    private Snapshot snapshot(MediaPlatform platform, String adapterId) {
        var connection = new Connection(UUID.randomUUID(), "Synthetic connection", platform, true, 0, 1);
        var capability = new Capability(UUID.randomUUID(), connection.id(), "Synthetic capability", true, 0, 1, false);
        var version = new ConnectionVersion(connection.id(), 1, null, null, null, null, null, null);
        return new Snapshot(connection, capability, version, adapterId, "synthetic-mapping", "{\"settings\":{}}");
    }

    private void stub(Snapshot snapshot) {
        when(repository.connection(snapshot.connection().id())).thenReturn(Optional.of(snapshot.connection()));
        when(repository.connectionVersion(snapshot.connection().id(), 1)).thenReturn(Optional.of(snapshot.connectionVersion()));
        when(repository.capabilities(snapshot.connection().id())).thenReturn(List.of(snapshot.capability()));
        when(repository.snapshot(snapshot.capability().id())).thenReturn(Optional.of(snapshot));
    }

    private MediaCapabilityBinding binding(Snapshot snapshot) {
        return new MediaCapabilityBinding(snapshot.connection().id(), 1, snapshot.capability().id(), 1,
                snapshot.adapterId(), snapshot.mappingSha256());
    }
}
