import { QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../src/app/queryClient";
import { DirectorSettingsPage } from "../src/features/settings/DirectorSettingsPage";
import type { DirectorSettings, UpdateDirectorSettingsRequest } from "../src/shared/api/client";
import { setLocale } from "../src/shared/i18n";
import "../src/styles.css";

// Synthetic browser evidence only; never contacts a backend or model.
setLocale("zh");
let settings: DirectorSettings = { contextWindowTokens: 1_000_000, compactionThresholdPercent: 50,
  responseReserveTokens: 8192, summaryTokens: 2048, recentToolRounds: 4, recoveryWindowMinutes: 1440,
  maxModelRetries: 288, retryMaxDelaySeconds: 300, maxRepairAttempts: 4, version: 1 };
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  const method = init?.method ?? (input instanceof Request ? input.method : "GET");
  let value: unknown;
  let status = 200;
  if (url.pathname === "/api/v1/auth/me") value = { id: "synthetic-owner", loginName: "Synthetic owner", role: "ADMIN" };
  else if (url.pathname === "/api/v1/auth/csrf") value = { token: "synthetic", headerName: "X-CSRF-TOKEN" };
  else if (url.pathname === "/api/v1/settings/director" && method === "GET") value = settings;
  else if (url.pathname === "/api/v1/settings/director" && method === "PUT") {
    const { expectedVersion, ...values } = JSON.parse(String(init?.body)) as UpdateDirectorSettingsRequest;
    if (expectedVersion !== settings.version) { status = 409; value = { code: "VERSION_CONFLICT" }; }
    else { settings = { ...values, version: settings.version + 1 }; value = settings; }
  } else throw new Error(`Unexpected synthetic request: ${method} ${url.pathname}`);
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
};
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={createQueryClient()}>
  <MemoryRouter initialEntries={["/settings/director"]}><DirectorSettingsPage /></MemoryRouter>
</QueryClientProvider>);
