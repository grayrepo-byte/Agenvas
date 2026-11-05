# Local Ollama with Agenvas

Agenvas uses Ollama's OpenAI-compatible chat endpoint. Public and private HTTP model endpoints are allowed by default, including private DNS names and Docker host aliases; ordinary HTTP needs no allowlist. Use only trusted networks: HTTP sends prompts and credentials without encryption. See [HTTP/HTTPS service policy](http-endpoints.md) for the complete ranges, controls and download boundaries.

This behavior requires a server image built from the updated code; changing Compose alone cannot add it to an older image. From a source checkout, build the updated server and web explicitly:

```sh
docker compose -f docker-compose.local.yml up -d --build --wait
```

## Docker to a host Ollama

A container's `127.0.0.1` refers to that container, not the host. `AGENVAS_LLM_ALLOW_LOOPBACK_HTTP` is only for a model sharing the server's network namespace, such as a standalone JVM calling a host-local model. It does not make a host Ollama reachable from Docker.

1. Make Ollama reachable from the server container on an interface restricted to the trusted Docker network. Do not expose its unauthenticated local API to the Internet. Standard Compose supplies `host.docker.internal` and the Linux `host-gateway` mapping. Windows/WSL networking varies; verify reachability from the container.
2. Start the standard deployment:

   ```sh
   docker compose -f docker-compose.yml up -d --wait
   ```

   No HTTP allowlist override is needed when every host alias answer passes the service address policy. For a model in another container, use its service name and port on the shared Docker network.
3. In **Settings → Model**, save:

   | Field | Example |
   | --- | --- |
   | Endpoint | `http://host.docker.internal:11434/v1` |
   | Model ID | The exact Ollama model name, such as `agenvas-qwen` below |
   | API key | `ollama-local` for native local Ollama |

   Agenvas requires 8–4096 printable ASCII characters here, so the six-character placeholder `ollama` is rejected. Native Ollama ignores the placeholder; an authenticated proxy requires its real server-side credential. Saved credentials remain encrypted and masked.
4. Select the **Ollama** provider profile and **Read model list**. Automatic capacity requires the exact model to be loaded with its intended context; an unloaded model remains unknown. The advertised `/api/show` maximum does not allocate memory. Confirm manually when native metadata is unavailable.
5. Run the explicit tool diagnostic before enabling Agent work. HTTP connectivity does not prove tool support or adequate context capacity. Saving alone makes no generation call.

### Other host services

The host alias only resolves an address. It does not start a reverse proxy, forward ports, change a service's listen address or configure a firewall. Ollama and ComfyUI keep their own ports; both must be reachable from the server container. ComfyUI now accepts `http://host.docker.internal:8188`, private IPv4/IPv6 or private service DNS, including a configured proxy prefix. The [host-service discovery control](local-service-discovery.md) can identify standard Ollama or ComfyUI status interfaces and fill a permitted address; it does not scan networks or grant endpoint access.

## Provider context capacity

Agenvas's Director context budget does not configure Ollama's model context. Ollama's OpenAI-compatible endpoint does not provide that setting; configure the model itself. For example, for an already installed model:

```text
FROM qwen2.5:14b
PARAMETER num_ctx 16384
```

Save as `Modelfile`, then run `ollama create agenvas-qwen -f Modelfile` on the Ollama host and select `agenvas-qwen` in Agenvas. Verify the loaded context with `ollama ps`, then reread model metadata in Agenvas's connection form. Automatic mode uses the loaded capacity and checks it again before generation; it never sets `num_ctx` or loads weights. The Director budget is an additional application ceiling. This preflight cannot prevent a later Ollama reload, so provider logs still matter. More context consumes more memory. The external review reported success for one small scenario at 16K; that is not an established minimum or a guarantee for longer tasks. An upstream service can silently truncate without reporting it, so inspect provider logs when behavior suggests lost instructions and review the actual canvas results.

## Optional exact-origin-only LLM HTTP

The existing `deploy/compose.ollama.yaml` host mapping and trusted-origin entry still work. They do not disable the new default HTTP support. No override is needed for ordinary HTTP model endpoints.

If you explicitly want the previous stricter deployment mode, add these values in your own Compose override and recreate the server:

```yaml
services:
  server:
    environment:
      AGENVAS_LLM_ALLOW_HTTP: "false"
      AGENVAS_LLM_TRUSTED_HTTP_ORIGINS: "http://host.docker.internal:11434"
```

Keep that override on later operations. See [deployment controls](http-endpoints.md#optional-deployment-restrictions-and-existing-settings). Removing an origin alone does not revoke it while general HTTP remains enabled. Neither control bypasses metadata or special-address checks; literal loopback keeps its separate option.

## Verification scope and references

Policy and synthetic transport tests exercise private admission, exact-origin matching, runtime revocation, metadata/mixed-DNS rejection and request/redirect boundaries. They do not establish real Ollama compatibility, Windows/WSL reachability, generation quality or a project's required context capacity.

- [Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility): local placeholder credentials and model context configuration.
- [Ollama context length](https://docs.ollama.com/context-length): verify the loaded context and memory implications.
- [Docker Compose networking](https://docs.docker.com/compose/how-tos/networking/): host access and `host-gateway` mapping.
