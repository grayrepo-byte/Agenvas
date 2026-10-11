package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class KlingProtocolAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JooqMediaCapabilityRepository catalog = mock(JooqMediaCapabilityRepository.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final KlingClient client = spy(new KlingClient(mapper, URI.create("http://127.0.0.1:65534")));
    private final KlingMediaDownloadPolicy downloads = mock(KlingMediaDownloadPolicy.class);
    private final KlingPreparedMedia media = mock(KlingPreparedMedia.class);
    private final KlingImageAdapter adapter = new KlingImageAdapter(catalog, cipher, client, downloads, mapper, media);
    private final UUID owner = UUID.randomUUID(), connection = UUID.randomUUID(), capability = UUID.randomUUID();
    private final String origin = KlingClient.ORIGIN;
    private final KlingModels.Profile profile = KlingModels.resolve("kling-v3", "GENERATE", Task.Kind.IMAGE_GENERATION);

    @Test void pinsCredentialAndModelAndFreezesDenseOutputOrdinalsSeparatelyFromProviderIndices() {
        var context = context(profile, mapper.createObjectNode().put("n", 2));
        doReturn(
                new KlingClient.State("succeed", List.of(new KlingClient.Output(2, "https://h1.inkwai.com/two.png"),
                        new KlingClient.Output(1, "https://h1.inkwai.com/one.png"))))
                .when(client).query(profile, origin, "synthetic-key", "remote-original", "single");
        var result = (Submission.CompletedResults) adapter.reconcile(context);
        assertThat(result.manifest().results()).extracting(ProviderResultManifest.Result::ordinal).containsExactly(0, 1);
        assertThat(result.manifest().results()).extracting(ProviderResultManifest.Result::nodeId)
                .containsExactly("kling:GENERATE:kling-v3:1", "kling:GENERATE:kling-v3:2");
        assertThat(result.manifest().results()).extracting(ProviderResultManifest.Result::primary).containsExactly(true, false);
        verify(catalog).snapshotAt(capability, 7, connection, 4);
        verify(cipher).decryptMedia(eq(connection), eq(4), any(CredentialCipher.Encrypted.class));
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }

    @Test void preflightValidatesFrozenProfileAndValuesBeforeAnyGeneration() {
        var context = context(profile, mapper.createObjectNode());
        when(media.load(eq(context), eq(profile), any(), eq(false))).thenReturn(Map.of());
        assertThat(adapter.preflightFailure(context)).isNull();
        verify(client, never()).create(any(), anyString(), anyString(), any());
        ((ObjectNode) context.lease().input().at("/mediaInput/providerParameters/kling")).put("model", "kling-image-o1");
        assertThat(adapter.preflightFailure(context)).isEqualTo("PROVIDER_UNSUPPORTED_INPUT");
        ((ObjectNode) context.lease().input().at("/mediaInput/providerParameters/kling")).put("model", "kling-v3");
        ((ObjectNode) context.lease().input().at("/mediaInput/parameters/dynamicValues")).put("n", 9);
        assertThat(adapter.preflightFailure(context)).isEqualTo("PROVIDER_UNSUPPORTED_INPUT");
    }

    @Test void lostCreateResponseIsUnknownAndQueryAuthorizationFailureIsBlocked() {
        var context = context(profile, mapper.createObjectNode());
        when(media.load(eq(context), eq(profile), any(), eq(true))).thenReturn(Map.of());
        doThrow(new KlingClient.Uncertain()).when(client).create(eq(profile), eq(origin), eq("synthetic-key"), any());
        assertThat(adapter.submit(context)).isInstanceOf(Submission.Unknown.class);
        doThrow(new KlingClient.CredentialUnavailable()).when(client).query(profile, origin, "synthetic-key", "remote-original", "single");
        assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Blocked("MEDIA_CREDENTIAL_UNAVAILABLE"));
    }

    @Test void refreshesOnlyOriginalTaskAndMatchesProviderIndexAfterUrlExpiry() throws Exception {
        var context = context(profile, mapper.createObjectNode().put("n", 2));
        String stale = "https://h1.inkwai.com/expired.png", fresh = "https://h1.inkwai.com/fresh.png";
        doReturn(
                new KlingClient.State("succeed", List.of(new KlingClient.Output(2, stale), new KlingClient.Output(1, "https://h1.inkwai.com/one.png"))),
                new KlingClient.State("succeed", List.of(new KlingClient.Output(2, fresh), new KlingClient.Output(1, "https://h1.inkwai.com/one-new.png"))))
                .when(client).query(profile, origin, "synthetic-key", "remote-original", "single");
        var manifest = ((Submission.CompletedResults) adapter.reconcile(context)).manifest();
        when(downloads.download(eq(URI.create(stale)), any(OutputStream.class), eq(false), anyLong()))
                .thenThrow(new KlingMediaDownloadPolicy.Expired());
        when(downloads.download(eq(URI.create(fresh)), any(OutputStream.class), eq(false), anyLong())).thenAnswer(call -> {
            ((OutputStream) call.getArgument(1)).write(new byte[] {1, 2, 3}); return "image/png";
        });
        try (var payload = adapter.downloadResult(context, manifest.results().get(1))) {
            assertThat(payload.stream().readAllBytes()).containsExactly(1, 2, 3);
        }
        verify(client, org.mockito.Mockito.times(2)).query(profile, origin, "synthetic-key", "remote-original", "single");
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }

    @Test void selectedWatermarkIsArchivedAndMissingVariantDoesNotFallBackToOriginal() {
        var context = context(profile, mapper.createObjectNode().put("watermark", true));
        String original = "https://h1.inkwai.com/original.png", marked = "https://h1.inkwai.com/watermarked.png";
        doReturn(
                new KlingClient.State("succeed", List.of(new KlingClient.Output(0, original, marked, null))),
                new KlingClient.State("succeed", List.of(new KlingClient.Output(0, original))))
                .when(client).query(profile, origin, "synthetic-key", "remote-original", "single");
        var manifest = ((Submission.CompletedResults) adapter.reconcile(context)).manifest();
        assertThat(manifest.results()).singleElement().extracting(ProviderResultManifest.Result::url).isEqualTo(marked);
        assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Blocked("PROVIDER_PROTOCOL_INVALID"));
        verify(downloads, never()).validate(URI.create(original));
    }

    @Test void rejectsMissingDuplicateOrUnexpectedOutputCountsBeforeArchival() {
        var context = context(profile, mapper.createObjectNode().put("n", 2));
        for (var outputs : List.of(List.of(new KlingClient.Output(0, "https://h1.inkwai.com/one.png")),
                List.of(new KlingClient.Output(0, "https://h1.inkwai.com/one.png"), new KlingClient.Output(0, "https://h1.inkwai.com/two.png")))) {
            doReturn(new KlingClient.State("succeed", outputs))
                    .when(client).query(profile, origin, "synthetic-key", "remote-original", "single");
            assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Blocked("PROVIDER_PROTOCOL_INVALID"));
        }
    }

    @Test void subjectCompletionArchivesEachReturnedTripleAndSeriesAutoKeepsActualCount() {
        var completion = KlingModels.resolve("", "SUBJECT_COMPLETION", Task.Kind.IMAGE_GENERATION);
        var context = context(completion, mapper.createObjectNode().put("image_1", UUID.randomUUID().toString()));
        doReturn(new KlingClient.State("succeed", List.of(new KlingClient.Output(3, "https://h1.inkwai.com/front.png"),
                new KlingClient.Output(4, "https://h1.inkwai.com/side.png"), new KlingClient.Output(5, "https://h1.inkwai.com/back.png"))))
                .when(client).query(completion, origin, "synthetic-key", "remote-original", "single");
        assertThat(((Submission.CompletedResults) adapter.reconcile(context)).manifest().results()).hasSize(3);
        var series = KlingModels.resolve("kling-v3-omni", "GENERATE", Task.Kind.IMAGE_GENERATION);
        context = context(series, mapper.createObjectNode().put("result_type", "series").put("series_amount", "auto"));
        doReturn(new KlingClient.State("succeed", List.of(new KlingClient.Output(1, "https://h1.inkwai.com/scene-one.png"),
                new KlingClient.Output(2, "https://h1.inkwai.com/scene-two.png"))))
                .when(client).query(series, origin, "synthetic-key", "remote-original", "series");
        assertThat(((Submission.CompletedResults) adapter.reconcile(context)).manifest().results()).hasSize(2);
    }

    private AttemptContext context(KlingModels.Profile selected, ObjectNode overrides) {
        var binding = new MediaCapabilityBinding(connection, 4, capability, 7, KlingModels.IMAGE_ADAPTER_ID, "a".repeat(64));
        var version = new JooqMediaCapabilityRepository.ConnectionVersion(connection, 4, origin, null,
                new byte[] {1}, new byte[] {2}, 1, "masked");
        var settings = mapper.createObjectNode().put("model", selected.model()).put("operation", selected.operation().name());
        var spec = mapper.createObjectNode().set("settings", settings);
        var snapshot = new JooqMediaCapabilityRepository.Snapshot(null, null, version, KlingModels.IMAGE_ADAPTER_ID,
                binding.mappingSha256(), mapper.writeValueAsString(spec));
        when(catalog.snapshotAt(capability, 7, connection, 4)).thenReturn(Optional.of(snapshot));
        when(cipher.decryptMedia(eq(connection), eq(4), any(CredentialCipher.Encrypted.class))).thenReturn("synthetic-key");
        var parameters = mapper.createObjectNode().set(KlingModels.VALUES_PROPERTY, overrides);
        var values = KlingModels.effectiveValues(selected, parameters, "A synthetic landscape", null, mapper);
        var input = mapper.createObjectNode();
        var mediaInput = input.putObject("mediaInput");
        mediaInput.putObject("parameters").set(KlingModels.VALUES_PROPERTY, values.deepCopy());
        mediaInput.putObject("providerParameters").putObject("kling").put("model", selected.model())
                .put("operation", selected.operation().name()).set("values", values);
        mediaInput.putArray("images"); mediaInput.putArray("videos"); mediaInput.putArray("audios");
        Task task = mock(Task.class);
        when(task.kind()).thenReturn(Task.Kind.IMAGE_GENERATION); when(task.input()).thenReturn(input);
        return new AttemptContext(task, binding, owner, "synthetic-request-key", "remote-original");
    }
}
