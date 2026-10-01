package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private static final String AUXILIARY_ARCHIVE_TYPE = "zip";
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
                        remote = client.upload(origin, key, file.path(), file.asset().contentType(), field.effectiveResourceFormat());
                        uploaded.put(uploadKey, remote);
                    }
                    value = mapper.valueToTree(remote);
                }
                addNode(nodes, field.nodeId(), field.fieldName(), value, field.effectiveEncoding());
            }
            if (definition.fixedBindings() != null) for (var fixed : definition.fixedBindings())
                addNode(nodes, fixed.nodeId(), fixed.fieldName(), fixed.value(), fixed.encoding());
        } catch (RunningHubClient.Rejected | RunningHubClient.ProtocolFailure invalid) {
            return new Submission.Rejected("RUNNINGHUB_UPLOAD_FAILED");
        }
        try { return new Submission.Accepted(client.submit(origin, key, definition, nodes)); }
        catch (RunningHubClient.Rejected rejected) { return new Submission.Rejected("RUNNINGHUB_SUBMISSION_REJECTED"); }
        catch (RunningHubClient.Uncertain uncertain) { return new Submission.Unknown("PROVIDER_SUBMISSION_UNKNOWN"); }
    }

    @Override public Submission reconcile(AttemptContext context) {
        var snapshot = snapshot(context);
        JsonNode response = client.query(snapshot.connectionVersion().origin(), credential(snapshot), context.originalRequestId());
        return switch (response.path("status").asText()) {
            case "QUEUED", "RUNNING" -> new Submission.Pending(clock.instant().plusSeconds(POLL_SECONDS));
            case "FAILED" -> new Submission.Rejected("RUNNINGHUB_GENERATION_FAILED");
            case "SUCCESS" -> new Submission.CompletedResults(manifest(response, definition(snapshot), snapshot.connectionVersion().origin()));
            default -> throw new RunningHubClient.ProtocolFailure();
        };
    }

    @Override public MediaPayload downloadResult(AttemptContext context, ProviderResultManifest.Result result) {
        return client.download(snapshot(context).connectionVersion().origin(), result.url());
    }

    ProviderResultManifest manifest(JsonNode response, RunningHubDefinition definition, String origin) {
        JsonNode raw = response.path("results");
        if (!raw.isArray() || raw.isEmpty() || raw.size() > RunningHubDefinition.MAX_OUTPUTS) throw new RunningHubClient.ProtocolFailure();
        List<ProviderResultManifest.Result> results = new ArrayList<>();
        Map<RunningHubDefinition.Output, Integer> counts = new HashMap<>();
        Set<String> seen = new HashSet<>();
        boolean primary = false;
        for (JsonNode item : raw) {
            // RunningHub apps can return a companion ZIP even with their ZIP-only switch disabled.
            // It is neither downloaded nor extracted; a valid mapped primary media result is still required.
            if (AUXILIARY_ARCHIVE_TYPE.equalsIgnoreCase(item.path("outputType").asText(""))) continue;
            String url = item.path("url").asText("");
            var outputKind = outputKind(item.path("outputType").asText(""));
            if (outputKind == null) throw new RunningHubClient.ProtocolFailure();
            String nodeId = item.path("nodeId").asText("");
            var output = definition.outputs().stream().filter(binding -> binding.kind() == outputKind
                    && (binding.nodeId() == null || binding.nodeId().equals(nodeId))).findFirst()
                    .orElseThrow(RunningHubClient.ProtocolFailure::new);
            RunningHubClient.validateDownload(origin, url);
            if (!seen.add(nodeId + ":" + outputKind + ":" + url)) continue;
            int count = counts.merge(output, 1, Integer::sum);
            if (count > output.maxCount()) throw new RunningHubClient.ProtocolFailure();
            boolean main = output.primary() && !primary;
            if (main) primary = true;
            results.add(new ProviderResultManifest.Result(results.size(), nodeId, outputKind, main, url));
        }
        if (!primary) throw new RunningHubClient.ProtocolFailure();
        var usage = mapper.createObjectNode();
        for (String name : USAGE_FIELDS) {
            JsonNode value = response.path("usage").get(name);
            JsonNode normalized = usageValue(value);
            if (normalized != null) usage.set(name, normalized);
        }
        // No inferred currency or total: null remains unknown and GPU time is not media duration.
        return new ProviderResultManifest(ProviderResultManifest.SCHEMA_VERSION, List.copyOf(results), usage);
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

    private static RunningHubDefinition.OutputKind outputKind(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "image", "png", "jpg", "jpeg", "webp" -> RunningHubDefinition.OutputKind.IMAGE;
            case "video", "mp4" -> RunningHubDefinition.OutputKind.VIDEO;
            case "audio", "mp3", "wav", "flac" -> RunningHubDefinition.OutputKind.AUDIO;
            default -> null;
        };
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
        if (pinned == null) throw RunningHubDefinition.invalid("素材槽位没有匹配的冻结精确引用");
        var version = artifacts.requireMediaVersionForTask(context.ownerId(), context.lease().projectId(), versionId, Artifact.Kind.valueOf(field.type().name()));
        if (!version.artifactId().toString().equals(pinned.path("artifactId").asText())) throw RunningHubDefinition.invalid("冻结素材身份不匹配");
        var file = assets.get(context.ownerId(), context.lease().projectId(), UUID.fromString(version.content().path("assetId").asText()));
        if (!file.asset().mediaKind().name().equals(field.type().name()) || file.asset().byteSize() > RunningHubClient.MAX_UPLOAD_BYTES)
            throw RunningHubDefinition.invalid("RunningHub 输入文件不能超过 30 MB");
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
