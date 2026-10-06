package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.RunningHubDefinition;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Deterministic editor suggestions only. Unknown inputs remain manual parameters;
 * neither imported text nor a suggestion can publish a capability or run its graph. */
final class RunningHubInputDiscovery {
    private static final Set<String> TEXT_INPUTS = Set.of("text", "string", "value", "prompt", "positive", "positiveprompt", "prompttext", "textpositive");
    private static final Set<String> FILE_INPUTS = Set.of("image", "audio", "video", "file", "filename", "path", "url", "imagepath", "audiopath", "videopath", "imageurl", "audiourl", "videourl");

    enum PromptRole { NONE, TEXT, EXPLICIT, POSITIVE_CONDITIONING }
    record Hint(RunningHubDefinition.FieldType type, PromptRole promptPriority) {}

    static Hint hint(RunningHubDefinition.FieldType declaredType, String fieldName, String nodeType, String label) {
        if (declaredType != RunningHubDefinition.FieldType.STRING) return new Hint(declaredType, PromptRole.NONE);
        String name = normalize(fieldName);
        String node = normalize(nodeType);
        String title = normalize(label);
        if (FILE_INPUTS.contains(name)) {
            boolean loader = contains(node, "load", "upload", "input") || contains(title, "加载", "上传", "输入");
            if (loader) {
                if (contains(node, "image") || contains(title, "图片", "图像")) return new Hint(RunningHubDefinition.FieldType.IMAGE, PromptRole.NONE);
                if (contains(node, "audio") || contains(title, "音频", "声音")) return new Hint(RunningHubDefinition.FieldType.AUDIO, PromptRole.NONE);
                if (contains(node, "video") || contains(title, "视频")) return new Hint(RunningHubDefinition.FieldType.VIDEO, PromptRole.NONE);
            }
        }
        if (negative(name) || negative(title) || negative(node)) return new Hint(declaredType, PromptRole.NONE);
        if (TEXT_INPUTS.contains(name)) {
            if (contains(name, "prompt", "positive") || contains(title, "提示词", "正向", "正面", "prompt", "positive"))
                return new Hint(declaredType, PromptRole.EXPLICIT);
            if (contains(node, "text", "string") || contains(title, "文本", "文字")) return new Hint(declaredType, PromptRole.TEXT);
        }
        return new Hint(declaredType, PromptRole.NONE);
    }

    /** Trace conditioning connections only to distinguish positive from negative text.
     * Connections stay out of editable fields and are never submitted as a local graph. */
    static Set<String> conditioningAncestors(JsonNode graph, boolean negative) {
        Set<String> nodes = new HashSet<>();
        var pending = new ArrayDeque<String>();
        for (var node : graph.properties()) for (var input : node.getValue().path("inputs").properties()) {
            String name = normalize(input.getKey());
            if (negative ? negative(name) : name.equals("positive") || name.equals("positiveprompt")) {
                String upstream = connection(input.getValue());
                if (upstream != null) pending.add(upstream);
            }
        }
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!nodes.add(id)) continue;
            for (var input : graph.path(id).path("inputs").properties()) {
                String upstream = connection(input.getValue());
                if (upstream != null) pending.add(upstream);
            }
        }
        return nodes;
    }

    /** Only an unambiguous primary text input gets the canvas prompt source.
     * Multiple equally plausible text inputs stay manual instead of violating the singleton source contract. */
    static int primaryPrompt(List<PromptRole> priorities) {
        PromptRole best = PromptRole.NONE;
        int chosen = -1;
        for (int i = 0; i < priorities.size(); i++) {
            PromptRole score = priorities.get(i);
            if (score.compareTo(best) > 0) { best = score; chosen = i; }
            else if (score == best) chosen = -1;
        }
        return chosen;
    }

    static boolean urlInput(String name) { return normalize(name).endsWith("url"); }

    private static String connection(JsonNode value) {
        return value.isArray() && value.size() == 2 && value.get(0).isTextual() && value.get(1).isIntegralNumber()
                ? value.get(0).asText() : null;
    }
    private static boolean negative(String value) { return contains(value, "negative", "负面", "负向", "反向"); }
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private static boolean contains(String value, String... hints) {
        for (String hint : hints) if (value.contains(hint)) return true;
        return false;
    }
}
