import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ReferenceSource } from "../../shared/api/referenceCatalog";
import { setLocale } from "../../shared/i18n";
import { server } from "../../test/server";
import { ReferenceSourcesDialog } from "./ReferenceSourcesDialog";

const preset: ReferenceSource = { id: "omilic", name: "Omilic", owner: "hongye007", repository: "open-pose-costume", ref: "main", format: "OPEN_POSE_COSTUME", manifestPath: "data/catalog.json", sourceType: "OMILIC", enabled: true, imageDistributionApproved: false, version: 0, currentSnapshotId: null, resolvedCommit: null, lastSyncStatus: "NEVER", lastSyncError: null, syncedAt: null };
function setup(initial: ReferenceSource[] = [], options: { failSync?: boolean; loseAddResponse?: boolean } = {}) {
  setLocale("en"); let items = initial; const creates: object[] = []; const syncs: { id: string; body: unknown }[] = []; const updates: object[] = [];
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items })),
    http.post("/api/v1/settings/reference-catalog/sources", async ({ request }) => {
      const body = await request.json() as typeof preset; creates.push(body); const source = { ...preset, ...body }; items = [...items, source]; return options.loseAddResponse ? HttpResponse.error() : HttpResponse.json(source);
    }),
    http.patch("/api/v1/settings/reference-catalog/sources/:id", async ({ request, params }) => {
      const body = await request.json() as { enabled: boolean; expectedVersion: number }; updates.push(body);
      const source = items.find((source) => source.id === params.id)!; const saved = { ...source, enabled: body.enabled, version: source.version + 1 }; items = items.map((item) => item.id === saved.id ? saved : item); return HttpResponse.json(saved);
    }),
    http.post("/api/v1/settings/reference-catalog/sources/:id/sync", async ({ request, params }) => {
      syncs.push({ id: String(params.id), body: await request.json() }); const source = items.find((source) => source.id === params.id)!;
      return HttpResponse.json({ state: options.failSync ? "FAILED" : "SUCCESS", source: { ...source, currentSnapshotId: options.failSync ? source.currentSnapshotId : "snapshot", lastSyncError: options.failSync ? "REFERENCE_CATALOG_REMOTE_UNAVAILABLE" : null }, snapshotId: "snapshot", entryCount: 1, variantCount: 1 });
    }));
  const onClose = vi.fn(); const onLoaded = vi.fn();
  render(<QueryClientProvider client={createQueryClient()}><ReferenceSourcesDialog onClose={onClose} onLoaded={onLoaded} /></QueryClientProvider>);
  return { user: userEvent.setup(), creates, syncs, updates, onClose, onLoaded, succeed: () => { options.failSync = false; } };
}

it("loads the selected preset in one action without technical configuration", async () => {
  const { user, creates, syncs, onClose, onLoaded } = setup();
  await waitFor(() => expect(screen.getByRole("button", { name: "Load" })).toBeEnabled());
  expect(screen.getAllByRole("combobox")).toHaveLength(1);
  expect(screen.queryByRole("textbox")).not.toBeInTheDocument(); expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  expect(creates).toHaveLength(0);
  await user.click(screen.getByRole("button", { name: "Load" }));
  await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(creates).toEqual([{ id: "omilic", name: "Omilic", owner: "hongye007", repository: "open-pose-costume", ref: "main", format: "OPEN_POSE_COSTUME", manifestPath: "data/catalog.json", imageDistributionApproved: false, sourceType: "OMILIC" }]);
  expect(syncs).toEqual([{ id: "omilic", body: { expectedVersion: 0 } }]);
  expect(onLoaded).toHaveBeenCalledWith(expect.objectContaining({ id: "omilic", currentSnapshotId: "snapshot" }));
});

it("reuses a configured preset with a different id and its current version", async () => {
  const { user, creates, updates, syncs, onClose } = setup([{ ...preset, id: "my-existing-source", version: 7 }]);
  await user.click(await screen.findByRole("button", { name: "Load" }));
  await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(creates).toHaveLength(0); expect(updates).toHaveLength(0);
  expect(syncs).toEqual([{ id: "my-existing-source", body: { expectedVersion: 7 } }]);
});

