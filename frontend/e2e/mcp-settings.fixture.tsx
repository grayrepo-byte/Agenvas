import { QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../src/app/queryClient";
import { SystemSettingsPage } from "../src/features/settings/SystemSettingsPage";
import { type CreateMcpTokenRequest, type McpTokenSummary } from "../src/shared/api/client";
import { setLocale } from "../src/shared/i18n";
import "../src/styles.css";

// Entirely synthetic settings UI evidence. No real credentials, backend or Provider requests.
setLocale("zh");
const now = "2026-10-22T00:00:00Z";
const secret = "agv_mcp_synthetic_browser_fixture_not_a_real_credential";
const token: McpTokenSummary = { id: "synthetic-active", name: "Synthetic desktop client", tokenHint: "agv_mcp_synthetic…",
  scopes: ["READ", "CONTENT_WRITE"], version: 0, createdAt: now, expiresAt: "2099-11-01T00:00:00Z", lastUsedAt: null, revokedAt: null };
let tokens: McpTokenSummary[] = [token,
  { ...token, id: "synthetic-expired", name: "Expired synthetic client", expiresAt: "2025-01-01T00:00:00Z" },
  { ...token, id: "synthetic-revoked", name: "Revoked synthetic client", revokedAt: now },
];
const client = createQueryClient();
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  const method = init?.method ?? (input instanceof Request ? input.method : "GET");
  let value: unknown;
  let status = 200;
  if (url.pathname.endsWith("/auth/me")) value = { id: "synthetic-owner", loginName: "Synthetic owner", role: "ADMIN" };
  else if (url.pathname.endsWith("/auth/csrf")) value = { token: "synthetic-csrf", headerName: "X-CSRF-TOKEN" };
  else if (url.pathname.endsWith("/account/mcp-tokens") && method === "GET") value = tokens;
  else if (url.pathname.endsWith("/account/mcp-tokens") && method === "POST") {
    const body = JSON.parse(String(init?.body)) as CreateMcpTokenRequest;
    const created: McpTokenSummary = { ...token, id: "synthetic-created", name: body.name, scopes: body.scopes };
    tokens = [created, ...tokens]; value = { credential: created, token: secret }; status = 201;
  } else if (url.pathname.endsWith("/revoke") && method === "POST") {
    const id = url.pathname.split("/").at(-2);
    const found = tokens.find((item) => item.id === id);
    if (!found) throw new Error("Unknown fixture token");
    const revoked = { ...found, version: found.version + 1, revokedAt: now };
    tokens = tokens.map((item) => item.id === id ? revoked : item); value = revoked;
  } else throw new Error(`Unexpected synthetic request: ${method} ${url.pathname}`);
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
};
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={client}>
  <MemoryRouter initialEntries={["/settings/general?tab=security"]}><SystemSettingsPage /></MemoryRouter>
</QueryClientProvider>);
