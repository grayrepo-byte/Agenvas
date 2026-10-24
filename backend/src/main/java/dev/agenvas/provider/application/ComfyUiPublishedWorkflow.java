package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.ComfyUiWorkflowDefinition;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiHistory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared execution of imported image/video graphs through the existing durable ComfyUI protocol. */
@Component
public class ComfyUiPublishedWorkflow {
    private static final int POLL_SECONDS = 5;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MediaToolRunner mediaTools;

    public ComfyUiPublishedWorkflow(ArtifactService artifacts, AssetService assets, ObjectMapper mapper, Clock clock,
            MediaToolRunner mediaTools) {
        this.artifacts = artifacts;
        this.assets = assets;
        this.mapper = mapper;
        this.clock = clock;
        this.mediaTools = mediaTools;
    }

    public String preflightFailure(AttemptContext context) {
        try {
            for (var reference : references(context)) verifiedFile(context, reference);
            return null;
        } catch (RuntimeException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
    }

    public Submission submit(AttemptContext context, ComfyUiClient client, JsonNode settings) {
        var task = context.lease();
        var definition = ComfyUiWorkflowDefinition.parse(mapper, settings.get(ComfyUiWorkflowDefinition.SETTINGS_KEY), task.kind());
        JsonNode frozen = task.input().path("mediaInput").path("providerParameters");
        if (!frozen.path("width").isIntegralNumber() || !frozen.path("height").isIntegralNumber())
            throw new IllegalStateException("Pinned ComfyUI dimensions are missing");
        UUID requestKey = UUID.fromString(context.requestKey());
        JsonNode values = frozen.path(dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY);
        JsonNode graph;
        try {
            Map<String, String> uploaded = new HashMap<>();
            for (var reference : references(context)) {
                var file = verifiedFile(context, reference);
                UUID uploadId = UUID.nameUUIDFromBytes((context.requestKey() + ":" + reference.input().versionId()).getBytes(StandardCharsets.UTF_8));
                String name;
                if (reference.kind() == Asset.MediaKind.IMAGE) {
                    var image = ImageIO.read(file.path().toFile());
                    if (image == null) throw new IllegalStateException("Pinned ComfyUI image cannot be decoded");
                    var buffer = new ByteArrayOutputStream();
                    if (!ImageIO.write(image, "png", buffer)) throw new IllegalStateException("PNG encoder unavailable");
                    name = client.uploadImage(uploadId, buffer.toByteArray(), "png");
                } else name = client.uploadMedia(uploadId, file.path(), file.asset().contentType());
                uploaded.put(reference.input().versionId().toString(), name);
            }
            graph = definition.render(mapper, task.input().path("prompt").asText(), task.input().path("negativePrompt").asText(""),
                    requestKey.getMostSignificantBits() & Long.MAX_VALUE,
                    new ComfyUiWorkflowDefinition.Dimensions(frozen.path("width").intValue(), frozen.path("height").intValue()),
                    task.input().path("durationSeconds").asInt(0),
                    uploadedSlots(definition, values, uploaded, context, ComfyUiWorkflowDefinition.Source.REFERENCE_IMAGE),
                    uploadedSlots(definition, values, uploaded, context, ComfyUiWorkflowDefinition.Source.REFERENCE_VIDEO),
                    uploadedSlots(definition, values, uploaded, context, ComfyUiWorkflowDefinition.Source.REFERENCE_AUDIO), values);
        } catch (IOException | RuntimeException preparationFailure) {
            // Uploading reference bytes cannot create a generation request or generation UNKNOWN.
            return new Submission.Rejected("MEDIA_REFERENCE_PREPARATION_FAILED");
        }
        return new Submission.Accepted(client.submit(graph, requestKey));
    }

    private List<String> uploadedSlots(ComfyUiWorkflowDefinition definition, JsonNode values, Map<String, String> uploaded,
            AttemptContext context, ComfyUiWorkflowDefinition.Source source) {
        String prefix = ComfyUiWorkflowDefinition.referenceKeyPrefix(source);
        // Historical image-only tasks used an ordered image array before named slots were introduced.
        if (source == ComfyUiWorkflowDefinition.Source.REFERENCE_IMAGE
                && values.propertyNames().stream().noneMatch(key -> key.startsWith(prefix)))
            return FrozenMediaInputs.images(context.lease()).stream().map(input -> uploaded.get(input.versionId().toString())).toList();
        var result = new ArrayList<String>();
        for (int index = 0; index < definition.referenceCount(source); index++) {
            String name = uploaded.get(values.path(prefix + index).asText());
            if (name == null) throw new IllegalStateException("Pinned ComfyUI slot has no authorized input");
            result.add(name);
        }
        return result;
    }

    private record Reference(FrozenMediaInputs.Image input, Asset.MediaKind kind) {}

    private List<Reference> references(AttemptContext context) {
        var result = new ArrayList<Reference>();
        FrozenMediaInputs.images(context.lease()).forEach(input -> result.add(new Reference(input, Asset.MediaKind.IMAGE)));
        FrozenMediaInputs.videos(context.lease()).forEach(input -> result.add(new Reference(input, Asset.MediaKind.VIDEO)));
        FrozenMediaInputs.audios(context.lease()).forEach(input -> result.add(new Reference(input, Asset.MediaKind.AUDIO)));
        return result;
    }

    /** Validate original archived bytes before any upload; names and paths never come from creator input. */
    private AssetService.AssetFile verifiedFile(AttemptContext context, Reference reference) {
        var version = artifacts.requireMediaVersionForTask(context.ownerId(), context.lease().projectId(),
                reference.input().versionId(), dev.agenvas.artifact.domain.Artifact.Kind.valueOf(reference.kind().name()));
        if (!version.artifactId().equals(reference.input().artifactId())) throw new IllegalStateException("Pinned ComfyUI reference identity differs");
        var file = assets.get(context.ownerId(), context.lease().projectId(), UUID.fromString(version.content().path("assetId").asText()));
        var asset = file.asset();
        ComfyUiWorkflowDefinition.validateMediaAsset(asset);
        if (asset.mediaKind() != reference.kind()) throw new IllegalStateException("Pinned ComfyUI reference type differs");
        try {
            if (Files.size(file.path()) != asset.byteSize()) throw new IllegalStateException("Pinned ComfyUI reference size differs");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var source = Files.newInputStream(file.path())) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = source.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            if (!asset.sha256().equals(HexFormat.of().formatHex(digest.digest()))) throw new IllegalStateException("Pinned ComfyUI reference digest differs");
            if (reference.kind() == Asset.MediaKind.IMAGE) {
                var image = ImageIO.read(file.path().toFile());
                if (image == null || image.getWidth() != asset.width() || image.getHeight() != asset.height())
                    throw new IllegalStateException("Pinned ComfyUI image dimensions differ");
            } else validateProbe(file);
            return file;
        } catch (IOException | NoSuchAlgorithmException failure) { throw new IllegalStateException("Cannot verify pinned ComfyUI reference", failure); }
    }

