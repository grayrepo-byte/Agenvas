import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { ReferenceCatalog } from "../src/features/reference-catalog/ReferenceCatalog";
import { Dialog } from "../src/shared/ui/Dialog";
import { Button } from "../src/shared/ui/primitives/button";
import { setLocale } from "../src/shared/i18n";
import type { ReferenceEntry, ReferencePrivateImage, ReferenceSource } from "../src/shared/api/referenceCatalog";
import "../src/styles.css";

setLocale("en");
const source: ReferenceSource = { id: "synthetic", name: "Original geometric studies", owner: "example", repository: "original-studies", ref: "a".repeat(40),
  manifestPath: "reference-pack.json", format: "AGENVAS_REFERENCE_PACK", enabled: true, imageDistributionApproved: true, version: 1,
  currentSnapshotId: "00000000-0000-4000-8000-000000000001", resolvedCommit: "a".repeat(40), lastSyncStatus: "SUCCESS", lastSyncError: null, syncedAt: "2026-11-06T00:00:00Z" };
const entry: ReferenceEntry = { id: "neutral", sourceId: source.id, snapshotId: source.currentSnapshotId!, kind: "POSE", title: "Synthetic neutral stance",
  localizedTitle: { en: "Synthetic neutral stance", zh: "合成站姿测试" }, code: "TEST-01", category: "standing", localizedCategory: { en: "Standing", zh: "站姿" },
  series: { en: "Original geometric tests", zh: "原创几何测试" }, tags: ["test"], localizedTags: { test: { en: "Test", zh: "测试" } }, localizedKeywords: { en: "synthetic stance geometry", zh: "合成 站姿 几何" },
  facets: { purpose: "Synthetic layout and interaction test" }, collection: "builtin", extended: false, externalUrl: null, importAllowed: true, disabledReason: null,
  variants: ["front", "side", "high", "low", "diag"].map((angle) => ({ id: `neutral-${angle}`, label: angle, localizedLabel: { en: angle }, view: angle, angle,
    externalUrl: null, previewUrl: `/api/v1/reference-catalog/synthetic-preview/${angle}`, sha256: "b".repeat(64), byteSize: 1024,
    contentType: "image/png", availability: "AVAILABLE", importAllowed: true, disabledReason: null, cached: false, privateCacheAllowed: false, privateCacheDisabledReason: null })) };
const data: ReferenceEntry[] = [entry, { ...entry, id: "links", title: "Metadata-only source", localizedTitle: { en: "Metadata-only source" }, importAllowed: false,
  disabledReason: "REFERENCE_CATALOG_LINK_ONLY", variants: entry.variants.map((v) => ({ ...v, previewUrl: null, externalUrl: "https://example.com/reference", importAllowed: false, availability: "LINK_ONLY", sha256: null })) }];
const privateEntry: ReferenceEntry = { ...entry, id: "automatic-costume", kind: "COSTUME", title: "Synthetic private costume", localizedTitle: { en: "Synthetic private costume" }, importAllowed: false,
  variants: entry.variants.slice(0, 1).map((view) => ({ ...view, previewUrl: null, sha256: null, importAllowed: false, availability: "LINK_ONLY", privateCacheAllowed: true })) };
let privateDownloads = 0;
let imports = 0;
let privateImages: ReferencePrivateImage[] = [{ id: "00000000-0000-4000-8000-000000000002", sourceId: source.id,
  snapshotId: "00000000-0000-4000-8000-000000000003", entryId: "old-snapshot-geometric-study", variantId: "old-front-view",
  sha256: "b".repeat(64), byteSize: 1024, contentType: "image/png", width: 240, height: 320,
  previewUrl: "/api/v1/reference-catalog/synthetic-preview/front", cached: true }];
