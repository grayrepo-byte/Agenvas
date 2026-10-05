package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.settings.application.CredentialProperties;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.settings.application.LlmEndpointProperties;
import dev.agenvas.settings.application.LlmProviderConfig;
import dev.agenvas.shared.http.DebugHttpCapture;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

/** Production factory and Spring AI SDK against synthetic local HTTP only; no real Provider. */
class LlmCallTimeoutHttpTest {
    private static final Duration EXPECTED_MODEL_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration EXPECTED_READ_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration EXPECTED_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String MODEL_ID = "synthetic-timeout-model";
    private static final int CONFIG_VERSION = 1;
    private static final Duration SHORT_TEST_TIMEOUT = Duration.ofMillis(400);
    private static final Duration LONG_TEST_TIMEOUT = Duration.ofSeconds(3);
    private static final int STREAM_FRAGMENTS = 30;
    private static final long STREAM_INTERVAL_MILLIS = 40;
    private static final long SILENT_READ_MILLIS = 700;
    private final LlmEndpointPolicy localPolicy = new LlmEndpointPolicy(new LlmEndpointProperties(true));
    private final CredentialCipher cipher = new CredentialCipher(new CredentialProperties(
            Base64.getEncoder().encodeToString(new byte[32]), CONFIG_VERSION, null));

