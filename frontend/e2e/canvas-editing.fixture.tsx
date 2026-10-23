import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MemoryRouter, Route, Routes } from "react-router";
import { ProjectWorkspacePage } from "../src/features/canvas/ProjectWorkspacePage";
import { useCanvasStore } from "../src/features/canvas/canvasStore";
import type { Artifact, CanvasEditReceipt, CanvasEditReplayRequest, CanvasEditRequest, CanvasEditResponse,
  CanvasEditorState, CanvasGroup, CanvasItem, ProjectSnapshot, ReviseArtifactRequest } from "../src/shared/api/client";
import { resolveLocale, setLocale } from "../src/shared/i18n";
import "../src/styles.css";

// All identities, content, accounts and HTTP writes are synthetic and remain in memory.
// This fixture never opens an application database, external provider or real SSE stream.
const options = new URL(location.href).searchParams;
setLocale(resolveLocale(options.get("locale")) ?? "zh");
const projectId = "synthetic-canvas-editing";
const now = "2026-10-23T00:00:00Z";
const project: ProjectSnapshot["project"] = { id: projectId, name: "Synthetic 100-node canvas · Большой творческий холст",
  status: "ACTIVE", aspectRatio: "LANDSCAPE_16_9", version: 0, createdAt: now, updatedAt: now };
