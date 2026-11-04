package dev.agenvas.shared.security;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * 判定外部端点解析到的地址是否可接受。LLM 端点与内置云媒体传输共用这一套规则，
 * 此前两份实现各自维护、已经漂移，不再分头演算。
 */
public final class EndpointAddressRules {

    private static final List<byte[]> METADATA_IPV6 = List.of(
            HexFormat.of().parseHex("fd000ec2000000000000000000000254"),
            HexFormat.of().parseHex("fd2000ce000000000000000000000254"));

    private EndpointAddressRules() {}

    /** Classify genuine RFC1918 and IPv6 ULA separately from proxy fake-IP. */
    public static boolean isPrivateNetwork(InetAddress address) {
        if (metadata(address)) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255, b = bytes[1] & 255;
            return a == 10 || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168;
        }
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static boolean metadata(InetAddress address) {
        return METADATA_IPV6.stream().anyMatch(value -> Arrays.equals(value, address.getAddress()));
    }

    /** Shared service address boundary; explicit origins cannot override special-address blocks. */
    public static boolean allowsService(InetAddress address) {
        if (metadata(address)) return false;
        if (allowsSelfHosted(address)) return true;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255, d = bytes[3] & 255;
            return !(a == 0 || a >= 224 || a == 100 && b >= 64 && b <= 127
                    || a == 192 && b == 0 && (c == 0 || c == 2)
                    || a == 192 && b == 88 && c == 99 || a == 198 && b == 51 && c == 100
                    || a == 203 && b == 0 && c == 113 || a == 168 && b == 63 && c == 129 && d == 16);
        }
        if (bytes.length == 16) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255, d = bytes[3] & 255;
            return (a & 0xe0) == 0x20 && !(a == 0x20 && b == 0x02)
                    && !(a == 0x20 && b == 0x01 && c == 0 && d == 0)
                    && !(a == 0x20 && b == 0x01 && c == 0x0d && d == 0xb8);
        }
        return false;
    }

    /** Untrusted cross-origin downloads never acquire service private-network privileges. */
    public static boolean allowsPublicDownload(InetAddress address) {
        return allowsService(address) && !allowsSelfHosted(address);
    }

    /**
     * 自托管部署的合法目标：RFC 1918 / IPv6 ULA 私网，以及代理 fake-ip 段
     * （Clash、mihomo 默认用 {@code 198.18.0.0/15} 与 {@code 2001:2::/48} 应答所有域名）。
     * 云元数据端点属于 link-local，不在此列，任何部署下仍会被拒。
     */
    public static boolean allowsSelfHosted(InetAddress address) {
        if (isPrivateNetwork(address)) return true;
        if (metadata(address)) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 198 && (second == 18 || second == 19);
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return first == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0 && (bytes[3] & 0xff) == 2
                    && bytes[4] == 0 && bytes[5] == 0;
        }
        return false;
    }
}
