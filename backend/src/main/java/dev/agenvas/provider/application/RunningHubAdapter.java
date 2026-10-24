package dev.agenvas.provider.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.ProviderFailure;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.provider.infrastructure.RunningHubResultArchive;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.io.FilterInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/** One compiled protocol, three output kinds; each workflow/app is immutable capability data. */
public final class RunningHubAdapter implements MediaAdapter {
    private static final int POLL_SECONDS = 5;
    private static final int MAX_USAGE_VALUE_LENGTH = 80;
    private static final int MAX_USAGE_PRECISION = 40;
    private static final int MAX_USAGE_SCALE = 20;
    private static final Set<String> USAGE_FIELDS = Set.of("consumeMoney", "consumeCoins", "taskCostTime", "thirdPartyConsumeMoney");
    private static final String ARCHIVE_TYPE = "zip";
    private final String id;
    private final Task.Kind kind;
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final RunningHubClient client;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RunningHubAdapter(String id, Task.Kind kind, JooqMediaCapabilityRepository catalog,
            CredentialCipher cipher, ArtifactService artifacts, AssetService assets,
            RunningHubClient client, ObjectMapper mapper, Clock clock) {
        this.id = id; this.kind = kind; this.catalog = catalog; this.cipher = cipher;
        this.artifacts = artifacts; this.assets = assets; this.client = client; this.mapper = mapper; this.clock = clock;
    }
    @Override public String adapterId() { return id; }
    @Override public boolean supports(PortInput input) { return kind == input.kind(); }

    @Override public String preflightFailure(AttemptContext context) {
        try {
            var snapshot = snapshot(context);
            credential(snapshot);
            RunningHubDefinition definition = definition(snapshot);
            JsonNode values = values(context, definition);
            for (var field : definition.fields()) if (field.media() && values.has(field.key())) reference(context, field, values.get(field.key()));
            return null;
        } catch (RuntimeException invalid) { return "RUNNINGHUB_INPUT_UNAVAILABLE"; }
    }

    @Override public Submission submit(AttemptContext context) {
        var snapshot = snapshot(context);
        RunningHubDefinition definition = definition(snapshot);
        String key = credential(snapshot);
        String origin = snapshot.connectionVersion().origin();
        JsonNode values = values(context, definition);
        ArrayNode nodes = mapper.createArrayNode();
        Map<String, String> uploaded = new HashMap<>();
        // Upload is non-generative. A failed upload rejects this attempt before any paid run request.
        try {
            for (var field : definition.fields()) {
                JsonNode value = values.get(field.key());
                if (value == null) continue;
                if (field.media()) {
                    String uploadKey = value.asText() + ":" + field.effectiveResourceFormat();
                    String remote = uploaded.get(uploadKey);
                    if (remote == null) {
                        var file = reference(context, field, value);
                        try {
                            remote = client.upload(origin, key, file.path(), file.asset().contentType(), field.effectiveResourceFormat());
                        } catch (RunningHubClient.Failure failure) {
                            // The current frozen slot supplies a trusted location when an upload endpoint omits one.
                            String name = field.fieldName().matches("(?i).*(apikey|api_key|authorization|token|secret|password).*")
                                    ? null : field.fieldName();
                            var located = new ProviderFailure(ProviderFailure.Stage.UPLOAD, failure.diagnostic().category(),
                                    failure.diagnostic().httpStatus(), failure.diagnostic().upstreamCode(), field.nodeId(), name);
                            if (failure instanceof RunningHubClient.Rejected) throw new RunningHubClient.Rejected(located);
                            throw new RunningHubClient.ProtocolFailure(located);
                        }
                        uploaded.put(uploadKey, remote);
                    }
                    value = mapper.valueToTree(remote);
                }
                addNode(nodes, field.nodeId(), field.fieldName(), value, field.effectiveEncoding());
            }
            if (definition.fixedBindings() != null) for (var fixed : definition.fixedBindings())
                addNode(nodes, fixed.nodeId(), fixed.fieldName(), fixed.value(), fixed.encoding());
        } catch (RunningHubClient.Rejected | RunningHubClient.ProtocolFailure invalid) {
            var safe = safeFailure(invalid.diagnostic(), definition);
            if (safe.stage() != ProviderFailure.Stage.UPLOAD) safe = new ProviderFailure(ProviderFailure.Stage.UPLOAD,
                    ProviderFailure.Category.UPLOAD, safe.httpStatus(), safe.upstreamCode(), safe.nodeId(), safe.fieldName());
            return new Submission.Rejected(safe.code(), safe);
        }
        try { return new Submission.Accepted(client.submit(origin, key, definition, nodes)); }
        catch (RunningHubClient.Rejected rejected) {
            var safe = safeFailure(rejected.diagnostic(), definition);
            return new Submission.Rejected(safe.code(), safe);
        }
        catch (RunningHubClient.Uncertain uncertain) { return new Submission.Unknown("PROVIDER_SUBMISSION_UNKNOWN", safeFailure(uncertain.diagnostic(), definition)); }
    }

