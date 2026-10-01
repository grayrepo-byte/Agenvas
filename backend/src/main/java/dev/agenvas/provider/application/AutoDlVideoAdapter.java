package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.AutoDlClient;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import tools.jackson.databind.JsonNode;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared H3 submission mapping; exact inputs and both configuration versions stay pinned. */
@Component
public final class AutoDlVideoAdapter implements MediaAdapter {
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final AutoDlClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    public AutoDlVideoAdapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            ArtifactService artifacts, AssetService assets, AutoDlClient client,
            ObjectMapper mapper, Clock clock) {
        this.catalog = catalog; this.cipher = cipher; this.artifacts = artifacts;
        this.assets = assets; this.client = client; this.mapper = mapper; this.clock = clock;
    }
    @Override public String adapterId() { return AutoDlWorkflows.ADAPTER_ID; }
    @Override public boolean supports(PortInput input) { return input.kind() == Task.Kind.VIDEO_GENERATION; }
    @Override public String preflightFailure(AttemptContext context) {
        try { credential(context); body(context); return null; }
        catch (ApiProblemException | IllegalArgumentException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
        catch (IllegalStateException unavailable) { return "AUTODL_INPUT_UNAVAILABLE"; }
    }
    @Override public Submission submit(AttemptContext context) {
        ObjectNode body = body(context);
        String workflow = settings(context).path("workflowId").asText();
        try { return new Submission.Accepted(client.create(credential(context), workflow, body)); }
        catch (AutoDlClient.Rejected rejected) { return new Submission.Rejected(
                rejected.status == 401 || rejected.status == 403 ? "AUTODL_CREDENTIAL_REJECTED" : "AUTODL_CREATE_REJECTED"); }
        catch (AutoDlClient.Uncertain uncertain) { return new Submission.Unknown("AUTODL_CREATE_UNCERTAIN"); }
    }
    @Override public Submission reconcile(AttemptContext context) {
        if (context.originalRequestId() == null) return new Submission.Unknown("AUTODL_CREATE_UNCERTAIN");
        try {
            var state = client.query(credential(context), context.originalRequestId());
            return switch (state.status()) {
                case "QUEUED", "RUNNING" -> new Submission.Pending(clock.instant().plus(POLL_INTERVAL));
                case "FAILED", "CANCELLED", "CANCELED" -> new Submission.Rejected("AUTODL_TASK_FAILED");
                case "SUCCESS" -> completed(context, state);
                default -> new Submission.Blocked("PROVIDER_PROTOCOL_INVALID");
            };
        } catch (AutoDlClient.ProtocolFailure invalid) { return new Submission.Blocked("PROVIDER_PROTOCOL_INVALID"); }
        catch (AutoDlClient.ResultRejected invalid) { return new Submission.Blocked("AUTODL_RESULT_REJECTED"); }
        catch (AutoDlClient.ResultExpired expired) { return new Submission.Blocked("AUTODL_RESULT_EXPIRED"); }
    }
    private Submission completed(AttemptContext context, AutoDlClient.TaskState state) {
        var results = state.results().stream().filter(result -> "video".equals(result.type())).toList();
        if (results.size() != 1) return new Submission.Blocked("AUTODL_RESULT_MISSING_VIDEO");
        try { return new Submission.Completed(client.downloadVideo(results.getFirst().url())); }
        catch (AutoDlClient.ResultExpired expired) {
            // A fresh signed URL may be obtained only from the original task, never a new submission.
            var refreshed = client.query(credential(context), context.originalRequestId());
            var videos = refreshed.results().stream().filter(result -> "video".equals(result.type())).toList();
            if (!"SUCCESS".equals(refreshed.status()) || videos.size() != 1
                    || videos.getFirst().url().equals(results.getFirst().url())) throw expired;
            return new Submission.Completed(client.downloadVideo(videos.getFirst().url()));
        }
    }
    private Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        var snapshot = catalog.snapshotAt(binding.capabilityId(), binding.capabilityVersion(),
                binding.connectionId(), binding.connectionVersion()).orElseThrow(() ->
                new IllegalStateException("Pinned AutoDL capability missing"));
        if (!adapterId().equals(snapshot.adapterId()) || !binding.mappingSha256().equals(snapshot.mappingSha256()))
            throw new IllegalStateException("Pinned AutoDL mapping differs");
        return snapshot;
    }
    private JsonNode settings(AttemptContext context) {
        return mapper.readTree(snapshot(context).specJson()).path("settings");
    }
    private String credential(AttemptContext context) {
        var version = snapshot(context).connectionVersion();
        if (version.credentialCiphertext() == null || version.credentialNonce() == null
                || version.credentialKeyVersion() == null) throw new IllegalStateException("AutoDL credential missing");
        return cipher.decryptMedia(version.connectionId(), version.version(), new CredentialCipher.Encrypted(
                version.credentialCiphertext(), version.credentialNonce(), version.credentialKeyVersion()));
    }
    private ObjectNode body(AttemptContext context) {
        var task = context.lease();
        var settings = settings(context);
        var workflow = AutoDlWorkflows.require(settings);
        var images = FrozenMediaInputs.images(task);
        var audios = FrozenMediaInputs.audios(task);
        String prompt = task.input().path("prompt").asText();
        int seconds = task.input().path("durationSeconds").asInt(-1);
        workflow.validate(prompt, seconds, task.input().path("mediaInput").path("mode").asText(), images.size(), audios.size());
        JsonNode providerParameters = task.input().path("mediaInput").path("providerParameters");
        if (!workflow.id().equals(providerParameters.path("workflowId").asText()))
            throw new IllegalArgumentException("Frozen workflow identity invalid");
        String resolution = providerParameters.path("resolution").asText();
        if (!workflow.resolutions().contains(resolution)) throw new IllegalArgumentException("Frozen resolution invalid");
        ObjectNode body = mapper.createObjectNode().put("prompt", prompt).put("duration", seconds).put("resolution", resolution);
        if (providerParameters.has("seed")) body.set("seed", providerParameters.get("seed"));
        long[] total = {0};
        for (var image : images) {
            String field = "START_END".equals(workflow.mode()) ? switch (image.role()) {
                case "START_FRAME" -> "first_frame";
                case "END_FRAME" -> "last_frame";
                default -> throw new IllegalArgumentException("Invalid frame role");
            } : workflow.imageFields().get(image.order());
            body.put(field, reference(context, image, false, total));
        }
        for (var audio : audios) body.put(workflow.audioFields().get(audio.order()), reference(context, audio, true, total));
        return body;
    }
    private String reference(AttemptContext context, FrozenMediaInputs.Image input, boolean audio, long[] total) {
        var task = context.lease();
        var version = artifacts.requireVersion(context.ownerId(), task.projectId(), input.artifactId(), input.versionId());
        var file = assets.get(context.ownerId(), task.projectId(), java.util.UUID.fromString(version.content().path("assetId").asText()));
        Asset asset = file.asset();
        Set<String> types = audio ? Set.of("audio/mpeg", "audio/wav", "audio/flac") : Set.of("image/png", "image/jpeg", "image/webp");
        if (asset.mediaKind() != (audio ? Asset.MediaKind.AUDIO : Asset.MediaKind.IMAGE)
                || !types.contains(asset.contentType()) || asset.byteSize() > AutoDlWorkflows.MAX_REFERENCE_BYTES)
            throw new IllegalArgumentException("AutoDL reference type/size unsupported");
        try (var stream = Files.newInputStream(file.path())) {
            byte[] bytes = stream.readNBytes(AutoDlWorkflows.MAX_REFERENCE_BYTES + 1);
            total[0] += bytes.length;
            if (bytes.length == 0 || bytes.length > AutoDlWorkflows.MAX_REFERENCE_BYTES
                    || total[0] > AutoDlWorkflows.MAX_TOTAL_REFERENCE_BYTES)
                throw new IllegalArgumentException("AutoDL reference size unsupported");
            return "data:" + asset.contentType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException failure) { throw new IllegalStateException("AutoDL reference unavailable", failure); }
    }
}
