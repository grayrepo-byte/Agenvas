package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.audit.domain.LlmStreamLog;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmStreamIncompleteException;
import dev.agenvas.llm.application.LlmRoundService;
import dev.agenvas.llm.application.LlmTurn;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.AgentImageInputService;
import dev.agenvas.audit.application.AsyncCallLogWriter;
import dev.agenvas.audit.application.CallLogRepository;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.shared.error.ApiProblemException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.ObjectMapper;

/** Real Spring AI and HTTP transport; synthetic local responses never invoke a paid model. */
class LlmStreamCompletionHttpTest {
    private static final String TOOL_DELTA = "{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"fixture-tool\","
            + "\"type\":\"function\",\"function\":{\"name\":\"read_project_summary\",\"arguments\":\"{}\"}}]}";

    @Test
    void incompleteActualHttpResponseCannotBeCheckpointedAsResponded() throws Exception {
        var requests = new AtomicInteger();
        HttpServer server = server("text/event-stream", 200, event(TOOL_DELTA, null), requests);
        try {
            var owner = UUID.randomUUID();
            var project = UUID.randomUUID();
            var runId = UUID.randomUUID();
            var lease = mock(Task.class);
            var mapper = new ObjectMapper();
            var checkpoints = mock(LlmTurnCheckpointService.class);
            var runs = mock(AgentRunRepository.class);
            var tasks = mock(TaskService.class);
            var images = mock(AgentImageInputService.class);
            var run = mock(AgentRun.class);
            when(run.policySnapshot()).thenReturn(mapper.createObjectNode()
                    .put("modelConfigSource", "spring-ai").put("modelConfigVersion", 1));
            when(runs.find(owner, project, runId)).thenReturn(Optional.of(run));
            when(images.hydrate(any(), any(), any(), any(), anyList())).thenAnswer(call -> call.getArgument(4));
            when(checkpoints.reserveLeased(any(), any(), any(), anyInt(), anyInt(), anyString(), any(), any(), anyString()))
                    .thenReturn(new LlmTurn(project, runId, 0, LlmTurn.Status.REQUESTED, 1,
                            mapper.createObjectNode(), null, Instant.EPOCH, null));
            var writer = mock(AsyncCallLogWriter.class);
            var logs = new CallLogService(mock(CallLogRepository.class), Clock.systemUTC(), writer);
            var rounds = new LlmRoundService(gateway(server), new LlmProtocolCodec(mapper), checkpoints,
                    runs, logs, tasks, images);

            assertThatThrownBy(() -> rounds.callLeased(owner, project, runId, 0,
                    List.of(new UserMessage("Fixture")), List.of(), Map.of(), lease, "worker"))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            failure -> assertThat(failure.code()).isEqualTo("LLM_STREAM_INCOMPLETE"));
            verify(checkpoints, never()).saveResponseLeased(any(), any(), any(), anyInt(), anyInt(), any(), any(), anyString());
            var outcome = org.mockito.ArgumentCaptor.forClass(CallLogService.CallOutcome.class);
            verify(writer).submit(any(), outcome.capture(), any(), anyLong(), any(), anyList(), eq(false));
            assertThat(outcome.getValue().errorCode()).isEqualTo("LLM_STREAM_INCOMPLETE");
            assertThat(requests).hasValue(1);
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "missing"})
    void incompleteActualSseCannotBypassTheGuardThroughItsContentType(String type) throws Exception {
        HttpServer server = server(type.equals("missing") ? null : type, 200,
                event(TOOL_DELTA, null), new AtomicInteger());
        try {
            var gateway = gateway(server);
            assertThatThrownBy(() -> gateway.callStreaming(List.of(new UserMessage("Fixture")), List.of(), Map.of(),
                    gateway.configIdentity(), ignored -> {}))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            failure -> assertThat(failure.code()).isEqualTo("LLM_STREAM_INCOMPLETE"));
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "data: [DONE]junk\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"})
    void cleanEofAfterParseableToolIsAnExplicitNonRetryableFailure(String ignoredTail) throws Exception {
        var requests = new AtomicInteger();
        HttpServer server = server("text/event-stream", 200, event(TOOL_DELTA, null) + ignoredTail, requests);
        try {
            var gateway = gateway(server);
            var log = new AtomicReference<LlmStreamLog>();
            assertThatThrownBy(() -> gateway.callStreaming(List.of(new UserMessage("Fixture")), List.of(), Map.of(),
                    gateway.configIdentity(), ignored -> {}, false, log::set))
                    .isInstanceOfSatisfying(ApiProblemException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(LlmStreamIncompleteException.ERROR_CODE);
                        assertThat(failure.retryable()).isFalse();
                        assertThat(failure.getMessage()).doesNotContain("fixture-tool", "read_project_summary");
                    });
            assertThat(requests).hasValue(1);
            assertThat(log.get().metrics().status()).isEqualTo(LlmStreamLog.EndStatus.FAILED);
            assertThat(log.get().metrics().errorCode()).isEqualTo("LLM_STREAM_INCOMPLETE");
            assertThat(log.get().content()).isNull();
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"finish", "done", "both", "provider_end"})
    void acceptsASelectedFinishReasonOrDoneWithoutRequiringBoth(String marker) throws Exception {
        String ending = marker.equals("provider_end") ? event("{}", "provider_end")
                : (marker.equals("done") ? "" : event("{}", "tool_calls"))
                    + (marker.equals("finish") ? "" : "data: [DONE]\n\n");
        HttpServer server = server("text/event-stream", 200, event(TOOL_DELTA, null) + ending, new AtomicInteger());
        try {
            var gateway = gateway(server);
            var result = gateway.callStreaming(List.of(new UserMessage("Fixture")), List.of(), Map.of(),
                    gateway.configIdentity(), ignored -> {});
            assertThat(result.response().getResult().getOutput().getToolCalls()).hasSize(1);
        } finally { server.stop(0); }
    }

    @Test
    void aBomPrefixedMarkerIgnoredByTheSdkCannotValidateAnUnfinishedTool() throws Exception {
        HttpServer server = server("text/event-stream", 200,
                "\uFEFF" + event("{}", "stop") + event(TOOL_DELTA, null), new AtomicInteger());
        try {
            var gateway = gateway(server);
            assertThatThrownBy(() -> gateway.callStreaming(List.of(new UserMessage("Fixture")), List.of(), Map.of(),
                    gateway.configIdentity(), ignored -> {}))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            failure -> assertThat(failure.code()).isEqualTo("LLM_STREAM_INCOMPLETE"));
        } finally { server.stop(0); }
    }

    @Test
    void realSdkUppercaseLengthCannotEnterTheDurableToolExecutionPath() throws Exception {
        HttpServer server = server("text/event-stream", 200,
                event(TOOL_DELTA, null) + event("{}", "length"), new AtomicInteger());
        try {
            var gateway = gateway(server);
            var result = gateway.callStreaming(List.of(new UserMessage("Fixture")), List.of(), Map.of(),
                    gateway.configIdentity(), ignored -> {});
            assertThat(result.response().getResult().getMetadata().getFinishReason()).isEqualTo("LENGTH");
            var codec = new LlmProtocolCodec(new ObjectMapper());
            var checkpoint = codec.response(result.response());
            assertThatThrownBy(() -> codec.selectedAssistant(checkpoint))
                    .isInstanceOf(LlmProtocolCodec.OutputTruncated.class);
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 401, 429, 502})
    void streamingAcceptDoesNotImposeSseTerminationOnJsonResponsesOrHideHttpErrors(int status) throws Exception {
        String body = "{\"error\":{\"message\":\"synthetic error\"}}";
        HttpServer server = server("application/json", status, body, new AtomicInteger());
        try {
            var client = new OkHttpClient.Builder().addInterceptor(transport(server).interceptor()).build();
            var request = new Request.Builder().url(endpoint(server) + "/chat/completions")
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create("{}", okhttp3.MediaType.get("application/json"))).build();
            try (var response = client.newCall(request).execute()) {
                assertThat(response.code()).isEqualTo(status);
                assertThat(response.body().string()).isEqualTo(body);
            }
        } finally { server.stop(0); }
    }

    @Test
    void unsuccessfulSseResponseRetainsTheHttpErrorAndItsBody() throws Exception {
        HttpServer server = server("text/event-stream", 429, "data: error\n\n", new AtomicInteger());
        try {
            var client = new OkHttpClient.Builder().addInterceptor(transport(server).interceptor()).build();
            var request = new Request.Builder().url(endpoint(server) + "/chat/completions")
                    .post(RequestBody.create("{}", okhttp3.MediaType.get("application/json"))).build();
            try (var response = client.newCall(request).execute()) {
                assertThat(response.code()).isEqualTo(429);
                assertThat(response.body().string()).isEqualTo("data: error\n\n");
            }
        } finally { server.stop(0); }
    }

    private static SpringAiChatGateway gateway(HttpServer server) {
        var transport = transport(server);
        var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(endpoint(server))
                .apiKey("synthetic-key").model("fixture").maxRetries(0).timeout(Duration.ofSeconds(5)).build())
                .httpClientBuilderCustomizer(builder -> builder.interceptor(transport.interceptor())).build();
        return new SpringAiChatGateway(model, 1);
    }

    private static SafeLlmTransport transport(HttpServer server) {
        return new SafeLlmTransport(endpoint(server), new LlmEndpointPolicy());
    }

    private static String endpoint(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

    private static HttpServer server(String type, int status, String body, AtomicInteger requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (type != null) exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, 0);
            try (var out = exchange.getResponseBody()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            finally { exchange.close(); }
        });
        server.start();
        return server;
    }

    private static String event(String delta, String reason) {
        return "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":" + delta
                + ",\"finish_reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}]}\n\n";
    }
}
