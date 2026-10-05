import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Agent, Artifact, CanvasItem, ProjectSnapshot } from "../../shared/api/client";
import { server } from "../../test/server";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

type FlowProps = {
  nodes?: unknown[];
  zoomOnDoubleClick?: unknown;
  onConnectStart?: (event: unknown, params: { nodeId: string | null; handleId: string | null }) => void;
  onConnectEnd?: (event: unknown, state: { toHandle?: unknown }) => Promise<void> | void;
};

let flowProps: FlowProps = {};

// React Flow 的落点判定依赖真实布局，这里只驱动页面的手势回调，指针命中由 stub 指定。
vi.mock("@xyflow/react", async (importOriginal) => {
  const real = await importOriginal<typeof import("@xyflow/react")>();
  return {
    ...real,
    ReactFlow: (props: FlowProps) => {
      flowProps = props;
      return <div data-testid="flow" />;
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
    selectedVersion: kind === "TEXT" ? null : selectedVersion, version: 3, agent: null,
    artifact: { id, projectId: "project-1", kind, title, resourceDefaultVersionId: versionId,
      version: 3, createdAt: now, updatedAt: now,
      resourceDefaultVersion: selectedVersion } };
}

const items: CanvasItem[] = [
  card("image-id", "IMAGE", "参考图", imageVersionId,
    { sourceType: "UPLOAD", assetId: "asset-id" }),
  { id: "empty-image-id-card", subjectType: "ARTIFACT", subjectId: "empty-image-id",
    title: "空白图片", x: 300, y: 200, width: 260, height: 150, zIndex: 0, groupId: null,
    locked: false, selectedVersionId: null, selectedVersion: null, version: 1, agent: null,
    artifact: { id: "empty-image-id", projectId: "project-1", kind: "IMAGE", title: "空白图片",
      resourceDefaultVersionId: null, resourceDefaultVersion: null, version: 1,
      createdAt: now, updatedAt: now } },
  card("text-id", "TEXT", "文字", "22222222-2222-4222-8222-222222222222",
    { format: "PLAIN_TEXT", text: "正文" }),
  card("video-id", "VIDEO", "视频", "33333333-3333-4333-8333-333333333333",
    { assetId: "asset-id", prompt: "缓慢推近",
      workflowVersion: "mock-video-v1", parameters: {}, sourceTaskId: "task-id" }),
  { id: "agent-card", subjectType: "AGENT", subjectId: "agent-id", x: 400, y: 0,
    title: "Agent",
    width: 460, height: 600, zIndex: 1, groupId: null, locked: false, selectedVersionId: null, selectedVersion: null, version: 4, artifact: null,
    agent: { id: "agent-id", projectId: "project-1", profileKey: "creator", profileVersion: 1,
      name: "Creator", instruction: "Create", outputGroupId: "group-1", version: 4,
      createdAt: now, updatedAt: now, bindings: [] } },
];

const agentPatches: Array<Record<string, unknown>> = [];
const canvasConnections: Array<Record<string, unknown>> = [];
const revisions: string[] = [];

function snapshot(): ProjectSnapshot {
  return { project: { id: "project-1", name: "落点项目", status: "ACTIVE",
    aspectRatio: "LANDSCAPE_16_9", version: 1, createdAt: now, updatedAt: now },
    canvas: { items }, connections: [], agents: [], activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 0 };
}

beforeEach(() => {
  agentPatches.length = 0;
  canvasConnections.length = 0;
  revisions.length = 0;
  flowProps = {};
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({
      id: "owner-id", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({
      headerName: "X-XSRF-TOKEN", token: "test-token" })),
    http.get("/api/v1/projects/:projectId", () => HttpResponse.json(snapshot().project)),
    http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json(snapshot())),
    http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items })),
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
      }, draft: null, agent: items[3]!.agent }, { status: 201 });
    }),
    http.patch("/api/v1/projects/:projectId/agents/:agentId", async ({ request }) => {
      const body = await request.json() as Record<string, unknown>;
      agentPatches.push(body);
      return HttpResponse.json({ ...items[3]!.agent, bindings: body.bindings } as Agent);
    }),
    http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions",
      async ({ params }) => {
        revisions.push(String(params.artifactId));
        return HttpResponse.json({} as Artifact, { status: 201 });
      }));
});

/** jsdom 没有布局与 elementFromPoint，这里把它定义成「指针下就是这张卡片」。 */
function pointAt(nodeId: string) {
  Object.defineProperty(document, "elementFromPoint", {
    configurable: true,
    writable: true,
    value: () => ({ closest: () => ({ getAttribute: () => nodeId }) }),
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
  it("does not commit a hand-drawn line between two Artifact cards", async () => {
    await renderFlow();
    await dropOn("text-id-card");
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(revisions).toHaveLength(0);
    expect(agentPatches).toHaveLength(0);
  });

  it("persists an exact-version Agent image connection when dropped on an Agent card", async () => {
    await renderFlow();
    await dropOn("agent-card");
    await waitFor(() => expect(canvasConnections).toHaveLength(1));
    expect(canvasConnections[0]).toMatchObject({
      sourceCanvasItemId: "image-id-card", targetCanvasItemId: "agent-card",
      sourceVersionId: imageVersionId, relationType: "AGENT_IMAGE_INPUT",
      expectedTargetAgentVersion: 4,
    });
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