    @Override public Submission reconcile(AttemptContext context) {
        var snapshot = snapshot(context);
        var definition = definition(snapshot);
        JsonNode response = query(context, snapshot, definition);
        return switch (response.path("status").asText()) {
            case "QUEUED", "RUNNING" -> new Submission.Pending(clock.instant().plusSeconds(POLL_SECONDS));
            case "FAILED" -> {
                var failure = safeFailure(client.generationFailure(response, credential(snapshot)), definition);
                yield new Submission.Rejected(failure.code(), failure);
            }
            case "SUCCESS" -> new Submission.CompletedResults(manifest(response, definition, snapshot.connectionVersion().origin()));
            default -> throw new RunningHubClient.ProtocolFailure();
        };
    }

    @Override public ProviderResultManifest refreshResults(AttemptContext context, ProviderResultManifest frozen) {
        var snapshot = snapshot(context);
        String origin = snapshot.connectionVersion().origin();
        JsonNode response = query(context, snapshot, definition(snapshot));
        if (!"SUCCESS".equals(response.path("status").asText())) throw new RunningHubClient.ProtocolFailure();
        return refreshedManifest(frozen, response, origin);
    }

    private JsonNode query(AttemptContext context, JooqMediaCapabilityRepository.Snapshot snapshot, RunningHubDefinition definition) {
        try { return client.query(snapshot.connectionVersion().origin(), credential(snapshot), context.originalRequestId()); }
        catch (RunningHubClient.Rejected failure) { throw new RunningHubClient.Rejected(safeFailure(failure.diagnostic(), definition)); }
        catch (RunningHubClient.ProtocolFailure failure) { throw new RunningHubClient.ProtocolFailure(safeFailure(failure.diagnostic(), definition)); }
    }

    /** Locations must name an input in the frozen contract, not merely resemble an identifier. */
    private ProviderFailure safeFailure(ProviderFailure failure, RunningHubDefinition definition) {
        String node = failure.nodeId();
        String field = failure.fieldName();
        boolean matches = definition.fields().stream().anyMatch(binding ->
                (node == null || node.equals(binding.nodeId())) && (field == null || field.equals(binding.fieldName())));
        if (!matches && definition.fixedBindings() != null) matches = definition.fixedBindings().stream().anyMatch(binding ->
                (node == null || node.equals(binding.nodeId())) && (field == null || field.equals(binding.fieldName())));
        return new ProviderFailure(failure.stage(), failure.category(), failure.httpStatus(), failure.upstreamCode(),
                matches ? node : null, matches ? field : null);
    }

    /** A fresh query may reorder candidates. Only exact, unique file paths may renew a frozen result URL. */
    ProviderResultManifest refreshedManifest(ProviderResultManifest frozen, JsonNode response, String origin) {
        JsonNode raw = response.path("results");
        if (!raw.isArray()) throw new RunningHubClient.ResultIdentityChanged();
        List<ProviderResultManifest.Result> refreshed = new ArrayList<>();
        for (var result : frozen.results()) {
            String identity = resultIdentity(result.url());
            List<String> matches = new ArrayList<>();
            for (JsonNode item : raw) {
                if (!result.nodeId().equals(item.path("nodeId").asText(""))) continue;
                String type = item.path("outputType").asText("");
                if (result.archiveEntry() == null ? result.kind() != RunningHubResultArchive.outputKind(type)
                        : !ARCHIVE_TYPE.equalsIgnoreCase(type)) continue;
                String url = item.path("url").asText("");
                RunningHubClient.validateDownload(origin, url);
                if (identity.equals(resultIdentity(url))) matches.add(url);
            }
            // Never attach an ordinal to a different file, even if it is now first in the provider list.
            if (matches.size() != 1) throw new RunningHubClient.ResultIdentityChanged();
            refreshed.add(new ProviderResultManifest.Result(result.ordinal(), result.nodeId(), result.kind(),
                    result.primary(), matches.getFirst(), result.archiveEntry(), result.providerMetadata()));
        }
        return new ProviderResultManifest(frozen.schemaVersion(), List.copyOf(refreshed), frozen.usage());
    }

