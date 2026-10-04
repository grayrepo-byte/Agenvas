import { QueryClientProvider } from "@tanstack/react-query";
import { act,cleanup,createEvent,fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import type { NodeSelectionChange,ReactFlowInstance,ReactFlowProps } from "@xyflow/react";
import { http,HttpResponse } from "msw";
import { useLayoutEffect } from "react";
import { MemoryRouter,Route,Routes } from "react-router";
import { afterEach,beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasCommand,CanvasItem,ProjectSnapshot } from "../../shared/api/client";
import { clickControl } from "../../test/controls";
import { server } from "../../test/server";
import { CANVAS_POINTER_THRESHOLD } from "./canvasInteraction";
import { CANVAS_SELECTION_MODE,useCanvasStore } from "./canvasStore";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

type SelectionChange = NodeSelectionChange;
type FlowProps = ReactFlowProps;
const TEST_VIEWPORT = { width: 1400, height: 900 };
const SELECTION_BOX = { startX: -10, startY: -10, endX: 900, endY: 700 };
let renderRealFlow = false;

let flowProps: FlowProps = {};
const focusProbe = vi.hoisted(() => ({
  center: vi.fn<ReactFlowInstance["setCenter"]>().mockResolvedValue(true),
  viewport: vi.fn<ReactFlowInstance["setViewport"]>().mockResolvedValue(true), zoom: 1,
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
    ReactFlow: (props: FlowProps) => {
      flowProps = props;
      // Keep a fixed viewport; node dimensions come from the actual workspace projection.
      // jsdom has no viewport bounds; disable edge auto-pan so gesture coordinates remain deterministic.
      return renderRealFlow ? <real.ReactFlow {...props} fitView={false} autoPanOnNodeDrag={false}
        autoPanOnSelection={false} {...TEST_VIEWPORT}
        onInit={(instance) => props.onInit?.({ ...instance, setCenter: focusProbe.center, setViewport: focusProbe.viewport,
          getZoom: () => focusProbe.zoom })}>
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
    width: 260, height: 150, zIndex: 0, groupId: null, locked: false, selectedVersionId: null, selectedVersion: null, version: 3, agent: null,
    artifact: { id: "image-id", projectId: "project-1", kind: "IMAGE", title: "参考图",
      resourceDefaultVersionId: imageVersionId, version: 3, createdAt: now, updatedAt: now,
      resourceDefaultVersion: { id: imageVersionId, versionNo: 1, schemaVersion: 1,
        content: { sourceType: "UPLOAD", assetId: "asset-id" }, inputReferences: [],
        createdByKind: "USER", runId: null, createdAt: now } } },
  { id: "agent-card", subjectType: "AGENT", subjectId: "agent-id", x: 400, y: 0,
    title: "Agent",
    width: 460, height: 600, zIndex: 1, groupId: null, locked: false, selectedVersionId: null, selectedVersion: null, version: 4, artifact: null,
    agent: { id: "agent-id", projectId: "project-1", profileKey: "creator", profileVersion: 1,
      name: "Creator", instruction: "Create", outputGroupId: "group-1", version: 4,
      createdAt: now, updatedAt: now,
      bindings: [{ id: "binding-id", artifactId: "image-id",
        selectedVersionId: imageVersionId }] } },
  { id: "text-card", subjectType: "ARTIFACT", subjectId: "text-id", x: 800, y: 0,
    title: "正文",
    width: 260, height: 150, zIndex: 2, groupId: null, locked: false, selectedVersionId: null, selectedVersion: null, version: 5, agent: null,
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
    canvas: { items }, connections: [], agents: [], activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 0 };
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

beforeEach(() => {
  flowProps = {};
  renderRealFlow = false;
  focusProbe.center.mockClear();
  focusProbe.viewport.mockClear();
  focusProbe.zoom = 1;
  useCanvasStore.setState({ selectedIds: [], selectionMode: CANVAS_SELECTION_MODE.SINGLE,
    drafts: {}, mediaDraftRecoveries: {}, saveState: "saved" });
  server.use(
    http.get("/api/v1/settings/media-connections", () => HttpResponse.json({ connections: [], defaults: [] })),
    http.get("/api/v1/projects/:projectId/artifacts/:artifactId/run", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/agents/:agentId/conversations", () => HttpResponse.json({ items: [], nextCursor: null })),
    http.get("/api/v1/auth/me", () => HttpResponse.json({
      id: "owner-id", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({
      headerName: "X-XSRF-TOKEN", token: "test-token" })),
    http.get("/api/v1/projects/:projectId", () => HttpResponse.json(snapshot().project)),
    http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json(snapshot())),
    http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items })),
    http.get("/api/v1/projects/:projectId/artifacts", () => HttpResponse.json({
      items: items.flatMap((item) => item.artifact ? [item.artifact] : []) })),
    http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/export-proposals", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/usage", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/assets/:assetId", ({ params }) => HttpResponse.json({
      id: params.assetId, width: 1024, height: 1024 })),
    http.get("/api/v1/projects/:projectId/canvas-items/:canvasItemId/media-draft", ({ params }) =>
      HttpResponse.json({ projectId: "project-1", canvasItemId: params.canvasItemId, prompt: "",
        parameters: {}, durationSeconds: null, capabilityId: null, videoInputMode: null,
        mediaInputs: [], mentions: [], displayMode: "RESULT", version: 0,
        createdAt: now, updatedAt: now })));
});