    @Test
    void productionTransportUsesTenMinuteCallsAndThreeMinuteSilentReads() {
        SafeLlmTransport transport = new SafeLlmTransport("http://127.0.0.1:8080/v1", localPolicy);
        OkHttpClient client = (OkHttpClient) ReflectionTestUtils.getField(transport, "client");
        assertThat(client).isNotNull();
        assertThat(client.callTimeoutMillis()).isEqualTo(EXPECTED_MODEL_TIMEOUT.toMillis());
        assertThat(client.readTimeoutMillis()).isEqualTo(EXPECTED_READ_TIMEOUT.toMillis());
        assertThat(client.connectTimeoutMillis()).isEqualTo(EXPECTED_CONNECT_TIMEOUT.toMillis());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void productionFactorySetsTenMinuteSdkDeadlineForNormalAndStreamingRequests(boolean debug) throws Exception {
        AtomicReference<String> normalTimeout = new AtomicReference<>();
        AtomicReference<String> streamTimeout = new AtomicReference<>();
        AtomicReference<String> normalReadTimeout = new AtomicReference<>();
        AtomicReference<String> streamReadTimeout = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int request = requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String deadline = exchange.getRequestHeaders().getFirst("X-Stainless-Timeout");
            boolean streaming = request > 1;
            (streaming ? streamTimeout : normalTimeout).set(deadline);
            (streaming ? streamReadTimeout : normalReadTimeout)
                    .set(exchange.getRequestHeaders().getFirst("X-Stainless-Read-Timeout"));
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                String response = streaming
                        ? "data: {\"id\":\"synthetic-stream\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                                + "\"model\":\"" + MODEL_ID + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Synthetic complete\"},\"finish_reason\":\"stop\"}]}\n\n"
                                + "data: [DONE]\n\n"
                        : "{\"id\":\"synthetic-normal\",\"object\":\"chat.completion\",\"created\":1700000000,"
                                + "\"model\":\"" + MODEL_ID + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Synthetic complete\"},\"finish_reason\":\"stop\"}]}";
                output.write(response.getBytes(StandardCharsets.UTF_8));
            } finally { exchange.close(); }
        });
        server.start();
        try {
            SpringAiChatGateway gateway = new StoredChatModelFactory(cipher, localPolicy).create(config(server));
            List<UserMessage> messages = List.of(new UserMessage("Synthetic timeout check"));
            try (DebugHttpCapture capture = debug ? DebugHttpCapture.openLlm(ignored -> {}) : null) {
                assertThat(gateway.call(List.copyOf(messages), List.of(), Map.of()).response()
                        .getResult().getOutput().getText()).isEqualTo("Synthetic complete");
                assertThat(gateway.callStreaming(List.copyOf(messages), List.of(), Map.of(),
                        gateway.configIdentity(), ignored -> {}).response().getResult().getOutput().getText())
                        .isEqualTo("Synthetic complete");
            }
            assertThat(normalTimeout).hasValue(Long.toString(EXPECTED_MODEL_TIMEOUT.toSeconds()));
            assertThat(streamTimeout).hasValue(Long.toString(EXPECTED_MODEL_TIMEOUT.toSeconds()));
            // SDK metadata inherits its total deadline. The admitted inner transport enforces
            // the independent 180-second read limit verified above and by the silent fixture.
            assertThat(normalReadTimeout).hasValue(Long.toString(EXPECTED_MODEL_TIMEOUT.toSeconds()));
            assertThat(streamReadTimeout).hasValue(Long.toString(EXPECTED_MODEL_TIMEOUT.toSeconds()));
            assertThat(requests).hasValue(2);
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentCallAndReadDeadlinesStopUnfinishedStreamsBeforeACompleteRetry(boolean silentRead) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger sentFragments = new AtomicInteger();
        var fixtureExecutor = Executors.newFixedThreadPool(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(fixtureExecutor);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                int fragments = silentRead ? 1 : STREAM_FRAGMENTS;
                for (int index = 0; index < fragments; index++) {
                    streamEvent(output, "{\"content\":\"x\"}", null);
                    sentFragments.incrementAndGet();
                    Thread.sleep(silentRead ? SILENT_READ_MILLIS : STREAM_INTERVAL_MILLIS);
                }
                streamEvent(output, "{}", "stop");
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException timedOutClient) {
                // The short deadline closes its connection before the fixture finishes.
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            StoredChatModelFactory factory = new StoredChatModelFactory(cipher, localPolicy);
            LlmProviderConfig config = config(server);
            SpringAiChatGateway shortCall = factory.create(config,
                    silentRead ? LONG_TEST_TIMEOUT : SHORT_TEST_TIMEOUT,
                    silentRead ? SHORT_TEST_TIMEOUT : LONG_TEST_TIMEOUT);
            Throwable failure = catchThrowable(() -> shortCall.callStreaming(
                    List.of(new UserMessage("Synthetic unfinished stream")), List.of(), Map.of(),
                    shortCall.configIdentity(), ignored -> {}));
            assertThat(failure).isNotNull();
            boolean timedOut = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (!silentRead && cause instanceof InterruptedIOException
                        && "timeout".equals(cause.getMessage())) timedOut = true;
                if (silentRead && cause instanceof SocketTimeoutException) timedOut = true;
            }
            assertThat(timedOut).as("Finite call/read deadline before the synthetic finish marker").isTrue();
            assertThat(sentFragments.get()).isPositive();

            SpringAiChatGateway longCall = factory.create(config, LONG_TEST_TIMEOUT, LONG_TEST_TIMEOUT);
            var completed = longCall.callStreaming(List.of(new UserMessage("Synthetic unfinished stream")),
                    List.of(), Map.of(), longCall.configIdentity(), ignored -> {});
            assertThat(completed.response().getResult().getOutput().getText())
                    .isEqualTo("x".repeat(silentRead ? 1 : STREAM_FRAGMENTS));
            assertThat(requests).as("Neither deadline path retries a billable request").hasValue(2);
        } finally {
            server.stop(0);
            fixtureExecutor.shutdownNow();
        }
    }

    private static void streamEvent(OutputStream output, String delta, String finish) throws IOException {
        String event = "data: {\"id\":\"synthetic-timeout-stream\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1700000000,\"model\":\"" + MODEL_ID + "\",\"choices\":[{\"index\":0,"
                + "\"delta\":" + delta + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\n\n";
        output.write(event.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private LlmProviderConfig config(HttpServer server) {
        UUID id = UUID.randomUUID();
        CredentialCipher.Encrypted encrypted = cipher.encrypt(id, CONFIG_VERSION, "synthetic-unusable-key");
        return new LlmProviderConfig(id, CONFIG_VERSION,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", MODEL_ID,
                encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(), "synthetic-mask",
                true, true, Instant.EPOCH);
    }
}
