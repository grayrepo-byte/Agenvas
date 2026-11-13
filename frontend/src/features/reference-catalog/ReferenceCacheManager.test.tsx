import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ReferenceSource } from "../../shared/api/referenceCatalog";
import { setLocale } from "../../shared/i18n";
import { server } from "../../test/server";
import { ReferenceCacheManager } from "./ReferenceCacheManager";

const base = "/api/v1/settings/reference-catalog/cache";
const source: ReferenceSource = {
  sourceType: "GITHUB",
  id: "pack",
  name: "Original pack",
  owner: "me",
  repository: "pack",
  ref: "main",
  manifestPath: "manifest.json",
  format: "AGENVAS_REFERENCE_PACK",
  enabled: true,
  imageDistributionApproved: true,
  version: 4,
  currentSnapshotId: "snapshot",
  resolvedCommit: "commit",
  lastSyncStatus: "SUCCESS",
  lastSyncError: null,
  syncedAt: "2026-11-01T00:00:00Z",
};
const stats = {
  protectedBytes: 0,
  protectedFileCount: 0,
  quotaBytes: 1000,
  usedBytes: 100,
  availableBytes: 900,
  fileCount: 1,
};
const job = {
  id: "job",
  sourceId: "pack",
  snapshotId: "snapshot",
  state: "RUNNING",
  totalCount: 2,
  readyCount: 1,
  failedCount: 0,
  totalBytes: 300,
  readyBytes: 100,
  createdAt: "2026-11-01T00:00:00Z",
  updatedAt: "2026-11-01T00:00:00Z",
  failures: [],
};
function setup(sources: ReferenceSource[] = [source], jobs: object[] = []) {
  setLocale("en");
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get(base, () => HttpResponse.json(stats)),
    http.get(`${base}/jobs`, () => HttpResponse.json({ items: jobs })),
  );
  render(
    <QueryClientProvider client={createQueryClient()}>
      <ReferenceCacheManager sources={sources} />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}
