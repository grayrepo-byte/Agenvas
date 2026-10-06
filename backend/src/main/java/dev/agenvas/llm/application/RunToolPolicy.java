package dev.agenvas.llm.application;

import java.util.List;
import java.util.LinkedHashSet;
import tools.jackson.databind.JsonNode;

/** Pins definitions and execution to the same Run policy, including historical checkpoints. */
public final class RunToolPolicy {
    public static final int PROGRESSIVE_VERSION = 2;
    public static final int CURRENT_VERSION = 3;
    private static final List<String> LEGACY = List.of("read_project_summary", "read_selection",
            "read_artifacts", "read_task_status", "create_text", "revise_artifact",
            "place_artifacts", "arrange_items");
    private static final List<String> MEDIA = java.util.stream.Stream.concat(LEGACY.stream(),
            java.util.stream.Stream.of("list_media_capabilities", "propose_media_generation")).toList();
    private static final List<String> V1 = java.util.stream.Stream.concat(MEDIA.stream(),
            java.util.stream.Stream.of("read_skill_resource")).toList();

    private static final List<String> V2 = java.util.stream.Stream.concat(V1.stream(),
            java.util.stream.Stream.of("read_skill")).toList();
    public static final List<String> CURRENT = java.util.stream.Stream.concat(V2.stream(),
            java.util.stream.Stream.of("read_skill_asset")).toList();

    private RunToolPolicy() {}

    /** Main instructions are available even for Skills without any resource attachments. */
    public static List<String> current(boolean hasSkills, boolean hasSkillResources) {
        return current(hasSkills, hasSkillResources, false);
    }

    /** Fixed Skill images are model context and are requested independently of text attachments. */
    public static List<String> current(boolean hasSkills, boolean hasSkillResources, boolean hasSkillAssets) {
        return CURRENT.stream().filter(name -> !"read_skill".equals(name) || hasSkills)
                .filter(name -> !"read_skill_resource".equals(name) || hasSkillResources)
                .filter(name -> !"read_skill_asset".equals(name) || hasSkillAssets).toList();
    }

    public static List<String> allowed(JsonNode policy) {
        if (!policy.has("toolPolicyVersion")) {
            int prompt = policy.path("systemPromptVersion").asInt(1);
            if (prompt < 1 || prompt > 3) throw new IllegalStateException("Missing pinned tool policy");
            return prompt == 3 ? MEDIA : LEGACY;
        }
        JsonNode version = policy.path("toolPolicyVersion");
        JsonNode tools = policy.path("allowedTools");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || (version.intValue() != 1 && version.intValue() != PROGRESSIVE_VERSION && version.intValue() != CURRENT_VERSION) || !tools.isArray()
                || tools.isEmpty() || tools.size() > CURRENT.size()) {
            throw new IllegalStateException("Unsupported Run tool policy");
        }
        List<String> supported = switch (version.intValue()) {
            case 1 -> V1;
            case PROGRESSIVE_VERSION -> V2;
            default -> CURRENT;
        };
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode tool : tools) {
            if (!tool.isTextual() || !supported.contains(tool.asText()) || !result.add(tool.asText())) {
                throw new IllegalStateException("Malformed Run tool allowlist");
            }
        }
        return List.copyOf(result);
    }
}
