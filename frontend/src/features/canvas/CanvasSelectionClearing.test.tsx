import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import { act,cleanup,createEvent,fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import type { NodeSelectionChange,ReactFlowInstance,ReactFlowProps } from "@xyflow/react";
import { http,HttpResponse } from "msw";
import { useLayoutEffect } from "react";
import { MemoryRouter,Route,Routes } from "react-router";
import { afterEach,beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasEditRequest,CanvasItem,MediaDraft,ProjectSnapshot,SaveMediaDraftRequest } from "../../shared/api/client";
import { server } from "../../test/server";
import { CANVAS_POINTER_THRESHOLD } from "./canvasInteraction";
import { canvasGroupNodeId } from "./canvasGroups";
import { CANVAS_SELECTION_MODE,useCanvasStore } from "./canvasStore";
import { useCharacterNodeRecovery } from "./characterNodeRecovery";
import { t } from "../../shared/i18n";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

type CanvasItemChange = NonNullable<CanvasEditRequest["itemChanges"]>[number];

type SelectionChange = NodeSelectionChange;
type FlowProps = ReactFlowProps;
const TEST_VIEWPORT = { width: 1400, height: 900 };
const SELECTION_BOX = { startX: -10, startY: -10, endX: 900, endY: 700 };
let renderRealFlow = false;

let flowProps: FlowProps = {};
const focusProbe = vi.hoisted(() => ({
  center: vi.fn<ReactFlowInstance["setCenter"]>().mockResolvedValue(true),
  viewport: vi.fn<ReactFlowInstance["setViewport"]>().mockResolvedValue(true),
  fit: vi.fn<ReactFlowInstance["fitView"]>().mockResolvedValue(true), zoom: 1,
  updateInternals: vi.fn(),
  instance: null as ReactFlowInstance | null,
  deferInit: false,
  pendingInit: null as (() => void) | null,
}));

// 选中态的同步依赖 React Flow 的变更通知，这里直接驱动页面对应的回调。
vi.mock("@xyflow/react", async (importOriginal) => {
  const real = await importOriginal<typeof import("@xyflow/react")>();
  function TestHandleMeasurements() {
    const store = real.useStoreApi();
    const nodes = real.useStore((state) => state.nodes);
    useLayoutEffect(() => {
      // jsdom has no element measurement. Empty measured handles prevent React Flow's
      // initial-render fallback from treating every unmeasured node as inside the box.
      for (const node of store.getState().nodeLookup.values()) {
        node.internals.handleBounds ??= { source: [], target: [] };
      }
    }, [nodes, store]);
    return null;
  }
  return {
    ...real,
    // Group handle geometry is covered in native Chromium; jsdom has no DOMMatrix.
    useUpdateNodeInternals: () => focusProbe.updateInternals,
    ReactFlow: (props: FlowProps) => {
      flowProps = props;
      // Keep a fixed viewport; node dimensions come from the actual workspace projection.
      // jsdom has no viewport bounds; disable edge auto-pan so gesture coordinates remain deterministic.
      return renderRealFlow ? <real.ReactFlow {...props} fitView={false} autoPanOnNodeDrag={false}
        autoPanOnSelection={false} {...TEST_VIEWPORT}
        defaultViewport={{ x: 0, y: 0, zoom: focusProbe.zoom }}
        onInit={(instance) => {
          const initialize = () => {
            focusProbe.instance = instance;
            props.onInit?.({ ...instance, setCenter: focusProbe.center, setViewport: focusProbe.viewport, fitView: focusProbe.fit });
          };
          if (focusProbe.deferInit) focusProbe.pendingInit = initialize;
          else initialize();
        }}>
        <TestHandleMeasurements />{props.children}
      </real.ReactFlow> : <div data-testid="flow" />;
    },
  };
});

const now = "2026-09-27T00:00:00Z";
const imageVersionId = "11111111-1111-4111-8111-111111111111";

const items: CanvasItem[] = [
  { id: "image-card", subjectType: "ARTIFACT", subjectId: "image-id", x: 0, y: 0,
    title: "参考图",
    width: 260, height: 150, zIndex: 0, groupId: null, locked: false, selectedVersionId: null, selectedVersion: null, version: 3,
    artifact: { id: "image-id", projectId: "project-1", kind: "IMAGE", title: "参考图",
      resourceDefaultVersionId: imageVersionId, version: 3, createdAt: now, updatedAt: now,
      resourceDefaultVersion: { id: imageVersionId, versionNo: 1, schemaVersion: 1,
        content: { sourceType: "UPLOAD", assetId: "asset-id" }, inputReferences: [],
        createdByKind: "USER", runId: null, createdAt: now } } },
  { id: "middle-card", subjectType: "ARTIFACT", subjectId: "middle-id", x: 400, y: 0,
    title: "脚本", width: 460, height: 600, zIndex: 1, groupId: null, locked: false,
    selectedVersionId: null, selectedVersion: null, version: 4,
    artifact: { id: "middle-id", projectId: "project-1", kind: "TEXT", title: "脚本",
      resourceDefaultVersionId: null, resourceDefaultVersion: null, version: 0, createdAt: now, updatedAt: now } },
  { id: "text-card", subjectType: "ARTIFACT", subjectId: "text-id", x: 800, y: 0,
    title: "正文",
    width: 260, height: 150, zIndex: 2, groupId: null, locked: false, selectedVersionId: null, selectedVersion: null, version: 5,
    artifact: { id: "text-id", projectId: "project-1", kind: "TEXT", title: "正文",
      resourceDefaultVersionId: "22222222-2222-4222-8222-222222222222", version: 5,
      createdAt: now, updatedAt: now,
      resourceDefaultVersion: { id: "22222222-2222-4222-8222-222222222222", versionNo: 1, schemaVersion: 1,
        content: { format: "PLAIN_TEXT", text: "选中的正文" }, inputReferences: [],
        createdByKind: "USER", runId: null, createdAt: now } } },
];

function snapshot(): ProjectSnapshot {
  return { project: { id: "project-1", name: "选中项目", status: "ACTIVE",
    aspectRatio: "LANDSCAPE_16_9", version: 1, createdAt: now, updatedAt: now },
    canvas: { items }, connections: [], director: { id: "agent-id", projectId: "project-1", profileKey: "creator",
      profileVersion: 1, name: "Creator", instruction: "Create", version: 4,
      createdAt: now, updatedAt: now, }, activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 0 };
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

beforeEach(() => {
  // These callback tests have no browser layout; release hit testing sees empty space.
  Object.defineProperty(document, "elementFromPoint", { configurable: true, value: () => null });
  flowProps = {};
  renderRealFlow = false;
  focusProbe.center.mockClear();
  focusProbe.viewport.mockClear();
  focusProbe.fit.mockClear();
  focusProbe.zoom = 1;
  focusProbe.instance = null;
  focusProbe.deferInit = false;
  focusProbe.pendingInit = null;
  useCharacterNodeRecovery.setState({ pending: {}, placements: {} });
  useCanvasStore.setState({ selectedIds: [], selectionMode: CANVAS_SELECTION_MODE.SINGLE,
    drafts: {}, mediaDraftRecoveries: {}, saveState: "saved" });
  server.use(
    http.get("/api/v1/settings/media-connections", () => HttpResponse.json({ connections: [], defaults: [] })),
    http.get("/api/v1/projects/:projectId/artifacts/:artifactId/run", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/artifacts/:artifactId/versions", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/projects/:projectId/agents/:agentId/conversations", () => HttpResponse.json({ items: [], nextCursor: null })),
    http.get("/api/v1/auth/me", () => HttpResponse.json({
      id: "owner-id", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({
      headerName: "X-XSRF-TOKEN", token: "test-token" })),
    http.get("/api/v1/projects/:projectId", () => HttpResponse.json(snapshot().project)),
    http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json(snapshot())),
    http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items })),
    http.get("/api/v1/projects/:projectId/artifacts", () => HttpResponse.json({
      items: items.map((item) => item.artifact) })),
    http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/export-proposals", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/usage", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/assets/:assetId", ({ params }) => HttpResponse.json({
      id: params.assetId, width: 1024, height: 1024 })),
    http.get("/api/v1/projects/:projectId/canvas-items/:canvasItemId/media-draft", ({ params }) =>
      HttpResponse.json({ projectId: "project-1", canvasItemId: params.canvasItemId, prompt: "",
        parameters: {}, durationSeconds: null, capabilityId: null, videoInputMode: null,
        mediaInputs: [], mentions: [], displayMode: "RESULT", version: 0,
        createdAt: now, updatedAt: now })),
    http.put("/api/v1/projects/:projectId/canvas-items/:canvasItemId/media-draft", async ({ request, params }) => {
      const input = await request.json() as SaveMediaDraftRequest;
      return HttpResponse.json({ ...input, projectId: "project-1", canvasItemId: params.canvasItemId,
        displayMode: "RESULT", version: input.expectedVersion + 1, createdAt: now, updatedAt: now });
    }));
});

async function renderFlow(expectedNodeCount = items.length) {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(<QueryClientProvider client={client}>
    <MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter>
  </QueryClientProvider>);
  await waitFor(() => expect(flowProps.nodes).toHaveLength(expectedNodeCount));
  return client;
}

const selectedIds = () => useCanvasStore.getState().selectedIds;
const selectChange = (id: string, selected: boolean): SelectionChange =>
  ({ id, type: "select", selected });

