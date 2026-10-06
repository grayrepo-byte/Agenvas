package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.RunToolPolicy;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.settings.application.LlmEndpointProperties;
import dev.agenvas.shared.http.DebugHttpCapture;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.ObjectMapper;

/** Real Spring AI 2.0 SDK and production transport against synthetic local HTTP only.
 * The SDK starts streaming HTTP from a common-pool continuation, outside the caller's ThreadLocal. */
class LlmDebugCaptureStreamingTest {
    private static final int CONFIG_VERSION = 7;
    private static final int HTTP_OK = 200;
    private static final int HTTP_UNAVAILABLE = 503;
    private static final int CONCURRENT_CALLS = 2;
    private static final long TIMEOUT_SECONDS = 10;
    private static final long SDK_TIMEOUT_SECONDS = 30;
    private static final long UNFINISHED_STREAM_HOLD_SECONDS = 20;
    private static final String MODEL_ID = "synthetic-capture-model";
    private static final String API_KEY = "synthetic-unusable-capture-key";
    private static final String REQUEST_TOKEN = "synthetic-request-token";
    private static final String SSE_CONTENT_TYPE = "text/event-stream";
    private static final String DEFAULT_HEADER = "X-Test-Synthetic-Capture";
    private static final String DEFAULT_HEADER_VALUE = "synthetic-default-header";
    private static final String REQUEST_COOKIE = "session=synthetic-http-cookie";
    private static final String RESPONSE_COOKIE = "session=synthetic-response-cookie";
    private static final double DEFAULT_TEMPERATURE = 0.25;
    private static final String CALLBACK_FAILURE_PROMPT = "callback failure stream";
    private static final String CALLBACK_FAILURE_MESSAGE = "synthetic public delta callback failure";
    private static final int DISCONNECT_PROBE_CHUNKS = 128;
    private static final int DISCONNECT_PROBE_CHUNK_BYTES = 16 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void blockingVisionDebugUsesPlaceholderWithoutChangingTheActualRequest() throws Exception {
        assertVisionRequestUsesPlaceholder(false);
    }

    @Test
    void streamingVisionDebugUsesPlaceholderWithoutChangingTheActualRequest() throws Exception {
        assertVisionRequestUsesPlaceholder(true);
    }

