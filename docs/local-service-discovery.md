# Find Ollama and ComfyUI on the Agenvas host

Model settings and ComfyUI connection forms offer a host-service discovery button. Click it to check the standard service on the machine running Agenvas, then explicitly choose the discovered address to fill the form. Nothing is saved automatically; existing drafts and manual address fields remain available.

The check runs from the Agenvas server, not your browser. If you access a remote Agenvas installation, “host” means that remote installation's host, not your laptop.

## What is checked

| Service | Port | Read-only request | Form value |
| --- | --- | --- | --- |
| Ollama | 11434 | `GET /api/version` | The discovered host endpoint with `/v1` |
| ComfyUI | 8188 | `GET /system_stats` | The specific IPv4/IPv6 address checked, without `/v1` or `/prompt` |

Checks do not send saved API keys, cookies, prompts, model requests or media tasks. No GPU or installed model is needed to identify the service. A recognized response means the status interface answered; it does not establish model capacity, tool support or successful generation.

Discovery examines one server-selected address for the service you chose. It does not scan subnets, try other ports, enumerate containers, follow redirects or guess credentials. Original service response bodies, versions, device details and launch arguments are discarded.

## Where the service is running

| Agenvas runtime | Candidate | Practical limits |
| --- | --- | --- |
| Docker Desktop on Mac / Windows | `host.docker.internal` | The service must listen on an interface reachable from Docker. Windows/WSL may put the service in another network layer. |
| Linux Docker Engine | The same name through `host-gateway` | Standard Compose supplies the mapping. Rootless or custom networking can still require manual setup. |
| Known native macOS / Windows JVM | `127.0.0.1` | The service must run on that same machine. |
| Known native Linux JVM | `127.0.0.1` after explicitly choosing `AGENVAS_LOCAL_SERVICE_RUNTIME=NATIVE` | AUTO leaves unrecognized Linux environments in manual mode instead of assuming container loopback is the host. |
| Another container, custom port, IPv6-only service, or unknown runtime | Manual configuration | Use the service's actual reachable endpoint and existing connection policy. |

Container markers are checked before native OS detection. Standard Compose explicitly declares `AGENVAS_LOCAL_SERVICE_RUNTIME: "CONTAINER"`; a deployment can select `NATIVE`, `CONTAINER` or `AUTO` to describe its own topology. This is a server configuration, not a browser-supplied target. Disabling optional discovery with `AGENVAS_LOCAL_SERVICE_DISCOVERY_ENABLED=false` leaves manual configuration available.

`host.docker.internal` and `host-gateway` supply a name/address mapping. They do not start a reverse proxy, forward ports, change service listen addresses, or alter host firewall settings. Containers never probe their own loopback as the host. Discovery does not alter the host's network or service configuration.

## Use a discovered address

A found service can be selected without address permission checks. No loopback switch, public/private classification or origin allowlist is required. ComfyUI returns the selected IPv4/IPv6 route; Ollama returns the host alias with `/v1`. A changed address requires a new discovery or manual update. Manual configuration remains available for other hosts or ports.

## Understanding failed checks

- **No service / timeout:** start the service, verify its listening interface and standard port, and check the network path. Repeated checks do not change those settings.
- **Host name unavailable:** use the standard Compose host mapping or configure a manual reachable address.
- **Authentication required / unexpected response:** use manual connection configuration for the authenticated service or proxy. No credential is tried automatically.
- **Busy:** another bounded check or resolver is still running, or the minimum interval has not elapsed. Wait before trying again.
- **Unknown runtime:** choose a correct server runtime mode or keep using manual configuration.

Each accepted request has an outward deadline of three seconds, including name resolution, and a bounded response size. A blocked OS resolver may remain in the single worker until the OS returns; new checks stay busy rather than creating more threads. Canceling stops the UI from waiting and prevents late results from changing the form.

## Verification scope

Focused synthetic DNS/HTTP and security-chain tests cover fixed requests, unrestricted DNS answer ranges, response identification, bounds, cancellation and administrator permissions. Real Ollama/ComfyUI generation, Windows/WSL routing and live deployment networks require separate acceptance.