describe("canvas keyboard ownership", () => {
  it.each([
    { key: "c", metaKey: true }, { key: "v", ctrlKey: true }, { key: "d", metaKey: true },
    { key: "z", metaKey: true }, { key: "Z", metaKey: true, shiftKey: true }, { key: "y", ctrlKey: true },
    { key: "Backspace" }, { key: "Delete" },
    { key: "c", metaKey: true, repeat: true }, { key: "v", ctrlKey: true, repeat: true },
    { key: "z", metaKey: true, repeat: true },
  ])("consumes unavailable canvas shortcut %j without invoking the browser", async (options) => {
    await renderInteractiveFlow();
    const pane = document.querySelector(".react-flow__pane")!;
    const shortcut = createEvent.keyDown(pane, options);
    fireEvent(pane, shortcut);
    expect(shortcut.defaultPrevented).toBe(true);
    expect(selectedIds()).toEqual([]);
    expect(flowProps.nodes).toHaveLength(items.length);
  });

  it("leaves deletion to an open dialog even while a canvas node stays selected", async () => {
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["image-card"]));
    const dialog = document.createElement("div"); dialog.setAttribute("role", "dialog");
    const content = document.createElement("div"); dialog.append(content); document.body.append(dialog);
    try {
      const shortcut = createEvent.keyDown(content, { key: "Backspace", code: "Backspace" });
      fireEvent(content, shortcut);
      expect(shortcut.defaultPrevented).toBe(false);
      expect(selectedIds()).toEqual(["image-card"]);
    } finally { dialog.remove(); }
  });

  it("deletes a selected relation through the same guarded shortcut without removing its nodes", async () => {
    let connected = true;
    const disconnected: unknown[] = [];
    server.use(
      http.get("/api/v1/projects/:projectId/canvas/connections", () => HttpResponse.json({ items: connected ? [{
        id: "media-line", projectId: "project-1", sourceCanvasItemId: "image-card", targetCanvasItemId: "text-card",
        sourceArtifactVersionId: imageVersionId, relationType: "MEDIA_INPUT", version: 0, createdAt: now, updatedAt: now,
      }] : [] })),
      http.post("/api/v1/projects/:projectId/canvas/connections/:connectionId/disconnect", async ({ request }) => {
        disconnected.push(await request.json()); connected = false;
        return HttpResponse.json({ projectId: "project-1", canvasItemId: "text-card", prompt: "", parameters: {},
          durationSeconds: null, capabilityId: null, videoInputMode: null, mediaInputs: [], mentions: [],
          displayMode: "RESULT", version: 1, createdAt: now, updatedAt: now });
      }),
    );
    await renderInteractiveFlow();
    await waitFor(() => expect(flowProps.edges).toHaveLength(1));
    act(() => flowProps.onEdgesChange?.([{ id: flowProps.edges![0]!.id, type: "select", selected: true }]));
    await waitFor(() => expect(flowProps.edges?.[0]?.selected).toBe(true));
    const pane = document.querySelector(".react-flow__pane")!;
    const shortcut = createEvent.keyDown(pane, { key: "Delete" });
    fireEvent(pane, shortcut);
    expect(shortcut.defaultPrevented).toBe(true);
    await waitFor(() => expect(flowProps.edges).toHaveLength(0));
    expect(disconnected).toEqual([{ expectedTargetDraftVersion: 0 }]);
    expect(flowProps.nodes).toHaveLength(items.length);
  });

  it("ungroups multiple selected group frames sequentially while keeping every member", async () => {
    let grouped: CanvasItem[] = items.map((item, index) => ({ ...item, groupId: index < 2 ? "group-1" : "group-2" }));
    let groups = ["group-1", "group-2"].map((id) => ({ id, projectId: "project-1", name: id, collapsed: true, version: 0 }));
    const requests: CanvasEditRequest[] = [];
    server.use(
      http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({ ...snapshot(), canvas: { items: grouped } })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: grouped })),
      http.get("/api/v1/projects/:projectId/canvas/editor", () => HttpResponse.json({ items: grouped, groups })),
      http.post("/api/v1/projects/:projectId/canvas/edits", async ({ request }) => {
        const input = await request.json() as CanvasEditRequest; requests.push(input);
        grouped = grouped.map((item) => input.itemChanges?.some((change) => change.itemId === item.id)
          ? { ...item, groupId: null, version: item.version + 1 } : item);
        groups = groups.filter((group) => !input.groupChanges?.some((change) => change.groupId === group.id));
        return HttpResponse.json({ items: grouped, groups, receipt: {
          id: input.commandId, operation: input.operation, revision: 0, state: "APPLIED" } });
      }),
    );
    renderRealFlow = true;
    await renderFlow(items.length + 2);
    act(() => useCanvasStore.getState().setSelectedIds([canvasGroupNodeId("group-1"), canvasGroupNodeId("group-2")]));
    fireEvent.keyDown(document.querySelector(".react-flow__pane")!, { key: "Delete" });
    await waitFor(() => expect(requests).toHaveLength(2));
    await waitFor(() => expect(flowProps.nodes).toHaveLength(items.length));
    expect(requests.every((request) => request.operation === "GROUP")).toBe(true);
    expect(grouped.every((item) => item.groupId === null)).toBe(true);
  });

  it("keeps native copy of a manually selected text range outside an editor", async () => {
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["image-card"]));
    const text = within(nodeElement("text-card")).getByText("选中的正文");
    const range = document.createRange(); range.selectNodeContents(text);
    window.getSelection()?.addRange(range);
    try {
      const shortcut = createEvent.keyDown(text, { key: "c", metaKey: true });
      fireEvent(text, shortcut);
      expect(shortcut.defaultPrevented).toBe(false);
      expect(window.getSelection()?.toString()).toBe("选中的正文");
      expect(selectedIds()).toEqual(["image-card"]);
    } finally { window.getSelection()?.removeAllRanges(); }
  });

  it("consumes shortcuts during a pending edit and after its failure without submitting again", async () => {
    const requests: CanvasEditRequest[] = [];
    let finish: () => void = () => {};
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    server.use(
      http.get("/api/v1/projects/:projectId/canvas/editor", () => HttpResponse.json({ items, groups: [] })),
      http.post("/api/v1/projects/:projectId/canvas/edits", async ({ request }) => {
        requests.push(await request.json() as CanvasEditRequest);
        await pending;
        return HttpResponse.json({ code: "SYNTHETIC_FAILURE", title: "Synthetic edit unavailable" }, { status: 503 });
      }),
    );
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["middle-card"]));
    const pane = document.querySelector(".react-flow__pane")!;
    fireEvent.keyDown(pane, { key: "d", metaKey: true });
    await waitFor(() => expect(requests).toHaveLength(1));
    try {
      for (const options of [{ key: "c", ctrlKey: true }, { key: "v", metaKey: true },
        { key: "z", metaKey: true }, { key: "Z", metaKey: true, shiftKey: true },
        { key: "d", metaKey: true, repeat: true }, { key: "Delete" }]) {
        const shortcut = createEvent.keyDown(pane, options);
        fireEvent(pane, shortcut);
        expect(shortcut.defaultPrevented).toBe(true);
      }
      expect(requests).toHaveLength(1);
    } finally { finish(); }
    await waitFor(() => expect(useCanvasStore.getState().saveState).toBe("failed"));
    for (const key of ["z", "y"]) {
      const shortcut = createEvent.keyDown(pane, { key, ctrlKey: true });
      fireEvent(pane, shortcut);
      expect(shortcut.defaultPrevented).toBe(true);
    }
    expect(requests).toHaveLength(1);
  });
});

