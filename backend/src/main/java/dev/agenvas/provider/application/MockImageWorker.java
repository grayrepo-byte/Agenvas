package dev.agenvas.provider.application;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.plan.application.PlanProviderProperties;
import dev.agenvas.provider.domain.GenerationGateway;
import dev.agenvas.provider.domain.GenerationRequest;
import dev.agenvas.provider.domain.GenerationResult;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 通过持久化提交账本执行已审批图片任务，并把明确标注的演示图归档为真实本地资产。 */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "mock", matchIfMissing = true)
public class MockImageWorker {

    /** 管理任务认领、提交检查点和带 fencing 的成功回写。 */
    private final TaskWorker worker;
    /** 查询任务所有者并读取固定输入。 */
    private final TaskService tasks;
    /** 校验演示图片字节并写入本地媒体归档。 */
    private final AssetService assets;
    /** 生成本地 Mock 结果，不调用外部模型服务。 */
    private final GenerationGateway gateway;
    /** 选择当前演示素材 fixture。 */
    private final MockProviderProperties properties;
    /** 为任务结果写入和校验 Mock Provider 配置版本。 */
    private final PlanProviderProperties provider;
    /** 构造生成产物正文。 */
    private final ObjectMapper mapper;

    /** 装配图片演示 Worker 所需的任务、资产和本地生成服务。 */
    public MockImageWorker(TaskService tasks, AssetService assets, GenerationGateway gateway,
            MockProviderProperties properties, PlanProviderProperties provider,
            ObjectMapper mapper) {
        this.worker = new TaskWorker(tasks);
        this.tasks = tasks;
        this.assets = assets;
        this.gateway = gateway;
        this.properties = properties;
        this.provider = provider;
        this.mapper = mapper;
    }

    /** 每轮最多认领一个图片生成任务，不处理视频或模型回合任务。 */
    public int runOnce(String workerId) {
        return worker.runImagesOnce(workerId, 1, new TaskWorker.MediaHandler() {
            /** 配置版本变化时在生成演示图片前阻止旧计划继续。 */
            @Override
            public String preflightFailure(Task task) {
                return task.input().path("providerConfigVersion").asInt(-1)
                        == provider.configVersion() ? null : "PROVIDER_CONFIG_CHANGED";
            }

            /** 使用已持久化的请求键执行本地演示生成。 */
            @Override
            public TaskWorker.Outcome execute(Task task, UUID requestKey) {
                return submit(task, requestKey);
            }
        });
    }

    /** 以已提交的请求键生成结果，只接受同步完成的 Mock 响应。 */
    private TaskWorker.Outcome submit(Task task, UUID requestKey) {
        GenerationResult result = gateway.submit(new GenerationRequest(task.projectId(),
                requestKey.toString(), properties.fixture()));
        return switch (result.status()) {
            case COMPLETED -> completed(task, result);
            case FAILED -> new TaskWorker.Failed(result.errorCode());
            case ACCEPTED -> throw new IllegalStateException(
                    "Mock image adapter unexpectedly returned asynchronous acceptance");
            case UNKNOWN -> throw new IllegalStateException(
                    "Mock image submission outcome is intentionally ambiguous");
        };
    }

    /** 要求 Provider 结果带演示标记，再将验证后的字节归档并记录来源任务。 */
    private TaskWorker.GeneratedArtifact completed(Task task, GenerationResult result) {
        if (!result.demoOutput()) {
            throw new IllegalStateException("Mock image result was not marked as demo output");
        }
        UUID ownerId = tasks.ownerForWorker(task);
        Asset asset = assets.archiveImage(ownerId, task.projectId(),
                new ByteArrayInputStream(renderDemoImage(task.id())));
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", asset.id().toString());
        content.put("prompt", task.input().path("prompt").asText("Mock image"));
        if (task.input().has("negativePrompt")) {
            content.put("negativePrompt", task.input().path("negativePrompt").asText());
        }
        content.put("providerConfigVersion", provider.configVersion());
        content.put("workflowVersion", task.input().path("workflowVersion").asText());
        content.put("sourceTaskId", task.id().toString());
        ObjectNode parameters = content.putObject("parameters");
        parameters.put("mock", true);
        parameters.put("displayLabel", "演示素材");
        parameters.put("providerRequestId", result.providerRequestId());
        return new TaskWorker.GeneratedArtifact(content);
    }

    /** 根据任务 ID 生成可复现的合成 PNG，图面明确标出演示且不使用模型或 GPU。 */
    private byte[] renderDemoImage(UUID taskId) {
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            int hue = taskId.hashCode();
            Color top = new Color(30 + (hue & 63), 32, 74 + ((hue >>> 6) & 63));
            Color bottom = new Color(105, 70 + ((hue >>> 12) & 63), 114);
            graphics.setPaint(new GradientPaint(0, 0, top, 640, 360, bottom));
            graphics.fillRect(0, 0, 640, 360);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 62));
            graphics.drawString("DEMO IMAGE", 82, 184);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 23));
            graphics.drawString("AGENVAS  /  NOT AI GENERATED", 112, 230);
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