it("loads an existing local catalog during GitHub rate limiting without synchronizing it again", async () => {
  const { user, creates, syncs, onClose, onLoaded } = setup([{ ...preset, currentSnapshotId: "cached-snapshot", lastSyncStatus: "FAILED", lastSyncError: "REFERENCE_CATALOG_REMOTE_RATE_LIMITED" }], { failSync: true });
  await user.click(await screen.findByRole("button", { name: "Load" }));
  await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(creates).toHaveLength(0); expect(syncs).toHaveLength(0);
  expect(onLoaded).toHaveBeenCalledWith(expect.objectContaining({ currentSnapshotId: "cached-snapshot" }));
});

it("uses GitHub only when it is explicitly selected", async () => {
  const { user, creates, syncs, onClose } = setup();
  await waitFor(() => expect(screen.getByRole("button", { name: "Load" })).toBeEnabled());
  await user.click(screen.getByRole("combobox", { name: "Source" }));
  await user.click(screen.getByRole("option", { name: "GitHub" }));
  await user.click(screen.getByRole("button", { name: "Load" }));
  await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(creates).toEqual([expect.objectContaining({ id: "open-pose-costume", sourceType: "GITHUB", manifestPath: "public/data/catalog.json" })]);
  expect(syncs[0]?.id).toBe("open-pose-costume");
});

it("fetches JSON again only when the user explicitly refreshes a cached catalog", async () => {
  const { user, syncs, onClose } = setup([{ ...preset, currentSnapshotId: "cached-snapshot" }]);
  await user.click(await screen.findByRole("button", { name: "Refresh catalog" }));
  await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(syncs).toHaveLength(1);
});

it("retains an added source after a failed load and retries without adding it again", async () => {
  const { user, creates, syncs, onClose, succeed } = setup([], { failSync: true });
  await user.click(await screen.findByRole("button", { name: "Load" }));
  expect(await screen.findByRole("alert")).toBeInTheDocument();
  expect(screen.queryByText(/The last successful catalog remains available/)).not.toBeInTheDocument();
  expect(onClose).not.toHaveBeenCalled(); succeed();
  await user.click(screen.getByRole("button", { name: "Retry loading" }));
  await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(creates).toHaveLength(1); expect(syncs).toHaveLength(2);
});

it("recovers an added source after losing its acknowledgement without creating a duplicate", async () => {
  const { user, creates, syncs, onClose } = setup([], { loseAddResponse: true });
  await user.click(await screen.findByRole("button", { name: "Load" }));
  await screen.findByRole("alert");
  await user.click(screen.getByRole("button", { name: "Retry loading" }));
  await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(creates).toHaveLength(1); expect(syncs).toHaveLength(1);
});

it("preserves an unrelated source that already uses the preset's default id", async () => {
  const { user, creates, updates, syncs, onClose } = setup([{ ...preset, owner: "example", repository: "unrelated" }]);
  await user.click(await screen.findByRole("button", { name: "Load" }));
  await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(creates).toEqual([expect.objectContaining({ id: "omilic-2", owner: "hongye007" })]);
  expect(updates).toHaveLength(0); expect(syncs[0]?.id).toBe("omilic-2");
});

it("enables the selected existing source without changing its image rights", async () => {
  const { user, updates, syncs, onClose } = setup([{ ...preset, enabled: false, version: 3 }]);
  await user.click(await screen.findByRole("button", { name: "Load" }));
  await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(updates).toEqual([{ expectedVersion: 3, enabled: true, imageDistributionApproved: false }]);
  expect(syncs[0]?.body).toEqual({ expectedVersion: 4 });
});

it("enables a disabled source before opening its existing local directory", async () => {
  const { user, updates, syncs, onLoaded } = setup([{ ...preset, enabled: false, currentSnapshotId: "cached-snapshot", version: 3 }]);
  await user.click(await screen.findByRole("button", { name: "Load" }));
  await waitFor(() => expect(onLoaded).toHaveBeenCalled());
  expect(updates).toEqual([{ expectedVersion: 3, enabled: true, imageDistributionApproved: false }]);
  expect(syncs).toHaveLength(0);
  expect(onLoaded).toHaveBeenCalledWith(expect.objectContaining({ enabled: true }));
});
