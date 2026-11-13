package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.DirectorSettings;
import dev.agenvas.llm.application.DirectorSettingsService;
import dev.agenvas.llm.application.LlmProperties;
import dev.agenvas.llm.application.LlmRequestBudget;
import dev.agenvas.settings.application.LlmModelPolicy;
import dev.agenvas.settings.application.LlmProviderConfig;
import dev.agenvas.settings.application.LlmProviderConfigRepository;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Environment-only installations use bounded calls without inventing provider capacity metadata. */
class ConfiguredChatGatewayBudgetTest {
    private static final ChatGateway.ConfigIdentity ENVIRONMENT = new ChatGateway.ConfigIdentity("environment", 7);

    @Test
    void availableEnvironmentModelUsesLocalDefaultsWithoutAStoredPolicy() {
        var gateway = gateway(new ProbeModel(), null);

        var budget = gateway.requestBudgetFor(ENVIRONMENT);

        assertThat(budget.contextWindowTokens()).isEqualTo(32768);
        assertThat(budget.responseReserveTokens()).isEqualTo(4096);
        assertThat(budget.inputBudget()).isEqualTo(28672);
        assertThat(budget.outputParameter()).isEqualTo(LlmModelPolicy.OutputParameter.MAX_TOKENS);
    }

    @Test
    void environmentBudgetUsesSavedDirectorSettingsAndGenericOutputCeiling() {
        var settings = mock(DirectorSettingsService.class);
        when(settings.get()).thenReturn(new DirectorSettings(65536, 50, 8192, 2048, 2, 60, 5, 30, 2, 12));
        var factory = new StoredChatModelFactory(null, null, null, settings, null);

        var budget = gateway(new ProbeModel(), factory).requestBudgetFor(ENVIRONMENT);

        assertThat(budget.contextWindowTokens()).isEqualTo(65536);
        assertThat(budget.responseReserveTokens()).isEqualTo(4096);
        assertThat(budget.summaryTokens()).isEqualTo(2048);
        assertThat(budget.directorVersion()).isEqualTo(12);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void frozenEnvironmentBudgetReachesGenericModelWithoutExecutingTools(boolean withFactory, boolean streaming) {
        var model = new ProbeModel();
        var gateway = gateway(model, withFactory ? new StoredChatModelFactory(null, null) : null);
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_project_summary").description("Read project summary")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override
            public String call(String input) {
                throw new AssertionError("Runtime owns tool execution");
            }
        };
        var input = List.<Message>of(new UserMessage("Inspect the project"));
        var tools = List.of(tool);
        var context = Map.<String, Object>of("projectId", "trusted-project");

        ChatGateway.Exchange result = streaming
                ? gateway.callStreaming(input, tools, context, ENVIRONMENT,
                        frozenBudget(LlmModelPolicy.OutputParameter.MAX_TOKENS),
                        ignored -> {
                        }, false, ignored -> {
                        })
                : gateway.call(input, tools, context, ENVIRONMENT,
                        frozenBudget(LlmModelPolicy.OutputParameter.MAX_TOKENS));

        assertThat(result.configVersion()).isEqualTo(7);
        assertThat(result.response().hasToolCalls()).isTrue();
        assertThat(model.prompts).hasSize(1);
        var options = (ToolCallingChatOptions) model.prompts.getFirst().getOptions();
        assertThat(options.getMaxTokens()).isEqualTo(512);
        assertThat(options.getModel()).isEqualTo("environment-model");
        assertThat(options.getTemperature()).isEqualTo(0.3);
        assertThat(options.getToolCallbacks()).extracting(value -> value.getToolDefinition().name())
                .containsExactly("read_project_summary");
        assertThat(options.getToolContext()).containsEntry("projectId", "trusted-project");
        assertThat(model.getOptions().getMaxTokens()).isEqualTo(9999);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void frozenEnvironmentOutputIsSentAsOneWireParameter(boolean streaming, boolean completionTokens) throws Exception {
        var received = new AtomicReference<JsonNode>();
        var mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            String reply = streaming
                    ? "data: {\"id\":\"synthetic\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"synthetic\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"synthetic\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}]}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var options = OpenAiChatOptions.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .apiKey("synthetic-unusable-key").model("synthetic").timeout(Duration.ofSeconds(5)).maxRetries(0);
            // The frozen request must replace the opposite parameter from deployment defaults.
            if (completionTokens)
                options.maxTokens(9999);
            else
                options.maxCompletionTokens(9999);
            var model = OpenAiChatModel.builder().options(options.build()).build();
            var gateway = gateway(model, new StoredChatModelFactory(null, null));
            var parameter = completionTokens
                    ? LlmModelPolicy.OutputParameter.MAX_COMPLETION_TOKENS
                    : LlmModelPolicy.OutputParameter.MAX_TOKENS;
            var input = List.<Message>of(new UserMessage("Small synthetic request"));

            var result = streaming
                    ? gateway.callStreaming(input, List.of(), Map.of(), ENVIRONMENT, frozenBudget(parameter),
                            ignored -> {
                            }, false, ignored -> {
                            })
                    : gateway.call(input, List.of(), Map.of(), ENVIRONMENT, frozenBudget(parameter));

            assertThat(result.response().getResult().getOutput().getText()).isEqualTo("done");
            String field = completionTokens ? "max_completion_tokens" : "max_tokens";
            String other = completionTokens ? "max_tokens" : "max_completion_tokens";
            assertThat(received.get().path(field).asInt()).isEqualTo(512);
            assertThat(received.get().has(other)).isFalse();
            assertThat(
                    completionTokens ? model.getOptions().getMaxTokens() : model.getOptions().getMaxCompletionTokens())
                    .isEqualTo(9999);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void absentEnvironmentModelDoesNotGainABudgetOrDispatch() {
        var gateway = gateway(null, new StoredChatModelFactory(null, null));
        var input = List.<Message>of(new UserMessage("hello"));
        var budget = frozenBudget(LlmModelPolicy.OutputParameter.MAX_TOKENS);

        assertThatThrownBy(() -> gateway.requestBudgetFor(ENVIRONMENT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No LLM ChatModel");
        assertThatThrownBy(() -> gateway.call(input, List.of(), Map.of(), ENVIRONMENT, budget))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No LLM ChatModel");
        assertThatThrownBy(() -> gateway.callStreaming(input, List.of(), Map.of(), ENVIRONMENT, budget,
                ignored -> {
                }, false, ignored -> {
                }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No LLM ChatModel");
    }

    @Test
    void storedConfigurationReplacingEnvironmentRejectsBudgetAndFrozenCalls() {
        var model = new ProbeModel();
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("environmentModel", model);
        var configs = mock(LlmProviderConfigRepository.class);
        when(configs.active()).thenReturn(Optional.of(new LlmProviderConfig(UUID.randomUUID(), 7,
                "https://private.invalid", "stored-model", new byte[0], new byte[0], 1, "masked", true, true,
                Instant.EPOCH)));
        var gateway = new ConfiguredChatGateway(beans.getBeanProvider(ChatModel.class), new LlmProperties(7, true),
                configs, new StoredChatModelFactory(null, null));
        var input = List.<Message>of(new UserMessage("hello"));
        var budget = frozenBudget(LlmModelPolicy.OutputParameter.MAX_TOKENS);

        assertThatThrownBy(() -> gateway.requestBudgetFor(ENVIRONMENT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration changed");
        assertThatThrownBy(() -> gateway.call(input, List.of(), Map.of(), ENVIRONMENT, budget))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("configuration changed");
        assertThatThrownBy(() -> gateway.callStreaming(input, List.of(), Map.of(), ENVIRONMENT, budget,
                ignored -> {
                }, false, ignored -> {
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration changed");
        assertThat(model.prompts).isEmpty();
    }

    private static ConfiguredChatGateway gateway(ChatModel model, StoredChatModelFactory factory) {
        var beans = new DefaultListableBeanFactory();
        if (model != null)
            beans.registerSingleton("environmentModel", model);
        return new ConfiguredChatGateway(beans.getBeanProvider(ChatModel.class), new LlmProperties(7, true), null,
                factory);
    }

    private static LlmRequestBudget frozenBudget(LlmModelPolicy.OutputParameter parameter) {
        return new LlmRequestBudget(1, 8192, 512, 7680, 256, 0, 4096, parameter, 3);
    }

    private static final class ProbeModel implements ChatModel {
        private final List<Prompt> prompts = new ArrayList<>();
        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model("environment-model").maxTokens(9999).temperature(0.3).build();
        }
        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(
                            List.of(new AssistantMessage.ToolCall("call-1", "function", "read_project_summary", "{}")))
                    .build())));
        }
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
