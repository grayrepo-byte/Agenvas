package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KlingMediaDownloadPolicyTest {
    private HttpServer server;
    private URI origin;
    private KlingMediaDownloadPolicy downloads;
    private int requests;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests++;
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            byte[] bytes = mp4();
            int status = 200;
            String type = "video/mp4";
            switch (exchange.getRequestURI().getPath()) {
                case "/redirect" -> { status = 302; exchange.getResponseHeaders().set("Location", "/mp4"); }
                case "/expired" -> status = 403;
                case "/png" -> { type = "image/png"; bytes = new byte[] {(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0}; }
                case "/mismatch" -> type = "image/png";
                case "/oversize" -> bytes = new byte[17];
                case "/html" -> type = "text/html";
            }
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        downloads = KlingMediaDownloadPolicy.forLoopbackTest(origin);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void onlyAllowsOfficialHttpsHostsAndExactSubdomainBoundaries() {
        var official = new KlingMediaDownloadPolicy();
        official.validate(URI.create("https://h1.inkwai.com/output.png?signature=synthetic"));
        official.validate(URI.create("https://cdn.klingai.com/output.mp4"));
        for (String invalid : java.util.List.of("https://h2.inkwai.com/a.mp4", "https://evil-klingai.com/a.mp4",
                "https://cdn.klingai.com.attacker.example/a.mp4", "http://cdn.klingai.com/a.mp4",
                "https://cdn.klingai.com:444/a.mp4", "https://user@cdn.klingai.com/a.mp4",
                "https://cdn.klingai.com/a.mp4#fragment", "https://cdn.klingai.com/../a.mp4", "https://127.0.0.1/a.mp4"))
            assertThatThrownBy(() -> official.validate(URI.create(invalid))).isInstanceOf(KlingMediaDownloadPolicy.Rejected.class);
    }

    @Test void downloadsWithNoCredentialAndChecksEachMediaSignature() {
        var output = new ByteArrayOutputStream();
        assertThat(downloads.download(origin.resolve("/mp4"), output, true, 16)).isEqualTo("video/mp4");
        assertThat(output.toByteArray()).isEqualTo(mp4());
        assertThat(downloads.download(origin.resolve("/png"), new ByteArrayOutputStream(), false, 16)).isEqualTo("image/png");
        assertThatThrownBy(() -> downloads.download(origin.resolve("/mismatch"), new ByteArrayOutputStream(), false, 16))
                .isInstanceOf(KlingMediaDownloadPolicy.Rejected.class);
    }

    @Test void refusesRedirectsOversizedBodiesAndWrongMimeAndDistinguishesExpiredResults() {
        for (String route : java.util.List.of("/redirect", "/oversize", "/html"))
            assertThatThrownBy(() -> downloads.download(origin.resolve(route), new ByteArrayOutputStream(), true, 16))
                    .isInstanceOf(KlingMediaDownloadPolicy.Rejected.class);
        assertThat(requests).isEqualTo(3);
        assertThatThrownBy(() -> downloads.download(origin.resolve("/expired"), new ByteArrayOutputStream(), true, 16))
                .isInstanceOf(KlingMediaDownloadPolicy.Expired.class);
    }

    private byte[] mp4() { return new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0}; }
}
