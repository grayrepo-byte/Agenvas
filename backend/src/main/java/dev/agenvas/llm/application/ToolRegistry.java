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

    /** 文本产物只允许标题、正文和格式字段。 */
    private static final String CREATE_TEXT_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["title","text","format"],
             "properties":{"title":{"type":"string","minLength":1,"maxLength":160},
               "text":{"type":"string","minLength":1,"maxLength":20000},
               "format":{"type":"string","enum":["PLAIN_TEXT","MARKDOWN"]}}}
            """;
    /** 角色创建字段白名单及显式参考版本数量上限。 */
    private static final String CREATE_CHARACTER_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["name","description","appearance","referenceVersionIds"],
             "properties":{"name":{"type":"string","minLength":1,"maxLength":120},
               "description":{"type":"string","minLength":1,"maxLength":4000},
               "appearance":{"type":"string","minLength":1,"maxLength":4000},
               "referenceVersionIds":{"type":"array","maxItems":8,"uniqueItems":true,
                 "items":{"type":"string","format":"uuid"}}}}
            """;
    /** 场景创建字段白名单及显式参考版本数量上限。 */
    private static final String CREATE_SCENE_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["name","location","timeOfDay","lighting","style","referenceVersionIds"],
             "properties":{"name":{"type":"string","minLength":1,"maxLength":120},
               "location":{"type":"string","minLength":1,"maxLength":500},
               "timeOfDay":{"type":"string","minLength":1,"maxLength":80},
               "lighting":{"type":"string","minLength":1,"maxLength":1000},
               "style":{"type":"string","minLength":1,"maxLength":1000},
               "referenceVersionIds":{"type":"array","maxItems":8,"uniqueItems":true,
                 "items":{"type":"string","format":"uuid"}}}}
            """;
    /** 一次最多提出六个有序镜头，每个镜头显式关联角色和场景版本。 */
    private static final String CREATE_SHOTS_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["shots"],
             "properties":{"shots":{"type":"array","minItems":1,"maxItems":6,
               "items":{"type":"object","additionalProperties":false,
                 "required":["title","order","durationMs","description","camera","action",
                   "characterVersionIds","sceneVersionId"],
                 "properties":{"title":{"type":"string","minLength":1,"maxLength":160},
                   "order":{"type":"integer","minimum":1,"maximum":6},
                   "durationMs":{"type":"integer","minimum":100,"maximum":30000},
                   "description":{"type":"string","minLength":1,"maxLength":4000},
                   "camera":{"type":"string","minLength":1,"maxLength":1000},
                   "action":{"type":"string","minLength":1,"maxLength":2000},
                   "characterVersionIds":{"type":"array","maxItems":10,"uniqueItems":true,
                     "items":{"type":"string","format":"uuid"}},
                   "sceneVersionId":{"type":"string","format":"uuid"}}}}}}
            """;
    /** 媒体 DAG 提案 Schema；只表达待审批步骤，不包含批准字段。 */
    private static final String PROPOSE_GENERATION_PLAN_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["stage","objective","steps"],
             "properties":{"stage":{"type":"string","enum":["IMAGE","VIDEO"]},
               "objective":{"type":"string","minLength":1,"maxLength":1000},
               "steps":{"type":"array","minItems":1,"maxItems":6,
                 "items":{"type":"object","additionalProperties":false,
                   "required":["stepKey","outputSlotKey","shotArtifactId","shotVersionId",
                     "prompt","dependsOnStepKeys"],
                   "properties":{"stepKey":{"type":"string","minLength":1,"maxLength":160},
                     "outputSlotKey":{"type":"string","minLength":1,"maxLength":160},
                     "shotArtifactId":{"type":"string","format":"uuid"},
                     "shotVersionId":{"type":"string","format":"uuid"},
                     "imageArtifactId":{"type":"string","format":"uuid"},
                     "imageVersionId":{"type":"string","format":"uuid"},
                     "prompt":{"type":"string","minLength":1,"maxLength":8000},
                     "negativePrompt":{"type":"string","minLength":1,"maxLength":8000},
                     "dependsOnStepKeys":{"type":"array","maxItems":6,"uniqueItems":true,
                       "items":{"type":"string"}}}}}}}
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
    /** 语义连线关系白名单；连线只表示内容引用，不触发执行。 */
    private static final String LINK_ARTIFACTS_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["sourceArtifactId","expectedVersion","targetVersionId","relationship"],
             "properties":{"sourceArtifactId":{"type":"string","format":"uuid"},
               "expectedVersion":{"type":"integer","minimum":0},
               "targetVersionId":{"type":"string","format":"uuid"},
               "relationship":{"type":"string","enum":["CHARACTER_REFERENCE_IMAGE",
                 "SCENE_REFERENCE_IMAGE","SHOT_CHARACTER","SHOT_SCENE"]}}}
            """;
    /** 无声导出顺序提案；是否启动 FFmpeg 仍由独立人工审批接口决定。 */
    private static final String PROPOSE_EXPORT_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["aspectRatio","segments"],
             "properties":{"aspectRatio":{"type":"string",
               "enum":["LANDSCAPE_16_9","PORTRAIT_9_16","SQUARE_1_1"]},
               "segments":{"type":"array","minItems":1,"maxItems":6,
                 "items":{"type":"object","additionalProperties":false,
                   "required":["shotArtifactId","shotVersionId","videoArtifactId",
                     "videoVersionId","startMs","endMs"],
                   "properties":{"shotArtifactId":{"type":"string","format":"uuid"},
                     "shotVersionId":{"type":"string","format":"uuid"},
                     "videoArtifactId":{"type":"string","format":"uuid"},
                     "videoVersionId":{"type":"string","format":"uuid"},
                     "startMs":{"type":"integer","minimum":0,"maximum":60000},
                     "endMs":{"type":"integer","minimum":1,"maximum":60000}}}}}}
            """;

    /** 返回当前应用服务确实实现且可在此 Run 策略下开放的工具定义。 */
    public List<ToolCallback> modelDefinitions() {
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
                definition("create_text", "Create a text artifact in the current project",
                        CREATE_TEXT_SCHEMA),
                definition("create_character", "Create a character description",
                        CREATE_CHARACTER_SCHEMA),
                definition("create_scene", "Create a scene description", CREATE_SCENE_SCHEMA),
                definition("create_shots", "Create one to six ordered storyboard shots",
                        CREATE_SHOTS_SCHEMA),
                definition("revise_artifact",
                        "Create a new version of a Run-visible text, character, scene or shot; "
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
                        ARRANGE_ITEMS_SCHEMA),
                definition("link_artifacts",
                        "Create a typed semantic reference on a Run-visible character, scene "
                                + "or shot by immutable content revision; never triggers "
                                + "execution or selects media",
                        LINK_ARTIFACTS_SCHEMA),
                definition("propose_generation_plan",
                        "Propose a media DAG for authenticated user approval; never approves it",
                        PROPOSE_GENERATION_PLAN_SCHEMA),
                definition("propose_export",
                        "Save an ordered silent-export proposal for separate authenticated "
                                + "approval; requires current Run-visible shot and video "
                                + "versions and never starts FFmpeg",
                        PROPOSE_EXPORT_SCHEMA));
    }

    /** 局部重做只公开媒体计划提案，不能创建无关角色、场景、镜头或文本产物。 */
    public List<ToolCallback> modelDefinitions(boolean scopedRedo) {
        return scopedRedo ? List.of(definition("propose_generation_plan",
                "Propose one target-shot media plan for separate authenticated approval",
                PROPOSE_GENERATION_PLAN_SCHEMA)) : modelDefinitions();
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
