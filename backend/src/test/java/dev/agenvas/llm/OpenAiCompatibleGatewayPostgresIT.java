package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProtocolCodec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real Spring AI OpenAI adapter against a fake HTTP endpoint and real PostgreSQL context. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=compatible-llm-integration-secret",
        "agenvas.llm.mode=configured",
        "agenvas.llm.tool-calling-verified=true",
        "agenvas.llm.scheduler-enabled=false",
        "spring.ai.model.chat=openai",
        "spring.ai.openai.api-key=local-test-key",
        "spring.ai.openai.chat.model=test-tool-model"})
class OpenAiCompatibleGatewayPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<JsonNode> FIRST_REQUEST = new AtomicReference<>();
    private static final AtomicReference<JsonNode> SECOND_REQUEST = new AtomicReference<>();
    private static final HttpServer SERVER = fakeModel();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.ai.openai.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired private ChatGateway gateway;
    @Autowired private ChatModel model;
    @Autowired private LlmProtocolCodec codec;

    @Test
    void actualAdapterPreservesToolIdAcrossCallerDrivenRounds() {
        assertThat(model.getClass().getSimpleName()).isEqualTo("OpenAiChatModel");
        assertThat(gateway.modelDetails().available()).isTrue();
        assertThat(gateway.modelDetails().modelId()).isEqualTo("test-tool-model");
        AtomicInteger executed = new AtomicInteger();
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_project_summary")
                        .description("Read one project summary")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                executed.incrementAndGet();
                return "{\"title\":\"Studio\"}";
            }
        };
        List<Message> initial = List.of(new UserMessage("Summarize the project"));
        ChatGateway.Exchange first = gateway.call(initial, List.of(tool), Map.of());
        assertThat(executed).hasValue(0);
        assertThat(CALLS).hasValue(1);
        JsonNode savedResponse = codec.response(first.response());
        assertThat(savedResponse.path("metadata").path("id").asText())
                .isEqualTo("chatcmpl-local-1");
        assertThat(savedResponse.path("metadata").path("usage")
                .path("promptTokens").asInt()).isEqualTo(12);
        assertThat(savedResponse.path("metadata").path("usage")
                .path("completionTokens").asInt()).isEqualTo(3);
        AssistantMessage assistant = codec.selectedAssistant(savedResponse);
        assertThat(assistant.getToolCalls()).hasSize(1);
        assertThat(assistant.getToolCalls().getFirst().id()).isEqualTo("call_project_1");
        assertThat(FIRST_REQUEST.get().path("model").asText()).isEqualTo("test-tool-model");
        assertThat(FIRST_REQUEST.get().path("tools").get(0).path("function")
                .path("name").asText()).isEqualTo("read_project_summary");

        ToolResponseMessage reply = codec.toolResults(assistant,
                Map.of("call_project_1", new ObjectMapper().readTree(tool.call("{}"))));
        ChatGateway.Exchange second = gateway.call(List.of(initial.getFirst(), assistant, reply),
                List.of(tool), Map.of());
        assertThat(CALLS).hasValue(2);
        assertThat(executed).hasValue(1);
        assertThat(SECOND_REQUEST.get().path("messages").get(1).path("tool_calls")
                .get(0).path("id").asText()).isEqualTo("call_project_1");
        assertThat(SECOND_REQUEST.get().path("messages").get(2)
                .path("tool_call_id").asText()).isEqualTo("call_project_1");
        assertThat(second.response().getResult().getOutput().getText())
                .isEqualTo("Project summary received.");
    }

    /** The fake service deliberately never runs application tools. */
    private static HttpServer fakeModel() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                JsonNode request = new ObjectMapper().readTree(
                        exchange.getRequestBody().readAllBytes());
                int call = CALLS.incrementAndGet();
                if (call == 1) FIRST_REQUEST.set(request);
                else SECOND_REQUEST.set(request);
                String message = call == 1
                        ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_project_1\",\"type\":\"function\",\"function\":{\"name\":\"read_project_summary\",\"arguments\":\"{}\"}}]}"
                        : "{\"role\":\"assistant\",\"content\":\"Project summary received.\"}";
                String finish = call == 1 ? "tool_calls" : "stop";
                String response = "{\"id\":\"chatcmpl-local-" + call
                        + "\",\"object\":\"chat.completion\",\"created\":1700000000,"
                        + "\"model\":\"test-tool-model\",\"choices\":[{\"index\":0,"
                        + "\"message\":" + message + ",\"finish_reason\":\"" + finish
                        + "\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,"
                        + "\"total_tokens\":15}}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot start fake chat completion endpoint", failure);
        }
    }
}
