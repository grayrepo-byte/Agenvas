package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.settings.application.LlmEndpointProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/** Real Spring AI SDK with synthetic local SSE; the bound applies before its tool buffer completes. */
class SafeLlmStreamingLimitHttpTest {
    private static final long TIMEOUT_SECONDS = 20;
    private static final int FRAGMENT_SIZE = 1024;
    // Keep the synthetic response unfinished when the client reaches its bound, even on a fast loopback socket.
    private static final int MAX_TEST_FRAGMENTS = (int) (8 * SafeLlmTransport.MAX_STREAM_RESPONSE_BYTES / FRAGMENT_SIZE);

    @Test
    void acceptsFiveMiBPublicResponseThroughTransportAggregationAndCheckpoint() throws Exception {
        String fragment = "x".repeat(5 * 1024 * 1024 / 2);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                event(output, "{\"content\":\"" + fragment + "\"}");
                event(output, "{\"content\":\"" + fragment + "\"}");
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } finally { exchange.close(); }
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        SafeLlmTransport transport = new SafeLlmTransport(endpoint,
                new LlmEndpointPolicy(new LlmEndpointProperties(true)));
        OpenAiChatModel model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .baseUrl(endpoint).apiKey("synthetic-unusable-key").model("synthetic-limit-model")
                .maxRetries(0).streamUsage(false).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build())
                .httpClientBuilderCustomizer(builder -> builder.interceptor(transport.interceptor())).build();
        StringBuilder deltas = new StringBuilder();
        SpringAiChatGateway gateway = new SpringAiChatGateway(model, 3);
        try {
            var exchange = gateway.callStreaming(List.of(new UserMessage("Synthetic long response")),
                    List.of(), Map.of(), gateway.configIdentity(), deltas::append);
            String expected = fragment + fragment;
            assertThat(deltas.toString()).isEqualTo(expected);
            LlmProtocolCodec codec = new LlmProtocolCodec(new ObjectMapper());
            assertThat(codec.selectedAssistant(codec.response(exchange.response())).getText()).isEqualTo(expected);
            assertThat(requests).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test
    void omittedProviderUsageRemainsUnknownInTheCheckpointInsteadOfFrameworkZero() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                event(output, "{\"content\":\"Public response without reported usage.\"}");
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                exchange.close();
            }
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        SafeLlmTransport transport = new SafeLlmTransport(endpoint, new LlmEndpointPolicy(new LlmEndpointProperties(true)));
        OpenAiChatModel model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .baseUrl(endpoint).apiKey("synthetic-unusable-key").model("synthetic-limit-model")
                .maxRetries(0).streamUsage(false).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build())
                .httpClientBuilderCustomizer(builder -> builder.interceptor(transport.interceptor())).build();
        SpringAiChatGateway gateway = new SpringAiChatGateway(model, 3);
        try {
            var exchange = gateway.callStreaming(List.of(new UserMessage("Hello")), List.of(), Map.of(), gateway.configIdentity(), ignored -> {});
            assertThat(exchange.response().getResult().getOutput().getText()).isEqualTo("Public response without reported usage.");
            assertThat(exchange.response().getMetadata().getUsage()).isInstanceOf(EmptyUsage.class);
            var checkpoint = new LlmProtocolCodec(new ObjectMapper()).response(exchange.response());
            assertThat(checkpoint.path("metadata").path("usage").isNull()).isTrue();
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedUnfinishedToolStreamFailsClosesAndNeverRetriesOrExecutesTools() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger fragments = new AtomicInteger();
        CountDownLatch disconnected = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            // Request Accept alone must protect streaming even if a Provider mislabels the body.
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                event(output, "{\"content\":\"Published public text.\"}");
                event(output, "{\"tool_calls\":[{\"index\":0,\"id\":\"call-bound-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"read_project_summary\",\"arguments\":\"{\\\"prompt\\\":\\\"\"}}]}");
                String continuation = "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"" + "x".repeat(FRAGMENT_SIZE) + "\"}}]}";
                for (int index = 0; index < MAX_TEST_FRAGMENTS; index++) {
                    event(output, continuation);
                    fragments.incrementAndGet();
                }
            } catch (IOException closed) {
                disconnected.countDown();
            } finally {
                exchange.close();
            }
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        SafeLlmTransport transport = new SafeLlmTransport(endpoint, new LlmEndpointPolicy(new LlmEndpointProperties(true)));
        OpenAiChatModel model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .baseUrl(endpoint).apiKey("synthetic-unusable-key").model("synthetic-limit-model")
                .maxRetries(0).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build())
                .httpClientBuilderCustomizer(builder -> builder.interceptor(transport.interceptor())).build();
        ToolCallback tool = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_project_summary").description("Read project")
                        .inputSchema("{\"type\":\"object\"}").build();
            }
            @Override public String call(String input) { executions.incrementAndGet(); return "{}"; }
        };
        SpringAiChatGateway gateway = new SpringAiChatGateway(model, 3);
        List<String> deltas = new ArrayList<>();
        try {
            Throwable failure = catchThrowable(() -> gateway.callStreaming(List.of(new UserMessage("Hello")),
                    List.of(tool), Map.of(), gateway.configIdentity(), deltas::add));
            assertThat(failure).isNotNull();
            boolean limited = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof IOException && cause.getMessage().contains("transport size limit")) limited = true;
            }
            assertThat(limited).as("Raw source bound, before tool calls have been merged").isTrue();
            assertThat(requests).hasValue(1);
            assertThat(executions).hasValue(0);
            assertThat(disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(fragments.get()).isLessThan(MAX_TEST_FRAGMENTS);
            assertThat(String.join("", deltas)).doesNotContain("prompt", "call-bound-1");
        } finally {
            server.stop(0);
        }
    }

    private static void event(OutputStream output, String delta) throws IOException {
        String body = "data: {\"id\":\"response-bound\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"synthetic-limit-model\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}\n\n";
        output.write(body.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
