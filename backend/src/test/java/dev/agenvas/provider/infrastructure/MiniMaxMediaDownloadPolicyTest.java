package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Dns;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class MiniMaxMediaDownloadPolicyTest {
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger PRIVATE_REDIRECT_HITS = new AtomicInteger();
    private static final URI ORIGIN = URI.create("http://127.0.0.1:"
            + SERVER.getAddress().getPort());
    private final MiniMaxMediaDownloadPolicy downloads = new MiniMaxMediaDownloadPolicy();

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Test
    void signedResultPathsRetainEncodedCharacters() {
        new MiniMaxMediaDownloadPolicy()
                .validate(URI.create("http://localhost:8090/video%20clip.mp4?signature=synthetic"));
    }

    @Test
    void resultDownloadsAcceptAnyHttpHostAndValidPort() {
        var policy = new MiniMaxMediaDownloadPolicy();
        for (String url : java.util.List.of("http://localhost:8090/result.mp4", "https://proxy.invalid:8443/result.mp4",
                "http://169.254.169.254/result.mp4", "http://[::1]/result.mp4"))
            policy.validate(URI.create(url));
        for (String url : java.util.List.of("file:///tmp/result.mp4", "https://user@localhost/result.mp4",
                "https://localhost/../result.mp4",
                "https://localhost/result.mp4#fragment", "http://localhost:0/result.mp4"))
            assertThatThrownBy(() -> policy.validate(URI.create(url)))
                    .isInstanceOf(MiniMaxMediaDownloadPolicy.Rejected.class);
    }

    @Test
    void rejectsMalformedPathsSchemesAndCredentials() {
        for (String url : List.of("file:///video.mp4",
                ORIGIN + "/../video.mp4", "http://user@127.0.0.1:"
                        + SERVER.getAddress().getPort() + "/video.mp4")) {
            assertThatThrownBy(() -> downloads.download(URI.create(url),
                    new ByteArrayOutputStream(), 1024))
                    .isInstanceOf(MiniMaxMediaDownloadPolicy.Rejected.class);
        }
    }

    @Test
    void blocksRedirectsWithoutContactingTheirTarget() {
        assertThatThrownBy(() -> downloads.download(ORIGIN.resolve("/redirect"),
                new ByteArrayOutputStream(), 1024))
                .isInstanceOf(MiniMaxMediaDownloadPolicy.Rejected.class)
                .hasMessage("PROVIDER_RESULT_URL_INVALID");
        assertThat(PRIVATE_REDIRECT_HITS).hasValue(0);
    }

    @Test
    void boundsBytesAndChecksMimeAndMp4Signature() {
        assertThatThrownBy(() -> downloads.download(ORIGIN.resolve("/oversize"),
                new ByteArrayOutputStream(), 16))
                .isInstanceOf(MiniMaxMediaDownloadPolicy.Rejected.class)
                .hasMessage("PROVIDER_RESULT_TOO_LARGE");
        assertThatThrownBy(() -> downloads.download(ORIGIN.resolve("/bad-mime"),
                new ByteArrayOutputStream(), 1024))
                .isInstanceOf(MiniMaxMediaDownloadPolicy.Rejected.class)
                .hasMessage("PROVIDER_PROTOCOL_INVALID");
        assertThatThrownBy(() -> downloads.download(ORIGIN.resolve("/bad-mp4"),
                new ByteArrayOutputStream(), 1024))
                .isInstanceOf(MiniMaxMediaDownloadPolicy.Rejected.class)
                .hasMessage("PROVIDER_PROTOCOL_INVALID");
    }

    @Test
    void acceptsOnlyBoundedMp4BytesFromAllowedSource() {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        long size = downloads.download(ORIGIN.resolve("/good"), result, 1024);
        assertThat(size).isEqualTo(16);
        assertThat(result.toByteArray()).hasSize(16);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:"
                        + server.getAddress().getPort() + "/private-target");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/private-target", exchange -> {
                PRIVATE_REDIRECT_HITS.incrementAndGet();
                reply(exchange, 200, "video/mp4", "bad".getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/oversize", exchange -> reply(exchange, 200,
                    "video/mp4", new byte[17]));
            server.createContext("/bad-mime", exchange -> reply(exchange, 200,
                    "text/html", validHeader()));
            server.createContext("/bad-mp4", exchange -> reply(exchange, 200,
                    "video/mp4", "not an mp4 file".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/good", exchange -> {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                reply(exchange, 200, "video/mp4", validHeader());
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static byte[] validHeader() {
        return new byte[]{0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                0, 0, 0, 0};
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status,
            String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
