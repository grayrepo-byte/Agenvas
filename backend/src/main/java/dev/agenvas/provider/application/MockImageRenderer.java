package dev.agenvas.provider.application;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.domain.GenerationGateway;
import dev.agenvas.provider.domain.GenerationRequest;
import dev.agenvas.provider.domain.GenerationResult;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 生成并归档明确标注的演示图片，由统一媒体执行管线管理任务状态。 */
@Component
public class MockImageRenderer {

    /** 查询任务所有者并读取固定输入。 */
    private final TaskService tasks;
    /** 校验演示图片字节并写入本地媒体归档。 */
    private final AssetService assets;
    /** 生成本地 Mock 结果，不调用外部模型服务。 */
    private final GenerationGateway gateway;
    /** 选择当前演示素材 fixture。 */
    private final MockProviderProperties properties;
    /** 构造生成产物正文。 */
    private final ObjectMapper mapper;
    private final ProjectService projects;

    /** 装配图片演示渲染器 所需的任务、资产和本地生成服务。 */
    public MockImageRenderer(TaskService tasks, AssetService assets, GenerationGateway gateway,
            MockProviderProperties properties, ObjectMapper mapper, ProjectService projects) {
        this.tasks = tasks;
        this.assets = assets;
        this.gateway = gateway;
        this.properties = properties;
        this.mapper = mapper;
        this.projects = projects;
    }

    /** 以已提交的请求键生成结果，只接受同步完成的 Mock 响应。 */
    Submission executeBound(Task task, UUID requestKey) {
        GenerationResult result = gateway.submit(new GenerationRequest(task.projectId(),
                requestKey.toString(), properties.fixture()));
        return switch (result.status()) {
            case COMPLETED -> completed(task, result);
            case FAILED -> new Submission.Rejected(result.errorCode());
            case ACCEPTED -> throw new IllegalStateException(
                    "Mock image adapter unexpectedly returned asynchronous acceptance");
            case UNKNOWN -> throw new IllegalStateException(
                    "Mock image submission outcome is intentionally ambiguous");
        };
    }

    /** 要求 Provider 结果带演示标记，再将验证后的字节归档并记录来源任务。 */
    private Submission.CompletedArtifact completed(Task task, GenerationResult result) {
        if (!result.demoOutput()) {
            throw new IllegalStateException("Mock image result was not marked as demo output");
        }
        UUID ownerId = tasks.ownerForWorker(task);
        Asset asset = assets.archiveImage(ownerId, task.projectId(),
                new ByteArrayInputStream(renderDemoImage(task)));
        ObjectNode content = MediaResult.content(mapper, task, asset.id(),
                task.input().path("prompt").asText("Mock image"));
        ObjectNode parameters = MediaResult.copyFrozenParameters(content, task);
        parameters.put("mock", true);
        parameters.put("displayLabel", "演示素材");
        parameters.put("providerRequestId", result.providerRequestId());
        return new Submission.CompletedArtifact(content);
    }

    /** 根据任务 ID 生成可复现的合成 PNG，图面明确标出演示且不使用模型或 GPU。 */
    private byte[] renderDemoImage(Task task) {
        ImageGenerationParameters parameters = ImageGenerationParameters.parse(
                task.input().path("mediaInput").path("parameters"));
        String ratio = parameters.aspectRatio();
        if (ImageGenerationParameters.AUTO_ASPECT_RATIO.equals(ratio)) {
            ratio = switch (projects.get(tasks.ownerForWorker(task), task.projectId()).aspectRatio()) {
                case LANDSCAPE_16_9 -> "16:9";
                case PORTRAIT_9_16 -> "9:16";
                case SQUARE_1_1 -> "1:1";
            };
        }
        String[] size = OpenAiImage2Adapter.openAiSize(ratio, parameters.resolution())
                .split("x", 2);
        int width = Integer.parseInt(size[0]);
        int height = Integer.parseInt(size[1]);
        BufferedImage image = new BufferedImage(width, height,
                parameters.transparentBackground() ? BufferedImage.TYPE_INT_ARGB
                        : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            int hue = task.id().hashCode();
            Color top = new Color(30 + (hue & 63), 32, 74 + ((hue >>> 6) & 63));
            Color bottom = new Color(105, 70 + ((hue >>> 12) & 63), 114);
            if (!parameters.transparentBackground()) {
                graphics.setPaint(new GradientPaint(0, 0, top, width, height, bottom));
                graphics.fillRect(0, 0, width, height);
            }
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(parameters.transparentBackground() ? new Color(245, 55, 170)
                    : Color.WHITE);
            int titleSize = Math.max(24, Math.min(width, height) / 10);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, titleSize));
            graphics.drawString("DEMO IMAGE", Math.max(20, width / 10), height / 2);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(14, titleSize / 3)));
            graphics.drawString("AGENVAS / NOT AI GENERATED", Math.max(20, width / 10),
                    height / 2 + titleSize);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG encoder unavailable for Mock fixture");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Mock image rendering failed", exception);
        }
    }
}
