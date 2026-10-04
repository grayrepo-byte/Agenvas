package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.ComfyUiWorkflowDefinition;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiHistory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
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

    public ComfyUiPublishedWorkflow(ArtifactService artifacts, AssetService assets, ObjectMapper mapper, Clock clock) {
        this.artifacts = artifacts;
        this.assets = assets;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Submission submit(AttemptContext context, ComfyUiClient client, JsonNode settings) {
        var task = context.lease();
        var definition = ComfyUiWorkflowDefinition.parse(mapper, settings.get(ComfyUiWorkflowDefinition.SETTINGS_KEY), task.kind());
        var references = FrozenMediaInputs.images(task);
        definition.requireReferences(references.size());
        var uploaded = new ArrayList<String>();
        // Only authorized immutable project Assets can supply a mapped image filename.
        for (var reference : references) {
            var version = artifacts.requireImageVersionForTask(context.ownerId(), task.projectId(), reference.versionId());
            var file = assets.get(context.ownerId(), task.projectId(), UUID.fromString(version.content().path("assetId").asText()));
            if (file.asset().mediaKind() != Asset.MediaKind.IMAGE) throw new IllegalStateException("Pinned ComfyUI input is not an image");
            byte[] bytes;
            try {
                var image = ImageIO.read(file.path().toFile());
                if (image == null) throw new IllegalStateException("Pinned ComfyUI image cannot be decoded");
                var buffer = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "png", buffer)) throw new IllegalStateException("PNG encoder unavailable");
                bytes = buffer.toByteArray();
            } catch (IOException failure) { throw new IllegalStateException("Cannot encode pinned ComfyUI image", failure); }
            UUID uploadId = UUID.nameUUIDFromBytes((context.requestKey() + ":" + reference.order()).getBytes(StandardCharsets.UTF_8));
            uploaded.add(client.uploadImage(uploadId, bytes, "png"));
        }
        JsonNode frozen = task.input().path("mediaInput").path("providerParameters");
        if (!frozen.path("width").isIntegralNumber() || !frozen.path("height").isIntegralNumber())
            throw new IllegalStateException("Pinned ComfyUI dimensions are missing");
        UUID requestKey = UUID.fromString(context.requestKey());
        var graph = definition.render(mapper, task.input().path("prompt").asText(), task.input().path("negativePrompt").asText(""),
                requestKey.getMostSignificantBits() & Long.MAX_VALUE,
                new ComfyUiWorkflowDefinition.Dimensions(frozen.path("width").intValue(), frozen.path("height").intValue()),
                task.input().path("durationSeconds").asInt(0), uploaded);
        return new Submission.Accepted(client.submit(graph, requestKey).toString());
    }

    public Submission reconcile(AttemptContext context, ComfyUiClient client, JsonNode settings) {
        var definition = ComfyUiWorkflowDefinition.parse(mapper, settings.get(ComfyUiWorkflowDefinition.SETTINGS_KEY), context.lease().kind());
        UUID promptId = UUID.fromString(context.originalRequestId());
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
