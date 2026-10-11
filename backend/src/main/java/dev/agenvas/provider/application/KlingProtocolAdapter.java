package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.domain.Task;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** One frozen protocol for all model profiles; recovery can only query the acknowledged remote identity. */
abstract class KlingProtocolAdapter implements MediaAdapter {
    private final Task.Kind kind;
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    protected final KlingClient client;
    private final KlingMediaDownloadPolicy downloads;
    protected final ObjectMapper mapper;
    private final KlingPreparedMedia media;

    KlingProtocolAdapter(Task.Kind kind, JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            KlingClient client, KlingMediaDownloadPolicy downloads, ObjectMapper mapper, KlingPreparedMedia media) {
        this.kind = kind; this.catalog = catalog; this.cipher = cipher; this.client = client;
        this.downloads = downloads; this.mapper = mapper; this.media = media;
    }

    @Override public boolean supports(PortInput input) { return kind == input.kind(); }

    @Override public String preflightFailure(AttemptContext context) {
        Snapshot snapshot;
        try { snapshot = snapshot(context); frozen(context, snapshot); }
        catch (RuntimeException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
        try { credential(snapshot); }
        catch (RuntimeException unavailable) { return "MEDIA_CREDENTIAL_UNAVAILABLE"; }
        try { prepare(context, snapshot, false); return null; }
        catch (RuntimeException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
    }

    @Override public Submission submit(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        String key;
        try { key = credential(snapshot); }
        catch (RuntimeException unavailable) { return new Submission.Rejected("MEDIA_CREDENTIAL_UNAVAILABLE"); }
        Prepared request;
        try { request = prepare(context, snapshot, true); }
        catch (RuntimeException invalid) { return new Submission.Rejected("MEDIA_REFERENCE_PREPARATION_FAILED"); }
        try { return new Submission.Accepted(client.create(request.frozen().profile(), snapshot.connectionVersion().origin(), key, request.body())); }
        catch (KlingClient.Rejected rejected) { return new Submission.Rejected(ProviderFailureCodes.KLING_CREATE_REJECTED); }
        catch (KlingClient.Uncertain uncertain) { return new Submission.Unknown(ProviderFailureCodes.KLING_CREATE_UNCERTAIN); }
    }

    @Override public Submission reconcile(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        try {
            Frozen frozen = frozen(context, snapshot);
            var state = query(context, snapshot, frozen);
            return switch (state.status()) {
                case "submitted", "processing" -> new Submission.Pending(Instant.now().plusSeconds(5));
                case "failed" -> new Submission.Rejected(ProviderFailureCodes.KLING_TASK_FAILED);
                case "succeed", "succeeded" -> new Submission.CompletedResults(manifest(frozen, state.outputs()));
                default -> new Submission.Blocked(ProviderFailureCodes.PROTOCOL_INVALID);
            };
        } catch (KlingClient.TaskExpired expired) { return new Submission.Blocked(ProviderFailureCodes.KLING_TASK_EXPIRED); }
        catch (KlingClient.CredentialUnavailable unavailable) { return new Submission.Blocked("MEDIA_CREDENTIAL_UNAVAILABLE"); }
        catch (KlingClient.ProtocolFailure invalid) { return new Submission.Blocked(ProviderFailureCodes.PROTOCOL_INVALID); }
        catch (KlingMediaDownloadPolicy.Rejected rejected) { return new Submission.Blocked(rejected.getMessage()); }
        catch (IllegalArgumentException invalid) { return new Submission.Blocked(ProviderFailureCodes.RESULT_URL_INVALID); }
    }

    @Override public MediaPayload downloadResult(AttemptContext context, ProviderResultManifest.Result result) {
        Snapshot snapshot = snapshot(context);
        Frozen frozen = frozen(context, snapshot);
        if (result.kind() != outputKind() || result.archiveEntry() != null || result.ordinal() < 0
                || result.ordinal() > 8 || !validNodeId(frozen, result.nodeId())) throw new KlingClient.ProtocolFailure();
        try { return download(context, frozen, URI.create(result.url())); }
        catch (KlingMediaDownloadPolicy.Expired expired) {
            // A persisted manifest may outlive a signed result URL. Refresh only its original task and output index.
            var state = query(context, snapshot, frozen);
            if (!Set.of("succeed", "succeeded").contains(state.status())) throw new KlingClient.TaskExpired();
            var fresh = manifest(frozen, state.outputs()).results().stream()
                    .filter(item -> item.nodeId().equals(result.nodeId())).toList();
            if (fresh.size() != 1 || fresh.getFirst().url().equals(result.url())) throw new KlingMediaDownloadPolicy.Expired();
            return download(context, frozen, URI.create(fresh.getFirst().url()));
        }
    }

    protected void validateResult(AttemptContext context, KlingModels.Profile profile, JsonNode values, Path file) {}

    private ProviderResultManifest manifest(Frozen frozen, List<KlingClient.Output> outputs) {
        Integer expected = KlingModels.resultCount(frozen.profile(), frozen.values());
        boolean completion = frozen.profile().operation() == KlingModels.Operation.SUBJECT_COMPLETION;
        if (outputs == null || outputs.isEmpty() || outputs.size() > 9
                || completion && outputs.size() % 3 != 0
                || !completion && expected != null && outputs.size() != expected
                || !completion && expected == null && outputs.size() < 2) throw new KlingClient.ProtocolFailure();
        List<ProviderResultManifest.Result> results = new ArrayList<>();
        Set<Integer> indices = new HashSet<>();
        for (var output : outputs.stream().sorted(Comparator.comparingInt(KlingClient.Output::ordinal)).toList()) {
            if (output.ordinal() < 0 || !indices.add(output.ordinal())) throw new KlingClient.ProtocolFailure();
            String url = frozen.values().path("watermark").asBoolean(false) ? output.watermarkUrl() : output.url();
            if (url == null || url.isBlank()) throw new KlingClient.ProtocolFailure();
            downloads.validate(URI.create(url));
            // Local ordinals are dense for archival; the node identity retains the immutable provider index.
            results.add(new ProviderResultManifest.Result(results.size(), nodeId(frozen, output.ordinal()),
                    outputKind(), results.isEmpty(), url));
        }
        return new ProviderResultManifest(ProviderResultManifest.SCHEMA_VERSION, List.copyOf(results), null);
    }

    private static String nodeId(Frozen frozen, int ordinal) {
        return "kling:" + frozen.profile().operation().name() + ":" + frozen.profile().model() + ":" + ordinal;
    }
    private static boolean validNodeId(Frozen frozen, String nodeId) {
        for (int index = 0; index <= 9; index++) if (nodeId(frozen, index).equals(nodeId)) return true;
        return false;
    }
    private RunningHubDefinition.OutputKind outputKind() {
        return kind == Task.Kind.VIDEO_GENERATION ? RunningHubDefinition.OutputKind.VIDEO : RunningHubDefinition.OutputKind.IMAGE;
    }

    private KlingClient.State query(AttemptContext context, Snapshot snapshot, Frozen frozen) {
        return client.query(frozen.profile(), snapshot.connectionVersion().origin(), credential(snapshot),
                context.originalRequestId(), frozen.values().path("result_type").asText("single"));
    }

    private Prepared prepare(AttemptContext context, Snapshot snapshot, boolean submitting) {
        Frozen frozen = frozen(context, snapshot);
        ObjectNode body = client.build(frozen.profile(), frozen.values(), media.load(context, frozen.profile(), frozen.values(), submitting),
                submitting ? context.requestKey() : "preflight-only");
        return new Prepared(frozen, body);
    }

    private Frozen frozen(AttemptContext context, Snapshot snapshot) {
        var input = context.lease().input().path("mediaInput");
        var kling = input.path("providerParameters").path("kling");
        if (context.lease().kind() != kind || !kling.isObject()
                || !kling.propertyNames().equals(Set.of("model", "operation", "values"))) throw new KlingClient.ProtocolFailure();
        var profile = KlingModels.resolve(kling.path("model").asText(), kling.path("operation").asText(), kind);
        var configured = KlingModels.resolve(mapper.readTree(snapshot.specJson()).path("settings"), kind);
        JsonNode values = kling.path("values");
        if (!profile.equals(configured) || !values.equals(input.path("parameters").path(KlingModels.VALUES_PROPERTY)))
            throw new KlingClient.ProtocolFailure();
        KlingModels.validateValues(profile, values, true);
        return new Frozen(profile, values);
    }

    private Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        Snapshot snapshot = catalog.snapshotAt(binding.capabilityId(), binding.capabilityVersion(),
                binding.connectionId(), binding.connectionVersion()).orElseThrow(() -> new IllegalStateException("Pinned Kling capability is missing"));
        if (!adapterId().equals(binding.adapterId()) || !adapterId().equals(snapshot.adapterId())
                || !binding.mappingSha256().equals(snapshot.mappingSha256())) throw new IllegalStateException("Pinned Kling mapping differs");
        return snapshot;
    }

    private String credential(Snapshot snapshot) {
        var version = snapshot.connectionVersion();
        if (version.credentialCiphertext() == null || version.credentialNonce() == null || version.credentialKeyVersion() == null)
            throw new KlingClient.CredentialUnavailable();
        String key;
        try { key = cipher.decryptMedia(version.connectionId(), version.version(),
                new CredentialCipher.Encrypted(version.credentialCiphertext(), version.credentialNonce(), version.credentialKeyVersion())); }
        catch (RuntimeException unavailable) { throw new KlingClient.CredentialUnavailable(); }
        if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) throw new KlingClient.CredentialUnavailable();
        return key;
    }

