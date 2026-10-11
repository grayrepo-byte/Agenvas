package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
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

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.domain.WanModels;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.WanClient;
import dev.agenvas.provider.infrastructure.WanMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WanProtocolAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WanModels models = new WanModels();
    private final NativeMediaProtocol.Profile profile = models.profiles().getFirst();
    private final JooqMediaCapabilityRepository catalog = mock(JooqMediaCapabilityRepository.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final WanClient client = spy(new WanClient(mapper, URI.create("http://127.0.0.1:65534")));
    private final WanMediaDownloadPolicy downloads = mock(WanMediaDownloadPolicy.class);
    private final NativeMediaReferences references = mock(NativeMediaReferences.class);
    private final WanImageAdapter adapter = new WanImageAdapter(catalog, cipher, client, downloads, mapper, references);
    private final UUID owner = UUID.randomUUID(), connection = UUID.randomUUID(), capability = UUID.randomUUID();
    private final String origin = "https://llm-synthetic.cn-beijing.maas.aliyuncs.com";
    @Test void acceptedFrozenProfileAndCredentialVersionRemainPinnedDuringReconciliation() {
        var context = context(mapper.createObjectNode().put("n", 2));
        doReturn(state("one.png", "two.png")).when(client).query(profile, origin, "synthetic-key", "remote-original");
        var result = (Submission.CompletedResults) adapter.reconcile(context);
        assertThat(result.manifest().results()).extracting(ProviderResultManifest.Result::ordinal).containsExactly(0, 1);
        assertThat(result.manifest().results()).extracting(ProviderResultManifest.Result::nodeId)
                .containsExactly("wan:wan2.7-image:choice0:content0", "wan:wan2.7-image:choice0:content1");
        verify(catalog).snapshotAt(capability, 7, connection, 4);
        verify(cipher).decryptMedia(eq(connection), eq(4), any(CredentialCipher.Encrypted.class));
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }
    @Test void preflightValidatesFrozenParametersAndNeverPosts() {
        var context = context(mapper.createObjectNode());
        when(references.load(any(), any(), any(), eq(false))).thenReturn(Map.of());
        assertThat(adapter.preflightFailure(new AttemptContext(context.lease(), context.binding(), owner, null, null))).isNull();
        ((ObjectNode) context.lease().input().at("/mediaInput/providerParameters/native")).put("model", "wan2.7-image-pro");
        assertThat(adapter.preflightFailure(context)).isEqualTo("PROVIDER_UNSUPPORTED_INPUT");
        ((ObjectNode) context.lease().input().at("/mediaInput/providerParameters/native")).put("model", profile.model());
        ((ObjectNode) context.lease().input().at("/mediaInput/parameters/dynamicValues")).put("n", 4);
        assertThat(adapter.preflightFailure(context)).isEqualTo("PROVIDER_UNSUPPORTED_INPUT");
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }
    @Test void uncertainCreateAndPermanentQueryFailuresHaveDifferentRecoveryOutcomes() {
        var context = context(mapper.createObjectNode());
        when(references.load(eq(context), any(), any(), eq(true))).thenReturn(Map.of());
        doThrow(new WanClient.Uncertain()).when(client).create(eq(profile), eq(origin), eq("synthetic-key"), any());
        assertThat(adapter.submit(context)).isEqualTo(new Submission.Unknown(WanClient.CREATE_UNCERTAIN));
        doThrow(new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE")).when(client).query(profile, origin, "synthetic-key", "remote-original");
        assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Blocked("MEDIA_CREDENTIAL_UNAVAILABLE"));
    }
    @Test void expiredSignedUrlRefreshUsesOnlyOriginalTaskAndSameArrayIdentity() throws Exception {
        var context = context(mapper.createObjectNode().put("n", 2));
        doReturn(state("one.png", "expired.png"), state("one-new.png", "fresh.png"))
                .when(client).query(profile, origin, "synthetic-key", "remote-original");
        var manifest = ((Submission.CompletedResults) adapter.reconcile(context)).manifest();
        URI stale = URI.create(url("expired.png")), fresh = URI.create(url("fresh.png"));
        when(downloads.download(eq(stale), any(OutputStream.class), eq(false))).thenThrow(new WanMediaDownloadPolicy.Expired());
        when(downloads.download(eq(fresh), any(OutputStream.class), eq(false))).thenAnswer(call -> {
            ((OutputStream) call.getArgument(1)).write(new byte[] {1, 2, 3}); return "image/png";
        });
        try (var payload = adapter.downloadResult(context, manifest.results().get(1))) { assertThat(payload.stream().readAllBytes()).containsExactly(1, 2, 3); }
        verify(client, org.mockito.Mockito.times(2)).query(profile, origin, "synthetic-key", "remote-original");
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }
    @Test void sequenceArchivesActualCountWithinMaximumAndStandardCountIsExact() {
        var context = context(mapper.createObjectNode().put("enable_sequential", true).put("n", 12));
        doReturn(state("one.png", "two.png")).when(client).query(profile, origin, "synthetic-key", "remote-original");
        assertThat(((Submission.CompletedResults) adapter.reconcile(context)).manifest().results()).hasSize(2);
        context = context(mapper.createObjectNode().put("n", 1));
        assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Blocked("PROVIDER_PROTOCOL_INVALID"));
        context = context(mapper.createObjectNode().put("enable_sequential", true).put("n", 1));
        assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Blocked("PROVIDER_PROTOCOL_INVALID"));
    }
    @Test void pngAlphaIsRejectedBeforeCreateEvenForFullyOpaqueRgbaPixels() throws Exception {
        UUID version = UUID.randomUUID();
        var context = context(mapper.createObjectNode().put("image_1", version.toString()));
        var stream = new ByteArrayOutputStream();
        var raster = new BufferedImage(240, 240, BufferedImage.TYPE_INT_ARGB);
        var graphics = raster.createGraphics(); graphics.setColor(java.awt.Color.BLACK); graphics.fillRect(0, 0, 240, 240); graphics.dispose();
        ImageIO.write(raster, "png", stream);
        byte[] bytes = stream.toByteArray();
        var asset = new Asset(UUID.randomUUID(), UUID.randomUUID(), Asset.MediaKind.IMAGE, "synthetic", "image/png", bytes.length,
                "a".repeat(64), 240, 240, null, null, null, null, Instant.now());
        when(references.load(eq(context), any(), any(), eq(true))).thenReturn(Map.of("image_1", new NativeMediaReferences.Input(asset, mapper.createObjectNode(), bytes, null)));
        assertThat(adapter.submit(context)).isEqualTo(new Submission.Rejected("MEDIA_REFERENCE_PREPARATION_FAILED"));
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }
    private WanClient.State state(String... names) {
        var outputs = new java.util.ArrayList<WanClient.Output>();
        for (int i = 0; i < names.length; i++) outputs.add(new WanClient.Output("choice0:content" + i, url(names[i])));
        return new WanClient.State("SUCCEEDED", List.copyOf(outputs), mapper.createObjectNode().put("image_count", names.length));
    }
    private static String url(String name) { return "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/" + name; }
    private AttemptContext context(ObjectNode overrides) {
        var binding = new MediaCapabilityBinding(connection, 4, capability, 7, WanModels.IMAGE_ADAPTER_ID, "a".repeat(64));
        var version = new JooqMediaCapabilityRepository.ConnectionVersion(connection, 4, origin, null, new byte[] {1}, new byte[] {2}, 1, "masked");
        var spec = mapper.createObjectNode(); spec.putObject("settings").put("model", profile.model()).put("operation", profile.operation());
        var snapshot = new JooqMediaCapabilityRepository.Snapshot(null, null, version, WanModels.IMAGE_ADAPTER_ID, binding.mappingSha256(), mapper.writeValueAsString(spec));
        when(catalog.snapshotAt(capability, 7, connection, 4)).thenReturn(Optional.of(snapshot));
        when(cipher.decryptMedia(eq(connection), eq(4), any(CredentialCipher.Encrypted.class))).thenReturn("synthetic-key");
        var parameters = mapper.createObjectNode().set(NativeMediaProtocol.VALUES_PROPERTY, overrides);
        var values = NativeMediaProfiles.effectiveValues(new NativeMediaProfiles.Selection(models, profile), parameters, "A synthetic landscape", null, mapper);
        var input = mapper.createObjectNode(); var mediaInput = input.putObject("mediaInput");
        mediaInput.putObject("parameters").set(NativeMediaProtocol.VALUES_PROPERTY, values.deepCopy());
        mediaInput.putObject("providerParameters").putObject("native").put("adapterId", profile.adapterId()).put("model", profile.model()).put("operation", profile.operation()).set("values", values);
        Task task = mock(Task.class); when(task.kind()).thenReturn(profile.kind()); when(task.input()).thenReturn(input);
        return new AttemptContext(task, binding, owner, "synthetic-request-key", "remote-original");
    }
}
