package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JdbcMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JdbcMediaCapabilityRepository.Snapshot;
import dev.agenvas.provider.infrastructure.OpenAiImage2Client;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Generates or edits one image with the approved fixed connection and image version. */
@Component
public class OpenAiImage2Adapter implements MediaAdapter {
    private final JdbcMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectService projects;
    private final OpenAiImage2Client client;
    private final ObjectMapper mapper;

    public OpenAiImage2Adapter(JdbcMediaCapabilityRepository catalog, CredentialCipher cipher,
            ArtifactService artifacts, AssetService assets, ProjectService projects,
            OpenAiImage2Client client, ObjectMapper mapper) {
        this.catalog = catalog;
        this.cipher = cipher;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.client = client;
        this.mapper = mapper;
    }

    @Override public String adapterId() { return "OPENAI_GPT_IMAGE_2"; }

    @Override public boolean supports(PortInput input) {
        return input.kind() == Task.Kind.IMAGE_GENERATION;
    }

    @Override public String preflightFailure(AttemptContext context) {
        try {
            credential(snapshot(context));
        } catch (RuntimeException unavailable) {
            return "MEDIA_CREDENTIAL_UNAVAILABLE";
        }
        try {
            size(context);
            if (context.lease().input().has("referenceImageVersionId")) {
                referencePng(context);
            }
            return null;
        } catch (RuntimeException invalid) {
            return "PROVIDER_UNSUPPORTED_INPUT";
        }
    }

    @Override public Submission submit(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        String key = credential(snapshot);
        String quality = mapper.readTree(snapshot.specJson()).path("settings")
                .path("quality").asText("medium");
        String prompt = context.lease().input().path("prompt").asText();
        String negative = context.lease().input().path("negativePrompt").asText("");
        if (!negative.isBlank()) prompt += "\nAvoid: " + negative;
        try {
            return new Submission.Completed(context.lease().input().has("referenceImageVersionId")
                    ? client.edit(key, prompt, quality, size(context), referencePng(context),
                            snapshot.connectionVersion().origin())
                    : client.generate(key, prompt, quality, size(context),
                            snapshot.connectionVersion().origin()));
        } catch (OpenAiImage2Client.Rejected rejected) {
            return new Submission.Rejected("OPENAI_IMAGE_REJECTED");
        } catch (OpenAiImage2Client.Uncertain uncertain) {
            return new Submission.Unknown("OPENAI_IMAGE_SUBMISSION_UNKNOWN");
        }
    }

    @Override public Submission reconcile(AttemptContext context) {
        return new Submission.Blocked("OPENAI_IMAGE_HAS_NO_STATUS_ENDPOINT");
    }

    private Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        Snapshot snapshot = catalog.snapshotAt(binding.capabilityId(),
                binding.capabilityVersion(), binding.connectionId(),
                binding.connectionVersion()).orElseThrow(() ->
                new IllegalStateException("Pinned media capability version is missing"));
        if (!adapterId().equals(snapshot.adapterId())
                || !binding.mappingSha256().equals(snapshot.mappingSha256())) {
            throw new IllegalStateException("Pinned GPT Image 2 mapping differs");
        }
        return snapshot;
    }

    private String credential(Snapshot snapshot) {
        var version = snapshot.connectionVersion();
        if (version.credentialCiphertext() == null || version.credentialNonce() == null
                || version.credentialKeyVersion() == null) {
            throw new IllegalStateException("Pinned GPT Image 2 credential is missing");
        }
        return cipher.decryptMedia(version.connectionId(), version.version(),
                new CredentialCipher.Encrypted(version.credentialCiphertext(),
                        version.credentialNonce(), version.credentialKeyVersion()));
    }

    private String size(AttemptContext context) {
        Project.AspectRatio ratio = projects.get(context.ownerId(),
                context.lease().projectId()).aspectRatio();
        return switch (ratio) {
            case LANDSCAPE_16_9 -> "1536x1024";
            case PORTRAIT_9_16 -> "1024x1536";
            case SQUARE_1_1 -> "1024x1024";
        };
    }

    /** Resolve only the exact same-project image version approved in the task input. */
    private byte[] referencePng(AttemptContext context) {
        Task task = context.lease();
        UUID versionId = UUID.fromString(task.input().path("referenceImageVersionId").asText());
        ArtifactVersion version = artifacts.requireImageVersionForTask(context.ownerId(),
                task.projectId(), versionId);
        UUID assetId = UUID.fromString(version.content().path("assetId").asText());
        AssetService.AssetFile file = assets.get(context.ownerId(), task.projectId(), assetId);
        if (file.asset().mediaKind() != Asset.MediaKind.IMAGE) {
            throw new IllegalArgumentException("Pinned reference Asset is not an image");
        }
        try {
            BufferedImage source = ImageIO.read(file.path().toFile());
            if (source == null) throw new IllegalArgumentException("Reference cannot be decoded");
            int width = size(context).startsWith("1536") ? 1536 : 1024;
            int height = size(context).endsWith("1536") ? 1536 : 1024;
            BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                double scale = Math.min((double) width / source.getWidth(),
                        (double) height / source.getHeight());
                int scaledWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int scaledHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, (width - scaledWidth) / 2,
                        (height - scaledHeight) / 2, scaledWidth, scaledHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(output, "png", bytes) || bytes.size() > 20 * 1024 * 1024) {
                throw new IllegalArgumentException("Reference PNG exceeds adapter bound");
            }
            return bytes.toByteArray();
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Reference cannot be read", invalid);
        }
    }
}
