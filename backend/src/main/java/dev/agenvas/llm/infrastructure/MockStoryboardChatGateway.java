package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.ChatGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 通过与真实模型相同的持久化工具协议返回确定性演示回合，并明确标注结果并非 AI 生成。 */
public final class MockStoryboardChatGateway implements ChatGateway {

    /** 写入响应元数据的固定演示模型标识。 */
    private static final String MODEL_ID = "mock-storyboard-v1";
    /** 正常演示流程要求可用的工具白名单。 */
    private static final Set<String> REQUIRED_TOOLS = Set.of("create_text", "create_scene",
            "create_shots", "propose_generation_plan", "propose_export");
    /** 创建工具参数和解析已保存工具结果。 */
    private final ObjectMapper mapper;
    /** 固定本演示网关对应的配置版本。 */
    private final int configVersion;

    /** 初始化无外部模型依赖的确定性演示网关。 */
    public MockStoryboardChatGateway(ObjectMapper mapper, int configVersion) {
        this.mapper = mapper;
        this.configVersion = configVersion;
    }

    /** 仅根据已保存的对话和工具结果决定下一步，进程重启后流程仍可继续。 */
    @Override
    public Exchange call(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext) {
        RedoScope redo = messages == null ? null : redoScope(messages);
        Set<String> requiredTools = redo == null ? REQUIRED_TOOLS
                : Set.of("propose_generation_plan");
        if (messages == null || messages.isEmpty() || tools == null || toolContext == null
                || !tools.stream().map(tool -> tool.getToolDefinition().name()).toList()
                        .containsAll(requiredTools)) {
            throw new IllegalArgumentException("Mock storyboard lacks the approved tool protocol");
        }
        List<ToolResponseMessage> replies = messages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).toList();
        AssistantMessage assistant = redo == null ? switch (replies.size()) {
            case 0 -> briefAndScene(messages);
            case 1 -> shots(replies.getFirst());
            case 2 -> imagePlan(replies.getLast());
            case 3 -> afterImageDecision(messages, 3);
            case 4 -> afterVideoDecision(messages, 3);
            case 5 -> finishExportProposal(replies.getLast());
            default -> throw new IllegalStateException("Mock storyboard exceeded known turns");
        } : switch (replies.size()) {
            case 0 -> redoImagePlan(redo);
            case 1 -> afterImageDecision(messages, 1);
            case 2 -> finishVideo(messages, 1);
            default -> throw new IllegalStateException("Mock redo exceeded known turns");
        };
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id("mock-turn-" + replies.size()).model(MODEL_ID).build();
        return new Exchange(configVersion,
                new ChatResponse(List.of(new Generation(assistant)), metadata));
    }

    /** 演示网关支持工具调用，但不支持视觉输入或 Provider 流式输出。 */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false, false);
    }

    /** 返回创建网关时固定的演示配置版本。 */
    @Override
    public int configVersion() {
        return configVersion;
    }

    /** 标记响应来源为 Mock，避免与真实模型用量混淆。 */
    @Override
    public String configSource() {
        return "mock";
    }

    /** 返回明确标注为非 AI 的模型展示信息。 */
    @Override
    public ModelDetails modelDetails() {
        return new ModelDetails(true, "演示模型（非 AI）", MODEL_ID, true);
    }

    /** 不声称理解图片像素或生成原创文字；正文明确展示用户指令和演示标签。 */
    private AssistantMessage briefAndScene(List<Message> messages) {
        String request = messages.stream().map(Message::getText)
                .filter(value -> value != null && value.startsWith("Current Run request:\n"))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Mock storyboard lacks the Run instruction"));
        ObjectNode text = mapper.createObjectNode();
        text.put("title", "演示创作说明");
        text.put("format", "PLAIN_TEXT");
        String preview = request.length() > 4_000
                ? request.substring(0, 4_000) + "\n（仅展示前 4000 字符；完整指令保留在 Run 中）"
                : request;
        text.put("text", "演示素材，非真实模型生成。\n" + preview);
        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "演示场景");
        scene.put("location", "简约咖啡空间");
        scene.put("timeOfDay", "白天");
        scene.put("lighting", "柔和窗光");
        scene.put("style", "现代极简；演示素材，非真实模型生成");
        scene.putArray("referenceVersionIds");
        return calls(List.of(tool("mock-brief-0", "create_text", text),
                tool("mock-scene-0", "create_scene", scene)));
    }

    /** 从已提交的工具结果读取场景的准确版本 ID，不自行猜测资源标识。 */
    private AssistantMessage shots(ToolResponseMessage reply) {
        JsonNode scene = result(reply, "create_scene");
        String sceneId = requiredId(scene.path("createdIds").path(0));
        String sceneVersionId = requiredId(scene.path("affectedVersions").path(sceneId));
        ObjectNode arguments = mapper.createObjectNode();
        ArrayNode shots = arguments.putArray("shots");
        for (int index = 1; index <= 3; index++) {
            ObjectNode shot = shots.addObject();
            shot.put("title", "演示镜头 " + index);
            shot.put("order", index);
            shot.put("durationSeconds", 5);
            shot.put("description", "演示镜头 " + index + "：咖啡广告分镜占位内容");
            shot.put("camera", index == 1 ? "广角" : index == 2 ? "中景" : "特写");
            shot.put("action", "展示咖啡产品；非真实模型构思");
            shot.putArray("characterVersionIds");
            shot.put("sceneVersionId", sceneVersionId);
        }
        return calls(List.of(tool("mock-shots-1", "create_shots", arguments)));
    }

    /** 图片 DAG 只生成待审提案，只有已鉴权用户能批准并触发任务。 */
    private AssistantMessage imagePlan(ToolResponseMessage reply) {
        JsonNode result = result(reply, "create_shots");
        JsonNode created = result.path("createdIds");
        if (!created.isArray() || created.size() != 3) {
            throw new IllegalStateException("Mock storyboard did not create exactly three shots");
        }
        ObjectNode plan = mapper.createObjectNode();
        plan.put("stage", "IMAGE");
        plan.put("objective", "为三个演示镜头生成占位关键帧；非真实模型输出");
        ArrayNode steps = plan.putArray("steps");
        for (int index = 0; index < 3; index++) {
            String shotId = requiredId(created.get(index));
            String versionId = requiredId(result.path("affectedVersions").path(shotId));
            ObjectNode step = steps.addObject();
            step.put("stepKey", "mock-image-" + (index + 1));
            step.put("outputSlotKey", "mock-shot-" + (index + 1) + "-keyframe");
            step.put("shotArtifactId", shotId);
            step.put("shotVersionId", versionId);
            step.put("prompt", "演示素材：咖啡广告镜头 " + (index + 1));
            step.putArray("dependsOnStepKeys");
        }
        return calls(List.of(tool("mock-image-plan-2", "propose_generation_plan", plan)));
    }

    /** 局部重做仅为指定镜头提出一个关键帧，不重新创建其他镜头。 */
    private AssistantMessage redoImagePlan(RedoScope scope) {
        ObjectNode plan = mapper.createObjectNode();
        plan.put("stage", "IMAGE");
        plan.put("objective", "只重做用户明确选定镜头的演示关键帧；非真实模型输出");
        ObjectNode step = plan.putArray("steps").addObject();
        step.put("stepKey", "mock-redo-image-1");
        step.put("outputSlotKey", "mock-redo-keyframe-1");
        step.put("shotArtifactId", scope.artifactId());
        step.put("shotVersionId", scope.versionId());
        step.put("prompt", "局部重做当前镜头；演示素材，非 AI 生成");
        step.putArray("dependsOnStepKeys");
        return calls(List.of(tool("mock-redo-image-plan-0", "propose_generation_plan", plan)));
    }

    /** 仅把用户选定的精确素材版本作为视频计划输入。 */
    private AssistantMessage afterImageDecision(List<Message> messages, int expectedShots) {
        String decision = messages.getLast().getText();
        if (decision == null || !decision.startsWith("User decision for plan ")) {
            throw new IllegalStateException("Mock storyboard lacks the saved user decision");
        }
        if (!decision.contains("APPROVED by the authenticated user")) {
            return AssistantMessage.builder().content("用户拒绝了演示图片计划；未提交媒体生成。")
                    .build();
        }
        ObjectNode plan = mapper.createObjectNode();
        plan.put("stage", "VIDEO");
        plan.put("objective", "从用户选定的三张演示关键帧制作无声占位视频；非真实模型输出");
        ArrayNode steps = plan.putArray("steps");
        int index = 0;
        for (String line : decision.split("\\n")) {
            if (!line.startsWith("shotArtifactId=")) {
                continue;
            }
            Map<String, String> fields = new java.util.HashMap<>();
            for (String assignment : line.split(" ")) {
                String[] pair = assignment.split("=", 2);
                if (pair.length == 2) fields.put(pair[0], pair[1]);
            }
            ObjectNode step = steps.addObject();
            step.put("stepKey", "mock-video-" + (++index));
            step.put("outputSlotKey", "mock-shot-" + index + "-video");
            for (String field : List.of("shotArtifactId", "shotVersionId",
                    "imageArtifactId", "imageVersionId")) {
                step.put(field, requiredId(mapper.valueToTree(fields.get(field))));
            }
            step.put("prompt", "演示视频：咖啡广告镜头 " + index);
            step.putArray("dependsOnStepKeys");
        }
        if (index != expectedShots) {
            throw new IllegalStateException("Mock storyboard lacks the expected keyframe choices");
        }
        return calls(List.of(tool("mock-video-plan-3", "propose_generation_plan", plan)));
    }

    /** 只有已归档的视频结果写入后续对话，才报告该演示阶段结束。 */
    private AssistantMessage finishVideo(List<Message> messages, int expectedShots) {
        String decision = messages.getLast().getText();
        if (decision == null || !decision.startsWith("User decision for plan ")) {
            throw new IllegalStateException("Mock storyboard lacks the video decision");
        }
        String summary = decision.contains("APPROVED by the authenticated user")
                ? expectedShots + " 个演示视频已归档；由选定演示图片生成，并非 AI 图生视频。"
                : "用户拒绝了演示视频计划；未提交视频生成。";
        return AssistantMessage.builder().content(summary).build();
    }

    /** 演示媒体结果只转成可审阅的导出提案，不直接创建 FFmpeg 任务。 */
    private AssistantMessage afterVideoDecision(List<Message> messages, int expectedShots) {
        String decision = messages.getLast().getText();
        if (decision == null || !decision.startsWith("User decision for plan ")) {
            throw new IllegalStateException("Mock storyboard lacks the video decision");
        }
        if (!decision.contains("APPROVED by the authenticated user")) {
            return finishVideo(messages, expectedShots);
        }
        String aspectRatio = messages.stream().map(Message::getText)
                .filter(value -> value != null && value.startsWith("Project: "))
                .findFirst().map(value -> {
                    int start = value.lastIndexOf('(');
                    int end = value.lastIndexOf(')');
                    return start >= 0 && end > start ? value.substring(start + 1, end) : "";
                }).orElse("");
        if (!Set.of("LANDSCAPE_16_9", "PORTRAIT_9_16", "SQUARE_1_1")
                .contains(aspectRatio)) {
            throw new IllegalStateException("Mock storyboard lacks the project aspect ratio");
        }
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("aspectRatio", aspectRatio);
        ArrayNode segments = proposal.putArray("segments");
        for (String line : decision.split("\\n")) {
            if (!line.startsWith("shotArtifactId=")) continue;
            Map<String, String> fields = new java.util.HashMap<>();
            for (String assignment : line.split(" ")) {
                String[] pair = assignment.split("=", 2);
                if (pair.length == 2) fields.put(pair[0], pair[1]);
            }
            ObjectNode segment = segments.addObject();
            for (String field : List.of("shotArtifactId", "shotVersionId",
                    "videoArtifactId", "videoVersionId")) {
                segment.put(field, requiredId(mapper.valueToTree(fields.get(field))));
            }
            segment.put("startMs", 0);
            segment.put("endMs", 1_000);
        }
        if (segments.size() != expectedShots) {
            throw new IllegalStateException("Mock storyboard lacks the expected video results");
        }
        return calls(List.of(tool("mock-export-proposal-4", "propose_export", proposal)));
    }

    /** 最终回复引用已保存的提案，不声称导出已经完成。 */
    private AssistantMessage finishExportProposal(ToolResponseMessage reply) {
        JsonNode proposal = result(reply, "propose_export");
        String proposalId = requiredId(proposal.path("createdIds").path(0));
        return AssistantMessage.builder().content("已提出无声顺序导出提案 " + proposalId
                + "；需用户审批后才会开始本地 FFmpeg 导出。演示素材非真实模型生成。")
                .build();
    }

    /** 从服务端写入的首轮上下文提取局部重做范围。 */
    private RedoScope redoScope(List<Message> messages) {
        for (Message message : messages) {
            String value = message.getText();
            if (value == null || !value.startsWith("Scoped redo shot: artifactId=")) continue;
            String[] fields = value.substring("Scoped redo shot: ".length()).split("\\s+", 3);
            if (fields.length < 2 || !fields[0].startsWith("artifactId=")
                    || !fields[1].startsWith("versionId=")) {
                throw new IllegalStateException("Mock redo scope is malformed");
            }
            String artifactId = requiredId(mapper.valueToTree(fields[0].substring(11)));
            String versionId = requiredId(mapper.valueToTree(fields[1].substring(10)));
            return new RedoScope(artifactId, versionId);
        }
        return null;
    }

    /** 已由服务端上下文限定的局部重做目标。
     * @param artifactId 需要重做关键帧的镜头产物 ID
     * @param versionId 本次 Run 固定的镜头内容版本 ID
     */
    private record RedoScope(String artifactId, String versionId) {}

    /** 从指定工具响应中取得已提交成功的 JSON 结果。 */
    private JsonNode result(ToolResponseMessage reply, String name) {
        for (ToolResponseMessage.ToolResponse response : reply.getResponses()) {
            if (name.equals(response.name())) {
                JsonNode value = mapper.readTree(response.responseData());
                if ("SUCCEEDED".equals(value.path("status").asText())) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("Mock storyboard lacks committed result for " + name);
    }

    /** 将工具结果中的文本 ID 规范化为 UUID；拒绝演示链路中的无效标识。 */
    private String requiredId(JsonNode value) {
        String id = value.asText("");
        try {
            return java.util.UUID.fromString(id).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Mock storyboard result contains an invalid ID", exception);
        }
    }

    /** 按 Spring AI 结构构造演示工具调用，不在网关内执行工具。 */
    private AssistantMessage.ToolCall tool(String id, String name, ObjectNode arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments.toString());
    }

    /** 构造仅包含工具调用的助手消息，交由持久化回合执行器处理。 */
    private AssistantMessage calls(List<AssistantMessage.ToolCall> toolCalls) {
        return AssistantMessage.builder().content("").toolCalls(new ArrayList<>(toolCalls)).build();
    }
}
