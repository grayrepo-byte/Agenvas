package dev.agenvas.settings.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.settings.application.LocalServiceDiscoveryProperties;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Runtime;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Service;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Status;
import dev.agenvas.shared.http.DebugHttpCapture;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import okhttp3.Dns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Local synthetic HTTP only; this is not verification of a real Ollama or ComfyUI installation. */
class LocalServiceProbeHttpTest {
    private HttpServer server;
    private LocalServiceProbe probe;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        probe = new LocalServiceProbe(
                new LocalServiceDiscoveryProperties(true, LocalServiceDiscoveryProperties.Mode.NATIVE),
                Dns.SYSTEM, () -> false, () -> "Mac OS X", dns -> {
                    var client = PinnedHttpClients.pinnedUncaptured(dns, Duration.ofSeconds(1), Duration.ofSeconds(2),
                            Duration.ofSeconds(2));
                    // Only the test transport rewrites the fixed service port to an isolated ephemeral fixture.
                    return request -> client.newCall(request.newBuilder().url(request.url().newBuilder()
                            .port(server.getAddress().getPort()).build()).build());
                });
    }
    @AfterEach
    void stop() {
        if (server != null)
            server.stop(0);
    }

    @Test
    void decodedChunkedGzipBudgetRejectsLargeResponse() throws Exception {
        String large = "{\"version\":\"" + "x".repeat(32769) + "\"}";
        var encoded = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(encoded)) {
            gzip.write(large.getBytes(StandardCharsets.UTF_8));
        }
        server.createContext("/api/version", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(encoded.toByteArray());
            }
        });
        assertThat(probe.discover(Service.OLLAMA, Runtime.NATIVE, new AtomicReference<>(), budget()).status())
                .isEqualTo(Status.UNEXPECTED_RESPONSE);
    }

    @Test
    void activeDebugScopeNeverCapturesServiceMetadataOrSendsCredentials() {
        server.createContext("/system_stats", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Connection")).isEqualTo("close");
            byte[] reply = "{\"system\":{\"os\":\"linux\",\"python_version\":\"3\",\"argv\":[\"/private/path\",\"--secret=hidden\"]},\"devices\":[]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            try (var body = exchange.getResponseBody()) {
                body.write(reply);
            }
        });
        List<DebugHttpCapture.Exchange> captured = new ArrayList<>();
        try (var ignored = DebugHttpCapture.openLlm(exchanges -> {
            captured.clear();
            captured.addAll(exchanges);
        })) {
            var found = probe.discover(Service.COMFYUI, Runtime.NATIVE, new AtomicReference<>(), budget());
            assertThat(found.status()).isEqualTo(Status.FOUND);
            assertThat(found.endpoint()).isEqualTo("http://127.0.0.1:8188");
        }
        assertThat(captured).isEmpty();
    }

    @Test
    void redirectsAndRetryAfterDoNotCreateAdditionalRequests() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/api/version", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere");
            exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        assertThat(probe.discover(Service.OLLAMA, Runtime.NATIVE, new AtomicReference<>(), budget()).status())
                .isEqualTo(Status.UNEXPECTED_RESPONSE);
        assertThat(requests).hasValue(1);
    }

    @Test
    void noResponseBeforeHttpDeadlineReportsTimeout() {
        server.createContext("/api/version", exchange -> {
            try {
                Thread.sleep(2300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        long started = System.nanoTime();
        assertThat(probe.discover(Service.OLLAMA, Runtime.NATIVE, new AtomicReference<>(), budget()).status())
                .isEqualTo(Status.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }
    private static LocalServiceProbe.Budget budget() {
        return new LocalServiceProbe.Budget(Duration.ofSeconds(3));
    }
}