async function renderFlow() {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(<QueryClientProvider client={client}>
    <MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter>
  </QueryClientProvider>);
  await waitFor(() => expect(flowProps.nodes).toHaveLength(items.length));
}

const selectedIds = () => useCanvasStore.getState().selectedIds;
const selectChange = (id: string, selected: boolean): SelectionChange =>
  ({ id, type: "select", selected });

describe("canvas selection clearing", () => {
  it("selects all Agent outputs and offers relevant bulk actions with a working close button", async () => {
    const outputs = items.map((item) => item.artifact ? { ...item, groupId: "group-1" } : item);
    const commands: CanvasCommand[][] = [];
    server.use(
      http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({ ...snapshot(), canvas: { items: outputs } })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: outputs })),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        commands.push((await request.json() as { commands: CanvasCommand[] }).commands);
        return HttpResponse.json({ items: outputs });
      }),
    );
    await renderInteractiveFlow();
    fireEvent.click(await screen.findByRole("button", { name: "查看产物 · 2" }));
    const toolbar = await screen.findByRole("group", { name: "批量操作" });
    expect(selectedIds()).toEqual(["image-card", "text-card"]);
    expect(within(toolbar).getByText("2 张卡片已选中")).toBeVisible();
    expect(within(toolbar).queryByRole("button", { name: "绑定到 Agent" })).not.toBeInTheDocument();
    expect(within(toolbar).queryByRole("button", { name: "清空 Agent 输入" })).not.toBeInTheDocument();
    fireEvent.click(within(toolbar).getByRole("button", { name: "左对齐" }));
    await waitFor(() => expect(commands).toHaveLength(1));
    expect(commands[0]).toEqual([
      expect.objectContaining({ type: "UPDATE_LAYOUT", itemId: "image-card", x: 0 }),
      expect.objectContaining({ type: "UPDATE_LAYOUT", itemId: "text-card", x: 0 }),
    ]);
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "text-card", "agent-card"]));
    expect(within(toolbar).getByRole("button", { name: "绑定到 Agent" })).toBeEnabled();
    expect(within(toolbar).getByRole("button", { name: "清空 Agent 输入" })).toBeEnabled();
    fireEvent.click(within(toolbar).getByRole("button", { name: "关闭编辑区" }));
    expect(selectedIds()).toEqual([]);
    expect(screen.queryByRole("group", { name: "批量操作" })).not.toBeInTheDocument();
  });

  it("clears the card selection when React Flow reports a select change", async () => {
    await renderFlow();
    useCanvasStore.setState({ selectedIds: ["image-card", "agent-card"] });
    flowProps.onNodesChange?.([selectChange("image-card", false)]);
    expect(selectedIds()).toEqual(["agent-card"]);
  });

  it("drops every card when the pane reports an empty selection", async () => {
    await renderFlow();
    useCanvasStore.setState({ selectedIds: ["image-card", "agent-card"] });
    flowProps.onNodesChange?.([
      selectChange("image-card", false), selectChange("agent-card", false)]);
    expect(selectedIds()).toEqual([]);
  });

  it("clears cards and relation lines when the user moves the viewport", async () => {
    await renderFlow();
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
    useCanvasStore.setState({ selectedIds: ["agent-card"] });
    flowProps.onNodesChange?.([selectChange("agent-card", false), selectChange("image-card", true)]);
    expect(selectedIds()).toEqual(["image-card"]);
    flowProps.onNodesChange?.([selectChange("agent-card", true)]);
    expect(selectedIds()).toEqual(["image-card", "agent-card"]);
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
    const video = screen.getByLabelText("参考图 的视频") as HTMLVideoElement;
    expect(play).toHaveBeenCalledOnce();
    expect(video.muted).toBe(false);
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

describe("media selection focus", () => {
  async function openMedia() {
    const originalBounds = HTMLElement.prototype.getBoundingClientRect;
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      return this.classList.contains("workspace-canvas")
        ? new DOMRect(0, 0, TEST_VIEWPORT.width, TEST_VIEWPORT.height)
        : originalBounds.call(this);
    });
    await renderInteractiveFlow();
    fireEvent.click(nodeElement("image-card"));
    await waitFor(() => expect(focusProbe.center).toHaveBeenCalledTimes(1));
  }

  it("animates the card and editor focus instead of jumping the viewport", async () => {
    await openMedia();
    const options = focusProbe.center.mock.calls[0]?.[2];
    expect(options?.duration).toBeGreaterThan(0);
    expect(options?.interpolate).toBe("linear");
    expect(options?.ease?.(0)).toBe(0);
    expect(options?.ease?.(1)).toBe(1);
    expect(selectedIds()).toEqual(["image-card"]);
    fireEvent.click(nodeElement("image-card"));
    expect(focusProbe.center).toHaveBeenCalledTimes(1);
  });

  it("preserves a zoomed-out view when the card and editor already fit", async () => {
    focusProbe.zoom = 0.6;
    await openMedia();
    expect(focusProbe.center.mock.calls[0]?.[2]?.zoom).toBe(0.6);
  });

  it("honors reduced motion when positioning the card and editor", async () => {
    vi.stubGlobal("matchMedia", vi.fn().mockReturnValue({ matches: true }));
    await openMedia();
    expect(focusProbe.center.mock.calls[0]?.[2]?.duration).toBe(0);
  });

  it.each(["close", "pan"])("interrupts the current animation when the user chooses to %s", async (action) => {
    focusProbe.center.mockImplementationOnce(() => new Promise(() => undefined));
    await openMedia();
    if (action === "close") fireEvent.click(screen.getByRole("button", { name: "关闭编辑区" }));
    else act(() => flowProps.onMoveStart?.(new MouseEvent("mousedown"), { x: 0, y: 0, zoom: 1 }));
    await waitFor(() => expect(focusProbe.viewport).toHaveBeenCalledTimes(1));
    expect(selectedIds()).toEqual([]);
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

function observeLayoutSaves(fail = false) {
  const batches: CanvasCommand[][] = [];
  server.use(http.post("/api/v1/projects/project-1/canvas/commands", async ({ request }) => {
    const body = await request.json() as { commands: CanvasCommand[] };
    batches.push(body.commands);
    if (fail) return HttpResponse.json({ code: "VERSION_CONFLICT", title: "布局冲突" }, { status: 409 });
    return HttpResponse.json({ items: items.map((item) => {
      const command = body.commands.find((candidate) => candidate.itemId === item.id);
      return command?.type === "UPDATE_LAYOUT"
        ? { ...item, x: command.x, y: command.y, width: command.width, height: command.height,
          version: item.version + 1 } : item;
    }) });
  }));
  return batches;
}

describe("node click and drag gestures", () => {
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
    expect(saves[0]).toEqual([expect.objectContaining({ type: "UPDATE_LAYOUT", itemId: "image-card",
      expectedVersion: 3, x: 36, y: 0 })]);
    await waitFor(() => expect(useCanvasStore.getState().saveState).toBe("saved"));
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
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "agent-card"]));
    expect(await screen.findByLabelText("批量操作")).toBeVisible();
    const node = nodeElement("image-card");
    nodeMouseGesture("mouseDown", node, 0);
    nodeMouseGesture("mouseMove", node, CANVAS_POINTER_THRESHOLD + 1);
    expect(node.querySelector(".artifact-canvas-card")).toHaveClass("is-selected");
    expect(nodeElement("agent-card").querySelector(".agent-chat-card"))
      .toHaveClass("agent-chat-card--selected");
    expect(screen.queryByLabelText("批量操作")).not.toBeInTheDocument();
    nodeMouseGesture("mouseMove", node, 40);
    nodeMouseGesture("mouseUp", node, 40);
    expect(selectedIds()).toEqual([]);
    await waitFor(() => expect(saves).toHaveLength(1));
    expect(saves[0]).toEqual([
      expect.objectContaining({ itemId: "image-card", x: 36, y: 0 }),
      expect.objectContaining({ itemId: "agent-card", x: 436, y: 0 }),
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
  it("keeps a single box-selected node in multi-select mode without opening its editor", async () => {
    await renderInteractiveFlow();
    boxSelectNodes({ startX: -10, startY: -10, endX: 300, endY: 300 });
    expect(selectedIds()).toEqual(["image-card"]);
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("媒体卡片操作")).not.toBeInTheDocument();
    const toolbar = screen.getByRole("group", { name: "批量操作" });
    expect(toolbar).toBeVisible();
    expect(within(toolbar).getByText("1 张卡片已选中")).toBeVisible();
    expect(within(toolbar).getByRole("button", { name: "左对齐" })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: "选择卡片：参考图" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "选择卡片：Agent" })).not.toBeChecked();
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
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "agent-card"]));
    const imageCheckbox = screen.getByRole("checkbox", { name: "选择卡片：参考图" });
    const agentCheckbox = screen.getByRole("checkbox", { name: "选择卡片：Agent" });
    const textCheckbox = screen.getByRole("checkbox", { name: "选择卡片：正文" });
    expect(imageCheckbox).toBeChecked();
    expect(agentCheckbox).toBeChecked();
    expect(textCheckbox).not.toBeChecked();
    nodeMouseGesture("mouseDown", textCheckbox, 0);
    nodeMouseGesture("mouseMove", textCheckbox, 40);
    nodeMouseGesture("mouseUp", textCheckbox, 40);
    fireEvent.click(textCheckbox);
    expect(selectedIds()).toEqual(["image-card", "agent-card", "text-card"]);
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
  });

  it.each([
    { name: "a slight blank-pane movement", endX: 1101, endY: 701, previous: [] },
    { name: "a large empty box", endX: 1200, endY: 800, previous: [] },
    { name: "an empty box after a single selection", endX: 1200, endY: 800, previous: ["image-card"] },
    { name: "an empty box after a group selection", endX: 1200, endY: 800, previous: ["image-card", "agent-card"] },
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
    fireEvent.click(nodeElement("agent-card"));
    expect(selectedIds()).toEqual(["agent-card"]);
    await waitFor(() => expect(nodeElement("agent-card")).toHaveClass("selected"));
    expect(nodeElement("image-card")).not.toHaveClass("selected");
  });

  it.each([
    { platform: "Mac", key: "Meta", code: "MetaLeft", modifier: { metaKey: true } },
    { platform: "Windows", key: "Control", code: "ControlLeft", modifier: { ctrlKey: true } },
  ])("adds and toggles selection with $key on $platform", async ({ platform, key, code, modifier }) => {
    vi.spyOn(window.navigator, "userAgent", "get").mockReturnValue(platform);
    await renderInteractiveFlow();
    fireEvent.click(nodeElement("image-card"));
    fireEvent.keyDown(window, { key, code, ...modifier });
    fireEvent.click(nodeElement("agent-card"), modifier);
    expect(selectedIds()).toEqual(["image-card", "agent-card"]);
    await waitFor(() => expect(nodeElement("agent-card")).toHaveClass("selected"));
    fireEvent.click(nodeElement("image-card"), modifier);
    expect(selectedIds()).toEqual(["agent-card"]);
    expect(screen.getByRole("checkbox", { name: "选择卡片：Agent" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "选择卡片：参考图" })).not.toBeChecked();
    expect(screen.getByRole("group", { name: "批量操作" })).toBeVisible();
    fireEvent.keyUp(window, { key, code });
  });

  it("collapses a multi-selection when clicking one of its selected nodes", async () => {
    await renderInteractiveFlow();
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "agent-card"]));
    await waitFor(() => expect(nodeElement("agent-card")).toHaveClass("selected"));
    fireEvent.click(nodeElement("image-card"));
    expect(selectedIds()).toEqual(["image-card"]);
  });

  it("box-selects nodes and replaces the box selection with an ordinary click", async () => {
    await renderInteractiveFlow();
    fireEvent.keyDown(window, { key: "Shift", code: "ShiftLeft", shiftKey: true });
    boxSelectNodes();
    fireEvent.keyUp(window, { key: "Shift", code: "ShiftLeft" });
    expect(selectedIds()).toEqual(["image-card", "agent-card"]);
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
  it("defaults to selection and temporarily pans with Space, releasing on blur", async () => {
    await renderFlow();
    expect(flowProps.panOnDrag).toBe(false);
    expect(flowProps.selectionOnDrag).toBe(true);
    fireEvent.keyDown(window, { key: " ", code: "Space" });
    expect(flowProps.panOnDrag).toBe(true);
    expect(flowProps.selectionOnDrag).toBe(false);
    expect(flowProps.nodes?.every((node) => node.draggable === false)).toBe(true);
    fireEvent.keyDown(window, { key: " ", code: "Space", repeat: true });
    fireEvent.keyUp(window, { key: " ", code: "Space" });
    expect(flowProps.panOnDrag).toBe(false);
    fireEvent.keyDown(window, { key: " ", code: "Space" });
    fireEvent.blur(window);
    expect(flowProps.panOnDrag).toBe(false);
  });

  it("switches tools through the menu and V, preserving hand mode after Space", async () => {
    await renderFlow();
    await clickControl(screen.getByRole("button", { name: "画布工具" }));
    fireEvent.click(screen.getByRole("menuitemradio", { name: /手形工具/ }));
    expect(flowProps.panOnDrag).toBe(true);
    expect(flowProps.elementsSelectable).toBe(false);
    fireEvent.keyDown(window, { key: " ", code: "Space" });
    fireEvent.keyUp(window, { key: " ", code: "Space" });
    expect(flowProps.panOnDrag).toBe(true);
    fireEvent.keyDown(window, { key: "v", code: "KeyV" });
    expect(flowProps.panOnDrag).toBe(false);
    await clickControl(screen.getByRole("button", { name: "画布工具" }));
    const menu = screen.getByRole("menu", { name: "画布工具模式" });
    fireEvent.keyDown(menu, { key: "Escape" });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("does not intercept Space or V while editing text", async () => {
    await renderFlow();
    const input = document.createElement("textarea");
    document.body.append(input);
    fireEvent.keyDown(input, { key: " ", code: "Space" });
    expect(flowProps.panOnDrag).toBe(false);
    await clickControl(screen.getByRole("button", { name: "画布工具" }));
    fireEvent.click(screen.getByRole("menuitemradio", { name: /手形工具/ }));
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
