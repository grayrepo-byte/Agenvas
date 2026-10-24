import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect } from "react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Artifact, CanvasCommand, CanvasItem, CreateArtifactRequest, ProjectSnapshot } from "../../shared/api/client";
import { server } from "../../test/server";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

type FlowProps = {
  nodes?: unknown[];
  zoomOnDoubleClick?: unknown;
  onConnectStart?: (event: unknown, params: { nodeId: string | null; handleId: string | null }) => void;
  onConnectEnd?: (event: unknown, state: { toHandle?: unknown }) => Promise<void> | void;
  onInit?: (instance: { screenToFlowPosition: (point: { x: number; y: number }) => { x: number; y: number } }) => void;
};

let flowProps: FlowProps = {};

// React Flow 的落点判定依赖真实布局，这里只驱动页面的手势回调，指针命中由 stub 指定。
vi.mock("@xyflow/react", async (importOriginal) => {
  const real = await importOriginal<typeof import("@xyflow/react")>();
  return {
    ...real,
    ReactFlow: (props: FlowProps) => {
      flowProps = props;
      useEffect(() => {
        props.onInit?.({ screenToFlowPosition: ({ x, y }) => ({ x: (x - 140) / 2, y: (y - 80) / 2 }) });
      }, [props.onInit]);
      return <div className="react-flow"><div className="react-flow__pane" data-testid="flow" /></div>;
    },
  };
});

const now = "2026-09-27T00:00:00Z";
const imageVersionId = "11111111-1111-4111-8111-111111111111";

function card(id: string, kind: Artifact["kind"], title: string, versionId: string,
  content: NonNullable<Artifact["resourceDefaultVersion"]>["content"]): CanvasItem {
  const selectedVersion = { id: versionId, versionNo: 1, schemaVersion: 1 as const, content,
    inputReferences: [], createdByKind: "USER" as const, runId: null, createdAt: now };
  return { id: `${id}-card`, subjectType: "ARTIFACT", subjectId: id, title, x: 0, y: 0,
    width: 260, height: 150, zIndex: 0, groupId: null, locked: false,
    selectedVersionId: kind === "TEXT" ? null : versionId,
    selectedVersion: kind === "TEXT" ? null : selectedVersion, version: 3,
    artifact: { id, projectId: "project-1", kind, title, resourceDefaultVersionId: versionId,
      version: 3, createdAt: now, updatedAt: now,
      resourceDefaultVersion: selectedVersion } };
}

const items: CanvasItem[] = [
  card("image-id", "IMAGE", "参考图", imageVersionId,
    { sourceType: "UPLOAD", assetId: "asset-id" }),
  { id: "empty-image-id-card", subjectType: "ARTIFACT", subjectId: "empty-image-id",
    title: "空白图片", x: 300, y: 200, width: 260, height: 150, zIndex: 0, groupId: null,
    locked: false, selectedVersionId: null, selectedVersion: null, version: 1,
    artifact: { id: "empty-image-id", projectId: "project-1", kind: "IMAGE", title: "空白图片",
      resourceDefaultVersionId: null, resourceDefaultVersion: null, version: 1,
      createdAt: now, updatedAt: now } },
  card("text-id", "TEXT", "文字", "22222222-2222-4222-8222-222222222222",
    { format: "PLAIN_TEXT", text: "正文" }),
  card("video-id", "VIDEO", "视频", "33333333-3333-4333-8333-333333333333",
    { assetId: "asset-id", prompt: "缓慢推近",
      workflowVersion: "mock-video-v1", parameters: {}, sourceTaskId: "task-id" }),

];

const agentPatches: Array<Record<string, unknown>> = [];
const canvasConnections: Array<Record<string, unknown>> = [];
const revisions: string[] = [];
let currentItems: CanvasItem[] = [];
const createdArtifacts: Artifact[] = [];
const placements: CanvasCommand[] = [];

function snapshot(): ProjectSnapshot {
  return { project: { id: "project-1", name: "落点项目", status: "ACTIVE",
    aspectRatio: "LANDSCAPE_16_9", version: 1, createdAt: now, updatedAt: now },
    canvas: { items: currentItems }, connections: [], director: null, activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 0 };
}

