# Choose HTTP or HTTPS service endpoints

Agenvas accepts administrator-selected **public and private HTTP or HTTPS** service endpoints. Ordinary HTTP does not require an origin allowlist by default. Existing HTTPS settings keep their scheme: Agenvas never falls back to HTTP when TLS or a connection fails. This behavior requires an updated server build; changing Compose alone cannot update an older image.

HTTP sends API credentials, prompts, materials, passwords or signed links without encryption. The settings forms explain this risk. Choose the service host, port and protocol you intend; prefer HTTPS on networks you do not trust. A private IP does not identify an application: administrators must select the intended model/storage service rather than a router or management console. Model output and provider responses cannot configure a new service endpoint.

## Which connections accept HTTP?

| Connection | Protocol and address rules |
| --- | --- |
| LLM and model metadata | HTTP or HTTPS; public, RFC1918, IPv6 ULA and existing proxy fake-IP support; deployment may restrict HTTP |
| ComfyUI, OpenAI/Gemini image and RunningHub-compatible services | HTTP or HTTPS with the saved API prefix/root; public/private service addresses |
| WAN workspace and Google native video | User-selected HTTP or HTTPS; existing official hostname, region/profile and fixed API-path rules remain |
| Object stores and WebDAV | HTTP or HTTPS; public/private service addresses; storage loopback stays blocked |
| Media relay | Selected object-store protocol; must be publicly reachable and resolve to real public addresses; receiving providers may require HTTPS |
| Third-party prompt feeds and imported reference downloads | HTTP or HTTPS, **public addresses only**; no URL credentials, literal IPs or redirects |
| Fixed official service presets | Existing HTTPS URLs stay unchanged; no automatic protocol rewrite |
| SMB | Existing SMB2/3 protocol and signing rules stay unchanged |

Known metadata and special addresses remain blocked regardless of HTTP versus HTTPS: link-local, unspecified, multicast, CGNAT, Azure `168.63.129.16`, AWS `fd00:ec2::254`, Google `fd20:ce::254`, deprecated IPv6 site-local and known translation/tunneling ranges. Runtime checks cover literals (which OkHttp otherwise resolves without its custom DNS callback) and **every** DNS answer, including mixed answers. Numeric spellings cannot bypass resolved address checks; scoped IPv6 is rejected. DNS changes to permitted public/private service addresses remain possible: selecting a hostname trusts its operator and DNS, while changes to metadata, loopback aliases or other blocked addresses fail.

Literal `127.0.0.1` still needs `AGENVAS_LLM_ALLOW_LOOPBACK_HTTP=true` for LLM. Configurable media services retain their existing explicit literal loopback support. Storage and public feed transports reject loopback. This does not grant arbitrary URL import, network scanning or access to application management interfaces. Service requests retain fixed selected origins and API paths.

Saving media/storage configuration checks URL syntax and literal addresses without network I/O in database transactions. Hostnames are provisional until a connection validates all DNS answers. LLM saving retains its existing DNS preflight. Saving alone does not run a model or submit a generation.

## Docker host services

Use `http://host.docker.internal:11434/v1` for host Ollama and `http://host.docker.internal:8188` for host ComfyUI. Standard Compose includes the `host.docker.internal:host-gateway` mapping. A container's `127.0.0.1` refers to that container. The host service must listen on a reachable interface and the firewall must permit the trusted Docker network; the alias does not forward ports, change listeners or start a proxy. Windows/WSL reachability still needs verification from the server container. Another container can use its service name and actual port.

HTTP defaults to port 80, HTTPS to 443; explicit valid ports remain meaningful. IPv6 literals use brackets, for example `http://[fd12::50]:8188`. Object stores using literal IPs normally need path-style addressing. Preserve the API prefix required by the selected service; RunningHub expects an origin, while ComfyUI accepts a proxy prefix and keeps path credentials encrypted and hidden.

See [Docker host access](https://docs.docker.com/desktop/features/networking/networking-how-tos/) and [host-gateway configuration](https://docs.docker.com/reference/cli/dockerd/#configure-host-gateway-ip).

## Optional deployment restrictions and existing settings

`AGENVAS_LLM_ALLOW_HTTP` / `agenvas.settings.llm.allow-http` defaults to **true**. Set it to false and recreate the server to restore the previous exact-origin-only LLM HTTP policy. This is optional; ordinary public/private HTTP needs no allowlist with the default. Compose declares values in YAML; a standalone JVM can use the environment variable directly.

The existing `AGENVAS_LLM_TRUSTED_HTTP_ORIGINS` retains its meaning: up to 32 exact HTTP origins without a path, wildcard, credentials, query or fragment. It supplies explicit exceptions when general LLM HTTP is disabled, and cannot override metadata or special-address rejection. Removing an entry alone does not revoke an origin while general HTTP is enabled. To revoke non-loopback LLM HTTP, disable general HTTP and clear the list. The literal loopback option remains independent. These LLM controls do not alter media/storage/feed rules.

`deploy/compose.ollama.yaml` retains its older host mapping and exact-origin entry without disabling general HTTP. To choose stricter LLM behavior, explicitly set `AGENVAS_LLM_ALLOW_HTTP: "false"` in your deployment in addition to the intended origin list. Existing configurations, encrypted credentials and saved HTTPS endpoints remain intact. No database migration is required.

## Results, redirects and browser behavior

LLM requests, model metadata, storage and feed transport never follow redirects. Submissions retain fixed API paths and no implicit paid retries. OpenAI image downloads may use the explicitly selected service origin, or a public HTTPS destination; at most three redirects are checked individually before a request. RunningHub uses the same destination boundary without redirects. Other private hosts/ports, metadata and unrequested plaintext cross-origin downloads are rejected. Result GETs send no API credentials. A redirect from HTTPS cannot return to HTTP, even when the initial selected service used HTTP.

ComfyUI retains controlled output interfaces and separately checked HTTPS cross-origin redirects, including its existing HTTPS fake-IP proxy DNS compatibility. OpenAI/RunningHub cross-origin results and media-relay endpoints require real public addresses. Service HTTP permission cannot be transferred through a response to another private service.

Authorized relay reference URLs retain the chosen HTTP(S) scheme in LLM, image and native media payloads; Agenvas does not reject HTTP before contacting the provider solely because of its scheme.

No certificate checks, browser mixed-content protection, secure-cookie requirements or browser security warnings are bypassed. A browser or receiving provider may reject HTTP content under its own policy; Agenvas does not downgrade HTTPS or disable those protections to make it load.

## Verification limits

Tests use synthetic DNS and fake HTTP services plus isolated PostgreSQL to cover public/private HTTP and HTTPS settings, blocked addresses, literals, runtime policy, prefixes, signing, credential masking, mixed/rebound DNS and redirects. They do not prove live provider compatibility, LAN routing, IPv6 reachability or Windows/WSL behavior. Full suites remain manually run per AGENTS.md.
