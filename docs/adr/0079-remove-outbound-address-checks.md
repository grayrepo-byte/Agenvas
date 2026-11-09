# ADR 0079: Remove outbound address checks and allowlists

Status: accepted (2026-11-07), explicitly requested by the user.

## Context and decision

Public-only DNS checks prevented reference metadata and image downloads on proxy Fake-IP networks. The user requested removing this feature across all functions and clarified that address allowlists should be removed entirely. Adding another proxy exception or deployment switch would retain the unwanted policy and its configuration burden.

Delete destination IP classification, public-address preflight, local-address exceptions, HTTP origin allowlists and configurable-provider/CDN host allowlists. Model, metadata, media, storage, relay, catalog and discovery paths use their configured resolver without filtering its answers. Save-time validation does not resolve DNS. Keep existing explicit proxy and DoH options, including ordinary TLS and DNS response parsing; bootstrap IPs no longer need to be public.

Kling and MiniMax use editable HTTP(S) roots. WAN and Google native video accept compatible custom hosts without official-domain/region admission rules. Fixed provider defaults and compiled API paths remain; this does not add custom endpoint support to protocols whose configuration has no address field. Credential-free result downloads accept HTTP(S) hosts and valid ports without CDN/public-network checks. Existing bounded redirects remain, without address classification or HTTPS downgrade checks.

Remove LLM deployment properties `allow-loopback-http`, `allow-http` and `trusted-http-origins`, including environment/Compose examples. Remove discovery's `BLOCKED_ADDRESS` status and `policyAllowed` response property; a found service is eligible to fill the draft. Regenerate the TypeScript schema from OpenAPI. No database migration or credential rewrite is required. Existing clients must adapt to the changed discovery response; old deployment variables no longer control endpoint access. Historical persisted errors remain readable and can be retried under the new rules.

## Boundaries and consequences

Keep authentication, CSRF, encrypted server credentials, credential scope, URL syntax, protocol paths, file integrity/decoding, response limits, timeouts and execution semantics. No implicit proxy, insecure TLS, automatic paid resubmission or runtime permissions are introduced. ComfyUI path credentials stay within their configured origin; result downloads do not forward API credentials.

The application no longer provides destination-based SSRF protection: configured services and returned HTTP(S) links can reach networks available to the server. Deployment networking controls access. Relay recipients must actually reach the chosen storage endpoint; local DNS classification is no longer used as a proxy for recipient reachability.

Supersedes the address-policy portions of ADR 0007, 0067, 0070 and 0076. Cache authorization, image licensing, immutable snapshots and other unrelated decisions remain applicable.

## Verification

Focused tests cover local source downloads, mixed Fake-IP/private/special DNS answers, DoH parsing and bootstrap configuration, model calls, metadata, media result URLs, storage signing, discovery and settings. Synthetic HTTP and PostgreSQL tests are distinct from live provider compatibility. Actual commands and limitations are recorded in the development checklist.
