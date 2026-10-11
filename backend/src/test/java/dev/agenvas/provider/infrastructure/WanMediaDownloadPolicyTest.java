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
    @Test void resultHostAdmissionUsesExactOfficialBucketsAndSecureUris() {
        var policy = new WanMediaDownloadPolicy();
        policy.validate(URI.create("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/result.png?Expires=synthetic&Signature=synthetic"));
        policy.validate(URI.create("https://dashscope-result-sh.oss-accelerate.aliyuncs.com/result.mp4"));
        for (String invalid : List.of("http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.png", "https://arbitrary.oss-cn-beijing.aliyuncs.com/a.png",
                "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com.untrusted.example/a.png", "https://user@dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.png",
                "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com:444/a.png", "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/../a.png",
                "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.png#fragment", "https://127.0.0.1/a.png"))
            assertThatThrownBy(() -> policy.validate(URI.create(invalid))).isInstanceOf(NativeMediaFailure.class);
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
            var policy = WanMediaDownloadPolicy.forLoopbackTest(origin); var sink = new ByteArrayOutputStream();
            assertThat(policy.download(origin.resolve("/image"), sink, false)).isEqualTo("image/png");
            assertThat(sink.toByteArray()).isEqualTo(png);
            assertThatThrownBy(() -> policy.download(origin.resolve("/redirect"), new ByteArrayOutputStream(), false)).hasMessage("PROVIDER_RESULT_URL_INVALID");
            assertThatThrownBy(() -> policy.download(origin.resolve("/expired"), new ByteArrayOutputStream(), false)).isInstanceOf(WanMediaDownloadPolicy.Expired.class);
            assertThatThrownBy(() -> policy.download(origin.resolve("/image"), new ByteArrayOutputStream(), true)).hasMessage("PROVIDER_PROTOCOL_INVALID");
        } finally { server.stop(0); }
    }
}
