package dev.agenvas.provider.infrastructure;

import dev.agenvas.shared.security.ServiceEndpointPolicy;
import java.net.URI;

/** Administrator-selected ComfyUI base, including a reverse proxy prefix. */
public final class ComfyUiEndpoint {
    private static final int MAX_ENDPOINT_LENGTH = 500;
    private static final int MAX_PORT = 65535;
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
            ServiceEndpointPolicy.validate(uri, true);
            return URI.create(uri.toASCIIString().replaceAll("/+$", ""));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    /** A path may contain credentials; only its presence is returned to the administrator. */
    public static String display(String endpoint) {
        URI uri = checked(endpoint);
        return uri.getScheme() + "://" + uri.getRawAuthority()
                + (uri.getRawPath().isEmpty() ? "" : HIDDEN_PATH);
    }

    private static IllegalArgumentException invalid() {
        // Never include the full URL: reverse proxies may carry a credential in its path.
        return new IllegalArgumentException("ComfyUI base URL must be an HTTP or HTTPS service");
    }
}
