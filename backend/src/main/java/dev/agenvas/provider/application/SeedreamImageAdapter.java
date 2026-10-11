package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.SeedreamModels;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.provider.infrastructure.SeedreamClient;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.domain.Task;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Acknowledges a synchronous response and its results together; recovery only reads persisted bytes. */
@Component
public class SeedreamImageAdapter implements MediaAdapter {
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final SeedreamClient client;
    private final NativeMediaReferences references;
    private final ObjectMapper mapper;

    public SeedreamImageAdapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher, SeedreamClient client,
            NativeMediaReferences references, ObjectMapper mapper) {
        this.catalog = catalog; this.cipher = cipher; this.client = client; this.references = references; this.mapper = mapper;
    }
    @Override public String adapterId() { return SeedreamModels.ADAPTER_ID; }
    @Override public boolean supports(PortInput input) { return input.kind() == Task.Kind.IMAGE_GENERATION; }

    @Override public String preflightFailure(AttemptContext context) {
        try {
            Snapshot snapshot = snapshot(context);
            credential(snapshot);
            SeedreamClient.validatedOrigin(snapshot.connectionVersion().origin());
            Frozen frozen = frozen(context, snapshot);
            client.build(frozen.selection().profile(), frozen.values(), prepare(context, frozen, false));
            return null;
        } catch (NativeMediaFailure failed) { return failed.code(); }
        catch (RuntimeException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
    }

    @Override public Submission submit(AttemptContext context) {
        try {
            Snapshot snapshot = snapshot(context);
            String key = credential(snapshot);
            Frozen frozen = frozen(context, snapshot);
            var body = client.build(frozen.selection().profile(), frozen.values(), prepare(context, frozen, true));
            var response = client.create(frozen.selection().profile(), frozen.values(), snapshot.connectionVersion().origin(),
                    key, body, context.requestKey());
            return new Submission.AcceptedResults(response.requestId(), response.manifest());
        } catch (SeedreamClient.Uncertain unknown) { return new Submission.Unknown(unknown.code()); }
        catch (SeedreamClient.Rejected rejected) { return new Submission.Rejected(rejected.code()); }
        catch (NativeMediaFailure failed) { return new Submission.Rejected(failed.code()); }
        catch (RuntimeException invalid) { return new Submission.Rejected("MEDIA_REFERENCE_PREPARATION_FAILED"); }
    }

    /** There is no status API. The Worker bypasses this method when the acknowledged manifest exists. */
    @Override public Submission reconcile(AttemptContext context) {
        return new Submission.Blocked("SEEDREAM_HAS_NO_STATUS_ENDPOINT");
    }

    @Override public MediaPayload downloadResult(AttemptContext context, ProviderResultManifest.Result result) {
        Frozen frozen = frozen(context, snapshot(context));
        String prefix = "seedream:" + frozen.selection().profile().operation() + ":" + frozen.selection().profile().model() + ":";
        if (result == null || result.kind() != RunningHubDefinition.OutputKind.IMAGE || result.archiveEntry() != null
                || result.ordinal() < 0 || result.ordinal() >= SeedreamModels.maximumResults(frozen.selection().profile(), frozen.values())
                || result.nodeId() == null || !result.nodeId().startsWith(prefix)) throw protocol();
        String index = result.nodeId().substring(prefix.length());
        if (!index.matches("(?:[0-9]|1[0-6])") || Integer.parseInt(index) >= SeedreamModels.maximumResults(frozen.selection().profile(), frozen.values())) throw protocol();
        byte[] bytes = SeedreamClient.inlineBytes(result.url());
        String mime = result.url().startsWith("data:image/png;base64,") ? "image/png" : "image/jpeg";
        return new MediaPayload(new ByteArrayInputStream(bytes), mime, result.providerMetadata());
    }

    private Map<String, String> prepare(AttemptContext context, Frozen frozen, boolean submitting) {
        Map<String, String> images = new HashMap<>();
        for (var entry : references.load(context, frozen.selection(), frozen.values(), submitting).entrySet()) {
            if ("transparent".equals(frozen.values().path("background").asText())) SeedreamClient.requireAlpha(entry.getValue().bytes());
            images.put(entry.getKey(), entry.getValue().dataUrl());
        }
        return Map.copyOf(images);
    }

    private Frozen frozen(AttemptContext context, Snapshot snapshot) {
        JsonNode nativeValues = context.lease().input().at("/mediaInput/providerParameters/native");
        if (!nativeValues.isObject() || !nativeValues.propertyNames().equals(Set.of("adapterId", "model", "operation", "values"))
                || !adapterId().equals(nativeValues.path("adapterId").asText()) || context.lease().kind() != Task.Kind.IMAGE_GENERATION) throw protocol();
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
        Snapshot snapshot = catalog.snapshotAt(binding.capabilityId(), binding.capabilityVersion(), binding.connectionId(), binding.connectionVersion())
                .orElseThrow(() -> new IllegalStateException("Pinned Seedream capability is missing"));
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
        } catch (RuntimeException unavailable) { throw new NativeMediaFailure("MEDIA_CREDENTIAL_UNAVAILABLE"); }
    }
    private static NativeMediaFailure protocol() { return new NativeMediaFailure(ProviderFailureCodes.PROTOCOL_INVALID); }
    private record Frozen(NativeMediaProfiles.Selection selection, JsonNode values) {}
}
