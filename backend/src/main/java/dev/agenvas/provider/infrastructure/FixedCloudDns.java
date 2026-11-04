package dev.agenvas.provider.infrastructure;

import dev.agenvas.shared.security.EndpointAddressRules;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;

/**
 * Validates service DNS answers for the built-in cloud media transports. Private ranges and
 * proxy fake-ip answers are allowed, matching {@link EndpointAddressRules}; cloud metadata
 * endpoints and special address families stay blocked.
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
                                && "127.0.0.1".equals(address.getHostAddress()))) {
                    throw new UnknownHostException("Cloud DNS returned blocked address");
                }
            }
            return addresses;
        };
    }

    private static boolean publicAddress(InetAddress address) {
        return EndpointAddressRules.allowsService(address);
    }
}