    private void validateProbe(AssetService.AssetFile file) {
        var probe = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error", "-show_entries",
                "stream=codec_type,width,height:format=format_name,duration", "-of", "json", file.path().toString())));
        var asset = file.asset();
        double seconds = probe.path("format").path("duration").asDouble(-1);
        if (!Double.isFinite(seconds) || seconds <= 0 || Math.abs(seconds * 1000 - asset.durationMs()) > 100)
            throw new IllegalStateException("Pinned ComfyUI media duration differs");
        int videos = 0, audios = 0;
        for (var stream : probe.path("streams")) {
            if ("audio".equals(stream.path("codec_type").asText())) audios++;
            if ("video".equals(stream.path("codec_type").asText())) {
                videos++;
                if (stream.path("width").asInt() != asset.width() || stream.path("height").asInt() != asset.height())
                    throw new IllegalStateException("Pinned ComfyUI video dimensions differ");
            }
        }
        String format = probe.path("format").path("format_name").asText();
        if (asset.mediaKind() == Asset.MediaKind.VIDEO ? videos != 1 || !format.contains("mp4")
                : videos != 0 || audios < 1 || !(format.contains("wav") || format.contains("mp3")))
            throw new IllegalStateException("Pinned ComfyUI media format differs");
    }

    public Submission reconcile(AttemptContext context, ComfyUiClient client, JsonNode settings) {
        var definition = ComfyUiWorkflowDefinition.parse(mapper, settings.get(ComfyUiWorkflowDefinition.SETTINGS_KEY), context.lease().kind());
        String promptId = context.originalRequestId();
        try {
            return switch (ComfyUiHistory.published(client.history(promptId), promptId, definition.output(), context.lease().kind())) {
                case ComfyUiHistory.PublishedPending ignored -> new Submission.Pending(clock.instant().plusSeconds(POLL_SECONDS));
                case ComfyUiHistory.PublishedFailed ignored -> new Submission.Rejected("PROVIDER_EXECUTION_FAILED");
                case ComfyUiHistory.PublishedReady ready -> new Submission.Completed(new MediaPayload(
                        client.output(ready.filename(), ready.subfolder()), ready.contentType()));
            };
        } catch (ComfyUiClient.ProtocolFailure invalid) { return new Submission.Blocked("PROVIDER_PROTOCOL_INVALID"); }
    }
}
