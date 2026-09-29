package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.shared.error.ProviderFailureCodes;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Definite rejection, uncertain submission and redirect boundaries of the fixed API. */
class GoogleNanoBananaClientTest {
    /** 注入短超时的用例里，响应只要慢过这个时长的上限就足以触发超时。 */
    private static final Duration IMPATIENT_TIMEOUT = Duration.ofMillis(300);
    private static final long SLOW_RESPONSE_DELAY_MS = 2_000;

    /**
     * 与 GPT Image 同一个故障面：同步生成的读超时必须覆盖整次生成，一旦等满上限，
     * 必须给出可区分的原因码，而不是笼统的「提交结果未知」。
     */
    @Test
    void callTimeoutCarriesItsOwnReasonCode() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/gemini-3.1-flash-image:generateContent", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(SLOW_RESPONSE_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            GoogleNanoBananaClient impatient = new GoogleNanoBananaClient(new ObjectMapper(),
                    IMPATIENT_TIMEOUT, IMPATIENT_TIMEOUT);
            assertThatThrownBy(() -> impatient.generate("test-key",
                    GoogleNanoBananaClient.DEFAULT_MODEL,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "draw", "1:1",
                    List.of()))
                    .isInstanceOfSatisfying(GoogleNanoBananaClient.Uncertain.class,
                            failure -> assertThat(failure.reasonCode())
                                    .isEqualTo(ProviderFailureCodes.CALL_TIMEOUT));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 429 属于「语义不明确的上游状态」：请求可能已被受理，码必须能区分于超时与协议错误。
     */
    @Test
    void uncertainUpstreamStatusCarriesItsOwnReasonCode() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/gemini-3.1-flash-image:generateContent", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        try {
            GoogleNanoBananaClient client = new GoogleNanoBananaClient(new ObjectMapper());
            assertThatThrownBy(() -> client.generate("test-key",
                    GoogleNanoBananaClient.DEFAULT_MODEL,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "draw", "1:1",
                    List.of()))
                    .isInstanceOfSatisfying(GoogleNanoBananaClient.Uncertain.class,
                            failure -> assertThat(failure.reasonCode())
                                    .isEqualTo(ProviderFailureCodes.SUBMISSION_UNKNOWN));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void distinguishesDefiniteRejectionFromUnknownAndDoesNotFollowRedirects() throws IOException {
        AtomicInteger status = new AtomicInteger(400);
        AtomicInteger redirected = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/gemini-3.1-flash-image:generateContent", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (status.get() == 302) exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            GoogleNanoBananaClient client = new GoogleNanoBananaClient(new ObjectMapper());
            assertThatThrownBy(() -> client.generate("test-key", GoogleNanoBananaClient.DEFAULT_MODEL,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "draw", "1:1",
                    List.of()))
                    .isInstanceOf(GoogleNanoBananaClient.Rejected.class);
            status.set(429);
            assertThatThrownBy(() -> client.generate("test-key", GoogleNanoBananaClient.DEFAULT_MODEL,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "draw", "1:1",
                    List.of()))
                    .isInstanceOf(GoogleNanoBananaClient.Uncertain.class);
            status.set(302);
            assertThatThrownBy(() -> client.generate("test-key", GoogleNanoBananaClient.DEFAULT_MODEL,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "draw", "1:1",
                    List.of()))
                    .isInstanceOf(GoogleNanoBananaClient.Uncertain.class);
            assertThat(redirected).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    /** 配置的模型名必须出现在请求路径里，而不是固定使用内置默认。 */
    @Test
    void configuredModelNameIsUsedInTheRequestPath() throws IOException {
        AtomicInteger matched = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/gemini-2.5-flash-image:generateContent", exchange -> {
            exchange.getRequestBody().readAllBytes();
            matched.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        server.start();
        try {
            GoogleNanoBananaClient client = new GoogleNanoBananaClient(new ObjectMapper());
            assertThatThrownBy(() -> client.generate("test-key", "gemini-2.5-flash-image",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "draw", "1:1", List.of()))
                    .isInstanceOf(GoogleNanoBananaClient.Rejected.class);
            assertThat(matched).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void orderedReferencesBecomeOrderedInlineDataParts() throws IOException {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/gemini-3.1-flash-image:generateContent", exchange -> {
            var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            var parts = body.path("contents").path(0).path("parts");
            assertThat(parts.size()).isEqualTo(3);
            assertThat(parts.path(0).path("text").asText()).isEqualTo("compose");
            assertThat(body.path("generationConfig").path("responseFormat").path("image")
                    .path("imageSize").asText()).isEqualTo("4K");
            assertThat(Base64.getDecoder().decode(
                    parts.path(1).path("inlineData").path("data").asText()))
                    .isEqualTo("FIRST".getBytes(StandardCharsets.UTF_8));
            assertThat(Base64.getDecoder().decode(
                    parts.path(2).path("inlineData").path("data").asText()))
                    .isEqualTo("SECOND".getBytes(StandardCharsets.UTF_8));
            calls.incrementAndGet();
            byte[] response = ("{\"candidates\":[{\"content\":{\"parts\":[{"
                    + "\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""
                    + Base64.getEncoder().encodeToString(png)
                    + "\"}}]}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            GoogleNanoBananaClient client = new GoogleNanoBananaClient(mapper);
            try (var generated = client.generate("test-key",
                    GoogleNanoBananaClient.DEFAULT_MODEL,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "compose", "1:1",
                    "4K",
                    List.of(
                            new GoogleNanoBananaClient.InputImage(
                                    "FIRST".getBytes(StandardCharsets.UTF_8), "image/png"),
                            new GoogleNanoBananaClient.InputImage(
                                    "SECOND".getBytes(StandardCharsets.UTF_8), "image/jpeg")))) {
                assertThat(generated.stream().readAllBytes()).isEqualTo(png);
            }
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void referenceCountBeyondPublishedCapabilityIsRejectedBeforeNetwork() {
        GoogleNanoBananaClient client = new GoogleNanoBananaClient(new ObjectMapper());
        List<GoogleNanoBananaClient.InputImage> references = java.util.stream.IntStream
                .range(0, 15)
                .mapToObj(index -> new GoogleNanoBananaClient.InputImage(
                        new byte[] {(byte) index}, "image/png"))
                .toList();
        assertThatThrownBy(() -> client.generate("key", GoogleNanoBananaClient.DEFAULT_MODEL,
                null, "draw", "1:1", references))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
