package dev.agenvas.shared.security;

import java.net.InetAddress;

/**
 * 判定外部端点解析到的地址是否可接受。LLM 端点与内置云媒体传输共用这一套规则，
 * 此前两份实现各自维护、已经漂移，不再分头演算。
 */
public final class EndpointAddressRules {

    private EndpointAddressRules() {}

    /**
     * 自托管部署的合法目标：RFC 1918 / IPv6 ULA 私网，以及代理 fake-ip 段
     * （Clash、mihomo 默认用 {@code 198.18.0.0/15} 与 {@code 2001:2::/48} 应答所有域名）。
     * 云元数据端点属于 link-local，不在此列，任何部署下仍会被拒。
     */
    public static boolean allowsSelfHosted(InetAddress address) {
        if (address.isSiteLocalAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 198 && (second == 18 || second == 19);
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return true;
            return first == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0 && (bytes[3] & 0xff) == 0;
        }
        return false;
    }
}