describe("canvas selection clearing", () => {
  it("offers relevant bulk actions for any selected cards with a working close button", async () => {
    const canvasItems = items;
    const commands: CanvasItemChange[][] = [];
    server.use(
      http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({ ...snapshot(), canvas: { items: canvasItems } })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: canvasItems })),
      http.post("/api/v1/projects/:projectId/canvas/edits", async ({ request }) => {
        const input = await request.json() as CanvasEditRequest;
        expect(input).toMatchObject({ operation: "DISPLAY", commandId: expect.any(String) });
        commands.push(input.itemChanges ?? []);
        return HttpResponse.json({ items: canvasItems, groups: [], receipt: {
          id: input.commandId, operation: input.operation, revision: 0, state: "APPLIED" } });
      }),
    );
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "text-card"]));
    const toolbar = await screen.findByRole("group", { name: "批量操作" });
    expect(selectedIds()).toEqual(["image-card", "text-card"]);
    expect(within(toolbar).getByText("2 张卡片已选中")).toBeVisible();
    expect(within(toolbar).queryByRole("button", { name: "绑定到 Agent" })).not.toBeInTheDocument();
    expect(within(toolbar).queryByRole("button", { name: "清空 Agent 输入" })).not.toBeInTheDocument();
    fireEvent.click(within(toolbar).getByRole("button", { name: "左对齐" }));
    await waitFor(() => expect(commands).toHaveLength(1));
    expect(commands[0]).toEqual([
      expect.objectContaining({ itemId: "image-card", x: 0 }),
      expect.objectContaining({ itemId: "text-card", x: 0 }),
    ]);
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "text-card", "middle-card"]));
    expect(within(toolbar).queryByRole("button", { name: "绑定到 Agent" })).not.toBeInTheDocument();
    expect(within(toolbar).queryByRole("button", { name: "清空 Agent 输入" })).not.toBeInTheDocument();
    fireEvent.click(within(toolbar).getByRole("button", { name: "关闭编辑区" }));
    expect(selectedIds()).toEqual([]);
    expect(screen.queryByRole("group", { name: "批量操作" })).not.toBeInTheDocument();
  });

  it("clears the card selection when React Flow reports a select change", async () => {
    await renderFlow();
    useCanvasStore.setState({ selectedIds: ["image-card", "middle-card"] });
    flowProps.onNodesChange?.([selectChange("image-card", false)]);
    expect(selectedIds()).toEqual(["middle-card"]);
  });

  it("drops every card when the pane reports an empty selection", async () => {
    await renderFlow();
    useCanvasStore.setState({ selectedIds: ["image-card", "middle-card"] });
    flowProps.onNodesChange?.([
      selectChange("image-card", false), selectChange("middle-card", false)]);
    expect(selectedIds()).toEqual([]);
  });

  it("clears cards and relation lines when the user moves the viewport", async () => {
    server.use(http.get("/api/v1/projects/:projectId/canvas/connections", () => HttpResponse.json({ items: [{
      id: "media-line", projectId: "project-1", sourceCanvasItemId: "image-card", targetCanvasItemId: "text-card",
      sourceArtifactVersionId: imageVersionId, relationType: "MEDIA_INPUT", version: 0, createdAt: now, updatedAt: now,
    }] })));
    await renderFlow();
    await waitFor(() => expect(flowProps.edges).toHaveLength(1));
    const edgeId = flowProps.edges?.[0]?.id;
    expect(edgeId).toBeTruthy();
    useCanvasStore.setState({ selectedIds: ["image-card"] });
    flowProps.onEdgesChange?.([selectChange(edgeId!, true)]);
    await waitFor(() => expect(flowProps.edges?.[0]?.selected).toBe(true));
    flowProps.onMoveStart?.(new MouseEvent("mousedown"), { x: 0, y: 0, zoom: 1 });
    expect(selectedIds()).toEqual([]);
    await waitFor(() => expect(flowProps.edges?.[0]?.selected).toBeFalsy());
  });

  it("applies replacement and additive select changes", async () => {
    await renderFlow();
    useCanvasStore.setState({ selectedIds: ["middle-card"] });
    flowProps.onNodesChange?.([selectChange("middle-card", false), selectChange("image-card", true)]);
    expect(selectedIds()).toEqual(["image-card"]);
    flowProps.onNodesChange?.([selectChange("middle-card", true)]);
    expect(selectedIds()).toEqual(["image-card", "middle-card"]);
  });

  it("keeps the selection for a programmatic viewport move", async () => {
    await renderFlow();
    useCanvasStore.setState({ selectedIds: ["image-card"] });
    flowProps.onMoveStart?.(null, { x: 0, y: 0, zoom: 1 });
    expect(selectedIds()).toEqual(["image-card"]);
  });
});

// Use the real library with the workspace callbacks: mocked select notifications cannot
// expose differences between clicking an unselected node and one inside a multi-selection.
function nodeElement(id: string) {
  const element = document.querySelector(`[data-id="${id}"].react-flow__node`);
  if (!(element instanceof HTMLElement)) throw new Error(`Missing node ${id}`);
  return element;
}
async function renderInteractiveFlow() {
  renderRealFlow = true;
  await renderFlow();
  await waitFor(() => expect(nodeElement("image-card")).toBeInTheDocument());
}

function panePointerGesture(type: "pointerDown" | "pointerMove" | "pointerUp", x: number, y: number) {
  const pane = document.querySelector(".react-flow__pane");
  if (!(pane instanceof HTMLElement)) throw new Error("Missing pane");
  const event = createEvent[type](pane, { bubbles: true });
  Object.defineProperties(event, {
    clientX: { value: x }, clientY: { value: y },
    button: { value: 0 }, isPrimary: { value: true }, pointerId: { value: 1 },
    pointerType: { value: "mouse" },
  });
  fireEvent(pane, event);
}

function boxSelectNodes(box: typeof SELECTION_BOX = SELECTION_BOX) {
  for (const type of ["pointerDown", "pointerMove", "pointerUp"] as const) {
    panePointerGesture(type, type === "pointerDown" ? box.startX : box.endX,
      type === "pointerDown" ? box.startY : box.endY);
  }
  // Browsers dispatch click after pointerup; the pane consumes this selection-ending click.
  fireEvent.click(document.querySelector(".react-flow__pane")!);
}

describe("video hover controls", () => {
  it("keeps the editor closed when hovering and seeking, then opens it on a picture click", async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
    const source = items[0]!;
    const version = source.artifact!.resourceDefaultVersion!;
    const videoVersion = { ...version, content: { sourceType: "UPLOAD", assetId: "synthetic-video" } };
    const videoItems = items.map((item) => item.id === source.id ? {
      ...item, selectedVersionId: videoVersion.id, selectedVersion: videoVersion,
      artifact: { ...source.artifact!, kind: "VIDEO" as const, resourceDefaultVersion: videoVersion },
    } : item);
    server.use(
      http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({ ...snapshot(), canvas: { items: videoItems } })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: videoItems })),
    );
    await renderInteractiveFlow();
    const poster = await screen.findByRole("img", { name: "参考图 的视频封面" });
    fireEvent.mouseEnter(poster.parentElement!);
    expect(screen.queryByLabelText("参考图 的视频")).not.toBeInTheDocument();
    expect(play).not.toHaveBeenCalled();
    const video = await screen.findByLabelText("参考图 的视频", {}, { timeout: 2000 }) as HTMLVideoElement;
    expect(play).toHaveBeenCalledOnce();
    expect(video.muted).toBe(true);
    Object.defineProperty(video, "duration", { configurable: true, value: 10 });
    fireEvent.loadedMetadata(video);
    const seek = screen.getByRole("slider", { name: "视频播放进度" });
    fireEvent.pointerDown(seek);
    fireEvent.mouseDown(seek);
    fireEvent.change(seek, { target: { value: "5" } });
    fireEvent.mouseUp(seek);
    fireEvent.click(seek);
    expect(video.currentTime).toBe(5);
    expect(selectedIds()).toEqual([]);
    expect(screen.queryByLabelText("媒体卡片操作")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    expect(focusProbe.center).not.toHaveBeenCalled();
    // Hover playback must still allow dragging the picture; only its controls opt out.
    nodeMouseGesture("mouseDown", video, 0);
    nodeMouseGesture("mouseMove", video, CANVAS_POINTER_THRESHOLD + 1);
    expect(nodeElement(source.id).querySelector(".artifact-canvas-card")).toHaveClass("is-selected");
    expect(selectedIds()).toEqual([]);
    nodeMouseGesture("mouseUp", video, 0);
    fireEvent.click(video);
    expect(selectedIds()).toEqual([]);
    await act(async () => {});
    fireEvent.click(video);
    expect(selectedIds()).toEqual([source.id]);
    expect(await screen.findByLabelText("媒体卡片操作")).toBeVisible();
    expect(await screen.findByLabelText("所选卡片编辑区")).toBeVisible();
  });
});

describe("connection gesture overlays", () => {
  function startConnection() {
    act(() => flowProps.onConnectStart?.(new MouseEvent("mousedown"), {
      nodeId: "image-card", handleId: "artifact-output", handleType: "source",
    }));
  }

  function endConnection() {
    act(() => flowProps.onConnectEnd?.(new MouseEvent("mouseup"), {
      isValid: null, from: null, fromHandle: null, fromPosition: null, fromNode: null,
      to: null, toHandle: null, toPosition: null, toNode: null, pointer: null,
    }));
  }

  it.each([
    ["image-card", "媒体卡片操作"],
    ["text-card", "文字卡片操作"],
  ])("hides %s actions while connecting and restores the editor without losing input", async (id, label) => {
    await renderInteractiveFlow();
    fireEvent.click(nodeElement(id));
    expect(await screen.findByLabelText(label)).toBeVisible();
    const editor = await screen.findByLabelText("所选卡片编辑区");
    const prompt = await within(editor).findByRole("textbox");
    if (prompt instanceof HTMLTextAreaElement) fireEvent.change(prompt, { target: { value: "未保存的提示词" } });
    else {
      prompt.textContent = "未保存的提示词";
      fireEvent.input(prompt);
    }
    startConnection();
    expect(screen.queryByLabelText(label)).not.toBeInTheDocument();
    expect(editor).toBeInTheDocument();
    expect(editor).not.toBeVisible();
    expect(selectedIds()).toEqual([id]);
    endConnection();
    expect(await screen.findByLabelText(label)).toBeVisible();
    expect(screen.getByLabelText("所选卡片编辑区")).toBe(editor);
    expect(editor).toBeVisible();
    expect(within(editor).getByRole("textbox")).toBe(prompt);
    if (prompt instanceof HTMLTextAreaElement) expect(prompt).toHaveValue("未保存的提示词");
    else expect(prompt).toHaveTextContent("未保存的提示词");
  });

  it("hides and restores bulk actions without changing the selection", async () => {
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "text-card"]));
    expect(await screen.findByRole("group", { name: "批量操作" })).toBeVisible();
    startConnection();
    expect(screen.queryByRole("group", { name: "批量操作" })).not.toBeInTheDocument();
    expect(selectedIds()).toEqual(["image-card", "text-card"]);
    endConnection();
    expect(await screen.findByRole("group", { name: "批量操作" })).toBeVisible();
    expect(selectedIds()).toEqual(["image-card", "text-card"]);
  });
});

