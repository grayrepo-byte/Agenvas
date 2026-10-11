package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.domain.WanModels;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.provider.infrastructure.WanClient;
import dev.agenvas.provider.infrastructure.WanMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Acknowledged task identity and frozen connection version remain authoritative throughout recovery. */
abstract class WanProtocolAdapter implements MediaAdapter {
    private final Task.Kind kind;
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final WanClient client;
    private final WanMediaDownloadPolicy downloads;
    private final NativeMediaReferences references;
    private final WanModels models = new WanModels();
    protected final ObjectMapper mapper;
    WanProtocolAdapter(Task.Kind kind, JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            WanClient client, WanMediaDownloadPolicy downloads, ObjectMapper mapper, NativeMediaReferences references) {
        this.kind = kind; this.catalog = catalog; this.cipher = cipher; this.client = client;
        this.downloads = downloads; this.mapper = mapper; this.references = references;
    }
    @Override public boolean supports(PortInput input) { return kind == input.kind(); }
    @Override public String preflightFailure(AttemptContext context) {
        Snapshot snapshot;
        try { snapshot = snapshot(context); frozen(context, snapshot); }
        catch (RuntimeException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
        try { credential(snapshot); }
        catch (RuntimeException invalid) { return "MEDIA_CREDENTIAL_UNAVAILABLE"; }
        try { prepare(context, snapshot, false); return null; }
        catch (RuntimeException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
    }
    @Override public Submission submit(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        String key;
        try { key = credential(snapshot); }
        catch (RuntimeException unavailable) { return new Submission.Rejected("MEDIA_CREDENTIAL_UNAVAILABLE"); }
        Prepared prepared;
        try { prepared = prepare(context, snapshot, true); }
        catch (RuntimeException invalid) { return new Submission.Rejected("MEDIA_REFERENCE_PREPARATION_FAILED"); }
        try { return new Submission.Accepted(client.create(prepared.frozen().selected().profile(), snapshot.connectionVersion().origin(), key, prepared.body())); }
        catch (WanClient.Rejected refused) { return new Submission.Rejected(WanClient.CREATE_REJECTED); }
        catch (WanClient.Uncertain uncertain) { return new Submission.Unknown(WanClient.CREATE_UNCERTAIN); }
        catch (NativeMediaFailure failure) { return new Submission.Rejected(failure.code()); }
    }
    @Override public Submission reconcile(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        try {
            Frozen frozen = frozen(context, snapshot);
            var state = query(context, snapshot, frozen);
            return switch (state.status()) {
                case "PENDING", "RUNNING" -> new Submission.Pending(Instant.now().plusSeconds(5));
                case "FAILED", "CANCELED" -> new Submission.Rejected(WanClient.TASK_FAILED);
                case "SUCCEEDED" -> new Submission.CompletedResults(manifest(frozen, state));
                default -> new Submission.Blocked("PROVIDER_PROTOCOL_INVALID");
            };
        } catch (NativeMediaFailure permanent) { return new Submission.Blocked(permanent.code()); }
        catch (IllegalArgumentException invalid) { return new Submission.Blocked("PROVIDER_RESULT_URL_INVALID"); }
    }
    @Override public MediaPayload downloadResult(AttemptContext context, ProviderResultManifest.Result result) {
        Snapshot snapshot = snapshot(context);
        Frozen frozen = frozen(context, snapshot);
        if (result.kind() != outputKind() || result.archiveEntry() != null || result.ordinal() < 0 || result.ordinal() >= 12
                || result.nodeId() == null || !result.nodeId().startsWith(nodePrefix(frozen))) throw WanClient.protocolFailure();
        try { return download(context, frozen, URI.create(result.url())); }
        catch (WanMediaDownloadPolicy.Expired expired) {
            // The original acknowledged task is the only refresh authority; never submit again.
            var state = query(context, snapshot, frozen);
            if (!"SUCCEEDED".equals(state.status())) throw new NativeMediaFailure(WanClient.TASK_EXPIRED);
            var fresh = manifest(frozen, state).results().stream().filter(item -> item.nodeId().equals(result.nodeId())).toList();
            if (fresh.size() != 1 || fresh.getFirst().ordinal() != result.ordinal() || fresh.getFirst().url().equals(result.url()))
                throw new NativeMediaFailure(WanClient.RESULT_EXPIRED);
            try { return download(context, frozen, URI.create(fresh.getFirst().url())); }
            catch (WanMediaDownloadPolicy.Expired stillExpired) { throw new NativeMediaFailure(WanClient.RESULT_EXPIRED); }
        }
    }
    protected void validateResult(AttemptContext context, NativeMediaProtocol.Profile profile, JsonNode values, Path file) {}

    private Prepared prepare(AttemptContext context, Snapshot snapshot, boolean submitting) {
        Frozen frozen = frozen(context, snapshot);
        var media = references.load(context, frozen.selected(), frozen.values(), submitting);
        Map<String, dev.agenvas.asset.domain.Asset> assets = new HashMap<>();
        Map<String, String> prepared = new HashMap<>();
        for (var entry : media.entrySet()) {
            var field = frozen.selected().inputs().stream().filter(f -> f.key().equals(entry.getKey())).findFirst().orElseThrow(NativeMediaProfiles::invalid);
            var input = entry.getValue();
            assets.put(entry.getKey(), input.asset());
            if (field.type() == RunningHubDefinition.FieldType.IMAGE) {
                rejectPngAlpha(input);
                prepared.put(entry.getKey(), input.dataUrl());
            } else prepared.put(entry.getKey(), input.url());
        }
        models.validatePrepared(frozen.selected().profile(), frozen.values(), assets);
        return new Prepared(frozen, client.build(frozen.selected().profile(), frozen.values(), prepared));
    }
    private static void rejectPngAlpha(NativeMediaReferences.Input input) {
        if (!"image/png".equals(input.contentType())) return;
        try {
            var image = ImageIO.read(new ByteArrayInputStream(input.bytes()));
            if (image == null || image.getColorModel().hasAlpha()) throw NativeMediaProfiles.invalid();
        } catch (IOException invalid) { throw NativeMediaProfiles.invalid(); }
    }
    private Frozen frozen(AttemptContext context, Snapshot snapshot) {
        JsonNode input = context.lease().input().path("mediaInput"), frozen = input.path("providerParameters").path("native");
        if (context.lease().kind() != kind || !frozen.isObject()
                || !frozen.propertyNames().equals(Set.of("adapterId", "model", "operation", "values"))
                || !adapterId().equals(frozen.path("adapterId").asText())) throw WanClient.protocolFailure();
        var selected = NativeMediaProfiles.resolve(adapterId(), frozen);
        var configured = NativeMediaProfiles.resolve(adapterId(), mapper.readTree(snapshot.specJson()).path("settings"));
        JsonNode values = frozen.path("values");
        if (!selected.profile().equals(configured.profile()) || !values.equals(input.path("parameters").path(NativeMediaProtocol.VALUES_PROPERTY)))
            throw WanClient.protocolFailure();
        NativeMediaProfiles.validateValues(selected, values, true);
        WanClient.validateOrigin(selected.profile(), snapshot.connectionVersion().origin());
        return new Frozen(selected, values);
    }
    private Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        Snapshot snapshot = catalog.snapshotAt(binding.capabilityId(), binding.capabilityVersion(), binding.connectionId(), binding.connectionVersion())
                .orElseThrow(() -> new IllegalStateException("Pinned Wan capability missing"));
        if (!adapterId().equals(binding.adapterId()) || !adapterId().equals(snapshot.adapterId())
                || !binding.mappingSha256().equals(snapshot.mappingSha256())) throw new IllegalStateException("Pinned Wan mapping differs");
        return snapshot;
    }
    private String credential(Snapshot snapshot) {
        var version = snapshot.connectionVersion();
        String key;
        try {
            if (version.credentialCiphertext() == null || version.credentialNonce() == null || version.credentialKeyVersion() == null)
                throw new IllegalStateException("Credential unavailable");
            key = cipher.decryptMedia(version.connectionId(), version.version(),
                    new CredentialCipher.Encrypted(version.credentialCiphertext(), version.credentialNonce(), version.credentialKeyVersion()));
        } catch (RuntimeException unavailable) { throw new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE"); }
        if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0)
            throw new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE");
        return key;
    }
    private WanClient.State query(AttemptContext context, Snapshot snapshot, Frozen frozen) {
        return client.query(frozen.selected().profile(), snapshot.connectionVersion().origin(), credential(snapshot), context.originalRequestId());
    }
    private ProviderResultManifest manifest(Frozen frozen, WanClient.State state) {
        Integer expected = models.resultCount(frozen.selected().profile(), frozen.values());
        var outputs = state.outputs();
        if (outputs == null || outputs.isEmpty() || outputs.size() > 12 || expected != null && outputs.size() != expected
                || expected == null && outputs.size() > frozen.values().path("n").asInt(1)) throw WanClient.protocolFailure();
        Set<String> identities = new HashSet<>();
        List<ProviderResultManifest.Result> results = new ArrayList<>();
        for (var output : outputs) {
            if (output.identity() == null || !identities.add(output.identity())) throw WanClient.protocolFailure();
            downloads.validate(URI.create(output.url()));
            results.add(new ProviderResultManifest.Result(results.size(), nodePrefix(frozen) + output.identity(),
                    outputKind(), results.isEmpty(), output.url()));
        }
        return new ProviderResultManifest(ProviderResultManifest.SCHEMA_VERSION, List.copyOf(results), state.usage());
    }
    private String nodePrefix(Frozen frozen) { return "wan:" + frozen.selected().profile().model() + ":"; }
    private RunningHubDefinition.OutputKind outputKind() {
        return kind == Task.Kind.IMAGE_GENERATION ? RunningHubDefinition.OutputKind.IMAGE : RunningHubDefinition.OutputKind.VIDEO;
    }
    private MediaPayload download(AttemptContext context, Frozen frozen, URI url) {
        downloads.validate(url);
        Path directory;
        try { directory = Files.createTempDirectory("agenvas-wan-result-"); }
        catch (IOException unavailable) { throw new WanMediaDownloadPolicy.TechnicalFailure(); }
        Path file = directory.resolve("result");
        try {
            String mime;
            try (OutputStream target = Files.newOutputStream(file)) { mime = downloads.download(url, target, kind == Task.Kind.VIDEO_GENERATION); }
            validateResult(context, frozen.selected().profile(), frozen.values(), file);
            return new MediaPayload(new FilterInputStream(Files.newInputStream(file)) {
                @Override public void close() throws IOException { try { super.close(); } finally { cleanup(file, directory); } }
            }, mime);
        } catch (IOException unavailable) { cleanup(file, directory); throw new WanMediaDownloadPolicy.TechnicalFailure(); }
        catch (RuntimeException failure) { cleanup(file, directory); throw failure; }
    }
    private static void cleanup(Path file, Path directory) {
        try { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
        catch (IOException ignored) { /* Private scratch cleanup does not alter the original result identity. */ }
    }
    private record Frozen(NativeMediaProfiles.Selection selected, JsonNode values) {}
    private record Prepared(Frozen frozen, ObjectNode body) {}
}
