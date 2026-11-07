package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.provider.domain.NativeMediaFailure;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class WanMediaDownloadPolicyTest {
    @Test void resultDownloadsAcceptAnyHttpHostAndValidPort() {
        var policy = new WanMediaDownloadPolicy();
        for (String url : java.util.List.of("http://localhost:8090/result.mp4", "https://proxy.invalid:8443/result.mp4",
                "http://169.254.169.254/result.mp4", "http://[::1]/result.mp4")) policy.validate(URI.create(url));
        for (String url : java.util.List.of("file:///tmp/result.mp4", "https://user@localhost/result.mp4", "https://localhost/../result.mp4",
                "https://localhost/result.mp4#fragment", "http://localhost:0/result.mp4"))
            assertThatThrownBy(() -> policy.validate(URI.create(url))).isInstanceOf(NativeMediaFailure.class);
    }
    @Test void downloadHasNoCredentialsDoesNotFollowRedirectsAndMagicChecksMime() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        server.createContext("/image", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            exchange.getResponseHeaders().set("Content-Type", "image/png"); exchange.sendResponseHeaders(200, png.length);
            try (var sink = exchange.getResponseBody()) { sink.write(png); }
        });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", "/image"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/expired", exchange -> { exchange.sendResponseHeaders(403, -1); exchange.close(); });
        server.start();
        try {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var policy = new WanMediaDownloadPolicy(); var sink = new ByteArrayOutputStream();
            assertThat(policy.download(origin.resolve("/image"), sink, false)).isEqualTo("image/png");
            assertThat(sink.toByteArray()).isEqualTo(png);
            assertThatThrownBy(() -> policy.download(origin.resolve("/redirect"), new ByteArrayOutputStream(), false)).hasMessage("PROVIDER_RESULT_URL_INVALID");
            assertThatThrownBy(() -> policy.download(origin.resolve("/expired"), new ByteArrayOutputStream(), false)).isInstanceOf(WanMediaDownloadPolicy.Expired.class);
            assertThatThrownBy(() -> policy.download(origin.resolve("/image"), new ByteArrayOutputStream(), true)).hasMessage("PROVIDER_PROTOCOL_INVALID");
        } finally { server.stop(0); }
    }
}
