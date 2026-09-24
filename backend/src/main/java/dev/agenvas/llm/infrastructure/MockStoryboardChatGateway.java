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

/** Deterministic, openly labeled demo turns through the same durable model/tool protocol. */
public final class MockStoryboardChatGateway implements ChatGateway {

    private static final String MODEL_ID = "mock-storyboard-v1";
    private static final Set<String> REQUIRED_TOOLS = Set.of("create_text", "create_scene",
            "create_shots", "propose_generation_plan", "propose_export");
    private final ObjectMapper mapper;
    private final int configVersion;

    public MockStoryboardChatGateway(ObjectMapper mapper, int configVersion) {
        this.mapper = mapper;
        this.configVersion = configVersion;
    }

    /** Derives its next turn only from saved messages, so process restarts do not reset it. */
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

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false, false);
    }

    @Override
    public int configVersion() {
        return configVersion;
    }

    @Override
    public String configSource() {
        return "mock";
    }

    @Override
    public ModelDetails modelDetails() {
        return new ModelDetails(true, "演示模型（非 AI）", MODEL_ID, true);
    }

    /** The demo never claims to understand bound image pixels or synthesize prose. */
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

    /** Exact Scene version comes from the durable tool result, never a guessed UUID. */
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
            shot.put("durationMs", 5_000);
            shot.put("description", "演示镜头 " + index + "：咖啡广告分镜占位内容");
            shot.put("camera", index == 1 ? "广角" : index == 2 ? "中景" : "特写");
            shot.put("action", "展示咖啡产品；非真实模型构思");
            shot.putArray("characterVersionIds");
            shot.put("sceneVersionId", sceneVersionId);
        }
        return calls(List.of(tool("mock-shots-1", "create_shots", arguments)));
    }

    /** The image DAG remains a proposal: only the authenticated user may approve it. */
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

    /** A scoped manual edit needs one new keyframe, never three new unrelated shots. */
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

    /** Human-selected exact versions become the only allowed video-plan inputs. */
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

    /** A video approval completes only after the archived media results are in the prompt. */
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

    /** Mock media results become a reviewable export proposal, never an immediate FFmpeg task. */
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

    /** The final message cites the persisted proposal rather than claiming export completed. */
    private AssistantMessage finishExportProposal(ToolResponseMessage reply) {
        JsonNode proposal = result(reply, "propose_export");
        String proposalId = requiredId(proposal.path("createdIds").path(0));
        return AssistantMessage.builder().content("已提出无声顺序导出提案 " + proposalId
                + "；需用户审批后才会开始本地 FFmpeg 导出。演示素材非真实模型生成。")
                .build();
    }

    /** The server-supplied scope message is part of the saved first-turn snapshot. */
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

    private record RedoScope(String artifactId, String versionId) {}

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

    private String requiredId(JsonNode value) {
        String id = value.asText("");
        try {
            return java.util.UUID.fromString(id).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Mock storyboard result contains an invalid ID", exception);
        }
    }

    private AssistantMessage.ToolCall tool(String id, String name, ObjectNode arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments.toString());
    }

    private AssistantMessage calls(List<AssistantMessage.ToolCall> toolCalls) {
        return AssistantMessage.builder().content("").toolCalls(new ArrayList<>(toolCalls)).build();
    }
}
