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

/** Runs approved image Tasks through the durable submission ledger and real local archive. */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "mock", matchIfMissing = true)
public class MockImageWorker {

    private final TaskWorker worker;
    private final TaskService tasks;
    private final AssetService assets;
    private final GenerationGateway gateway;
    private final MockProviderProperties properties;
    private final PlanProviderProperties provider;
    private final ObjectMapper mapper;

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

    /** Claims only IMAGE_GENERATION work; each call is bounded to one durable Task. */
    public int runOnce(String workerId) {
        return worker.runImagesOnce(workerId, 1, new TaskWorker.MediaHandler() {
            @Override
            public String preflightFailure(Task task) {
                return task.input().path("providerConfigVersion").asInt(-1)
                        == provider.configVersion() ? null : "PROVIDER_CONFIG_CHANGED";
            }

            @Override
            public TaskWorker.Outcome execute(Task task, UUID requestKey) {
                return submit(task, requestKey);
            }
        });
    }

    /** Submits with the committed request key, then archives only a synchronous Mock completion. */
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

    /** The fixture is visibly a demo image and its Asset id points to validated bytes. */
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

    /** Generates reproducible, unmistakably synthetic PNG bytes without a model or GPU. */
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
