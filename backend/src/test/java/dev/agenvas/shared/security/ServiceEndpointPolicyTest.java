package dev.agenvas.shared.security;

import static org.assertj.core.api.Assertions.*;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;
import org.junit.jupiter.api.Test;

class ServiceEndpointPolicyTest {
    @Test
    void resultSchemeCasingCannotBypassSpecialAddressChecksOrChangeTheEffectivePort() throws Exception {
        URI selected = URI.create("http://relay.invalid:8188");
        for (String scheme : List.of("http", "HTTP", "Http")) {
            URI result = URI.create(scheme + "://relay.invalid:8188/result.png");
            assertThatThrownBy(() -> ServiceEndpointPolicy.downloadDns(selected, result,
                    host -> List.of(InetAddress.getByName("169.254.169.254")), true).lookup("relay.invalid"))
                    .as(scheme).isInstanceOf(UnknownHostException.class);
        }
        URI custom = URI.create("https://relay.invalid:80");
        URI defaultPort = URI.create("HTTPS://relay.invalid/admin");
        assertThat(ServiceEndpointPolicy.sameOrigin(custom, defaultPort)).isFalse();
        assertThatThrownBy(() -> ServiceEndpointPolicy.downloadDns(custom, defaultPort,
                host -> List.of(InetAddress.getByName("10.0.0.1")), true).lookup("relay.invalid"))
                .isInstanceOf(UnknownHostException.class);
        assertThat(ServiceEndpointPolicy.sameOrigin(URI.create("https://cdn.invalid"),
                URI.create("HTTPS://cdn.invalid:443/result"))).isTrue();
    }

    @Test
    void canonicalIpv6OriginsShareOnlyTheSameEffectivePort() throws Exception {
        URI selected = URI.create("http://[fd12:0:0:0:0:0:0:50]:8188");
        URI compressed = URI.create("http://[fd12::50]:8188/result");
        assertThat(ServiceEndpointPolicy.sameOrigin(selected, compressed)).isTrue();
        assertThat(ServiceEndpointPolicy.downloadDns(selected, compressed, Dns.SYSTEM, true)).isNotNull();
        assertThat(ServiceEndpointPolicy.sameOrigin(selected, URI.create("http://[fd12::50]:80/result"))).isFalse();
        assertThatCode(() -> ServiceEndpointPolicy.validate(URI.create("http://pending-service.invalid:8188"), false))
                .doesNotThrowAnyException();
    }

    @Test
    void selectedServiceDnsChecksEveryPublicOrPrivateAnswer() throws Exception {
        URI selected = URI.create("http://host.docker.internal:8188");
        var answers = new java.util.concurrent.atomic.AtomicReference<>(List.of(InetAddress.getByName("192.168.65.254")));
        Dns checked = ServiceEndpointPolicy.dns(selected, host -> answers.get(), true);
        assertThat(checked.lookup("host.docker.internal")).extracting(InetAddress::getHostAddress)
                .containsExactly("192.168.65.254");
        for (String address : List.of("169.254.169.254", "fd00:ec2::254", "127.0.0.1")) {
            answers.set(List.of(InetAddress.getByName("10.0.0.5"), InetAddress.getByName(address)));
            assertThatThrownBy(() -> checked.lookup("host.docker.internal")).as(address)
                    .isInstanceOf(UnknownHostException.class);
        }
        assertThatThrownBy(() -> checked.lookup("different.internal")).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void resultUrlsCannotExpandTrustToOtherPrivateServicesOrPlaintextInternet() throws Exception {
        URI selected = URI.create("http://192.168.1.50:8188");
        Dns synthetic = host -> List.of(InetAddress.getByName(host));
        assertThat(ServiceEndpointPolicy.downloadDns(selected, URI.create(selected + "/result.png"), synthetic, true)
                .lookup("192.168.1.50")).hasSize(1);
        for (String target : List.of("http://192.168.1.50:80/admin", "http://8.8.8.8/result",
                "https://192.168.1.51/admin", "https://[fd12::1]/admin", "https://169.254.169.254/",
                "https://user:secret@8.8.8.8/result", "file:///etc/hosts")) {
            assertThatThrownBy(() -> ServiceEndpointPolicy.downloadDns(selected, URI.create(target), synthetic, true))
                    .as(target).isInstanceOf(UnknownHostException.class);
        }
        assertThatThrownBy(() -> ServiceEndpointPolicy.downloadDns(selected, URI.create("https://10.0.0.1/admin"),
                host -> List.of(InetAddress.getByName("8.8.8.8")), false)).isInstanceOf(UnknownHostException.class);
        assertThat(ServiceEndpointPolicy.downloadDns(selected, URI.create("https://cdn.invalid/result"),
                host -> List.of(InetAddress.getByName("8.8.8.8")), false).lookup("cdn.invalid")).hasSize(1);
        assertThatThrownBy(() -> ServiceEndpointPolicy.downloadDns(selected, URI.create("https://cdn.invalid/result"),
                host -> List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")), false)
                .lookup("cdn.invalid")).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void specialAddressFamiliesStayBlockedForAllServices() throws Exception {
        for (String address : List.of("0.0.0.0", "127.0.0.1", "169.254.169.254", "100.100.100.200",
                "168.63.129.16", "192.0.0.192", "224.0.0.1", "::", "::1", "fe80::1", "fec0::1",
                "fd00:ec2::254", "fd20:ce::254", "2001::1", "2002:a00:1::", "64:ff9b::a00:1")) {
            assertThat(EndpointAddressRules.allowsService(InetAddress.getByName(address))).as(address).isFalse();
        }
        assertThat(EndpointAddressRules.allowsSelfHosted(InetAddress.getByName("2001:2::1"))).isTrue();
        assertThat(EndpointAddressRules.isPrivateNetwork(InetAddress.getByName("2001:2::1"))).isFalse();
    }
}
