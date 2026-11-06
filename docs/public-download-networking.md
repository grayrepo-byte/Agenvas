# Public downloads through proxies and Fake-IP DNS

The reference catalog and third-party prompt downloader use a credential-free
public-download transport. By default they connect directly using system DNS and
reject every nonpublic destination, including benchmark/Fake-IP ranges. Provider
submission settings and the global endpoint policy are separate.

Configure this transport explicitly on the server when a network needs a trusted
proxy or a resolver that returns real public addresses. The application does not
automatically import OS proxies, PAC, `HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, JVM
proxy properties or a global authenticator. No host network setting is changed.

## Configuration

Example Spring deployment configuration for a trusted local SOCKS5 proxy:

```yaml
agenvas:
  public-download:
    proxy-type: SOCKS5
    proxy-host: 127.0.0.1
    proxy-port: 7890
    doh-url: https://cloudflare-dns.com/dns-query
    doh-bootstrap-addresses:
      - 1.1.1.1
      - 1.0.0.1
```

Choose the actual trusted proxy and resolver for the deployment; this example is
not installed automatically. `proxy-type` accepts `DIRECT` (default), `HTTP`,
`HTTPS` or `SOCKS5`. To use verified DoH with a direct connection, set `DIRECT` and
omit proxy host/port. To use a proxy with genuine public system DNS, omit DoH.
Proxy configuration alone cannot repair Fake-IP origin DNS.

With genuine public DNS and a reachable direct route, other installations keep the
default experience without extra configuration. A transparent proxy with genuine
DNS also follows that route; its presence is not separately detected. A transparent
Fake-IP deployment needs explicit real-address resolution. Merely enabling a
desktop system proxy does not opt the backend into it. The source dialog reports
configuration failures but does not edit server networking; deployers must supply
the settings above. Complete automatic proxy discovery is not implemented.

DoH currently expects the DNS-JSON schema documented by
[Cloudflare](https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/make-api-requests/),
not arbitrary DNS wire-format endpoints. Configure an HTTPS hostname URL and public
literal bootstrap IPs; credentials, query strings, fragments and literal-IP resolver
URLs are rejected. Bootstrap routing remains pinned while TLS verifies the resolver
hostname. Missing, malformed, mixed public/private or Fake-IP answers fail closed.
There is no fallback to system DNS after resolver failure.

The application validates all genuine origin addresses, then tunnels the selected
numeric address. HTTP(S) uses numeric CONNECT; SOCKS5 uses numeric address encoding
and a no-auth-only handshake. Origin TLS/SNI/Host still use the original hostname.
HTTPS proxies additionally require a certificate trusted by the JVM and matching
the configured proxy host. There is no insecure TLS mode. Proxy authentication is
currently unsupported; credentials are never borrowed from other configuration.

## Containers

`127.0.0.1` inside a container refers to that container. Docker Desktop's host alias
`host.docker.internal` was verified for an existing host proxy. On Linux, configure
an explicit reachable proxy service or host mapping appropriate to the deployment;
that topology was not verified in this task. The application does not add routes,
host networking, daemon changes or DNS overrides.

The operator-selected proxy address is allowed to be a local service. This does
not permit reference metadata to request local origin URLs: source hosts remain
allowlisted and origin addresses remain public-only. A trusted proxy must honor
the numeric tunnel destination; proxy compromise is outside this trust boundary.

Allowlisting a GitHub hostname and accepting its Fake-IP answer would be narrower
than disabling SSRF globally, but still relies on a local routing convention rather
than establishing a public destination. The same synthetic address can route
differently on a direct connection, another host or after a DNS/route change. TLS
hostname verification limits what a wrongly routed endpoint can return; it does
not prove that the connection target is public. This implementation validates the
complete origin answer set and connects the chosen numeric address without a second
origin lookup, avoiding a check/connect rebinding gap. It therefore keeps Fake-IP
denial even for fixed trusted source hosts.

## Diagnostics

| Stable reference error suffix | Meaning and next step |
| --- | --- |
| `DNS_BLOCKED` | Resolver returned nonpublic/Fake-IP addresses. Ask the administrator to configure trusted real-address DNS/DoH; keep the guard enabled. |
| `DNS_FAILED` | Resolution or response validation failed. Check the configured resolver and bootstrap reachability. |
| `PROXY_UNREACHABLE` | Configured proxy could not be reached. Check host/port and container topology. |
| `PROXY_AUTH_REQUIRED` | Proxy requires authentication, which this transport does not support. Use a trusted credential-free endpoint. |
| `PROXY_ERROR` | Tunnel negotiation failed. Check proxy type and supported numeric CONNECT/SOCKS5 routing. |
| `TLS_FAILED` | Resolver, proxy or origin TLS verification failed. Check trust and hostname; do not disable verification. |
| `REMOTE_RATE_LIMITED` | GitHub rate limit reached. Retry later; a fixed commit avoids ref resolution but image tree checks may still need the API. |
| `REMOTE_UNAVAILABLE` | Other public-download failure. Retain local cache and retry when reachable. |

API errors use `REFERENCE_CATALOG_` plus these suffixes. Third-party prompt
downloads expose corresponding stable download reasons. Raw upstream response
bodies and credentials are not included. Source synchronization failure retains
the previous successful metadata snapshot; verified file cache hits remain usable
without a new download, subject to current authorization.

## Verified compatibility and limits

Task verification used the real fixed public metadata at commit
`b290a06270dde0da4b8aeab1b4c1d99b1f74065f` and checked its 5,420,749-byte response
digest. The selected-image integration test separately used exactly one PNG,
then disabled only its own TCP bridge to prove offline reuse and project import.

| Route | Evidence |
| --- | --- |
| Native default direct + Fake-IP | Correctly blocked before HTTP. |
| Default Docker direct + inherited Fake-IP | Correctly blocked before HTTP. |
| Native direct + verified DoH | Real product metadata succeeded. |
| Native HTTP CONNECT + verified DoH | Real product metadata succeeded. |
| Native SOCKS5 + verified DoH | Real metadata and one selected-image cache/import succeeded. |
| Docker direct + verified DoH | Real product metadata succeeded. |
| Docker SOCKS5 via host alias + verified DoH | Real product metadata succeeded. |
| Docker SOCKS5 via container localhost | Expected `PROXY_UNREACHABLE`. |
| HTTPS proxy | Synthetic TLS-in-TLS success and independent hostname rejection tests; real HTTPS proxy unverified. |
| IPv6 numeric proxy targets | Synthetic CONNECT/SOCKS encoding verified; live IPv6-only route unverified. |
| Genuine public system DNS | Policy unit tests; no independent non-Fake-IP environment claimed. |
| Authenticated proxy / OS / PAC / environment auto-discovery | Unsupported; explicit configuration required. |

32 focused transport/source-client tests cover private/mixed/Fake-IP denial,
bounded rooted CNAME chains, proxy protocol limits, authentication refusal and TLS
hostname checks. Real proxy transport checks do not establish whole-library image
permission. No security check or user proxy setting was relaxed for verification.
