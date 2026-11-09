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

const costume = { ...entry, id: "custom-costume", kind: "COSTUME", sourceId: "self-created", snapshotId: "custom-costume", origin: "SELF_CREATED", version: 3, title: "Saved outfit", tags: ["linen"], category: "Casual", variants: [{ ...variant, previewUrl: "/api/v1/reference-catalog/costumes/custom-costume/image" }] };
it("browses saved and third-party costumes together without source-management tabs", async () => {
  const user = setup([costume, { ...entry, kind: "COSTUME", title: "Third-party outfit" }], true);
  await user.click(screen.getByRole("radio", { name: "Costumes" }));
  expect(await screen.findByRole("button", { name: "Preview Saved outfit" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Preview Third-party outfit" })).toBeInTheDocument();
  expect(screen.queryByRole("tab")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Preview Saved outfit" }));
  expect(screen.getByRole("img")).toHaveAttribute("src", costume.variants[0]!.previewUrl);
  expect(screen.getByRole("button", { name: "Edit costume details" })).toBeInTheDocument();
});
it("imports saved costumes by identity and CAS version with one stable replay command", async () => {
  setLocale("en"); const requests: Record<string, unknown>[] = []; let applied = "";
  server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/reference-catalog", () => HttpResponse.json({ items: [costume], total: 1, offset: 0, limit: 50 })),
    http.post("/api/v1/projects/project/reference-catalog/costumes/custom-costume/import", async ({ request }) => {
      requests.push(await request.json() as Record<string, unknown>);
      return requests.length === 1 ? HttpResponse.error() : HttpResponse.json({ versionId: "project-copy", assetId: "owned-image", title: costume.title });
    }));
  render(<QueryClientProvider client={createQueryClient()}><ReferenceCatalog projectId="project" placement={{ x: 80, y: 80 }} onApply={async (image) => { applied = image.versionId; }} /></QueryClientProvider>);
  const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" }));
  await user.click(screen.getByRole("button", { name: "Append as reference" })); await screen.findByRole("alert");
  expect(applied).toBe(""); await user.click(screen.getByRole("button", { name: "Retry" }));
  await waitFor(() => expect(applied).toBe("project-copy"));
  expect(requests).toHaveLength(2); expect(requests[0]).toEqual(requests[1]);
  expect(requests[0]).toMatchObject({ expectedVersion: 3, placement: { x: 80, y: 80 } });
  expect(requests[0]).not.toHaveProperty("sourceId");
});
it("keeps costume metadata after a CAS conflict and deletes only after confirmation", async () => {
  const user = setup([costume], true); const updates: Record<string, unknown>[] = []; const deletions: URL[] = [];
  server.use(http.patch("/api/v1/reference-catalog/costumes/custom-costume", async ({ request }) => {
    updates.push(await request.json() as Record<string, unknown>);
    return HttpResponse.json({ title: "Details changed remotely", code: "VERSION_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
  }), http.delete("/api/v1/reference-catalog/costumes/custom-costume", ({ request }) => { deletions.push(new URL(request.url)); return new HttpResponse(null, { status: 204 }); }));
  await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" }));
  await user.click(screen.getByRole("button", { name: "Edit costume details" }));
  const name = screen.getByRole("textbox", { name: "Name" }); await user.clear(name); await user.type(name, "Edited linen outfit");
  await user.click(screen.getByRole("button", { name: "Save details" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Your input has been kept");
  expect(name).toHaveValue("Edited linen outfit"); expect(updates[0]).toMatchObject({ expectedVersion: 3, name: "Edited linen outfit", tags: ["linen"] });
  await user.click(screen.getByRole("button", { name: "Cancel" }));
  await user.click(screen.getByRole("button", { name: "Delete costume reference" })); expect(deletions).toHaveLength(0);
  await user.click(screen.getByRole("button", { name: "Confirm deletion" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Saved outfit" })).not.toBeInTheDocument());
  expect(deletions[0]?.searchParams.get("expectedVersion")).toBe("3");
});
it("reopens an uncertain costume import with its original command and reuses the imported image after a draft conflict", async () => {
  setLocale("en"); const requests: Record<string, unknown>[] = []; let applied = 0;
  server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/reference-catalog", () => HttpResponse.json({ items: [costume], total: 1, offset: 0, limit: 50 })),
    http.post("/api/v1/projects/project/reference-catalog/costumes/custom-costume/import", async ({ request }) => {
      requests.push(await request.json() as Record<string, unknown>);
      return requests.length === 1 ? HttpResponse.error() : HttpResponse.json({ versionId: "owned-copy", assetId: "owned-image", title: costume.title });
    }));
  render(<QueryClientProvider client={createQueryClient()}><ReferenceCatalog projectId="project" onApply={async () => { applied++; if (applied === 1) throw new Error("Draft changed; input kept"); }} /></QueryClientProvider>);
  const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" }));
  await user.click(screen.getByRole("button", { name: "Append as reference" })); await screen.findByRole("alert");
  await user.click(screen.getByRole("button", { name: "Close" }));
  await user.click(screen.getByRole("button", { name: "Preview Saved outfit" }));
  await user.click(screen.getByRole("button", { name: "Retry" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Draft changed; input kept");
  await user.click(screen.getByRole("button", { name: "Retry" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Saved outfit" })).not.toBeInTheDocument());
  expect(requests).toHaveLength(2); expect(requests[0]).toEqual(requests[1]); expect(applied).toBe(2);
});
it("saves edited details on Enter without importing or applying the image", async () => {
  const user = setup([costume], true); let saved = false;
  server.use(http.patch("/api/v1/reference-catalog/costumes/custom-costume", async ({ request }) => {
    const input = await request.json() as Record<string, unknown>; saved = true;
    return HttpResponse.json({ ...costume, version: 4, title: input.name });
  }));
  await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" }));
  await user.click(screen.getByRole("button", { name: "Edit costume details" }));
  await user.type(screen.getByRole("textbox", { name: "Name" }), " updated{Enter}");
  await waitFor(() => expect(saved).toBe(true));
  expect(screen.getByRole("dialog", { name: "Saved outfit updated" })).toBeInTheDocument();
});
it("allows a fresh costume import after a rejected CAS request and catalog refresh", async () => {
  setLocale("en"); let current = costume; const requests: Record<string, unknown>[] = [];
  server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/reference-catalog", () => HttpResponse.json({ items: [current], total: 1, offset: 0, limit: 50 })),
    http.post("/api/v1/projects/project/reference-catalog/costumes/custom-costume/import", async ({ request }) => {
      requests.push(await request.json() as Record<string, unknown>);
      return requests.length === 1 ? HttpResponse.json({ title: "Costume changed", code: "VERSION_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } }) : HttpResponse.json({ versionId: "owned-copy" });
    }));
  render(<QueryClientProvider client={createQueryClient()}><ReferenceCatalog projectId="project" onApply={async () => {}} /></QueryClientProvider>);
  const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" }));
  await user.click(screen.getByRole("button", { name: "Append as reference" })); await screen.findByRole("alert");
  await user.click(screen.getByRole("button", { name: "Close" })); current = { ...costume, version: 4 };
  await user.click(screen.getByRole("button", { name: "Refresh" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Refresh" })).toBeEnabled());
  await user.click(screen.getByRole("button", { name: "Preview Saved outfit" }));
  await user.click(screen.getByRole("button", { name: "Retry" }));
  await waitFor(() => expect(requests).toHaveLength(2)); expect(requests[1]?.expectedVersion).toBe(4);
  expect(requests[0]?.commandKey).not.toBe(requests[1]?.commandKey);
});
it("keeps saved-costume management restricted to the administrator", async () => {
  const user = setup([costume]); await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" }));
  expect(screen.queryByRole("button", { name: "Edit costume details" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Delete costume reference" })).not.toBeInTheDocument();
});

it("refreshes the metadata CAS base in the open editor while preserving the local draft", async () => {
  const user = setup([costume], true); const updates: Record<string, unknown>[] = [];
  server.use(http.get("/api/v1/reference-catalog/costumes/custom-costume", () => HttpResponse.json({ ...costume, version: 4, title: "Changed remotely", tags: ["remote"] })),
    http.patch("/api/v1/reference-catalog/costumes/custom-costume", async ({ request }) => {
      const input = await request.json() as Record<string, unknown>; updates.push(input);
      return input.expectedVersion === 3 ? HttpResponse.json({ title: "Details changed remotely", code: "VERSION_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } })
        : HttpResponse.json({ ...costume, version: 5, title: input.name });
    }));
  await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" }));
  await user.click(screen.getByRole("button", { name: "Edit costume details" }));
  const name = screen.getByRole("textbox", { name: "Name" }); await user.clear(name); await user.type(name, "Local edits kept");
  await user.click(screen.getByRole("button", { name: "Save details" })); await screen.findByRole("alert");
  await user.click(screen.getByRole("button", { name: "Refresh details" }));
  await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  expect(name).toHaveValue("Local edits kept"); expect(screen.getByRole("textbox", { name: "Tags" })).toHaveValue("linen");
  await user.click(screen.getByRole("button", { name: "Save details" }));
  expect(await screen.findByRole("dialog", { name: "Local edits kept" })).toBeInTheDocument();
  expect(updates.map((input) => input.expectedVersion)).toEqual([3, 4]);
});
it("reconciles a lost successful metadata update by reading the exact next version", async () => {
  const user = setup([costume], true); let current = costume; let updates = 0;
  server.use(http.get("/api/v1/reference-catalog/costumes/custom-costume", () => HttpResponse.json(current)),
    http.patch("/api/v1/reference-catalog/costumes/custom-costume", async ({ request }) => {
      const input = await request.json() as Record<string, unknown>; updates++; current = { ...costume, version: 4, title: String(input.name) };
      return HttpResponse.error();
    }));
  await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" })); await user.click(screen.getByRole("button", { name: "Edit costume details" }));
  await user.type(screen.getByRole("textbox", { name: "Name" }), " updated");
  await user.click(screen.getByRole("button", { name: "Save details" })); await screen.findByRole("alert");
  await user.click(screen.getByRole("button", { name: "Refresh details" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Edit costume details" })).not.toBeInTheDocument());
  expect(screen.getByRole("dialog", { name: "Saved outfit updated" })).toBeInTheDocument(); expect(updates).toBe(1);
});
it("refreshes the deletion CAS base and retries deletion without reopening the preview", async () => {
  const user = setup([costume], true); const deletions: string[] = [];
  server.use(http.get("/api/v1/reference-catalog/costumes/custom-costume", () => HttpResponse.json({ ...costume, version: 4 })),
    http.delete("/api/v1/reference-catalog/costumes/custom-costume", ({ request }) => {
      const expected = new URL(request.url).searchParams.get("expectedVersion")!; deletions.push(expected);
      return expected === "3" ? HttpResponse.json({ title: "Details changed remotely", code: "VERSION_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } }) : new HttpResponse(null, { status: 204 });
    }));
  await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" })); await user.click(screen.getByRole("button", { name: "Delete costume reference" }));
  await user.click(screen.getByRole("button", { name: "Confirm deletion" })); await screen.findByRole("alert");
  await user.click(screen.getByRole("button", { name: "Refresh details" }));
  await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  await user.click(screen.getByRole("button", { name: "Confirm deletion" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Saved outfit" })).not.toBeInTheDocument()); expect(deletions).toEqual(["3", "4"]);
});
it("recognizes an already deleted entry after a lost deletion acknowledgement", async () => {
  const user = setup([costume], true); let deletions = 0;
  server.use(http.get("/api/v1/reference-catalog/costumes/custom-costume", () => HttpResponse.json({ title: "Not found", code: "NOT_FOUND" }, { status: 404, headers: { "Content-Type": "application/problem+json" } })),
    http.delete("/api/v1/reference-catalog/costumes/custom-costume", () => { deletions++; return HttpResponse.error(); }));
  await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" })); await user.click(screen.getByRole("button", { name: "Delete costume reference" }));
  await user.click(screen.getByRole("button", { name: "Confirm deletion" })); await screen.findByRole("alert");
  await user.click(screen.getByRole("button", { name: "Refresh details" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Saved outfit" })).not.toBeInTheDocument()); expect(deletions).toBe(1);
});
it("refreshes a stale costume import inside the preview and retains its project copy after a draft conflict", async () => {
  setLocale("en"); const requests: Record<string, unknown>[] = []; let applications = 0;
  server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ role: "USER" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/reference-catalog", () => HttpResponse.json({ items: [costume], total: 1, offset: 0, limit: 50 })),
    http.get("/api/v1/reference-catalog/costumes/custom-costume", () => HttpResponse.json({ ...costume, version: 4, title: "Current outfit" })),
    http.post("/api/v1/projects/project/reference-catalog/costumes/custom-costume/import", async ({ request }) => {
      const input = await request.json() as Record<string, unknown>; requests.push(input);
      return input.expectedVersion === 3 ? HttpResponse.json({ title: "Costume changed", code: "VERSION_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } }) : HttpResponse.json({ versionId: "owned-copy" });
    }));
  render(<QueryClientProvider client={createQueryClient()}><ReferenceCatalog projectId="project" onApply={async () => { applications++; if (applications === 1) throw new Error("Draft conflict; input kept"); }} /></QueryClientProvider>);
  const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" }));
  await user.click(screen.getByRole("button", { name: "Append as reference" })); await screen.findByRole("alert");
  await user.click(screen.getByRole("button", { name: "Refresh details" }));
  await screen.findByRole("dialog", { name: "Current outfit" });
  await user.click(screen.getByRole("button", { name: "Retry" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Draft conflict; input kept");
  await user.click(screen.getByRole("button", { name: "Retry" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Current outfit" })).not.toBeInTheDocument());
  expect(requests.map((input) => input.expectedVersion)).toEqual([3, 4]); expect(requests[0]?.commandKey).not.toBe(requests[1]?.commandKey); expect(applications).toBe(2);
});
it("keeps refresh recovery available when an uncertain metadata editor is reopened", async () => {
  const user = setup([costume], true);
  server.use(http.patch("/api/v1/reference-catalog/costumes/custom-costume", () => HttpResponse.error()),
    http.get("/api/v1/reference-catalog/costumes/custom-costume", () => HttpResponse.json({ ...costume, version: 4 })));
  await user.click(await screen.findByRole("button", { name: "Preview Saved outfit" })); await user.click(screen.getByRole("button", { name: "Edit costume details" }));
  await user.type(screen.getByRole("textbox", { name: "Name" }), " pending");
  await user.click(screen.getByRole("button", { name: "Save details" })); await screen.findByRole("alert");
  await user.click(screen.getByRole("button", { name: "Cancel" })); await user.click(screen.getByRole("button", { name: "Edit costume details" }));
  await user.click(screen.getByRole("button", { name: "Refresh details" }));
  await waitFor(() => expect(screen.getByRole("textbox", { name: "Name" })).toBeEnabled());
  expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Saved outfit pending");
});
it("combines saved website and self-created costumes by default without broadening explicit source filters", async () => {
  const sources = ["OMILIC", "GITHUB"].map((sourceType) => ({ sourceType, id: sourceType.toLowerCase(), name: sourceType,
    enabled: true, version: 1, currentSnapshotId: "snapshot" }));
  const reads: URL[] = [];
  const external = [{ ...entry, sourceId: "omilic", title: "Website pose" },
    { ...entry, kind: "COSTUME", sourceId: "omilic", title: "Website outfit" },
    { ...entry, kind: "COSTUME", sourceId: "github", title: "GitHub outfit" }];
  const user = setup([], false, undefined, sources, (request) => {
    const url = new URL(request.url); reads.push(url);
    const source = url.searchParams.get("sourceId"); const includeSelfCreated = url.searchParams.get("includeSelfCreated") === "true";
    const items = [...external, costume].filter((item) => item.kind === url.searchParams.get("kind")
      && (!source || item.sourceId === source || includeSelfCreated && "origin" in item && item.origin === "SELF_CREATED"));
    return HttpResponse.json({ items, total: items.length, offset: 0, limit: 50 });
  });
  await screen.findByRole("button", { name: "Preview Website pose" });
  await user.click(screen.getByRole("radio", { name: "Costumes" }));
  await screen.findByRole("button", { name: "Preview Website outfit" });
  expect(await screen.findByRole("button", { name: "Preview Saved outfit" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Preview GitHub outfit" })).not.toBeInTheDocument();
  expect(reads.every((url) => url.searchParams.get("sourceId") === "omilic")).toBe(true);
  expect(reads[0]?.searchParams.has("includeSelfCreated")).toBe(false);
  expect(reads.at(-1)?.searchParams.get("includeSelfCreated")).toBe("true");
  await user.click(screen.getByRole("combobox", { name: "Source" })); await user.click(screen.getByRole("option", { name: "GITHUB" }));
  await screen.findByRole("button", { name: "Preview GitHub outfit" });
  expect(screen.queryByRole("button", { name: "Preview Saved outfit" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Preview Website outfit" })).not.toBeInTheDocument();
  expect(reads.at(-1)?.searchParams.has("includeSelfCreated")).toBe(false);
  await user.click(screen.getByRole("combobox", { name: "Source" })); await user.click(screen.getByRole("option", { name: "OMILIC" }));
  await screen.findByRole("button", { name: "Preview Website outfit" });
  expect(screen.queryByRole("button", { name: "Preview Saved outfit" })).not.toBeInTheDocument();
  expect(reads.at(-1)?.searchParams.has("includeSelfCreated")).toBe(false);
  await user.click(screen.getByRole("combobox", { name: "Source" })); await user.click(screen.getByRole("option", { name: "All" }));
  expect(await screen.findByRole("button", { name: "Preview Saved outfit" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Preview Website outfit" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Preview GitHub outfit" })).toBeInTheDocument();
});
