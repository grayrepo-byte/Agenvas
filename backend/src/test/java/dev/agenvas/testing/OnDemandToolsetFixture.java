package dev.agenvas.testing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Model-script helpers only: the worker must commit this loader before business tools appear. */
public final class OnDemandToolsetFixture {
    private OnDemandToolsetFixture() {}

    public static boolean onlyLoader(List<ToolCallback> tools) {
        return tools.stream().map(tool -> tool.getToolDefinition().name()).toList()
                .equals(List.of("load_toolset"));
    }

    public static ChatGateway.Exchange load(String... toolsets) {
        String groups = Arrays.stream(toolsets).map(group -> "\"" + group + "\"")
                .collect(Collectors.joining(","));
        var response = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("prepare-toolsets", "function", "load_toolset",
                        "{\"toolsets\":[" + groups + "]}"))).build();
        return new ChatGateway.Exchange(1, new ChatResponse(List.of(new Generation(response))));
    }

    public static void prepare(AgentTurnWorker worker, JdbcClient jdbc, UUID runId) {
        assertThat(worker.runOnce("prepare-toolsets-fixture")).isEqualTo(1);
        assertThat(jdbc.sql("select tool_name from tool_execution where run_id=:run")
                .param("run", runId).query(String.class).list()).containsExactly("load_toolset");
    }
}
