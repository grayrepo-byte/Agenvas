import { QueryClientProvider, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { createQueryClient } from "../src/app/queryClient";
import { readContentText } from "../src/features/canvas/artifactContent";
import { MediaVersionPicker } from "../src/features/canvas/MediaVersionPicker";
import { TextVersionPicker } from "../src/features/canvas/TextVersionPicker";
import { hasCurrentVersion } from "../src/features/canvas/versionedArtifact";
import { assetThumbnailUrl, type ArtifactVersionList, type CanvasItem, type SelectMediaVersionRequest } from "../src/shared/api/client";
import { resolveLocale, setLocale, SUPPORTED_LOCALES } from "../src/shared/i18n";
import { ArrowCounterClockwise, CaretDown, Copy, DownloadSimple, MagicWand, MusicNotes } from "../src/shared/ui/icons";
import { Button } from "../src/shared/ui/primitives/button";
import "../src/styles.css";
import "./media-version-picker.fixture.css";

// Fixed synthetic identities and native-media middleware isolate this fixture from every backend.
const parameters = new URL(location.href).searchParams;
setLocale(resolveLocale(parameters.get("locale")) ?? "zh");
type ContentKind = "VIDEO" | "IMAGE" | "AUDIO" | "TEXT";
const requestedKind = parameters.get("kind")?.toUpperCase();
const kind: ContentKind = requestedKind === "IMAGE" || requestedKind === "AUDIO" || requestedKind === "TEXT" ? requestedKind : "VIDEO";
const requestedCount = parameters.get("count");
const parsedCount = Number(requestedCount);
const requestedVersionCount = requestedCount === "single" ? 1 : requestedCount === "long" ? 20
  : requestedCount !== null && Number.isSafeInteger(parsedCount) ? Math.min(40, Math.max(0, parsedCount)) : 3;
// TextVersionPicker is only mounted for text artifacts that already have an archived result.
const count = kind === "TEXT" ? Math.max(1, requestedVersionCount) : requestedVersionCount;
const state = parameters.get("state") ?? "ready";
const projectId = "synthetic-version-picker";
const itemId = `synthetic-version-picker-${kind.toLowerCase()}`;
const base = `/api/v1/projects/${projectId}/canvas/items/${itemId}`;
const now = "2026-10-24T09:00:00Z";
const assets = kind === "VIDEO" ? [5, 6] : kind === "AUDIO" ? [3, 4, 8] : [0, 1, 2];
const prompts = [
  "A quiet city street at night, cinematic lighting and a slow camera move.",
  "Soft daylight, a wider composition and subtle movement in the background.",
  "Close framing with a gentle push in, natural light and restrained contrast.",
];
const textBodies = [
  "SYNTHETIC DRAFT · Opening scene\n\nA quiet street. A single light marks the entrance, and the camera follows a slow, deliberate movement into the frame.\n\nThis is independently written fixture text for version selection, not user or Provider content.",
  "SYNTHETIC DRAFT · Revised composition\n\nBegin with a wider shot. Hold for a moment, then move closer as the subject enters the foreground. Keep the background soft and the pacing calm.\n\nThis revision changes the framing and rhythm while preserving the original scene.",
  "SYNTHETIC DRAFT · Final scene notes\n\nStart close on the detail, pull back to reveal the street, and let the subject cross the frame before the cut. Natural light, restrained contrast, an unhurried finish.\n\nThis saved revision is synthetic and exists only in memory.",
];
const versions: ArtifactVersionList["items"] = Array.from({ length: count }, (_, index) => ({
  id: `synthetic-version-${kind.toLowerCase()}-${index + 1}`, versionNo: kind === "TEXT" ? index + 1 : index * 3 + 8, schemaVersion: 1,
  content: kind === "TEXT" ? { format: "PLAIN_TEXT", text: textBodies[index % textBodies.length] ?? textBodies[0]! }
    : { sourceType: "UPLOAD", assetId: `synthetic-library-${assets[index % assets.length]}`,
    prompt: prompts[index % prompts.length], width: 640, height: 360, durationSeconds: 6.5, contentType: kind === "AUDIO" ? "audio/wav" : kind === "VIDEO" ? "video/mp4" : "image/svg+xml" },
  inputReferences: [], createdByKind: index % 2 === 0 ? "TASK" : "USER", runId: null,
  createdAt: new Date(Date.parse(now) - (count - index - 1) * 3_600_000).toISOString(),
}));
const selected = kind !== "TEXT" && state === "unselected" ? null : versions.at(-1) ?? null;
let serverItem: CanvasItem = { id: itemId, subjectType: "ARTIFACT", subjectId: `synthetic-${kind.toLowerCase()}-artifact`,
  title: `Synthetic ${kind.toLowerCase()} result`, x: 0, y: 0, width: 480, height: 270, zIndex: 1,
  locked: false, version: 7, selectedVersionId: selected?.id ?? null, selectedVersion: selected,
  artifact: { id: `synthetic-${kind.toLowerCase()}-artifact`, projectId, kind,
    title: `Synthetic ${kind.toLowerCase()} result`, resourceDefaultVersionId: kind === "TEXT" ? selected?.id ?? null : null,
    resourceDefaultVersion: kind === "TEXT" ? selected : null,
    version: 0, createdAt: now, updatedAt: now } };
const artifactBase = `/api/v1/projects/${projectId}/artifacts/${serverItem.artifact.id}`;
const historyQueryKey = kind === "TEXT" ? ["artifact-versions", projectId, serverItem.artifact.id] : ["canvas-media-versions", projectId, itemId];
let failHistory = state === "error" || state === "refresh";
let delayNextRead = state === "delay";
let conflictNextSelection = state === "conflict";
const requests: Array<{ method: string; path: string }> = [];
const unexpectedRequests: string[] = [];
const queryClient = createQueryClient();
queryClient.setDefaultOptions({ queries: { retry: false, refetchOnWindowFocus: false } });
if (state === "refresh") queryClient.setQueryData(historyQueryKey, { items: kind === "TEXT" ? [...versions].reverse() : versions }, { updatedAt: 1 });

function problem(status: number, detail: string) {
  return Response.json({ status, title: "Synthetic version picker fixture", detail,
    code: status === 409 ? "VERSION_CONFLICT" : "SYNTHETIC_READ_FAILURE", traceId: "synthetic-version-picker" },
  { status, headers: { "Content-Type": "application/problem+json" } });
}

const originalFetch = window.fetch.bind(window);
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  if (!url.pathname.startsWith("/api/")) return originalFetch(input, init);
  const method = (init?.method ?? (input instanceof Request ? input.method : "GET")).toUpperCase();
  const path = url.pathname;
  requests.push({ method, path });
  if (path === "/api/v1/auth/csrf" && method === "GET") return Response.json({ headerName: "X-XSRF-TOKEN", token: "synthetic-only" });
  if (path === base && method === "GET") return Response.json(serverItem);
  if ((path === `${base}/media-versions` || path === `${artifactBase}/versions`) && method === "GET") {
    if (delayNextRead) { delayNextRead = false; await new Promise((resolve) => window.setTimeout(resolve, 2500)); }
    if (failHistory) { failHistory = false; return problem(503, "Synthetic history read failed. Retry reads the same saved results."); }
    return Response.json({ items: kind === "TEXT" ? [...versions].reverse() : versions });
  }
  if (path === `${artifactBase}/set-default-version` && method === "POST") {
    const body = init?.body ?? (input instanceof Request ? await input.text() : "{}");
    const request = JSON.parse(String(body)) as SelectMediaVersionRequest;
    if (conflictNextSelection) {
      conflictNextSelection = false;
      serverItem = { ...serverItem, artifact: { ...serverItem.artifact, version: serverItem.artifact.version + 1 } };
    }
    if (request.expectedVersion !== serverItem.artifact.version) return problem(409, "Synthetic text artifact changed elsewhere. Refresh, then retry with the current artifact revision.");
    const version = versions.find((candidate) => candidate.id === request.versionId);
    if (!version) return problem(404, "The requested synthetic text version does not exist.");
    if (state === "delay") await new Promise((resolve) => window.setTimeout(resolve, 1800));
    serverItem = { ...serverItem, artifact: { ...serverItem.artifact, resourceDefaultVersionId: version.id,
      resourceDefaultVersion: version, version: serverItem.artifact.version + 1, updatedAt: now } };
    return Response.json(serverItem.artifact);
  }
  if (path === `${base}/select-media-version` && method === "POST") {
    const body = init?.body ?? (input instanceof Request ? await input.text() : "{}");
    const request = JSON.parse(String(body)) as SelectMediaVersionRequest;
    if (conflictNextSelection) {
      conflictNextSelection = false;
      // A different writer changed the server revision; the local item remains stale until refresh.
      serverItem = { ...serverItem, version: serverItem.version + 1 };
    }
    if (request.expectedVersion !== serverItem.version) return problem(409, "Synthetic node changed elsewhere. Refresh versions, then try again.");
    const version = versions.find((candidate) => candidate.id === request.versionId);
    if (!version) return problem(404, "The requested synthetic version does not exist.");
    if (state === "delay") await new Promise((resolve) => window.setTimeout(resolve, 1800));
    serverItem = { ...serverItem, version: serverItem.version + 1, selectedVersionId: version.id, selectedVersion: version };
    return Response.json(serverItem);
  }
  unexpectedRequests.push(`${method} ${path}`);
  return problem(404, "Unsupported synthetic fixture endpoint; no backend request was sent.");
};

