package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.GenerationGateway;
import dev.agenvas.provider.domain.GenerationRequest;
import dev.agenvas.provider.domain.GenerationResult;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 从任务固定的归档输入图片生成明确标为演示素材的 MP4，不调用真实视频模型。 */
@Component
public class MockVideoRenderer {

    /** 查询任务所有者并校验已固定的生成输入。 */
    private final TaskService tasks;
    /** 读取任务固定的输入图片历史版本。 */
    private final ArtifactService artifacts;
    /** 读取输入图片资产并幂等归档输出视频。 */
    private final AssetService assets;
    private final ProjectService projects;
    /** 通过受控 FFmpeg 参数合成演示视频。 */
    private final MediaToolRunner mediaTools;
    /** 产生本地演示结果，不依赖外部媒体 Provider。 */
    private final GenerationGateway gateway;
    /** 选择应用内置或部署提供的演示图片素材。 */
    private final MockProviderProperties fixture;
    /** 构造生成结果中的 JSON 内容。 */
    private final ObjectMapper mapper;

    /** 组装演示视频渲染器 的任务、素材和固定媒体工具依赖。 */
    public MockVideoRenderer(TaskService tasks, ArtifactService artifacts, AssetService assets,
            ProjectService projects,
            MediaToolRunner mediaTools, GenerationGateway gateway,
            MockProviderProperties fixture, ObjectMapper mapper) {
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.mediaTools = mediaTools;
        this.gateway = gateway;
        this.fixture = fixture;
        this.mapper = mapper;
    }

    /** 在同步演示渲染前已有持久请求键，成功结果再由统一执行管线凭租约提交。 */
    Submission executeBound(Task task, UUID requestKey) {
        GenerationResult result = gateway.submit(new GenerationRequest(task.projectId(),
                requestKey.toString(), fixture.fixture()));
        return switch (result.status()) {
            case COMPLETED -> completed(task, result);
            case FAILED -> new Submission.Rejected(result.errorCode());
            case ACCEPTED -> throw new IllegalStateException(
                    "Mock video adapter unexpectedly returned asynchronous acceptance");
            case UNKNOWN -> throw new IllegalStateException(
                    "Mock video submission outcome is intentionally ambiguous");
        };
    }

