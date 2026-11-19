package dev.agenvas.llm.application;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicit schema discovery; a group never extends the Run's frozen permission ceiling. */
enum AgentToolset {
    CANVAS("Discover current nodes, selected intent, exact content and project metadata",
            AgentTool.READ_CANVAS, AgentTool.READ_SELECTION, AgentTool.READ_ARTIFACTS,
            AgentTool.READ_PROJECT_SUMMARY),

    TEXT("Create or revise saved text", AgentTool.CREATE_TEXT, AgentTool.REVISE_ARTIFACT),

    LAYOUT(
            "Rename, move, resize, remove, duplicate or arrange canvas nodes", AgentTool.MANAGE_CANVAS_NODE,
            AgentTool.ARRANGE_ITEMS),

    STORYBOARD("Read and save formal storyboard shots", AgentTool.READ_STORYBOARD,
            AgentTool.WRITE_STORYBOARD_SHOTS),

    MEDIA(
            "Discover generation capabilities, edit drafts and propose approved image/video/audio generation",
            AgentTool.LIST_MEDIA_CAPABILITIES, AgentTool.UPDATE_MEDIA_DRAFT, AgentTool.PROPOSE_MEDIA_GENERATION,
            AgentTool.READ_TASK_STATUS),

    INSPECTION("Inspect actual video frames or audio samples",
            AgentTool.READ_MEDIA_SAMPLE),

    COMPOSITION(
            "Local video assembly, audio extraction and trimming",
            AgentTool.COMPOSE_VIDEO, AgentTool.EXTRACT_AUDIO, AgentTool.TRIM_AUDIO,
            AgentTool.READ_TASK_STATUS),

    SKILLS(
            "Activate a creative Skill and read its registered text/image attachments",
            AgentTool.READ_SKILL, AgentTool.READ_SKILL_RESOURCE, AgentTool.READ_SKILL_ASSET);

    static final String LOADER = "load_toolset";
    private final String description;
    private final List<String> tools;

    AgentToolset(String description, AgentTool... tools) {
        this.description = description;
        this.tools = Arrays.stream(tools).map(AgentTool::wireName).toList();
    }

    static boolean onDemand(JsonNode policy) {
        for (JsonNode tool : policy.path("allowedTools"))
            if (LOADER.equals(tool.asText()))
                return true;
        return false;
    }

    static String description() {
        return "Load tool schemas and professional instructions for the NEXT model turn. "
                + "Choose all relevant groups; add groups later as the task changes. "
                + "Wait for the successful reply before using newly loaded tools. "
                + "Loading grants no project/object access or media approval. "
                + Arrays.stream(values()).map(group -> group.name() + ": " + group.description)
                        .collect(Collectors.joining("; "));
    }

    static String schema() {
        String names = Arrays.stream(values()).map(group -> "\"" + group.name() + "\"")
                .collect(Collectors.joining(","));
        return "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"toolsets\"],\"properties\":{"
                + "\"toolsets\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":" + values().length
                + ",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":[" + names + "]}}}}";
    }

    /** Called inside the ordinary tool-ledger transaction; a rejected batch loads nothing. */
    static JsonNode load(JsonNode policy, UUID operationId, String arguments, ObjectMapper mapper) {
        JsonNode input;
        try {
            input = mapper.readTree(arguments);
        } catch (RuntimeException malformed) {
            throw invalid();
        }
        if (input == null || !input.isObject() || input.size() != 1 || !input.has("toolsets"))
            throw invalid();
        JsonNode requested = input.path("toolsets");
        if (!requested.isArray() || requested.isEmpty() || requested.size() > values().length)
            throw invalid();
        List<String> allowed = RunToolPolicy.allowed(policy);
        var groups = new LinkedHashSet<AgentToolset>();
        var names = new LinkedHashSet<String>();
        for (JsonNode value : requested) {
            AgentToolset group;
            try {
                group = value.isTextual() ? valueOf(value.asText()) : null;
            } catch (IllegalArgumentException unknown) {
                throw invalid();
            }
            if (group == null || !groups.add(group))
                throw invalid();
            List<String> available = group.tools.stream().filter(allowed::contains).toList();
            if (available.isEmpty())
                throw invalid();
            names.addAll(available);
        }
        var result = mapper.createObjectNode().put("status", ToolResultStatus.SUCCEEDED.name())
                .put("operationId", operationId.toString()).put("userVisibleSummary", "已准备所需能力");
        result.putArray("createdIds");
        result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        var data = result.putObject("data");
        data.set("toolsets", mapper.valueToTree(groups.stream().map(Enum::name).toList()));
        data.set("toolNames", mapper.valueToTree(names));
        data.put("availableFrom", "NEXT_MODEL_TURN");
        return result;
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "TOOL_ARGUMENT_INVALID",
                ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"),
                ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"), false);
    }
}
