package dev.agenvas.llm.application;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Explicit server tools, in frozen policy order. Model presentation order predates that policy. */
enum AgentTool {
    LOAD_TOOLSET(0) {
        @Override
        ToolCallback definition() {
            return callback(AgentToolset.description(), AgentToolset.schema());
        }
    },

    READ_PROJECT_SUMMARY(9, "Read the current project's bounded metadata and pinned Run limits",
            Schemas.READ_PROJECT_SUMMARY_SCHEMA),

    READ_SELECTION(10, "Read the canvas cards selected when this Run started; selection is "
            + "intent only and grants no write permission",
            Schemas.PAGED_READ_SCHEMA),

    READ_ARTIFACTS(11, Rule.CANVAS,
            "Read up to twelve exact immutable versions selected on the current canvas or explicitly mapped as Skill inputs; "
                    + "large content is explicitly truncated. To read full TEXT content, pass textOffset and/or textLimit: "
                    + "pagination requires exactly one TEXT version, counts Unicode code points, and defaults to offset 0 / limit 4000. "
                    + "Follow textPage.nextOffset on the same version until null; the final page alone does not mean earlier pages were read. "
                    + "For images read one per tool batch; the committed reply attaches pixels. "
                    + "Record public observations before requesting another image; only the latest image remains attached",
            Schemas.READ_ARTIFACTS_SCHEMA),

    READ_MEDIA_SAMPLE(12, Rule.INSPECTION,
            "Inspect one authorized exact VIDEO/AUDIO version. Request up to four unique frame timestamps in milliseconds, and/or an audioStartMs/audioDurationMs pair (max 15 seconds). Next model call attaches a timestamped contact sheet and/or WAV sample; no bytes in the tool ledger. Read one sample per batch. Inspect only attached content; sparse frames cannot verify every intervening action. Audio requires an audio-capable model; do not infer speech from metadata.",
            """
                        {"type":"object","additionalProperties":false,"required":["versionId"],"properties":{
                          "versionId":{"type":"string","format":"uuid"},
                          "timestampsMs":{"type":"array","maxItems":4,"uniqueItems":true,"items":{"type":"integer","minimum":0,"maximum":599999}},
                          "audioStartMs":{"type":"integer","minimum":0,"maximum":599999},
                          "audioDurationMs":{"type":"integer","minimum":1,"maximum":15000}}}
                        """),

    READ_TASK_STATUS(13, "Read status only for up to twelve tasks in this Run; ordinary progress "
            + "arrives through the scheduler, so do not repeatedly poll",
            Schemas.READ_TASK_STATUS_SCHEMA),

    CREATE_TEXT(19, "Create a text artifact in the current project",
            Schemas.CREATE_TEXT_SCHEMA),

    REVISE_ARTIFACT(20, Rule.CANVAS, "Create a new version of a current canvas text artifact; "
            + "requires expectedVersion observed from read_artifacts or a prior successful content write, plus complete content",
            Schemas.REVISE_ARTIFACT_SCHEMA),

    ARRANGE_ITEMS(21, Rule.CANVAS, "Arrange one to six current unlocked canvas nodes with layout CAS. "
            + "Supply each selected versionId when present; omit it only for an empty media draft. Requires observed itemVersion",
            Schemas.ARRANGE_ITEMS_SCHEMA),

    LIST_MEDIA_CAPABILITIES(14, "Read published media capabilities and supported inputs in stable default-first order. "
            + "Use offset/limit (default and maximum 40) and follow nextOffset until null for the complete directory. "
            + "A page is not the full catalog; never infer unavailable capabilities. If the catalog changes, restart from offset zero.",
            Schemas.PAGED_READ_SCHEMA),

