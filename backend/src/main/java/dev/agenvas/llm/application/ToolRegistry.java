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
    /** 当前画布或显式Skill输入的精确版本读取；最多 12 个互异 UUID。 */
    private static final String READ_ARTIFACTS_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["versionIds"],
             "properties":{"versionIds":{"type":"array","minItems":1,"maxItems":12,
               "uniqueItems":true,"items":{"type":"string","format":"uuid"}},
               "textOffset":{"type":"integer","minimum":0},
               "textLimit":{"type":"integer","minimum":1,"maximum":4000}}}
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

    private static final String EXTRACT_AUDIO_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["canvasItemId","sourceVersionId","expectedCanvasItemVersion"],
             "properties":{"canvasItemId":{"type":"string","format":"uuid"},
               "sourceVersionId":{"type":"string","format":"uuid"},
               "expectedCanvasItemVersion":{"type":"integer","minimum":0},
               "format":{"type":"string","enum":["WAV","MP3"],"default":"WAV"},
               "trackIndex":{"type":"integer","minimum":0,"maximum":31,"default":0}}}
            """;
    private static final String TRIM_AUDIO_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["canvasItemId","sourceVersionId","expectedCanvasItemVersion","startMs","endMs"],
             "properties":{"canvasItemId":{"type":"string","format":"uuid"},
               "sourceVersionId":{"type":"string","format":"uuid"},
               "expectedCanvasItemVersion":{"type":"integer","minimum":0},
               "startMs":{"type":"integer","minimum":0,"maximum":599900},
               "endMs":{"type":"integer","minimum":100,"maximum":600000},
               "format":{"type":"string","enum":["WAV","MP3"],"default":"WAV"}}}
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
    /** 布局命令须提供画布项和布局 CAS；有选用内容时须提供内容版本。 */
    private static final String ARRANGE_ITEMS_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["items","layout"],
             "properties":{"layout":{"type":"string",
               "enum":["HORIZONTAL","VERTICAL","GRID"]},
               "items":{"type":"array","minItems":1,"maxItems":6,
                 "items":{"type":"object","additionalProperties":false,
                   "required":["itemId","expectedVersion"],
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
    private static final String PAGED_READ_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{
             "offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":40}}}
            """;
    private static final String READ_CANVAS_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{
             "offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":40},
             "includeDrafts":{"type":"boolean"},
             "characterOffset":{"type":"integer","minimum":0,"default":0},
             "characterLimit":{"type":"integer","minimum":1,"maximum":20,"default":10},
             "characterId":{"type":"string","format":"uuid"},
             "characterTextOffset":{"type":"integer","minimum":0,"default":0},
             "characterTextLimit":{"type":"integer","minimum":1,"maximum":4000,"default":4000},
             "expectedCharacterVersion":{"type":"integer","minimum":0}}}
            """;
    private static final String MANAGE_CANVAS_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["action","itemId","expectedVersion"],
             "properties":{"action":{"type":"string","enum":["RENAME","MOVE","RESIZE","REMOVE","DUPLICATE"]},
             "itemId":{"type":"string","format":"uuid"},"expectedVersion":{"type":"integer","minimum":0},
             "title":{"type":"string","minLength":1,"maxLength":160},"x":{"type":"number"},"y":{"type":"number"},
             "width":{"type":"number","minimum":1},"height":{"type":"number","minimum":1},
             "expectedDraftVersion":{"type":"integer","minimum":0}}}
            """;
    private static final String UPDATE_MEDIA_DRAFT_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["itemId","expectedVersion"],
             "properties":{"itemId":{"type":"string","format":"uuid"},"expectedVersion":{"type":"integer","minimum":0},
             "prompt":{"type":"string","maxLength":20000},"parameters":{"type":"object"},
             "durationSeconds":{"type":"integer","minimum":1,"maximum":60},"capabilityId":{"type":"string","format":"uuid"},
             "videoInputMode":{"type":"string","enum":["TEXT","START_END","GENERAL_REFERENCE"]},
             "mediaInputs":{"type":"array","maxItems":14,"items":{"type":"object","additionalProperties":false,
               "required":["versionId","role"],"properties":{"versionId":{"type":"string","format":"uuid"},
               "role":{"type":"string","enum":["REFERENCE","START_FRAME","END_FRAME","AUDIO_REFERENCE","VIDEO_REFERENCE"]}}}},
             "mentions":{"type":"array","maxItems":1000,"items":{"type":"object","additionalProperties":false,
               "required":["versionId","role"],"properties":{"versionId":{"type":"string","format":"uuid"},
               "role":{"type":"string","enum":["REFERENCE","START_FRAME","END_FRAME","AUDIO_REFERENCE","VIDEO_REFERENCE"]}}}}}}
            """;
    private static final String PROPOSE_CANVAS_MEDIA_SCHEMA = PROPOSE_MEDIA_SCHEMA.replace(
            "\"capabilityId\":{", "\"canvasItemId\":{\"type\":\"string\",\"format\":\"uuid\"},"
                    + "\"expectedItemVersion\":{\"type\":\"integer\",\"minimum\":0},"
                    + "\"expectedDraftVersion\":{\"type\":\"integer\",\"minimum\":0},\"capabilityId\":{");
    private static final String COMPOSE_VIDEO_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["title","shots"],"properties":{"title":{"type":"string","minLength":1,"maxLength":160},"rationale":{"type":"string","maxLength":2000,"description":"面向用户的简短编排依据，不保存模型私有推理"},"originalAudioMode":{"type":"string","enum":["MUTE","BACKGROUND","KEEP"],"default":"MUTE"},"output":{"type":"object","additionalProperties":false,"properties":{"resolution":{"type":"string","enum":["720P","1080P"],"default":"720P"},"aspectRatio":{"type":"string","enum":["16:9","9:16","1:1"],"default":"16:9"}}},"music":{"type":"object","additionalProperties":false,"required":["audioVersionId"],"properties":{"audioVersionId":{"type":"string","format":"uuid"},"sourceStartMs":{"type":"integer","minimum":0,"maximum":599999,"default":0},"volume":{"type":"number","minimum":0,"maximum":1,"default":0.2}}},"subtitles":{"type":"array","maxItems":100,"items":{"type":"object","additionalProperties":false,"required":["text","startMs","endMs"],"properties":{"text":{"type":"string","minLength":1,"maxLength":500},"startMs":{"type":"integer","minimum":0,"maximum":59999},"endMs":{"type":"integer","minimum":1,"maximum":60000}}}},"shots":{"type":"array","minItems":1,"maxItems":12,"items":{"type":"object","additionalProperties":false,"required":["videoVersionId","lines"],"properties":{"videoVersionId":{"type":"string","format":"uuid"},"sourceStartMs":{"type":"integer","minimum":0,"maximum":599999,"default":0},"durationMs":{"type":"integer","minimum":100,"maximum":600000,"description":"取源时长；省略时取素材剩余长度；成片时长按速度换算并受60秒总限约束"},"speed":{"type":"number","minimum":0.5,"maximum":2,"default":1},"fadeMs":{"type":"integer","minimum":0,"maximum":500,"default":0,"description":"每镜开头及结尾的淡黑时长，不重叠镜头"},"lines":{"type":"array","maxItems":30,"items":{"type":"object","additionalProperties":false,"required":["audioVersionId"],"properties":{"audioVersionId":{"type":"string","format":"uuid"},"startMs":{"type":"integer","minimum":0,"maximum":59999,"description":"可选镜内锚点；省略时依声音活动自动排布"}}}}}}}}}
            """;

    public List<ToolCallback> modelDefinitions(tools.jackson.databind.JsonNode policy) {
        var allowed = RunToolPolicy.allowed(policy);
        return modelDefinitions().stream().filter(tool -> allowed.contains(
                tool.getToolDefinition().name())).toList();
    }

    /** 返回当前应用服务确实实现且可在此 Run 策略下开放的工具定义。 */
    private List<ToolCallback> modelDefinitions() {
        return List.of(
                definition("read_canvas", "Read a page of currently present authorized nodes, including new Run outputs, fresh node CAS, locks, selections and media drafts. "
                        + "Use nextOffset for nodes. Characters have an independent characterOffset/characterLimit cursor (default 10, maximum 20); follow nextCharacterOffset even on an empty canvas. "
                        + "Character and shot binding summaries omit descriptive text and expose names, stable IDs, versions, voice presence and review/application status only. "
                        + "For full safe character text, pass characterId and optional characterTextOffset/characterTextLimit (at most 4000 Unicode code points). "
                        + "characterDetails.text is one fragment of a JSON document; concatenate its textPage ranges in order. A final page alone is not the full document. "
                        + "Use the returned characterVersion as expectedCharacterVersion on every continuation (required for nonzero characterTextOffset); a changed profile must be reread from offset zero. "
                        + "Character metadata and text never grant media access or reveal profile media reference IDs. Set includeDrafts=true with limit at most 6 to inspect editable media input only when needed. "
                        + "Image pixels and immutable content require read_artifacts. All current nodes and their selected versions are available, including nodes added or changed during this Run.", READ_CANVAS_SCHEMA),
                definition("manage_canvas_node", "Rename, move, resize, remove or duplicate an authorized unlocked node. "
                        + "Use itemVersion as expectedVersion. MOVE needs x/y, RESIZE needs width/height, RENAME needs title. "
                        + "Media DUPLICATE also needs mediaDraft.version as expectedDraftVersion. REMOVE keeps the underlying artifact and history.", MANAGE_CANVAS_SCHEMA),
                definition("update_media_draft", "Edit an authorized unlocked IMAGE, VIDEO or AUDIO node's working draft without generating. "
                        + "Use mediaDraft.version as expectedVersion. Omitted fields retain their existing values; replacing prompt clears old structured mentions unless mentions is supplied. U+FFFC markers require matching mentions. Media references must be exact authorized versions.", UPDATE_MEDIA_DRAFT_SCHEMA),
                definition("extract_audio", "Extract the chosen audio track from an exact selected VIDEO version into a new AUDIO node, using a fixed local processor with no Provider charge or approval. "
                        + "Observe canvasItemId, sourceVersionId and itemVersion through read_canvas; source must be unlocked. WAV default, MP3 optional, trackIndex is zero-based. "
                        + "The server waits durably then resumes this Run with the archived audio version. Never poll or claim completion from its acceptance receipt. No ASR, voice isolation or speaker embedding is performed.", EXTRACT_AUDIO_SCHEMA),
                definition("trim_audio", "Create an immutable audio segment in a derived node from an exact selected AUDIO version and observed itemVersion. "
                        + "Use integer startMs/endMs within measured source duration, at least 100 ms; WAV default, MP3 optional. This fixed local command has no Provider charge or approval. "
                        + "The server waits durably then resumes with the archived version, usable as AUDIO_REFERENCE. It does not remove music or identify speakers; choose the interval from actual evidence. Never poll or resubmit UNKNOWN.", TRIM_AUDIO_SCHEMA),
                definition("compose_video", "Queue local video trimming, joining and dubbing from authorized exact VIDEO/AUDIO versions. Zero dialogue is allowed. Optional shot speed/fades, KEEP original sound, music, timed subtitles and output shape are supported. "
                        + "First read storyboard/script text and immutable media prompts plus mediaMetadata.durationMs; infer shot order and match each spoken line from that evidence. "
                        + "rationale is a short public edit summary, never private reasoning. shots are playback order; lines are spoken order. "
                        + "sourceStartMs defaults to zero and omitted durationMs uses the remaining source video. startMs is optional, relative to the selected shot, only for an evidenced story anchor; "
                        + "omit it for automatic waveform/silence placement. At most 12 shots, 30 total lines and 60000 ms total. "
                        + "MUTE replaces original sound; BACKGROUND keeps it quietly beneath dialogue. The local renderer allows at most 1.15x dialogue speed and fails rather than cutting speech. "
                        + "No external media generation or media approval occurs. The receipt confirms acceptance only: the server durably waits for completion and resumes this same Run with the archived version. "
                        + "Stopping the Director does not cancel an accepted composition; the user can cancel it from the new output node. "
                        + "Continue from the final localTaskOutcome reply; do not poll or claim completion from acceptance, semantic audio understanding or lip synchronization.", COMPOSE_VIDEO_SCHEMA),
                definition("read_project_summary",
                        "Read the current project's bounded metadata and pinned Run limits",
                        READ_PROJECT_SUMMARY_SCHEMA),
                definition("read_selection",
                        "Read the canvas cards selected when this Run started; selection is "
                                + "intent only and grants no write permission",
                        PAGED_READ_SCHEMA),
                definition("read_artifacts",
                        "Read up to twelve exact immutable versions selected on the current canvas or explicitly mapped as Skill inputs; "
                                + "large content is explicitly truncated. To read full TEXT content, pass textOffset and/or textLimit: "
                                + "pagination requires exactly one TEXT version, counts Unicode code points, and defaults to offset 0 / limit 4000. "
                                + "Follow textPage.nextOffset on the same version until null; the final page alone does not mean earlier pages were read. "
                                + "For images read one per tool batch; the committed reply attaches pixels. "
                                + "Record public observations before requesting another image; only the latest image remains attached",
                        READ_ARTIFACTS_SCHEMA),
                definition("read_media_sample", "Inspect one authorized exact VIDEO/AUDIO version. Request up to four unique frame timestamps in milliseconds, and/or an audioStartMs/audioDurationMs pair (max 15 seconds). Next model call attaches a timestamped contact sheet and/or WAV sample; no bytes in the tool ledger. Read one sample per batch. Inspect only attached content; sparse frames cannot verify every intervening action. Audio requires an audio-capable model; do not infer speech from metadata.",
                        """
                        {"type":"object","additionalProperties":false,"required":["versionId"],"properties":{
                          "versionId":{"type":"string","format":"uuid"},
                          "timestampsMs":{"type":"array","maxItems":4,"uniqueItems":true,"items":{"type":"integer","minimum":0,"maximum":599999}},
                          "audioStartMs":{"type":"integer","minimum":0,"maximum":599999},
                          "audioDurationMs":{"type":"integer","minimum":1,"maximum":15000}}}
                        """),
                definition("read_task_status",
                        "Read status only for up to twelve tasks in this Run; ordinary progress "
                                + "arrives through the scheduler, so do not repeatedly poll",
                        READ_TASK_STATUS_SCHEMA),
                definition("list_media_capabilities", "List published media capabilities and supported inputs; "
                        + "never infer unavailable capabilities", READ_PROJECT_SUMMARY_SCHEMA),
                definition("propose_media_generation", "Propose one fixed batch of image, video or audio generation. "
                        + "The server pauses this Run for user approval and archived outcomes. "
                        + "Never poll read_task_status for these tasks or claim user authorization. "
                        + "Use one output per request, with generationCount=1; await the server's final tool reply. "
                        + "Supply canvasItemId, expectedItemVersion and expectedDraftVersion to target an existing node; omit them to create a node. Both paths require approval.",
                        PROPOSE_CANVAS_MEDIA_SCHEMA),
                definition("read_skill", "Activate one Skill from the available catalogue and read its full frozen SKILL.md plus reference and attachment manifests.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"skillVersionId\"],\"properties\":{\"skillVersionId\":{\"type\":\"string\",\"format\":\"uuid\"}}}"),
                definition("read_skill_asset", "Read one registered Skill reference image after read_skill has activated its exact version. "
                        + "Its image preview follows the committed reply as LLM context only; it has no project artifact ID and cannot be a Provider media input. "
                        + "Request one image per tool batch and record public observations before another read; only the latest image stays attached.",
                        """
                        {"type":"object","additionalProperties":false,"required":["skillVersionId","alias"],
                         "properties":{"skillVersionId":{"type":"string","format":"uuid"},
                         "alias":{"type":"string","minLength":1,"maxLength":64}}}
                        """),
                definition("read_skill_resource", "Read one page of a registered text attachment after a successful read_skill reply. "
                        + "Use the same skillVersionId and exact resourceManifest path relative to the Skill root. "
                        + "Resolve relative Markdown links against their source directory; omit heading fragments. "
                        + "Offsets count Unicode code points. Follow nextOffset until null when the task needs the remaining text; a partial page is not complete.",
                        PROGRESSIVE_SKILL_RESOURCE_SCHEMA),
                definition("create_text", "Create a text artifact in the current project",
                        CREATE_TEXT_SCHEMA),
                definition("revise_artifact",
                        "Create a new version of a current canvas text artifact; "
                                + "requires expectedVersion observed from read_artifacts or a prior successful content write, plus complete content",
                        REVISE_ARTIFACT_SCHEMA),
                definition("arrange_items",
                        "Arrange one to six current unlocked canvas nodes with layout CAS. "
                                + "Supply each selected versionId when present; omit it only for an empty media draft. Requires observed itemVersion",
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