globalThis.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.origin);
  if (url.pathname === "/api/v1/auth/me") return Response.json({ id: "synthetic-user", username: "synthetic", role: "ADMIN" });
  if (url.pathname === "/api/v1/auth/csrf") return Response.json({ headerName: "X-CSRF-TOKEN", token: "synthetic" });
  if (url.pathname === "/api/v1/settings/reference-catalog/cache") return Response.json({ quotaBytes: 536870912, usedBytes: 0, fileCount: 0, availableBytes: 536870912, protectedBytes: 0, protectedFileCount: 0 });
  if (url.pathname === "/api/v1/settings/reference-catalog/cache/jobs") return Response.json({ items: [] });
  if (url.pathname === "/api/v1/reference-catalog/private-images") {
    if (init?.method === "POST") {
      privateDownloads++; document.documentElement.dataset.privateDownloadCount = String(privateDownloads);
      const saved: ReferencePrivateImage = { id: "synthetic-auto", sourceId: privateEntry.sourceId, snapshotId: privateEntry.snapshotId, entryId: privateEntry.id, variantId: privateEntry.variants[0]!.id,
        sha256: "b".repeat(64), byteSize: 1024, contentType: "image/png", width: 240, height: 320, previewUrl: "/api/v1/reference-catalog/private-images/synthetic-auto/preview", cached: true };
      privateImages.push(saved); return Response.json(saved);
    }
    return Response.json({ items: privateImages.filter((image) => (!url.searchParams.has("sourceId") || image.sourceId === url.searchParams.get("sourceId")) && (!url.searchParams.has("snapshotId") || image.snapshotId === url.searchParams.get("snapshotId"))) });
  }
  if (url.pathname.startsWith("/api/v1/reference-catalog/private-images/") && init?.method === "DELETE") {
    const removed = privateImages.find((image) => url.pathname.endsWith(image.id));
    if (!removed) throw new Error("Unknown synthetic private image");
    privateImages = privateImages.filter((image) => image.id !== removed.id);
    document.documentElement.dataset.privateClearCount = "1";
    return Response.json({ ...removed, cached: false });
  }
  if (url.pathname === "/api/v1/reference-catalog/sources") return Response.json({ items: [source] });
  if (url.pathname === "/api/v1/settings/reference-catalog/sources/synthetic/sync") {
    document.documentElement.dataset.sourceLoadCount = "1";
    return Response.json({ state: "SUCCESS", source, snapshotId: source.currentSnapshotId, entryCount: 2, variantCount: 10 });
  }
  if (url.pathname === "/api/v1/reference-catalog") {
    const query = (url.searchParams.get("query") ?? "").toLowerCase();
    const kind = url.searchParams.get("kind") ?? "POSE";
    const items = (kind === "COSTUME" ? [privateEntry] : data).filter((item) => item.title.toLowerCase().includes(query));
    return Response.json({ items, total: items.length, offset: 0, limit: 50, filters: { categories: ["standing"], tags: ["test"], styles: [], collections: ["builtin"], angles: ["front", "side", "high", "low", "diag"] } });
  }
  if (url.pathname.endsWith("/reference-catalog/import")) {
    const body = JSON.parse(String(init?.body ?? "{}")) as { variantId?: string };
    imports += 1;
    document.documentElement.dataset.importCount = String(imports);
    return Response.json({ artifactId: "owned-artifact", canvasItemId: null, versionId: `owned-${body.variantId}`, assetId: "owned-asset", title: entry.title,
      contentType: "image/png", byteSize: 1024, width: 240, height: 320, thumbnailUrl: "/api/v1/reference-catalog/synthetic-preview/front" });
  }
  throw new Error(`Unexpected synthetic request: ${url.pathname}`);
};
function Fixture() {
  const [open, setOpen] = useState(false); const [busy, setBusy] = useState(false); const [applied, setApplied] = useState("");
  return <main className="app-page"><Button id="open-reference" onClick={() => setOpen(true)}>Open reference catalog</Button><output id="applied-reference">{applied}</output>
    {open ? <Dialog className="reference-picker-dialog" title="Reference catalog" busy={busy} onClose={() => setOpen(false)} onSubmit={(event) => event.preventDefault()}
      footer={<Button type="button" disabled={busy} onClick={() => setOpen(false)}>Close catalog</Button>}>
      <ReferenceCatalog projectId="synthetic-project" onBusy={setBusy} onApply={async (image) => { setApplied(image.versionId); }} />
    </Dialog> : null}</main>;
}
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Fixture /></QueryClientProvider>);
