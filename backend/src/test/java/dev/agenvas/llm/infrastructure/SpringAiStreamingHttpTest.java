package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.RunToolPolicy;
import dev.agenvas.llm.application.ToolRegistry;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Uses the real Spring AI adapter with a synthetic local HTTP stream, never a paid Provider. */
class SpringAiStreamingHttpTest {
    private static final int CONFIG_VERSION = 7;
    private static final long TIMEOUT_SECONDS = 10;
    private static final String MODEL_ID = "synthetic-stream-model";

    @Test
    void completesReasoningStreamWithUsageOnTheFinalStopChunk() throws Exception {
        List<String> deltas = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                for (int index = 0; index < 35; index++) {
                    chunk(output, "{\"reasoning_content\":\"Synthetic private thought.\"}", null);
                }
                chunk(output, "{\"content\":\"Test complete\"}", null);
                event(output, "{\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1700000000,\"model\":\"" + MODEL_ID + "\","
                        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":10,\"cache_write_tokens\":0,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":10,\"text_tokens\":12,\"image_tokens\":0,\"video_tokens\":0,\"audio_tokens\":0},"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":2,\"audio_tokens\":0,\"text_tokens\":3,\"image_tokens\":0,\"video_tokens\":0,\"accepted_prediction_tokens\":0,\"rejected_prediction_tokens\":0}}}");
                event(output, "[DONE]");
            } finally { exchange.close(); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            SafeLlmTransport transport = new SafeLlmTransport(endpoint,
                    new dev.agenvas.settings.application.LlmEndpointPolicy(
                            new dev.agenvas.settings.application.LlmEndpointProperties(true)));
            OpenAiChatModel model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                    .baseUrl(endpoint)
                    .apiKey("synthetic-unusable-key").model(MODEL_ID).maxRetries(0)
                    .streamUsage(true).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build())
                    .httpClientBuilderCustomizer(builder -> builder.interceptor(transport.interceptor())).build();
            AtomicReference<dev.agenvas.audit.domain.LlmStreamLog> log = new AtomicReference<>();
            ChatGateway.Exchange result;
            try (var capture = dev.agenvas.shared.http.DebugHttpCapture.openLlm(ignored -> {})) {
                result = SpringAiChatGateway.withDebugCapture(model, CONFIG_VERSION).callStreaming(
                        List.of(new UserMessage("Report completion")), List.of(), Map.of(),
                        new ChatGateway.ConfigIdentity("spring-ai", CONFIG_VERSION), deltas::add, true, log::set);
            }
            assertThat(log.get().metrics().status()).isEqualTo(dev.agenvas.audit.domain.LlmStreamLog.EndStatus.COMPLETED);
            assertThat(String.join("", deltas)).isEqualTo("Test complete");
            var checkpoint = new LlmProtocolCodec(new ObjectMapper()).response(result.response());
            assertThat(checkpoint.toString()).doesNotContain("Synthetic private thought");
            assertThat(checkpoint.path("metadata").path("usage").path("completionTokens").asInt()).isEqualTo(3);
            assertThat(result.response().getResult().getOutput().getText()).isEqualTo("Test complete");
        } finally { server.stop(0); }
    }

    @Test
    void sendsImagePixelsWithReadableToolDefinitionsThroughTheRealAdapter() throws Exception {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4uoAAAAASUVORK5CYII=");
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                chunk(output, "{\"content\":\"Synthetic image received\"}", "stop");
                event(output, "[DONE]");
            } finally { exchange.close(); }
        });
        server.start();
        try {
            OpenAiChatModel model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .apiKey("synthetic-unusable-key").model(MODEL_ID).maxRetries(0)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()).build();
            var policy = mapper.createObjectNode().put("toolPolicyVersion", RunToolPolicy.CURRENT_VERSION);
            policy.set("allowedTools", mapper.valueToTree(RunToolPolicy.current(false, false)));
            UserMessage user = UserMessage.builder().text("Inspect the synthetic image")
                    .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(png))).build();
            var readCall = AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("read-image", "function", "read_artifacts", "{}"))).build();
            var readReply = ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse("read-image", "read_artifacts", "{\"status\":\"SUCCEEDED\"}"))).build();
            var result = new SpringAiChatGateway(model, CONFIG_VERSION).callStreaming(List.of(
                    new UserMessage("Synthetic image metadata"), readCall, readReply, user),
                    new ToolRegistry().modelDefinitions(policy), Map.of(),
                    new ChatGateway.ConfigIdentity("spring-ai", CONFIG_VERSION), ignored -> {});
            assertThat(result.response().getResult().getOutput().getText()).isEqualTo("Synthetic image received");
            assertThat(body.get().path("messages").get(2).path("role").asText()).isEqualTo("tool");
            assertThat(body.get().path("messages").get(2).path("tool_call_id").asText()).isEqualTo("read-image");
            JsonNode content = body.get().path("messages").get(3).path("content");
            assertThat(content.isArray()).isTrue();
            assertThat(content.get(0).path("text").asText()).isEqualTo("Inspect the synthetic image");
            assertThat(content.get(1).path("image_url").path("url").asText())
                    .isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            List<String> names = new java.util.ArrayList<>();
            body.get().path("tools").forEach(tool -> names.add(tool.path("function").path("name").asText()));
            assertThat(names).contains("read_project_summary", "read_selection", "read_artifacts")
                    .doesNotContain("read_skill_resource");
        } finally { server.stop(0); }
    }

    @Test
    void emitsPublicTextBeforeEndOfStreamAndPreservesToolsAndUsageWithoutExecutingTools() throws Exception {
        CountDownLatch publicTextReceived = new CountDownLatch(1);
        CountDownLatch finishAllowed = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        List<String> deltas = new CopyOnWriteArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            requestBody.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                chunk(output, "{\"content\":\"Preparing \"}", null);
                chunk(output, "{\"content\":\"a media proposal.\"}", null);
                if (!finishAllowed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return;
                chunk(output, "{\"tool_calls\":[{\"index\":0,\"id\":\"call-media-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"propose_media_generation\",\"arguments\":\"{\\\"kind\\\":\"}}]}", null);
                chunk(output, "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"IMAGE\\\"}\"}}]}", null);
                chunk(output, "{}", "tool_calls");
                event(output, "{\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1700000000,\"model\":\"" + MODEL_ID + "\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}");
                event(output, "[DONE]");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        ToolCallback tool = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("propose_media_generation")
                        .description("Propose a media generation batch")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"kind\":{\"type\":\"string\"}}}").build();
            }
            @Override public String call(String input) {
                executions.incrementAndGet();
                throw new AssertionError("The persistent runtime owns tool execution");
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            OpenAiChatModel model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .apiKey("synthetic-unusable-key").model(MODEL_ID).maxRetries(0)
                    .streamUsage(true).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()).build();
            SpringAiChatGateway gateway = new SpringAiChatGateway(model, CONFIG_VERSION);
            var future = executor.submit(() -> gateway.callStreaming(List.of(new UserMessage("Prepare an image")),
                    List.of(tool), Map.of(), new ChatGateway.ConfigIdentity("spring-ai", CONFIG_VERSION), delta -> {
                        deltas.add(delta);
                        publicTextReceived.countDown();
                    }));
            try {
                assertThat(publicTextReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                assertThat(future.isDone()).as("Text is delivered before the Provider completes").isFalse();
            } finally {
                finishAllowed.countDown();
            }
            var result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(requests).hasValue(1);
            assertThat(requestBody.get().path("stream").asBoolean()).isTrue();
            assertThat(requestBody.get().path("tools").get(0).path("function").path("name").asText())
                    .isEqualTo("propose_media_generation");
            assertThat(executions).hasValue(0);
            assertThat(String.join("", deltas)).isEqualTo("Preparing a media proposal.");
            var assistant = result.response().getResult().getOutput();
            assertThat(assistant.getText()).isEqualTo("Preparing a media proposal.");
            assertThat(assistant.getToolCalls()).hasSize(1);
            assertThat(assistant.getToolCalls().getFirst().id()).isEqualTo("call-media-1");
            assertThat(mapper.readTree(assistant.getToolCalls().getFirst().arguments()).path("kind").asText())
                    .isEqualTo("IMAGE");
            JsonNode checkpoint = new LlmProtocolCodec(mapper).response(result.response());
            assertThat(checkpoint.path("metadata").path("usage").path("promptTokens").asInt()).isEqualTo(12);
            assertThat(checkpoint.path("metadata").path("usage").path("completionTokens").asInt()).isEqualTo(3);
        } finally {
            finishAllowed.countDown();
            server.stop(0);
        }
    }

    private static void chunk(OutputStream output, String delta, String finish) throws java.io.IOException {
        event(output, "{\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"" + MODEL_ID + "\",\"choices\":[{\"index\":0,\"delta\":" + delta
                + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}");
    }

    private static void event(OutputStream output, String value) throws java.io.IOException {
        output.write(("data: " + value + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