    private void assertVisionRequestUsesPlaceholder(boolean streaming) throws Exception {
        byte[] image = "synthetic-image".getBytes(StandardCharsets.UTF_8);
        String encoded = java.util.Base64.getEncoder().encodeToString(image);
        CaptureLog saved = new CaptureLog();
        try (LocalServer server = new LocalServer(ignored -> saved.current())) {
            var gateway = gateway(server);
            var message = UserMessage.builder().text("Inspect synthetic image")
                    .media(new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                            new org.springframework.core.io.ByteArrayResource(image))).build();
            var policy = MAPPER.createObjectNode().put("toolPolicyVersion", RunToolPolicy.CURRENT_VERSION);
            policy.set("allowedTools", MAPPER.valueToTree(RunToolPolicy.current(false, false)));
            var tools = new ToolRegistry().modelDefinitions(policy);
            try (var scope = DebugHttpCapture.openLlm(saved::accept)) {
                // Synthetic credential collision inside the image Base64 must not leave a suffix.
                DebugHttpCapture.registerSecret("dGhl");
                var result = streaming
                        ? gateway.callStreaming(List.of(message), tools, Map.of(), gateway.configIdentity(), ignored -> {})
                        : gateway.call(List.of(message), tools, Map.of(), gateway.configIdentity());
                assertThat(result.response().getResult().getOutput().getText()).isEqualTo("ok");
            }
            Received received = server.received().getFirst();
            assertThat(received.body()).contains("data:image/png;base64," + encoded, "Inspect synthetic image");
            assertThat(received.body()).doesNotContain("[REDACTED]", "[image bytes omitted]");
            assertThat(received.authorization()).isEqualTo("Bearer " + API_KEY);
            assertThat(received.cookie()).isEqualTo(REQUEST_COOKIE);
            assertThat(received.checkpoint().getFirst().requestBody().content())
                    .contains("Inspect synthetic image", "image_url", "[image bytes omitted]")
                    .doesNotContain(encoded, API_KEY);
            var wire = MAPPER.readTree(received.body());
            assertThat(wire.path("tools").isArray()).isTrue();
            assertThat(wire.path("tools").size()).isPositive();
            ((tools.jackson.databind.node.ObjectNode) wire.at("/messages/0/content/1/image_url"))
                    .put("url", "[image bytes omitted]");
            assertThat(MAPPER.readTree(received.checkpoint().getFirst().requestBody().content())).isEqualTo(wire);
            assertThat(MAPPER.readTree(saved.current().getFirst().requestBody().content())).isEqualTo(wire);
            assertThat(saved.current().getFirst().requestBody().content()).doesNotContain(encoded);
            assertThat(saved.current().getFirst().responseBody().content()).contains("ok");
            assertThat(MAPPER.writeValueAsString(saved.current())).doesNotContain(API_KEY, REQUEST_COOKIE, RESPONSE_COOKIE,
                    DEFAULT_HEADER_VALUE, "Set-Cookie", "Authorization");
            if (streaming) {
                assertThat(saved.current().getFirst().responseBody().content()).contains("private stream reasoning")
                        .doesNotContain("data:", "[DONE]");
            }
        }
    }

    @Test
    void capturesRequestForBlockingCallBeforeItReachesTheServer() throws Exception {
        CaptureLog saved = new CaptureLog();
        try (LocalServer server = new LocalServer(ignored -> saved.current())) {
            var gateway = gateway(server);
            try (var scope = DebugHttpCapture.open(saved::accept)) {
                gateway.call(List.of(new UserMessage("capture blocking")), List.of(), Map.of(),
                        gateway.configIdentity());
            }
            assertThat(saved.current()).hasSize(1);
            assertThat(saved.current().getFirst().requestBody().content()).contains("capture blocking");
            assertThat(saved.current().getFirst().responseStatus()).isEqualTo(HTTP_OK);
            assertThat(saved.current().getFirst().responseBody().content()).contains("\"content\":\"ok\"");
            assertCheckpointBeforeArrival(server.received().getFirst(), "capture blocking");
            assertDefaultOptions(server.received().getFirst());
            assertThat(DebugHttpCapture.enabled()).isFalse();
        }
    }

    @Test
    void capturesStreamRequestWithRedactionBeforeSendingAndOmitsTheSseBody() throws Exception {
        CaptureLog saved = new CaptureLog();
        String prompt = "capture streaming " + API_KEY
                + " https://example.invalid/download?token=" + REQUEST_TOKEN;
        try (LocalServer server = new LocalServer(ignored -> saved.current())) {
            var gateway = gateway(server);
            List<String> deltas = new CopyOnWriteArrayList<>();
            try (var scope = DebugHttpCapture.open(saved::accept)) {
                var result = stream(gateway, prompt, deltas);
                assertThat(result.response().getResult().getOutput().getText()).isEqualTo("ok");
            }
            assertThat(String.join("", deltas)).isEqualTo("ok");
            assertThat(saved.current()).hasSize(1);
            var captured = saved.current().getFirst();
            assertThat(captured.requestBody().content()).contains("capture streaming", "REDACTED")
                    .doesNotContain(API_KEY, REQUEST_TOKEN);
            assertThat(captured.responseStatus()).isEqualTo(HTTP_OK);
            assertThat(captured.responseBody().encoding()).isEqualTo(DebugHttpCapture.Encoding.OMITTED);
            assertThat(MAPPER.writeValueAsString(saved.current()))
                    .doesNotContain(API_KEY, REQUEST_TOKEN, "Authorization", "private stream reasoning");
            Received received = server.received().getFirst();
            // Sanitization affects the checkpoint only; the actual request retains its credentials.
            assertThat(received.authorization()).isEqualTo("Bearer " + API_KEY);
            assertThat(received.body()).contains(API_KEY, REQUEST_TOKEN);
            assertCheckpointBeforeArrival(received, "capture streaming");
            assertDefaultOptions(received);
            assertThat(DebugHttpCapture.enabled()).isFalse();
        }
    }

    @Test
    void realSdkStreamAlsoProducesSemanticResponse() throws Exception {
        CaptureLog http = new CaptureLog();
        AtomicReference<dev.agenvas.audit.domain.LlmStreamLog> log = new AtomicReference<>();
        try (LocalServer server = new LocalServer(ignored -> http.current())) {
            var gateway = gateway(server);
            try (var scope = DebugHttpCapture.openLlm(http::accept)) {
                gateway.callStreaming(List.of(new UserMessage("semantic capture")), List.of(), Map.of(),
                        gateway.configIdentity(), ignored -> {}, true, log::set);
                assertThat(scope.sanitizeJson(log.get().content().response())).contains("ok").doesNotContain(API_KEY);
            }
            assertThat(http.current().getFirst().responseBody().encoding()).isEqualTo(DebugHttpCapture.Encoding.UTF8);
            assertThat(http.current().getFirst().responseBody().content()).contains("private stream reasoning")
                    .doesNotContain(API_KEY, "data:", "[DONE]");
            assertThat(log.get().metrics().status()).isEqualTo(dev.agenvas.audit.domain.LlmStreamLog.EndStatus.COMPLETED);
            assertThat(log.get().metrics().firstTextMs()).isNotNull();
            assertThat(log.get().metrics().totalTokens()).isEqualTo(2);
            assertThat(server.received()).hasSize(1);
        }
    }

    @Test
    void defaultGatewayNeverSendsAnInternalCaptureMarkerToAnUnmanagedTransport() throws Exception {
        CaptureLog saved = new CaptureLog();
        try (LocalServer server = new LocalServer(ignored -> saved.current())) {
            OpenAiChatModel model = OpenAiChatModel.builder().options(options(server)).build();
            SpringAiChatGateway gateway = new SpringAiChatGateway(model, CONFIG_VERSION);
            try (var scope = DebugHttpCapture.open(saved::accept)) {
                stream(gateway, "unmanaged streaming transport", new CopyOnWriteArrayList<>());
            }
            assertThat(server.received()).hasSize(1);
            assertThat(server.received().getFirst().captureMarker()).isNull();
            assertDefaultOptions(server.received().getFirst());
            assertThat(saved.current()).isEmpty();
            assertThat(DebugHttpCapture.enabled()).isFalse();
        }
    }

    @Test
    void overlappingStreamingCallsOnTheSameGatewayKeepTheirOwnCapture() throws Exception {
        String firstPrompt = "stream capture first";
        String secondPrompt = "stream capture second";
        CaptureLog first = new CaptureLog();
        CaptureLog second = new CaptureLog();
        CountDownLatch arrived = new CountDownLatch(CONCURRENT_CALLS);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService calls = Executors.newFixedThreadPool(CONCURRENT_CALLS);
        try (LocalServer server = new LocalServer(body -> body.contains(firstPrompt)
                ? first.current() : second.current(), arrived, release)) {
            var gateway = gateway(server);
            var firstCall = calls.submit(() -> captureStream(gateway, firstPrompt, first));
            var secondCall = calls.submit(() -> captureStream(gateway, secondPrompt, second));
            try {
                assertThat(arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("Both streams are in flight").isTrue();
                assertOnlyPrompt(first, firstPrompt, secondPrompt);
                assertOnlyPrompt(second, secondPrompt, firstPrompt);
                assertThat(first.current().getFirst().responseStatus()).isNull();
                assertThat(second.current().getFirst().responseStatus()).isNull();
                release.countDown();
                firstCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                secondCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertOnlyPrompt(first, firstPrompt, secondPrompt);
                assertOnlyPrompt(second, secondPrompt, firstPrompt);
                assertThat(first.current().getFirst().responseStatus()).isEqualTo(HTTP_OK);
                assertThat(second.current().getFirst().responseStatus()).isEqualTo(HTTP_OK);
                assertThat(server.received()).hasSize(CONCURRENT_CALLS);
                for (Received received : server.received()) {
                    assertCheckpointBeforeArrival(received,
                            received.body().contains(firstPrompt) ? firstPrompt : secondPrompt);
                }
            } finally {
                release.countDown();
            }
        } finally {
            shutdown(calls);
        }
    }

    @Test
    void disabledStreamingCallOverlappingAnEnabledCallNeverJoinsItsCapture() throws Exception {
        String enabledPrompt = "enabled overlapping stream";
        String disabledPrompt = "disabled overlapping stream";
        CaptureLog enabled = new CaptureLog();
        CountDownLatch arrived = new CountDownLatch(CONCURRENT_CALLS);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService calls = Executors.newFixedThreadPool(CONCURRENT_CALLS);
        try (LocalServer server = new LocalServer(ignored -> enabled.current(), arrived, release)) {
            var gateway = gateway(server);
            var enabledCall = calls.submit(() -> captureStream(gateway, enabledPrompt, enabled));
            var disabledCall = calls.submit(() -> {
                assertThat(DebugHttpCapture.enabled()).isFalse();
                stream(gateway, disabledPrompt, new CopyOnWriteArrayList<>());
                assertThat(DebugHttpCapture.enabled()).isFalse();
            });
            try {
                assertThat(arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                assertOnlyPrompt(enabled, enabledPrompt, disabledPrompt);
                release.countDown();
                enabledCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                disabledCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertOnlyPrompt(enabled, enabledPrompt, disabledPrompt);
                assertThat(server.received()).hasSize(CONCURRENT_CALLS);
            } finally {
                release.countDown();
            }
        } finally {
            shutdown(calls);
        }
    }

    @Test
    void capturesHttpFailureWithoutRetryAndDoesNotLeakIntoFollowingCalls() throws Exception {
        String failedPrompt = "capture HTTP failure";
        String disabledPrompt = "disabled after failure";
        String followingPrompt = "enabled after failure";
        CaptureLog failed = new CaptureLog();
        CaptureLog following = new CaptureLog();
        try (LocalServer server = new LocalServer(body -> body.contains(failedPrompt)
                ? failed.current() : following.current())) {
            var gateway = gateway(server);
            Throwable failure;
            try (var scope = DebugHttpCapture.open(failed::accept)) {
                failure = catchThrowable(() -> stream(gateway, failedPrompt, new CopyOnWriteArrayList<>()));
            }
            assertThat(failure).isNotNull();
            assertThat(DebugHttpCapture.enabled()).isFalse();
            assertThat(server.received()).hasSize(1);
            assertOnlyPrompt(failed, failedPrompt, disabledPrompt);
            var failedCapture = failed.current().getFirst();
            assertThat(failedCapture.responseStatus()).isEqualTo(HTTP_UNAVAILABLE);
            assertThat(failedCapture.responseBody().content()).contains("synthetic unavailable", "REDACTED")
                    .doesNotContain(API_KEY, "synthetic-response-secret");
            List<DebugHttpCapture.Exchange> completedFailure = failed.current();
            stream(gateway, disabledPrompt, new CopyOnWriteArrayList<>());
            assertThat(failed.current()).isEqualTo(completedFailure);
            assertThat(following.current()).isEmpty();
            assertThat(DebugHttpCapture.enabled()).isFalse();
            captureStream(gateway, followingPrompt, following);
            assertOnlyPrompt(following, followingPrompt, failedPrompt);
            assertThat(following.current().getFirst().requestBody().content()).doesNotContain(disabledPrompt);
            assertThat(failed.current()).isEqualTo(completedFailure);
            assertThat(server.received()).hasSize(3);
            assertCheckpointBeforeArrival(server.received().getFirst(), failedPrompt);
            assertThat(server.received().get(1).checkpoint()).isEmpty();
            assertCheckpointBeforeArrival(server.received().getLast(), followingPrompt);
        }
    }

    @Test
    void publicDeltaFailureClosesResponseCaptureWithoutRetryOrScopeLeak() throws Exception {
        String disabledPrompt = "disabled after callback failure";
        String followingPrompt = "enabled after callback failure";
        CaptureLog failed = new CaptureLog();
        CaptureLog following = new CaptureLog();
        CallbackStreamGate gate = new CallbackStreamGate();
        try (LocalServer server = new LocalServer(body -> body.contains(CALLBACK_FAILURE_PROMPT)
                ? failed.current() : following.current(), new CountDownLatch(0), new CountDownLatch(0), gate)) {
            var gateway = gateway(server);
            Throwable failure;
            try (var scope = DebugHttpCapture.openLlm(failed::accept)) {
                failure = catchThrowable(() -> gateway.callStreaming(List.of(new UserMessage(CALLBACK_FAILURE_PROMPT)),
                        List.of(), Map.of(), gateway.configIdentity(), delta -> {
                            gate.callbackInvoked.countDown();
                            throw new IllegalStateException(CALLBACK_FAILURE_MESSAGE);
                        }));
            }
            assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage(CALLBACK_FAILURE_MESSAGE);
            assertThat(gate.callbackInvoked.getCount()).isZero();
            assertThat(failed.terminal.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("Capture closes before the unfinished response or SDK deadline completes").isTrue();
            assertThat(failed.terminalCheckpoints).hasValue(1);
            assertThat(server.received()).hasSize(1);
            assertOnlyPrompt(failed, CALLBACK_FAILURE_PROMPT, disabledPrompt);
            var cancelled = failed.current().getFirst();
            assertThat(cancelled.responseStatus()).isEqualTo(HTTP_OK);
            assertThat(cancelled.responseBody().encoding()).isEqualTo(DebugHttpCapture.Encoding.UTF8);
            assertThat(cancelled.responseBody().content()).contains("callback public text")
                    .doesNotContain(API_KEY, "[DONE]");
            assertThat(cancelled.responseBody().truncated()).isTrue();
            assertThat(DebugHttpCapture.enabled()).isFalse();
            List<DebugHttpCapture.Exchange> completedFailure = failed.current();
            stream(gateway, disabledPrompt, new CopyOnWriteArrayList<>());
            assertThat(failed.current()).isEqualTo(completedFailure);
            assertThat(following.current()).isEmpty();
            captureStream(gateway, followingPrompt, following);
            assertOnlyPrompt(following, followingPrompt, CALLBACK_FAILURE_PROMPT);
            assertThat(following.current().getFirst().requestBody().content()).doesNotContain(disabledPrompt);
            assertThat(failed.current()).isEqualTo(completedFailure);
            assertThat(failed.terminalCheckpoints).hasValue(1);
            assertThat(server.received()).hasSize(3);
        }
    }

    private static void captureStream(SpringAiChatGateway gateway, String prompt, CaptureLog capture) {
        try (var scope = DebugHttpCapture.open(capture::accept)) {
            stream(gateway, prompt, new CopyOnWriteArrayList<>());
        }
        assertThat(DebugHttpCapture.enabled()).isFalse();
    }

    private static ChatGateway.Exchange stream(SpringAiChatGateway gateway, String prompt, List<String> deltas) {
        return gateway.callStreaming(List.of(new UserMessage(prompt)), List.of(), Map.of(),
                gateway.configIdentity(), deltas::add);
    }

    private static void assertOnlyPrompt(CaptureLog saved, String ownPrompt, String otherPrompt) {
        assertThat(saved.current()).hasSize(1);
        assertThat(saved.current().getFirst().requestBody().content()).contains(ownPrompt).doesNotContain(otherPrompt);
    }

    private static void assertCheckpointBeforeArrival(Received received, String prompt) {
        assertThat(received.checkpoint()).as("Request checkpoint exists before the HTTP server receives the request").hasSize(1);
        assertThat(received.checkpoint().getFirst().requestBody().content()).contains(prompt);
        assertThat(received.checkpoint().getFirst().responseStatus()).isNull();
        assertThat(received.captureMarker()).as("Internal correlation header never leaves the process").isNull();
    }

    private static void assertDefaultOptions(Received received) {
        var body = MAPPER.readTree(received.body());
        assertThat(body.path("model").asText()).isEqualTo(MODEL_ID);
        assertThat(body.path("temperature").asDouble()).isEqualTo(DEFAULT_TEMPERATURE);
        assertThat(received.defaultHeader()).isEqualTo(DEFAULT_HEADER_VALUE);
    }

    private static SpringAiChatGateway gateway(LocalServer server) {
        String endpoint = "http://127.0.0.1:" + server.port() + "/v1";
        SafeLlmTransport transport = new SafeLlmTransport(endpoint,
                new LlmEndpointPolicy(new LlmEndpointProperties(true)));
        OpenAiChatModel model = OpenAiChatModel.builder().options(options(server))
                .httpClientBuilderCustomizer(builder -> builder.interceptor(transport.interceptor()))
                .build();
        return SpringAiChatGateway.withDebugCapture(model, CONFIG_VERSION);
    }

    private static OpenAiChatOptions options(LocalServer server) {
        return OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:" + server.port() + "/v1").apiKey(API_KEY).model(MODEL_ID).maxRetries(0)
                .temperature(DEFAULT_TEMPERATURE).customHeaders(Map.of(DEFAULT_HEADER, DEFAULT_HEADER_VALUE, "Cookie", REQUEST_COOKIE))
                .timeout(Duration.ofSeconds(SDK_TIMEOUT_SECONDS)).build();
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private static final class CaptureLog {
        private final AtomicReference<List<DebugHttpCapture.Exchange>> saved = new AtomicReference<>(List.of());
        private final AtomicInteger terminalCheckpoints = new AtomicInteger();
        private final CountDownLatch terminal = new CountDownLatch(1);
        private void accept(List<DebugHttpCapture.Exchange> exchanges) {
            saved.set(exchanges);
            if (exchanges.stream().anyMatch(exchange -> exchange.responseBody() != null)) {
                terminalCheckpoints.incrementAndGet();
                terminal.countDown();
            }
        }
        private List<DebugHttpCapture.Exchange> current() { return saved.get(); }
    }

    private record Received(String body, String authorization, String cookie, String captureMarker, String defaultHeader,
            List<DebugHttpCapture.Exchange> checkpoint) {}

    private static final class CallbackStreamGate {
        private final CountDownLatch callbackInvoked = new CountDownLatch(1);
        private final CountDownLatch finish = new CountDownLatch(1);
    }

    /** The gate keeps both requests pending so isolation cannot pass through accidental serialization. */
    private static final class LocalServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService requests = Executors.newFixedThreadPool(CONCURRENT_CALLS);
        private final List<Received> received = new CopyOnWriteArrayList<>();
        private final Function<String, List<DebugHttpCapture.Exchange>> checkpoint;
        private final CountDownLatch arrived;
        private final CountDownLatch release;
        private final CallbackStreamGate callbackGate;

        private LocalServer(Function<String, List<DebugHttpCapture.Exchange>> checkpoint) throws IOException {
            this(checkpoint, new CountDownLatch(0), new CountDownLatch(0));
        }

        private LocalServer(Function<String, List<DebugHttpCapture.Exchange>> checkpoint,
                CountDownLatch arrived, CountDownLatch release) throws IOException {
            this(checkpoint, arrived, release, null);
        }

        private LocalServer(Function<String, List<DebugHttpCapture.Exchange>> checkpoint,
                CountDownLatch arrived, CountDownLatch release, CallbackStreamGate callbackGate) throws IOException {
            this.checkpoint = checkpoint;
            this.arrived = arrived;
            this.release = release;
            this.callbackGate = callbackGate;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(requests);
            server.createContext("/v1/chat/completions", this::respond);
            server.start();
        }

        private int port() { return server.getAddress().getPort(); }
        private List<Received> received() { return List.copyOf(received); }

        private void respond(HttpExchange exchange) throws IOException {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.add(new Received(body, exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Cookie"),
                        exchange.getRequestHeaders().getFirst(DebugHttpCapture.CAPTURE_HEADER),
                        exchange.getRequestHeaders().getFirst(DEFAULT_HEADER), checkpoint.apply(body)));
                exchange.getResponseHeaders().set("Set-Cookie", RESPONSE_COOKIE);
                arrived.countDown();
                if (callbackGate != null && body.contains(CALLBACK_FAILURE_PROMPT)) {
                    unfinishedStream(exchange);
                    return;
                }
                try {
                    if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IOException("Synthetic response gate timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Synthetic response gate interrupted", interrupted);
                }
                boolean failed = body.contains("capture HTTP failure");
                boolean streaming = body.contains("\"stream\":true");
                exchange.getResponseHeaders().set("Content-Type", failed || !streaming ? "application/json" : SSE_CONTENT_TYPE);
                if (failed) exchange.getResponseHeaders().set("Retry-After", "0");
                byte[] response = failed ? errorBody() : streaming ? sseBody() : jsonBody();
                exchange.sendResponseHeaders(failed ? HTTP_UNAVAILABLE : HTTP_OK, response.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
            } finally {
                exchange.close();
            }
        }

        @Override public void close() throws InterruptedException {
            release.countDown();
            if (callbackGate != null) callbackGate.finish.countDown();
            server.stop(0);
            shutdown(requests);
        }

        private void unfinishedStream(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", SSE_CONTENT_TYPE);
            exchange.sendResponseHeaders(HTTP_OK, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(unfinishedEvent("callback public text"));
                output.flush();
                try {
                    if (!callbackGate.callbackInvoked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("Synthetic stream callback timed out");
                    }
                    // Only write more data after the public callback fails. No stop or DONE frame
                    // is sent; the server holds its response longer than the capture-close check.
                    byte[] next = unfinishedEvent("x".repeat(DISCONNECT_PROBE_CHUNK_BYTES));
                    for (int index = 0; index < DISCONNECT_PROBE_CHUNKS; index++) {
                        output.write(next);
                        output.flush();
                    }
                    if (!callbackGate.finish.await(UNFINISHED_STREAM_HOLD_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("Synthetic unfinished stream teardown timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Synthetic unfinished stream interrupted", interrupted);
                }
            } catch (IOException closed) {
                // A caller closing its HTTP response may end the writer before teardown.
            }
        }
    }

    private static byte[] unfinishedEvent(String content) {
        return event("{\"id\":\"chatcmpl-unfinished\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"" + MODEL_ID + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content
                + "\"},\"finish_reason\":null}]}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] errorBody() {
        return ("{\"error\":{\"message\":\"synthetic unavailable " + API_KEY + "\","
                + "\"type\":\"server_error\",\"code\":\"synthetic_unavailable\","
                + "\"apiKey\":\"synthetic-response-secret\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jsonBody() {
        return ("{\"id\":\"chatcmpl-block\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\""
                + MODEL_ID + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sseBody() {
        StringBuilder body = new StringBuilder();
        body.append(event("{\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"" + MODEL_ID + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\","
                + "\"reasoning_content\":\"private stream reasoning\"},\"finish_reason\":\"stop\"}]}"));
        body.append(event("{\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"" + MODEL_ID + "\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,"
                + "\"completion_tokens\":1,\"total_tokens\":2}}"));
        body.append(event("[DONE]"));
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String event(String value) { return "data: " + value + "\n\n"; }
}
