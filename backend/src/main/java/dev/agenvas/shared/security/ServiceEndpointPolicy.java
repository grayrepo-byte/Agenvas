package dev.agenvas.shared.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;
import okhttp3.HttpUrl;

/** Administrator-selected services may use HTTP or HTTPS; response URLs never grant new LAN access. */
public final class ServiceEndpointPolicy {
    private ServiceEndpointPolicy() {}

    /** Validate URL/literal addresses without network I/O in configuration transactions. */
    public static void validate(URI uri, boolean allowLiteralLoopback) {
        try {
            String host = uri.getHost();
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (host == null || host.contains("%") || host.equalsIgnoreCase("localhost") || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.toString().length() > 500
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || path.contains("..") || path.contains("%") || path.contains("\\") || path.contains("//")
                    || uri.getRawAuthority().endsWith(":")) throw invalid();
            HttpUrl parsed = HttpUrl.get(uri.toString());
            if ((!host.contains(":") && !parsed.host().equalsIgnoreCase(host))
                    || !parsed.encodedPath().equals(path)) throw invalid();
            dns(uri, Dns.SYSTEM, allowLiteralLoopback);
        } catch (UnknownHostException | IllegalArgumentException blocked) { throw invalid(); }
    }

    /** Pin one selected host and check every answer, including literals that OkHttp does not resolve. */
    public static Dns dns(URI selected, Dns resolver, boolean allowLiteralLoopback) throws UnknownHostException {
        String host = HttpUrl.get(selected.toString()).host();
        if (host.contains(":") || host.matches("[0-9.]+"))
            requireAddress(selected, InetAddress.getByName(host), allowLiteralLoopback);
        return requested -> {
            if (!host.equalsIgnoreCase(requested)) throw new UnknownHostException("Service host changed");
            List<InetAddress> answers = resolver.lookup(requested);
            if (answers.isEmpty()) throw new UnknownHostException("Service DNS is empty");
            for (InetAddress address : answers) requireAddress(selected, address, allowLiteralLoopback);
            return List.copyOf(answers);
        };
    }

    private static void requireAddress(URI uri, InetAddress address, boolean allowLiteralLoopback)
            throws UnknownHostException {
        if (allowLiteralLoopback && "127.0.0.1".equals(uri.getHost())
                && "127.0.0.1".equals(address.getHostAddress())) return;
        if (!EndpointAddressRules.allowsService(address))
            throw new UnknownHostException("Service DNS returned a blocked address");
    }

    /** Same-origin results keep only that service's trust; other hosts require public HTTPS. */
    public static Dns downloadDns(URI selected, URI target, Dns resolver, boolean allowLiteralLoopback)
            throws UnknownHostException {
        if (target.getHost() == null || target.getRawUserInfo() != null || target.getRawFragment() != null
                || target.getHost().contains("%") || target.getPort() == 0 || target.getPort() > 65535
                || target.toString().length() > 8192) throw new UnknownHostException("Invalid result target");
        if (sameOrigin(selected, target)) return dns(target, resolver, allowLiteralLoopback);
        if (!"https".equalsIgnoreCase(target.getScheme())) throw new UnknownHostException("Result requires public HTTPS");
        Dns publicDns = host -> {
            List<InetAddress> answers = resolver.lookup(host);
            if (answers.isEmpty()) throw new UnknownHostException("Result DNS is empty");
            for (InetAddress address : answers) if (!EndpointAddressRules.allowsPublicDownload(address))
                throw new UnknownHostException("Result DNS returned a blocked address");
            return List.copyOf(answers);
        };
        String host = HttpUrl.get(target.toString()).host();
        // OkHttp skips custom DNS for literals: enforce public-only here as well.
        if ((host.contains(":") || host.matches("[0-9.]+"))
                && !EndpointAddressRules.allowsPublicDownload(InetAddress.getByName(host)))
            throw new UnknownHostException("Result literal is a blocked address");
        return dns(target, publicDns, false);
    }

    /** An explicit HTTP service does not authorize later HTTPS redirect downgrades. */
    public static void requireNoDowngrade(URI current, URI target) {
        if ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(target.getScheme()))
            throw new IllegalArgumentException("HTTPS redirects must not downgrade to HTTP");
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