Object.assign(window, { __agenvasVersionPickerFixture: {
  evidence: () => structuredClone({ item: serverItem, requests, unexpectedRequests }),
  failNextRead: () => { failHistory = true; },
  conflictNextSelection: () => { conflictNextSelection = true; },
} });

function href(next: Record<string, string>) {
  const query = new URLSearchParams(location.search);
  Object.entries(next).forEach(([key, value]) => query.set(key, value));
  return `${location.pathname}?${query}`;
}

function Fixture() {
  const item = useQuery({ queryKey: ["canvas", projectId], queryFn: async () => {
    const response = await fetch(base);
    return await response.json() as CanvasItem;
  }, initialData: serverItem }).data;
  const [notice, setNotice] = useState("All content is synthetic. No backend or Provider calls.");
  const selectedAsset = readContentText(item.selectedVersion?.content, "assetId") || `synthetic-library-${assets[0]}`;
  const selectedText = readContentText(item.artifact.resourceDefaultVersion?.content, "text");
  return <main className="version-picker-fixture" id="fixture" data-kind={kind} data-count={count} data-version={kind === "TEXT" ? item.artifact.version : item.version}>
    <section className="version-picker-fixture-stage" aria-label="Synthetic canvas">
      <div className="version-picker-fixture-toolbar" role="toolbar" aria-label="Synthetic media toolbar">
        <Button variant="ghost" type="button"><MagicWand size={16} />Edit<CaretDown size={12} /></Button>
        <Button variant="ghost" type="button"><ArrowCounterClockwise size={16} />Regenerate</Button>
        <span className="media-toolbar-divider" />
        {kind === "TEXT" ? hasCurrentVersion(item.artifact) ? <TextVersionPicker artifact={item.artifact} disabled={state === "dirty"} /> : null
          : <MediaVersionPicker projectId={projectId} item={item} />}
        <span className="media-toolbar-divider" />
        <Button variant="ghost" type="button"><Copy size={16} />Copy</Button>
        <Button variant="ghost" type="button" aria-label="Download"><DownloadSimple size={17} /></Button>
      </div>
      <div className="version-picker-fixture-card">
        {kind === "TEXT" ? <article className="version-picker-fixture-text" aria-label="Synthetic text content">
          {state === "dirty" ? <p className="version-picker-fixture-draft">Unsaved synthetic draft — switching saved versions is disabled.</p> : null}
          <p>{selectedText}{state === "dirty" ? "\n\nUNSAVED SYNTHETIC EDIT: Add a final wide shot before the cut." : ""}</p>
        </article> : kind === "AUDIO" ? <div className="version-picker-fixture-audio"><MusicNotes size={44} /><span>SYNTHETIC AUDIO</span></div>
          : <img src={assetThumbnailUrl(projectId, selectedAsset)} alt="Synthetic fixture media" />}
        <div className="version-picker-fixture-card-caption"><span>SYNTHETIC {kind}</span><span>{kind === "TEXT" ? `v${item.artifact.resourceDefaultVersion?.versionNo}`
          : item.selectedVersionId ? `v${versions.findIndex((version) => version.id === item.selectedVersionId) + 1}` : "Unselected"}</span></div>
      </div>
    </section>
    <aside className="version-picker-fixture-controls" aria-label="Synthetic fixture controls">
      <p>{notice}</p>
      <nav aria-label="Media kind">{["video", "image", "audio", "text"].map((value) => <a key={value} href={href({ kind: value })}>{value}</a>)}</nav>
      <nav aria-label="Version count">{["1", "3", "20"].map((value) => <a key={value} href={href({ count: value, state: "ready" })}>{value} versions</a>)}</nav>
      <nav aria-label="Fixture state">{["ready", "unselected", "error", "delay", "conflict", "refresh", "dirty"].map((value) => <a key={value} href={href({ state: value })}>{value}</a>)}</nav>
      <nav aria-label="Locale">{SUPPORTED_LOCALES.map((locale) => <a key={locale} href={href({ locale })}>{locale}</a>)}</nav>
      <div className="version-picker-fixture-actions">
        <Button variant="outline" size="sm" onClick={() => { conflictNextSelection = true; setNotice("The next selection returns a real CAS conflict. Use the product refresh action, then retry."); }}>Conflict next selection</Button>
        <Button variant="outline" size="sm" onClick={() => { failHistory = true; void queryClient.invalidateQueries({ queryKey: historyQueryKey }); setNotice("History refresh failed once. Existing versions remain in cache; retry reads synthetic results."); }}>Fail history refresh</Button>
        <Button variant="outline" size="sm" onClick={() => { void queryClient.invalidateQueries({ queryKey: ["canvas", projectId] }); void queryClient.invalidateQueries({ queryKey: historyQueryKey }); setNotice("Synthetic saved artifact and history refreshed. Local draft content remains synthetic."); }}>Refresh saved state</Button>
      </div>
    </aside>
  </main>;
}

createRoot(document.getElementById("root")!).render(<QueryClientProvider client={queryClient}><Fixture /></QueryClientProvider>);
