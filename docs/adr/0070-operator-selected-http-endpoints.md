# ADR 0070: Operator-selected HTTP or HTTPS endpoints

> 地址拦截、地址白名单及回环准入规则已由 [ADR 0079](0079-remove-outbound-address-checks.md) 替代；以下保留历史决策背景。

Status: Accepted (2026-11-04). The user explicitly requested removing mandatory HTTPS for public and private configured endpoints. This supersedes ADR 0067's default HTTP rejection; existing exact-origin and loopback controls remain available.

## Decision

Default to allowing administrator-selected HTTP and HTTPS for LLM/model metadata, configurable media services, object stores and WebDAV. Ordinary public HTTP requires no allowlist. WAN workspace and Google native video retain their official host/profile/path restrictions while honoring the explicitly selected scheme. Fixed official preset URLs remain HTTPS; there is no automatic downgrade, fallback or credential forwarding to another origin. SMB is unchanged.

The rejected alternatives were private-only plaintext (does not satisfy the updated user decision), mandatory exact-origin deployment lists for ordinary HTTP (same problem), and unchecked arbitrary URLs/redirects (crosses the administrator trust boundary). The chosen rule trusts a configured service origin, not provider-returned destinations. HTTP can expose keys, prompts, files, passwords and signed links; settings explain this in all supported languages. Private address classification cannot distinguish a model from a management application, so selecting the intended service remains the administrator's responsibility.

Both protocols retain shared special-address rejection, including known AWS/GCP ULA metadata, CGNAT/link-local, Azure management, IPv6 site-local and translation/tunneling ranges. Runtime checks validate every DNS answer and literal IPs independently of OkHttp's DNS callback. Permitted public/private address rotation is allowed; DNS rebinding to blocked targets is denied. Correct the existing proxy IPv6 classifier to match `2001:2::/48`, rather than Teredo's `2001:0::/32`.

Storage now validates final request origins before decrypting/signing credentials, including S3's bucket-prefixed host. Storage loopback remains denied. S3 signatures use the actual scheme-dependent port and bracketed IPv6 authority. LLM keeps its separate literal `127.0.0.1` switch; media retains existing explicit literal loopback support. Media/storage saves validate syntax/literals without domain DNS inside database transactions. LLM keeps its existing save-time DNS preflight.

Third-party feeds accept HTTP(S) but retain public-only DNS, no literals, credentials or redirects. Display attribution fragments remain supported. Media relay may use its selected HTTP(S) object endpoint but still requires real public addresses; external providers can require HTTPS. No browser warnings, mixed-content restrictions, secure-cookie or certificate validation is disabled.

`AGENVAS_LLM_ALLOW_HTTP` defaults to true. False restores exact-origin-only LLM HTTP via the existing trusted-origin list; neither overrides metadata/special-address rejection. The optional Ollama override retains its legacy mapping and exact-origin entry; it does not silently disable general HTTP. Operators can explicitly select the stricter mode. Removing a trusted entry alone does not revoke general HTTP. Existing configurations and encrypted credentials remain valid. No database or wire-shape migration is needed; contract descriptions/generated comments are synchronized.

OpenAI results allow only the selected service origin or public HTTPS and at most three manually checked redirects. RunningHub applies that boundary without redirects. Result downloads carry no API credentials. ComfyUI preserves its existing controlled paths, path-credential isolation, HTTPS cross-origin checks and fake-IP proxy compatibility. Untrusted responses cannot grant access to another private origin or downgrade any HTTPS redirect hop. Authorized relay input URLs retain their selected HTTP(S) scheme, including OpenAI URL edits and WAN/Kling native references; external providers may enforce their own HTTPS requirements.

## Validation and limits

Targeted tests cover public/private HTTP, preserved HTTPS, official hostname bounds, all-answer/literal checks, deployment opt-out, sensitive addresses, signing authorities, masking, HTTP feeds, prefixes, redirects, URL casing and IPv6 canonical origins. Isolated PostgreSQL tests exercise settings and storage behavior. Live providers, real LAN/Docker-host/IPv6 routing and Windows/WSL are not established by these tests. Full suites remain manual per AGENTS.md.

Operational guide: [HTTP and HTTPS endpoints](../http-endpoints.md). Metadata references: [AWS](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html), [Google Cloud](https://docs.cloud.google.com/compute/docs/metadata/overview).
