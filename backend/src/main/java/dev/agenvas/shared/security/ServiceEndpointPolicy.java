package dev.agenvas.shared.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;
import okhttp3.HttpUrl;

/** HTTP(S) URL structure and request scope, without destination address restrictions. */
public final class ServiceEndpointPolicy {
    private ServiceEndpointPolicy() {}

    /** Validate URL/literal addresses without network I/O in configuration transactions. */
    public static void validate(URI uri) {
        try {
            String host = uri.getHost();
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (host == null || host.contains("%") || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.toString().length() > 500
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || path.contains("..") || path.contains("%") || path.contains("\\") || path.contains("//")
                    || uri.getRawAuthority().endsWith(":")) throw invalid();
            HttpUrl parsed = HttpUrl.get(uri.toString());
            if ((!host.contains(":") && !parsed.host().equalsIgnoreCase(host))
                    || !parsed.encodedPath().equals(path)) throw invalid();
        } catch (IllegalArgumentException blocked) { throw invalid(); }
    }

    /** Keep credentials scoped to the selected host; use every resolver answer unchanged. */
    public static Dns dns(URI selected, Dns resolver) throws UnknownHostException {
        String host = HttpUrl.get(selected.toString()).host();
        return requested -> {
            if (!host.equalsIgnoreCase(requested)) throw new UnknownHostException("Service host changed");
            List<InetAddress> answers = resolver.lookup(requested);
            if (answers.isEmpty()) throw new UnknownHostException("Service DNS is empty");
            return List.copyOf(answers);
        };
    }

    /** Result requests carry no service credentials and may use any HTTP(S) destination. */
    public static Dns downloadDns(URI target, Dns resolver) throws UnknownHostException {
        if (target.getHost() == null || target.getRawUserInfo() != null || target.getRawFragment() != null
                || target.getHost().contains("%") || target.getPort() == 0 || target.getPort() > 65535
                || !("https".equalsIgnoreCase(target.getScheme()) || "http".equalsIgnoreCase(target.getScheme()))
                || target.toString().length() > 8192) throw new UnknownHostException("Invalid result target");
        return dns(target, resolver);
    }

    /** Normalize a service root for adapters whose API paths are defined by their protocol. */
    public static String origin(String value) {
        URI uri = URI.create(value.trim());
        validate(uri);
        if (!(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) throw invalid();
        return uri.toASCIIString().replaceAll("/+$", "");
    }

    public static boolean sameOrigin(URI first, URI second) {
        try {
            HttpUrl firstUrl = HttpUrl.get(first.toString());
            HttpUrl secondUrl = HttpUrl.get(second.toString());
            return firstUrl.scheme().equals(secondUrl.scheme()) && firstUrl.host().equals(secondUrl.host())
                    && firstUrl.port() == secondUrl.port();
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid service endpoint"); }
}