describe("node editor centering", () => {
  it("waits for referenced group definitions before fitting a collapsed canvas", async () => {
    renderRealFlow = true;
    const groupedItems = items.map((item, index) => ({ ...item, groupId: index < 2 ? "initial-group" : null }));
    let completeGroupRead: (() => void) | undefined;
    const groupRead = new Promise<void>((resolve) => { completeGroupRead = resolve; });
    let reads = 0;
    server.use(
      http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({
        ...snapshot(), canvas: { items: groupedItems },
      })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: groupedItems })),
      http.get("/api/v1/projects/:projectId/canvas/editor", async () => {
        reads++; await groupRead;
        return HttpResponse.json({ items: groupedItems, groups: [{ id: "initial-group", projectId: "project-1",
          name: "Collapsed group", collapsed: true, version: 0 }] });
      }),
    );
    await renderFlow();
    await waitFor(() => { expect(reads).toBe(1); expect(focusProbe.instance).not.toBeNull(); });
    expect(focusProbe.fit).not.toHaveBeenCalled();
    act(() => completeGroupRead?.());
    await waitFor(() => expect(focusProbe.fit).toHaveBeenCalledOnce());
    const fitted = focusProbe.fit.mock.lastCall?.[0]?.nodes;
    expect(fitted).toHaveLength(2);
    expect(fitted).toContainEqual({ id: "text-card" });
    expect(fitted).not.toContainEqual({ id: "image-card" });
    expect(fitted).not.toContainEqual({ id: "middle-card" });
  });

  it("fits the loaded snapshot when a delayed initial callback captured the pending query", async () => {
    renderRealFlow = true;
    focusProbe.deferInit = true;
    await renderFlow();
    await waitFor(() => expect(focusProbe.pendingInit).not.toBeNull());
    expect(focusProbe.fit).not.toHaveBeenCalled();
    act(() => focusProbe.pendingInit?.());
    await waitFor(() => expect(focusProbe.fit).toHaveBeenCalledOnce());
    expect(focusProbe.fit).toHaveBeenCalledWith({ padding: 0.2, maxZoom: 1,
      nodes: [{ id: "image-card" }, { id: "middle-card" }, { id: "text-card" }] });
  });

  it("fits the first loaded snapshot once and preserves the view on later canvas refreshes", async () => {
    renderRealFlow = true;
    const client = await renderFlow();
    await waitFor(() => expect(focusProbe.fit).toHaveBeenCalledOnce());
    expect(focusProbe.fit).toHaveBeenCalledWith({ padding: 0.2, maxZoom: 1,
      nodes: [{ id: "image-card" }, { id: "middle-card" }, { id: "text-card" }] });
    act(() => client.setQueryData(["canvas", "project-1"], {
      items: items.map((item) => ({ ...item, x: item.x + 100, version: item.version + 1 })),
    }));
    await waitFor(() => expect(flowProps.nodes?.find((node) => node.id === "image-card")?.position.x).toBe(100));
    expect(focusProbe.fit).toHaveBeenCalledOnce();
  });

  it.each(["ARTIFACT", "CHARACTER"] as const)("keeps an initially empty canvas view when the first %s card arrives", async (subjectType) => {
    renderRealFlow = true;
    server.use(
      http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({
        ...snapshot(), canvas: { items: [] }, connections: [],
      })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [] })),
    );
    const client = await renderFlow(0);
    await waitFor(() => expect(focusProbe.instance).not.toBeNull());
    await screen.findByRole("button", { name: "创建第一个节点" });
    expect(focusProbe.fit).not.toHaveBeenCalled();
    const original = items[0];
    if (!original) throw new Error("Missing initial item fixture");
    const first: CanvasItem = subjectType === "ARTIFACT" ? original : { ...original, id: "character-card", subjectType: "CHARACTER", subjectId: "hero", artifact: null,
      characterNode: { canvasItemId: "character-card", version: 0, newerVersionAvailable: false, connections: [],
        selection: { characterId: "hero", characterVersion: 1, lookId: null, viewVersionIds: [], useAppearance: true, useVoice: false },
        snapshot: { characterId: "hero", characterVersion: 1, name: "Hero", description: "Pilot", look: null, voice: null, useAppearance: true, useVoice: false } } };
    expect(flowProps.fitView).not.toBe(true);
    act(() => client.setQueryData(["canvas", "project-1"], { items: [first] }));
    await waitFor(() => expect(flowProps.nodes).toHaveLength(1));
    expect(focusProbe.fit).not.toHaveBeenCalled();
  });

  it.each([0.6, 1.8])("centers initial, repeated, and switched editor clicks at zoom %s without changing node layouts", async (zoom) => {
    focusProbe.zoom = zoom;
    // Focus must use the current canvas projection, including unsaved layout and media dimensions.
    useCanvasStore.getState().updateDraft("image-card", { x: 120, y: 620, width: 420, height: 320 });
    const originalBounds = HTMLElement.prototype.getBoundingClientRect;
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      return this.classList.contains("workspace-canvas")
        ? new DOMRect(0, 0, TEST_VIEWPORT.width, TEST_VIEWPORT.height)
        : originalBounds.call(this);
    });
    await renderInteractiveFlow();
    await waitFor(() => expect(focusProbe.instance).not.toBeNull());
    const fitsAfterInitialLoad = focusProbe.fit.mock.calls.length;
    const viewport = focusProbe.instance!.getViewport();
    expect(viewport.zoom).toBe(zoom);
    const layouts = new Map(["image-card", "text-card"].map((id) => {
      const { width, height, transform } = nodeElement(id).style;
      return [id, { width, height, transform }];
    }));
    const layoutDrafts = useCanvasStore.getState().drafts;
    let previousEditor: HTMLElement | undefined;
    let previousPrompt: HTMLElement | undefined;
    let previousId: string | undefined;
    for (const id of ["image-card", "image-card", "text-card"]) {
      const projected = flowProps.nodes?.find((node) => node.id === id);
      const { width, height } = projected?.measured ?? {};
      if (!projected || width === undefined || height === undefined) throw new Error(`Missing projected dimensions for ${id}`);
      const centersBeforeClick = focusProbe.center.mock.calls.length;
      fireEvent.click(nodeElement(id));
      expect(selectedIds()).toEqual([id]);
      expect(await screen.findByLabelText(id === "text-card" ? "文字卡片操作" : "媒体卡片操作")).toBeVisible();
      const editor = await screen.findByLabelText("所选卡片编辑区");
      expect(editor).toBeVisible();
      const prompt = await within(editor).findByRole("textbox");
      if (previousId === id) {
        expect(editor).toBe(previousEditor);
        expect(prompt).toBe(previousPrompt);
        if (prompt instanceof HTMLTextAreaElement) expect(prompt).toHaveValue("点击居中仍保留的提示词");
        else expect(prompt).toHaveTextContent("点击居中仍保留的提示词");
      } else if (id === "image-card") {
        if (prompt instanceof HTMLTextAreaElement) fireEvent.change(prompt, { target: { value: "点击居中仍保留的提示词" } });
        else {
          prompt.textContent = "点击居中仍保留的提示词";
          fireEvent.input(prompt);
        }
      }
      previousEditor = editor;
      previousPrompt = prompt;
      previousId = id;
      const editorToolbar = editor.closest(".react-flow__node-toolbar");
      expect(editorToolbar).toHaveAttribute("data-id", id);
      // The portal translates to the node but must never inherit the canvas zoom.
      expect((editorToolbar as HTMLElement).style.transform).not.toContain("scale");
      await act(() => new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve())));
      for (const [itemId, layout] of layouts) expect(nodeElement(itemId)).toHaveStyle(layout);
      expect(useCanvasStore.getState().drafts).toBe(layoutDrafts);
      expect(focusProbe.center).toHaveBeenCalledTimes(centersBeforeClick + 1);
      expect(focusProbe.center).toHaveBeenLastCalledWith(
        projected.position.x + width / 2, projected.position.y + height / 2,
        expect.objectContaining({ zoom, duration: expect.any(Number), ease: expect.any(Function), interpolate: "linear" }));
      const duration = focusProbe.center.mock.lastCall?.[2]?.duration;
      expect(duration).toBeGreaterThanOrEqual(240);
      expect(duration).toBeLessThanOrEqual(420);
      // The probe does not animate the camera. A second focus may stop the previous
      // native transition, but that stop must preserve the current position and zoom.
      for (const call of focusProbe.viewport.mock.calls) expect(call).toEqual([viewport, { duration: 0 }]);
      expect(focusProbe.fit).toHaveBeenCalledTimes(fitsAfterInitialLoad);
    }
    const centersBeforeClosing = focusProbe.center.mock.calls.length;
    const closeEditor = screen.getByRole("button", { name: "关闭编辑区" });
    closeEditor.focus();
    fireEvent.click(closeEditor);
    expect(selectedIds()).toEqual([]);
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    expect(nodeElement("text-card")).toHaveFocus();
    expect(flowProps.autoPanOnNodeFocus).toBe(false);
    expect(focusProbe.instance!.getViewport()).toEqual(viewport);
    expect(focusProbe.center).toHaveBeenCalledTimes(centersBeforeClosing);
    for (const call of focusProbe.viewport.mock.calls) expect(call).toEqual([viewport, { duration: 0 }]);
    expect(focusProbe.fit).toHaveBeenCalledTimes(fitsAfterInitialLoad);
  });

  it("focuses both media and text nodes immediately when reduced motion is requested", async () => {
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true })));
    await renderInteractiveFlow();
    for (const id of ["image-card", "text-card"]) {
      fireEvent.click(nodeElement(id));
      await act(() => new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve())));
      expect(focusProbe.center).toHaveBeenLastCalledWith(expect.any(Number), expect.any(Number), {
        zoom: 1, duration: 0, ease: expect.any(Function), interpolate: "linear",
      });
    }
    expect(focusProbe.center).toHaveBeenCalledTimes(2);
    expect(focusProbe.viewport).not.toHaveBeenCalled();
  });

  it.each(["pointerDown", "wheel"] as const)("cancels a pending text editor focus on %s before the next frame", async (gesture) => {
    await renderInteractiveFlow();
    const schedule = vi.spyOn(window, "requestAnimationFrame");
    const cancel = vi.spyOn(window, "cancelAnimationFrame");
    fireEvent.click(nodeElement("text-card"));
    expect(schedule).toHaveBeenCalled();
    const frame = schedule.mock.results.at(-1)?.value;
    expect(frame).toEqual(expect.any(Number));
    fireEvent[gesture](nodeElement("image-card"));
    expect(cancel).toHaveBeenCalledWith(frame);
    await act(() => new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve())));
    expect(focusProbe.center).not.toHaveBeenCalled();
  });

  it("stops an unfinished focus in pointer capture before a node starts dragging", async () => {
    const saves = observeLayoutSaves();
    // D3's interrupted animation promise can remain pending forever. A new gesture
    // must never wait for that promise before it can drag or save a node.
    focusProbe.center.mockImplementationOnce(() => new Promise<boolean>(() => {}));
    await renderInteractiveFlow();
    const node = nodeElement("image-card");
    fireEvent.click(node);
    expect(focusProbe.center).toHaveBeenCalledOnce();
    const viewport = focusProbe.instance!.getViewport();
    const onPointerDown = vi.fn(() => {
      expect(focusProbe.viewport).toHaveBeenCalledExactlyOnceWith(viewport, { duration: 0 });
    });
    node.addEventListener("pointerdown", onPointerDown, { once: true });
    fireEvent.pointerDown(node);
    expect(onPointerDown).toHaveBeenCalledOnce();
    nodeMouseGesture("mouseDown", node, 0);
    nodeMouseGesture("mouseMove", node, CANVAS_POINTER_THRESHOLD + 1);
    nodeMouseGesture("mouseMove", node, 40);
    nodeMouseGesture("mouseUp", node, 40);
    await waitFor(() => expect(saves).toHaveLength(1));
    expect(saves[0]).toEqual([expect.objectContaining({ itemId: "image-card", x: 36, y: 0 })]);
    expect(focusProbe.center).toHaveBeenCalledOnce();
  });

  it("keeps programmatic camera motion and gives user viewport movement immediate control", async () => {
    await renderInteractiveFlow();
    fireEvent.click(nodeElement("image-card"));
    const viewport = focusProbe.instance!.getViewport();
    act(() => flowProps.onMoveStart?.(null, viewport));
    expect(focusProbe.viewport).not.toHaveBeenCalled();
    expect(selectedIds()).toEqual(["image-card"]);
    act(() => flowProps.onMoveStart?.(new MouseEvent("mousedown"), viewport));
    expect(focusProbe.viewport).toHaveBeenCalledExactlyOnceWith(viewport, { duration: 0 });
    expect(selectedIds()).toEqual([]);
  });

  it("leaves composing Escape to the input method, then returns focus when the editor closes", async () => {
    await renderInteractiveFlow();
    fireEvent.click(nodeElement("text-card"));
    const prompt = await screen.findByRole("textbox", { name: "文字生成提示词" });
    if (prompt instanceof HTMLTextAreaElement) fireEvent.change(prompt, { target: { value: "正在输入的中文" } });
    else {
      prompt.textContent = "正在输入的中文";
      fireEvent.input(prompt);
    }
    await act(() => new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve())));
    const centersBeforeClosing = focusProbe.center.mock.calls.length;
    prompt.focus();
    const transform = document.querySelector(".react-flow__viewport")?.getAttribute("style");
    fireEvent.keyDown(prompt, { key: "Escape", isComposing: true });
    expect(selectedIds()).toEqual(["text-card"]);
    expect(prompt).toHaveFocus();
    fireEvent.keyDown(prompt, { key: "Escape", isComposing: false });
    expect(selectedIds()).toEqual([]);
    expect(nodeElement("text-card")).toHaveFocus();
    expect(document.querySelector(".react-flow__viewport")).toHaveAttribute("style", transform!);
    expect(focusProbe.center).toHaveBeenCalledTimes(centersBeforeClosing);
    fireEvent.keyDown(nodeElement("text-card"), { key: "Enter" });
    const reopenedPrompt = await screen.findByRole("textbox", { name: "文字生成提示词" });
    if (reopenedPrompt instanceof HTMLTextAreaElement) expect(reopenedPrompt).toHaveValue("正在输入的中文");
    else expect(reopenedPrompt).toHaveTextContent("正在输入的中文");
  });
});

