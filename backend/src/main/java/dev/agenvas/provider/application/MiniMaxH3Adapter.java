package dev.agenvas.provider.application;

import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.MiniMaxH3Protocol;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.provider.infrastructure.MiniMaxH3Client;
import dev.agenvas.provider.infrastructure.MiniMaxMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.domain.Task;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixed official H3 generation; task recovery only queries the original provider identity. */
@Component
public class MiniMaxH3Adapter implements MediaAdapter {
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final MiniMaxH3Client client;
    private final MiniMaxMediaDownloadPolicy downloads;
    private final MediaToolRunner mediaTools;
    private final ObjectMapper mapper;
    private final MiniMaxH3ReferenceLoader references;

    public MiniMaxH3Adapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            MiniMaxH3Client client, MiniMaxMediaDownloadPolicy downloads, MediaToolRunner mediaTools,
            ObjectMapper mapper, MiniMaxH3ReferenceLoader references) {
        this.catalog = catalog; this.cipher = cipher; this.client = client;
        this.downloads = downloads; this.mediaTools = mediaTools; this.mapper = mapper; this.references = references;
    }
    @Override public String adapterId() { return MiniMaxH3Protocol.ADAPTER_ID; }
    @Override public boolean supports(PortInput input) {
        return input.kind() == Task.Kind.VIDEO_GENERATION && input.durationSeconds() >= 4 && input.durationSeconds() <= 15;
    }
    @Override public String preflightFailure(AttemptContext context) {
        try { credential(snapshot(context)); }
        catch (RuntimeException unavailable) { return "MEDIA_CREDENTIAL_UNAVAILABLE"; }
        try { prepare(context, false); return null; }
        catch (dev.agenvas.shared.error.ApiProblemException invalid) {
            return Set.of("MEDIA_RELAY_REQUIRED", "MEDIA_RELAY_PUBLIC_ENDPOINT_REQUIRED").contains(invalid.code())
                    ? invalid.code() : "PROVIDER_UNSUPPORTED_INPUT";
        } catch (RuntimeException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
    }
    @Override public Submission submit(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        String key = credential(snapshot);
        tools.jackson.databind.node.ObjectNode body;
        try { body = prepare(context, true); }
        catch (RuntimeException invalid) { return new Submission.Rejected("MEDIA_REFERENCE_PREPARATION_FAILED"); }
        try { return new Submission.Accepted(client.create(snapshot.connectionVersion().origin(), key, body)); }
        catch (MiniMaxH3Client.Rejected rejected) { return new Submission.Rejected(ProviderFailureCodes.MINIMAX_CREATE_REJECTED); }
        catch (MiniMaxH3Client.Uncertain uncertain) { return new Submission.Unknown(ProviderFailureCodes.MINIMAX_CREATE_UNCERTAIN); }
    }
    private tools.jackson.databind.node.ObjectNode prepare(AttemptContext context, boolean submitting) {
        Task task = context.lease();
        String prompt = task.input().path("prompt").asText();
        String negative = task.input().path("negativePrompt").asText("");
        if (!negative.isBlank()) prompt += "\nAvoid: " + negative;
        var parameters = VideoGenerationParameters.parse(task.input().path("mediaInput").path("parameters"));
        String ratio = task.input().path("mediaInput").path("providerParameters").path("ratio").asText();
        return client.prepare(prompt, references.load(context, submitting), task.input().path("durationSeconds").asInt(-1),
                ratio, parameters.videoResolution());
    }
    @Override public Submission reconcile(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        try {
            var state = client.query(snapshot.connectionVersion().origin(), credential(snapshot), context.originalRequestId());
            return switch (state.status()) {
                case "queued", "running" -> new Submission.Pending(Instant.now().plusSeconds(5));
                case "failed", "cancelled" -> new Submission.Rejected(ProviderFailureCodes.MINIMAX_TASK_FAILED);
                case "succeeded" -> completed(context, snapshot, state);
                default -> new Submission.Blocked(ProviderFailureCodes.PROTOCOL_INVALID);
            };
        } catch (MiniMaxH3Client.TaskExpired expired) { return new Submission.Blocked(ProviderFailureCodes.MINIMAX_TASK_EXPIRED); }
        catch (MiniMaxH3Client.ProtocolFailure | InvalidMedia invalid) { return new Submission.Blocked(ProviderFailureCodes.PROTOCOL_INVALID); }
        catch (MiniMaxMediaDownloadPolicy.Rejected rejected) { return new Submission.Blocked(rejected.getMessage()); }
    }
    private Submission completed(AttemptContext context, Snapshot snapshot, MiniMaxH3Client.TaskState state) {
        if (state.videoUrl() == null || state.videoUrl().isBlank()) return new Submission.Blocked(ProviderFailureCodes.PROTOCOL_INVALID);
        try {
            return new Submission.Completed(downloadVideo(URI.create(state.videoUrl()), context.lease().input().path("durationSeconds").asInt(-1)));
        } catch (IllegalArgumentException invalid) { return new Submission.Blocked(ProviderFailureCodes.RESULT_URL_INVALID); }
        catch (MiniMaxMediaDownloadPolicy.Expired expired) {
            // Refresh only this accepted task's URL; a missing/expired result never resubmits generation.
            var refreshed = client.query(snapshot.connectionVersion().origin(), credential(snapshot), context.originalRequestId());
            if (!"succeeded".equals(refreshed.status()) || refreshed.videoUrl() == null || refreshed.videoUrl().equals(state.videoUrl()))
                return new Submission.Blocked(ProviderFailureCodes.MINIMAX_RESULT_EXPIRED);
            try { return new Submission.Completed(downloadVideo(URI.create(refreshed.videoUrl()), context.lease().input().path("durationSeconds").asInt(-1))); }
            catch (IllegalArgumentException | MiniMaxMediaDownloadPolicy.Expired invalid) { return new Submission.Blocked(ProviderFailureCodes.MINIMAX_RESULT_EXPIRED); }
        }
    }
    private MediaPayload downloadVideo(URI url, int expectedSeconds) {
        downloads.validate(url);
        Path directory;
        try {
            directory = Files.createTempDirectory("agenvas-minimax-result-");
        } catch (IOException unavailable) {
            throw new MiniMaxMediaDownloadPolicy.TechnicalFailure("MiniMax scratch unavailable");
        }
        Path raw = directory.resolve("result.mp4");
        try {
            try (OutputStream sink = Files.newOutputStream(raw)) {
                downloads.download(url, sink, 500L * 1024 * 1024);
            }
            probe(raw, expectedSeconds);
            InputStream file = Files.newInputStream(raw);
            return new MediaPayload(new FilterInputStream(file) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { cleanup(raw, directory); }
                }
            }, "video/mp4");
        } catch (IOException failure) {
            cleanup(raw, directory);
            throw new MiniMaxMediaDownloadPolicy.TechnicalFailure("MiniMax scratch write failed");
        } catch (MediaToolRunner.MediaToolException failure) {
            cleanup(raw, directory);
            if (failure.invalidInput()) throw new InvalidMedia();
            throw failure;
        } catch (RuntimeException failure) {
            cleanup(raw, directory);
            throw failure;
        }
    }

    private void probe(Path file, int expectedSeconds) {
        JsonNode details = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error",
                "-show_entries", "stream=codec_type:format=format_name,duration",
                "-of", "json", file.toString())));
        double duration = details.path("format").path("duration").asDouble(-1);
        String format = details.path("format").path("format_name").asText();
        boolean video = false;
        for (JsonNode stream : details.path("streams")) {
            video |= "video".equals(stream.path("codec_type").asText());
        }
        if (!video || !format.contains("mp4") || !Double.isFinite(duration)
                || expectedSeconds < 4 || expectedSeconds > 15
                || duration < expectedSeconds - 1.0 || duration > expectedSeconds + 1.5) {
            throw new InvalidMedia();
        }
    }

    private static void cleanup(Path raw, Path directory) {
        try {
            Files.deleteIfExists(raw);
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Scratch cleanup is best effort after the response stream has been closed.
        }
    }

    private static final class InvalidMedia extends RuntimeException {}

    private Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        Snapshot snapshot = catalog.snapshotAt(binding.capabilityId(),
                binding.capabilityVersion(), binding.connectionId(),
                binding.connectionVersion()).orElseThrow(() ->
                new IllegalStateException("Pinned media capability version is missing"));
        if (!adapterId().equals(snapshot.adapterId())
                || !binding.mappingSha256().equals(snapshot.mappingSha256())) {
            throw new IllegalStateException("Pinned MiniMax mapping differs");
        }
        return snapshot;
    }

    private String credential(Snapshot snapshot) {
        var version = snapshot.connectionVersion();
        if (version.credentialCiphertext() == null || version.credentialNonce() == null
                || version.credentialKeyVersion() == null) {
            throw new IllegalStateException("Pinned MiniMax credential is missing");
        }
        String key = cipher.decryptMedia(version.connectionId(), version.version(),
                new CredentialCipher.Encrypted(version.credentialCiphertext(),
                        version.credentialNonce(), version.credentialKeyVersion()));
        if (key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0)
            throw new IllegalStateException("Pinned MiniMax credential is invalid");
        return key;
    }

}