async function chooseSource(user: ReturnType<typeof userEvent.setup>, name = "Original pack") {
  await user.click(await screen.findByRole("combobox", { name: "Cache source" }));
  await user.click(screen.getByRole("option", { name }));
}
it("shows real usage and creates a pinned cache job only after an explicit action", async () => {
  const user = setup();
  const writes: Record<string, unknown>[] = [];
  server.use(
    http.post(`${base}/jobs`, async ({ request }) => {
      writes.push((await request.json()) as Record<string, unknown>);
      return HttpResponse.json(job);
    }),
  );
  expect(await screen.findByText("100 / 1,000 bytes · 1 files")).toBeInTheDocument();
  expect(writes).toHaveLength(0);
  await chooseSource(user);
  expect(writes).toHaveLength(0);
  await user.click(screen.getByRole("button", { name: "Cache authorized images" }));
  expect(await screen.findByText("1 / 2 images cached · 100 / 300 bytes · 0 failed")).toBeInTheDocument();
  expect(writes).toHaveLength(1);
  expect(writes[0]).toMatchObject({ sourceId: "pack", snapshotId: "snapshot", entryIds: [] });
  expect(typeof writes[0]?.commandKey).toBe("string");
});
it("replays a lost cache-job acknowledgement with the same command and pinned payload", async () => {
  const user = setup();
  const writes: Record<string, unknown>[] = [];
  server.use(
    http.post(`${base}/jobs`, async ({ request }) => {
      writes.push((await request.json()) as Record<string, unknown>);
      return writes.length === 1 ? HttpResponse.error() : HttpResponse.json(job);
    }),
  );
  await chooseSource(user);
  await user.click(screen.getByRole("button", { name: "Cache authorized images" }));
  await user.click(await screen.findByRole("button", { name: "Retry cache request" }));
  await screen.findByText("1 / 2 images cached · 100 / 300 bytes · 0 failed");
  expect(writes).toHaveLength(2);
  expect(writes[0]).toEqual(writes[1]);
});
it("retries failed job items without submitting a new bulk download", async () => {
  const failed = {
    ...job,
    state: "PARTIAL_FAILED",
    failedCount: 1,
    failures: [{ entryId: "pose", variantId: "side", code: "REFERENCE_CATALOG_REMOTE_UNAVAILABLE" }],
  };
  const user = setup([source], [failed]);
  let retries = 0;
  server.use(
    http.post(`${base}/jobs/job/retry`, () => {
      retries++;
      return HttpResponse.json({ ...job, state: "SUCCEEDED", readyCount: 2, readyBytes: 300 });
    }),
  );
  await user.click(await screen.findByRole("button", { name: "Retry failed images" }));
  expect(await screen.findByText("2 / 2 images cached · 300 / 300 bytes · 0 failed")).toBeInTheDocument();
  expect(retries).toBe(1);
});
it("requires confirmation before clearing source cache and explains preservation of project copies", async () => {
  const user = setup();
  let clears = 0;
  let cleared = false;
  server.use(
    http.get(base, () =>
      HttpResponse.json(cleared ? { ...stats, usedBytes: 0, availableBytes: 1000, fileCount: 0 } : stats),
    ),
    http.post(`${base}/clear`, async ({ request }) => {
      expect(await request.json()).toEqual({});
      clears++;
      cleared = true;
      return HttpResponse.json({
        removedFiles: 1,
        removedBytes: 100,
        stats: { ...stats, usedBytes: 0, availableBytes: 1000, fileCount: 0 },
      });
    }),
  );
  await user.click(await screen.findByRole("button", { name: "Clear shared source cache" }));
  expect(clears).toBe(0);
  expect(
    screen.getByText(
      "Clearing removes shared source images and cancels matching cache jobs. Private user caches and imported project copies are preserved.",
    ),
  ).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Confirm clear cache" }));
  await waitFor(() => expect(clears).toBe(1));
  expect(await screen.findByText("0 / 1,000 bytes · 0 files")).toBeInTheDocument();
});
it("keeps restricted metadata sources ineligible for bulk image caching", async () => {
  const user = setup([
    {
      ...source,
      id: "restricted",
      name: "Metadata source",
      format: "OPEN_POSE_COSTUME",
      imageDistributionApproved: false,
    },
  ]);
  await chooseSource(user, "Metadata source");
  expect(screen.getByRole("button", { name: "Cache authorized images" })).toBeDisabled();
  expect(
    screen.getByText(
      "Bulk image caching is not authorized for this source. Check private single-image options in the preview.",
    ),
  ).toBeInTheDocument();
});
it("retries cache status reads without starting a job or changing source selection", async () => {
  const user = setup();
  let failed = true;
  server.use(
    http.get(base, () =>
      failed
        ? HttpResponse.json(
            { title: "Unavailable", code: "CACHE_OFFLINE" },
            { status: 503, headers: { "Content-Type": "application/problem+json" } },
          )
        : HttpResponse.json(stats),
    ),
  );
  await chooseSource(user);
  await screen.findByRole("alert");
  failed = false;
  await user.click(screen.getByRole("button", { name: "Retry cache status" }));
  expect(await screen.findByText("100 / 1,000 bytes · 1 files")).toBeInTheDocument();
  expect(screen.getByRole("combobox", { name: "Cache source" })).toHaveTextContent("Original pack");
});
it("polls active work and refreshes usage from the completed server job", async () => {
  setup([source], [job]);
  let reads = 0;
  let complete = false;
  server.use(
    http.get(`${base}/jobs`, () => {
      reads++;
      complete = reads > 1;
      return HttpResponse.json({
        items: [complete ? { ...job, state: "SUCCEEDED", readyCount: 2, readyBytes: 300 } : job],
      });
    }),
    http.get(base, () =>
      HttpResponse.json(complete ? { ...stats, usedBytes: 300, availableBytes: 700, fileCount: 2 } : stats),
    ),
  );
  await screen.findByText("1 / 2 images cached · 100 / 300 bytes · 0 failed");
  expect(
    await screen.findByText("2 / 2 images cached · 300 / 300 bytes · 0 failed", {}, { timeout: 6000 }),
  ).toBeInTheDocument();
  expect(await screen.findByText("300 / 1,000 bytes · 2 files")).toBeInTheDocument();
  expect(reads).toBeGreaterThan(1);
});
it("cancels active work without discarding the already cached image counts", async () => {
  const user = setup([source], [job]);
  server.use(http.post(`${base}/jobs/job/cancel`, () => HttpResponse.json({ ...job, state: "CANCELLED" })));
  await user.click(await screen.findByRole("button", { name: "Cancel cache job" }));
  expect(await screen.findByText("Original pack · Cancelled")).toBeInTheDocument();
  expect(screen.getByText("1 / 2 images cached · 100 / 300 bytes · 0 failed")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Cancel cache job" })).not.toBeInTheDocument();
});
it("does not offer failed-item retry for a terminal cancelled job", async () => {
  setup([source], [{ ...job, state: "CANCELLED", failedCount: 1 }]);
  await screen.findByText("Original pack · Cancelled");
  expect(screen.queryByRole("button", { name: "Retry failed images" })).not.toBeInTheDocument();
});
it("limits a selected-source clear to that source and displays the returned remaining usage", async () => {
  const user = setup();
  let requestBody: unknown;
  server.use(
    http.post(`${base}/clear`, async ({ request }) => {
      requestBody = await request.json();
      return HttpResponse.json({
        removedFiles: 1,
        removedBytes: 50,
        stats: { ...stats, usedBytes: 50, availableBytes: 950, fileCount: 1 },
      });
    }),
  );
  await chooseSource(user);
  await user.click(screen.getByRole("button", { name: "Clear selected source cache" }));
  expect(requestBody).toBeUndefined();
  await user.click(screen.getByRole("button", { name: "Confirm clear cache" }));
  await waitFor(() => expect(requestBody).toEqual({ sourceId: "pack" }));
  expect(await screen.findByText("50 / 1,000 bytes · 1 files")).toBeInTheDocument();
});

it("explains private files protected from shared cache clearing", async () => {
  setup();
  server.use(http.get(base, () => HttpResponse.json({ ...stats, protectedBytes: 100, protectedFileCount: 1 })));
  expect(
    await screen.findByText(
      "Protected private cache: 100 bytes · 1 files. Shared cache clearing preserves these files.",
    ),
  ).toBeInTheDocument();
});