function textItem(index: number): CanvasItem {
  const id = `synthetic-card-${index}`;
  const version: NonNullable<Artifact["resourceDefaultVersion"]> = { id: `${id}-v1`, versionNo: 1, schemaVersion: 1,
    content: { format: "PLAIN_TEXT", text: `Synthetic independent text ${index}. 合成镜头笔记，支持独立编辑。` },
    inputReferences: [], createdByKind: "USER", runId: null, createdAt: now };
  const artifact: Artifact = { id: `${id}-artifact`, projectId, kind: "TEXT", title: `CARD ${String(index).padStart(3, "0")}`,
    resourceDefaultVersionId: version.id, resourceDefaultVersion: version, version: 0, createdAt: now, updatedAt: now };
  // The first three cards form a useful close-up while the other 97 retain a real large canvas.
  const x = index < 3 ? index * 380 : ((index - 3) % 10 - 5) * 430;
  const y = index < 3 ? 0 : (Math.floor((index - 3) / 10) + 1) * 310;
  return { id, subjectType: "ARTIFACT", subjectId: artifact.id, title: artifact.title, x, y, width: 330, height: 240,
    zIndex: index, groupId: null, locked: false, selectedVersionId: version.id, selectedVersion: version,
    artifact, version: 0 };
}
let items: CanvasItem[] = Array.from({ length: 100 }, (_, index) => textItem(index));
if (options.has("hand-drag")) {
  const empty = textItem(1);
  empty.artifact.resourceDefaultVersion!.content = { format: "PLAIN_TEXT", text: "" };
  empty.title = "EMPTY TEXT";
  const media = (["IMAGE", "VIDEO"] as const).map((kind, index): CanvasItem => {
    const item = textItem(index + 2);
    item.title = `SYNTHETIC ${kind}`; item.x = index * 380; item.y = 310;
    item.selectedVersion = null; item.selectedVersionId = null;
    item.artifact = { ...item.artifact, kind, title: item.title, resourceDefaultVersion: null, resourceDefaultVersionId: null };
    return item;
  });
  items = [textItem(0), empty, ...media, ...(options.has("motion-large") ? items.slice(4).map((item) => ({ ...item, y: item.y + 310 })) : [])];
}
let groups: CanvasGroup[] = [];
const connections: ProjectSnapshot["connections"] = options.has("hand-drag") ? [] : [
  { id: "synthetic-internal-connection", projectId, sourceCanvasItemId: items[0]!.id, targetCanvasItemId: items[1]!.id,
    sourceArtifactVersionId: items[0]!.selectedVersionId!, relationType: "MEDIA_DERIVATION", version: 0, createdAt: now, updatedAt: now },
  { id: "synthetic-external-connection", projectId, sourceCanvasItemId: items[1]!.id, targetCanvasItemId: items[2]!.id,
    sourceArtifactVersionId: items[1]!.selectedVersionId!, relationType: "MEDIA_DERIVATION", version: 0, createdAt: now, updatedAt: now },
];
const requests: Array<{ method: string; path: string }> = [];
const edits: CanvasEditRequest[] = [];
const replays: CanvasEditReplayRequest[] = [];
const textSaves: Array<{ artifactId: string; request: ReviseArtifactRequest }> = [];
const unexpected: string[] = [];
type ReceiptRecord = { receipt: CanvasEditReceipt; before: CanvasEditorState; after: CanvasEditorState };
const receipts = new Map<string, ReceiptRecord>();
const editCommands = new Map<string, CanvasEditResponse>();
const replayCommands = new Map<string, CanvasEditResponse>();
let nextEditFailure: number | null = null;
let nextTextFailure: number | null = null;
const copy = <T,>(value: T): T => structuredClone(value);
const state = (): CanvasEditorState => copy({ items, groups });
const diagnostics = {
  evidence: () => copy({ items, groups, requests, edits, replays, textSaves, unexpected,
    layoutDrafts: useCanvasStore.getState().drafts,
    mediaDraftRecoveryCount: Object.keys(useCanvasStore.getState().mediaDraftRecoveries).length,
    receipts: [...receipts.values()].map(({ receipt }) => receipt) }),
  failNextEdit: (status: number) => { nextEditFailure = status; },
  failNextTextSave: (status: number) => { nextTextFailure = status; },
};
Object.assign(window, { __agenvasCanvasEditingFixture: diagnostics });
function problem(status: number, detail: string) {
  return Response.json({ type: "about:blank", title: "Synthetic canvas failure", detail, status,
    code: status === 409 ? "VERSION_CONFLICT" : "SYNTHETIC_FAILURE", traceId: "synthetic", retryable: status !== 409 },
  { status, headers: { "Content-Type": "application/problem+json" } });
}
function persistEdit(request: CanvasEditRequest): CanvasEditResponse | Response {
  const prior = editCommands.get(request.commandId);
  if (prior) return copy(prior);
  if (nextEditFailure !== null) {
    const status = nextEditFailure; nextEditFailure = null;
    return problem(status, status === 409 ? "Synthetic conflicting canvas edit; input is retained." : "Synthetic unavailable canvas edit; retry is safe.");
  }
  for (const change of request.itemChanges ?? []) {
    const item = items.find((candidate) => candidate.id === change.itemId);
    if (!item || item.version !== change.expectedVersion) return problem(409, "Synthetic item version conflict");
  }
  for (const change of request.groupChanges ?? []) {
    const group = groups.find((candidate) => candidate.id === change.groupId);
    if (group ? group.version !== change.expectedVersion : change.expectedVersion !== 0) return problem(409, "Synthetic group version conflict");
  }
  for (const requestCopy of request.copies ?? []) {
    const item = items.find((candidate) => candidate.id === requestCopy.sourceItemId);
    if (!item || item.version !== requestCopy.expectedSourceVersion
      || item.selectedVersionId !== (requestCopy.expectedSourceContentVersion ?? null)) return problem(409, "Synthetic source changed before copy");
  }
  const before = state();
  for (const change of request.groupChanges ?? []) {
    const existing = groups.find((candidate) => candidate.id === change.groupId);
    groups = groups.filter((candidate) => candidate.id !== change.groupId);
    if (change.type === "UPSERT") groups.push({ id: change.groupId, projectId, name: change.name!.trim(),
      collapsed: change.collapsed!, version: existing ? existing.version + 1 : 0 });
  }
  for (const change of request.itemChanges ?? []) {
    const { itemId, expectedVersion: _expectedVersion, ...presentation } = change;
    void _expectedVersion;
    items = items.map((item) => item.id === itemId ? { ...item, ...presentation, version: item.version + 1 } : item);
  }
  for (const requestCopy of request.copies ?? []) {
    const source = items.find((candidate) => candidate.id === requestCopy.sourceItemId)!;
    const artifact = copy(source.artifact);
    artifact.id = `${requestCopy.targetItemId}-artifact`; artifact.version = 0;
    if (artifact.resourceDefaultVersion) {
      artifact.resourceDefaultVersion.id = `${requestCopy.targetItemId}-v1`;
      artifact.resourceDefaultVersion.versionNo = 1;
      artifact.resourceDefaultVersionId = artifact.resourceDefaultVersion.id;
    }
    items.push({ ...copy(source), id: requestCopy.targetItemId, subjectId: artifact.id, artifact,
      x: requestCopy.x, y: requestCopy.y, width: requestCopy.width, height: requestCopy.height,
      zIndex: requestCopy.zIndex, groupId: requestCopy.groupId ?? null, locked: false, version: 0,
      selectedVersionId: artifact.resourceDefaultVersionId, selectedVersion: artifact.resourceDefaultVersion });
  }
  const receipt: CanvasEditReceipt = { id: crypto.randomUUID(), operation: request.operation, state: "APPLIED", revision: 0 };
  const saved = { ...state(), receipt };
  receipts.set(receipt.id, { receipt, before, after: state() }); editCommands.set(request.commandId, copy(saved));
  return saved;
}
function restore(target: CanvasEditorState) {
  const current = new Map(items.map((item) => [item.id, item]));
  items = target.items.map((snapshot) => {
    const live = current.get(snapshot.id);
    if (!live) return copy(snapshot);
    return { ...live, title: snapshot.title, x: snapshot.x, y: snapshot.y, width: snapshot.width, height: snapshot.height,
      zIndex: snapshot.zIndex, locked: snapshot.locked, groupId: snapshot.groupId, version: live.version + 1 };
  });
  const currentGroups = new Map(groups.map((group) => [group.id, group]));
  groups = target.groups.map((group) => ({ ...copy(group), version: (currentGroups.get(group.id)?.version ?? group.version) + 1 }));
}
function replay(receiptId: string, request: CanvasEditReplayRequest): CanvasEditResponse | Response {
  const previous = replayCommands.get(request.commandId); if (previous) return copy(previous);
  const record = receipts.get(receiptId);
  if (!record || record.receipt.revision !== request.expectedRevision
    || record.receipt.state !== (request.direction === "UNDO" ? "APPLIED" : "UNDONE")) return problem(409, "Synthetic stale edit receipt");
  restore(request.direction === "UNDO" ? record.before : record.after);
  record.receipt = { ...record.receipt, revision: record.receipt.revision + 1, state: request.direction === "UNDO" ? "UNDONE" : "APPLIED" };
  const saved = { ...state(), receipt: record.receipt }; replayCommands.set(request.commandId, copy(saved)); return saved;
}
class SyntheticEventSource extends EventTarget {
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private timer = window.setTimeout(() => this.onopen?.(), 0);
  close() { window.clearTimeout(this.timer); }
}
Object.defineProperty(window, "EventSource", { configurable: true, value: SyntheticEventSource });
const originalFetch = window.fetch.bind(window);
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  if (!url.pathname.startsWith("/api/")) return originalFetch(input, init);
  const method = init?.method ?? (input instanceof Request ? input.method : "GET");
  const path = url.pathname;
  requests.push({ method, path });
  let value: unknown;
  if (path.endsWith("/auth/me")) value = { id: "synthetic-user", loginName: "Synthetic user", role: "ADMIN" };
  else if (path.endsWith("/auth/csrf")) value = { headerName: "X-XSRF-TOKEN", token: "synthetic-token" };
  else if (path === `/api/v1/projects/${projectId}` && method === "GET") value = project;
  else if (path.endsWith("/snapshot") && method === "GET") value = { project, canvas: { items }, connections,
    director: null, activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 0 } satisfies ProjectSnapshot;
  else if (path.endsWith("/canvas/editor") && method === "GET") value = state();
  else if (path.endsWith("/canvas/items") && method === "GET") value = { items };
  else if (path.endsWith("/canvas/connections") && method === "GET") value = { items: connections };
  else if (path.endsWith("/canvas/edits") && method === "POST") {
    const request = JSON.parse(String(init?.body)) as CanvasEditRequest; edits.push(copy(request)); value = persistEdit(request);
  } else if (/\/canvas\/edits\/[^/]+\/replay$/.test(path) && method === "POST") {
    const request = JSON.parse(String(init?.body)) as CanvasEditReplayRequest; replays.push(copy(request));
    value = replay(path.split("/").at(-2)!, request);
  } else if (/\/artifacts\/[^/]+\/revisions$/.test(path) && method === "POST") {
    const artifactId = path.split("/").at(-2)!;
    const request = JSON.parse(String(init?.body)) as ReviseArtifactRequest; textSaves.push({ artifactId, request });
    const item = items.find((candidate) => candidate.artifact.id === artifactId);
    if (nextTextFailure !== null) { const status = nextTextFailure; nextTextFailure = null; return problem(status, "Synthetic text save failure retains draft"); }
    if (!item || item.artifact.version !== request.expectedVersion) return problem(409, "Synthetic text version conflict");
    const version: NonNullable<Artifact["resourceDefaultVersion"]> = { id: crypto.randomUUID(), versionNo: (item.artifact.resourceDefaultVersion?.versionNo ?? 0) + 1,
      schemaVersion: 1, content: request.content, inputReferences: [], createdByKind: "USER", runId: null, createdAt: now };
    item.artifact = { ...item.artifact, title: request.title, version: item.artifact.version + 1,
      resourceDefaultVersionId: version.id, resourceDefaultVersion: version };
    item.selectedVersion = version; item.selectedVersionId = version.id;
    value = item.artifact;
  } else if (/\/artifacts\/[^/]+$/.test(path) && method === "GET") value = items.find((item) => item.artifact.id === path.split("/").at(-1))?.artifact;
  else if (path.endsWith("/artifacts") && method === "GET") value = { items: items.map((item) => item.artifact) };
  else if (path.endsWith("/versions") && method === "GET") value = { items: [items.find((item) => item.artifact.id === path.split("/").at(-2))?.artifact.resourceDefaultVersion].filter(Boolean) };
  else if (path.endsWith("/characters") && method === "GET") value = { characters: [], bindings: [] };
  else if (path.endsWith("/media-draft") && method === "GET") value = { projectId, canvasItemId: path.split("/").at(-2),
    prompt: "", parameters: {}, videoInputMode: null, mediaInputs: [], mentions: [], durationSeconds: null,
    capabilityId: null, displayMode: "RESULT", version: 0, createdAt: now, updatedAt: now };
  else if (path.endsWith("/media-versions") && method === "GET") value = { items: [] };
  else if (path.endsWith("/settings/llm")) value = { configured: false, version: 0 };
  else if (path.endsWith("/settings/diagnostics")) value = { llmMode: "MOCK" };
  else if (path.endsWith("/settings/media-connections")) value = { connections: [], defaults: [] };
  else if (method === "GET" && /\/(text-generations|run|tasks|exports|export-proposals|usage|media-approvals)$/.test(path)) value = [];
  else {
    unexpected.push(`${method} ${path}`);
    throw new Error(`Unsupported synthetic canvas request: ${method} ${path}`);
  }
  return value instanceof Response ? value : Response.json(value);
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
const fixtureRoot = createRoot(document.getElementById("root")!);
fixtureRoot.render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[`/projects/${projectId}`]}><Routes>
  <Route path="/projects/:projectId" element={<ProjectWorkspacePage />} />
</Routes></MemoryRouter></QueryClientProvider>);
if (import.meta.hot) import.meta.hot.dispose(() => { fixtureRoot.unmount(); window.fetch = originalFetch; });
