package dev.agenvas.shared.http;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator-controlled transport for credential-free public downloads, never provider submissions. */
@ConfigurationProperties(prefix = "agenvas.public-download")
public record PublicDownloadProperties(String dohUrl, List<String> dohBootstrapAddresses,
        ProxyType proxyType, String proxyHost, int proxyPort) {
    public enum ProxyType {
        DIRECT, HTTP, HTTPS, SOCKS5
    }
    public PublicDownloadProperties {
        dohUrl = dohUrl == null ? "" : dohUrl.strip();
        dohBootstrapAddresses = dohBootstrapAddresses == null ? List.of() : List.copyOf(dohBootstrapAddresses);
        proxyType = proxyType == null ? ProxyType.DIRECT : proxyType;
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
    }
    public static PublicDownloadProperties defaults() {
        return new PublicDownloadProperties(null, null, null, null, 0);
    }
}
