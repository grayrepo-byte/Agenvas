package dev.agenvas.audit.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.audit.domain.LlmStreamLog;
import dev.agenvas.llm.application.LlmStreamLogCollector;
import dev.agenvas.shared.http.DebugHttpCapture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class CallLogStreamServiceTest {
    private final CallLogRepository repository = mock(CallLogRepository.class);
    private final AsyncCallLogWriter writer = mock(AsyncCallLogWriter.class);
    private final CallLogService service = new CallLogService(repository, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), writer);

    @Test void durableStartPrecedesStreamAndActualBodyContentIsEnqueued() {
        when(repository.isDebugEnabled()).thenReturn(true);
        MDC.put("traceId", "parent");
        try {
            var result = service.recordStream(descriptor(), (capture, listener) -> {
                verify(repository).start(any(), any(), anyString(), any());
                verify(repository, never()).saveDebug(any(), anyList());
                verifyNoInteractions(writer);
                assertThat(capture).isTrue();
                assertThat(DebugHttpCapture.enabled()).isTrue();
                listener.accept(log("public sk-synthetic-unusable-secret <think>private</think>"));
                return "model result";
            }, ignored -> CallLogService.CallOutcome.succeeded("synthetic-response"));
            assertThat(result).isEqualTo("model result");
            assertThat(MDC.get("traceId")).isEqualTo("parent");
            assertThat(DebugHttpCapture.enabled()).isFalse();
            var captured = ArgumentCaptor.forClass(LlmStreamLog.class);
            verify(writer).submit(any(), any(), any(), anyLong(), captured.capture(), anyList(), eq(true));
            assertThat(captured.getValue().content().response()).contains("<think>private</think>", "sk-synthetic-unusable-secret")
                    .doesNotContain("[REDACTED]");
            verify(repository, never()).finish(any(), any(), any(), anyLong());
        } finally { MDC.clear(); }
    }

    @Test void disabledBodiesAreDroppedEvenIfAdapterAccidentallySuppliesContent() {
        service.recordStream(descriptor(), (capture, listener) -> {
            assertThat(capture).isFalse(); listener.accept(log("synthetic private content")); return "ok";
        }, ignored -> CallLogService.CallOutcome.succeeded(null));
        var captured = ArgumentCaptor.forClass(LlmStreamLog.class);
        verify(writer).submit(any(), any(), any(), anyLong(), captured.capture(), anyList(), eq(false));
        assertThat(captured.getValue().content()).isNull();
        assertThat(captured.getValue().metrics().firstTextMs()).isNotNull();
    }

    @Test void persistsOneAssembledHttpResponseInsteadOfDuplicateSdkAndHttpBodies() throws Exception {
        when(repository.isDebugEnabled()).thenReturn(true);
        String events = "data: {\"id\":\"synthetic-response\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"wire answer\","
                + "\"reasoning_content\":\"wire reasoning\"}}]}\n\ndata: [DONE]\n\n";
        service.recordStream(descriptor(), (capture, listener) -> {
            int id = DebugHttpCapture.begin("POST", "https://provider.invalid/chat", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json");
            try (var body = DebugHttpCapture.responseStream(id, 200, "text/event-stream",
                    new java.io.ByteArrayInputStream(events.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                body.readAllBytes();
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            listener.accept(log("sdk answer"));
            return "result";
        }, ignored -> CallLogService.CallOutcome.succeeded(null));
        var log = ArgumentCaptor.forClass(LlmStreamLog.class);
        ArgumentCaptor<List<DebugHttpCapture.Exchange>> exchanges = ArgumentCaptor.captor();
        verify(writer).submit(any(), any(), any(), anyLong(), log.capture(), exchanges.capture(), eq(true));
        assertThat(log.getValue().content().response()).contains("wire answer", "wire reasoning")
                .doesNotContain("sdk answer", "data:", "[DONE]", "[REDACTED]");
        assertThat(exchanges.getValue()).hasSize(1);
        var exchange = exchanges.getValue().getFirst();
        assertThat(exchange.responseBody()).isNull();
        assertThat(exchange.responseStatus()).isEqualTo(200);
        assertThat(exchange.requestBody().content()).isEqualTo("{}");
    }

    @Test void preservesHttpCorrelationIdsWhenStreamBodyMovesIntoSingleSummary() throws Exception {
        when(repository.isDebugEnabled()).thenReturn(true);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", http -> {
            http.getRequestBody().readAllBytes();
            http.getResponseHeaders().set("Content-Type", "text/event-stream");
            http.getResponseHeaders().set("X-Trace-Id", "synthetic-platform-trace");
            byte[] body = "data: {\"id\":\"synthetic-response\",\"choices\":[]}\n\ndata: [DONE]\n\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            http.sendResponseHeaders(200, body.length);
            http.getResponseBody().write(body);
            http.close();
        });
        server.start();
        try {
            service.recordStream(descriptor(), (capture, listener) -> {
                var client = new okhttp3.OkHttpClient.Builder().addInterceptor(DebugHttpCapture.interceptor()).build();
                var request = new okhttp3.Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/chat")
                        .post(okhttp3.RequestBody.create("{}", okhttp3.MediaType.get("application/json")));
                DebugHttpCapture.requestHeaders().forEach(request::header);
                try (var response = client.newCall(request.build()).execute()) { response.body().string(); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                listener.accept(log("sdk answer"));
                return "result";
            }, ignored -> CallLogService.CallOutcome.succeeded("synthetic-response"));
            ArgumentCaptor<List<DebugHttpCapture.Exchange>> exchanges = ArgumentCaptor.captor();
            verify(writer).submit(any(), any(), any(), anyLong(), any(), exchanges.capture(), eq(true));
            assertThat(exchanges.getValue().getFirst().responseBody()).isNull();
            assertThat(exchanges.getValue().getFirst().responseIdentifiers())
                    .containsEntry("x-trace-id", "synthetic-platform-trace");
        } finally { server.stop(0); }
    }

    @Test void originalFailureAndPartialLogSurviveWithoutModelRetry() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException failure = new IllegalStateException("synthetic private failure");
        assertThatThrownBy(() -> service.recordStream(descriptor(), (capture, listener) -> {
            calls.incrementAndGet(); listener.accept(log("partial")); throw failure;
        }, ignored -> CallLogService.CallOutcome.succeeded(null))).isSameAs(failure);
        assertThat(calls).hasValue(1);
        var outcome = ArgumentCaptor.forClass(CallLogService.CallOutcome.class);
        verify(writer).submit(any(), outcome.capture(), any(), anyLong(), any(), anyList(), anyBoolean());
        assertThat(outcome.getValue().status()).isEqualTo(CallLog.Status.FAILED);
        assertThat(outcome.getValue().errorCode()).isEqualTo("CALL_TECHNICAL_FAILURE");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test void oversizedHttpAggregateUsesOneBoundedSdkPrefixWithAPartialMarker() {
        when(repository.isDebugEnabled()).thenReturn(true);
        service.recordStream(descriptor(), (capture, listener) -> {
            String response = "{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(LlmStreamLog.MAX_CONTENT_BYTES) + "\"}}]}";
            int id = DebugHttpCapture.begin("POST", "https://provider.invalid/chat", null, null);
            try (var body = DebugHttpCapture.responseStream(id, 200, "application/json",
                    new java.io.ByteArrayInputStream(response.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                body.readAllBytes();
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            listener.accept(log("bounded sdk prefix"));
            return "result";
        }, ignored -> CallLogService.CallOutcome.succeeded(null));
        var log = ArgumentCaptor.forClass(LlmStreamLog.class);
        ArgumentCaptor<List<DebugHttpCapture.Exchange>> exchanges = ArgumentCaptor.captor();
        verify(writer).submit(any(), any(), any(), anyLong(), log.capture(), exchanges.capture(), eq(true));
        assertThat(log.getValue().content().response()).contains("bounded sdk prefix").hasSizeLessThan(LlmStreamLog.MAX_CONTENT_BYTES);
        assertThat(log.getValue().content().truncated()).isTrue();
        assertThat(exchanges.getValue().getFirst().responseBody()).isNull();
    }

    private LlmStreamLog log(String text) {
        var collector = new LlmStreamLogCollector(true);
        collector.chunk(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
        return collector.snapshot(LlmStreamLog.EndStatus.COMPLETED, null);
    }
    private CallLogService.CallDescriptor descriptor() {
        return new CallLogService.CallDescriptor(UUID.randomUUID(), null, UUID.randomUUID(), 0,
                CallLog.Kind.LLM, CallLog.Operation.CHAT, "synthetic", "synthetic-model", false);
    }
}
