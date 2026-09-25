package dev.agenvas.provider.infrastructure;

import dev.agenvas.project.domain.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 固定且显式启用的 Wan 2.1 图生视频模板；模型不能指定节点、模型文件或输出路径。 */
@Component
@ConditionalOnProperty(prefix = "agenvas.provider.comfyui.video", name = "enabled",
        havingValue = "true")
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiVideoWorkflow {

    /** 固定模板中负责保存最终视频的节点编号，历史任务轮询依赖此编号。 */
    public static final String OUTPUT_NODE_ID = "14";
    /** 随应用打包的工作流定义；不会从模型输出或用户请求加载图。 */
    private static final String RESOURCE = "comfyui/image-to-video-v1.json";
    /** 允许的节点 ID 与类型白名单，启动时用于拒绝被意外替换的模板。 */
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("1", "LoadImage"), Map.entry("2", "UNETLoader"),
            Map.entry("3", "CLIPLoader"), Map.entry("4", "VAELoader"),
            Map.entry("5", "CLIPVisionLoader"), Map.entry("6", "CLIPVisionEncode"),
            Map.entry("7", "CLIPTextEncode"), Map.entry("8", "CLIPTextEncode"),
            Map.entry("9", "WanImageToVideo"), Map.entry("10", "ModelSamplingSD3"),
            Map.entry("11", "KSampler"), Map.entry("12", "VAEDecode"),
            Map.entry("13", "CreateVideo"), Map.entry("14", "SaveVideo"));

    /** 启动时读取并验证的只读模板，渲染时先深拷贝再写入本次输入。 */
    private final ObjectNode template;
    /** 固定模板使用的四个服务端配置模型文件名。 */
    private final ComfyUiVideoProperties models;
    /** 绑定模板字节和模型文件名的版本摘要，审批计划会固定该值。 */
    private final String version;

    /** 加载并验证随应用发布的固定图生视频图；工作流结构不由用户或模型提供。
     * @param models 服务端配置的模型文件名
     * @param mapper 解析和复制 JSON 工作流模板
     */
    public ComfyUiVideoWorkflow(ComfyUiVideoProperties models, ObjectMapper mapper) {
        validateModelName(models.diffusionModel());
        validateModelName(models.textEncoder());
        validateModelName(models.vae());
        validateModelName(models.clipVision());
        this.models = models;
        byte[] bytes;
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Bundled image-to-video-v1 graph is missing", failure);
        }
        if (!(mapper.readTree(bytes) instanceof ObjectNode graph)) {
            throw new IllegalStateException("Bundled I2V graph must be an object");
        }
        template = graph;
        verifyFixedGraph();
        version = "image-to-video-v1-" + hash(bytes, models).substring(0, 32);
    }

    /** 审批摘要绑定精确工作流图及四个已配置模型文件名。 */
    public String version() {
        return version;
    }

    /** 即使当前模型配置变化，历史 v1 任务仍使用相同的输出节点编号。 */
    public static boolean supportsHistoricalVersion(String version) {
        return version != null && version.matches("image-to-video-v1-[0-9a-f]{32}");
    }

    /** 此模板只支持一至五秒且以四分之一秒为步长的时长。 */
    public boolean supportsDuration(int durationMs) {
        return durationMs >= 1_000 && durationMs <= 5_000 && durationMs % 250 == 0;
    }

    /** 新业务计划仅允许固定模板可精确表示的 1–5 整数秒。 */
    public boolean supportsDurationSeconds(int durationSeconds) {
        return durationSeconds >= 1 && durationSeconds <= 5;
    }

    /** 把新业务秒数转换为固定模板使用的旧毫秒协议。 */
    public ObjectNode renderSeconds(String prompt, String negativePrompt, long seed,
            String uploadedImageName, Project.AspectRatio ratio, int durationSeconds) {
        if (!supportsDurationSeconds(durationSeconds)) {
            throw new IllegalArgumentException("ComfyUI duration must be 1–5 whole seconds");
        }
        return render(prompt, negativePrompt, seed, uploadedImageName, ratio,
                Math.multiplyExact(durationSeconds, 1_000));
    }

    /** 深拷贝固定工作流，并将已上传的固定关键帧写入图生视频的两个图像输入。 */
    public ObjectNode render(String prompt, String negativePrompt, long seed,
            String uploadedImageName, Project.AspectRatio ratio, int durationMs) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 8_000
                || negativePrompt != null && negativePrompt.length() > 8_000
                || seed < 0 || ratio == null || !supportsDuration(durationMs)
                || uploadedImageName == null
                || !uploadedImageName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                || uploadedImageName.contains("..")) {
            throw new IllegalArgumentException("Invalid approved image-to-video-v1 inputs");
        }
        ObjectNode graph = template.deepCopy();
        inputs(graph, "1").put("image", uploadedImageName);
        inputs(graph, "2").put("unet_name", models.diffusionModel());
        inputs(graph, "3").put("clip_name", models.textEncoder());
        inputs(graph, "4").put("vae_name", models.vae());
        inputs(graph, "5").put("clip_name", models.clipVision());
        inputs(graph, "7").put("text", prompt);
        inputs(graph, "8").put("text", negativePrompt == null ? "" : negativePrompt);
        inputs(graph, "11").put("seed", seed);
        Dimensions dimensions = dimensions(ratio);
        inputs(graph, "9").put("width", dimensions.width());
        inputs(graph, "9").put("height", dimensions.height());
        inputs(graph, "9").put("length", durationMs / 250 * 4 + 1);
        return graph;
    }

    /** 归一化与工作流图使用同一套固定画幅到尺寸映射。 */
    public Dimensions dimensions(Project.AspectRatio ratio) {
        return switch (ratio) {
            case LANDSCAPE_16_9 -> new Dimensions(832, 480);
            case PORTRAIT_9_16 -> new Dimensions(480, 832);
            case SQUARE_1_1 -> new Dimensions(640, 640);
        };
    }

    /** 固定工作流输出尺寸。
     * @param width 输出宽度，单位为像素
     * @param height 输出高度，单位为像素
     */
    public record Dimensions(int width, int height) {}

    /** 启动时校验节点、连线和编码参数，确保模板仍是预期的固定图。 */
    private void verifyFixedGraph() {
        if (template.size() != TYPES.size()) {
            throw new IllegalStateException("I2V graph contains unexpected nodes");
        }
        TYPES.forEach((id, kind) -> {
            if (!kind.equals(template.path(id).path("class_type").asText())) {
                throw new IllegalStateException("I2V node type mismatch: " + id);
            }
            inputs(template, id);
        });
        link("6", "clip_vision", "5", 0);
        link("6", "image", "1", 0);
        link("7", "clip", "3", 0);
        link("8", "clip", "3", 0);
        link("9", "positive", "7", 0);
        link("9", "negative", "8", 0);
        link("9", "vae", "4", 0);
        link("9", "clip_vision_output", "6", 0);
        link("9", "start_image", "1", 0);
        link("10", "model", "2", 0);
        link("11", "model", "10", 0);
        link("11", "positive", "9", 0);
        link("11", "negative", "9", 1);
        link("11", "latent_image", "9", 2);
        link("12", "samples", "11", 0);
        link("12", "vae", "4", 0);
        link("13", "images", "12", 0);
        link("14", "video", "13", 0);
        if (!"none".equals(inputs(template, "6").path("crop").asText())
                || inputs(template, "9").path("batch_size").asInt(-1) != 1
                || inputs(template, "13").path("fps").asInt(-1) != 16
                || !"agenvas-video".equals(inputs(template, "14")
                        .path("filename_prefix").asText())
                || !"mp4".equals(inputs(template, "14").path("format").asText())
                || !"h264".equals(inputs(template, "14").path("codec")
                        .path("codec").asText())) {
            throw new IllegalStateException("I2V fixed output or conditioning settings changed");
        }
    }

    /** 校验一个输入必须连到模板中指定来源节点及输出端口。 */
    private void link(String node, String input, String source, int index) {
        JsonNode value = inputs(template, node).path(input);
        if (!value.isArray() || value.size() != 2
                || !source.equals(value.get(0).asText()) || value.get(1).asInt(-1) != index) {
            throw new IllegalStateException("I2V fixed graph link mismatch: " + node + "." + input);
        }
    }

    /** 取得指定节点的 inputs 对象；节点或输入结构缺失时停止启动或渲染。 */
    private ObjectNode inputs(ObjectNode graph, String id) {
        if (!(graph.path(id).path("inputs") instanceof ObjectNode node)) {
            throw new IllegalStateException("I2V inputs missing: " + id);
        }
        return node;
    }

    /** 仅允许安全的 safetensors 基名，禁止路径分隔符、遍历片段和其他扩展名。 */
    private void validateModelName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                || name.contains("..") || !name.endsWith(".safetensors")) {
            throw new IllegalArgumentException("Four ComfyUI I2V model basenames are required");
        }
    }

    /** 将模板原始字节和四个模型名纳入 SHA-256，确保配置变化使计划版本变化。 */
    private String hash(byte[] bytes, ComfyUiVideoProperties names) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            for (String name : new String[] {names.diffusionModel(), names.textEncoder(),
                    names.vae(), names.clipVision()}) {
                digest.update((byte) '\n');
                digest.update(name.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
