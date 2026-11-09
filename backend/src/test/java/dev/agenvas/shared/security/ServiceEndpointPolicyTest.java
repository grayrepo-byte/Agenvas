package dev.agenvas.shared.security;

import static org.assertj.core.api.Assertions.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceEndpointPolicyTest {
    @Test void keepsEveryDnsAnswerIncludingPrivateFakeIpLoopbackAndMetadata() throws Exception {
        var answers = List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("198.18.1.3"),
                InetAddress.getByName("127.0.0.1"), InetAddress.getByName("169.254.169.254"),
                InetAddress.getByName("fd00:ec2::254"));
        var dns = ServiceEndpointPolicy.dns(URI.create("http://model.invalid:8188"), ignored -> answers);
        assertThat(dns.lookup("model.invalid")).containsExactlyElementsOf(answers);
        assertThatThrownBy(() -> dns.lookup("different.invalid")).isInstanceOf(UnknownHostException.class);
    }

    @Test void resultDownloadsAcceptAnyHttpHostAndPortWithoutDnsPreflight() throws Exception {
        for (String target : List.of("http://localhost:9000/result", "https://10.0.0.1:8443/result",
                "http://169.254.169.254/result", "http://[::1]/result", "http://cdn.invalid:8080/result")) {
            var url = URI.create(target);
            var dns = ServiceEndpointPolicy.downloadDns(url, ignored -> List.of(InetAddress.getByName("198.18.1.3")));
            assertThat(dns.lookup(okhttp3.HttpUrl.get(target).host())).extracting(InetAddress::getHostAddress)
                    .containsExactly("198.18.1.3");
        }
        for (String target : List.of("file:///etc/hosts", "https://user:secret@localhost/result", "http://localhost:0/result")) {
            assertThatThrownBy(() -> ServiceEndpointPolicy.downloadDns(URI.create(target), okhttp3.Dns.SYSTEM))
                    .isInstanceOf(UnknownHostException.class);
        }
    }

    @Test void canonicalOriginsStillDefineCredentialScope() {
        assertThat(ServiceEndpointPolicy.sameOrigin(URI.create("http://[fd12:0:0:0:0:0:0:50]:8188"),
                URI.create("http://[fd12::50]:8188/result"))).isTrue();
        assertThat(ServiceEndpointPolicy.sameOrigin(URI.create("https://cdn.invalid:80"),
                URI.create("HTTPS://cdn.invalid/result"))).isFalse();
    }

    @Test void configuredServicesCanUseLocalhostAndPendingDnsNames() {
        for (String url : List.of("http://localhost:8188", "https://169.254.169.254", "http://pending-service.invalid:8188"))
            assertThatCode(() -> ServiceEndpointPolicy.validate(URI.create(url))).doesNotThrowAnyException();
    }
}