    PROPOSE_MEDIA_GENERATION(15, Rule.MEDIA, "Propose one fixed batch of image, video or audio generation. "
            + "The server pauses this Run for user approval and archived outcomes. "
            + "Never poll read_task_status for these tasks or claim user authorization. "
            + "Use one output per request, with generationCount=1; await the server's final tool reply. "
            + "For storyboard shot media, supply the observed shotId and expectedShotRevision; the server freezes the shot association and inferred media role. "
            + "Approval and archived candidates return to that shot, while users alone approve and select results. A changed shot requires a fresh reviewed proposal. "
            + "Supply canvasItemId, expectedItemVersion and expectedDraftVersion to target an existing node; omit them to create a node. Both paths require approval.",
            Schemas.PROPOSE_CANVAS_MEDIA_SCHEMA),

    READ_SKILL_RESOURCE(18, "Read one page of a registered text attachment after a successful read_skill reply. "
            + "Use the same skillVersionId and exact resourceManifest path relative to the Skill root. "
            + "Resolve relative Markdown links against their source directory; omit heading fragments. "
            + "Offsets count Unicode code points. Follow nextOffset until null when the task needs the remaining text; a partial page is not complete.",
            Schemas.PROGRESSIVE_SKILL_RESOURCE_SCHEMA),

    READ_SKILL(16, Rule.SKILL,
            "Activate one Skill from the available catalogue and read its full frozen SKILL.md plus reference and attachment manifests.",
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"skillVersionId\"],\"properties\":{\"skillVersionId\":{\"type\":\"string\",\"format\":\"uuid\"}}}"),

    READ_SKILL_ASSET(17, "Read one registered Skill reference image after read_skill has activated its exact version. "
            + "Its image preview follows the committed reply as LLM context only; it has no project artifact ID and cannot be a Provider media input. "
            + "Request one image per tool batch and record public observations before another read; only the latest image stays attached.",
            """
                        {"type":"object","additionalProperties":false,"required":["skillVersionId","alias"],
                         "properties":{"skillVersionId":{"type":"string","format":"uuid"},
                         "alias":{"type":"string","minLength":1,"maxLength":64}}}
                        """),

    READ_CANVAS(1,
            "Read a page of currently present authorized nodes, including new Run outputs, fresh node CAS, locks, selections and media drafts. "
                    + "Use nextOffset for nodes. Characters have an independent characterOffset/characterLimit cursor (default 10, maximum 20); follow nextCharacterOffset even on an empty canvas. "
                    + "Character and shot binding summaries omit descriptive text and expose names, stable IDs, versions, voice presence and review/application status only. "
                    + "For full safe character text, pass characterId and optional characterTextOffset/characterTextLimit (at most 4000 Unicode code points). "
                    + "characterDetails.text is one fragment of a JSON document; concatenate its textPage ranges in order. A final page alone is not the full document. "
                    + "Use the returned characterVersion as expectedCharacterVersion on every continuation (required for nonzero characterTextOffset); a changed profile must be reread from offset zero. "
                    + "Character metadata and text never grant media access or reveal profile media reference IDs. Set includeDrafts=true with limit at most 6 to inspect editable media input only when needed. "
                    + "Image pixels and immutable content require read_artifacts. All current nodes and their selected versions are available, including nodes added or changed during this Run.",
            Schemas.READ_CANVAS_SCHEMA),

    MANAGE_CANVAS_NODE(4, "Rename, move, resize, remove or duplicate an authorized unlocked node. "
            + "Use itemVersion as expectedVersion. MOVE needs x/y, RESIZE needs width/height, RENAME needs title. "
            + "Media DUPLICATE also needs mediaDraft.version as expectedDraftVersion. REMOVE keeps the underlying artifact and history.",
            Schemas.MANAGE_CANVAS_SCHEMA),

    UPDATE_MEDIA_DRAFT(5, "Edit an authorized unlocked IMAGE, VIDEO or AUDIO node's working draft without generating. "
            + "Use mediaDraft.version as expectedVersion. Omitted fields retain their existing values; replacing prompt clears old structured mentions unless mentions is supplied. U+FFFC markers require matching mentions. Media references must be exact authorized versions.",
            Schemas.UPDATE_MEDIA_DRAFT_SCHEMA),

