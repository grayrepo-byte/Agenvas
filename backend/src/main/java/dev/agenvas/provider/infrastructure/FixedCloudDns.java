package dev.agenvas.provider.infrastructure;

import dev.agenvas.shared.security.EndpointAddressRules;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;

/**
 * Rejects non-public DNS answers for the built-in cloud media transports. Private ranges and
 * proxy fake-ip answers are allowed, matching {@link EndpointAddressRules}; cloud metadata
 * endpoints stay blocked because they are link-local.
 */
final class FixedCloudDns {
    private FixedCloudDns() {}

    static Dns checked(Dns resolver, boolean loopbackTest) {
        return hostname -> {
            List<InetAddress> addresses = resolver.lookup(hostname);
            if (addresses.isEmpty()) throw new UnknownHostException("Cloud DNS is empty");
            for (InetAddress address : addresses) {
                if (!publicAddress(address)
                        && !(loopbackTest && "127.0.0.1".equals(hostname)
                                && address.isLoopbackAddress())) {
                    throw new UnknownHostException("Cloud DNS returned blocked address");
                }
            }
            return addresses;
        };
    }

    private static boolean publicAddress(InetAddress address) {
        if (EndpointAddressRules.allowsSelfHosted(address)) return true;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            if (a == 0 || a >= 224 || a == 100 && b >= 64 && b <= 127
                    || a == 169 && b == 254 || a == 192 && b == 0 && c == 0
                    || a == 192 && b == 0 && c == 2
                    || a == 198 && b == 51 && c == 100
                    || a == 203 && b == 0 && c == 113) return false;
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            if (first == 0 || first == 0x20
                    && (bytes[1] & 0xff) == 0x01 && (bytes[2] & 0xff) == 0x0d
                    && (bytes[3] & 0xff) == 0xb8) return false;
        }
        return true;
    }
}
