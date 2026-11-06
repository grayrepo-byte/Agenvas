package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.*;
import java.net.Proxy;
import java.time.Duration;
import java.util.*;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PublicDownloadTransportTest {
    @Test void defaultIsDirectWithNoRedirectRetryOrAmbientCredentialTransmission()throws Exception {
        var client=new PublicDownloadHttpClients().create(Set.of("raw.githubusercontent.com"),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(2));
        assertThat(client.proxy()).isEqualTo(Proxy.NO_PROXY);
        assertThat(client.followRedirects()).isFalse();assertThat(client.followSslRedirects()).isFalse();assertThat(client.retryOnConnectionFailure()).isFalse();
        for(String header:List.of("Authorization","Proxy-Authorization","Cookie")) {
            assertThatThrownBy(()->client.newCall(new Request.Builder().url("https://raw.githubusercontent.com/synthetic").header(header,"synthetic-private").build()).execute())
                .isInstanceOf(PublicDownloadException.class).hasMessage("PUBLIC_DOWNLOAD_DNS_BLOCKED");
        }
        assertThatThrownBy(()->client.newCall(new Request.Builder().url("https://127.0.0.1/synthetic").build()).execute()).isInstanceOf(PublicDownloadException.class);
    }
    @Test void deploymentBindingIsExplicitAndDoesNotReadProxyEnvironment() {
        var source=new MapConfigurationPropertySource(Map.of("agenvas.public-download.proxy-type","SOCKS5","agenvas.public-download.proxy-host","127.0.0.1","agenvas.public-download.proxy-port","7890","agenvas.public-download.doh-url","https://cloudflare-dns.com/dns-query","agenvas.public-download.doh-bootstrap-addresses","1.1.1.1,1.0.0.1"));
        var p=new Binder(source).bind("agenvas.public-download",Bindable.of(PublicDownloadProperties.class)).get();
        assertThat(p.proxyType()).isEqualTo(PublicDownloadProperties.ProxyType.SOCKS5);assertThat(p.proxyPort()).isEqualTo(7890);assertThat(p.dohBootstrapAddresses()).containsExactly("1.1.1.1","1.0.0.1");
        assertThat(PublicDownloadProperties.defaults().proxyType()).isEqualTo(PublicDownloadProperties.ProxyType.DIRECT);
    }
}
