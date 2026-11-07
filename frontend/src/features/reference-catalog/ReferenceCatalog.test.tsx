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
function setup(items: object[] = [entry], admin = false, filters?: object, initialSources: object[] = [], readEntries?: (request: Request) => Response) {
  setLocale("en");
  server.use(...cacheReads(),
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "user", role: admin ? "ADMIN" : "USER" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: initialSources })),
    http.get("/api/v1/reference-catalog", ({ request }) => readEntries?.(request) ?? HttpResponse.json({ items, total: items.length, offset: 0, limit: 50, filters })),
  );
  render(<QueryClientProvider client={createQueryClient()}><ReferenceCatalog /></QueryClientProvider>);
  return userEvent.setup();
}
it("prefers a saved website directory on reopening and loads all sources only when explicitly selected", async () => {
  const sources = ["OMILIC", "GITHUB"].map((sourceType) => ({ sourceType, id: sourceType.toLowerCase(), name: sourceType, owner: "hongye007", repository: "open-pose-costume", ref: "main", manifestPath: sourceType === "OMILIC" ? "data/catalog.json" : "public/data/catalog.json", format: "OPEN_POSE_COSTUME", enabled: true, imageDistributionApproved: false, version: 1, currentSnapshotId: "snapshot", resolvedCommit: null, lastSyncStatus: "SUCCESS", lastSyncError: null, syncedAt: "2026-11-07T00:00:00Z" }));
  const reads: (string | null)[] = [];
  const user = setup([], false, undefined, sources, (request) => {
    const source = new URL(request.url).searchParams.get("sourceId"); reads.push(source);
    const items = [{ ...entry, sourceId: "omilic", title: "Website pose" }, { ...entry, sourceId: "github", title: "Legacy GitHub pose" }].filter((entry) => !source || entry.sourceId === source);
    return HttpResponse.json({ items, total: items.length, offset: 0, limit: 50 });
  });
  await screen.findByRole("button", { name: "Preview Website pose" });
  expect(reads).toEqual(["omilic"]);
  expect(screen.queryByRole("button", { name: "Preview Legacy GitHub pose" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("combobox", { name: "Source" })); await user.click(screen.getByRole("option", { name: "All" }));
  expect(await screen.findByRole("button", { name: "Preview Legacy GitHub pose" })).toBeInTheDocument();
  expect(reads.at(-1)).toBeNull();
});
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
it("loads a preset and immediately shows that source's catalog", async () => {
  const user = setup([], true); let saved: object | null = null;
  server.use(http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: saved ? [saved] : [] })),
    http.post("/api/v1/settings/reference-catalog/sources", async ({ request }) => {
      saved = { ...await request.json() as object, enabled: true, version: 0 }; return HttpResponse.json(saved);
    }),
    http.post("/api/v1/settings/reference-catalog/sources/omilic/sync", () => HttpResponse.json({ state: "SUCCESS", source: { ...saved, currentSnapshotId: "snapshot" }, snapshotId: "snapshot", entryCount: 1, variantCount: 1 })));
  await user.click(await screen.findByRole("button", { name: "Manage sources" }));
  await user.click(screen.getByRole("button", { name: "Load" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Reference sources" })).not.toBeInTheDocument());
  expect(screen.getByRole("combobox", { name: "Source" })).toHaveTextContent("Omilic");
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
it("reports a failed load returned with HTTP 200 and retains the prior catalog", async () => {
  setLocale("en"); const source = { id: "pack", name: "Original pack", sourceType: "GITHUB", owner: "me", repository: "pack", ref: "main", manifestPath: "manifest.json", format: "AGENVAS_REFERENCE_PACK", enabled: true, imageDistributionApproved: true, version: 4, currentSnapshotId: "snapshot", resolvedCommit: "original-commit", lastSyncStatus: "SUCCESS", lastSyncError: null, syncedAt: "2026-11-01T00:00:00Z" };
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
  await user.click(screen.getByRole("combobox", { name: "Source" }));
  await user.click(screen.getByRole("option", { name: "Original pack" }));
  await user.click(screen.getByRole("button", { name: "Refresh catalog" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("The last successful catalog remains available.");
  expect(screen.getByRole("button", { name: "Retry loading" })).toBeEnabled();
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

it("keeps source loading separate from cache maintenance", async () => {
  const user = setup([], true); await user.click(await screen.findByRole("button", { name: "Manage sources" }));
  expect(screen.getAllByRole("combobox")).toHaveLength(1);
  expect(screen.queryByRole("heading", { name: "Local image cache" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Cache authorized images" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Load" })).toBeEnabled();
});
it("labels a preview cached only when the server reports that exact variant as cached", async () => {
  const user = setup([{ ...entry, variants: [{ ...variant, cached: true }] }]);
  await user.click(await screen.findByRole("button", { name: "Preview Walking" }));
  expect(screen.getByText("Cached locally on this server")).toBeInTheDocument();
});
