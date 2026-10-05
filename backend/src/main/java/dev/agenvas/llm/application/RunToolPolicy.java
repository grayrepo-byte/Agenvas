package dev.agenvas.llm.application;

import java.util.List;
import java.util.LinkedHashSet;
import tools.jackson.databind.JsonNode;

/** Pins definitions and execution to the same Run policy, including historical checkpoints. */
public final class RunToolPolicy {
    public static final int CURRENT_VERSION = 1;
    private static final List<String> LEGACY = List.of("read_project_summary", "read_selection",
            "read_artifacts", "read_task_status", "create_text", "revise_artifact",
            "place_artifacts", "arrange_items");
    private static final List<String> MEDIA = java.util.stream.Stream.concat(LEGACY.stream(),
            java.util.stream.Stream.of("list_media_capabilities", "propose_media_generation")).toList();
    public static final List<String> CURRENT = java.util.stream.Stream.concat(MEDIA.stream(),
            java.util.stream.Stream.of("read_skill_resource")).toList();

    private RunToolPolicy() {}

    /** A Skill resource tool is useful only when this Run freezes readable resource paths. */
    public static List<String> current(boolean hasSkillResources) {
        return hasSkillResources ? CURRENT : CURRENT.stream()
                .filter(name -> !"read_skill_resource".equals(name)).toList();
    }

    public static List<String> allowed(JsonNode policy) {
        if (!policy.has("toolPolicyVersion")) {
            int prompt = policy.path("systemPromptVersion").asInt(1);
            if (prompt < 1 || prompt > 3) throw new IllegalStateException("Missing pinned tool policy");
            return prompt == 3 ? MEDIA : LEGACY;
        }
        JsonNode version = policy.path("toolPolicyVersion");
        JsonNode tools = policy.path("allowedTools");
        if (!version.isIntegralNumber() || version.intValue() != CURRENT_VERSION || !tools.isArray()
                || tools.isEmpty() || tools.size() > CURRENT.size()) {
            throw new IllegalStateException("Unsupported Run tool policy");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode tool : tools) {
            if (!tool.isTextual() || !CURRENT.contains(tool.asText()) || !result.add(tool.asText())) {
                throw new IllegalStateException("Malformed Run tool allowlist");
            }
        }
        return List.copyOf(result);
    }
}