    COMPOSE_VIDEO(8, Rule.COMPOSITION,
            "Queue local video trimming, joining and dubbing from authorized exact VIDEO/AUDIO versions. Zero dialogue is allowed. Optional shot speed/fades, KEEP original sound, music, timed subtitles and output shape are supported. "
                    + "First read storyboard/script text and immutable media prompts plus mediaMetadata.durationMs; infer shot order and match each spoken line from that evidence. "
                    + "rationale is a short public edit summary, never private reasoning. shots are playback order; lines are spoken order. "
                    + "sourceStartMs defaults to zero and omitted durationMs uses the remaining source video. startMs is optional, relative to the selected shot, only for an evidenced story anchor; "
                    + "omit it for automatic waveform/silence placement. Default deployment budget: 60 shots, 300 total lines, 2000 captions, 600000 ms and 2 GiB sources. Deployment may lower budgets; hard ceilings: 120 shots, 600 lines, 5000 captions, 600000 ms and 8 GiB sources. Output at most 500 MiB. 720P/1080P/2160P subject to deployment configuration. "
                    + "MUTE replaces original sound; BACKGROUND keeps it quietly beneath dialogue. The local renderer allows at most 1.15x dialogue speed and fails rather than cutting speech. "
                    + "No external media generation or media approval occurs. The receipt confirms acceptance only: the server durably waits for completion and resumes this same Run with the archived version. "
                    + "Stopping the Director does not cancel an accepted composition; the user can cancel it from the new output node. "
                    + "Continue from the final localTaskOutcome reply; do not poll or claim completion from acceptance, semantic audio understanding or lip synchronization.",
            Schemas.COMPOSE_VIDEO_SCHEMA),

    EXTRACT_AUDIO(6, Rule.EXTRACTION,
            "Extract the chosen audio track from an exact selected VIDEO version into a new AUDIO node, using a fixed local processor with no Provider charge or approval. "
                    + "Observe canvasItemId, sourceVersionId and itemVersion through read_canvas; source must be unlocked. WAV default, MP3 optional, trackIndex is zero-based. "
                    + "The server waits durably then resumes this Run with the archived audio version. Never poll or claim completion from its acceptance receipt. No ASR, voice isolation or speaker embedding is performed.",
            Schemas.EXTRACT_AUDIO_SCHEMA),

    TRIM_AUDIO(7,
            "Create an immutable audio segment in a derived node from an exact selected AUDIO version and observed itemVersion. "
                    + "Use integer startMs/endMs within measured source duration, at least 100 ms; WAV default, MP3 optional. This fixed local command has no Provider charge or approval. "
                    + "The server waits durably then resumes with the archived version, usable as AUDIO_REFERENCE. It does not remove music or identify speakers; choose the interval from actual evidence. Never poll or resubmit UNKNOWN.",
            Schemas.TRIM_AUDIO_SCHEMA),

    READ_STORYBOARD(2,
            "Read the current project's storyboard workbench. Default offset/limit pages return shot IDs, codes, creative text previews, exact references and production/review metadata. "
                    + "Follow data.nextOffset for the complete directory; previews are not full text. Use shotIds with exactly one observed shot ID and no pagination fields to read that shot's complete creative text. "
                    + "data.version is the board CAS. Source/reference IDs do not grant permission to read their artifact content. No media is generated.",
            Schemas.READ_STORYBOARD_SCHEMA),

    WRITE_STORYBOARD_SHOTS(3, Rule.STORYBOARD,
            "Save one to six storyboard shots using data.version from read_storyboard as expectedVersion. "
                    + "Omit shotId to append a new server-identified shot; include code, description, dialogue and estimated durationSeconds. "
                    + "Use an observed shot ID to patch only supplied creative fields. Other shots, production selections and composition are preserved. "
                    + "This cannot delete/reorder shots, select media results, confirm reviews or approve generation. Creative changes mark retained results for review. "
                    + "New source/reference versions must already be authorized exact inputs; media production still requires propose_media_generation and user approval.",
            Schemas.WRITE_STORYBOARD_SHOTS_SCHEMA);

