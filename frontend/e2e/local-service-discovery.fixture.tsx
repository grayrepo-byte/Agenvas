import { QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../src/app/queryClient";
import { LlmSettingsPage } from "../src/features/settings/LlmSettingsPage";
import { MediaSettingsPage } from "../src/features/settings/MediaSettingsPage";
import type { LocalServiceDiscoveryResult, LocalServiceKind } from "../src/shared/api/client";
import { setLocale } from "../src/shared/i18n";
import "../src/styles.css";

// Existing product settings pages with production CSS; only their API responses are synthetic.
// This fixture is not a product route and never contacts a backend or model.
setLocale("en");
const fixture: {
  mode: "FOUND" | "DELAYED"; requests: LocalServiceKind[]; writes: number; metadataReads: number;
  finishPending: () => void;
} = { mode: "FOUND", requests: [], writes: 0, metadataReads: 0, finishPending: () => {} };
Object.assign(window, { localServiceDiscoveryFixture: fixture });
function response(service: LocalServiceKind) {
  const result: LocalServiceDiscoveryResult = { service, runtime: "CONTAINER", status: "FOUND",
    endpoint: service === "OLLAMA" ? "http://host.docker.internal:11434/v1" : "http://192.168.65.254:8188" };
  return json(result);
}
function json(value: unknown) { return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" } }); }
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  const method = init?.method ?? "GET";
  if (url.pathname === "/api/v1/auth/me") return json({ id: "synthetic-owner", loginName: "Synthetic administrator", role: "ADMIN" });
  if (url.pathname === "/api/v1/auth/csrf") return json({ token: "synthetic", headerName: "X-CSRF-TOKEN" });
  if (url.pathname === "/api/v1/settings/llm" && method === "GET") return json({ configured: true, version: 4,
    endpoint: "https://manual.example.com/ollama", modelId: "saved-model", keyMask: "••••1234", toolCallingVerified: false, updatedAt: null });
  if (url.pathname === "/api/v1/settings/diagnostics" && method === "GET") return json({ checkedAt: "2026-11-04T00:00:00Z",
    database: "AVAILABLE", storage: "AVAILABLE", llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
    mediaMode: "MOCK", imageConfigured: false, videoConfigured: false, recentErrors: [] });
  if (url.pathname === "/api/v1/settings/media-connections" && method === "GET") return json({ connections: [], defaults: [] });
  if (url.pathname === "/api/v1/settings/llm/models" && method === "POST") {
    fixture.metadataReads++;
    return json({ id: "dcf3d1c0-6e87-48d5-bb76-27efeaf8ff93", partial: false, checkedAt: "2026-11-04T00:00:00Z",
      models: [{ id: "synthetic-model", contextWindowTokens: 32768, inputLimitTokens: null, outputLimitTokens: 2048,
        runtimeContextTokens: null, deploymentContextTokens: 16384, digest: null }] });
  }
  if (url.pathname !== "/api/v1/settings/local-services/discover" || method !== "POST") {
    if (method !== "GET") fixture.writes++;
    throw new Error("Unexpected fixture request");
  }
  const { service } = JSON.parse(String(init?.body)) as { service: LocalServiceKind };
  if (service !== "OLLAMA" && service !== "COMFYUI") throw new Error("Unexpected fixture service");
  fixture.requests.push(service);
  if (fixture.mode === "DELAYED") {
    // Deliberately finish after abort to verify late-response suppression in the actual settings form.
    return new Promise<Response>((resolve) => { fixture.finishPending = () => resolve(response(service)); });
  }
  return response(service);
};
const media = new URLSearchParams(location.search).get("surface") === "comfy";
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={createQueryClient()}>
  <MemoryRouter initialEntries={[media ? "/settings/media" : "/settings/providers"]}>
    {media ? <MediaSettingsPage /> : <LlmSettingsPage />}
  </MemoryRouter>
</QueryClientProvider>);
