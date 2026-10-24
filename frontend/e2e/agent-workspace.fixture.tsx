import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MemoryRouter, Route, Routes } from "react-router";
import { ProjectWorkspacePage } from "../src/features/canvas/ProjectWorkspacePage";
import type { Agent, AgentConversation, CanvasEditorState, CanvasItem, CharacterIndex, ProjectSnapshot } from "../src/shared/api/client";
import { setLocale } from "../src/shared/i18n";
import "../src/styles.css";

// Real workspace, React Flow, Router and production styles; all HTTP/SSE data is
// synthetic. No application database, credentials, generation or Provider calls.
setLocale("zh");
const now = "2026-10-20T00:00:00Z";
const projectId = "synthetic-workspace";
const project: ProjectSnapshot["project"] = { id: projectId, name: "午后城市 · 合成项目",
  aspectRatio: "LANDSCAPE_16_9", status: "ACTIVE", version: 0, createdAt: now, updatedAt: now, archivedAt: null };
const director: Agent = { id: "synthetic-director", projectId, name: "导演 Agent", instruction: "协助当前画布创作。",
  profileKey: "creator", profileVersion: 1, version: 0,
  createdAt: now, updatedAt: now };
function textItem(id: string, title: string, text: string, x: number, y: number): CanvasItem {
  const version: NonNullable<CanvasItem["selectedVersion"]> = { id: `${id}-version`, versionNo: 1, schemaVersion: 1,
    content: { format: "PLAIN_TEXT", text }, inputReferences: [], createdByKind: "USER", runId: null, createdAt: now };
  return { id, subjectType: "ARTIFACT", subjectId: `${id}-artifact`, title, x, y,
    width: 330, height: 240, zIndex: 0, groupId: null, locked: false, version: 0,
    selectedVersionId: version.id, selectedVersion: version,
    artifact: { id: `${id}-artifact`, projectId, kind: "TEXT", title, resourceDefaultVersionId: version.id,
      resourceDefaultVersion: version, version: 0, createdAt: now, updatedAt: now } };
}
const story = textItem("synthetic-story", "故事梗概", "城市午后，一次不期而遇，让两个平行的日常交汇。", 40, 70);
const character = textItem("synthetic-character", "角色设定", "林夏，独立摄影师。以一张意外的照片，找回与城市的联系。", 420, 70);
const image: CanvasItem = { ...textItem("synthetic-image", "视觉参考", "", 40, 370),
  artifact: { id: "synthetic-image-artifact", projectId, kind: "IMAGE", title: "视觉参考",
    resourceDefaultVersionId: null, resourceDefaultVersion: null, version: 0, createdAt: now, updatedAt: now },
  selectedVersionId: null, selectedVersion: null };
const items = [story, character, image];
const canvasEditor: CanvasEditorState = { items, groups: [] };
const characters: CharacterIndex = { characters: [], bindings: [] };
const connections: ProjectSnapshot["connections"] = [];
const snapshot: ProjectSnapshot = { project, canvas: { items }, connections, director,
  activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 0 };
const diagnostics = { requests: [] as string[], unexpected: [] as string[], mutations: [] as string[], conversationMutations: [] as string[] };
Object.assign(window, { __agenvasWorkspaceFixture: diagnostics });
const conversations: { items: AgentConversation[]; currentConversationId: string } = {
  items: [{ id: `conversation-${director.id}`, projectId, agentInstanceId: director.id,
    title: "午后城市 · 创作讨论", version: 0, turnCount: 0, createdAt: now, updatedAt: now }],
  currentConversationId: `conversation-${director.id}`,
};
let conversationNumber = 0;

// EventSource does not use window.fetch. This local stand-in reports a connected
// stream without opening a real network connection or fabricating Run results.
class SyntheticEventSource extends EventTarget {
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private timer = window.setTimeout(() => this.onopen?.(), 0);
  close() { window.clearTimeout(this.timer); }
}
Object.defineProperty(window, "EventSource", { configurable: true, value: SyntheticEventSource });

