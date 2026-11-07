# Source download networking

Reference catalogs, reference images and third-party prompt sources share a bounded HTTP(S) download transport. System DNS and direct connections are the defaults. Address checks and allowlists have been removed: private, loopback, special-purpose and proxy Fake-IP answers are passed to the connector unchanged. No real-address DNS/DoH exception is required just to pass an application guard.

Optional deployment settings remain available when the network needs an explicit route:

- `agenvas.public-download.proxy-type`: `DIRECT`, `HTTP`, `HTTPS` or `SOCKS5`.
- `agenvas.public-download.proxy-host` and `agenvas.public-download.proxy-port`: explicit proxy host and valid port.
- `agenvas.public-download.doh-url`: an HTTPS DNS-JSON resolver URL.
- `agenvas.public-download.doh-bootstrap-addresses`: literal IPv4/IPv6 resolver routes. They no longer need to be public.

The transport does not discover OS/PAC/environment proxies or automatically fall back to another resolver. HTTP/HTTPS CONNECT and SOCKS5 carry the selected numeric route while retaining the origin hostname for HTTP Host and TLS/SNI. Proxy credentials remain unsupported. TLS certificate verification, response bounds, timeout/cancellation and DNS-JSON structure/CNAME validation remain. DoH is optional; removing address checks does not itself start a proxy or make an unreachable route work.

Stable errors distinguish DNS resolution failure, unavailable proxy, required proxy authentication, proxy protocol errors, TLS failure and remote rate limiting. A persisted `REFERENCE_CATALOG_DNS_BLOCKED` is an old sync failure: choose Sync again to use the new behavior. Failed syncs retain the last successful catalog, and image rights/cache permissions are unchanged.

For Docker, a loopback proxy address refers to the server container. Use a reachable host/container route when the proxy is outside it. Synthetic local HTTP and proxy tests verify the transport behavior; live provider/proxy infrastructure needs separate validation. See [ADR 0079](adr/0079-remove-outbound-address-checks.md).