    /** 仅读取任务固定的输入图片版本，再归档 FFmpeg 实际生成的 MP4 文件。 */
    private Submission.CompletedArtifact completed(Task task, GenerationResult result) {
        if (!result.demoOutput()) {
            throw new IllegalStateException("Mock video result lacks the demo marker");
        }
        UUID ownerId = tasks.ownerForWorker(task);
        var firstImage = task.input().path("mediaInput").path("images").get(0);
        AssetService.AssetFile input = null;
        if (firstImage != null) {
            UUID imageId = UUID.fromString(firstImage.path("artifactId").asText());
            UUID imageVersionId = UUID.fromString(firstImage.path("versionId").asText());
            ArtifactVersion image = artifacts.requireVersion(ownerId, task.projectId(),
                    imageId, imageVersionId);
            UUID imageAssetId = UUID.fromString(image.content().path("assetId").asText());
            input = assets.get(ownerId, task.projectId(), imageAssetId);
            if (input.asset().mediaKind() != Asset.MediaKind.IMAGE) {
                throw new IllegalStateException("Pinned video input is not an archived image");
            }
        }
        String ratio = resolvedRatio(ownerId, task);
        int[] dimensions = switch (ratio) {
            case VideoGenerationParameters.PORTRAIT_ASPECT_RATIO -> new int[] {360, 640};
            case VideoGenerationParameters.SQUARE_ASPECT_RATIO -> new int[] {512, 512};
            default -> new int[] {640, 360};
        };
        Duration duration = VideoDuration.fromFrozenTask(task.input());
        Path rendered;
        try {
            rendered = Files.createTempFile("agenvas-demo-video-", ".mp4");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create demo video output", exception);
        }
        try {
            String seconds = Long.toString(duration.toSeconds());
            String size = dimensions[0] + "x" + dimensions[1];
            List<String> command = input == null
                    ? List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                            "-f", "lavfi", "-i", "color=c=#24233d:s=" + size + ":r=24",
                            "-t", seconds, "-vf", "format=yuv420p", "-an", "-c:v", "libx264",
                            "-preset", "veryfast", "-crf", "25", "-movflags", "+faststart",
                            "-y", rendered.toString())
                    : List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                            "-loop", "1", "-framerate", "24", "-i", input.path().toString(),
                            "-t", seconds, "-vf", "scale=" + dimensions[0] + ":"
                                    + dimensions[1] + ":force_original_aspect_ratio=decrease,"
                                    + "pad=" + dimensions[0] + ":" + dimensions[1]
                                    + ":(ow-iw)/2:(oh-ih)/2,format=yuv420p",
                            "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "25",
                            "-movflags", "+faststart", "-y", rendered.toString());
            var audioInputs = FrozenMediaInputs.audios(task);
            if (!audioInputs.isEmpty()) {
                List<String> withAudio = new java.util.ArrayList<>(command);
                int inputEnd = withAudio.indexOf("-t");
                List<String> audioArguments = new java.util.ArrayList<>();
                for (var audio : audioInputs) {
                    var version = artifacts.requireVersion(ownerId, task.projectId(), audio.artifactId(), audio.versionId());
                    var file = assets.get(ownerId, task.projectId(), UUID.fromString(version.content().path("assetId").asText()));
                    if (file.asset().mediaKind() != Asset.MediaKind.AUDIO) throw new IllegalArgumentException("Audio reference invalid");
                    audioArguments.addAll(List.of("-stream_loop", "-1", "-i", file.path().toString()));
                }
                withAudio.addAll(inputEnd, audioArguments);
                int silentFlag = withAudio.indexOf("-an");
                withAudio.remove(silentFlag);
                StringBuilder mix = new StringBuilder();
                for (int index = 0; index < audioInputs.size(); index++) mix.append('[').append(index + 1).append(":a:0]");
                mix.append("amix=inputs=").append(audioInputs.size()).append(":duration=longest[mixed]");
                withAudio.addAll(silentFlag, List.of("-filter_complex", mix.toString(), "-map", "0:v:0", "-map", "[mixed]", "-c:a", "aac"));
                command = withAudio;
            }
            mediaTools.ffmpeg(command);
            Asset archived = assets.archiveTaskVideo(ownerId, task.projectId(), task.id(),
                    () -> {
                        try {
                            return Files.newInputStream(rendered);
                        } catch (IOException failure) {
                            throw new IllegalStateException("Cannot read generated demo MP4", failure);
                        }
                    });
            ObjectNode content = MediaResult.content(mapper, task, archived.id(),
                    task.input().path("prompt").asText("Mock video"));
            ObjectNode parameters = MediaResult.copyFrozenParameters(content, task);
            parameters.put("mock", true);
            parameters.put("displayLabel", "演示视频（非 AI 生成）");
            parameters.put("providerRequestId", result.providerRequestId());
            return new Submission.CompletedArtifact(content);
        } finally {
            try {
                Files.deleteIfExists(rendered);
            } catch (IOException ignored) {
                // Temporary output is never the durable archived Asset.
            }
        }
    }

    private String resolvedRatio(UUID ownerId, Task task) {
        String requested = VideoGenerationParameters.parse(
                task.input().path("mediaInput").path("parameters")).aspectRatio();
        if (!VideoGenerationParameters.AUTO_ASPECT_RATIO.equals(requested)) return requested;
        Project.AspectRatio projectRatio = projects.get(ownerId, task.projectId()).aspectRatio();
        return switch (projectRatio) {
            case LANDSCAPE_16_9 -> VideoGenerationParameters.LANDSCAPE_ASPECT_RATIO;
            case PORTRAIT_9_16 -> VideoGenerationParameters.PORTRAIT_ASPECT_RATIO;
            case SQUARE_1_1 -> VideoGenerationParameters.SQUARE_ASPECT_RATIO;
        };
    }
}
