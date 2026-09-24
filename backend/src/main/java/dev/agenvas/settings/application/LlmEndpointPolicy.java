package dev.agenvas.settings.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Validates admin-supplied LLM destinations before a secret can be associated with them. */
@Component
public class LlmEndpointPolicy {

    private final LlmEndpointProperties properties;

    public LlmEndpointPolicy(LlmEndpointProperties properties) {
        this.properties = properties;
    }

    /** Returns a canonical base URL; this admission check is not a per-request DNS pin. */
    public String normalize(String requested) {
        if (requested == null || requested.length() > 500) throw invalid();
        URI uri;
        try {
            uri = new URI(requested.trim());
        } catch (URISyntaxException invalid) {
            throw invalid();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || uri.getRawAuthority() == null || !uri.normalize().equals(uri)
                || uri.getPort() > 65535 || uri.getPort() == 0
                || (!"https".equalsIgnoreCase(scheme)
                        && !"http".equalsIgnoreCase(scheme))) {
            throw invalid();
        }
        boolean loopback = "127.0.0.1".equals(host);
        if (loopback && !properties.allowLoopbackHttp()) throw invalid();
        if ("http".equalsIgnoreCase(scheme)
                && !(properties.allowLoopbackHttp() && loopback)) {
            throw invalid();
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw invalid();
            for (InetAddress address : addresses) requireAllowedAddress(host, address);
        } catch (UnknownHostException unresolved) {
            throw invalid();
        }
        String path = uri.getRawPath();
        String normalizedPath = path == null || path.isEmpty() ? "" : path.replaceAll("/+$", "");
        return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + normalizedPath;
    }

    /** Reused by the runtime DNS resolver so rebinding cannot bypass the save-time check. */
    public void requireAllowedAddress(String host, InetAddress address) {
        if ("127.0.0.1".equals(host)) {
            byte[] bytes = address.getAddress();
            if (properties.allowLoopbackHttp() && bytes.length == 4
                    && (bytes[0] & 0xff) == 127 && (bytes[1] & 0xff) == 0
                    && (bytes[2] & 0xff) == 0 && (bytes[3] & 0xff) == 1) return;
            throw invalid();
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) throw invalid();
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            if (first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && third == 0)
                    || (first == 192 && second == 0 && third == 2)
                    || (first == 192 && second == 88 && third == 99)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)
                    || (first == 168 && second == 63 && third == 129
                            && (bytes[3] & 0xff) == 16)) throw invalid();
            return;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            int fourth = bytes[3] & 0xff;
            if ((first & 0xe0) != 0x20
                    || (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8)
                    || (first == 0x20 && second == 0x01 && third == 0 && fourth == 0)
                    || (first == 0x20 && second == 0x02)) throw invalid();
            return;
        }
        throw invalid();
    }

    private ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "PROVIDER_ENDPOINT_INVALID",
                "模型服务地址无效", "只接受明确的 HTTPS 公网端点；本机 HTTP 需部署者显式开启。", false);
    }
}
