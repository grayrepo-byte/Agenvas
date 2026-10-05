package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 本地假服务校验共享客户端的固定约束，不构成对任何真实 Provider 的验证。 */
class PinnedHttpClientsTest {

    private HttpServer server;
    private OkHttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = PinnedHttpClients.pinned(Dns.SYSTEM, Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /**
     * OkHttp 对 503 会读 {@code Retry-After}，值为 0 时重发一次原请求。提交类调用非幂等，
     * 这条路径必须堵死：没有工厂里的网络拦截器时，本用例会看到两次请求。
     */
    @Test
    void retryAfterZeroDoesNotResubmitTheRequest() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/submit", exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "0");
            respond(exchange, 503, "unavailable");
        });

        try (Response response = client.newCall(submit()).execute()) {
            assertThat(response.code()).isEqualTo(503);
            // 拦截器只重建响应、不动响应体，调用方仍应读到原正文。
            assertThat(response.body().string()).isEqualTo("unavailable");
        }
        assertThat(attempts).hasValue(1);
    }

    @Test
    void doesNotFollowRedirects() throws IOException {
        AtomicInteger reached = new AtomicInteger();
        server.createContext("/target", exchange -> {
            reached.incrementAndGet();
            respond(exchange, 200, "private bytes");
        });
        server.createContext("/submit", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/target");
            respond(exchange, 302, "redirect");
        });

        try (Response response = client.newCall(submit()).execute()) {
            assertThat(response.code()).isEqualTo(302);
        }
        assertThat(reached).hasValue(0);
    }

    private Request submit() {
        return new Request.Builder()
                .url("http://127.0.0.1:" + server.getAddress().getPort() + "/submit")
                .post(RequestBody.create("{}".getBytes(StandardCharsets.UTF_8),
                        MediaType.parse("application/json")))
                .build();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
