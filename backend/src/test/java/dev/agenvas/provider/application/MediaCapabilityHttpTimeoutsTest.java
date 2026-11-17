package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Capability;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Connection;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.ConnectionVersion;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.http.HttpTimeouts;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.support.ComfyWorkflowFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Publication takes the same strict policy through all settings normalization branches. */
class MediaCapabilityHttpTimeoutsTest {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final UUID CAPABILITY = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final JooqMediaCapabilityRepository repository = mock(JooqMediaCapabilityRepository.class);
    private final MediaAdapterRegistry registry = new MediaAdapterRegistry(List.of());
    private final MediaCapabilityService catalog = new MediaCapabilityService(repository, registry,
            mock(CredentialCipher.class), Clock.fixed(NOW, ZoneOffset.UTC), mapper,
            new ProviderModeProperties(ProviderModeProperties.Mode.CONFIGURED));

    @ParameterizedTest
    @ValueSource(strings = {"OPENAI_GPT_IMAGE_2", "GOOGLE_NANO_BANANA_2", "VOLC_SEED_AUDIO_1",
            "ARK_SEEDANCE_2_I2V", "ARK_SEEDREAM_IMAGE", "GOOGLE_VEO", "GOOGLE_OMNI_VIDEO",
            "WAN_IMAGE", "WAN_VIDEO", "KLING_IMAGE", "KLING_VIDEO", "KLING_IMAGE_3", "KLING_VIDEO_3",
            "MINIMAX_H3", "AUTODL_COMFY_VIDEO", "COMFY_IMAGE_V1", "COMFY_VIDEO_V1",
            "RUNNINGHUB_IMAGE", "RUNNINGHUB_VIDEO", "RUNNINGHUB_AUDIO"})
    void everyNetworkPublicationBranchPreservesTheExplicitCompletePolicy(String adapter) {
        stubConnection(adapter);
        var settings = settings(adapter);
        settings.set("httpTimeouts", mapper.valueToTree(new HttpTimeouts(10, 120, 240, 300)));
        when(repository.capability(any())).thenAnswer(call -> Optional.of(capability(call.getArgument(0), 0, 1)));

        catalog.publishCapability(CONNECTION, "Synthetic policy", adapter, settings);

        var spec = ArgumentCaptor.forClass(String.class);
        verify(repository).insertCapability(any(), eq(CONNECTION), eq("Synthetic policy"), eq(adapter), anyString(),
                spec.capture(), eq(NOW));
        assertThat(mapper.readTree(spec.getValue()).at("/settings/httpTimeouts"))
                .isEqualTo(mapper.readTree("""
                        {"connectSeconds":10,"writeSeconds":120,"readSeconds":240,"callSeconds":300}
                        """));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENAI_GPT_IMAGE_2", "GOOGLE_NANO_BANANA_2", "VOLC_SEED_AUDIO_1",
            "ARK_SEEDANCE_2_I2V", "ARK_SEEDREAM_IMAGE", "GOOGLE_VEO", "GOOGLE_OMNI_VIDEO",
            "WAN_IMAGE", "WAN_VIDEO", "KLING_IMAGE", "KLING_VIDEO", "KLING_IMAGE_3", "KLING_VIDEO_3",
            "MINIMAX_H3", "AUTODL_COMFY_VIDEO", "COMFY_IMAGE_V1", "COMFY_VIDEO_V1",
            "RUNNINGHUB_IMAGE", "RUNNINGHUB_VIDEO", "RUNNINGHUB_AUDIO"})
    void noPublicationBranchCanBypassTimeoutValidation(String adapter) {
        stubConnection(adapter);
        var settings = settings(adapter);
        settings.set("httpTimeouts", mapper.readTree("""
                {"connectSeconds":1,"writeSeconds":61,"readSeconds":30,"callSeconds":60}
                """));
        assertThatThrownBy(() -> catalog.publishCapability(CONNECTION, "Invalid policy", adapter, settings))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.detail().key()).isEqualTo("api.media-http-timeouts.invalid"));
        verify(repository, never()).insertCapability(any(), any(), anyString(), anyString(), anyString(), anyString(),
                any());
    }

    @Test
    void metadataOnlyEditDoesNotInventPolicyOrPublishANewLegacyContentVersion() {
        var connection = stubConnection("OPENAI_GPT_IMAGE_2");
        var current = capability(CAPABILITY, 7, 3);
        var snapshot = new Snapshot(connection, current, version(), "OPENAI_GPT_IMAGE_2", "legacy-hash",
                "{\"settings\":{\"quality\":\"medium\",\"model\":\"\"}}");
        when(repository.snapshot(CAPABILITY)).thenReturn(Optional.of(snapshot));
        when(repository.capability(CAPABILITY)).thenReturn(Optional.of(current));
        when(repository.updateCapability(CAPABILITY, 7, "Renamed legacy", true, 3, NOW)).thenReturn(true);

        catalog.updateCapability(CONNECTION, CAPABILITY, 7, "Renamed legacy", true, "OPENAI_GPT_IMAGE_2", null);

        verify(repository).updateCapability(CAPABILITY, 7, "Renamed legacy", true, 3, NOW);
        verify(repository, never()).insertCapabilityVersion(any(), anyInt(), anyString(), anyString(), anyString(),
                any());
        assertThat(mapper.readTree(snapshot.specJson()).at("/settings/httpTimeouts").isMissingNode()).isTrue();
    }

    @Test
    void policyLookupUsesTheExactFrozenBindingAndRetainsLegacyAbsence() {
        var connection = stubConnection("OPENAI_GPT_IMAGE_2");
        var old = new MediaCapabilityBinding(CONNECTION, 1, CAPABILITY, 1, "OPENAI_GPT_IMAGE_2", "old");
        var newer = new MediaCapabilityBinding(CONNECTION, 1, CAPABILITY, 2, "OPENAI_GPT_IMAGE_2", "new");
        when(repository.snapshotAt(CAPABILITY, 1, CONNECTION, 1)).thenReturn(Optional.of(new Snapshot(connection,
                capability(CAPABILITY, 1, 2), version(), old.adapterId(), "old", "{\"settings\":{}}")));
        when(repository.snapshotAt(CAPABILITY, 2, CONNECTION, 1)).thenReturn(Optional.of(new Snapshot(connection,
                capability(CAPABILITY, 1, 2), version(), newer.adapterId(), "new", """
                {"settings":{"httpTimeouts":{"connectSeconds":5,"writeSeconds":40,"readSeconds":60,"callSeconds":90}}}
                """)));

        assertThat(catalog.httpTimeouts(old)).isNull();
        assertThat(catalog.httpTimeouts(newer)).isEqualTo(new HttpTimeouts(5, 40, 60, 90));
        verify(repository, never()).snapshot(CAPABILITY);
    }

    @Test
    void serverDefaultsCoverAllNetworkAdaptersAndAlignRequestWritesWithTheWholeCallBudget() {
        assertThat(catalog.httpTimeoutDefaults()).containsOnlyKeys("OPENAI_GPT_IMAGE_2", "GOOGLE_NANO_BANANA_2",
                "VOLC_SEED_AUDIO_1", "ARK_SEEDANCE_2_I2V", "ARK_SEEDREAM_IMAGE", "GOOGLE_VEO", "GOOGLE_OMNI_VIDEO",
                "WAN_IMAGE", "WAN_VIDEO", "KLING_IMAGE", "KLING_VIDEO", "KLING_IMAGE_3", "KLING_VIDEO_3",
                "MINIMAX_H3", "AUTODL_COMFY_VIDEO", "COMFY_IMAGE_V1", "COMFY_VIDEO_V1",
                "RUNNINGHUB_IMAGE", "RUNNINGHUB_VIDEO", "RUNNINGHUB_AUDIO");
        assertThat(catalog.httpTimeoutDefaults()).allSatisfy((id, policy) -> {
            assertThat(policy.writeSeconds()).isEqualTo(policy.callSeconds());
            assertThat(policy.readSeconds()).isLessThanOrEqualTo(policy.callSeconds());
        });
        assertThat(catalog.httpTimeoutDefaults().get("COMFY_IMAGE_V1")).isEqualTo(new HttpTimeouts(5, 20, 20, 20));
        assertThat(catalog.httpTimeoutDefaults().get("GOOGLE_VEO")).isEqualTo(new HttpTimeouts(10, 600, 480, 600));
    }

    private ObjectNode settings(String adapter) {
        if (adapter.startsWith("COMFY_"))
            return ComfyWorkflowFixture.settings(mapper, adapter.contains("VIDEO"), false);
        var settings = mapper.createObjectNode();
        if (adapter.startsWith("RUNNINGHUB_")) {
            var definition = settings.putObject("runningHub");
            definition.put("schemaVersion", 1).put("protocolVersion", "V2").put("targetType", "AI_APP")
                    .put("targetId", "123");
            definition.putArray("fields");
            definition.putArray("outputs").addObject().put("kind", adapter.substring("RUNNINGHUB_".length()))
                    .put("primary", true).put("maxCount", 1);
        }
        return settings;
    }

    private Connection stubConnection(String adapter) {
        var connection = new Connection(CONNECTION, "Synthetic connection", registry.declaration(adapter).platform(),
                true, 0, 1);
        when(repository.connection(CONNECTION)).thenReturn(Optional.of(connection));
        var version = new ConnectionVersion(CONNECTION, 1,
                registry.declaration(adapter).originRequired() ? "http://localhost:12345" : null,
                null, null, null, null, null);
        when(repository.connectionVersion(CONNECTION, 1)).thenReturn(Optional.of(version));
        return connection;
    }

    private ConnectionVersion version() {
        return new ConnectionVersion(CONNECTION, 1, "http://localhost:12345", null, null, null, null, null);
    }

    private Capability capability(UUID id, long metadataVersion, int contentVersion) {
        return new Capability(id, CONNECTION, "Synthetic policy", true, metadataVersion, contentVersion, false);
    }
}
