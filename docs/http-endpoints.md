# HTTP and HTTPS endpoints

Agenvas accepts valid HTTP(S) service addresses without destination allowlists. Localhost, literal IPv4/IPv6, private networks, proxy Fake-IP and special-purpose addresses are accepted across models, media, storage, relay and source downloads. Names are resolved at connection time; saving an LLM endpoint does not require DNS or a running service.

Kling and MiniMax accept an editable service root; leaving the address empty keeps their existing default. WAN and Google native video accept compatible custom hosts. Each adapter still uses its compiled protocol paths and payloads, so changing the host does not implement a different provider protocol. Fixed provider defaults remain where no custom address field exists.

The LLM loopback, general-HTTP and exact-origin deployment options have been removed. Old environment variables for these options have no effect; no allowlist override is needed. Existing saved endpoints and encrypted credentials remain valid, without a database migration.

Result downloads accept HTTP(S) destinations without public-address, CDN host or port allowlists. Existing redirect bounds, response limits and credential isolation remain: an API credential is sent only to its configured service interface. Downloads use a fresh request without that credential. URL credentials, unsupported schemes, invalid ports and malformed protocol paths still fail validation. TLS certificates remain verified.

Object stores, WebDAV and SMB can use local hosts. Relay signing no longer performs a public-DNS preflight. The provider receiving a signed URL must be able to reach its endpoint; the application cannot determine that by classifying local DNS answers.

See [ADR 0079](adr/0079-remove-outbound-address-checks.md), [public download networking](public-download-networking.md) and [local Ollama](ollama-local.md). Address-based SSRF protection is removed; operators manage access through deployment networking. Authentication, CSRF, encryption, file validation and task execution semantics remain in effect.
