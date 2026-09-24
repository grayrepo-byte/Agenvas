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

/** 在关联服务端密钥前校验管理员配置的 LLM 目标地址，阻止内网和特殊用途 IP。 */
@Component
public class LlmEndpointPolicy {

    /** 提供部署者显式开启本机回环 HTTP 的唯一例外开关。 */
    private final LlmEndpointProperties properties;

    /** 注入部署级端点例外策略。 */
    public LlmEndpointPolicy(LlmEndpointProperties properties) {
        this.properties = properties;
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

    /** 构造不允许的 Provider 地址统一使用的 400 响应。 */
    private ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "PROVIDER_ENDPOINT_INVALID",
                "模型服务地址无效", "只接受明确的 HTTPS 公网端点；本机 HTTP 需部署者显式开启。", false);
    }
}
