package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.ChatGateway;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/** 每个业务回合只调用一次 Spring AI，并关闭自动工具循环，由持久化 Runtime 管理工具执行。 */
public class SpringAiChatGateway implements ChatGateway {

    /** 不注册自动工具执行顾问的聊天客户端。 */
    private final ChatClient client;
    /** 提供模型能力选项和 Spring AI 请求执行。 */
    private final ChatModel model;
    /** 本次请求固定使用的 LLM 配置版本。 */
    private final int configVersion;

    /** 创建禁用自动工具循环的客户端，并拒绝无效配置版本。 */
    public SpringAiChatGateway(ChatModel model, int configVersion) {
        if (configVersion < 1) {
            throw new IllegalArgumentException("LLM configVersion must be positive");
        }
        this.model = model;
        this.client = ChatClient.builder(model)
                .defaultAdvisors(advisors -> advisors.param(
                        ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false))
                .build();
        this.configVersion = configVersion;
    }

    /** 提交一个有界消息回合并返回模型原始响应，不在此处执行工具调用。 */
    @Override
    public Exchange call(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext) {
        if (messages == null || messages.isEmpty() || messages.size() > 80
                || tools == null || toolContext == null) {
            throw new IllegalArgumentException("Invalid bounded ChatGateway round");
        }
        if (!tools.isEmpty() && !(model.getOptions() instanceof ToolCallingChatOptions)) {
            throw new IllegalStateException("Configured ChatModel does not support tool calling");
        }
        ChatResponse response = client.prompt()
                .messages(List.copyOf(messages))
                .tools(tools.toArray(ToolCallback[]::new))
                .toolContext(Map.copyOf(toolContext))
                .call()
                .chatResponse();
        if (response == null || response.getResult() == null) {
            throw new IllegalStateException("LLM returned no assistant response");
        }
        return new Exchange(configVersion, response);
    }

    /** 根据 ChatModel 选项报告工具调用能力，视觉和流式能力保持未验证。 */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(model.getOptions() instanceof ToolCallingChatOptions,
                false, false);
    }

    /** 返回创建网关时固定的配置版本，供 LlmTurn 持久化来源身份。 */
    @Override
    public int configVersion() {
        return configVersion;
    }

    /** 标识响应来自 Spring AI 配置 Provider。 */
    @Override
    public String configSource() {
        return "spring-ai";
    }

    /** 提供管理员诊断使用的模型类名和可用模型 ID，不返回客户端凭证。 */
    @Override
    public ModelDetails modelDetails() {
        String modelId = model.getOptions() == null ? null : model.getOptions().getModel();
        return new ModelDetails(true, model.getClass().getSimpleName(), modelId,
                capabilities().toolCalling());
    }
}
