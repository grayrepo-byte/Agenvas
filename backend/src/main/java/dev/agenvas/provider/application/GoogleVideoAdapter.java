package dev.agenvas.provider.application;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.GoogleVideoModels;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.GoogleVideoClient;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.domain.Task;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One immutable video request. Downloads and retries query the acknowledged ID and cannot create a new attempt. */
abstract class GoogleVideoAdapter implements MediaAdapter {
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final GoogleVideoClient client;
    private final GoogleVideoPreparedMedia references;
    private final MediaToolRunner tools;
    private final ObjectMapper mapper;

    GoogleVideoAdapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher, GoogleVideoClient client,
            GoogleVideoPreparedMedia references, MediaToolRunner tools, ObjectMapper mapper) {
        this.catalog = catalog; this.cipher = cipher; this.client = client;
        this.references = references; this.tools = tools; this.mapper = mapper;
    }
    @Override public boolean supports(PortInput input) { return input.kind() == Task.Kind.VIDEO_GENERATION; }
    @Override public String preflightFailure(AttemptContext context) {
        try {
            Snapshot snapshot = snapshot(context); credential(snapshot);
            Frozen frozen = frozen(context, snapshot);
            GoogleVideoClient.validatedOrigin(snapshot.connectionVersion().origin());
            client.build(frozen.selection().profile(), frozen.values(), references.load(context, frozen.selection(), frozen.values(), false));
            return null;
        } catch (NativeMediaFailure failed) { return failed.code(); }
        catch (RuntimeException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
    }
    @Override public Submission submit(AttemptContext context) {
        try {
            Snapshot snapshot = snapshot(context); String key = credential(snapshot);
            Frozen frozen = frozen(context, snapshot);
            var body = client.build(frozen.selection().profile(), frozen.values(), references.load(context, frozen.selection(), frozen.values(), true));
            return new Submission.Accepted(client.create(frozen.selection().profile(), snapshot.connectionVersion().origin(), key, body));
        } catch (GoogleVideoClient.Unknown unknown) { return new Submission.Unknown(unknown.code()); }
        catch (NativeMediaFailure rejected) { return new Submission.Rejected(rejected.code()); }
        catch (RuntimeException invalid) { return new Submission.Rejected("MEDIA_REFERENCE_PREPARATION_FAILED"); }
    }
    @Override public Submission reconcile(AttemptContext context) {
        try {
            Snapshot snapshot = snapshot(context); Frozen frozen = frozen(context, snapshot); String key = credential(snapshot);
            var state = client.query(frozen.selection().profile(), snapshot.connectionVersion().origin(), key, context.originalRequestId());
            if ("pending".equals(state.status())) return new Submission.Pending(Instant.now().plusSeconds(10));
            if ("failed".equals(state.status())) return new Submission.Rejected(GoogleVideoClient.TASK_FAILED);
            if (!"completed".equals(state.status()) || state.video() == null) throw new NativeMediaFailure(ProviderFailureCodes.PROTOCOL_INVALID);
            if (state.video().fileName() != null && !client.fileActive(snapshot.connectionVersion().origin(), key, state.video().fileName()))
                return new Submission.Pending(Instant.now().plusSeconds(5));
            return new Submission.Completed(payload(context, frozen, snapshot, key, state.video()));
        } catch (NativeMediaFailure blocked) { return new Submission.Blocked(blocked.code()); }
    }

    private MediaPayload payload(AttemptContext context, Frozen frozen, Snapshot snapshot, String key, GoogleVideoClient.Video video) {
        Path directory;
        try { directory = Files.createTempDirectory("agenvas-google-video-"); }
        catch (IOException unavailable) { throw new GoogleVideoClient.TechnicalFailure(); }
        Path file = directory.resolve("result.mp4");
        try {
            if (video.bytes() != null) Files.write(file, video.bytes());
            else try (var target = Files.newOutputStream(file)) { client.download(snapshot.connectionVersion().origin(), key, video.fileName(), target); }
            validateResult(frozen, file);
            JsonNode metadata = null;
            if (GoogleVideoModels.veo(frozen.selection().profile()) && video.fileName() != null)
                metadata = mapper.createObjectNode().put("adapterId", adapterId()).put("model", frozen.selection().profile().model())
                        .put("connectionId", context.binding().connectionId().toString()).put("connectionVersion", context.binding().connectionVersion())
                        .put("fileName", video.fileName());
            return new MediaPayload(new FilterInputStream(Files.newInputStream(file)) {
                @Override public void close() throws IOException { try { super.close(); } finally { cleanup(file, directory); } }
            }, "video/mp4", metadata);
        } catch (IOException unavailable) { cleanup(file, directory); throw new GoogleVideoClient.TechnicalFailure(); }
        catch (RuntimeException failed) { cleanup(file, directory); throw failed; }
    }
    private void validateResult(Frozen frozen, Path file) {
        JsonNode probe;
        try {
            probe = mapper.readTree(tools.ffprobe(List.of("-v", "error", "-show_entries",
                    "stream=codec_type,width,height,avg_frame_rate:format=format_name,duration", "-of", "json", file.toString())));
        } catch (MediaToolRunner.MediaToolException failure) {
            if (failure.invalidInput()) throw protocol();
            throw failure; // A missing, interrupted or timed-out local tool remains retryable.
        } catch (tools.jackson.core.JacksonException malformed) { throw protocol(); }
        double seconds = probe.path("format").path("duration").asDouble(-1);
        boolean video = false, audio = false;
        for (JsonNode stream : probe.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText())) {
                if (video || stream.path("width").asInt(0) < 1 || stream.path("height").asInt(0) < 1) throw protocol();
                video = true;
            }
            if ("audio".equals(stream.path("codec_type").asText())) audio = true;
        }
        if (!video || !audio || !probe.path("format").path("format_name").asText().contains("mp4")
                || !Double.isFinite(seconds) || seconds < 1.5) throw protocol();
        var p = frozen.selection().profile();
        if (GoogleVideoModels.veo(p)) {
            if (GoogleVideoModels.extension(p)) { if (seconds > 148.5) throw protocol(); }
            else if (Math.abs(seconds - frozen.values().path("durationSeconds").asInt(8)) > 1.5) throw protocol();
        } else if (seconds > (GoogleVideoModels.extension(p) ? 20.5 : 10.5)) throw protocol();
    }
    private static NativeMediaFailure protocol() { return new NativeMediaFailure(ProviderFailureCodes.PROTOCOL_INVALID); }
    private static void cleanup(Path file, Path directory) { try { Files.deleteIfExists(file); Files.deleteIfExists(directory); } catch (IOException ignored) { /* scratch is never a project asset */ } }

    private Frozen frozen(AttemptContext context, Snapshot snapshot) {
        JsonNode nativeValues = context.lease().input().at("/mediaInput/providerParameters/native");
        if (!nativeValues.isObject() || !nativeValues.propertyNames().equals(Set.of("adapterId", "model", "operation", "values"))
                || !adapterId().equals(nativeValues.path("adapterId").asText()) || context.lease().kind() != Task.Kind.VIDEO_GENERATION) throw protocol();
        var selection = NativeMediaProfiles.resolve(adapterId(), nativeValues);
        var configured = NativeMediaProfiles.resolve(adapterId(), mapper.readTree(snapshot.specJson()).path("settings"));
        JsonNode values = nativeValues.path("values");
        if (!selection.profile().equals(configured.profile())
                || !values.equals(context.lease().input().at("/mediaInput/parameters/dynamicValues"))) throw protocol();
        NativeMediaProfiles.validateValues(selection, values, true);
        return new Frozen(selection, values);
    }
    private Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        var snapshot = catalog.snapshotAt(binding.capabilityId(), binding.capabilityVersion(), binding.connectionId(), binding.connectionVersion())
                .orElseThrow(() -> new IllegalStateException("Pinned Google video capability is missing"));
        if (!adapterId().equals(binding.adapterId()) || !adapterId().equals(snapshot.adapterId())
                || !binding.mappingSha256().equals(snapshot.mappingSha256())) throw protocol();
        return snapshot;
    }
    private String credential(Snapshot snapshot) {
        var version = snapshot.connectionVersion();
        try {
            if (version.credentialCiphertext() == null || version.credentialNonce() == null || version.credentialKeyVersion() == null) throw protocol();
            String key = cipher.decryptMedia(version.connectionId(), version.version(), new CredentialCipher.Encrypted(
                    version.credentialCiphertext(), version.credentialNonce(), version.credentialKeyVersion()));
            if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) throw protocol();
            return key;
        } catch (RuntimeException invalid) { throw new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE"); }
    }
    private record Frozen(NativeMediaProfiles.Selection selection, JsonNode values) {}
}
