import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ReferenceImport } from "../../shared/api/referenceCatalog";
import { setLocale } from "../../shared/i18n";
import { server } from "../../test/server";
import { ReferenceCatalog } from "./ReferenceCatalog";

const base = "/api/v1/reference-catalog/private-images";
const image = { id: "private-image", sourceId: "upstream", snapshotId: "snapshot", entryId: "pose", variantId: "front", sha256: "verified-sha", byteSize: 150, contentType: "image/png", width: 100, height: 150, previewUrl: `${base}/private-image/preview`, cached: true };
const variant = { id: "front", label: "Front", availability: "LINK_ONLY", importAllowed: false, privateCacheAllowed: true, privateCacheDisabledReason: null, externalUrl: "https://example.com/original", sha256: null, previewUrl: null, cached: false };
const entry = { sourceId: "upstream", snapshotId: "snapshot", id: "pose", kind: "POSE", title: "Selected pose", category: "Standing", tags: [], facets: {}, importAllowed: false, disabledReason: "REFERENCE_CATALOG_LINK_ONLY", variants: [variant] };
function setup(options: { records?: object[]; privateAllowed?: boolean; reason?: string; placement?: { x: number; y: number } } = {}) {
  setLocale("en"); let indexReads = 0; const applied: ReferenceImport[] = [];
  server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "user", role: "USER" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/reference-catalog", () => HttpResponse.json({ items: [{ ...entry, variants: [{ ...variant, privateCacheAllowed: options.privateAllowed ?? true, privateCacheDisabledReason: options.reason ?? null }] }], total: 1, offset: 0, limit: 50 })),
    http.get(base, ({ request }) => { indexReads++; expect(new URL(request.url).searchParams.get("sourceId")).toBe("upstream"); expect(new URL(request.url).searchParams.get("snapshotId")).toBe("snapshot"); return HttpResponse.json({ items: options.records ?? [] }); }));
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><ReferenceCatalog projectId="project" placement={options.placement} onApply={async (result) => { applied.push(result); }} /></QueryClientProvider>);
  return { user: userEvent.setup(), applied, client, getIndexReads: () => indexReads };
}
it("caches only the selected private image after an explicit action and never imports on cache", async () => {
  const { user, applied, getIndexReads } = setup(); const writes: Record<string, unknown>[] = [];
  server.use(http.post(base, async ({ request }) => { writes.push(await request.json() as Record<string, unknown>); return HttpResponse.json(image); }));
  await screen.findByRole("button", { name: "Preview Selected pose" }); expect(getIndexReads()).toBe(0); expect(screen.queryByRole("img")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Preview Selected pose" }));
  await user.click(await screen.findByRole("button", { name: "Cache this image privately" }));
  expect(await screen.findByRole("img")).toHaveAttribute("src", image.previewUrl);
  expect(writes).toHaveLength(1); expect(writes[0]).toMatchObject({ sourceId: "upstream", snapshotId: "snapshot", entryId: "pose", variantId: "front" });
  expect(applied).toHaveLength(0); expect(screen.getByRole("button", { name: "Append as reference" })).toBeEnabled();
});
it.each([undefined, { x: 80, y: 80 }])("reuses an owner-indexed image and imports its exact version with placement %j", async (placement) => {
  const { user, applied } = setup({ records: [image], placement }); const imports: Record<string, unknown>[] = [];
  server.use(http.post(`/api/v1/projects/project/reference-catalog/private-images/private-image/import`, async ({ request }) => { imports.push(await request.json() as Record<string, unknown>); return HttpResponse.json({ versionId: "owned-version", assetId: "owned-asset", title: "Selected pose", canvasItemId: placement ? "placed-item" : null }); }));
  await user.click(await screen.findByRole("button", { name: "Preview Selected pose" }));
  expect(await screen.findByRole("img")).toHaveAttribute("src", image.previewUrl);
  expect(screen.queryByRole("button", { name: "Cache this image privately" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Append as reference" }));
  await waitFor(() => expect(applied[0]?.versionId).toBe("owned-version"));
  expect(imports).toHaveLength(1); expect(imports[0]?.placement).toEqual(placement); expect(typeof imports[0]?.commandKey).toBe("string");
});
it("replays a lost private-cache acknowledgement with the same selected image and key", async () => {
  const { user } = setup(); const writes: Record<string, unknown>[] = [];
  server.use(http.post(base, async ({ request }) => { writes.push(await request.json() as Record<string, unknown>); return writes.length === 1 ? HttpResponse.error() : HttpResponse.json(image); }));
  await user.click(await screen.findByRole("button", { name: "Preview Selected pose" })); await user.click(await screen.findByRole("button", { name: "Cache this image privately" }));
  await user.click(await screen.findByRole("button", { name: "Retry private cache" })); await screen.findByRole("img");
  expect(writes).toHaveLength(2); expect(writes[0]).toEqual(writes[1]);
});
it("confirms owner-private clearing, removes preview access and preserves project copies", async () => {
  const { user, applied } = setup({ records: [image] }); let deletes = 0;
  server.use(http.delete(`${base}/private-image`, () => { deletes++; return HttpResponse.json({ ...image, cached: false }); }));
  await user.click(await screen.findByRole("button", { name: "Preview Selected pose" })); await screen.findByRole("img");
  await user.click(screen.getByRole("button", { name: "Clear my private copy" })); expect(deletes).toBe(0);
  expect(screen.getByText("Clear your private cache of this image? Imported project copies and other users’ caches are preserved.")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Confirm clear private copy" }));
  await waitFor(() => expect(deletes).toBe(1)); expect(screen.queryByRole("img")).not.toBeInTheDocument();
  expect(await screen.findByRole("button", { name: "Cache this image privately" })).toBeEnabled(); expect(applied).toHaveLength(0);
});
it.each([
  ["PRIVATE_IMAGE_CUSTOM_USE_SCOPE_UNVERIFIED", "Private-cache permission for custom contributions has not been verified."],
  ["PRIVATE_IMAGE_ANIME_USE_SCOPE_UNVERIFIED", "Private-cache permission for this anime collection has not been verified."],
  ["PRIVATE_IMAGE_ARCHIVE_PATH_UNVERIFIED", "A verified private-cache file path is unavailable for this variant."],
])("uses the server eligibility reason %s without downloading an image", async (reason, message) => {
  const { user } = setup({ privateAllowed: false, reason }); await user.click(await screen.findByRole("button", { name: "Preview Selected pose" }));
  expect(await screen.findByText(message)).toBeInTheDocument();
  expect(screen.queryByRole("img")).not.toBeInTheDocument(); expect(screen.queryByRole("button", { name: "Cache this image privately" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Append as reference" })).toBeDisabled();
});
it("recovers a lost private import with the same command instead of creating another project copy", async () => {
  const { user, applied } = setup({ records: [image] }); const imports: Record<string, unknown>[] = [];
  server.use(http.post(`/api/v1/projects/project/reference-catalog/private-images/private-image/import`, async ({ request }) => { imports.push(await request.json() as Record<string, unknown>); return imports.length === 1 ? HttpResponse.error() : HttpResponse.json({ versionId: "owned-version", assetId: "owned-asset", title: "Selected pose" }); }));
  await user.click(await screen.findByRole("button", { name: "Preview Selected pose" })); await screen.findByRole("img");
  await user.click(screen.getByRole("button", { name: "Append as reference" })); await user.click(await screen.findByRole("button", { name: "Retry" }));
  await waitFor(() => expect(applied[0]?.versionId).toBe("owned-version")); expect(imports[0]).toEqual(imports[1]);
});
it("never reuses a same-named variant from another immutable snapshot", async () => {
  const { user } = setup({ records: [{ ...image, snapshotId: "other-snapshot" }] });
  await user.click(await screen.findByRole("button", { name: "Preview Selected pose" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Cache this image privately" })).toBeEnabled());
  expect(screen.queryByRole("img")).not.toBeInTheDocument(); expect(screen.getByRole("button", { name: "Append as reference" })).toBeDisabled();
});
it("recovers an owner-index read without creating or downloading a new private copy", async () => {
  const { user } = setup(); let failed = true;
  server.use(http.get(base, () => failed ? HttpResponse.json({ title: "Index unavailable", code: "OFFLINE" }, { status: 503, headers: { "Content-Type": "application/problem+json" } }) : HttpResponse.json({ items: [image] })));
  await user.click(await screen.findByRole("button", { name: "Preview Selected pose" }));
  await screen.findByRole("alert"); failed = false;
  await user.click(screen.getByRole("button", { name: "Retry private cache status" }));
  expect(await screen.findByRole("img")).toHaveAttribute("src", image.previewUrl);
});
it("does not hotlink a remote URL supplied in an owner-cache record", async () => {
  const { user } = setup({ records: [{ ...image, previewUrl: "https://example.com/remote-image.png" }] });
  await user.click(await screen.findByRole("button", { name: "Preview Selected pose" }));
  await screen.findByText("Privately cached on this server"); expect(screen.queryByRole("img")).not.toBeInTheDocument();
});

it("does not reuse another signed-in account's private cache projection", async () => {
  const { user, client } = setup({ records: [image] });
  await user.click(await screen.findByRole("button", { name: "Preview Selected pose" })); await screen.findByRole("img");
  server.use(http.get(base, () => HttpResponse.json({ items: [] })));
  act(() => client.setQueryData(["auth", "me"], { id: "other-user", role: "USER" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Cache this image privately" })).toBeEnabled());
  expect(screen.queryByRole("img")).not.toBeInTheDocument(); expect(screen.getByRole("button", { name: "Append as reference" })).toBeDisabled();
});
it("offers old-snapshot private cache management to ordinary authenticated users", async () => {
  const { user } = setup();
  server.use(http.get(base, ({ request }) => { expect(new URL(request.url).search).toBe(""); return HttpResponse.json({ items: [{ ...image, snapshotId: "old-snapshot" }] }); }));
  await user.click(await screen.findByRole("button", { name: "My private image cache" }));
  expect(await screen.findByRole("dialog", { name: "My private image cache" })).toBeInTheDocument();
  expect(await screen.findByText("old-snapshot")).toBeInTheDocument();
});
