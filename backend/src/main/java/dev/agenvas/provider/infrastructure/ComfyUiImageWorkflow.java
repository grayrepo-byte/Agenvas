package dev.agenvas.provider.infrastructure;

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

/** 使用随应用打包的核心节点图生图模板；审批任务只能修改明确列出的安全输入。 */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiImageWorkflow {

    /** 固定模板中保存生成图像的节点编号，历史任务查询使用此编号。 */
    public static final String OUTPUT_NODE_ID = "8";
    /** 应用内置的固定图生图 JSON 模板。 */
    private static final String RESOURCE = "comfyui/image-v1.json";
    /** 启动校验所允许的节点编号和类型。 */
    private static final Map<String, String> NODE_TYPES = Map.of(
            "1", "LoadImage", "2", "CheckpointLoaderSimple",
            "3", "CLIPTextEncode", "4", "CLIPTextEncode",
            "5", "VAEEncode", "6", "KSampler",
            "7", "VAEDecode", "8", "SaveImage");

    /** 启动时校验的模板副本；每个任务渲染时都先深拷贝。 */
    private final ObjectNode template;
    /** 服务端配置的检查点文件名，不接受模型或任务输入覆盖。 */
    private final String checkpoint;
    /** 绑定模板内容和检查点名的版本摘要，计划审批时固定该版本。 */
    private final String version;

    /** 读取安全检查点配置与内置模板，并在启动时校验完整固定工作流。 */
    public ComfyUiImageWorkflow(ComfyUiImageProperties properties, ObjectMapper mapper) {
        String requested = properties.checkpoint();
        if (requested == null || !requested.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                || requested.contains("..") || !requested.endsWith(".safetensors")) {
            throw new IllegalArgumentException("ComfyUI image checkpoint basename is required");
        }
        this.checkpoint = requested;
        byte[] bytes;
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Bundled image-v1 workflow is unavailable", failure);
        }
        JsonNode parsed = mapper.readTree(bytes);
        if (!(parsed instanceof ObjectNode object)) {
            throw new IllegalStateException("Bundled image-v1 workflow must be an object");
        }
        this.template = object;
        verifyFixedGraph();
        this.version = "image-v1-" + sha256(bytes, checkpoint).substring(0, 32);
    }

    /** 返回模板和检查点组合版本；任一变化都会使待审批计划失效。 */
    public String version() {
        return version;
    }

    /** 判断历史 v1 结果是否仍可由固定输出节点查询，不要求当前检查点相同。 */
    public static boolean supportsHistoricalVersion(String version) {
        return version != null && version.matches("image-v1-[0-9a-f]{32}");
    }

    /** 创建本次任务的工作流图；调用方不能选择节点、模型文件、输出路径或端点。 */
    public ObjectNode render(String prompt, String negativePrompt, long seed,
            String uploadedImageName, boolean hasReference) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 8_000
                || negativePrompt != null && negativePrompt.length() > 8_000
                || uploadedImageName == null
                || !uploadedImageName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                || uploadedImageName.contains("..") || seed < 0) {
            throw new IllegalArgumentException("Invalid approved image-v1 inputs");
        }
        ObjectNode graph = template.deepCopy();
        inputs(graph, "1").put("image", uploadedImageName);
        inputs(graph, "2").put("ckpt_name", checkpoint);
        inputs(graph, "3").put("text", prompt);
        inputs(graph, "4").put("text", negativePrompt == null ? "" : negativePrompt);
        inputs(graph, "6").put("seed", seed);
        inputs(graph, "6").put("denoise", hasReference ? 0.65 : 1.0);
        return graph;
    }

    /** 启动时确认参考图必须经 VAEEncode 进入采样器潜空间输入。 */
    private void verifyFixedGraph() {
        if (template.size() != NODE_TYPES.size()) {
            throw new IllegalStateException("image-v1 contains unexpected nodes");
        }
        NODE_TYPES.forEach((id, kind) -> {
            if (!kind.equals(template.path(id).path("class_type").asText())) {
                throw new IllegalStateException("image-v1 node type mismatch: " + id);
            }
            inputs(template, id);
        });
        requireLink("5", "pixels", "1", 0);
        requireLink("5", "vae", "2", 2);
        requireLink("6", "latent_image", "5", 0);
        requireLink("6", "positive", "3", 0);
        requireLink("6", "negative", "4", 0);
        requireLink("6", "model", "2", 0);
        requireLink("7", "samples", "6", 0);
        requireLink("7", "vae", "2", 2);
        requireLink("8", "images", "7", 0);
    }

    /** 要求指定节点输入连接到精确来源节点和输出序号。 */
    private void requireLink(String node, String input, String source, int output) {
        JsonNode link = inputs(template, node).path(input);
        if (!link.isArray() || link.size() != 2
                || !source.equals(link.get(0).asText()) || link.get(1).asInt(-1) != output) {
            throw new IllegalStateException("image-v1 graph link mismatch: " + node + "." + input);
        }
    }

    /** 读取节点 inputs 对象；模板缺少节点或输入结构时拒绝继续。 */
    private ObjectNode inputs(ObjectNode graph, String id) {
        if (!(graph.path(id).path("inputs") instanceof ObjectNode object)) {
            throw new IllegalStateException("image-v1 node inputs missing: " + id);
        }
        return object;
    }

    /** 将模板原始字节与检查点名一同纳入 SHA-256 工作流版本。 */
    private String sha256(byte[] bytes, String checkpointName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            digest.update((byte) '\n');
            digest.update(checkpointName.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
