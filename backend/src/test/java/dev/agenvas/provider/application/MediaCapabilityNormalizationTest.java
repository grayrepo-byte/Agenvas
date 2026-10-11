package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.provider.domain.ComfyUiWorkflowDefinition;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Capability;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Connection;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.ConnectionVersion;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.CreateKey;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Publication hashes, admission ordering and immutable-version comparisons are wire-sensitive. */
class MediaCapabilityNormalizationTest {
    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final UUID CONNECTION_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID CAPABILITY_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final String ADAPTER = MediaAdapterRegistry.OPENAI_GPT_IMAGE_2;
    private static final String OPENAI_SPEC = "{\"schemaVersion\":1,\"kind\":\"IMAGE_GENERATION\","
            + "\"minimumSeconds\":0,\"maximumSeconds\":0,\"maxReferenceImages\":2,"
            + "\"maxReferenceAudios\":0,\"maxReferenceVideos\":0,\"supportedVideoInputModes\":[],"
            + "\"defaultVideoInputMode\":null,\"supportsEndFrame\":false,\"modelId\":\"gpt-image-2\","
            + "\"outputFormat\":\"png\",\"settings\":{\"quality\":\"high\",\"model\":\"synthetic-model\","
            + "\"maxReferenceImages\":2,\"pricing\":{\"amount\":\"0.1200\",\"currency\":\"USD\",\"unit\":\"IMAGE\"}}}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JooqMediaCapabilityRepository repository = mock(JooqMediaCapabilityRepository.class);
    private final MediaCapabilityService service = new MediaCapabilityService(repository,
            new MediaAdapterRegistry(List.of()), mock(CredentialCipher.class),
            Clock.fixed(NOW, ZoneOffset.UTC), mapper,
            new ProviderModeProperties(ProviderModeProperties.Mode.CONFIGURED));

    @ParameterizedTest
    @ValueSource(strings = {"WAN_IMAGE", "WAN_VIDEO", "ARK_SEEDREAM_IMAGE", "GOOGLE_VEO",
            "GOOGLE_OMNI_VIDEO", "KLING_IMAGE", "KLING_VIDEO"})
    void partialNativeDraftKeepsPublishedDefaultsAndExplicitValuesWithoutMutatingEitherSource(String adapter) {
        var connection = new Connection(CONNECTION_ID, "Synthetic connection", MediaPlatform.WAN, true, 0, 1);
        var published = mapper.readTree("""
                {"settings":{"defaultParameters":{"dynamicValues":{
                    "resolution":"720P","seed":17,"generate_audio":true}}}}
                """);
        var draft = mapper.readTree("""
                {"dynamicValues":{"seed":0,"generate_audio":false,"video_1":"synthetic-version"}}
                """);
        var binding = new MediaCapabilityBinding(CONNECTION_ID, 1, CAPABILITY_ID, 1,
                adapter, "synthetic-history");
        var snapshot = new Snapshot(connection, capability(CAPABILITY_ID, 0, 1), null,
                adapter, binding.mappingSha256(), published.toString());
        when(repository.snapshotAt(CAPABILITY_ID, 1, CONNECTION_ID, 1)).thenReturn(Optional.of(snapshot));

        var effective = service.parameters(binding, draft);

        assertThat(effective.at("/dynamicValues/resolution").asText()).isEqualTo("720P");
        assertThat(effective.at("/dynamicValues/seed").asInt()).isZero();
        assertThat(effective.at("/dynamicValues/generate_audio").asBoolean()).isFalse();
        assertThat(effective.at("/dynamicValues/video_1").asText()).isEqualTo("synthetic-version");
        assertThat(draft.at("/dynamicValues/resolution").isMissingNode()).isTrue();
        assertThat(published.at("/settings/defaultParameters/dynamicValues/seed").asInt()).isEqualTo(17);
    }

