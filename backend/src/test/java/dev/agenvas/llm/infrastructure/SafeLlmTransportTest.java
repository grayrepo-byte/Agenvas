package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Outbound redirect, origin, path and DNS checks run before a credential can leave the host. */
class SafeLlmTransportTest {
    private static final long SLOW_RESPONSE_SECONDS = 46;

    private HttpServer server;
    private final AtomicInteger completions = new AtomicInteger();
    private final AtomicInteger redirects = new AtomicInteger();
    private final LlmEndpointPolicy localPolicy = new LlmEndpointPolicy();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            completions.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/leak");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/leak", exchange -> {
            redirects.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void waitsForAContinuationResponseBeyondTheOldReadDeadlineWithoutResubmitting() throws Exception {
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", exchange -> {
            completions.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                new CountDownLatch(1).await(SLOW_RESPONSE_SECONDS, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        OkHttpClient outer = new OkHttpClient.Builder()
                .addInterceptor(new SafeLlmTransport(base, localPolicy).interceptor()).build();
        try (var response = outer.newCall(post(base + "/chat/completions")).execute()) {
            assertThat(response.code()).isEqualTo(200);
        }
        assertThat(completions).hasValue(1);
    }

    @Test
    void rejectsRedirectWithoutSendingAuthorizationToItsTarget() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        SafeLlmTransport transport = new SafeLlmTransport(base, localPolicy);
        OkHttpClient outer = new OkHttpClient.Builder()
                .addInterceptor(transport.interceptor()).build();
        Request request = post(base + "/chat/completions");
        assertThatThrownBy(() -> { try (var ignored = outer.newCall(request).execute()) { } })
                .isInstanceOf(IOException.class).hasMessageContaining("redirect");
        assertThat(completions).hasValue(1);
        assertThat(redirects).hasValue(0);
    }

    @Test
    void rejectsDifferentPathAndPortBeforeSendingARequest() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        SafeLlmTransport transport = new SafeLlmTransport(base, localPolicy);
        OkHttpClient outer = new OkHttpClient.Builder()
                .addInterceptor(transport.interceptor()).build();
        assertThatThrownBy(() -> outer.newCall(post(base + "/models")).execute())
                .isInstanceOf(IOException.class).hasMessageContaining("target differs");
        assertThatThrownBy(() -> outer.newCall(post("http://127.0.0.1:1/v1/chat/completions"))
                .execute()).isInstanceOf(IOException.class).hasMessageContaining("target differs");
        assertThat(completions).hasValue(0);
    }

    private Request post(String url) {
        return new Request.Builder().url(url)
                .header("Authorization", "Bearer never-leak-this")
                .post(RequestBody.create("{}", okhttp3.MediaType.get("application/json")))
                .build();
    }
}
