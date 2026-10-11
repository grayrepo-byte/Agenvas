package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.GoogleVideoModels;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.GoogleVideoClient;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.domain.Task;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GoogleVideoAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JooqMediaCapabilityRepository catalog = mock(JooqMediaCapabilityRepository.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final GoogleVideoClient client = spy(new GoogleVideoClient(mapper, URI.create("http://127.0.0.1:65534")));
    private final GoogleVideoPreparedMedia references = mock(GoogleVideoPreparedMedia.class);
    private final MediaToolRunner tools = mock(MediaToolRunner.class);
    private final GoogleVeoAdapter veo = new GoogleVeoAdapter(catalog, cipher, client, references, tools, mapper);
    private final GoogleOmniVideoAdapter omni = new GoogleOmniVideoAdapter(catalog, cipher, client, references, tools, mapper);
    private final UUID owner = UUID.randomUUID(), connection = UUID.randomUUID(), capability = UUID.randomUUID();
    private static final byte[] SYNTHETIC_MEDIA = new byte[]{0, 0, 0, 12, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    private static final String VALID_PROBE = "{\"format\":{\"format_name\":\"mov,mp4,m4a\",\"duration\":\"8\"},\"streams\":[{\"codec_type\":\"video\",\"width\":1280,\"height\":720},{\"codec_type\":\"audio\"}]}";

    @Test void pinsModelCredentialVersionAndExactNativeValuesBeforeSubmission() {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        var context = context(p, mapper.createObjectNode());
        when(references.load(eq(context), any(), any(), eq(false))).thenReturn(Map.of());
        assertThat(veo.preflightFailure(context)).isNull();
        verify(catalog).snapshotAt(capability, 4, connection, 7);
        verify(cipher).decryptMedia(eq(connection), eq(7), any(CredentialCipher.Encrypted.class));
        verify(client, never()).create(any(), anyString(), anyString(), any());
        ((ObjectNode) context.lease().input().at("/mediaInput/parameters/dynamicValues")).put("resolution", "4k");
        assertThat(veo.preflightFailure(context)).isEqualTo(ProviderFailureCodes.PROTOCOL_INVALID);
    }
    @Test void uncertainCreateNeverFallsBackAndKnownCredentialFailureIsRejectedBeforePost() {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        var context = context(p, mapper.createObjectNode());
        when(references.load(eq(context), any(), any(), eq(true))).thenReturn(Map.of());
        doThrow(new GoogleVideoClient.Unknown(ProviderFailureCodes.RESPONSE_LOST)).when(client).create(eq(p), eq(GoogleVideoClient.ORIGIN), eq("synthetic-key"), any());
        assertThat(veo.submit(context)).isEqualTo(new Submission.Unknown(ProviderFailureCodes.RESPONSE_LOST));
        verify(client, times(1)).create(eq(p), eq(GoogleVideoClient.ORIGIN), eq("synthetic-key"), any());
        when(cipher.decryptMedia(eq(connection), eq(7), any(CredentialCipher.Encrypted.class))).thenThrow(new IllegalStateException("synthetic missing"));
        assertThat(veo.submit(context)).isEqualTo(new Submission.Rejected("MEDIA_CREDENTIAL_UNAVAILABLE"));
        verify(client, times(1)).create(any(), anyString(), anyString(), any());
    }
    @Test void uriRecoveryQueriesOnlyOriginalIdentityAndStoresSafeExtensionProvenance() throws Exception {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        var context = context(p, mapper.createObjectNode());
        doReturn(new GoogleVideoClient.State("completed", new GoogleVideoClient.Video(null, "files/synthetic-video")))
                .when(client).query(p, GoogleVideoClient.ORIGIN, "synthetic-key", "operations/synthetic-original");
        doReturn(false, true).when(client).fileActive(GoogleVideoClient.ORIGIN, "synthetic-key", "files/synthetic-video");
        assertThat(veo.reconcile(context)).isInstanceOf(Submission.Pending.class);
        doAnswer(call -> { ((OutputStream) call.getArgument(3)).write(SYNTHETIC_MEDIA); return null; })
                .when(client).download(eq(GoogleVideoClient.ORIGIN), eq("synthetic-key"), eq("files/synthetic-video"), any(OutputStream.class));
        when(tools.ffprobe(anyList())).thenReturn(VALID_PROBE);
        var completed = (Submission.Completed) veo.reconcile(context);
        try (var payload = completed.payload()) {
            assertThat(payload.stream().readAllBytes()).isEqualTo(SYNTHETIC_MEDIA);
            assertThat(payload.providerMetadata().propertyNames()).containsExactlyInAnyOrder("adapterId", "model", "connectionId", "connectionVersion", "fileName");
            assertThat(payload.providerMetadata().path("fileName").asText()).isEqualTo("files/synthetic-video");
            assertThat(payload.providerMetadata().toString()).doesNotContain("synthetic-key", "https://", "uri");
        }
        verify(client, times(2)).query(p, GoogleVideoClient.ORIGIN, "synthetic-key", "operations/synthetic-original");
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }
    @Test void inlineInteractionRecoveryKeepsVideoOutOfDatabaseAndCleansScratch() throws Exception {
        var p = profile(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "GENERATE");
        var context = context(p, mapper.createObjectNode());
        doReturn(new GoogleVideoClient.State("completed", new GoogleVideoClient.Video(SYNTHETIC_MEDIA, null)))
                .when(client).query(p, GoogleVideoClient.ORIGIN, "synthetic-key", "v1_synthetic-original");
        final Path[] scratch = new Path[1];
        when(tools.ffprobe(anyList())).thenAnswer(call -> { List<String> args = call.getArgument(0); scratch[0] = Path.of(args.getLast()); return VALID_PROBE; });
        var completed = (Submission.Completed) omni.reconcile(context);
        try (var payload = completed.payload()) {
            assertThat(payload.stream().readAllBytes()).isEqualTo(SYNTHETIC_MEDIA);
            assertThat(payload.providerMetadata()).isNull();
            assertThat(Files.exists(scratch[0])).isTrue();
        }
        assertThat(Files.exists(scratch[0])).isFalse();
        verify(client, never()).download(anyString(), anyString(), anyString(), any());
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }
    @Test void permanentQueryAndMediaFailuresBlock() {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        var context = context(p, mapper.createObjectNode());
        doThrow(new NativeMediaFailure(GoogleVideoClient.TASK_EXPIRED)).when(client).query(p, GoogleVideoClient.ORIGIN, "synthetic-key", "operations/synthetic-original");
        assertThat(veo.reconcile(context)).isEqualTo(new Submission.Blocked(GoogleVideoClient.TASK_EXPIRED));
        doReturn(new GoogleVideoClient.State("completed", new GoogleVideoClient.Video(SYNTHETIC_MEDIA, null)))
                .when(client).query(p, GoogleVideoClient.ORIGIN, "synthetic-key", "operations/synthetic-original");
        when(tools.ffprobe(anyList())).thenReturn(VALID_PROBE.replace("\"8\"", "\"3\""));
        assertThat(veo.reconcile(context)).isEqualTo(new Submission.Blocked(ProviderFailureCodes.PROTOCOL_INVALID));
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }
    @Test void technicalDownloadFailureRetriesOnlyTheOriginalIdentityAndCleansPartialScratch() throws Exception {
        var p = profile(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        var context = context(p, mapper.createObjectNode());
        doReturn(new GoogleVideoClient.State("completed", new GoogleVideoClient.Video(null, "files/synthetic-video")))
                .when(client).query(p, GoogleVideoClient.ORIGIN, "synthetic-key", "operations/synthetic-original");
        doReturn(true).when(client).fileActive(GoogleVideoClient.ORIGIN, "synthetic-key", "files/synthetic-video");
        doThrow(new GoogleVideoClient.TechnicalFailure()).when(client)
                .download(eq(GoogleVideoClient.ORIGIN), eq("synthetic-key"), eq("files/synthetic-video"), any(OutputStream.class));
        assertThatThrownBy(() -> veo.reconcile(context)).isInstanceOf(GoogleVideoClient.TechnicalFailure.class);
        doAnswer(call -> { ((OutputStream) call.getArgument(3)).write(SYNTHETIC_MEDIA); return null; })
                .when(client).download(eq(GoogleVideoClient.ORIGIN), eq("synthetic-key"), eq("files/synthetic-video"), any(OutputStream.class));
        when(tools.ffprobe(anyList())).thenReturn(VALID_PROBE);
        try (var payload = ((Submission.Completed) veo.reconcile(context)).payload()) {
            assertThat(payload.stream().readAllBytes()).isEqualTo(SYNTHETIC_MEDIA);
        }
        verify(client, times(2)).query(p, GoogleVideoClient.ORIGIN, "synthetic-key", "operations/synthetic-original");
        verify(client, times(2)).download(eq(GoogleVideoClient.ORIGIN), eq("synthetic-key"), eq("files/synthetic-video"), any(OutputStream.class));
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }
    @Test void invalidEncodedMediaBlocksWhileUnavailableLocalProbeRemainsRetryable() {
        var p = profile(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "GENERATE");
        var context = context(p, mapper.createObjectNode());
        doReturn(new GoogleVideoClient.State("completed", new GoogleVideoClient.Video(SYNTHETIC_MEDIA, null)))
                .when(client).query(p, GoogleVideoClient.ORIGIN, "synthetic-key", "v1_synthetic-original");
        when(tools.ffprobe(anyList())).thenThrow(new MediaToolRunner.MediaToolException("synthetic invalid input", true, null));
        assertThat(omni.reconcile(context)).isEqualTo(new Submission.Blocked(ProviderFailureCodes.PROTOCOL_INVALID));
        doThrow(new MediaToolRunner.MediaToolException("synthetic unavailable", false, null)).when(tools).ffprobe(anyList());
        assertThatThrownBy(() -> omni.reconcile(context)).isInstanceOf(MediaToolRunner.MediaToolException.class);
        verify(client, never()).create(any(), anyString(), anyString(), any());
    }
    private NativeMediaProtocol.Profile profile(String adapter, String model, String operation) { return new NativeMediaProtocol.Profile(adapter, model, operation, Task.Kind.VIDEO_GENERATION); }
    private AttemptContext context(NativeMediaProtocol.Profile profile, ObjectNode overrides) {
        var binding = new MediaCapabilityBinding(connection, 7, capability, 4, profile.adapterId(), "a".repeat(64));
        var version = new JooqMediaCapabilityRepository.ConnectionVersion(connection, 7, GoogleVideoClient.ORIGIN, null, new byte[]{1}, new byte[]{2}, 1, "masked");
        var settings = mapper.createObjectNode().put("model", profile.model()).put("operation", profile.operation());
        var snapshot = new JooqMediaCapabilityRepository.Snapshot(null, null, version, profile.adapterId(), binding.mappingSha256(),
                mapper.createObjectNode().set("settings", settings).toString());
        when(catalog.snapshotAt(capability, 4, connection, 7)).thenReturn(Optional.of(snapshot));
        when(cipher.decryptMedia(eq(connection), eq(7), any(CredentialCipher.Encrypted.class))).thenReturn("synthetic-key");
        var selected = new NativeMediaProfiles.Selection(new GoogleVideoModels(), profile);
        var values = NativeMediaProfiles.effectiveValues(selected, mapper.createObjectNode().set("dynamicValues", overrides), "Synthetic landscape", null, mapper);
        var input = mapper.createObjectNode(); var media = input.putObject("mediaInput");
        media.putObject("parameters").set("dynamicValues", values.deepCopy());
        media.putObject("providerParameters").putObject("native").put("adapterId", profile.adapterId()).put("model", profile.model())
                .put("operation", profile.operation()).set("values", values);
        media.putArray("images"); media.putArray("videos"); media.putArray("audios");
        Task task = mock(Task.class); when(task.kind()).thenReturn(Task.Kind.VIDEO_GENERATION); when(task.input()).thenReturn(input);
        return new AttemptContext(task, binding, owner, "synthetic-request", GoogleVideoModels.veo(profile) ? "operations/synthetic-original" : "v1_synthetic-original");
    }
}