/** Exercise the library's actual click suppression and drag threshold, rather than only its callbacks. */
function nodeMouseGesture(type: "mouseDown" | "mouseMove" | "mouseUp", node: HTMLElement,
  displacement: number) {
  const origin = 100;
  const target = type === "mouseDown" ? node : window;
  const event = createEvent[type](target, {
    button: 0, buttons: type === "mouseUp" ? 0 : 1,
    clientX: origin + displacement, clientY: origin,
  });
  Object.defineProperty(event, "view", { value: window });
  fireEvent(target, event);
}

function observeLayoutSaves(fail = false, baseItems = items) {
  const batches: CanvasItemChange[][] = [];
  server.use(http.post("/api/v1/projects/project-1/canvas/edits", async ({ request }) => {
    const input = await request.json() as CanvasEditRequest;
    expect(input).toMatchObject({ operation: "DISPLAY", commandId: expect.any(String) });
    const changes = input.itemChanges ?? [];
    batches.push(changes);
    if (fail) return HttpResponse.json({ code: "VERSION_CONFLICT", title: "布局冲突" }, { status: 409 });
    return HttpResponse.json({ items: baseItems.map((item) => {
      const command = changes.find((candidate) => candidate.itemId === item.id);
      return command
        ? { ...item, x: command.x, y: command.y, width: command.width, height: command.height,
          version: item.version + 1 } : item;
    }), groups: [], receipt: { id: input.commandId, operation: input.operation, revision: 0, state: "APPLIED" } });
  }));
  return batches;
}