beforeEach(() => {
  agentPatches.length = 0;
  canvasConnections.length = 0;
  revisions.length = 0;
  currentItems = [...items];
  createdArtifacts.length = 0;
  placements.length = 0;
  flowProps = {};
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({
      id: "owner-id", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({
      headerName: "X-XSRF-TOKEN", token: "test-token" })),
    http.get("/api/v1/projects/:projectId", () => HttpResponse.json(snapshot().project)),
    http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json(snapshot())),
    http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: currentItems })),
    http.post("/api/v1/projects/:projectId/artifacts", async ({ request }) => {
      const body = await request.json() as CreateArtifactRequest;
      const artifact: Artifact = { id: `created-${createdArtifacts.length}`, projectId: "project-1",
        kind: body.kind, title: body.title, resourceDefaultVersion: null, resourceDefaultVersionId: null,
        version: 0, createdAt: now, updatedAt: now };
      createdArtifacts.push(artifact);
      return HttpResponse.json(artifact, { status: 201 });
    }),
    http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
      const { commands } = await request.json() as { commands: CanvasCommand[] };
      placements.push(...commands);
      for (const command of commands) {
        if (command.type !== "PLACE_ARTIFACT") throw new Error("Expected placement");
        const artifact = createdArtifacts.find((candidate) => candidate.id === command.artifactId);
        if (!artifact || !command.itemId) throw new Error("Missing created artifact or item identity");
        currentItems = [...currentItems, { id: command.itemId, subjectType: "ARTIFACT", subjectId: artifact.id,
          artifact, title: artifact.title, x: command.x, y: command.y, width: command.width, height: command.height,
          zIndex: command.zIndex, groupId: null, locked: false, selectedVersionId: null, selectedVersion: null, version: 0 }];
      }
      return HttpResponse.json({ items: currentItems });
    }),
    http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/export-proposals", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/usage", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/assets/:assetId", ({ params }) => HttpResponse.json({
      id: params.assetId, width: 1024, height: 1024 })),
    http.get("/api/v1/projects/:projectId/canvas-items/:canvasItemId/media-draft", ({ params }) =>
      HttpResponse.json({ projectId: "project-1", canvasItemId: params.canvasItemId, prompt: "",
        parameters: {}, durationSeconds: null, capabilityId: null, videoInputMode: "START_END",
        mediaInputs: [], mentions: [], displayMode: "RESULT", version: 0,
        createdAt: now, updatedAt: now })),
    http.post("/api/v1/projects/:projectId/canvas/connections", async ({ request }) => {
      const body = await request.json() as Record<string, unknown>;
      canvasConnections.push(body);
      return HttpResponse.json({ connection: {
        id: "connection-id", projectId: "project-1",
        sourceCanvasItemId: body.sourceCanvasItemId,
        targetCanvasItemId: body.targetCanvasItemId,
        sourceArtifactVersionId: body.sourceVersionId,
        relationType: body.relationType, version: 0, createdAt: now, updatedAt: now,
      }, draft: null }, { status: 201 });
    }),
    http.patch("/api/v1/projects/:projectId/agents/:agentId", async ({ request }) => {
      const body = await request.json() as Record<string, unknown>;
      agentPatches.push(body);
      return HttpResponse.json({});
    }),
    http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions",
      async ({ params }) => {
        revisions.push(String(params.artifactId));
        return HttpResponse.json({} as Artifact, { status: 201 });
      }));
});

/** jsdom 没有布局与 elementFromPoint，这里把它定义成「指针下就是这张卡片」。 */
function pointAt(nodeId: string | null) {
  const node = document.createElement("div");
  node.className = "react-flow__node";
  if (nodeId) node.dataset.id = nodeId;
  Object.defineProperty(document, "elementFromPoint", {
    configurable: true,
    writable: true,
    value: () => nodeId ? node : screen.getByTestId("flow"),
  });
}

async function renderFlow() {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(<QueryClientProvider client={client}>
    <MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter>
  </QueryClientProvider>);
  // 手势要等画布投影就绪：落点判定用的是服务端返回的卡片列表。
  await waitFor(() => expect(flowProps.nodes).toHaveLength(items.length));
  vi.spyOn(screen.getByLabelText("项目画布"), "getBoundingClientRect")
    .mockReturnValue(new DOMRect(100, 60, 1000, 700));
  return client;
}

async function releaseOnPane(sourceId = "image-id-card", x = 620, y = 400, touch = false) {
  pointAt(null);
  act(() => flowProps.onConnectStart?.(null, { nodeId: sourceId, handleId: "artifact-output" }));
  await act(async () => {
    await flowProps.onConnectEnd?.(touch ? { changedTouches: [{ clientX: x, clientY: y }] }
      : new MouseEvent("mouseup", { clientX: x, clientY: y }), { toHandle: null });
  });
}

/** 从源卡片出口拖到 nodeId 卡片上松手，模拟 React Flow 没有解析到任何连接点。 */
async function dropOn(nodeId: string | null) {
  pointAt(nodeId ?? "missing-card");
  await act(async () => {
    flowProps.onConnectStart?.(null, { nodeId: "image-id-card", handleId: "artifact-output" });
  });
  document.dispatchEvent(new MouseEvent("mousemove", { clientX: 10, clientY: 10 }));
  await act(async () => {
    await flowProps.onConnectEnd?.(null, { toHandle: null });
  });
}

