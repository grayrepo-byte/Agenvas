package dev.agenvas.provider.infrastructure;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import okhttp3.Dns;

/** Administrator-selected ComfyUI base, including a reverse proxy prefix. */
public final class ComfyUiEndpoint {
    private static final int MAX_ENDPOINT_LENGTH = 500;
    private static final int MAX_PORT = 65535;
    private static final Pattern IPV4 = Pattern.compile("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}");
    /** Returned display value, never an executable endpoint. */
    public static final String HIDDEN_PATH = "/[configured-path]";

    private ComfyUiEndpoint() {}

    public static URI checked(String endpoint) {
        try {
            if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_ENDPOINT_LENGTH) throw invalid();
            URI uri = URI.create(URI.create(endpoint.trim()).toASCIIString());
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > MAX_PORT
                    || path == null || path.contains("..") || path.contains("%")
                    || path.contains("\\") || path.contains("//") || path.equals(HIDDEN_PATH)) throw invalid();
            boolean local = IPV4.matcher(host).matches() && allowedLocalIpv4(host);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && local && uri.getPort() > 0)) throw invalid();
            // Literal IPs bypass OkHttp DNS; validate them before constructing the transport.
            if (IPV4.matcher(host).matches() || host.contains(":")) {
                FixedCloudDns.checked(Dns.SYSTEM, local).lookup(host);
            }
            return URI.create(uri.toASCIIString().replaceAll("/+$", ""));
        } catch (IllegalArgumentException | UnknownHostException failure) {
            throw invalid();
        }
    }

    /** A path may contain credentials; only its presence is returned to the administrator. */
    public static String display(String endpoint) {
        URI uri = checked(endpoint);
        return uri.getScheme() + "://" + uri.getRawAuthority()
                + (uri.getRawPath().isEmpty() ? "" : HIDDEN_PATH);
    }

    private static boolean allowedLocalIpv4(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress();
        } catch (UnknownHostException invalid) {
            return false;
        }
    }

    private static IllegalArgumentException invalid() {
        // Never include the full URL: reverse proxies may carry a credential in its path.
        return new IllegalArgumentException("ComfyUI base URL must be HTTPS or an exact local HTTP address");
    }
}
