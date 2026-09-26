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

class ArkMediaDownloadPolicyTest {
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger PRIVATE_REDIRECT_HITS = new AtomicInteger();
    private static final URI ORIGIN = URI.create("http://127.0.0.1:"
            + SERVER.getAddress().getPort());
    private final ArkMediaDownloadPolicy downloads = ArkMediaDownloadPolicy.forLoopbackTest(ORIGIN);

    @AfterAll static void stop() { SERVER.stop(0); }

    @Test void rejectsUnapprovedHostsSchemesAndCredentials() {
        for (String url : List.of("http://169.254.169.254/video.mp4",
                "https://127.0.0.1/video.mp4", "http://localhost/video.mp4",
                ORIGIN + "/../video.mp4", "http://user@127.0.0.1:"
                        + SERVER.getAddress().getPort() + "/video.mp4")) {
            assertThatThrownBy(() -> downloads.download(URI.create(url),
                    new ByteArrayOutputStream(), 1024))
                    .isInstanceOf(ArkMediaDownloadPolicy.Rejected.class);
        }
    }

    @Test void blocksRedirectsWithoutContactingTheirTarget() {
        assertThatThrownBy(() -> downloads.download(ORIGIN.resolve("/redirect"),
                new ByteArrayOutputStream(), 1024))
                .isInstanceOf(ArkMediaDownloadPolicy.Rejected.class)
                .hasMessage("ARK_MEDIA_REDIRECT_REJECTED");
        assertThat(PRIVATE_REDIRECT_HITS).hasValue(0);
    }

    @Test void boundsBytesAndChecksMimeAndMp4Signature() {
        assertThatThrownBy(() -> downloads.download(ORIGIN.resolve("/oversize"),
                new ByteArrayOutputStream(), 16))
                .isInstanceOf(ArkMediaDownloadPolicy.Rejected.class)
                .hasMessage("ARK_MEDIA_TOO_LARGE");
        assertThatThrownBy(() -> downloads.download(ORIGIN.resolve("/bad-mime"),
                new ByteArrayOutputStream(), 1024))
                .isInstanceOf(ArkMediaDownloadPolicy.Rejected.class)
                .hasMessage("ARK_MEDIA_MIME_REJECTED");
        assertThatThrownBy(() -> downloads.download(ORIGIN.resolve("/bad-mp4"),
                new ByteArrayOutputStream(), 1024))
                .isInstanceOf(ArkMediaDownloadPolicy.Rejected.class)
                .hasMessage("ARK_MEDIA_INVALID_MP4");
    }

    @Test void refusesPrivateDnsAnswersEvenForOfficialName() {
        Dns privateAnswer = hostname -> List.of(InetAddress.getByName("10.0.0.2"));
        ArkMediaDownloadPolicy official = new ArkMediaDownloadPolicy(
                URI.create("https://ark-acg-cn-beijing.tos-cn-beijing.volces.com"),
                privateAnswer, false);
        assertThatThrownBy(() -> official.download(URI.create(
                "https://ark-acg-cn-beijing.tos-cn-beijing.volces.com/a.mp4"),
                new ByteArrayOutputStream(), 1024))
                .isInstanceOf(ArkMediaDownloadPolicy.TechnicalFailure.class);
        for (String address : List.of("100.64.1.2", "192.0.2.3", "203.0.113.4",
                "::1", "169.254.169.254")) {
            Dns answer = hostname -> List.of(InetAddress.getByName(address));
            assertThatThrownBy(() -> FixedCloudDns.checked(answer, false)
                    .lookup("api.openai.com"))
                    .isInstanceOf(UnknownHostException.class);
        }
    }

    /** Private and proxy fake-ip answers are reachable targets for a self-hosted deployment. */
    @Test void acceptsPrivateAndFakeIpAnswers() throws Exception {
        for (String address : List.of("10.0.0.2", "192.168.1.50", "198.18.1.2",
                "fc00::1", "2001:2::59")) {
            Dns answer = hostname -> List.of(InetAddress.getByName(address));
            assertThat(FixedCloudDns.checked(answer, false).lookup("api.openai.com"))
                    .as("self-hosted address %s", address)
                    .hasSize(1);
        }
    }

    @Test void acceptsOnlyBoundedMp4BytesFromAllowedSource() {
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
            server.createContext("/good", exchange -> reply(exchange, 200,
                    "video/mp4", validHeader()));
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static byte[] validHeader() {
        return new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                0, 0, 0, 0};
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status,
            String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
