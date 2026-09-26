package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Definite rejection, uncertain submission and redirect boundaries of the fixed API. */
class GoogleNanoBananaClientTest {
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
                    "http://127.0.0.1:" + server.getAddress().getPort(), "draw", "1:1", null, null))
                    .isInstanceOf(GoogleNanoBananaClient.Rejected.class);
            status.set(429);
            assertThatThrownBy(() -> client.generate("test-key", GoogleNanoBananaClient.DEFAULT_MODEL,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "draw", "1:1", null, null))
                    .isInstanceOf(GoogleNanoBananaClient.Uncertain.class);
            status.set(302);
            assertThatThrownBy(() -> client.generate("test-key", GoogleNanoBananaClient.DEFAULT_MODEL,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "draw", "1:1", null, null))
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
                    "draw", "1:1", null, null))
                    .isInstanceOf(GoogleNanoBananaClient.Rejected.class);
            assertThat(matched).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
