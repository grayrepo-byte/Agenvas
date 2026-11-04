# Local Ollama with Agenvas

Agenvas uses Ollama's OpenAI-compatible chat endpoint. The default deployment allows HTTPS model endpoints, including private-network HTTPS. Plain HTTP is refused unless the deployment operator explicitly trusts that exact origin. The administrator's model form cannot grant this exception.

The exact-origin option is new after v0.0.8. Use a server image built from code containing this change; changing Compose alone cannot add it to a v0.0.7/v0.0.8 image. From a source checkout, build the updated server and web explicitly:

```sh
docker compose -f docker-compose.local.yml -f deploy/compose.ollama.yaml up -d --build --wait
```

## Docker to a host Ollama

A container's `127.0.0.1` refers to that container, not the host. The old `AGENVAS_LLM_ALLOW_LOOPBACK_HTTP` switch is only for a model sharing the server's network namespace, such as a standalone JVM calling a host-local model. It does not make a host Ollama reachable from Docker.

1. Make Ollama reachable from the server container on an interface restricted to the trusted Docker network. Do not expose its unauthenticated local API to the Internet. Docker Desktop provides `host.docker.internal`; Linux Docker Engine needs the `host-gateway` mapping included in this override. Windows/WSL and Docker networking differ, so verify reachability from the server container rather than assuming host `localhost` works.
2. Start with the explicit override in this repository:

   ```sh
   docker compose -f docker-compose.yml -f deploy/compose.ollama.yaml up -d --wait
   ```

   If you downloaded only `docker-compose.yml`, create `compose.ollama.yaml` beside it:

   ```yaml
   services:
     server:
       environment:
         AGENVAS_LLM_TRUSTED_HTTP_ORIGINS: "http://host.docker.internal:11434"
       extra_hosts:
         - "host.docker.internal:host-gateway"
   ```

   Use `-f compose.ollama.yaml` instead of the repository path in the command. Keep the same two `-f` arguments on later `up`, update and restart operations. For a model in another container, use that service's exact origin and shared Docker network instead; a container's loopback still will not reach it.
3. In **Settings → Model**, save:

   | Field | Example |
   | --- | --- |
   | Endpoint | `http://host.docker.internal:11434/v1` |
   | Model ID | The exact Ollama model name, such as `agenvas-qwen` below |
   | API key | `ollama-local` for native local Ollama |

   Agenvas requires 8–4096 printable ASCII characters for this field, so the common six-character placeholder `ollama` is rejected. `ollama-local` satisfies the existing contract. Native local Ollama ignores this placeholder; an authenticated proxy requires its real server-side credential. The saved value remains encrypted and masked.
4. Select the **Ollama** provider profile and **Read model list** in this same form.
   Automatic capacity requires the exact model to be loaded with its intended context;
   an unloaded model remains unknown. The advertised `/api/show` maximum does not
   allocate memory. Confirm manually when native metadata is unavailable.
5. Run the explicit tool diagnostic before enabling Agent work. Successful HTTP connectivity does not prove tool support or adequate context capacity. No model call occurs merely by saving these settings.

### Other services on the same host

The alias and `host-gateway` entry only resolve the host; they do not start a reverse proxy, forward ports, or change a service's listen address. Ollama and ComfyUI still use their own ports and must be reachable from the server container. On Windows/WSL, confirm whether the service runs in Windows, WSL, or another container.

This LLM override does not change ComfyUI's separate endpoint policy. Its current plain-HTTP form requires an exact local/private IPv4 address and port, such as `http://<host-private-ip>:8188`; `http://host.docker.internal:8188` is not accepted by that policy. HTTPS endpoints remain a separate supported route. The [host-service discovery control](local-service-discovery.md) can identify a standard Ollama or ComfyUI status interface and fill a permitted address. It does not scan networks or ports or grant additional endpoint access. ComfyUI discovery fills the specific private IPv4 address that answered, preserving its existing HTTP rule.

## Provider context capacity

Agenvas's Director context budget does not configure Ollama's model context. Ollama's OpenAI-compatible endpoint does not provide that setting; configure the model itself. For example, for an already installed model:

```text
FROM qwen2.5:14b
PARAMETER num_ctx 16384
```

Save as `Modelfile`, then run `ollama create agenvas-qwen -f Modelfile` on the Ollama host and select `agenvas-qwen` in Agenvas. Verify the loaded context with `ollama ps`, then reread model metadata in Agenvas's connection form. Automatic mode uses the loaded capacity and checks it again before generation; it never sets `num_ctx` or loads weights. The Director budget is an additional application ceiling. This preflight cannot prevent a later Ollama reload, so provider logs still matter. More context consumes more memory. The external review reported success for one small scenario at 16K; that is not an established minimum or a guarantee for longer tasks. An upstream service can silently truncate without reporting it, so inspect provider logs when behavior suggests lost instructions and review the actual canvas results.

## Scope of the exception

`AGENVAS_LLM_TRUSTED_HTTP_ORIGINS` is a comma-separated list of at most 32 HTTP origins. Each origin grants one host and effective port, for example `http://192.168.1.50:11434`; it has no path, credentials, query, fragment, wildcard or subnet. The model's `/v1` prefix belongs in the saved endpoint, not in the allowlist. A standalone JVM can use this environment variable directly; Compose declares visible values in its YAML and uses the explicit override above.

The default list is empty. Changing the origin requires changing deployment configuration and recreating the server. Removing it and recreating the server revokes saved HTTP endpoints. Requests still use the configured completion path and origin, reject redirects and automatic transport retries, pin approved DNS answers, and refuse loopback aliases, link-local/cloud metadata, unspecified, multicast and other blocked addresses. An allowlist entry does not override those address checks. The separate literal `127.0.0.1` option remains required for loopback. HTTPS private-network and proxy fake-IP handling retain ADR 0007's existing behavior.

## Verification scope and references

Policy and synthetic transport tests exercise origin matching, runtime revocation, metadata/DNS rejection, and existing request/redirect boundaries. They do not establish real Ollama model compatibility, Windows/WSL reachability, generation quality or the context capacity needed for a particular project.

- [Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility): local placeholder credentials and model context configuration.
- [Ollama context length](https://docs.ollama.com/context-length): verify the loaded context and memory implications.
- [Docker Compose networking](https://docs.docker.com/compose/how-tos/networking/): host access and `host-gateway` mapping.
