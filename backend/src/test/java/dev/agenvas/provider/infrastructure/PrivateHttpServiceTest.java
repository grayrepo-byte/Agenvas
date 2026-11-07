package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrivateHttpServiceTest {
    @Test
    void resultRedirectsCanUseTheSelectedHttpService() {
        var selected = java.net.URI.create("http://192.168.1.50:8188");
        var current = java.net.URI.create("https://cdn.example.com/image.png");
        assertThat(ComfyUiClient.checkedRedirect(selected, current,
                "http://192.168.1.50:8188/result.png", okhttp3.Dns.SYSTEM))
                .isEqualTo(java.net.URI.create("http://192.168.1.50:8188/result.png"));
    }

    @Test
    void selectedMediaServicesAcceptPublicHttpWithoutAnAllowlist() {
        String origin = "http://8.8.8.8:8188";
        assertThat(ComfyUiEndpoint.checked(origin).toString()).isEqualTo(origin);
        assertThat(OpenAiImage2Client.apiEndpoint(origin + "/v1", "images/generations").toString())
                .isEqualTo(origin + "/v1/images/generations");
        assertThat(GoogleNanoBananaClient.apiEndpoint(origin, "local-model").getScheme()).isEqualTo("http");
        assertThat(RunningHubClient.validatedOrigin(origin)).isEqualTo(origin);
        assertThat(GoogleVideoClient.fileName("http://generativelanguage.googleapis.com/v1beta/files/synthetic"))
                .isEqualTo("files/synthetic");
    }

    @Test
    void equivalentIpv6HostsKeepSameOriginHttpOutputRedirects() {
        var configured = java.net.URI.create("http://[fd12:0:0:0:0:0:0:1]:8188");
        var current = java.net.URI.create("http://[fd12::1]:8188/view?filename=image.png");
        assertThat(ComfyUiClient.checkedRedirect(configured, current, "/output/image.png", okhttp3.Dns.SYSTEM))
                .isEqualTo(java.net.URI.create("http://[fd12::1]:8188/output/image.png"));
    }

    @Test
    void selectedMediaServicesAcceptPrivateIpv4AndUlaHttp() {
        assertThat(ComfyUiEndpoint.checked("http://192.168.1.50").toString()).isEqualTo("http://192.168.1.50");
        for (String host : List.of("192.168.1.50", "[fd12::1]", "[fd12:0:0:0:0:0:0:1]")) {
            String origin = "http://" + host + ":8188";
            assertThat(ComfyUiEndpoint.checked(origin + "/proxy").toString()).isEqualTo(origin + "/proxy");
            assertThat(OpenAiImage2Client.apiEndpoint(origin + "/v1", "images/generations").toString())
                    .isEqualTo(origin + "/v1/images/generations");
            assertThat(GoogleNanoBananaClient.apiEndpoint(origin + "/v1beta", "local-model").toString())
                    .isEqualTo(origin + "/v1beta/models/local-model:generateContent");
            assertThat(RunningHubClient.validatedOrigin(origin)).isEqualTo(origin);
        }
    }

    @Test
    void configuredServicesAcceptSpecialLiteralAddresses() {
        for (String host : List.of("169.254.169.254", "[fd00:ec2::254]",
                "[fd20:ce::254]", "[fe80::1]", "[fec0::1]", "[::ffff:127.0.0.1]")) {
            String origin = "http://" + host + ":8188";
            assertThat(ComfyUiEndpoint.checked(origin)).isNotNull();
            assertThat(OpenAiImage2Client.apiEndpoint(origin, "images/generations")).isNotNull();
            assertThat(GoogleNanoBananaClient.apiEndpoint(origin, "local-model")).isNotNull();
            assertThat(RunningHubClient.validatedOrigin(origin)).isEqualTo(origin);
        }
    }

}
