package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.SeedreamModels;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.SeedreamClient;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SeedreamImageAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JooqMediaCapabilityRepository catalog = mock(JooqMediaCapabilityRepository.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final NativeMediaReferences references = mock(NativeMediaReferences.class);
    private final SeedreamClient client = spy(new SeedreamClient(mapper, URI.create("http://127.0.0.1:65534")));
    private final SeedreamImageAdapter adapter = new SeedreamImageAdapter(catalog, cipher, client, references, mapper);
    private final UUID owner = UUID.randomUUID(), connection = UUID.randomUUID(), capability = UUID.randomUUID();

    @Test void capturesIdentityAndAllResultsTogetherUsingOnlyFrozenConnectionVersion() throws Exception {
        var context = context();
        var result = manifest();
        doReturn(new SeedreamClient.Created("remote-identity", result)).when(client).create(any(), any(),
                eq(SeedreamClient.ORIGIN), eq("synthetic-key"), any(), eq("local-request"));
        assertThat(adapter.preflightFailure(context)).isNull();
        assertThat(adapter.submit(context)).isEqualTo(new Submission.AcceptedResults("remote-identity", result));
        verify(catalog, org.mockito.Mockito.atLeastOnce()).snapshotAt(capability, 7, connection, 4);
        verify(cipher, org.mockito.Mockito.atLeastOnce()).decryptMedia(eq(connection), eq(4), any(CredentialCipher.Encrypted.class));
        verify(references).load(eq(context), any(), any(), eq(false));
        verify(references).load(eq(context), any(), any(), eq(true));
    }

    @Test void failedPreparationAndUnknownSubmissionRemainSeparate() {
        var context = context();
        doThrow(new SeedreamClient.Uncertain(SeedreamClient.CREATE_UNCERTAIN)).when(client)
                .create(any(), any(), anyString(), anyString(), any(), anyString());
        assertThat(adapter.submit(context)).isEqualTo(new Submission.Unknown(SeedreamClient.CREATE_UNCERTAIN));
        when(references.load(eq(context), any(), any(), eq(true))).thenThrow(new IllegalArgumentException("synthetic invalid ref"));
        assertThat(adapter.submit(context)).isEqualTo(new Submission.Rejected("MEDIA_REFERENCE_PREPARATION_FAILED"));
    }

    @Test void recoveryReadsInternalInlineResultWithoutCredentialsOrAnotherPaidRequest() throws Exception {
        var context = context();
        var result = manifest().results().getFirst();
        try (var payload = adapter.downloadResult(context, result)) {
            assertThat(payload.declaredContentType()).isEqualTo("image/jpeg");
            assertThat(payload.stream().readAllBytes()).isNotEmpty();
        }
        assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Blocked("SEEDREAM_HAS_NO_STATUS_ENDPOINT"));
        verify(cipher, never()).decryptMedia(any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(client, never()).create(any(), any(), anyString(), anyString(), any(), anyString());
        var external = new ProviderResultManifest.Result(0, result.nodeId(), RunningHubDefinition.OutputKind.IMAGE, true, "https://example.org/image.jpg");
        assertThatThrownBy(() -> adapter.downloadResult(context, external)).isInstanceOf(NativeMediaFailure.class);
    }

    @Test void rejectsProfileOrParameterMutationBeforeSendingAnything() {
        var context = context();
        ((ObjectNode) context.lease().input().at("/mediaInput/providerParameters/native")).put("model", SeedreamModels.FLASH);
        assertThat(adapter.preflightFailure(context)).isEqualTo("PROVIDER_PROTOCOL_INVALID");
        assertThat(adapter.submit(context)).isEqualTo(new Submission.Rejected("PROVIDER_PROTOCOL_INVALID"));
        verify(client, never()).create(any(), any(), anyString(), anyString(), any(), anyString());
    }

    private ProviderResultManifest manifest() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
        return new ProviderResultManifest(1, List.of(new ProviderResultManifest.Result(0,
                "seedream:GENERATE:" + SeedreamModels.PRO + ":0", RunningHubDefinition.OutputKind.IMAGE, true,
                "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()))), null);
    }

    private AttemptContext context() {
        var binding = new MediaCapabilityBinding(connection, 4, capability, 7, SeedreamModels.ADAPTER_ID, "a".repeat(64));
        var version = new JooqMediaCapabilityRepository.ConnectionVersion(connection, 4, SeedreamClient.ORIGIN, null,
                new byte[]{1}, new byte[]{2}, 1, "masked");
        ObjectNode settings = mapper.createObjectNode().put("model", SeedreamModels.PRO).put("operation", SeedreamModels.GENERATE);
        var snapshot = new JooqMediaCapabilityRepository.Snapshot(null, null, version, SeedreamModels.ADAPTER_ID,
                binding.mappingSha256(), mapper.createObjectNode().set("settings", settings).toString());
        when(catalog.snapshotAt(capability, 7, connection, 4)).thenReturn(Optional.of(snapshot));
        when(cipher.decryptMedia(eq(connection), eq(4), any(CredentialCipher.Encrypted.class))).thenReturn("synthetic-key");
        var selection = NativeMediaProfiles.resolve(SeedreamModels.ADAPTER_ID, settings);
        var values = NativeMediaProfiles.effectiveValues(selection, mapper.createObjectNode(), "Synthetic landscape", null, mapper);
        ObjectNode input = mapper.createObjectNode();
        var mediaInput = input.putObject("mediaInput");
        mediaInput.putObject("parameters").set("dynamicValues", values.deepCopy());
        mediaInput.putObject("providerParameters").putObject("native").put("adapterId", SeedreamModels.ADAPTER_ID)
                .put("model", SeedreamModels.PRO).put("operation", SeedreamModels.GENERATE).set("values", values);
        mediaInput.putArray("images"); mediaInput.putArray("videos"); mediaInput.putArray("audios");
        Task task = mock(Task.class);
        when(task.kind()).thenReturn(Task.Kind.IMAGE_GENERATION); when(task.input()).thenReturn(input);
        var context = new AttemptContext(task, binding, owner, "local-request", "remote-original");
        when(references.load(eq(context), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Map.of());
        return context;
    }
}
