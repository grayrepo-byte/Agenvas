import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { setLocale } from "../../shared/i18n";
import { ReferenceCatalog } from "./ReferenceCatalog";

const variant = { id: "front", label: "Front view", view: "front", availability: "AVAILABLE", importAllowed: true, sha256: "abc", previewUrl: "/api/v1/reference-catalog/preview/front" };
function cacheReads() { return [
  http.get("/api/v1/settings/reference-catalog/cache", () => HttpResponse.json({ quotaBytes: 1000, usedBytes: 0, availableBytes: 1000, fileCount: 0, protectedBytes: 0, protectedFileCount: 0 })),
  http.get("/api/v1/settings/reference-catalog/cache/jobs", () => HttpResponse.json({ items: [] })),
]; }
const entry = { sourceId: "pack", snapshotId: "snapshot", id: "walk", kind: "POSE", title: "Walking", category: "Movement", tags: ["walk"], facets: { gender: "neutral" }, importAllowed: true, variants: [variant, { ...variant, id: "back", label: "Back view", view: "back" }] };
function setup(items: object[] = [entry], admin = false, filters?: object) {
  setLocale("en");
  server.use(...cacheReads(),
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "user", role: admin ? "ADMIN" : "USER" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/reference-catalog", () => HttpResponse.json({ items, total: items.length, offset: 0, limit: 50, filters })),
  );
  render(<QueryClientProvider client={createQueryClient()}><ReferenceCatalog /></QueryClientProvider>);
  return userEvent.setup();
}
it("keeps pose views grouped and preview selection preserves search", async () => {
  const user = setup();
  await user.type(screen.getByRole("searchbox", { name: "Search references" }), "walk");
  await user.click(await screen.findByRole("button", { name: "Preview Walking" }));
  const preview = screen.getByRole("dialog", { name: "Walking" });
  await user.click(within(preview).getByRole("radio", { name: "Back view" }));
  expect(within(preview).getByRole("img")).toHaveAttribute("alt", "Walking · Back view");
  await user.click(within(preview).getByRole("button", { name: "Close" }));
  expect(screen.getByRole("searchbox")).toHaveValue("walk");
  expect(screen.getAllByRole("button", { name: "Preview Walking" })).toHaveLength(1);
  await user.click(screen.getByRole("button", { name: "Preview Walking" }));
  expect(screen.getByRole("radio", { name: "Back view" })).toBeChecked();
});
it("never hotlinks metadata-only images and explains disabled import", async () => {
  const user = setup([{ ...entry, importAllowed: false, variants: [{ id: "link", label: "Original", availability: "LINK_ONLY", importAllowed: false, externalUrl: "https://example.com/reference", disabledReason: "REFERENCE_CATALOG_RIGHTS_REQUIRED", previewUrl: "https://example.com/private.jpg" }] }]);
  await user.click(await screen.findByRole("button", { name: "Preview Walking" }));
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Open original source" })).toHaveAttribute("href", "https://example.com/reference");
  expect(screen.getByText("Image distribution permission has not been verified. Browse metadata or open the original source.")).toBeInTheDocument();
});
it("retries a catalog read without losing filters", async () => {
  const user = setup(); let failed = true;
  server.use(http.get("/api/v1/reference-catalog", ({ request }) => {
    if (failed) return HttpResponse.json({ title: "Unavailable", code: "OFFLINE" }, { status: 503, headers: { "Content-Type": "application/problem+json" } });
    expect(new URL(request.url).searchParams.get("query")).toBe("walk");
    return HttpResponse.json({ items: [entry], total: 1, offset: 0, limit: 50 });
  }));
  await user.type(screen.getByRole("searchbox"), "walk");
  await screen.findByRole("alert"); failed = false;
  await user.click(screen.getByRole("button", { name: "Retry" }));
  expect(await screen.findByRole("button", { name: "Preview Walking" })).toBeInTheDocument();
});
it("requires explicit permission review and manual sync for a new source", async () => {
  const user = setup([], true); const writes: Record<string, unknown>[] = [];
  server.use(http.post("/api/v1/settings/reference-catalog/sources", async ({ request }) => {
    writes.push(await request.json() as Record<string, unknown>);
    return HttpResponse.json({ id: "pack", name: "My pack", owner: "me", repository: "pack", ref: "main", manifestPath: "manifest.json", enabled: true, version: 1, imageDistributionApproved: false });
  }));
  await user.click(await screen.findByRole("button", { name: "Manage sources" }));
  const dialog = screen.getByRole("dialog", { name: "Reference sources" });
  await user.type(within(dialog).getByLabelText("Source ID"), "pack");
  await user.type(within(dialog).getByLabelText("Name"), "My pack");
  await user.type(within(dialog).getByLabelText("GitHub owner"), "me");
  await user.type(within(dialog).getByLabelText("Repository"), "pack");
  await user.click(within(dialog).getByRole("button", { name: "Add source" }));
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]?.imageDistributionApproved).toBe(false);
  expect(screen.getByText("Sources update only when an administrator chooses Sync. Failed syncs retain the last successful catalog.")).toBeInTheDocument();
});
it("replays a lost import with the same intent and never generates or replaces input", async () => {
  setLocale("en"); let attempts = 0; const imports: Record<string, unknown>[] = []; let appliedVersion = "";
  server.use(...cacheReads(),
    http.get("/api/v1/auth/me", () => HttpResponse.json({ role: "USER" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/reference-catalog", () => HttpResponse.json({ items: [entry], total: 1, offset: 0, limit: 50 })),
    http.post("/api/v1/projects/project/reference-catalog/import", async ({ request }) => {
      imports.push(await request.json() as Record<string, unknown>); attempts++;
      if (attempts === 1) return HttpResponse.error();
      return HttpResponse.json({ versionId: "owned-exact-image", assetId: "owned-asset", title: "Walking" });
    }),
  );
  render(<QueryClientProvider client={createQueryClient()}><ReferenceCatalog projectId="project" onApply={async (image) => { appliedVersion = image.versionId; }} /></QueryClientProvider>);
  const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Preview Walking" }));
  await user.click(screen.getByRole("button", { name: "Append as reference" }));
  await screen.findByRole("alert"); expect(appliedVersion).toBe("");
  await user.click(screen.getByRole("button", { name: "Retry" }));
  await waitFor(() => expect(appliedVersion).toBe("owned-exact-image"));
  expect(imports).toHaveLength(2); expect(imports[0]).toEqual(imports[1]);
  expect(imports[0]).toMatchObject({ snapshotId: "snapshot", variantId: "front", sha256: "abc" });
});
it("uses cached bilingual metadata and sends the real source facet filters", async () => {
  const user = setup([{ ...entry, title: "Fallback", localizedTitle: { zh: "行走", en: "Walking" }, code: "P001" }], false, { categories: ["movement"], tags: ["female"], styles: ["photo"], collections: ["builtin", "custom"], angles: ["front", "low"] });
  const requests: URL[] = [];
  server.use(http.get("/api/v1/reference-catalog", ({ request }) => {
    requests.push(new URL(request.url));
    return HttpResponse.json({ items: [{ ...entry, localizedTitle: { zh: "行走", en: "Walking" } }], total: 1, offset: 0, limit: 50,
      filters: { categories: ["movement"], tags: ["female"], styles: ["photo"], collections: ["builtin", "custom"], angles: ["front", "low"] } });
  }));
  await screen.findByRole("button", { name: "Preview Walking" });
  await user.click(screen.getByRole("combobox", { name: "Tag" })); await user.click(screen.getByRole("option", { name: "Female" }));
  await user.click(screen.getByRole("combobox", { name: "Style" })); await user.click(screen.getByRole("option", { name: "Photo" }));
  await waitFor(() => expect(requests.at(-1)?.searchParams.get("tag")).toBe("female"));
  expect(requests.at(-1)?.searchParams.get("style")).toBe("photo");
});
it("fills the actual repository metadata preset without fetching it", async () => {
  const user = setup([], true); await user.click(await screen.findByRole("button", { name: "Manage sources" }));
  await user.click(screen.getByRole("button", { name: "Fill Open Pose Costume preset" }));
  expect(screen.getByLabelText("GitHub owner")).toHaveValue("hongye007");
  expect(screen.getByLabelText("Repository")).toHaveValue("open-pose-costume");
  expect(screen.getByLabelText("Manifest path")).toHaveValue("public/data/catalog.json");
  expect(screen.getByRole("checkbox", { name: "I have verified permission to redistribute these image files." })).not.toBeChecked();
  expect(screen.getByRole("checkbox", { name: "I have verified permission to redistribute these image files." })).toBeDisabled();
});
it("reports a failed manual sync returned with HTTP 200 and retains the prior catalog", async () => {
  setLocale("en"); const source = { id: "pack", name: "Original pack", owner: "me", repository: "pack", ref: "main", manifestPath: "manifest.json", format: "AGENVAS_REFERENCE_PACK", enabled: true, imageDistributionApproved: true, version: 4, currentSnapshotId: "snapshot", resolvedCommit: "original-commit", lastSyncStatus: "SUCCESS", lastSyncError: null, syncedAt: "2026-11-01T00:00:00Z" };
  server.use(...cacheReads(),
    http.get("/api/v1/auth/me", () => HttpResponse.json({ role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: [source] })),
    http.get("/api/v1/reference-catalog", () => HttpResponse.json({ items: [entry], total: 1, offset: 0, limit: 50 })),
    http.post("/api/v1/settings/reference-catalog/sources/pack/sync", async ({ request }) => {
      expect(await request.json()).toEqual({ expectedVersion: 4 });
      return HttpResponse.json({ state: "FAILED", source: { ...source, lastSyncStatus: "FAILED", lastSyncError: "REFERENCE_CATALOG_REMOTE_UNAVAILABLE" }, snapshotId: "snapshot", entryCount: 0, variantCount: 0 });
    }),
  );
  render(<QueryClientProvider client={createQueryClient()}><ReferenceCatalog /></QueryClientProvider>);
  const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Manage sources" }));
  await user.click(await screen.findByRole("button", { name: "Sync" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("The last successful catalog remains available.");
  expect(screen.queryByText("Sync completed.")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Close" }));
  expect(screen.getByRole("button", { name: "Preview Walking" })).toBeInTheDocument();
});
it("explains a disabled source and keeps its metadata available", async () => {
  const user = setup([{ ...entry, importAllowed: false, disabledReason: "REFERENCE_CATALOG_SOURCE_DISABLED", variants: [{ ...variant, importAllowed: false, disabledReason: "REFERENCE_CATALOG_SOURCE_DISABLED" }] }]);
  await user.click(await screen.findByRole("button", { name: "Preview Walking" }));
  expect(screen.getByText("This source is disabled or unavailable. Its cached metadata remains available.")).toBeInTheDocument();
});
it("shows a preview error with a local retry", async () => {
  const user = setup(); await user.click(await screen.findByRole("button", { name: "Preview Walking" }));
  const image = screen.getByRole("img");
  fireEvent.error(image);
  expect(await screen.findByText("Image preview could not be loaded. Retry the preview or open the original source.")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Retry preview" }));
  expect(screen.getByRole("img")).toHaveAttribute("src", variant.previewUrl);
});

it("exposes image cache management separately from metadata source configuration", async () => {
  const user = setup([], true); await user.click(await screen.findByRole("button", { name: "Manage sources" }));
  expect(await screen.findByRole("heading", { name: "Local image cache" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Cache authorized images" })).toBeDisabled();
  expect(await screen.findByText("0 / 1,000 bytes · 0 files")).toBeInTheDocument();
});
it("labels a preview cached only when the server reports that exact variant as cached", async () => {
  const user = setup([{ ...entry, variants: [{ ...variant, cached: true }] }]);
  await user.click(await screen.findByRole("button", { name: "Preview Walking" }));
  expect(screen.getByText("Cached locally on this server")).toBeInTheDocument();
});
