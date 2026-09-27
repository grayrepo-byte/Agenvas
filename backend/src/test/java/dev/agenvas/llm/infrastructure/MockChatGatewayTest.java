package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.agenvas.llm.application.ChatGateway;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

/**
 * Mock 网关必须在没有外部模型时也能让 Agent 回合正常开始与结束：它接受工具定义、只回文本，
 * 并且不伪造任何工具调用或业务副作用。
 */
class MockChatGatewayTest {

    private static final int CONFIG_VERSION = 7;

    private final MockChatGateway gateway = new MockChatGateway(CONFIG_VERSION);

    @Test
    void answersToolLessRoundsWithPlainText() {
        ChatGateway.Exchange exchange =
                gateway.call(List.of(new UserMessage("写一句标语")), List.of(), Map.of());

        assertThat(exchange.configVersion()).isEqualTo(CONFIG_VERSION);
        assertThat(exchange.response().getResult().getOutput().getText())
                .contains("演示文字，非真实模型生成。")
                .contains("写一句标语");
        assertThat(exchange.response().getResult().getOutput().getToolCalls()).isEmpty();
    }

    @Test
    void acceptsToolDefinitionsSoAgentRunsAreNotRefused() {
        ChatGateway.Exchange exchange = gateway.call(List.of(new UserMessage("整理画布")),
                List.of(mock(ToolCallback.class)), Map.of("projectId", "p", "runId", "r"));

        assertThat(exchange.response().getResult().getOutput().getText()).contains("整理画布");
        assertThat(exchange.response().getResult().getOutput().getToolCalls()).isEmpty();
        assertThat(gateway.capabilities().toolCalling()).isTrue();
        assertThat(gateway.capabilities().vision()).isFalse();
        assertThat(gateway.capabilities().nativeStructuredOutput()).isFalse();
        assertThat(gateway.modelDetails().toolCalling()).isTrue();
        assertThat(gateway.modelDetails().available()).isTrue();
    }

    @Test
    void reportsItsOwnMockIdentity() {
        assertThat(gateway.configVersion()).isEqualTo(CONFIG_VERSION);
        assertThat(gateway.configSource()).isEqualTo("mock");
        assertThat(gateway.modelDetails().modelId()).isEqualTo("mock-chat-v1");
    }

    @Test
    void rejectsAnEmptyConversation() {
        assertThatThrownBy(() -> gateway.call(List.<Message>of(), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