    private static String resultIdentity(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            // An opaque download endpoint may select a different file solely through its query.
            // Require a concrete media/archive path and retain every query parameter except known signing fields.
            if (path == null || !path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(png|jpe?g|webp|mp4|mp3|wav|flac|zip)$"))
                throw new RunningHubClient.ResultIdentityChanged();
            List<String> identityQuery = new ArrayList<>();
            if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
                String name = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
                if (Set.of("signature", "sig", "token", "expires", "expire", "expiry", "auth_key", "authorization",
                        "policy", "key-pair-id", "ossaccesskeyid", "accesskeyid", "q-ak", "q-key-time", "q-signature").contains(name)
                        || name.startsWith("x-amz-") || name.startsWith("x-oss-") || name.startsWith("q-sign-")) continue;
                identityQuery.add(pair);
            }
            identityQuery.sort(String::compareTo);
            return path + "?" + String.join("&", identityQuery);
        } catch (IllegalArgumentException invalid) { throw new RunningHubClient.ResultIdentityChanged(); }
    }

    @Override public MediaPayload downloadResult(AttemptContext context, ProviderResultManifest.Result result) {
        ResultDownloads downloads = openResultDownloads(context);
        try {
            MediaPayload payload = downloads.download(result);
            return new MediaPayload(new FilterInputStream(payload.stream()) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { downloads.close(); }
                }
            }, payload.declaredContentType());
        } catch (RuntimeException failure) {
            try { downloads.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public ResultDownloads openResultDownloads(AttemptContext context) {
        String origin = snapshot(context).connectionVersion().origin();
        return new ResultDownloads() {
            private final Map<String, RunningHubResultArchive> archives = new HashMap<>();
            @Override public MediaPayload download(ProviderResultManifest.Result result) {
                if (result.archiveEntry() == null) return client.download(origin, result.url());
                // Download/extract each ZIP once per archive attempt, including mixed-media batches.
                return archives.computeIfAbsent(result.url(), url -> RunningHubResultArchive.open(client.download(origin, url)))
                        .download(result.archiveEntry());
            }
            @Override public void close() {
                RuntimeException failure = null;
                for (var archive : archives.values()) {
                    try { archive.close(); }
                    catch (RuntimeException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
                }
                if (failure != null) throw failure;
            }
        };
    }

    ProviderResultManifest manifest(JsonNode response, RunningHubDefinition definition, String origin) {
        JsonNode raw = response.path("results");
        if (!raw.isArray() || raw.isEmpty()) throw new RunningHubClient.ProtocolFailure();
        var selection = new ResultSelection(definition, origin);
        for (JsonNode item : raw) {
            String url = item.path("url").asText("");
            String nodeId = item.path("nodeId").asText("");
            if (ARCHIVE_TYPE.equalsIgnoreCase(item.path("outputType").asText(""))) {
                // Once the matching mappings are full, an ancillary ZIP cannot improve
                // the selection. Do not download it or let its contents reject a ready result.
                if (!selection.needsArchive(nodeId)) continue;
                RunningHubClient.validateDownload(origin, url);
                try (var archive = RunningHubResultArchive.open(client.download(origin, url))) {
                    for (var member : archive.members()) selection.add(nodeId, member.kind(), url, member.entry());
                }
            } else {
                var outputKind = RunningHubResultArchive.outputKind(item.path("outputType").asText(""));
                if (outputKind == null) continue;
                selection.add(nodeId, outputKind, url, null);
            }
        }
        return new ProviderResultManifest(ProviderResultManifest.SCHEMA_VERSION, selection.results(), usage(response));
    }

    private JsonNode usage(JsonNode response) {
        var usage = mapper.createObjectNode();
        for (String name : USAGE_FIELDS) {
            JsonNode value = response.path("usage").get(name);
            JsonNode normalized = usageValue(value);
            if (normalized != null) usage.set(name, normalized);
        }
        // No inferred currency or total: null remains unknown and GPU time is not media duration.
        return usage;
    }

    /** Direct files and ZIP members share the same ordered selection and per-mapping caps. */
    private static final class ResultSelection {
        private final RunningHubDefinition definition;
        private final String origin;
        private final List<ProviderResultManifest.Result> results = new ArrayList<>();
        private final Map<RunningHubDefinition.Output, Integer> counts = new HashMap<>();
        private final Set<String> seen = new HashSet<>();

        private ResultSelection(RunningHubDefinition definition, String origin) {
            this.definition = definition;
            this.origin = origin;
        }

        private boolean needsArchive(String nodeId) {
            return definition.outputs().stream().anyMatch(output ->
                    (output.nodeId() == null || output.nodeId().equals(nodeId))
                    && counts.getOrDefault(output, 0) < output.maxCount());
        }

        private void add(String nodeId, RunningHubDefinition.OutputKind kind, String url,
                ProviderResultManifest.ArchiveEntry entry) {
            var output = definition.outputs().stream().filter(binding -> binding.kind() == kind
                    && (binding.nodeId() == null || binding.nodeId().equals(nodeId))).findFirst()
                    .orElse(null);
            // maxCount is a selection cap in provider/member order, not an assertion
            // about how many files a workflow may produce. Unmapped companions are optional.
            if (output == null || counts.getOrDefault(output, 0) >= output.maxCount()
                    || results.size() >= RunningHubDefinition.MAX_OUTPUTS) return;
            if (!seen.add(nodeId + ":" + kind + ":" + url + ":" + (entry == null ? "" : entry.name()))) return;
            RunningHubClient.validateDownload(origin, url);
            counts.merge(output, 1, Integer::sum);
            boolean main = output.primary() && results.stream().noneMatch(ProviderResultManifest.Result::primary);
            results.add(new ProviderResultManifest.Result(results.size(), nodeId, kind, main, url, entry));
        }

        private List<ProviderResultManifest.Result> results() {
            if (results.stream().noneMatch(ProviderResultManifest.Result::primary)) throw new RunningHubClient.ProtocolFailure();
            return List.copyOf(results);
        }
    }

    /** Real V2 responses also encode usage decimals as strings; currency and totals stay uninferred. */
    private JsonNode usageValue(JsonNode value) {
        if (value == null || value.isNull()) return value;
        if (!(value.isNumber() || value.isTextual()) || value.asText().length() > MAX_USAGE_VALUE_LENGTH) return null;
        try {
            BigDecimal amount = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
            if (amount.signum() < 0 || amount.precision() > MAX_USAGE_PRECISION || Math.abs((long) amount.scale()) > MAX_USAGE_SCALE) return null;
            return mapper.valueToTree(amount);
        } catch (NumberFormatException invalid) { return null; }
    }

    private JsonNode values(AttemptContext context, RunningHubDefinition definition) {
        JsonNode frozen = context.lease().input().path("mediaInput");
        return definition.values(mapper, frozen.path("parameters"), frozen.path("renderedPrompt").asText(""),
                frozen.hasNonNull("durationSeconds") ? frozen.path("durationSeconds").asInt() : null, true);
    }

    private AssetService.AssetFile reference(AttemptContext context, RunningHubDefinition.Field field, JsonNode value) {
        UUID versionId = UUID.fromString(value.asText());
        String array = switch (field.type()) { case IMAGE -> "images"; case AUDIO -> "audios"; case VIDEO -> "videos"; default -> throw new IllegalArgumentException(); };
        JsonNode pinned = null;
        for (JsonNode item : context.lease().input().path("mediaInput").path(array))
            if (item.path("versionId").asText().equals(versionId.toString())) pinned = item;
        if (pinned == null) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-adapter.asset-slot-does-not-have-a-matching-frozen-exact-reference"));
        var version = artifacts.requireMediaVersionForTask(context.ownerId(), context.lease().projectId(), versionId, Artifact.Kind.valueOf(field.type().name()));
        if (!version.artifactId().toString().equals(pinned.path("artifactId").asText())) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-adapter.frozen-footage-identity-mismatch"));
        var file = assets.get(context.ownerId(), context.lease().projectId(), UUID.fromString(version.content().path("assetId").asText()));
        if (!file.asset().mediaKind().name().equals(field.type().name()) || file.asset().byteSize() > RunningHubClient.MAX_UPLOAD_BYTES)
            throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-adapter.runninghub-input-file-cannot-exceed-30-mb"));
        return file;
    }

    private void addNode(ArrayNode nodes, String node, String field, JsonNode value, RunningHubDefinition.Encoding encoding) {
        var item = nodes.addObject().put("nodeId", node).put("fieldName", field);
        item.set("fieldValue", encoding == RunningHubDefinition.Encoding.STRING ? mapper.valueToTree(value.asText()) : value);
    }
    private RunningHubDefinition definition(JooqMediaCapabilityRepository.Snapshot snapshot) {
        return RunningHubDefinition.parse(mapper, mapper.readTree(snapshot.specJson()).path("settings").path("runningHub"), kind);
    }
    private JooqMediaCapabilityRepository.Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        var snapshot = catalog.snapshotAt(binding.capabilityId(), binding.capabilityVersion(), binding.connectionId(), binding.connectionVersion()).orElseThrow();
        if (!id.equals(snapshot.adapterId()) || !binding.mappingSha256().equals(snapshot.mappingSha256())) throw new IllegalStateException("Pinned RunningHub contract differs");
        return snapshot;
    }
    private String credential(JooqMediaCapabilityRepository.Snapshot snapshot) {
        var version = snapshot.connectionVersion();
        if (version.credentialCiphertext() == null || version.credentialNonce() == null || version.credentialKeyVersion() == null) throw new IllegalStateException("Pinned RunningHub credential unavailable");
        return cipher.decryptMedia(version.connectionId(), version.version(), new CredentialCipher.Encrypted(version.credentialCiphertext(), version.credentialNonce(), version.credentialKeyVersion()));
    }
}
