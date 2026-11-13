package dev.agenvas.settings.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.error.ApiProblemException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 在关联密钥前校验 LLM URL 格式，不解析或限制目标网络地址。 */
@Component
public class LlmEndpointPolicy {

    /** 解析并规范化 HTTP(S) 基础 URL；域名在实际连接时解析。
     * @param requested 管理员提交的 Provider 基础地址
     * @return 去除末尾斜线并规范化大小写的 HTTP(S) 基础地址
     */
    public String normalize(String requested) {
        if (requested == null || requested.length() > 500)
            throw invalid();
        URI uri;
        try {
            uri = new URI(requested.trim());
        } catch (URISyntaxException invalid) {
            throw invalid();
        }
        requireAllowedOrigin(uri);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String path = uri.getRawPath();
        String normalizedPath = path == null || path.isEmpty() ? "" : path.replaceAll("/+$", "");
        return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + normalizedPath;
    }

    /** Recheck URL structure for saved configurations before using credentials.
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
                    || host.contains("%") || uri.getRawAuthority().endsWith(":"))
                throw invalid();
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    /** 构造不允许的 Provider 地址统一使用的 400 响应。 */
    private ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "PROVIDER_ENDPOINT_INVALID",
                ApiMessage.of("api.llm-endpoint-policy.the-model-service-address-is-invalid"),
                ApiMessage.of(
                        "api.llm-endpoint-policy.only-explicit-https-public-network-endpoints-are-accepted-native-http"),
                false);
    }
}
