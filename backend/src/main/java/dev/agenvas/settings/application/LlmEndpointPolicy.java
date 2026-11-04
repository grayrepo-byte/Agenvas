package dev.agenvas.settings.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.security.EndpointAddressRules;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 在关联密钥前校验 LLM 目标，阻止特殊用途地址及未获部署准入的 HTTP。 */
@Component
public class LlmEndpointPolicy {

    /** Deployment settings remain separate from administrator-saved provider configuration. */
    private final LlmEndpointProperties properties;

    /** Exact HTTP origins; no wildcard host, subnet or port grants. */
    private final Set<HttpOrigin> trustedHttpOrigins;

    /** 注入部署级端点例外策略。 */
    public LlmEndpointPolicy(LlmEndpointProperties properties) {
        this.properties = properties;
        if (properties.trustedHttpOrigins().size() > 32) {
            throw new IllegalArgumentException("At most 32 trusted LLM HTTP origins are allowed");
        }
        this.trustedHttpOrigins = properties.trustedHttpOrigins().stream()
                .map(HttpOrigin::parse).collect(Collectors.toUnmodifiableSet());
    }

    /** 解析并规范化基础 URL，同时校验解析所得全部地址；调用时仍需再次做 DNS 检查。
     * @param requested 管理员提交的 Provider 基础地址
     * @return 去除末尾斜线并规范化大小写的 HTTP(S) 基础地址
     */
    public String normalize(String requested) {
        if (requested == null || requested.length() > 500) throw invalid();
        URI uri;
        try {
            uri = new URI(requested.trim());
        } catch (URISyntaxException invalid) {
            throw invalid();
        }
        requireAllowedOrigin(uri);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw invalid();
            for (InetAddress address : addresses) requireAllowedAddress(uri, address);
        } catch (UnknownHostException unresolved) {
            throw invalid();
        }
        String path = uri.getRawPath();
        String normalizedPath = path == null || path.isEmpty() ? "" : path.replaceAll("/+$", "");
        return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + normalizedPath;
    }

    /** Recheck scheme, origin and URL structure for saved configurations before using credentials.
     * DNS remains separately pinned by the transport, including each runtime lookup.
     */
    public void requireAllowedOrigin(URI uri) {
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
        try {
            okhttp3.HttpUrl url = okhttp3.HttpUrl.get(uri.toString());
            String suppliedHost = host.replaceAll("^\\[|\\]$", "");
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if ((!host.contains(":") && !url.host().equalsIgnoreCase(suppliedHost)) || !url.encodedPath().equals(path)
                    || host.contains("%") || uri.getRawAuthority().endsWith(":")) throw invalid();
        } catch (IllegalArgumentException malformed) { throw invalid(); }
        boolean loopback = "127.0.0.1".equals(host);
        if (loopback && !properties.allowLoopbackHttp()) throw invalid();
        if ("http".equalsIgnoreCase(scheme)
                && !(properties.allowLoopbackHttp() && loopback)
                && !trustedHttpOrigins.contains(HttpOrigin.from(uri))
                && !properties.allowHttp()) {
            throw invalid();
        }
    }

    /** 供运行时 DNS 解析器复用，防止保存后 DNS 重绑定绕过准入校验。
     * @param host URL 中经过解析的主机名
     * @param address 本次 DNS 查询得到的一个 IP 地址
     */
    public void requireAllowedAddress(String host, InetAddress address) {
        if ("127.0.0.1".equals(host)) {
            byte[] bytes = address.getAddress();
            if (properties.allowLoopbackHttp() && bytes.length == 4
                    && (bytes[0] & 0xff) == 127 && (bytes[1] & 0xff) == 0
                    && (bytes[2] & 0xff) == 0 && (bytes[3] & 0xff) == 1) return;
            throw invalid();
        }
        if (!EndpointAddressRules.allowsService(address)) throw invalid();
    }

    /** Runtime URI overload keeps the same scheme-independent address guard for HTTP and HTTPS. */
    public void requireAllowedAddress(URI uri, InetAddress address) {
        requireAllowedAddress(uri.getHost().replaceAll("^\\[|\\]$", ""), address);
    }

    /** Configuration entries are origins only; malformed operator input fails at startup. */
    private record HttpOrigin(String host, int port) {
        static HttpOrigin from(URI uri) {
            return new HttpOrigin(uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort() < 0 ? 80 : uri.getPort());
        }

        static HttpOrigin parse(String value) {
            try {
                URI uri = new URI(value.trim());
                if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                        || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535
                        || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                        || uri.getRawAuthority().endsWith(":")) {
                    throw new IllegalArgumentException();
                }
                return from(uri);
            } catch (RuntimeException | URISyntaxException invalid) {
                // Do not echo values: a misconfigured origin may contain credentials.
                throw new IllegalArgumentException("Trusted LLM HTTP entries must be exact HTTP origins");
            }
        }
    }

    /** 构造不允许的 Provider 地址统一使用的 400 响应。 */
    private ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "PROVIDER_ENDPOINT_INVALID",
                ApiMessage.of("api.llm-endpoint-policy.the-model-service-address-is-invalid"), ApiMessage.of("api.llm-endpoint-policy.only-explicit-https-public-network-endpoints-are-accepted-native-http"), false);
    }
}
