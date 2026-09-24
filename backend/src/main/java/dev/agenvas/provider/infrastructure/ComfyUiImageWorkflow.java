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

/** Bundled core-node img2img graph; only explicit safe inputs are mutable per approved Task. */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiImageWorkflow {

    public static final String OUTPUT_NODE_ID = "8";
    private static final String RESOURCE = "comfyui/image-v1.json";
    private static final Map<String, String> NODE_TYPES = Map.of(
            "1", "LoadImage", "2", "CheckpointLoaderSimple",
            "3", "CLIPTextEncode", "4", "CLIPTextEncode",
            "5", "VAEEncode", "6", "KSampler",
            "7", "VAEDecode", "8", "SaveImage");

    private final ObjectNode template;
    private final String checkpoint;
    private final String version;

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

    /** A template or checkpoint change invalidates an already proposed approval hash. */
    public String version() {
        return version;
    }

    /** Historical v1 results retain the same output node even when the installed checkpoint changes. */
    public static boolean supportsHistoricalVersion(String version) {
        return version != null && version.matches("image-v1-[0-9a-f]{32}");
    }

    /** Creates one new graph; no caller can select nodes, model file, output path or endpoint. */
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

    /** The reference image must flow through VAEEncode into the sampler's latent input. */
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

    private void requireLink(String node, String input, String source, int output) {
        JsonNode link = inputs(template, node).path(input);
        if (!link.isArray() || link.size() != 2
                || !source.equals(link.get(0).asText()) || link.get(1).asInt(-1) != output) {
            throw new IllegalStateException("image-v1 graph link mismatch: " + node + "." + input);
        }
    }

    private ObjectNode inputs(ObjectNode graph, String id) {
        if (!(graph.path(id).path("inputs") instanceof ObjectNode object)) {
            throw new IllegalStateException("image-v1 node inputs missing: " + id);
        }
        return object;
    }

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
