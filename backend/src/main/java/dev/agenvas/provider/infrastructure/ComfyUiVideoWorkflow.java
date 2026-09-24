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

/** Fixed, opt-in Wan 2.1 I2V candidate; no model-supplied node or file name is accepted. */
@Component
@ConditionalOnProperty(prefix = "agenvas.provider.comfyui.video", name = "enabled",
        havingValue = "true")
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiVideoWorkflow {

    public static final String OUTPUT_NODE_ID = "14";
    private static final String RESOURCE = "comfyui/image-to-video-v1.json";
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("1", "LoadImage"), Map.entry("2", "UNETLoader"),
            Map.entry("3", "CLIPLoader"), Map.entry("4", "VAELoader"),
            Map.entry("5", "CLIPVisionLoader"), Map.entry("6", "CLIPVisionEncode"),
            Map.entry("7", "CLIPTextEncode"), Map.entry("8", "CLIPTextEncode"),
            Map.entry("9", "WanImageToVideo"), Map.entry("10", "ModelSamplingSD3"),
            Map.entry("11", "KSampler"), Map.entry("12", "VAEDecode"),
            Map.entry("13", "CreateVideo"), Map.entry("14", "SaveVideo"));

    private final ObjectNode template;
    private final ComfyUiVideoProperties models;
    private final String version;

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

    /** Approval hashes include the exact graph and four installed model basenames. */
    public String version() {
        return version;
    }

    /** Historical v1 results retain the same output node even when installed models change. */
    public static boolean supportsHistoricalVersion(String version) {
        return version != null && version.matches("image-to-video-v1-[0-9a-f]{32}");
    }

    /** The candidate only supports exact quarter-second lengths from one to five seconds. */
    public boolean supportsDuration(int durationMs) {
        return durationMs >= 1_000 && durationMs <= 5_000 && durationMs % 250 == 0;
    }

    /** Creates a fresh fixed graph with an uploaded, pinned keyframe on both I2V inputs. */
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

    /** Normalization and graph dimensions always use the same fixed aspect mapping. */
    public Dimensions dimensions(Project.AspectRatio ratio) {
        return switch (ratio) {
            case LANDSCAPE_16_9 -> new Dimensions(832, 480);
            case PORTRAIT_9_16 -> new Dimensions(480, 832);
            case SQUARE_1_1 -> new Dimensions(640, 640);
        };
    }

    public record Dimensions(int width, int height) {}

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

    private void link(String node, String input, String source, int index) {
        JsonNode value = inputs(template, node).path(input);
        if (!value.isArray() || value.size() != 2
                || !source.equals(value.get(0).asText()) || value.get(1).asInt(-1) != index) {
            throw new IllegalStateException("I2V fixed graph link mismatch: " + node + "." + input);
        }
    }

    private ObjectNode inputs(ObjectNode graph, String id) {
        if (!(graph.path(id).path("inputs") instanceof ObjectNode node)) {
            throw new IllegalStateException("I2V inputs missing: " + id);
        }
        return node;
    }

    private void validateModelName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                || name.contains("..") || !name.endsWith(".safetensors")) {
            throw new IllegalArgumentException("Four ComfyUI I2V model basenames are required");
        }
    }

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
