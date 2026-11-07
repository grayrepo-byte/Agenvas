import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MemoryRouter, Route, Routes } from "react-router";
import type { Agent, Artifact, CanvasCommand, CanvasItem, CreateArtifactRequest, MediaDraft, SaveMediaDraftRequest } from "../src/shared/api/client";
import { ProjectWorkspacePage } from "../src/features/canvas/ProjectWorkspacePage";
import { setLocale } from "../src/shared/i18n";
import "../src/styles.css";

// Isolated browser fixture: all requests and writes stay in memory. No real account or provider.
setLocale("zh");
const projectId = "synthetic-studio-preview";
const options = new URLSearchParams(location.search);
const lightActions = options.has("light-actions");
const now = "2026-10-15T00:00:00Z";
const project = { id: projectId, name: "海岸来信 · 创作工作室（Mock）", status: "ACTIVE",
  aspectRatio: "LANDSCAPE_16_9", version: 0, createdAt: now, updatedAt: now, archivedAt: null };
const artifact = (id: string, kind: Artifact["kind"], title: string, text?: string): Artifact => ({
  id, kind, title, projectId, version: 1, createdAt: now, updatedAt: now,
  resourceDefaultVersionId: text !== undefined ? `${id}-v1` : null,
  resourceDefaultVersion: text !== undefined ? { id: `${id}-v1`, versionNo: 1, schemaVersion: 1,
    content: { format: "PLAIN_TEXT", text }, inputReferences: [], createdByKind: "USER", runId: null, createdAt: now } : null,
});
const artifacts = [
  artifact("brief", "TEXT", "01 / 故事起点", "海岸来信\n\n一封没有寄出的信，一座临海的小城。\n在日落之前，找回那些被风带走的片段。\n\n视觉方向\n温暖的胶片色调 · 自然光 · 安静的留白"),
  artifact("image", "IMAGE", "02 / 海岸 · 视觉探索"),
  artifact("video", "VIDEO", "03 / 日落之前 · 镜头草稿"),
];
const agent: Agent = { id: "director", projectId, name: "创作伙伴", instruction: "协助创作海岸短片。",
  profileKey: "creator", profileVersion: 1, version: 0, createdAt: now, updatedAt: now };
let items: CanvasItem[] = artifacts.map((subject, index) => ({
  id: subject.id, subjectType: "ARTIFACT", subjectId: subject.id, title: subject.title,
  artifact: subject, x: index === 0 ? 0 : 530, y: index === 2 ? 355 : 0,
  width: index === 0 ? 420 : 520, height: index === 0 ? 310 : 292.5,
  zIndex: index, groupId: null, locked: false, version: 0,
  selectedVersionId: subject.resourceDefaultVersionId, selectedVersion: subject.resourceDefaultVersion,
}));
if (lightActions) {
  const audio = artifact("audio", "AUDIO", "04 / 海风 · 环境声");
  artifacts.push(audio);
  items.push({ ...items[1]!, id: audio.id, subjectId: audio.id, title: audio.title, artifact: audio });
  items = items.map((item, index) => {
    const kind = item.artifact!.kind;
    if (kind === "TEXT") return { ...item, x: 0, y: 0, width: 320, height: 250 };
    const assetId = `synthetic-library-${kind === "IMAGE" ? 0 : kind === "VIDEO" ? 5 : 3}`;
    const version: NonNullable<CanvasItem["selectedVersion"]> = { id: `${item.id}-v1`, versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId },
      inputReferences: [], createdByKind: "USER" as const, runId: null, createdAt: now };
    const subject = { ...item.artifact!, resourceDefaultVersion: version, resourceDefaultVersionId: version.id };
    artifacts[index] = subject;
    return { ...item, artifact: subject, selectedVersion: version, selectedVersionId: version.id,
      x: index === 3 ? 0 : 370, y: index === 2 || index === 3 ? 320 : 0, width: 320, height: index === 3 ? 180 : 250 };
  });
  items.push({ id: "character", subjectType: "CHARACTER", subjectId: "synthetic-hero", title: "05 / 旅人 · 角色参考",
    x: 740, y: 0, width: 260, height: 370, zIndex: 4, locked: false, version: 0, artifact: null,
    selectedVersionId: null, selectedVersion: null,
    characterNode: { canvasItemId: "character", version: 1, newerVersionAvailable: false, connections: [],
      selection: { characterId: "synthetic-hero", characterVersion: 1, lookId: "travel", viewVersionIds: ["image-v1"], useAppearance: true, useVoice: false },
      snapshot: { characterId: "synthetic-hero", characterVersion: 1, name: "旅人", description: "独立合成角色，仅用于界面验证", useAppearance: true, useVoice: false, voice: null,
        look: { id: "travel", name: "海岸旅装", clothing: "", expression: "", description: "", views: [{ versionId: "image-v1", purpose: "正面", description: "" }] } } } });
}
if (options.has("empty")) items = [];
const drafts = new Map<string, MediaDraft>();
const connections = [{ id: "story-image", projectId, sourceCanvasItemId: "brief", targetCanvasItemId: "image",
  relationType: "MEDIA_DERIVATION", sourceArtifactVersionId: "brief-v1", version: 0, createdAt: now, updatedAt: now }];

