package dev.agenvas.llm.application;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/** 只发布已实现的工具 Schema；回调本身拒绝执行，所有副作用必须经过持久化工具执行器。 */
@Component
public class ToolRegistry {

    /** 项目摘要由服务端作用域决定，不接受模型传入项目 ID。 */
    private static final String READ_PROJECT_SUMMARY_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{}}
            """;
    /** 读取 Run 创建时快照中的画布选择，不接受额外选择 ID。 */
    private static final String READ_SELECTION_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{}}
            """;
    /** 精确版本白名单读取；最多 12 个互异 UUID。 */
    private static final String READ_ARTIFACTS_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["versionIds"],
             "properties":{"versionIds":{"type":"array","minItems":1,"maxItems":12,
               "uniqueItems":true,"items":{"type":"string","format":"uuid"}}}}
            """;
    /** Run 内任务状态查询；最多 12 个互异任务 ID。 */
    private static final String READ_TASK_STATUS_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["taskIds"],
             "properties":{"taskIds":{"type":"array","minItems":1,"maxItems":12,
               "uniqueItems":true,"items":{"type":"string","format":"uuid"}}}}
            """;
    private static final String PROPOSE_MEDIA_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["outputs"],
             "properties":{"outputs":{"type":"array","minItems":1,"maxItems":6,
               "items":{"type":"object","additionalProperties":false,
                 "required":["kind","title","prompt"],"properties":{
                   "kind":{"type":"string","enum":["IMAGE","VIDEO","AUDIO"]},
                   "title":{"type":"string","minLength":1,"maxLength":160},
                   "prompt":{"type":"string","maxLength":20000},
                   "capabilityId":{"type":"string","format":"uuid"},
                   "parameters":{"type":"object"},
                   "durationSeconds":{"type":"integer","minimum":1,"maximum":60},
                   "videoInputMode":{"type":"string","enum":["TEXT","START_END","GENERAL_REFERENCE"]},
                   "mediaInputs":{"type":"array","maxItems":14,"items":{
                     "type":"object","additionalProperties":false,"required":["versionId","role"],
                     "properties":{"versionId":{"type":"string","format":"uuid"},
                       "role":{"type":"string","enum":["REFERENCE","START_FRAME","END_FRAME",
                         "AUDIO_REFERENCE","VIDEO_REFERENCE"]}}}}}}}}}
            """;

    /** 文本产物只允许标题、正文和格式字段。 */
    private static final String CREATE_TEXT_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["title","text","format"],
             "properties":{"title":{"type":"string","minLength":1,"maxLength":160},
               "text":{"type":"string","minLength":1,"maxLength":20000},
               "format":{"type":"string","enum":["PLAIN_TEXT","MARKDOWN"]}}}
            """;
    /** 版本追加要求 expectedVersion 和完整内容，禁止局部补丁或服务端字段。 */
    private static final String REVISE_ARTIFACT_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["artifactId","expectedVersion","content"],
             "properties":{"artifactId":{"type":"string","format":"uuid"},
               "expectedVersion":{"type":"integer","minimum":0},
               "title":{"type":"string","minLength":1,"maxLength":160},
               "content":{"type":"object"}}}
            """;
    /** 仅允许把当前 Run 可见产物放入服务端指定的 Agent 输出组。 */
    private static final String PLACE_ARTIFACTS_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["versionIds","group"],
             "properties":{"versionIds":{"type":"array","minItems":1,"maxItems":6,
               "uniqueItems":true,"items":{"type":"string","format":"uuid"}},
               "group":{"type":"string","const":"AGENT_OUTPUT"}}}
            """;
    /** 布局命令须提供画布项、内容版本和布局 CAS 版本。 */
    private static final String ARRANGE_ITEMS_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["items","layout"],
             "properties":{"layout":{"type":"string",
               "enum":["HORIZONTAL","VERTICAL","GRID"]},
               "items":{"type":"array","minItems":1,"maxItems":6,
                 "items":{"type":"object","additionalProperties":false,
                   "required":["itemId","versionId","expectedVersion"],
                   "properties":{"itemId":{"type":"string","format":"uuid"},
                     "versionId":{"type":"string","format":"uuid"},
                     "expectedVersion":{"type":"integer","minimum":0}}}}}}
            """;
    private static final String PROGRESSIVE_SKILL_RESOURCE_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["skillVersionId","path"],
             "properties":{"skillVersionId":{"type":"string","format":"uuid"},
             "path":{"type":"string","maxLength":160},"offset":{"type":"integer","minimum":0},
             "limit":{"type":"integer","minimum":1,"maximum":4000}}}
            """;
    public List<ToolCallback> modelDefinitions(tools.jackson.databind.JsonNode policy) {
        var allowed = RunToolPolicy.allowed(policy);
        return modelDefinitions().stream().filter(tool -> allowed.contains(
                tool.getToolDefinition().name())).map(tool -> {
                    var current = tool.getToolDefinition();
                    if ("read_skill_resource".equals(current.name()) && policy.path("toolPolicyVersion").asInt(1) >= RunToolPolicy.CURRENT_VERSION)
                        return definition(current.name(), "Read a registered text attachment after read_skill has activated its exact version. Offsets count Unicode code points.",
                                PROGRESSIVE_SKILL_RESOURCE_SCHEMA);
                    if (!"read_artifacts".equals(current.name())
                            || policy.path("systemPromptVersion").asInt()
                                    < InitialModelContextService.IMAGE_INPUT_SYSTEM_PROMPT_VERSION) return tool;
                    return definition(current.name(), current.description()
                            + ". For IMAGE versions, authorized preview attachments follow the committed tool reply. "
                            + "Only requested images are sent, with at most " + AgentImageInputService.MAX_IMAGES
                            + " distinct images in the Run context.",
                            current.inputSchema());
                }).toList();
    }

    /** 返回当前应用服务确实实现且可在此 Run 策略下开放的工具定义。 */
    private List<ToolCallback> modelDefinitions() {
        return List.of(
                definition("read_project_summary",
                        "Read the current project's bounded metadata and pinned Run limits",
                        READ_PROJECT_SUMMARY_SCHEMA),
                definition("read_selection",
                        "Read the canvas cards selected when this Run started; selection is "
                                + "intent only and grants no write permission",
                        READ_SELECTION_SCHEMA),
                definition("read_artifacts",
                        "Read up to twelve exact immutable versions bound to this Run or "
                                + "created by it; large content is explicitly truncated",
                        READ_ARTIFACTS_SCHEMA),
                definition("read_task_status",
                        "Read status only for up to twelve tasks in this Run; ordinary progress "
                                + "arrives through the scheduler, so do not repeatedly poll",
                        READ_TASK_STATUS_SCHEMA),
                definition("list_media_capabilities", "List published media capabilities and supported inputs; "
                        + "never infer unavailable capabilities", READ_PROJECT_SUMMARY_SCHEMA),
                definition("propose_media_generation", "Propose one fixed batch of image, video or audio generation. "
                        + "The server pauses this Run for user approval and archived outcomes. "
                        + "Never poll read_task_status for these tasks or claim user authorization. "
                        + "Use one output per request, with generationCount=1; await the server's final tool reply.",
                        PROPOSE_MEDIA_SCHEMA),
                definition("read_skill", "Activate one Skill from the available catalogue and read its full frozen SKILL.md plus reference and attachment manifests.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"skillVersionId\"],\"properties\":{\"skillVersionId\":{\"type\":\"string\",\"format\":\"uuid\"}}}"),
                definition("read_skill_resource", "Read one text resource frozen in this Run's selected Skill; "
                        + "paths and URLs outside the resource manifest are never accessible. Offsets count Unicode code points.",
                        """
                        {"type":"object","additionalProperties":false,"required":["path"],
                         "properties":{"path":{"type":"string","maxLength":160},
                         "offset":{"type":"integer","minimum":0},
                         "limit":{"type":"integer","minimum":1,"maximum":4000}}}
                        """),
                definition("create_text", "Create a text artifact in the current project",
                        CREATE_TEXT_SCHEMA),
                definition("revise_artifact",
                        "Create a new version of a Run-visible text artifact; "
                                + "requires expectedVersion from bound input or artifactVersions "
                                + "in a prior tool result, plus complete content",
                        REVISE_ARTIFACT_SCHEMA),
                definition("place_artifacts",
                        "Place one to six current Run-visible Artifact versions in this Agent's "
                                + "server-owned output group; returns itemId and itemVersion "
                                + "for arrangement, and reuses existing cards",
                        PLACE_ARTIFACTS_SCHEMA),
                definition("arrange_items",
                        "Arrange one to six Run-visible cards in this Agent's output group "
                                + "using itemId, content versionId and itemVersion from "
                                + "place_artifacts; requires layout CAS, never changes content",
                        ARRANGE_ITEMS_SCHEMA));
    }

    /** 组装模型可见定义；此回调只供序列化 Schema，不能直接执行业务调用。 */
    private ToolCallback definition(String name, String description, String schema) {
        return new DefinitionOnlyCallback(ToolDefinition.builder().name(name)
                .description(description).inputSchema(schema).build());
    }

    /**
     * 防止 Spring AI 自动运行模型工具调用；Runtime 必须先保存响应并逐项通过业务执行器。
     *
     * @param definition 暴露给模型、但不绑定副作用执行逻辑的工具定义
     */
    private record DefinitionOnlyCallback(ToolDefinition definition) implements ToolCallback {
        /** 返回传给模型的工具 Schema。 */
        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        /** 自动调用路径始终失败，杜绝绕过权限、审批和幂等账本。 */
        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("Tool callbacks require the durable business executor");
        }
    }
}
