package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.ChatGateway;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;

/**
 * Mock 模式下不依赖外部模型的文本回复网关：只回显用户指令并明确标注内容并非真实模型生成。
 *
 * <p>它接受工具定义（Agent 回合因此可以正常开始与结束），但从不发出工具调用，所以不会产生任何
 * 业务副作用。需要真实工具执行的验证必须使用配置模型或测试内的假网关，不能依赖本类。
 */
public final class MockChatGateway implements ChatGateway {

    /** 写入响应元数据的固定 Mock 模型标识。 */
    private static final String MODEL_ID = "mock-chat-v1";
    /** 响应元数据中的固定调用标识。 */
    private static final String RESPONSE_ID = "mock-chat";
    /** 单次回复的字符上限，与文本产物正文上限保持一致。 */
    private static final int MAX_REPLY_CHARS = 20_000;
    /** 明确标注内容来源的固定前缀。 */
    private static final String REPLY_PREFIX = "演示文字，非真实模型生成。\n";

    /** 固定本 Mock 网关对应的配置版本。 */
    private final int configVersion;

    /** 初始化无外部模型依赖的确定性 Mock 网关。 */
    public MockChatGateway(int configVersion) {
        this.configVersion = configVersion;
    }

    /** 回显最后一条消息为公开回复；携带工具定义时同样只回文本，不伪造工具调用。 */
    @Override
    public Exchange call(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Mock gateway requires at least one message");
        }
        String instruction = messages.getLast().getText();
        String reply = REPLY_PREFIX + (instruction == null ? "" : instruction);
        AssistantMessage assistant = new AssistantMessage(
                reply.substring(0, Math.min(reply.length(), MAX_REPLY_CHARS)));
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id(RESPONSE_ID).model(MODEL_ID).build();
        return new Exchange(configVersion,
                new ChatResponse(List.of(new Generation(assistant)), metadata));
    }

    /** Mock 网关接受工具定义，但不支持视觉输入或 Provider 流式输出。 */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false, false);
    }

    /** 返回创建网关时固定的 Mock 配置版本。 */
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
}