describe("canvas connection drop target", () => {
  it.each(["图片", "视频", "音频"])("creates %s at the release point and connects the selected source version", async (label) => {
    await renderFlow();
    await releaseOnPane();
    expect(screen.getByRole("listbox", { name: "添加卡片" }).closest(".workspace-create-menu"))
      .toHaveStyle({ left: "520px", top: "340px" });
    expect(createdArtifacts).toHaveLength(0);
    expect(screen.getByRole("option", { name: "文字" })).toHaveAttribute("aria-disabled", "true");
    await userEvent.setup().click(screen.getByRole("option", { name: label }));
    await waitFor(() => expect(canvasConnections).toHaveLength(1));
    expect(placements).toEqual([expect.objectContaining({ x: 240, y: 160 })]);
    expect(canvasConnections[0]).toMatchObject({ sourceCanvasItemId: "image-id-card",
      targetCanvasItemId: currentItems.at(-1)?.id, sourceVersionId: imageVersionId,
      relationType: "MEDIA_INPUT", expectedTargetDraftVersion: 0 });
    expect(screen.queryByRole("listbox", { name: "添加卡片" })).not.toBeInTheDocument();
  });

  it("uses touch release coordinates and clamps only the menu at the canvas edge", async () => {
    await renderFlow();
    await releaseOnPane("image-id-card", 1080, 730, true);
    expect(screen.getByRole("listbox", { name: "添加卡片" }).closest(".workspace-create-menu"))
      .toHaveStyle({ left: "780px", top: "470px" });
    await userEvent.setup().click(screen.getByRole("option", { name: "图片" }));
    await waitFor(() => expect(canvasConnections).toHaveLength(1));
    expect(placements[0]).toMatchObject({ x: 470, y: 325 });
  });

  it.each(["escape", "outside"])("cancels pending creation with %s and leaves the next ordinary creation unconnected", async (method) => {
    await renderFlow();
    await releaseOnPane();
    if (method === "escape") await userEvent.setup().keyboard("{Escape}");
    else fireEvent.pointerDown(document.body);
    expect(screen.queryByRole("listbox", { name: "添加卡片" })).not.toBeInTheDocument();
    expect(createdArtifacts).toHaveLength(0);
    fireEvent.doubleClick(screen.getByTestId("flow"), { clientX: 620, clientY: 400 });
    expect(screen.getByRole("option", { name: "文字" })).not.toHaveAttribute("aria-disabled", "true");
    await userEvent.setup().click(screen.getByRole("option", { name: "图片" }));
    await waitFor(() => expect(placements).toHaveLength(1));
    expect(canvasConnections).toHaveLength(0);
  });

  it("retries a failed connection without creating a second node", async () => {
    let attempts = 0;
    server.use(http.post("/api/v1/projects/:projectId/canvas/connections", () => {
      attempts++;
      return attempts === 1 ? HttpResponse.json({ code: "CONFLICT", detail: "retry", retryable: true }, { status: 409 })
        : HttpResponse.json({ connection: {}, draft: null }, { status: 201 });
    }));
    await renderFlow();
    await releaseOnPane();
    await userEvent.setup().click(screen.getByRole("option", { name: "图片" }));
    await userEvent.setup().click(await screen.findByRole("button", { name: "重试" }));
    await waitFor(() => expect(attempts).toBe(2));
    expect(createdArtifacts).toHaveLength(1);
    expect(placements).toHaveLength(1);
  });

  it("keeps the pending connection when retrying failed placement", async () => {
    const failPlacement = http.post("/api/v1/projects/:projectId/canvas/commands", () =>
      HttpResponse.json({ code: "UNAVAILABLE", retryable: true }, { status: 503 }), { once: true });
    server.use(failPlacement);
    await renderFlow();
    await releaseOnPane();
    await userEvent.setup().click(screen.getByRole("option", { name: "图片" }));
    await userEvent.setup().click(await screen.findByRole("button", { name: "重试" }));
    await waitFor(() => expect(canvasConnections).toHaveLength(1));
    expect(createdArtifacts).toHaveLength(1);
    expect(placements).toHaveLength(1);
  });

  it("does not silently connect a different source version selected while the menu is open", async () => {
    const client = await renderFlow();
    await releaseOnPane();
    currentItems = currentItems.map((item) => item.id === "image-id-card" ? { ...item, selectedVersionId: "new-version" } : item);
    act(() => client.setQueryData(["canvas", "project-1"], { items: currentItems }));
    await userEvent.setup().click(screen.getByRole("option", { name: "图片" }));
    await screen.findByRole("alert");
    expect(placements).toHaveLength(1);
    expect(canvasConnections).toHaveLength(0);
  });

  it.each(["text-id-card", "empty-image-id-card"])("disables connected creation from %s without a usable media source", async (sourceId) => {
    await renderFlow();
    await releaseOnPane(sourceId);
    for (const option of screen.getAllByRole("option")) expect(option).toHaveAttribute("aria-disabled", "true");
    expect(createdArtifacts).toHaveLength(0);
  });

  it.each(["text-id-card", "image-id-card", "removed-card"])("does not open creation when released on node %s", async (targetId) => {
    await renderFlow();
    pointAt(targetId);
    act(() => flowProps.onConnectStart?.(null, { nodeId: "image-id-card", handleId: "artifact-output" }));
    await act(async () => flowProps.onConnectEnd?.(new MouseEvent("mouseup", { clientX: 620, clientY: 400 }), { toHandle: null }));
    expect(screen.queryByRole("listbox", { name: "添加卡片" })).not.toBeInTheDocument();
    expect(createdArtifacts).toHaveLength(0);
  });

  it("uses the final release hit instead of a previous valid card hover", async () => {
    await renderFlow();
    pointAt("video-id-card");
    act(() => flowProps.onConnectStart?.(null, { nodeId: "image-id-card", handleId: "artifact-output" }));
    fireEvent.mouseMove(document, { clientX: 300, clientY: 200 });
    pointAt(null);
    await act(async () => flowProps.onConnectEnd?.(new MouseEvent("mouseup", { clientX: 620, clientY: 400 }), { toHandle: null }));
    expect(screen.getByRole("listbox", { name: "添加卡片" })).toBeVisible();
    expect(canvasConnections).toHaveLength(0);
  });

  it("does not open creation outside the canvas", async () => {
    await renderFlow();
    pointAt(null);
    Object.defineProperty(document, "elementFromPoint", { configurable: true, value: () => document.body });
    act(() => flowProps.onConnectStart?.(null, { nodeId: "image-id-card", handleId: "artifact-output" }));
    await act(async () => flowProps.onConnectEnd?.(new MouseEvent("mouseup"), { toHandle: null }));
    expect(screen.queryByRole("listbox", { name: "添加卡片" })).not.toBeInTheDocument();
  });

  it("does not commit a hand-drawn line between two Artifact cards", async () => {
    await renderFlow();
    await dropOn("text-id-card");
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(revisions).toHaveLength(0);
    expect(agentPatches).toHaveLength(0);
  });

  it("ignores a stale pointer hit naming a removed node", async () => {
    await renderFlow();
    await dropOn("removed-card");
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(canvasConnections).toHaveLength(0);
    expect(agentPatches).toHaveLength(0);
    expect(revisions).toHaveLength(0);
  });

  it("persists a media input connection when dropped on a video card", async () => {
    await renderFlow();
    await dropOn("video-id-card");
    await waitFor(() => expect(canvasConnections).toHaveLength(1));
    expect(canvasConnections[0]).toMatchObject({
      sourceCanvasItemId: "image-id-card", targetCanvasItemId: "video-id-card",
      sourceVersionId: imageVersionId, relationType: "MEDIA_INPUT",
      expectedTargetDraftVersion: 0,
    });
    expect(revisions).toHaveLength(0);
    expect(agentPatches).toHaveLength(0);
  });

  it("persists a media input connection when dropped on an empty image draft card", async () => {
    await renderFlow();
    await dropOn("empty-image-id-card");
    await waitFor(() => expect(canvasConnections).toHaveLength(1));
    expect(canvasConnections[0]).toMatchObject({
      sourceCanvasItemId: "image-id-card", targetCanvasItemId: "empty-image-id-card",
      sourceVersionId: imageVersionId, relationType: "MEDIA_INPUT",
      expectedTargetDraftVersion: 0,
    });
    expect(revisions).toHaveLength(0);
    expect(agentPatches).toHaveLength(0);
  });

  it("leaves a drop that already reached a port to React Flow's own onConnect", async () => {
    await renderFlow();
    pointAt("text-id-card");
    await act(async () => {
      flowProps.onConnectStart?.(null, { nodeId: "image-id-card", handleId: "artifact-output" });
    });
    document.dispatchEvent(new MouseEvent("mousemove", { clientX: 10, clientY: 10 }));
    await act(async () => {
      await flowProps.onConnectEnd?.(null, { toHandle: { nodeId: "text-id-card" } });
    });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(revisions).toHaveLength(0);
  });

  it("keeps double-click on empty canvas from zooming the viewport", async () => {
    await renderFlow();
    expect(flowProps.zoomOnDoubleClick).toBe(false);
  });
});
