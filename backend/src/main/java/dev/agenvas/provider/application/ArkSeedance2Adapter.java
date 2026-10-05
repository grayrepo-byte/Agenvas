package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.ArkSeedanceClient;
import dev.agenvas.provider.infrastructure.ArkMediaDownloadPolicy;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixed Seedance 2.0 task with exact image/audio bytes and signed video references. */
@Component
public class ArkSeedance2Adapter implements MediaAdapter {
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectService projects;
    private final ArkSeedanceClient client;
    private final ArkMediaDownloadPolicy downloads;
    private final MediaToolRunner mediaTools;
    private final ObjectMapper mapper;
    private final AudioReferenceLoader audioReferences;
    private final SeedanceVideoReferenceLoader videoReferences;

    public ArkSeedance2Adapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            ArtifactService artifacts, AssetService assets, ProjectService projects,
            ArkSeedanceClient client, ArkMediaDownloadPolicy downloads,
            MediaToolRunner mediaTools, ObjectMapper mapper, AudioReferenceLoader audioReferences, SeedanceVideoReferenceLoader videoReferences) {
        this.catalog = catalog;
        this.cipher = cipher;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.client = client;
        this.downloads = downloads;
        this.mediaTools = mediaTools;
        this.mapper = mapper;
        this.audioReferences = audioReferences;
        this.videoReferences = videoReferences;
    }

    @Override public String adapterId() { return "ARK_SEEDANCE_2_I2V"; }

    @Override public boolean supports(PortInput input) {
        return input.kind() == Task.Kind.VIDEO_GENERATION
                && input.durationSeconds() >= 4 && input.durationSeconds() <= 15;
    }

    @Override public String preflightFailure(AttemptContext context) {
        try {
            credential(snapshot(context));
        } catch (RuntimeException unavailable) {
            return "MEDIA_CREDENTIAL_UNAVAILABLE";
        }
        try {
            int seconds = context.lease().input().path("durationSeconds").asInt(-1);
            if (seconds < 4 || seconds > 15) return "PROVIDER_UNSUPPORTED_INPUT";
            references(context, false);
            videoReferences.preflight(context);
            return null;
        } catch (dev.agenvas.shared.error.ApiProblemException invalid) {
            return java.util.Set.of("SEEDANCE_VIDEO_REFERENCE_INVALID", "MEDIA_RELAY_REQUIRED",
                    "MEDIA_RELAY_PUBLIC_ENDPOINT_REQUIRED").contains(invalid.code())
                    ? invalid.code() : "PROVIDER_UNSUPPORTED_INPUT";
        } catch (RuntimeException invalid) {
            return "PROVIDER_UNSUPPORTED_INPUT";
        }
    }

    @Override public Submission submit(AttemptContext context) {
        String key = credential(snapshot(context));
        Task task = context.lease();
        String prompt = task.input().path("prompt").asText();
        String negative = task.input().path("negativePrompt").asText("");
        if (!negative.isBlank()) prompt += "\nAvoid: " + negative;
        try {
            List<ArkSeedanceClient.Reference> references;
            try { references = references(context, true); }
            catch (RuntimeException unavailable) {
                // No Ark create call occurred. A relay PUT failure cannot be a generation UNKNOWN.
                return new Submission.Rejected("MEDIA_REFERENCE_PREPARATION_FAILED");
            }
            String id = client.create(key, prompt, references,
                    task.input().path("durationSeconds").asInt(-1), ratio(context), hasAudioReferences(context));
            return new Submission.Accepted(id);
        } catch (ArkSeedanceClient.Rejected rejected) {
            return new Submission.Rejected("ARK_CREATE_REJECTED");
        } catch (ArkSeedanceClient.Uncertain uncertain) {
            return new Submission.Unknown("ARK_CREATE_UNCERTAIN");
        }
    }

    @Override public Submission reconcile(AttemptContext context) {
        try {
            ArkSeedanceClient.TaskState state = client.query(
                    credential(snapshot(context)), context.originalRequestId());
            return switch (state.status()) {
                case "queued", "running" -> new Submission.Pending(Instant.now().plusSeconds(5));
                case "failed", "cancelled" -> new Submission.Rejected("ARK_TASK_FAILED");
                case "expired" -> new Submission.Blocked("ARK_TASK_EXPIRED");
                case "succeeded" -> completed(context, state);
                default -> new Submission.Blocked("PROVIDER_PROTOCOL_INVALID");
            };
        } catch (ArkSeedanceClient.ProtocolFailure invalid) {
            return new Submission.Blocked("PROVIDER_PROTOCOL_INVALID");
        } catch (ArkMediaDownloadPolicy.Rejected rejected) {
            return new Submission.Blocked(rejected.getMessage());
        } catch (InvalidMedia invalid) {
            return new Submission.Blocked("ARK_MEDIA_INVALID");
        }
    }

    /** An expired TOS URL can only be refreshed by querying the same persisted task ID. */
    private Submission completed(AttemptContext context, ArkSeedanceClient.TaskState state) {
        if (state.videoUrl() == null || state.videoUrl().isBlank()) {
            return new Submission.Blocked("ARK_RESULT_MISSING_URL");
        }
        URI url;
        try {
            url = URI.create(state.videoUrl());
        } catch (IllegalArgumentException invalid) {
            return new Submission.Blocked("ARK_MEDIA_URL_REJECTED");
        }
        try {
            return new Submission.Completed(downloadVideo(url,
                    context.lease().input().path("durationSeconds").asInt(-1), hasAudioReferences(context)));
        } catch (ArkMediaDownloadPolicy.Expired expired) {
            ArkSeedanceClient.TaskState refreshed = client.query(
                    credential(snapshot(context)), context.originalRequestId());
            if (!"succeeded".equals(refreshed.status()) || refreshed.videoUrl() == null
                    || refreshed.videoUrl().equals(state.videoUrl())) {
                return new Submission.Blocked("ARK_MEDIA_URL_EXPIRED");
            }
            try {
                return new Submission.Completed(downloadVideo(URI.create(refreshed.videoUrl()),
                        context.lease().input().path("durationSeconds").asInt(-1), hasAudioReferences(context)));
            } catch (IllegalArgumentException | ArkMediaDownloadPolicy.Expired invalid) {
                return new Submission.Blocked("ARK_MEDIA_URL_EXPIRED");
            }
        }
    }

    private MediaPayload downloadVideo(URI url, int expectedSeconds, boolean preserveAudio) {
        downloads.validate(url);
        Path directory;
        try {
            directory = Files.createTempDirectory("agenvas-ark-result-");
        } catch (IOException unavailable) {
            throw new ArkMediaDownloadPolicy.TechnicalFailure("Ark scratch unavailable");
        }
        Path raw = directory.resolve("result.mp4");
        Path silent = directory.resolve("silent.mp4");
        try {
            try (OutputStream sink = Files.newOutputStream(raw)) {
                downloads.download(url, sink, 500L * 1024 * 1024);
            }
            VideoProbe original = probe(raw, expectedSeconds);
            Path selected = raw;
            if (original.hasAudio() && !preserveAudio) {
                mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                        "-i", raw.toString(), "-map", "0:v:0", "-an", "-c:v", "copy",
                        "-movflags", "+faststart", "-y", silent.toString()));
                if (probe(silent, expectedSeconds).hasAudio()) {
                    throw new InvalidMedia();
                }
                selected = silent;
            }
            InputStream file = Files.newInputStream(selected);
            return new MediaPayload(new FilterInputStream(file) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { cleanup(raw, silent, directory); }
                }
            }, "video/mp4");
        } catch (IOException failure) {
            cleanup(raw, silent, directory);
            throw new ArkMediaDownloadPolicy.TechnicalFailure("Ark scratch write failed");
        } catch (MediaToolRunner.MediaToolException failure) {
            cleanup(raw, silent, directory);
            if (failure.invalidInput()) throw new InvalidMedia();
            throw failure;
        } catch (RuntimeException failure) {
            cleanup(raw, silent, directory);
            throw failure;
        }
    }

    private VideoProbe probe(Path file, int expectedSeconds) {
        JsonNode details = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error",
                "-show_entries", "stream=codec_type:format=format_name,duration",
                "-of", "json", file.toString())));
        double duration = details.path("format").path("duration").asDouble(-1);
        String format = details.path("format").path("format_name").asText();
        boolean video = false;
        boolean audio = false;
        for (JsonNode stream : details.path("streams")) {
            video |= "video".equals(stream.path("codec_type").asText());
            audio |= "audio".equals(stream.path("codec_type").asText());
        }
        if (!video || !format.contains("mp4") || !Double.isFinite(duration)
                || expectedSeconds < 4 || expectedSeconds > 15
                || duration < expectedSeconds - 1.0 || duration > expectedSeconds + 1.5) {
            throw new InvalidMedia();
        }
        return new VideoProbe(audio);
    }

    private static void cleanup(Path raw, Path silent, Path directory) {
        try {
            Files.deleteIfExists(raw);
            Files.deleteIfExists(silent);
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Scratch cleanup is best effort after the response stream has been closed.
        }
    }

    private record VideoProbe(boolean hasAudio) {}
    private static final class InvalidMedia extends RuntimeException {}

    private Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        Snapshot snapshot = catalog.snapshotAt(binding.capabilityId(),
                binding.capabilityVersion(), binding.connectionId(),
                binding.connectionVersion()).orElseThrow(() ->
                new IllegalStateException("Pinned media capability version is missing"));
        if (!adapterId().equals(snapshot.adapterId())
                || !binding.mappingSha256().equals(snapshot.mappingSha256())) {
            throw new IllegalStateException("Pinned Seedance mapping differs");
        }
        return snapshot;
    }

    private String credential(Snapshot snapshot) {
        var version = snapshot.connectionVersion();
        if (version.credentialCiphertext() == null || version.credentialNonce() == null
                || version.credentialKeyVersion() == null) {
            throw new IllegalStateException("Pinned Ark credential is missing");
        }
        return cipher.decryptMedia(version.connectionId(), version.version(),
                new CredentialCipher.Encrypted(version.credentialCiphertext(),
                        version.credentialNonce(), version.credentialKeyVersion()));
    }

    private Project.AspectRatio aspect(AttemptContext context) {
        return projects.get(context.ownerId(), context.lease().projectId()).aspectRatio();
    }

    private String ratio(AttemptContext context) {
        String requested = VideoGenerationParameters.parse(context.lease().input()
                .path("mediaInput").path("parameters")).aspectRatio();
        if (!VideoGenerationParameters.AUTO_ASPECT_RATIO.equals(requested)) return requested;
        return switch (aspect(context)) {
            case LANDSCAPE_16_9 -> VideoGenerationParameters.LANDSCAPE_ASPECT_RATIO;
            case PORTRAIT_9_16 -> VideoGenerationParameters.PORTRAIT_ASPECT_RATIO;
            case SQUARE_1_1 -> VideoGenerationParameters.SQUARE_ASPECT_RATIO;
        };
    }

    private boolean hasAudioReferences(AttemptContext context) {
        return !FrozenMediaInputs.audios(context.lease()).isEmpty();
    }

    private List<ArkSeedanceClient.Reference> references(AttemptContext context, boolean submitting) {
        var task = context.lease();
        var images = FrozenMediaInputs.images(task);
        var videos = FrozenMediaInputs.videos(task);
        var audios = audioReferences.load(context.ownerId(), task, false);
        String mode = task.input().path("mediaInput").path("mode").asText();
        if (images.size() > 9 || !audios.isEmpty() && (images.isEmpty() && videos.isEmpty() || !"GENERAL_REFERENCE".equals(mode)))
            throw new IllegalArgumentException("Seedance audio requires a visual reference in general mode");
        var result = new java.util.ArrayList<ArkSeedanceClient.Reference>();
        for (var image : images) {
            String role = switch (image.role()) {
                case "START_FRAME" -> "first_frame";
                case "END_FRAME" -> "last_frame";
                case "REFERENCE" -> "reference_image";
                default -> throw new IllegalArgumentException("Unsupported image role");
            };
            result.add(new ArkSeedanceClient.Reference("image/png", pinnedFrame(context, image), role));
        }
        for (var audio : audios) result.add(new ArkSeedanceClient.Reference(audio.contentType(), audio.bytes(), "reference_audio"));
        if (!videos.isEmpty() && !"GENERAL_REFERENCE".equals(mode))
            throw new IllegalArgumentException("Seedance video references require general mode");
        if (submitting) result.addAll(videoReferences.load(context));
        return List.copyOf(result);
    }

    /** Send the exact pinned input image version as a bounded normalized PNG data URL. */
    private byte[] pinnedFrame(AttemptContext context, FrozenMediaInputs.Image image) {
        Task task = context.lease();
        UUID imageId = image.artifactId();
        UUID versionId = image.versionId();
        ArtifactVersion version = artifacts.requireVersion(context.ownerId(), task.projectId(),
                imageId, versionId);
        UUID assetId = UUID.fromString(version.content().path("assetId").asText());
        AssetService.AssetFile file = assets.get(context.ownerId(), task.projectId(), assetId);
        if (file.asset().mediaKind() != Asset.MediaKind.IMAGE) {
            throw new IllegalArgumentException("Pinned video input is not an image Asset");
        }
        try {
            BufferedImage source = ImageIO.read(file.path().toFile());
            if (source == null) throw new IllegalArgumentException(
                    "Pinned video input cannot be decoded");
            int width = aspect(context) == Project.AspectRatio.PORTRAIT_9_16 ? 720
                    : aspect(context) == Project.AspectRatio.SQUARE_1_1 ? 1024 : 1280;
            int height = aspect(context) == Project.AspectRatio.PORTRAIT_9_16 ? 1280
                    : aspect(context) == Project.AspectRatio.SQUARE_1_1 ? 1024 : 720;
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
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(output, "png", png) || png.size() >= 30 * 1024 * 1024) {
                throw new IllegalArgumentException(
                        "Pinned video input exceeds Seedance input bound");
            }
            return png.toByteArray();
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Pinned video input cannot be read", invalid);
        }
    }
}
