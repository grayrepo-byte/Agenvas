package dev.agenvas.llm.infrastructure;

import dev.agenvas.settings.application.LlmEndpointPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Pins each model request to the admitted origin and validates DNS at connection time. */
final class SafeLlmTransport {

    private final HttpUrl base;
    private final String completionPath;
    private final OkHttpClient client;

    SafeLlmTransport(String endpoint, LlmEndpointPolicy policy) {
        this(endpoint, policy, Dns.SYSTEM);
    }

    /** Injectable DNS makes rebinding and mixed-address rejection deterministic in tests. */
    SafeLlmTransport(String endpoint, LlmEndpointPolicy policy, Dns resolver) {
        this.base = HttpUrl.get(endpoint);
        String path = base.encodedPath().replaceAll("/+$", "");
        this.completionPath = path + "/chat/completions";
        this.client = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(host -> {
                    if (!host.equalsIgnoreCase(base.host())) {
                        throw new UnknownHostException("LLM host changed");
                    }
                    List<InetAddress> addresses = resolver.lookup(host);
                    if (addresses.isEmpty()) throw new UnknownHostException("LLM DNS returned no addresses");
                    try {
                        for (InetAddress address : addresses) {
                            policy.requireAllowedAddress(host, address);
                        }
                    } catch (RuntimeException unsafe) {
                        throw new UnknownHostException("LLM DNS returned a blocked address");
                    }
                    return addresses;
                })
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(45))
                .callTimeout(Duration.ofSeconds(60))
                .build();
    }

    /** Routes Spring AI's request through a separate client that cannot follow redirects. */
    Interceptor interceptor() {
        return chain -> execute(chain.request());
    }

    private Response execute(Request request) throws IOException {
        HttpUrl url = request.url();
        if (!url.scheme().equals(base.scheme()) || !url.host().equalsIgnoreCase(base.host())
                || url.port() != base.port() || !url.encodedPath().equals(completionPath)
                || url.query() != null || !"POST".equals(request.method())) {
            throw new IOException("LLM request target differs from the admitted completion endpoint");
        }
        Response response = client.newCall(request).execute();
        if (response.isRedirect()) {
            response.close();
            throw new IOException("LLM endpoint redirect is forbidden");
        }
        return response;
    }
}