Object.defineProperty(window, "EventSource", { value: class extends EventTarget {
  onopen: (() => void) | null = null;
  constructor() { super(); queueMicrotask(() => this.onopen?.()); }
  close() { /* Fixture has no network stream. */ }
} });
window.fetch = async (input, init) => {
  const path = new URL(input instanceof Request ? input.url : String(input), location.href).pathname;
  const method = init?.method ?? (input instanceof Request ? input.method : "GET");
  const supportedWrite = path.endsWith("/canvas/commands") || path.endsWith("/media-draft") || path.endsWith("/artifacts");
  if (method !== "GET" && !supportedWrite) return Response.json({
    code: "MOCK_PREVIEW_UNSUPPORTED", title: "Mock 预览", detail: "此预览仅支持创建产物节点、调整布局和编辑媒体草稿。",
  }, { status: 422 });
  let value: unknown;
  if (path.endsWith("/auth/me")) value = { id: "preview", loginName: "Mock", role: "ADMIN" };
  else if (path.endsWith("/csrf")) value = { headerName: "X-XSRF-TOKEN", token: "mock" };
  else if (path.endsWith("/snapshot")) value = { project, canvas: { items }, connections: items.length ? connections : [],
    director: agent, activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 0 };
  else if (path.endsWith("/canvas/commands")) {
    const request = JSON.parse(String(init?.body)) as { commands: CanvasCommand[] };
    for (const command of request.commands) {
      if (command.type === "UPDATE_LAYOUT") items = items.map(item => item.id === command.itemId
        ? { ...item, x: command.x ?? item.x, y: command.y ?? item.y,
          width: command.width ?? item.width, height: command.height ?? item.height, version: item.version + 1 } : item);
      if (command.type === "PLACE_ARTIFACT") {
        const subject = artifacts.find(entry => entry.id === command.artifactId);
        if (subject) items.push({ id: command.itemId ?? crypto.randomUUID(), subjectType: "ARTIFACT", subjectId: subject.id,
          title: subject.title, artifact: subject, x: command.x ?? 0, y: command.y ?? 0,
          width: command.width ?? 420, height: command.height ?? 270, zIndex: command.zIndex ?? 0,
          groupId: null, locked: false, version: 0, selectedVersion: subject.resourceDefaultVersion,
          selectedVersionId: subject.resourceDefaultVersionId });
      }
      if (command.type === "REMOVE") items = items.filter(item => item.id !== command.itemId);
      if (command.type === "SET_LOCKED") items = items.map(item => item.id === command.itemId
        ? { ...item, locked: command.locked ?? false, version: item.version + 1 } : item);
    }
    value = { items };
  } else if (path.endsWith("/canvas/items")) value = { items };
  else if (path.endsWith("/canvas/editor")) value = { items, groups: [] };
  else if (path.endsWith("/characters")) value = { characters: [], bindings: [] };
  else if (path.endsWith("/canvas/connections")) value = { items: items.length ? connections : [] };
  else if (path.endsWith(`/projects/${projectId}`)) value = project;
  else if (path.endsWith("/media-draft")) {
    const itemId = path.split("/").at(-2)!;
    let draft: MediaDraft = drafts.get(itemId) ?? { projectId, canvasItemId: itemId,
    prompt: "让海风、日落和细碎的光，成为故事的主角。", parameters: {}, videoInputMode: "TEXT",
    mediaInputs: lightActions && itemId === "video" ? [{ versionId: "image-v1", artifactId: "image", role: "REFERENCE", order: 0,
      color: "#3388cc", sources: [{ id: "synthetic-manual-input", type: "MANUAL", connectionId: null }] }] : [],
    mentions: [], durationSeconds: null, capabilityId: null, styleId: null, displayMode: lightActions ? "RESULT" : "DRAFT", version: 0, createdAt: now, updatedAt: now };
    if (init?.method === "PUT") {
      const request = JSON.parse(String(init.body)) as SaveMediaDraftRequest;
      draft = { ...draft, ...request, mediaInputs: request.mediaInputs.map((entry, order) => ({ ...entry, order,
        artifactId: artifacts.find(subject => subject.resourceDefaultVersionId === entry.versionId)?.id ?? "preview",
        sources: [{ id: `manual-${order}`, type: "MANUAL", connectionId: null }] })), version: draft.version + 1 };
    }
    drafts.set(itemId, draft);
    value = draft;
  }
  else if (path.endsWith("/media-versions")) value = { items: [items.find((item) => item.id === path.split("/").at(-2))?.selectedVersion].filter(Boolean) };
  else if (path.endsWith("/versions")) value = { items: [artifacts.find((entry) => entry.id === path.split("/").at(-2))?.resourceDefaultVersion].filter(Boolean) };
  else if (/\/assets\/synthetic-library-/.test(path)) value = { id: path.split("/").at(-1), width: 640, height: 360, durationMs: 500 };
  else if (path.endsWith("/media-functions")) value = [];
  else if (path.endsWith("/media-connections")) value = { connections: [], defaults: [] };
  else if (path.endsWith("/llm")) value = { configured: false, version: 0 };
  else if (path.endsWith("/diagnostics")) value = { llmMode: "MOCK" };
  else if (path.endsWith("/conversations")) value = { items: [], currentConversationId: null, nextCursor: null };
  else if (path.endsWith("/artifacts")) {
    if (init?.method === "POST") {
      const request = JSON.parse(String(init.body)) as CreateArtifactRequest;
      const created = artifact(crypto.randomUUID(), request.kind, request.title,
        request.content && "text" in request.content ? request.content.text : undefined);
      artifacts.push(created);
      value = created;
    } else value = { items: artifacts, nextCursor: null };
  }
  else if (path.endsWith("/skill-binding")) value = { agentVersion: 0, skills: [] };
  else if (/\/(run|tasks|text-generations|image-prompts|media-styles|actions|plans)$/.test(path)) value = [];
  else value = { items: [], nextCursor: null };
  return Response.json(value);
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={client}>
  <MemoryRouter initialEntries={[`/projects/${projectId}`]}><Routes>
    <Route path="/projects/:projectId" element={<ProjectWorkspacePage />} />
  </Routes></MemoryRouter>
</QueryClientProvider>);