describe("node click and drag gestures", () => {
  it.each([
    ["text-card", ".content-card-body", 836],
    ["middle-card", ".content-card-empty", 436],
    ["middle-card", ".content-card-empty svg", 436],
  ] as const)("drags an unlocked text node from the blank surface %s %s and saves once", async (id, selector, x) => {
    const saves = observeLayoutSaves();
    await renderInteractiveFlow();
    const target = nodeElement(id).querySelector<HTMLElement>(selector);
    if (!target) throw new Error(`Missing text drag surface ${selector}`);
    nodeMouseGesture("mouseDown", target, 0);
    nodeMouseGesture("mouseMove", target, CANVAS_POINTER_THRESHOLD + 1);
    nodeMouseGesture("mouseMove", target, 40);
    nodeMouseGesture("mouseUp", target, 40);
    await waitFor(() => expect(saves).toHaveLength(1));
    expect(saves[0]).toEqual([expect.objectContaining({ itemId: id, x, y: 0 })]);
    await waitFor(() => expect(useCanvasStore.getState().saveState).toBe("saved"));
  });

  it("preserves native text selection instead of moving the node from its real text", async () => {
    const saves = observeLayoutSaves();
    await renderInteractiveFlow();
    const node = nodeElement("text-card");
    const text = node.querySelector<HTMLElement>(".content-card-text");
    if (!text) throw new Error("Missing text selection surface");
    const before = node.style.transform;
    nodeMouseGesture("mouseDown", text, 0);
    nodeMouseGesture("mouseMove", text, 40);
    nodeMouseGesture("mouseUp", text, 40);
    expect(node.style.transform).toBe(before);
    expect(saves).toHaveLength(0);
    expect(useCanvasStore.getState().drafts).toEqual({});
  });

  it("preserves editing gestures inside the text editor instead of moving the node", async () => {
    const saves = observeLayoutSaves();
    await renderInteractiveFlow();
    const node = nodeElement("text-card");
    fireEvent.doubleClick(within(node).getByText("选中的正文"));
    const editor = await screen.findByRole("textbox", { name: "内容" });
    const before = node.style.transform;
    nodeMouseGesture("mouseDown", editor, 0);
    nodeMouseGesture("mouseMove", editor, 40);
    nodeMouseGesture("mouseUp", editor, 40);
    expect(node.style.transform).toBe(before);
    expect(saves).toHaveLength(0);
    expect(useCanvasStore.getState().drafts).toEqual({});
  });

  it.each([".audio-player-details", ".audio-player"])("selects and drags an audio node from %s", async (targetSelector) => {
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
    const source = items[0]!;
    const version: NonNullable<CanvasItem["selectedVersion"]> = { ...source.artifact!.resourceDefaultVersion!,
      content: { sourceType: "UPLOAD", assetId: "synthetic-audio" } };
    const audioItems = items.map((item) => item.id === source.id ? {
      ...item, selectedVersionId: version.id, selectedVersion: version,
      artifact: { ...source.artifact!, kind: "AUDIO" as const, resourceDefaultVersion: version },
    } : item);
    server.use(
      http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({ ...snapshot(), canvas: { items: audioItems } })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: audioItems })),
      http.get("/api/v1/projects/:projectId/assets/synthetic-audio/content", () => new HttpResponse(null, { status: 503 })),
    );
    const saves = observeLayoutSaves(false, audioItems);
    await renderInteractiveFlow();
    const target = nodeElement("image-card").querySelector<HTMLElement>(targetSelector)!;
    fireEvent.click(target);
    expect(selectedIds()).toEqual(["image-card"]);
    act(() => useCanvasStore.getState().setSelectedIds([]));
    nodeMouseGesture("mouseDown", target, 0);
    nodeMouseGesture("mouseMove", target, CANVAS_POINTER_THRESHOLD + 1);
    expect(nodeElement("image-card").querySelector(".artifact-canvas-card")).toHaveClass("is-selected");
    nodeMouseGesture("mouseMove", target, 40);
    nodeMouseGesture("mouseUp", target, 40);
    await waitFor(() => expect(saves).toHaveLength(1));
    expect(saves[0]).toEqual([expect.objectContaining({ itemId: "image-card", x: 36, y: 0 })]);
  });

  it("highlights an unselected node only while dragging, saves on release, and accepts the next click", async () => {
    const saves = observeLayoutSaves();
    await renderInteractiveFlow();
    const node = nodeElement("image-card");
    nodeMouseGesture("mouseDown", node, 0);
    expect(selectedIds()).toEqual([]);
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    nodeMouseGesture("mouseMove", node, CANVAS_POINTER_THRESHOLD + 1);
    expect(node.querySelector(".artifact-canvas-card")).toHaveClass("is-selected");
    expect(screen.queryByLabelText("媒体卡片操作")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    expect(selectedIds()).toEqual([]);
    expect(saves).toHaveLength(0);
    nodeMouseGesture("mouseMove", node, 40);
    nodeMouseGesture("mouseUp", node, 40);
    // The browser's click following a completed drag must not open the editor.
    fireEvent.click(node);
    expect(selectedIds()).toEqual([]);
    expect(node.querySelector(".artifact-canvas-card")).not.toHaveClass("is-selected");
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    await waitFor(() => expect(saves).toHaveLength(1));
    expect(saves[0]).toEqual([expect.objectContaining({ itemId: "image-card",
      expectedVersion: 3, x: 36, y: 0 })]);
    await waitFor(() => expect(useCanvasStore.getState().saveState).toBe("saved"));
    expect(focusProbe.center).not.toHaveBeenCalled();
    fireEvent.click(node);
    expect(selectedIds()).toEqual(["image-card"]);
    expect(await screen.findByLabelText("媒体卡片操作")).toBeVisible();
    expect(await screen.findByLabelText("所选卡片编辑区")).toBeVisible();
  });

  it("hides the existing toolbar and editor when dragging a selected text node, then clears selection", async () => {
    observeLayoutSaves();
    await renderInteractiveFlow();
    const node = nodeElement("text-card");
    fireEvent.click(node);
    expect(await screen.findByLabelText("文字卡片操作")).toBeVisible();
    expect(await screen.findByLabelText("所选卡片编辑区")).toBeVisible();
    nodeMouseGesture("mouseDown", node, 0);
    nodeMouseGesture("mouseMove", node, CANVAS_POINTER_THRESHOLD + 1);
    expect(node.querySelector(".artifact-canvas-card")).toHaveClass("is-selected");
    expect(screen.queryByLabelText("文字卡片操作")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    nodeMouseGesture("mouseMove", node, 40);
    nodeMouseGesture("mouseUp", node, 40);
    expect(selectedIds()).toEqual([]);
    expect(node).not.toHaveClass("selected");
  });

  it("keeps a group highlighted during drag, clears it on release, and saves every moved node together", async () => {
    const saves = observeLayoutSaves();
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "middle-card"]));
    expect(await screen.findByLabelText("批量操作")).toBeVisible();
    const node = nodeElement("image-card");
    nodeMouseGesture("mouseDown", node, 0);
    nodeMouseGesture("mouseMove", node, CANVAS_POINTER_THRESHOLD + 1);
    expect(node.querySelector(".artifact-canvas-card")).toHaveClass("is-selected");
    expect(nodeElement("middle-card").querySelector(".artifact-canvas-card"))
      .toHaveClass("is-selected");
    expect(screen.queryByLabelText("批量操作")).not.toBeInTheDocument();
    nodeMouseGesture("mouseMove", node, 40);
    nodeMouseGesture("mouseUp", node, 40);
    expect(selectedIds()).toEqual([]);
    await waitFor(() => expect(saves).toHaveLength(1));
    expect(saves[0]).toEqual([
      expect.objectContaining({ itemId: "image-card", x: 36, y: 0 }),
      expect.objectContaining({ itemId: "middle-card", x: 436, y: 0 }),
    ]);
  });

  it("releases drag highlighting even when layout saving fails and retains the unsaved position", async () => {
    observeLayoutSaves(true);
    await renderInteractiveFlow();
    const node = nodeElement("image-card");
    nodeMouseGesture("mouseDown", node, 0);
    nodeMouseGesture("mouseMove", node, CANVAS_POINTER_THRESHOLD + 1);
    nodeMouseGesture("mouseMove", node, 40);
    nodeMouseGesture("mouseUp", node, 40);
    await waitFor(() => expect(useCanvasStore.getState().saveState).toBe("conflict"));
    expect(selectedIds()).toEqual([]);
    expect(node.querySelector(".artifact-canvas-card")).not.toHaveClass("is-selected");
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    expect(useCanvasStore.getState().drafts["image-card"]).toMatchObject({ x: 36, y: 0 });
  });
});

