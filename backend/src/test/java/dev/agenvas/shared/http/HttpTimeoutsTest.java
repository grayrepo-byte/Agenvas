package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Dns;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real loopback sockets verify timeout behavior; no external service is contacted. */
class HttpTimeoutsTest {
    private HttpServer server;
    private ExecutorService handlers;

    @BeforeEach
    void start() throws IOException {
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    void configuringAnotherInvocationDoesNotChangeAnEarlierReadLimit() throws IOException {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1_250);
                exchange.sendResponseHeaders(200, 1);
                exchange.getResponseBody().write(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        var base = PinnedHttpClients.pinned(Dns.SYSTEM, Duration.ofSeconds(1),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        var impatient = new HttpTimeouts(1, 1, 1, 3).configure(base);
        var patient = new HttpTimeouts(1, 2, 2, 3).configure(base);
        Request request = request("/slow");

        assertThatThrownBy(() -> {
            try (var response = impatient.newCall(request).execute()) {
                response.body().bytes();
            }
        }).isInstanceOf(IOException.class)
                .satisfies(failure -> assertThat(OutboundTimeouts.isTimeout(failure)).isTrue());
        try (var response = patient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().bytes()).containsExactly((byte) 1);
        }
    }

    @Test
    void wholeCallDeadlineDoesNotRestartWhenResponseBytesKeepArriving() throws IOException {
        server.createContext("/progress", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 20);
                for (int i = 0; i < 20; i++) {
                    exchange.getResponseBody().write(i);
                    exchange.getResponseBody().flush();
                    Thread.sleep(100);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException canceled) {
                // The client closes the socket when its whole-call deadline is reached.
            } finally {
                exchange.close();
            }
        });
        var base = PinnedHttpClients.pinned(Dns.SYSTEM, Duration.ofSeconds(1),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        var bounded = new HttpTimeouts(1, 1, 1, 1).configure(base);

        assertThatThrownBy(() -> {
            try (var response = bounded.newCall(request("/progress")).execute()) {
                response.body().bytes();
            }
        }).isInstanceOf(IOException.class)
                .satisfies(failure -> assertThat(OutboundTimeouts.isTimeout(failure)).isTrue());
    }

    private Request request(String path) {
        return new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + path).build();
    }
}