    @Test
    void idempotentCreationKeepsExactSpecBytesAndHashesAndClaimsBeforeConnectionAdmission() {
        stubConnection(MediaPlatform.OPENAI, true, null);
        when(repository.claimCapabilityCreateKey(anyString(), anyString(), any(), eq(NOW))).thenReturn(true);
        when(repository.capability(any())).thenAnswer(invocation -> Optional.of(
                capability(invocation.getArgument(0), 0, 1)));

        Capability result = service.publishCapability("synthetic-create", CONNECTION_ID,
                "  Synthetic capability  ", ADAPTER, openAiSettings());

        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        var order = inOrder(repository);
        order.verify(repository).claimCapabilityCreateKey(eq("synthetic-create"),
                eq(payloadHash("Synthetic capability")), id.capture(), eq(NOW));
        order.verify(repository).connection(CONNECTION_ID);
        order.verify(repository).connectionVersion(CONNECTION_ID, 1);
        order.verify(repository).insertCapability(id.getValue(), CONNECTION_ID, "Synthetic capability",
                ADAPTER, Sha256.hex(ADAPTER + ":v1:" + OPENAI_SPEC), OPENAI_SPEC, NOW);
        assertThat(result.id()).isEqualTo(id.getValue());
    }

    @Test
    void matchingReplayDoesNotReadCurrentConnectionAndChangedPayloadStillConflicts() {
        Capability published = capability(CAPABILITY_ID, 7, 3);
        when(repository.capabilityCreateKey("synthetic-replay"))
                .thenReturn(Optional.of(new CreateKey(payloadHash("Synthetic capability"), CAPABILITY_ID)));
        when(repository.capability(CAPABILITY_ID)).thenReturn(Optional.of(published));

        assertThat(service.publishCapability("synthetic-replay", CONNECTION_ID,
                "Synthetic capability", ADAPTER, openAiSettings())).isSameAs(published);
        assertThatThrownBy(() -> service.publishCapability("synthetic-replay", CONNECTION_ID,
                "Different capability", ADAPTER, openAiSettings()))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.code()).isEqualTo("MEDIA_CAPABILITY_CONFLICT"));
        verify(repository, never()).connection(any());
        verify(repository, never()).insertCapability(any(), any(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void ordinaryCreationChecksConnectionFirstWhileIdempotentCreationValidatesPayloadFirst() {
        stubConnection(MediaPlatform.COMFYUI, false, "http://127.0.0.1:8188");
        ObjectNode malformed = mapper.createObjectNode();
        malformed.putObject("comfyWorkflow");

        assertThatThrownBy(() -> service.publishCapability(CONNECTION_ID, "", "COMFY_IMAGE_V1", malformed))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.detail().key()).isEqualTo("api.media-capability-service.connection-disabled"));
        assertThatThrownBy(() -> service.publishCapability("synthetic-order", CONNECTION_ID,
                "", "COMFY_IMAGE_V1", malformed))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.detail().key()).isEqualTo("api.media-capability-service.name-must-be-1-160-characters-long"));
        assertThatThrownBy(() -> service.publishCapability("synthetic-order", CONNECTION_ID,
                "Synthetic capability", "COMFY_IMAGE_V1", malformed))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> {
                    assertThat(error.code()).isEqualTo("COMFYUI_WORKFLOW_INVALID");
                    assertThat(error.detail().arguments()).containsExactly("definition");
                });
        verify(repository).connection(CONNECTION_ID);
        verify(repository, never()).claimCapabilityCreateKey(anyString(), anyString(), any(), any());
    }

    @Test
    void normalizedComfyDefinitionStillRejectsExpansionAfterAdministratorConfigurationValidation() {
        stubConnection(MediaPlatform.COMFYUI, true, "http://127.0.0.1:8188");
        ObjectNode settings = mapper.createObjectNode();
        ObjectNode definition = (ObjectNode) mapper.readTree("""
                {"schemaVersion":1,"graph":{"1":{"class_type":"SyntheticOutput","inputs":{"padding":""}}},
                "bindings":[],"output":{"nodeId":"1","field":"images"},"width":640,"height":640,
                "minimumSeconds":0,"maximumSeconds":0,"fps":24,"frameMultiple":4,"frameOffset":1}
                """);
        int padding = ComfyUiWorkflowDefinition.MAX_JSON_BYTES - bytes(definition) - 1;
        definition.withObject("graph").withObject("1").withObject("inputs").put("padding", "x".repeat(padding));
        settings.set("comfyWorkflow", definition);
        assertThat(bytes(definition)).isLessThanOrEqualTo(ComfyUiWorkflowDefinition.MAX_JSON_BYTES);
        var parsed = ComfyUiWorkflowDefinition.parse(mapper, definition, Task.Kind.IMAGE_GENERATION);
        assertThat(bytes(mapper.valueToTree(parsed))).isGreaterThan(ComfyUiWorkflowDefinition.MAX_JSON_BYTES);

        settings.putObject("pricing").put("amount", "invalid").put("currency", "USD").put("unit", "IMAGE");
        assertThatThrownBy(() -> service.publishCapability(CONNECTION_ID, "Synthetic capability",
                "COMFY_IMAGE_V1", settings))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        assertThat(error.detail().key()).isEqualTo("api.media-capability-configuration.price-should-be-a-non-negative-decimal-amount-up-to"));
        settings.withObject("pricing").put("amount", "0.1200");
        assertThatThrownBy(() -> service.publishCapability(CONNECTION_ID, "Synthetic capability",
                "COMFY_IMAGE_V1", settings))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> {
                    assertThat(error.code()).isEqualTo("COMFYUI_WORKFLOW_INVALID");
                    assertThat(error.detail().arguments()).containsExactly("definition");
                });
        verify(repository, never()).insertCapability(any(), any(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void wideIntegerSettingsWithTheSameSerializedValuesDoNotCreateANewContentVersion() {
        Connection connection = stubConnection(MediaPlatform.AUTODL, true, null);
        Capability current = capability(CAPABILITY_ID, 7, 3);
        Snapshot snapshot = new Snapshot(connection, current,
                new ConnectionVersion(CONNECTION_ID, 1, null, null, null, null, null, null),
                AutoDlWorkflows.ADAPTER_ID, "synthetic-history", """
                {"settings":{"workflowId":"minimax_h3_b99_001","videoResolutions":["736p"],
                "videoResolution":"736p","seed":1,"maxReferenceImages":0}}
                """);
        when(repository.snapshot(CAPABILITY_ID)).thenReturn(Optional.of(snapshot));
        when(repository.capability(CAPABILITY_ID)).thenReturn(Optional.of(current));
        when(repository.updateCapability(CAPABILITY_ID, 7, "Renamed capability", false, 3, NOW)).thenReturn(true);
        ObjectNode supplied = mapper.createObjectNode().put("workflowId", "minimax_h3_b99_001").put("seed", 1L);
        supplied.put("maxReferenceImages", BigInteger.ZERO);

        service.updateCapability(CONNECTION_ID, CAPABILITY_ID, 7, "Renamed capability", false,
                AutoDlWorkflows.ADAPTER_ID, supplied);

        var order = inOrder(repository);
        order.verify(repository).lockCapability(CAPABILITY_ID);
        order.verify(repository).snapshot(CAPABILITY_ID);
        order.verify(repository).updateCapability(CAPABILITY_ID, 7, "Renamed capability", false, 3, NOW);
        verify(repository, never()).insertCapabilityVersion(any(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    private ObjectNode openAiSettings() {
        ObjectNode settings = mapper.createObjectNode().put("model", "synthetic-model")
                .put("quality", "high").put("maxReferenceImages", 2L);
        settings.putObject("pricing").put("unit", "IMAGE").put("currency", "USD").put("amount", "0000.1200");
        return settings;
    }

    private String payloadHash(String name) {
        return Sha256.hex(CONNECTION_ID + "\u0000" + name + "\u0000" + ADAPTER + "\u0000" + OPENAI_SPEC);
    }

    private Connection stubConnection(MediaPlatform platform, boolean enabled, String origin) {
        Connection connection = new Connection(CONNECTION_ID, "Synthetic connection", platform, enabled, 0, 1);
        when(repository.connection(CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(repository.connectionVersion(CONNECTION_ID, 1)).thenReturn(Optional.of(
                new ConnectionVersion(CONNECTION_ID, 1, origin, null, null, null, null, null)));
        return connection;
    }

    private Capability capability(UUID id, long metadataVersion, int contentVersion) {
        return new Capability(id, CONNECTION_ID, "Synthetic capability", true,
                metadataVersion, contentVersion, false);
    }

    private int bytes(JsonNode value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
