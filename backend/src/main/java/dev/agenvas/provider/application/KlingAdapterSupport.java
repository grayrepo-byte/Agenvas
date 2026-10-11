package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ProviderFailureCodes;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared frozen credentials and result recovery; image/video request and task schemas stay distinct. */
abstract class KlingAdapterSupport implements MediaAdapter {
    protected final KlingClient client;
    protected final ObjectMapper mapper;
    protected final KlingReferenceLoader references;
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final KlingMediaDownloadPolicy downloads;

    KlingAdapterSupport(JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            KlingClient client, KlingMediaDownloadPolicy downloads, ObjectMapper mapper,
            KlingReferenceLoader references) {
        this.catalog = catalog; this.cipher = cipher; this.client = client;
        this.downloads = downloads; this.mapper = mapper; this.references = references;
    }

    protected abstract boolean video();
    protected abstract ObjectNode prepare(AttemptContext context, boolean submitting);
    protected void validateResult(AttemptContext context, Path file) {}

    @Override public String preflightFailure(AttemptContext context) {
        try { credential(snapshot(context)); }
        catch (RuntimeException unavailable) { return "MEDIA_CREDENTIAL_UNAVAILABLE"; }
        try { prepare(context, false); return null; }
        catch (RuntimeException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
    }

    @Override public Submission submit(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        String key = credential(snapshot);
        ObjectNode body;
        try { body = prepare(context, true); }
        catch (RuntimeException preparationFailed) { return new Submission.Rejected("MEDIA_REFERENCE_PREPARATION_FAILED"); }
        try {
            String id = video() ? client.createVideo(snapshot.connectionVersion().origin(), key, body)
                    : client.createImage(snapshot.connectionVersion().origin(), key, body);
            return new Submission.Accepted(id);
        } catch (KlingClient.Rejected rejected) { return new Submission.Rejected(ProviderFailureCodes.KLING_CREATE_REJECTED); }
        catch (KlingClient.Uncertain uncertain) { return new Submission.Unknown(ProviderFailureCodes.KLING_CREATE_UNCERTAIN); }
    }

    @Override public Submission reconcile(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        try {
            var state = query(context, snapshot);
            return switch (state.status()) {
                case "submitted", "processing" -> new Submission.Pending(Instant.now().plusSeconds(5));
                case "failed" -> new Submission.Rejected(ProviderFailureCodes.KLING_TASK_FAILED);
                case "succeed", "succeeded" -> completed(context, snapshot, state);
                default -> new Submission.Blocked(ProviderFailureCodes.PROTOCOL_INVALID);
            };
        } catch (KlingClient.TaskExpired expired) { return new Submission.Blocked(ProviderFailureCodes.KLING_TASK_EXPIRED); }
        catch (KlingClient.CredentialUnavailable unavailable) { return new Submission.Blocked("MEDIA_CREDENTIAL_UNAVAILABLE"); }
        catch (KlingClient.ProtocolFailure invalid) { return new Submission.Blocked(ProviderFailureCodes.PROTOCOL_INVALID); }
        catch (KlingMediaDownloadPolicy.Rejected rejected) { return new Submission.Blocked(rejected.getMessage()); }
    }

    private KlingClient.TaskState query(AttemptContext context, Snapshot snapshot) {
        String origin = snapshot.connectionVersion().origin(), key = credential(snapshot);
        return video() ? client.queryVideo(origin, key, context.originalRequestId())
                : client.queryImage(origin, key, context.originalRequestId());
    }

    private Submission completed(AttemptContext context, Snapshot snapshot, KlingClient.TaskState state) {
        try { return new Submission.Completed(download(context, URI.create(state.url()))); }
        catch (IllegalArgumentException invalid) { return new Submission.Blocked(ProviderFailureCodes.RESULT_URL_INVALID); }
        catch (KlingMediaDownloadPolicy.Expired expired) {
            // Refresh only this acknowledged task; expired URLs cannot authorize another generation.
            var refreshed = query(context, snapshot);
            if (!(video() ? "succeeded" : "succeed").equals(refreshed.status())
                    || refreshed.url() == null || refreshed.url().equals(state.url()))
                return new Submission.Blocked(ProviderFailureCodes.KLING_RESULT_EXPIRED);
            try { return new Submission.Completed(download(context, URI.create(refreshed.url()))); }
            catch (IllegalArgumentException | KlingMediaDownloadPolicy.Expired invalid) {
                return new Submission.Blocked(ProviderFailureCodes.KLING_RESULT_EXPIRED);
            }
        }
    }

    private MediaPayload download(AttemptContext context, URI url) {
        downloads.validate(url);
        Path directory;
        try { directory = Files.createTempDirectory("agenvas-kling-result-"); }
        catch (IOException unavailable) { throw new KlingMediaDownloadPolicy.TechnicalFailure(); }
        Path file = directory.resolve("result");
        try {
            String mime;
            try (OutputStream output = Files.newOutputStream(file)) {
                mime = downloads.download(url, output, video(), video() ? 500L * 1024 * 1024 : 50L * 1024 * 1024);
            }
            validateResult(context, file);
            return new MediaPayload(new FilterInputStream(Files.newInputStream(file)) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { cleanup(file, directory); }
                }
            }, mime);
        } catch (IOException unavailable) {
            cleanup(file, directory);
            throw new KlingMediaDownloadPolicy.TechnicalFailure();
        } catch (RuntimeException failure) { cleanup(file, directory); throw failure; }
    }

    private static void cleanup(Path file, Path directory) {
        try { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
        catch (IOException ignored) { /* Private temporary data is removed on a best-effort basis. */ }
    }

    private Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        Snapshot snapshot = catalog.snapshotAt(binding.capabilityId(), binding.capabilityVersion(),
                binding.connectionId(), binding.connectionVersion()).orElseThrow(() ->
                new IllegalStateException("Pinned Kling capability version is missing"));
        if (!adapterId().equals(snapshot.adapterId()) || !binding.mappingSha256().equals(snapshot.mappingSha256()))
            throw new IllegalStateException("Pinned Kling mapping differs");
        return snapshot;
    }

    private String credential(Snapshot snapshot) {
        var version = snapshot.connectionVersion();
        if (version.credentialCiphertext() == null || version.credentialNonce() == null || version.credentialKeyVersion() == null)
            throw new IllegalStateException("Pinned Kling credential is missing");
        String key = cipher.decryptMedia(version.connectionId(), version.version(),
                new CredentialCipher.Encrypted(version.credentialCiphertext(), version.credentialNonce(), version.credentialKeyVersion()));
        if (key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0)
            throw new IllegalStateException("Pinned Kling credential is invalid");
        return key;
    }

    protected String prompt(AttemptContext context) {
        String prompt = context.lease().input().path("prompt").asText();
        String negative = context.lease().input().path("negativePrompt").asText("");
        return negative.isBlank() ? prompt : prompt + "\nAvoid: " + negative;
    }
}