    private MediaPayload download(AttemptContext context, Frozen frozen, URI url) {
        downloads.validate(url);
        Path directory;
        try { directory = Files.createTempDirectory("agenvas-kling-result-"); }
        catch (IOException unavailable) { throw new KlingMediaDownloadPolicy.TechnicalFailure(); }
        Path file = directory.resolve("result");
        try {
            String mime;
            try (OutputStream target = Files.newOutputStream(file)) {
                mime = downloads.download(url, target, kind == Task.Kind.VIDEO_GENERATION,
                        kind == Task.Kind.VIDEO_GENERATION ? 500L * 1024 * 1024 : 50L * 1024 * 1024);
            }
            validateResult(context, frozen.profile(), frozen.values(), file);
            return new MediaPayload(new FilterInputStream(Files.newInputStream(file)) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { cleanup(file, directory); }
                }
            }, mime);
        } catch (IOException unavailable) {
            cleanup(file, directory); throw new KlingMediaDownloadPolicy.TechnicalFailure();
        } catch (RuntimeException failure) { cleanup(file, directory); throw failure; }
    }

    private static void cleanup(Path file, Path directory) {
        try { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
        catch (IOException ignored) { /* Private scratch files are removed on a best-effort basis. */ }
    }
    private record Frozen(KlingModels.Profile profile, JsonNode values) {}
    private record Prepared(Frozen frozen, ObjectNode body) {}
}