window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  const method = init?.method ?? (input instanceof Request ? input.method : "GET");
  const path = url.pathname;
  diagnostics.requests.push(`${method} ${path}`);
  // Session changes are the only allowed fixture mutations. They never create a Run.
  const sessionRoute = path.match(/\/agents\/([^/]+)\/conversations(?:\/([^/]+)\/select)?$/);
  if (method === "POST" && sessionRoute) {
    const agentId = sessionRoute[1];
    if (agentId !== director.id) throw new Error(`Unexpected synthetic director: ${agentId}`);
    const sessions = conversations;
    diagnostics.conversationMutations.push(`${method} ${path}`);
    let conversation: AgentConversation;
    if (sessionRoute[2]) {
      const selected = sessions.items.find((item) => item.id === sessionRoute[2]);
      if (!selected) throw new Error(`Missing synthetic conversation: ${sessionRoute[2]}`);
      conversation = selected;
    } else {
      conversation = { id: `synthetic-conversation-${++conversationNumber}`, projectId, agentInstanceId: agentId,
        title: "新会话", version: 0, turnCount: 0, createdAt: now, updatedAt: now };
      sessions.items.unshift(conversation);
    }
    sessions.currentConversationId = conversation.id;
    return new Response(JSON.stringify(conversation), { status: sessionRoute[2] ? 200 : 201,
      headers: { "Content-Type": "application/json" } });
  }
  if (method !== "GET") {
    diagnostics.mutations.push(`${method} ${path}`);
    throw new Error(`The workspace fixture forbids mutation/generation: ${method} ${path}`);
  }
  let value: unknown;
  if (path.endsWith("/auth/me")) value = { id: "synthetic-user", loginName: "Synthetic user", role: "ADMIN" };
  else if (path.endsWith("/auth/csrf")) value = { headerName: "X-XSRF-TOKEN", token: "synthetic" };
  else if (path === `/api/v1/projects/${projectId}`) value = project;
  else if (path.endsWith("/snapshot")) value = snapshot;
  else if (path.endsWith("/canvas/items")) value = { items };
  else if (path === `/api/v1/projects/${projectId}/canvas/editor`) value = canvasEditor;
  else if (path === `/api/v1/projects/${projectId}/characters`) value = characters;
  else if (path.endsWith("/canvas/connections")) value = { items: connections };
  else if (path.endsWith("/conversations")) {
    const agentId = path.split("/").at(-2)!;
    if (agentId !== director.id) throw new Error(`Unexpected synthetic director: ${agentId}`);
    value = { ...conversations, nextCursor: null };
  } else if (path.endsWith("/skill-binding")) value = { agentVersion: 0, skills: [] };
  else if (path === "/api/v1/skills") value = { items: [], total: 0, nextCursor: null };
  else if (path.endsWith("/runs")) value = { items: [], nextCursor: null };
  else if (path.endsWith("/media-draft")) value = { projectId, canvasItemId: path.split("/").at(-2),
    prompt: "", parameters: {}, videoInputMode: null, mediaInputs: [], mentions: [],
    durationSeconds: null, capabilityId: null, displayMode: "RESULT", version: 0, createdAt: now, updatedAt: now };
  else if (path.endsWith("/media-versions")) value = { items: [] };
  else if (path.endsWith("/text-generations") || path.endsWith("/run") || path.endsWith("/tasks")
    || path.endsWith("/actions") || path.endsWith("/media-approvals")) value = [];
  else if (path.endsWith("/settings/llm")) value = { configured: false, version: 0 };
  else if (path.endsWith("/settings/diagnostics")) value = { llmMode: "MOCK" };
  else if (path.endsWith("/settings/media-connections")) value = { connections: [], defaults: [] };
  else {
    diagnostics.unexpected.push(`${method} ${path}`);
    throw new Error(`Unexpected synthetic workspace request: ${method} ${path}`);
  }
  return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" } });
};

createRoot(document.getElementById("root")!).render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={[`/projects/${projectId}`]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter>
  </QueryClientProvider>,
);
