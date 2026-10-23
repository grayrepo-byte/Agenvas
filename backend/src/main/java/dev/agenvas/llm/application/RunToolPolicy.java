package dev.agenvas.llm.application;

import java.util.List;
import java.util.LinkedHashSet;
import tools.jackson.databind.JsonNode;

/** Pins definitions and execution to the same Run policy, without retired protocol fallbacks. */
public final class RunToolPolicy {
    public static final int CURRENT_SCHEMA_VERSION = 7;
    public static final int CURRENT_VERSION = 6;
    public static final List<String> CURRENT = List.of("read_project_summary", "read_selection",
            "read_artifacts", "read_media_sample", "read_task_status", "create_text", "revise_artifact",
            "arrange_items", "list_media_capabilities", "propose_media_generation",
            "read_skill_resource", "read_skill", "read_skill_asset", "read_canvas",
            "manage_canvas_node", "update_media_draft", "compose_video", "extract_audio", "trim_audio");

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
        JsonNode schema = policy.path("schemaVersion");
        JsonNode prompt = policy.path("systemPromptVersion");
        if (!schema.isIntegralNumber() || !schema.canConvertToInt()
                || schema.intValue() != CURRENT_SCHEMA_VERSION
                || !prompt.isIntegralNumber() || !prompt.canConvertToInt()
                || prompt.intValue() != InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION)
            throw new IllegalStateException("Unsupported Run protocol");
        JsonNode version = policy.path("toolPolicyVersion");
        JsonNode tools = policy.path("allowedTools");
        if (!version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() != CURRENT_VERSION || !tools.isArray()
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