describe("workspace selection with real React Flow", () => {
  it.each([{ metaKey: true }, { ctrlKey: true }, { metaKey: true, repeat: true }])("selects all nodes instead of browser text with %j + A", async (modifier) => {
    await renderInteractiveFlow();
    const pane = document.querySelector(".react-flow__pane")!;
    const range = document.createRange();
    range.selectNodeContents(within(nodeElement("text-card")).getByText("选中的正文"));
    window.getSelection()?.addRange(range);
    const shortcut = createEvent.keyDown(pane, { key: "a", code: "KeyA", ...modifier });
    fireEvent(pane, shortcut);
    expect(shortcut.defaultPrevented).toBe(true);
    expect(window.getSelection()?.toString()).toBe("");
    expect(selectedIds()).toEqual(items.map((item) => item.id));
    for (const item of items) expect(nodeElement(item.id)).toHaveClass("selected");
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.MULTIPLE);
    expect(screen.getByRole("group", { name: "批量操作" })).toBeVisible();
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
  });

  it.each([false, true])("selects grouped nodes once with collapsed=%s and includes locked/offscreen cards", async (collapsed) => {
    const groupId = "group-1";
    const grouped = items.map((item, index) => ({ ...item, groupId: index < 2 ? groupId : null,
      ...(index === 2 ? { locked: true, x: 5000 } : {}) }));
    server.use(
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: grouped })),
      http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({ ...snapshot(), canvas: { items: grouped } })),
      http.get("/api/v1/projects/:projectId/canvas/editor", () => HttpResponse.json({ items: grouped, groups: [
        { id: groupId, projectId: "project-1", name: "镜头组", collapsed, version: 0 },
      ] })),
    );
    renderRealFlow = true;
    await renderFlow(items.length + 1);
    fireEvent.keyDown(document.querySelector(".react-flow__pane")!, { key: "a", metaKey: true });
    expect(selectedIds()).toEqual(collapsed ? [canvasGroupNodeId(groupId), "text-card"] : items.map((item) => item.id));
    expect(nodeElement("text-card")).toHaveClass("selected");
    expect(nodeElement(canvasGroupNodeId(groupId)).classList.contains("selected")).toBe(collapsed);
    expect(nodeElement("image-card").classList.contains("selected")).toBe(!collapsed);
    expect(screen.getByText("3 张卡片已选中")).toBeVisible();
  });

  it("keeps a single-node select-all in multi-select mode and handles an empty canvas", async () => {
    let currentItems = [items[0]!];
    server.use(
      http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({ ...snapshot(), canvas: { items: currentItems } })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: currentItems })),
    );
    renderRealFlow = true;
    const client = await renderFlow(1);
    fireEvent.keyDown(document.body, { key: "a", ctrlKey: true });
    expect(selectedIds()).toEqual(["image-card"]);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.MULTIPLE);
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    currentItems = [];
    act(() => client.setQueryData(["canvas", "project-1"], { items: currentItems }));
    await waitFor(() => expect(flowProps.nodes).toHaveLength(0));
    const shortcut = createEvent.keyDown(document.body, { key: "a", ctrlKey: true });
    fireEvent(document.body, shortcut);
    expect(shortcut.defaultPrevented).toBe(true);
    expect(selectedIds()).toEqual([]);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.SINGLE);
  });

  it("preserves native select-all and the draft inside the node text editor", async () => {
    await renderInteractiveFlow();
    fireEvent.click(nodeElement("text-card"));
    fireEvent.doubleClick(within(nodeElement("text-card")).getByText("选中的正文"));
    const editor = await screen.findByRole("textbox", { name: "内容" });
    fireEvent.change(editor, { target: { value: "尚未保存的正文" } });
    const shortcut = createEvent.keyDown(editor, { key: "a", metaKey: true });
    fireEvent(editor, shortcut);
    expect(shortcut.defaultPrevented).toBe(false);
    expect(selectedIds()).toEqual(["text-card"]);
    expect(editor).toHaveValue("尚未保存的正文");
  });

  it("keeps a single partially box-selected node in multi-select mode without opening its editor", async () => {
    await renderInteractiveFlow();
    boxSelectNodes({ startX: -10, startY: -10, endX: 10, endY: 10 });
    expect(selectedIds()).toEqual(["image-card"]);
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("媒体卡片操作")).not.toBeInTheDocument();
    const toolbar = screen.getByRole("group", { name: "批量操作" });
    expect(toolbar).toBeVisible();
    expect(within(toolbar).getByText("1 张卡片已选中")).toBeVisible();
    expect(within(toolbar).getByRole("button", { name: "左对齐" })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: "选择卡片：参考图" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "选择卡片：脚本" })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: "选择卡片：正文" })).not.toBeChecked();
    expect(focusProbe.center).not.toHaveBeenCalled();
    fireEvent.click(nodeElement("image-card"));
    expect(selectedIds()).toEqual(["image-card"]);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "批量操作" })).not.toBeInTheDocument();
    expect(await screen.findByLabelText("媒体卡片操作")).toBeVisible();
    expect(await screen.findByLabelText("所选卡片编辑区")).toBeVisible();
  });

  it("toggles cards through checkboxes while preserving multi-select at one or zero cards", async () => {
    const saves = observeLayoutSaves();
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "middle-card"]));
    const imageCheckbox = screen.getByRole("checkbox", { name: "选择卡片：参考图" });
    const agentCheckbox = screen.getByRole("checkbox", { name: "选择卡片：脚本" });
    const textCheckbox = screen.getByRole("checkbox", { name: "选择卡片：正文" });
    expect(imageCheckbox).toBeChecked();
    expect(agentCheckbox).toBeChecked();
    expect(textCheckbox).not.toBeChecked();
    nodeMouseGesture("mouseDown", textCheckbox, 0);
    nodeMouseGesture("mouseMove", textCheckbox, 40);
    nodeMouseGesture("mouseUp", textCheckbox, 40);
    fireEvent.click(textCheckbox);
    expect(selectedIds()).toEqual(["image-card", "middle-card", "text-card"]);
    expect(textCheckbox).toBeChecked();
    expect(saves).toHaveLength(0);
    fireEvent.click(agentCheckbox);
    fireEvent.click(imageCheckbox);
    expect(selectedIds()).toEqual(["text-card"]);
    expect(screen.getByRole("group", { name: "批量操作" })).toBeVisible();
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("文字卡片操作")).not.toBeInTheDocument();
    fireEvent.click(textCheckbox);
    expect(selectedIds()).toEqual([]);
    expect(screen.getAllByRole("checkbox")).toHaveLength(items.length);
    expect(screen.queryByRole("group", { name: "批量操作" })).not.toBeInTheDocument();
    fireEvent.click(imageCheckbox);
    expect(selectedIds()).toEqual(["image-card"]);
    expect(screen.queryByLabelText("媒体卡片操作")).not.toBeInTheDocument();
    fireEvent.keyDown(imageCheckbox, { key: "Escape" });
    expect(selectedIds()).toEqual([]);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(focusProbe.center).not.toHaveBeenCalled();
  });

  it.each([
    { name: "a slight blank-pane movement", endX: 1101, endY: 701, previous: [] },
    { name: "a large empty box", endX: 1200, endY: 800, previous: [] },
    { name: "an empty box after a single selection", endX: 1200, endY: 800, previous: ["image-card"] },
    { name: "an empty box after a group selection", endX: 1200, endY: 800, previous: ["image-card", "middle-card"] },
  ])("does not enter multi-select with $name", async ({ endX, endY, previous }) => {
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(previous));
    panePointerGesture("pointerDown", 1100, 700);
    panePointerGesture("pointerMove", endX, endY);
    expect(selectedIds()).toEqual([]);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.SINGLE);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    panePointerGesture("pointerUp", endX, endY);
    fireEvent.click(document.querySelector(".react-flow__pane")!);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.SINGLE);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("enters multi-select only when an ongoing box first selects a node", async () => {
    await renderInteractiveFlow();
    panePointerGesture("pointerDown", -10, -10);
    panePointerGesture("pointerMove", -5, -5);
    expect(selectedIds()).toEqual([]);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    panePointerGesture("pointerMove", 300, 300);
    expect(selectedIds()).toEqual(["image-card"]);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.MULTIPLE);
    expect(screen.getByRole("checkbox", { name: "选择卡片：参考图" })).toBeChecked();
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("媒体卡片操作")).not.toBeInTheDocument();
    expect(focusProbe.center).not.toHaveBeenCalled();
    panePointerGesture("pointerUp", 300, 300);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.MULTIPLE);
  });

  it("leaves multi-select when an ongoing box shrinks back to empty", async () => {
    await renderInteractiveFlow();
    panePointerGesture("pointerDown", -10, -10);
    panePointerGesture("pointerMove", 300, 300);
    expect(selectedIds()).toEqual(["image-card"]);
    panePointerGesture("pointerMove", -5, -5);
    expect(selectedIds()).toEqual([]);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.SINGLE);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    panePointerGesture("pointerUp", -5, -5);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.SINGLE);
  });

  it("selects and replaces nodes on ordinary clicks", async () => {
    await renderInteractiveFlow();
    fireEvent.click(nodeElement("image-card"));
    expect(selectedIds()).toEqual(["image-card"]);
    fireEvent.click(nodeElement("middle-card"));
    expect(selectedIds()).toEqual(["middle-card"]);
    await waitFor(() => expect(nodeElement("middle-card")).toHaveClass("selected"));
    expect(nodeElement("image-card")).not.toHaveClass("selected");
  });

  it.each([
    { platform: "Mac", key: "Meta", code: "MetaLeft", modifier: { metaKey: true } },
    { platform: "Windows", key: "Control", code: "ControlLeft", modifier: { ctrlKey: true } },
  ])("adds and toggles selection with $key on $platform", async ({ platform, key, code, modifier }) => {
    vi.spyOn(window.navigator, "userAgent", "get").mockReturnValue(platform);
    await renderInteractiveFlow();
    fireEvent.click(nodeElement("image-card"));
    const centersBeforeModifiers = focusProbe.center.mock.calls.length;
    fireEvent.keyDown(window, { key, code, ...modifier });
    fireEvent.click(nodeElement("middle-card"), modifier);
    expect(selectedIds()).toEqual(["image-card", "middle-card"]);
    await waitFor(() => expect(nodeElement("middle-card")).toHaveClass("selected"));
    fireEvent.click(nodeElement("image-card"), modifier);
    expect(selectedIds()).toEqual(["middle-card"]);
    expect(screen.getByRole("checkbox", { name: "选择卡片：脚本" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "选择卡片：参考图" })).not.toBeChecked();
    expect(screen.getByRole("group", { name: "批量操作" })).toBeVisible();
    expect(focusProbe.center).toHaveBeenCalledTimes(centersBeforeModifiers);
    fireEvent.keyUp(window, { key, code });
  });

  it("collapses a multi-selection when clicking one of its selected nodes", async () => {
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "middle-card"]));
    await waitFor(() => expect(nodeElement("middle-card")).toHaveClass("selected"));
    fireEvent.click(nodeElement("image-card"));
    expect(selectedIds()).toEqual(["image-card"]);
  });

  it("box-selects fully and partially overlapped nodes and replaces the selection with an ordinary click", async () => {
    await renderInteractiveFlow();
    fireEvent.keyDown(window, { key: "Shift", code: "ShiftLeft", shiftKey: true });
    boxSelectNodes();
    fireEvent.keyUp(window, { key: "Shift", code: "ShiftLeft" });
    expect(selectedIds()).toEqual(["image-card", "middle-card", "text-card"]);
    fireEvent.click(nodeElement("text-card"));
    expect(selectedIds()).toEqual(["text-card"]);
  });

  it("syncs programmatic selection and clears it on a pane click", async () => {
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["image-card"]));
    await waitFor(() => expect(nodeElement("image-card")).toHaveClass("selected"));
    const pane = document.querySelector(".react-flow__pane");
    if (!pane) throw new Error("Missing pane");
    for (const type of ["pointerDown", "pointerUp"] as const) {
      const event = createEvent[type](pane, { bubbles: true });
      Object.defineProperties(event, { button: { value: 0 }, isPrimary: { value: true },
        pointerId: { value: 1 }, clientX: { value: 0 }, clientY: { value: 0 } });
      fireEvent(pane, event);
    }
    expect(selectedIds()).toEqual([]);
    await waitFor(() => expect(nodeElement("image-card")).not.toHaveClass("selected"));
  });
});


