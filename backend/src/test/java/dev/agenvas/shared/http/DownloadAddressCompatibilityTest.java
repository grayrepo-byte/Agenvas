package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

class DownloadAddressCompatibilityTest {
    @Test
    void localSourceDownloadsWorkWithLiteralAndDnsLoopbackAddresses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source.json", exchange -> {
            byte[] bytes = "synthetic catalogue".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new PublicDownloadHttpClients().create(Duration.ofSeconds(1),
                    Duration.ofSeconds(1), Duration.ofSeconds(2));
            for (String host : List.of("127.0.0.1", "catalogue.invalid")) {
                var local = client.newBuilder().dns(new PublicDownloadDns(
                        ignored -> List.of(InetAddress.getByName("127.0.0.1")))).build();
                try (var response = local.newCall(new Request.Builder()
                        .url("http://" + host + ":" + server.getAddress().getPort() + "/source.json").build())
                        .execute()) {
                    assertThat(response.code()).isEqualTo(200);
                    assertThat(response.body().string()).isEqualTo("synthetic catalogue");
                }
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolverAnswersKeepFakeIpPrivateAndSpecialAddresses() throws Exception {
        var answers = List.of(InetAddress.getByName("198.18.1.3"), InetAddress.getByName("10.0.0.1"),
                InetAddress.getByName("169.254.169.254"), InetAddress.getByName("fd00:ec2::254"));
        assertThat(new PublicDownloadDns(ignored -> answers).lookup("source.invalid"))
                .containsExactlyElementsOf(answers);
    }
}
