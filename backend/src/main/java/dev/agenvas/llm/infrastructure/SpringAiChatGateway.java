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

/** Calls Spring AI exactly once per business round without registering its tool-loop advisor. */
public class SpringAiChatGateway implements ChatGateway {

    private final ChatClient client;
    private final ChatModel model;
    private final int configVersion;

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

    @Override
    public Capabilities capabilities() {
        return new Capabilities(model.getOptions() instanceof ToolCallingChatOptions,
                false, false);
    }

    @Override
    public int configVersion() {
        return configVersion;
    }

    @Override
    public String configSource() {
        return "spring-ai";
    }

    @Override
    public ModelDetails modelDetails() {
        String modelId = model.getOptions() == null ? null : model.getOptions().getModel();
        return new ModelDetails(true, model.getClass().getSimpleName(), modelId,
                capabilities().toolCalling());
    }
}