describe("canvas interaction tools", () => {
  it("does not center or open a node editor while the hand tool or Space is active", async () => {
    await renderInteractiveFlow();
    fireEvent.click(screen.getByRole("radio", { name: "手形工具" }));
    fireEvent.click(nodeElement("image-card"));
    expect(selectedIds()).toEqual([]);
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    expect(focusProbe.center).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("radio", { name: "选择工具" }));
    fireEvent.keyDown(window, { key: " ", code: "Space" });
    fireEvent.click(nodeElement("image-card"));
    expect(selectedIds()).toEqual([]);
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    expect(focusProbe.center).not.toHaveBeenCalled();
    fireEvent.keyUp(window, { key: " ", code: "Space" });
  });

  it("defaults to selection and temporarily pans with Space, releasing on blur", async () => {
    await renderFlow();
    expect(flowProps.panOnDrag).toBe(false);
    expect(flowProps.selectionOnDrag).toBe(true);
    expect(screen.getByRole("radio", { name: "选择工具" })).toBeChecked();
    fireEvent.keyDown(window, { key: " ", code: "Space" });
    expect(flowProps.panOnDrag).toBe(true);
    expect(flowProps.selectionOnDrag).toBe(false);
    expect(flowProps.nodes?.every((node) => node.draggable === false)).toBe(true);
    expect(screen.getByRole("radio", { name: "手形工具" })).toBeChecked();
    fireEvent.keyDown(window, { key: " ", code: "Space", repeat: true });
    fireEvent.keyUp(window, { key: " ", code: "Space" });
    expect(flowProps.panOnDrag).toBe(false);
    expect(screen.getByRole("radio", { name: "选择工具" })).toBeChecked();
    fireEvent.keyDown(window, { key: " ", code: "Space" });
    fireEvent.blur(window);
    expect(flowProps.panOnDrag).toBe(false);
  });

  it("switches tools directly and with V, preserving hand mode after Space", async () => {
    await renderFlow();
    const tools = screen.getByRole("radiogroup", { name: "画布工具模式" });
    fireEvent.click(within(tools).getByRole("radio", { name: "手形工具" }));
    expect(flowProps.panOnDrag).toBe(true);
    expect(flowProps.elementsSelectable).toBe(false);
    fireEvent.keyDown(window, { key: " ", code: "Space" });
    fireEvent.keyUp(window, { key: " ", code: "Space" });
    expect(flowProps.panOnDrag).toBe(true);
    expect(within(tools).getByRole("radio", { name: "手形工具" })).toBeChecked();
    fireEvent.keyDown(window, { key: "v", code: "KeyV" });
    expect(flowProps.panOnDrag).toBe(false);
    expect(within(tools).getByRole("radio", { name: "选择工具" })).toBeChecked();
    fireEvent.click(within(tools).getByRole("radio", { name: "手形工具" }));
    fireEvent.click(within(tools).getByRole("radio", { name: "选择工具" }));
    expect(flowProps.panOnDrag).toBe(false);
  });

  it("does not intercept Space or V while editing text", async () => {
    await renderFlow();
    const input = document.createElement("textarea");
    document.body.append(input);
    fireEvent.keyDown(input, { key: " ", code: "Space" });
    expect(flowProps.panOnDrag).toBe(false);
    fireEvent.click(screen.getByRole("radio", { name: "手形工具" }));
    fireEvent.keyDown(input, { key: "v", code: "KeyV" });
    expect(flowProps.panOnDrag).toBe(true);
    input.remove();
  });

  it("pans from a card with Space without moving the card itself", async () => {
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["image-card"]));
    const position = flowProps.nodes?.find((node) => node.id === "image-card")?.position;
    const viewport = document.querySelector(".react-flow__viewport");
    const before = viewport?.getAttribute("style");
    fireEvent.keyDown(window, { key: " ", code: "Space" });
    const node = nodeElement("image-card");
    fireEvent.pointerDown(node);
    const origin = 100;
    const panDistance = 40;
    for (const type of ["mouseDown", "mouseMove", "mouseUp"] as const) {
      const target = type === "mouseDown" ? node : window;
      const event = createEvent[type](target, { button: 0,
        clientX: origin + (type === "mouseDown" ? 0 : panDistance), clientY: origin });
      Object.defineProperty(event, "view", { value: window });
      fireEvent(target, event);
    }
    expect(viewport?.getAttribute("style")).not.toBe(before);
    expect(flowProps.nodes?.find((node) => node.id === "image-card")?.position).toEqual(position);
    expect(selectedIds()).toEqual([]);
    expect(focusProbe.center).not.toHaveBeenCalled();
    fireEvent.keyUp(window, { key: " ", code: "Space" });
    await new Promise((resolve) => setTimeout(resolve, 0));
  });

  it.each([0, 1, CANVAS_POINTER_THRESHOLD])("selects on the first click after %i pixels of pointer drift", async (displacement) => {
    await renderInteractiveFlow();
    const node = nodeElement("image-card");
    const origin = 100;
    for (const type of ["mouseDown", "mouseMove", "mouseUp", "click"] as const) {
      const target = type === "mouseMove" || type === "mouseUp" ? window : node;
      const event = createEvent[type](target, {
        button: 0, buttons: type === "mouseUp" || type === "click" ? 0 : 1,
        clientX: origin + (type === "mouseDown" ? 0 : displacement), clientY: origin,
      });
      Object.defineProperty(event, "view", { value: window });
      fireEvent(target, event);
      if (type !== "click") {
        expect(selectedIds()).toEqual([]);
        expect(screen.queryByLabelText("媒体卡片操作")).not.toBeInTheDocument();
        expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
      }
    }
    expect(selectedIds()).toEqual(["image-card"]);
    expect(await screen.findByLabelText("媒体卡片操作")).toBeVisible();
    expect(await screen.findByLabelText("所选卡片编辑区")).toBeVisible();
  });
});


it.each(["connection", "apply"] as const)("reopens the original %s recovery from an archived workspace header without creating an edge", async (kind) => {
  const targetId = "image-card";
  const commandId = "original-command";
  const connectionId = "original-connection";
  const confirmedDraft: MediaDraft = { projectId: "project-1", canvasItemId: targetId, version: 1, prompt: "", parameters: {},
    capabilityId: null, videoInputMode: null, durationSeconds: null, styleId: null, mediaInputs: [], mentions: [], displayMode: "DRAFT", createdAt: now, updatedAt: now };
  useCharacterNodeRecovery.getState().retain(`project-1:${targetId}`, kind === "connection"
    ? { kind, request: { connectionId, sourceCanvasItemId: "role", targetCanvasItemId: targetId, expectedSourceItemVersion: 1, expectedSourceSelectionVersion: 1, expectedTargetItemVersion: 3 } }
    : { kind, request: { commandId, expectedBindingVersion: 0, expectedDraftVersion: 0, clearMode: "REMOVE_OWNED", previewHash: "original-hash" } });
  let writes = 0, lookups = 0;
  server.use(http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({ ...snapshot(), project: { ...snapshot().project, status: "ARCHIVED" } })),
    http.post("/api/v1/projects/:projectId/canvas/character-connections", () => { writes++; return HttpResponse.json({}, { status: 409 }); }),
    http.post("/api/v1/projects/:projectId/canvas-items/:targetId/character-node-bindings/apply", () => { writes++; return HttpResponse.json({}, { status: 409 }); }),
    http.get("/api/v1/projects/:projectId/canvas/character-connections/:connectionId/receipt", ({ params }) => {
      lookups++; expect(params.connectionId).toBe(connectionId);
      return HttpResponse.json({ id: connectionId, sourceCanvasItemId: "role", targetCanvasItemId: targetId, version: 0 });
    }),
    http.get("/api/v1/projects/:projectId/canvas-items/:targetId/character-node-bindings/receipts/:commandId", ({ params }) => {
      lookups++; expect(params.targetId).toBe(targetId); expect(params.commandId).toBe(commandId);
      return HttpResponse.json({ draft: confirmedDraft, binding: { canvasItemId: targetId, version: 1 } });
    }));
  await renderFlow();
  await userEvent.click(await screen.findByRole("button", { name: t("characterNode.reconcileAction") }));
  const dialog = await screen.findByRole("dialog", { name: t("characterNode.connectPreview") });
  await userEvent.click(within(dialog).getByRole("button", { name: t("characterNode.reconcileAction") }));
  await waitFor(() => expect(useCharacterNodeRecovery.getState().pending[`project-1:${targetId}`]).toBeUndefined());
  expect(lookups).toBe(1); expect(writes).toBe(0);
});

it("reopens a retained creation from the archived workspace header without submitting a replacement", async () => {
  const pending = { id: "original-created-character", request: { selection: { characterId: "hero", characterVersion: 1, lookId: null,
    viewVersionIds: [], useAppearance: true, useVoice: false }, x: 0, y: 0, width: 320, height: 300, zIndex: 0, groupId: null, locked: false } };
  useCharacterNodeRecovery.getState().retainPlacement("project-1", pending);
  let writes = 0;
  server.use(http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({ ...snapshot(), project: { ...snapshot().project, status: "ARCHIVED" } })),
    http.put("/api/v1/projects/:projectId/canvas/character-nodes/:itemId", () => { writes++; return HttpResponse.json({}, { status: 409 }); }));
  await renderFlow();
  const openRecovery = screen.getByRole("button", { name: t("characterNode.place") });
  expect(openRecovery).toBeEnabled();
  await userEvent.click(openRecovery);
  await screen.findByText(pending.id);
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await screen.findByText(t("characterNode.receiptMissing"));
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.pendingExit") }));
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.place") }));
  expect(await screen.findByText(pending.id)).toBeVisible();
  expect(useCharacterNodeRecovery.getState().placements["project-1"]).toEqual(pending);
  expect(writes).toBe(0);
});

it("keeps an empty snapshot final when its first character arrives before delayed flow initialization", async () => {
  renderRealFlow = true;
  focusProbe.deferInit = true;
  server.use(http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({ ...snapshot(), canvas: { items: [] }, connections: [] })),
    http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [] })));
  const client = await renderFlow(0);
  await waitFor(() => expect(focusProbe.pendingInit).not.toBeNull());
  await screen.findByRole("button", { name: "创建第一个节点" });
  const first: CanvasItem = { id: "first-role", subjectType: "CHARACTER", subjectId: "hero", title: "Hero", x: 400, y: 300,
    width: 320, height: 300, zIndex: 0, locked: false, version: 0, artifact: null, selectedVersion: null, selectedVersionId: null,
    characterNode: { canvasItemId: "first-role", version: 0, newerVersionAvailable: false, connections: [],
      selection: { characterId: "hero", characterVersion: 1, lookId: null, viewVersionIds: [], useAppearance: true, useVoice: false },
      snapshot: { characterId: "hero", characterVersion: 1, name: "Hero", description: "Pilot", look: null, voice: null, useAppearance: true, useVoice: false } } };
  act(() => client.setQueryData(["canvas", "project-1"], { items: [first] }));
  await waitFor(() => expect(flowProps.nodes).toHaveLength(1));
  act(() => focusProbe.pendingInit?.());
  await waitFor(() => expect(focusProbe.instance).not.toBeNull());
  expect(focusProbe.fit).not.toHaveBeenCalled();
  expect(flowProps.fitView).not.toBe(true);
});