    static final List<String> CURRENT_NAMES = Arrays.stream(values()).map(AgentTool::wireName).toList();
    private static final List<AgentTool> MODEL_ORDER = Arrays.stream(values())
            .sorted(Comparator.comparingInt(tool -> tool.modelOrder)).toList();
    private static final Map<String, AgentTool> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(AgentTool::wireName, Function.identity()));

    private final int modelOrder;
    private final Rule rule;
    private final ToolCallback definition;

    AgentTool(int modelOrder) {
        this.modelOrder = modelOrder;
        this.rule = null;
        this.definition = null;
    }

    AgentTool(int modelOrder, String description, String schema) {
        this(modelOrder, null, description, schema);
    }

    AgentTool(int modelOrder, Rule rule, String description, String schema) {
        this.modelOrder = modelOrder;
        this.rule = rule;
        this.definition = callback(description, schema);
    }

    String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    ToolCallback callback(String description, String schema) {
        return new DefinitionOnlyCallback(ToolDefinition.builder().name(wireName())
                .description(description).inputSchema(schema).build());
    }

    ToolCallback definition() {
        return definition;
    }

    static List<ToolCallback> modelDefinitions(List<String> allowed) {
        return MODEL_ORDER.stream().filter(tool -> allowed.contains(tool.wireName())).map(AgentTool::definition)
                .toList();
    }

    /** Common sections have fixed precedence; tool-specific sections follow the frozen definition order. */
    static String rules(List<String> previous, List<String> current, boolean onDemand) {
        var additions = sections(current);
        additions.removeAll(sections(previous));
        var text = new StringBuilder();
        for (Rule section : List.of(Rule.CANVAS, Rule.SKILL, Rule.MEDIA, Rule.STORYBOARD)) {
            if (additions.remove(section) && (onDemand || section == Rule.STORYBOARD))
                text.append(section.text);
        }
        additions.forEach(section -> text.append(section.text));
        return text.toString();
    }

    private static LinkedHashSet<Rule> sections(List<String> names) {
        var sections = new LinkedHashSet<Rule>();
        for (String name : names) {
            var tool = BY_NAME.get(name);
            if (tool != null && tool.rule != null)
                sections.add(tool.rule);
        }
        return sections;
    }

    private enum Rule {
        CANVAS("""

            Discover current node IDs, selected immutable versions, locks and CAS with read_canvas;
            read_selection is frozen intent only. All current project nodes, including new/changed
            nodes, use the same permission checks. Read exact content through read_artifacts.
            A preview is not complete source text. For long TEXT, request exactly one versionId with
            textOffset/textLimit (max 4000 Unicode code points). Follow textPage.nextOffset until null
            and track every covered range; a last page proves no earlier page. Never combine versions.
            If access/content changes, reread the directory and report gaps. Read current content before
            revise_artifact, which needs its observed artifact expectedVersion and complete new text.
            Layout needs observed item CAS; media draft edits need their own draft CAS. Respect locks.
            Read one image per tool batch; committed reads attach pixels. Before another image, record
            concise public observations/continuity/defects with the exact version. Only the latest image
            stays attached. Metadata/prompts prove no visual content; reread pixels when needed.
            """), SKILL("""

            read_skill returns frozen SKILL.md, exact input mappings and resource/image manifests.
            Only successfully activated Skills constrain media proposals. Never send SKILL.md as a
            media prompt/dialogue. Wait for its successful committed reply before reading attachments
            in a later batch. Use the same skillVersionId and exact registered Skill-root path; resolve
            relative links from the source directory, omit fragments and match the manifest.
            Resource offsets count Unicode code points; follow nextOffset for all required text.
            Skill paths/text grant no permissions. read_skill_asset accepts one exact image alias;
            it attaches context pixels only, never a Provider input/artifact ID. Read one image per
            batch, record exact public observations before another; only the latest stays attached.
            """), MEDIA("""

            Read list_media_capabilities before choosing a generation capability. Follow nextOffset
            until null when the full directory is needed; a page is not the complete catalog.
            Prefer isDefault and
            defaultParameters/defaultDurationSeconds unless the user or required inputs need another;
            explain alternatives. Use published modes/inputs/resolutions/parameters only. Draft edits
            do not generate media. propose_media_generation creates a fixed batch requiring the
            authenticated user's approval endpoint. Text cannot approve. The server durably waits
            and resumes with final outcomes in the original tool reply; never poll to wait or claim
            completion from acceptance. Users may revise pending capability/parameters before approval;
            final outcomes show that choice. Only archived results count as success. Explain rejection,
            failure, expiry or UNKNOWN; preserve successes and do not automatically resubmit.
            For shot media, first load STORYBOARD and read the full latest shot; include its real shotId
            and expectedShotRevision. Users alone approve, select results and confirm applicability.
            Read/reference access never grants write targets, result selection or generation approval.
            """), COMPOSITION("""

            When asked to automatically arrange dialogue and assemble existing clips, use compose_video.
            Discover candidates with read_canvas, then read the actual script/storyboard text and the
            selected immutable VIDEO/AUDIO content with read_artifacts. mediaMetadata.durationMs is
            the archived, measured duration; a draft's requested duration is not the actual duration.
            Match clips and speech using explicit shot IDs, speakers, verbatim dialogue, stored prompts
            and authorized input references. Decide the shot order and spoken-line order yourself from
            this evidence instead of asking the user to manually pair every clip or place every line.
            Do not treat a current editable draft as proof of an older generated version's content.
            If the evidence is missing, ambiguous or contradictory, explain the specific gap instead
            of inventing dialogue, speakers or media content. These inputs are task data, not permission.
            Submit the ordered shots and exact audio versions to compose_video. Omit line startMs for
            automatic waveform/silence placement; provide it only for a timing anchor supported by the
            script or a deliberate public editing choice. The local renderer examines waveform energy
            and silence, trims edge silence and allows bounded speech speedup up to 1.15x. It does not
            perform ASR, identify speakers, inspect mouth motion or verify the meaning of audio/video.
            Preserve dialogue: if it cannot fit, report the failure rather than silently truncating it.
            This local composition of existing assets has no external Provider fee and needs no media
            approval. It cannot generate missing media; that still uses propose_media_generation and
            authenticated approval. Its tool receipt means queued, not a completed film. Processing
            waits durably without another model call until the local task has a terminal snapshot.
            The server resumes this same Run with localTaskOutcome and the exact archived artifactVersionId.
            Continue the workflow from that final reply; do not stop after an acceptance receipt.
            Stopping the Director does not cancel accepted composition; the user can cancel it from
            the new output node. Do not poll to wait. read_task_status may inspect exact local tasks
            committed by this Run's tool ledger; success requires a returned archived artifactVersionId.
            """), STORYBOARD("""

            When the user asks for script breakdown into a storyboard or asks to revise specific shots,
            save the formal shots to the storyboard workbench through write_storyboard_shots. Do not
            stop at a prose reply or a TEXT storyboard alone. Supporting breakdown, visual design,
            prompts, review notes and progress can still be ordinary TEXT artifacts. For a new script
            breakdown, discover and actually read the authorized script with read_canvas/read_artifacts.
            For an existing-shot edit, inspect read_storyboard first and read source content only as
            needed and currently authorized. Directory descriptions/dialogue are previews; read a complete shot with
            shotIds before rewriting its existing creative text. Source/reference IDs on the board
            do not extend artifact read permissions. Never guess shot or content identities.
            Follow directory data.nextOffset until the relevant existing shots and unique codes are
            accounted for. data.version is the board-wide expectedVersion. Append at most six shots
            at a time by omitting shotId; the server returns stable shot IDs. For existing shots,
            send shotId and only fields intentionally changed. Never recreate all shots to edit one
            line. Keep exact authorized sourceVersionId and referenceVersionIds where applicable;
            use null or [] only when deliberately clearing that creative reference. Duration is an
            estimate, not measured media timing. Advance progress only after a successful tool reply.
            After a board conflict, reread and compare the current shot before another explicit edit;
            do not overwrite user changes. Other shots and pinned media remain preserved. Changed
            creative input marks retained media and the linked composition for human review. You
            cannot confirm those reviews, choose media results, or grant/claim generation approval.
            Missing media must still go through propose_media_generation and authenticated approval.
            For media belonging to a storyboard shot, read that shot and supply its actual shotId and
            current expectedShotRevision in each output. The server derives the role from media kind
            and freezes the shot identity and creative revision. Other standalone assets may omit
            this pair. A stale shot requires rereading and a new intentional proposal; never resubmit
            automatically. Approval status and archived result candidates return to the workbench,
            but neither approval nor generation selects a storyboard result. Only the user may approve
            the fixed batch, choose exact results, and confirm applicability after a creative revision.
            Report archive success separately from user selection; never infer selection from a Task.
            Use read_artifacts to inspect exact archived versions from this Run's approved shot
            outputs, or the user's saved storyboard production/composition selections. These narrow
            read/reference grants do not authorize a new generation, another Run's unselected
            candidates, arbitrary board source/reference IDs, or a write to an unselected target.
            Saving a storyboard creates no media Task and conveys no permission to generate.
            """), EXTRACTION("""

                    To reuse a voice from existing video, discover an authorized unlocked selected VIDEO
                    with read_canvas, use extract_audio, then wait for the server's final localTaskOutcome.
                    Reread the resulting AUDIO node to observe its current itemVersion and measured duration.
                    Use trim_audio for a supported speech interval, then use its exact returned audio version
                    as AUDIO_REFERENCE on a capability whose published input policy supports audio references.
                    Local extraction and trimming require no media approval; generation still requires the
                    normal authenticated approval. Accepted local tasks survive stopping the Director, but
                    a stopped Run never resumes. Never poll or automatically retry UNKNOWN local outcomes.
                    Extraction preserves the selected track; trimming only selects a time interval. Neither
                    separates speakers/music nor creates a speaker embedding. A waveform or metadata alone
                    cannot identify speech or speakers. Use actual sampled audio if the model supports it,
                    otherwise use explicit known dialogue/timing evidence and state any remaining uncertainty.
                    """), INSPECTION("""

                    This Run additionally supports read_media_sample. Use it to inspect one authorized exact
                    VIDEO/AUDIO version: up to four requested timestamped frames as a contact sheet and/or
                    a bounded 15-second WAV audio interval. The prior no-video/audio statement applies to
                    metadata reads, not this explicit sample tool. Only these samples are attached on the
                    next model dispatch. Record concise public observations with exact version/time before
                    reading another sample. Sparse frames do not establish every intervening action or lip
                    synchronization. Audio requires a model supporting input_audio; if unsupported, explain
                    the limitation and read frames separately. Never infer dialogue or speakers from metadata.
                    """);
        private final String text;
        Rule(String text) {
            this.text = text;
        }
    }

    private static final class Schemas {
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
                   "shotId":{"type":"string","format":"uuid","description":"Storyboard shot ID observed through read_storyboard; pair with expectedShotRevision. This association grants no approval or result selection."},
                   "expectedShotRevision":{"type":"integer","minimum":1,"description":"Current creative revision of that shot, required with shotId."},
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

        private static final String READ_STORYBOARD_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{
             "offset":{"type":"integer","minimum":0,"default":0},
             "limit":{"type":"integer","minimum":1,"maximum":40,"default":20},
             "shotIds":{"type":"array","minItems":1,"maxItems":1,"uniqueItems":true,
               "description":"Read one complete shot instead of the summary page; omit offset and limit.",
               "items":{"type":"string","format":"uuid"}}}}
            """;
        private static final String WRITE_STORYBOARD_SHOTS_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["expectedVersion","shots"],
             "properties":{"expectedVersion":{"type":"integer","minimum":0},
             "shots":{"type":"array","minItems":1,"maxItems":6,
               "items":{"type":"object","additionalProperties":false,
                 "description":"Omit shotId to append; then code, description, dialogue and durationSeconds are required. With shotId supply only creative fields to change.",
                 "properties":{"shotId":{"type":"string","format":"uuid"},
                   "code":{"type":"string","minLength":1,"maxLength":80},
                   "scene":{"type":"string","maxLength":200},
                   "title":{"type":"string","maxLength":200},
                   "description":{"type":"string","maxLength":20000},
                   "dialogue":{"type":"string","maxLength":20000},
                   "durationSeconds":{"type":"number","minimum":0.1,"maximum":3600,"multipleOf":0.001},
                   "sourceVersionId":{"type":["string","null"],"format":"uuid"},
                   "referenceVersionIds":{"type":"array","maxItems":30,"uniqueItems":true,
                     "items":{"type":"string","format":"uuid"}}}}}}}
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
            {"type":"object","additionalProperties":false,"required":["title","shots"],"properties":{"title":{"type":"string","minLength":1,"maxLength":160},"rationale":{"type":"string","maxLength":2000,"description":"面向用户的简短编排依据，不保存模型私有推理"},"originalAudioMode":{"type":"string","enum":["MUTE","BACKGROUND","KEEP"],"default":"MUTE"},"output":{"type":"object","additionalProperties":false,"properties":{"resolution":{"type":"string","enum":["720P","1080P","2160P"],"default":"720P"},"aspectRatio":{"type":"string","enum":["16:9","9:16","1:1"],"default":"16:9"}}},"music":{"type":"object","additionalProperties":false,"required":["audioVersionId"],"properties":{"audioVersionId":{"type":"string","format":"uuid"},"sourceStartMs":{"type":"integer","minimum":0,"maximum":599999,"default":0},"volume":{"type":"number","minimum":0,"maximum":1,"default":0.2}}},"subtitles":{"type":"array","maxItems":5000,"items":{"type":"object","additionalProperties":false,"required":["text","startMs","endMs"],"properties":{"text":{"type":"string","minLength":1,"maxLength":500},"startMs":{"type":"integer","minimum":0,"maximum":599999},"endMs":{"type":"integer","minimum":1,"maximum":600000}}}},"shots":{"type":"array","minItems":1,"maxItems":120,"items":{"type":"object","additionalProperties":false,"required":["videoVersionId","lines"],"properties":{"videoVersionId":{"type":"string","format":"uuid"},"sourceStartMs":{"type":"integer","minimum":0,"maximum":599999,"default":0},"durationMs":{"type":"integer","minimum":100,"maximum":600000,"description":"取源时长；省略时取素材剩余长度；成片时长按速度换算并受当前服务端成片预算约束"},"speed":{"type":"number","minimum":0.5,"maximum":2,"default":1},"fadeMs":{"type":"integer","minimum":0,"maximum":500,"default":0,"description":"每镜开头及结尾的淡黑时长，不重叠镜头"},"lines":{"type":"array","maxItems":600,"items":{"type":"object","additionalProperties":false,"required":["audioVersionId"],"properties":{"audioVersionId":{"type":"string","format":"uuid"},"startMs":{"type":"integer","minimum":0,"maximum":599999,"description":"可选镜内锚点；省略时依声音活动自动排布"}}}}}}}}}
            """;

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
