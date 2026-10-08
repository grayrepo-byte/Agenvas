package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.storage.MediaRelayService;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.provider.infrastructure.OpenAiImage2Client;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Generates or edits one image with the approved ordered image versions. */
@Component
public class OpenAiImage2Adapter implements MediaAdapter {
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectService projects;
    private final OpenAiImage2Client client;
    private final ObjectMapper mapper;
    private final MediaRelayService relay;

    public OpenAiImage2Adapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            ArtifactService artifacts, AssetService assets, ProjectService projects,
            OpenAiImage2Client client, ObjectMapper mapper, MediaRelayService relay) {
        this.catalog = catalog;
        this.cipher = cipher;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.client = client;
        this.mapper = mapper;
        this.relay = relay;
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
            if (!FrozenMediaInputs.images(context.lease()).isEmpty()) {
                referencePngs(context);
                maskPng(context);
            }
        } catch (RuntimeException invalid) {
            return "PROVIDER_UNSUPPORTED_INPUT";
        }
        try {
            if (!FrozenMediaInputs.images(context.lease()).isEmpty() && relayProfile(context) != null)
                relay.preflightImage(relayProfile(context));
            return null;
        } catch (RuntimeException unavailable) {
            return ProviderFailureCodes.MEDIA_RELAY_PREPARATION_FAILED;
        }
    }

    @Override public Submission submit(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        String key = credential(snapshot);
        var settings = mapper.readTree(snapshot.specJson()).path("settings");
        ImageGenerationParameters parameters = parameters(context);
        String quality = parameters.quality();
        String configuredModel = settings.path("model").asText("");
        String model = configuredModel.isEmpty() ? OpenAiImage2Client.DEFAULT_MODEL
                : configuredModel;
        String prompt = context.lease().input().path("prompt").asText();
        String negative = context.lease().input().path("negativePrompt").asText("");
        if (!negative.isBlank()) prompt += "\nAvoid: " + negative;
        try {
            String origin = snapshot.connectionVersion().origin();
            if (FrozenMediaInputs.images(context.lease()).isEmpty())
                return new Submission.Completed(client.generate(key, model, prompt, quality, size(context),
                        parameters.transparentBackground(), origin));
            List<byte[]> references = referencePngs(context);
            byte[] mask = maskPng(context);
            UUID profile = relayProfile(context);
            if (profile == null) return new Submission.Completed(client.edit(key, model, prompt, quality,
                    size(context), references, mask, parameters.transparentBackground(), origin));
            List<String> urls;
            String maskUrl;
            try {
                urls = references.stream().map(png -> relay.signedImage(profile, png, "image/png")).toList();
                maskUrl = mask == null ? null : relay.signedImage(profile, mask, "image/png");
            } catch (RuntimeException preparationFailed) {
                // No paid generation request was sent, so preparation failure is never UNKNOWN.
                return new Submission.Rejected(ProviderFailureCodes.MEDIA_RELAY_PREPARATION_FAILED);
            }
            return new Submission.Completed(client.editUrls(key, model, prompt, quality, size(context),
                    urls, maskUrl, parameters.transparentBackground(), origin));
        } catch (OpenAiImage2Client.Rejected rejected) {
            return new Submission.Rejected("OPENAI_IMAGE_REJECTED");
        } catch (OpenAiImage2Client.Uncertain uncertain) {
            // 原样透传客户端区分出的原因码（超时/断线/协议不符/结果下载失败），
            // 不再统一改写成笼统的提交未知，否则用户无法判断该看哪一处。
            return new Submission.Unknown(uncertain.reasonCode());
        }
    }

    @Override public Submission reconcile(AttemptContext context) {
        return new Submission.Blocked("OPENAI_IMAGE_HAS_NO_STATUS_ENDPOINT");
    }

    private UUID relayProfile(AttemptContext context) {
        String id = context.lease().input().path("imageRelayProfileId").asText("");
        return id.isBlank() ? null : UUID.fromString(id);
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
        ImageGenerationParameters parameters = parameters(context);
        String ratio = parameters.aspectRatio();
        if (ImageGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio)) {
            ratio = switch (projects.get(context.ownerId(), context.lease().projectId()).aspectRatio()) {
                case LANDSCAPE_16_9 -> "16:9";
                case PORTRAIT_9_16 -> "9:16";
                case SQUARE_1_1 -> "1:1";
            };
        }
        return openAiSize(ratio, parameters.resolution());
    }

    private ImageGenerationParameters parameters(AttemptContext context) {
        return ImageGenerationParameters.parse(
                context.lease().input().path("mediaInput").path("parameters"));
    }

    static String openAiSize(String ratio, String resolution) {
        String key = resolution + ":" + ratio;
        return switch (key) {
            case "1K:1:1" -> "1024x1024";
            case "1K:2:3" -> "832x1248";
            case "1K:3:2" -> "1248x832";
            case "1K:3:4" -> "912x1216";
            case "1K:4:3" -> "1216x912";
            case "1K:9:16" -> "720x1280";
            case "1K:16:9" -> "1280x720";
            case "1K:21:9" -> "1344x576";
            case "2K:1:1" -> "2048x2048";
            case "2K:2:3" -> "1664x2496";
            case "2K:3:2" -> "2496x1664";
            case "2K:3:4" -> "1824x2432";
            case "2K:4:3" -> "2432x1824";
            case "2K:9:16" -> "1440x2560";
            case "2K:16:9" -> "2560x1440";
            case "2K:21:9" -> "2688x1152";
            case "4K:1:1" -> "2880x2880";
            case "4K:2:3" -> "2336x3504";
            case "4K:3:2" -> "3504x2336";
            case "4K:3:4" -> "2448x3264";
            case "4K:4:3" -> "3264x2448";
            case "4K:9:16" -> "2160x3840";
            case "4K:16:9" -> "3840x2160";
            case "4K:21:9" -> "3808x1632";
            default -> throw new IllegalArgumentException("Unsupported GPT Image dimensions");
        };
    }

    /** Resolve every exact same-project version in the task's frozen order. */
    private List<byte[]> referencePngs(AttemptContext context) {
        Task task = context.lease();
        List<FrozenMediaInputs.Image> inputs = FrozenMediaInputs.images(task);
        if (inputs.isEmpty()
                || inputs.size() > MediaAdapterRegistry.OPENAI_MAX_REFERENCE_IMAGES) {
            throw new IllegalArgumentException("Pinned reference image count is unsupported");
        }
        String outputSize = size(context);
        String[] dimensions = outputSize.split("x", 2);
        int width = Integer.parseInt(dimensions[0]);
        int height = Integer.parseInt(dimensions[1]);
        long totalBytes = 0;
        List<byte[]> result = new ArrayList<>(inputs.size());
        for (FrozenMediaInputs.Image input : inputs) {
            byte[] png = referencePng(context, task, input, width, height);
            totalBytes += png.length;
            if (totalBytes > OpenAiImage2Client.MAX_REFERENCE_TOTAL_BYTES) {
                throw new IllegalArgumentException("Pinned reference PNG total size is invalid");
            }
            result.add(png);
        }
        return List.copyOf(result);
    }

    private byte[] referencePng(AttemptContext context, Task task,
            FrozenMediaInputs.Image input, int width, int height) {
        ArtifactVersion version = artifacts.requireImageVersionForTask(context.ownerId(),
                task.projectId(), input.versionId());
        if (!version.artifactId().equals(input.artifactId())) {
            throw new IllegalArgumentException("Pinned reference artifact identity changed");
        }
        UUID assetId = UUID.fromString(version.content().path("assetId").asText());
        AssetService.AssetFile file = assets.get(context.ownerId(), task.projectId(), assetId);
        if (file.asset().mediaKind() != Asset.MediaKind.IMAGE) {
            throw new IllegalArgumentException("Pinned reference Asset is not an image");
        }
        try {
            BufferedImage source = ImageIO.read(file.path().toFile());
            if (source == null) throw new IllegalArgumentException("Reference cannot be decoded");
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
            if (!ImageIO.write(output, "png", bytes)
                    || bytes.size() > OpenAiImage2Client.MAX_REFERENCE_BYTES) {
                throw new IllegalArgumentException("Reference PNG exceeds adapter bound");
            }
            return bytes.toByteArray();
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Reference cannot be read", invalid);
        }
    }

    /** Resizes the immutable alpha mask with the same contain transform as the first input image. */
    private byte[] maskPng(AttemptContext context) {
        String maskAssetId = context.lease().input().path("imageOperation")
                .path("maskAssetId").asText("");
        if (maskAssetId.isBlank()) return null;
        AssetService.AssetFile file = assets.get(context.ownerId(),
                context.lease().projectId(), UUID.fromString(maskAssetId));
        Asset mask = file.asset();
        if (mask.mediaKind() != Asset.MediaKind.IMAGE
                || !"image/png".equals(mask.contentType())
                || mask.byteSize() < 1 || mask.byteSize() > OpenAiImage2Client.MAX_MASK_BYTES) {
            throw new IllegalArgumentException("Pinned image edit mask is unsupported");
        }
        String[] dimensions = size(context).split("x", 2);
        int width = Integer.parseInt(dimensions[0]);
        int height = Integer.parseInt(dimensions[1]);
        try {
            BufferedImage source = ImageIO.read(file.path().toFile());
            if (source == null || !source.getColorModel().hasAlpha()) {
                throw new IllegalArgumentException("Pinned image edit mask has no alpha channel");
            }
            BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.setColor(new Color(255, 255, 255, 255));
                graphics.fillRect(0, 0, width, height);
                double scale = Math.min((double) width / source.getWidth(),
                        (double) height / source.getHeight());
                int scaledWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int scaledHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, (width - scaledWidth) / 2,
                        (height - scaledHeight) / 2, scaledWidth, scaledHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(output, "png", bytes)
                    || bytes.size() > OpenAiImage2Client.MAX_MASK_BYTES) {
                throw new IllegalArgumentException("Pinned image edit mask exceeds provider bound");
            }
            return bytes.toByteArray();
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("Pinned image edit mask cannot be read", unreadable);
        }
    }
}
