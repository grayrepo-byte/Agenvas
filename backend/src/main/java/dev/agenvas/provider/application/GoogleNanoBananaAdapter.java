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
import dev.agenvas.provider.infrastructure.GoogleNanoBananaClient;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** One approved Nano Banana 2 image generation or single-reference edit. */
@Component
public class GoogleNanoBananaAdapter implements MediaAdapter {
    private static final long MAX_REFERENCE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> INPUT_MIME_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectService projects;
    private final GoogleNanoBananaClient client;
    private final ObjectMapper mapper;

    public GoogleNanoBananaAdapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            ArtifactService artifacts, AssetService assets, ProjectService projects,
            GoogleNanoBananaClient client, ObjectMapper mapper) {
        this.catalog = catalog;
        this.cipher = cipher;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.client = client;
        this.mapper = mapper;
    }

    @Override public String adapterId() { return "GOOGLE_NANO_BANANA_2"; }

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
            aspectRatio(context);
            if (context.lease().input().has("referenceImageVersionId")) reference(context);
            return null;
        } catch (RuntimeException invalid) {
            return "PROVIDER_UNSUPPORTED_INPUT";
        }
    }

    @Override public Submission submit(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        String key = credential(snapshot);
        String configuredModel = modelName(snapshot);
        String prompt = context.lease().input().path("prompt").asText();
        String negative = context.lease().input().path("negativePrompt").asText("");
        if (!negative.isBlank()) prompt += "\nAvoid: " + negative;
        Reference reference = context.lease().input().has("referenceImageVersionId")
                ? reference(context) : null;
        try {
            return new Submission.Completed(client.generate(key, configuredModel,
                    snapshot.connectionVersion().origin(), prompt, aspectRatio(context),
                    reference == null ? null : reference.bytes(),
                    reference == null ? null : reference.mimeType()));
        } catch (GoogleNanoBananaClient.Rejected rejected) {
            return new Submission.Rejected("GOOGLE_IMAGE_REJECTED");
        } catch (GoogleNanoBananaClient.Uncertain uncertain) {
            return new Submission.Unknown("GOOGLE_IMAGE_SUBMISSION_UNKNOWN");
        }
    }

    @Override public Submission reconcile(AttemptContext context) {
        return new Submission.Blocked("GOOGLE_IMAGE_HAS_NO_STATUS_ENDPOINT");
    }

    private Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        Snapshot snapshot = catalog.snapshotAt(binding.capabilityId(),
                binding.capabilityVersion(), binding.connectionId(),
                binding.connectionVersion()).orElseThrow(() ->
                new IllegalStateException("Pinned media capability version is missing"));
        if (!adapterId().equals(snapshot.adapterId())
                || !binding.mappingSha256().equals(snapshot.mappingSha256())) {
            throw new IllegalStateException("Pinned Nano Banana mapping differs");
        }
        return snapshot;
    }

    private String credential(Snapshot snapshot) {
        var version = snapshot.connectionVersion();
        if (version.credentialCiphertext() == null || version.credentialNonce() == null
                || version.credentialKeyVersion() == null) {
            throw new IllegalStateException("Pinned Google credential is missing");
        }
        return cipher.decryptMedia(version.connectionId(), version.version(),
                new CredentialCipher.Encrypted(version.credentialCiphertext(),
                        version.credentialNonce(), version.credentialKeyVersion()));
    }

    /** 能力未配置模型名时回退到内置默认，中转站可覆盖。 */
    private String modelName(Snapshot snapshot) {
        String configured = mapper.readTree(snapshot.specJson()).path("settings")
                .path("model").asText("");
        return configured.isEmpty() ? GoogleNanoBananaClient.DEFAULT_MODEL : configured;
    }

    private String aspectRatio(AttemptContext context) {
        Project.AspectRatio ratio = projects.get(context.ownerId(),
                context.lease().projectId()).aspectRatio();
        return switch (ratio) {
            case LANDSCAPE_16_9 -> "16:9";
            case PORTRAIT_9_16 -> "9:16";
            case SQUARE_1_1 -> "1:1";
        };
    }

    /** Never accept a URL or an image outside the exact project and pinned version. */
    private Reference reference(AttemptContext context) {
        Task task = context.lease();
        UUID versionId = UUID.fromString(task.input().path("referenceImageVersionId").asText());
        ArtifactVersion version = artifacts.requireImageVersionForTask(context.ownerId(),
                task.projectId(), versionId);
        UUID assetId = UUID.fromString(version.content().path("assetId").asText());
        AssetService.AssetFile file = assets.get(context.ownerId(), task.projectId(), assetId);
        Asset asset = file.asset();
        if (asset.mediaKind() != Asset.MediaKind.IMAGE || asset.byteSize() < 1
                || asset.byteSize() > MAX_REFERENCE_BYTES
                || !INPUT_MIME_TYPES.contains(asset.contentType())) {
            throw new IllegalArgumentException("Pinned reference image is unsupported");
        }
        try {
            byte[] bytes = Files.readAllBytes(file.path());
            if (bytes.length != asset.byteSize() || bytes.length > MAX_REFERENCE_BYTES) {
                throw new IllegalArgumentException("Pinned reference image size changed");
            }
            return new Reference(bytes, asset.contentType());
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Pinned reference image cannot be read", invalid);
        }
    }

    private record Reference(byte[] bytes, String mimeType) {}
}
