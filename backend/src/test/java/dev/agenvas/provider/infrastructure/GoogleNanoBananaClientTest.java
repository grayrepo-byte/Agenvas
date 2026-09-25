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
    void rejectsCustomOrigins() {
        assertThatThrownBy(() -> new GoogleNanoBananaClient(new ObjectMapper(),
                URI.create("https://example.com")))
                .isInstanceOf(IllegalArgumentException.class);
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
            GoogleNanoBananaClient client = new GoogleNanoBananaClient(new ObjectMapper(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThatThrownBy(() -> client.generate("test-key", "draw", "1:1", null, null))
                    .isInstanceOf(GoogleNanoBananaClient.Rejected.class);
            status.set(429);
            assertThatThrownBy(() -> client.generate("test-key", "draw", "1:1", null, null))
                    .isInstanceOf(GoogleNanoBananaClient.Uncertain.class);
            status.set(302);
            assertThatThrownBy(() -> client.generate("test-key", "draw", "1:1", null, null))
                    .isInstanceOf(GoogleNanoBananaClient.Uncertain.class);
            assertThat(redirected).hasValue(0);
        } finally {
            server.stop(0);
        }
    }
}
