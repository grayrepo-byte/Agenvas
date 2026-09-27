import { QueryClientProvider } from "@tanstack/react-query";
import { render, waitFor } from "@testing-library/react";
import type { Edge } from "@xyflow/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Agent, Artifact, CanvasItem, ProjectSnapshot } from "../../shared/api/client";
import { server } from "../../test/server";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

type FlowProps = {
  deleteKeyCode?: unknown;
  edges?: Edge[];
  onBeforeDelete?: (selection: { nodes: unknown[]; edges: Edge[] }) => Promise<unknown>;
};

let flowProps: FlowProps = {};

// React Flow needs measured DOM geometry to draw edges, so the deletion routing is driven directly here.
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
const videoVersionId = "22222222-2222-4222-8222-222222222222";

/** An image card, one Agent bound to it, and a video naming that exact image version. */
const linkedItems: CanvasItem[] = [
  { id: "image-card", subjectType: "ARTIFACT", subjectId: "image-id", x: 0, y: 0,
    title: "图片",
    width: 280, height: 180, zIndex: 0, groupId: null, locked: false, version: 3, agent: null,
    artifact: {
      id: "image-id", projectId: "project-1", kind: "IMAGE", title: "参考图",
      currentVersionId: imageVersionId, version: 2, createdAt: now, updatedAt: now,
      currentVersion: { id: imageVersionId, versionNo: 1, schemaVersion: 1,
        content: { sourceType: "UPLOAD", assetId: "asset-id" }, inputReferences: [],
        createdByKind: "USER", runId: null, createdAt: now },
    } },
  { id: "agent-card", subjectType: "AGENT", subjectId: "agent-id", x: 400, y: 0,
    title: "Agent",
    width: 460, height: 600, zIndex: 1, groupId: null, locked: false, version: 4, artifact: null,
    agent: { id: "agent-id", projectId: "project-1", profileKey: "creator", profileVersion: 1,
      name: "Creator", instruction: "Create", outputGroupId: "group-1", version: 4,
      createdAt: now, updatedAt: now,
      bindings: [{ id: "binding-id", artifactId: "image-id",
        selectedVersionId: imageVersionId, bindingType: "INPUT" }] } },
  { id: "video-card", subjectType: "ARTIFACT", subjectId: "video-id", x: 800, y: 0,
    title: "视频",
    width: 280, height: 180, zIndex: 2, groupId: null, locked: false, version: 5, agent: null,
    artifact: {
      id: "video-id", projectId: "project-1", kind: "VIDEO", title: "视频",
      currentVersionId: videoVersionId, version: 5, createdAt: now, updatedAt: now,
      currentVersion: { id: videoVersionId, versionNo: 1, schemaVersion: 1,
        content: { assetId: "asset-id", prompt: "缓慢推近", providerConfigVersion: 1,
          workflowVersion: "mock-video-v1", parameters: {}, sourceTaskId: "task-id" },
        inputReferences: [{ versionId: imageVersionId, role: "sourceImage",
          order: 0, kind: "IMAGE" }],
        createdByKind: "USER", runId: null, createdAt: now },
    } },
  { id: "output-card", subjectType: "ARTIFACT", subjectId: "output-id", x: 1200, y: 0,
    title: "输出",
    width: 280, height: 180, zIndex: 3, groupId: "group-1", locked: false, version: 6, agent: null,
    artifact: {
      id: "output-id", projectId: "project-1", kind: "IMAGE", title: "Agent 产物",
      currentVersionId: null, version: 1, createdAt: now, updatedAt: now, currentVersion: null,
    } },
];

const agentPatches: Array<Record<string, unknown>> = [];
const revisions: Array<{ artifactId: string; body: Record<string, unknown> }> = [];
const canvasCommands: Array<Record<string, unknown>> = [];

function snapshot(): ProjectSnapshot {
  return { project: { id: "project-1", name: "删除项目", status: "ACTIVE",
    aspectRatio: "LANDSCAPE_16_9", version: 1, createdAt: now, updatedAt: now },
    canvas: { items: linkedItems }, agents: [], activeRun: null, activeTasks: [],
    unknownTasks: [], snapshotSeq: 0 };
}

beforeEach(() => {
  agentPatches.length = 0;
  revisions.length = 0;
  canvasCommands.length = 0;
  flowProps = {};
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({
      id: "owner-id", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({
      headerName: "X-XSRF-TOKEN", token: "test-token" })),
    http.get("/api/v1/projects/:projectId", () => HttpResponse.json(snapshot().project)),
    http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json(snapshot())),
    http.get("/api/v1/projects/:projectId/canvas/items", () =>
      HttpResponse.json({ items: linkedItems })),
    http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/export-proposals", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/usage", () => HttpResponse.json([])),
    http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
      const body = await request.json() as { commands: Array<Record<string, unknown>> };
      canvasCommands.push(...body.commands);
      return HttpResponse.json({ items: linkedItems });
    }),
    http.patch("/api/v1/projects/:projectId/agents/:agentId", async ({ request }) => {
      const body = await request.json() as Record<string, unknown>;
      agentPatches.push(body);
      return HttpResponse.json({ ...linkedItems[1]!.agent, bindings: body.bindings } as Agent);
    }),
    http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions",
      async ({ request, params }) => {
        const body = await request.json() as Record<string, unknown>;
        revisions.push({ artifactId: String(params.artifactId), body });
        return HttpResponse.json({} as Artifact, { status: 201 });
      }));
});

async function renderFlow() {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(<QueryClientProvider client={client}>
    <MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter>
  </QueryClientProvider>);
  await waitFor(() => expect(flowProps.edges).toHaveLength(3));
}

function edge(prefix: string): Edge {
  const found = flowProps.edges?.find((candidate) => candidate.id.startsWith(prefix));
  if (!found) throw new Error(`Missing projected ${prefix} edge`);
  return found;
}

describe("canvas relation deletion", () => {
  it("enables both Delete and Backspace and never deletes locally", async () => {
    await renderFlow();
    expect(flowProps.deleteKeyCode).toEqual(["Backspace", "Delete"]);
    expect(await flowProps.onBeforeDelete!({ nodes: [], edges: [] })).toBe(false);
  });

  it("removes the Agent input binding behind the selected input line", async () => {
    await renderFlow();
    expect(await flowProps.onBeforeDelete!({ nodes: [], edges: [edge("input:")] })).toBe(false);
    await waitFor(() => expect(agentPatches).toHaveLength(1));
    expect(agentPatches[0]).toEqual({ expectedVersion: 4, name: "Creator",
      instruction: "Create", bindings: [] });
    expect(revisions).toHaveLength(0);
    expect(canvasCommands).toHaveLength(0);
  });

  it("writes nothing for a derived output line, an exact-version reference line, or a cascade from a removed card", async () => {
    await renderFlow();
    // 输出组线由 Agent 决定，精确版本引用线由生成时固定，两者都不能选中或删除。
    expect(edge("output:")).toMatchObject({ deletable: false, selectable: false });
    expect(edge("reference:")).toMatchObject({ deletable: false, selectable: false });
    await flowProps.onBeforeDelete!({ nodes: [], edges: [edge("output:")] });
    await flowProps.onBeforeDelete!({ nodes: [], edges: [edge("reference:")] });
    // 卡片被移除时，挂到它上面的关系线是级联来的，不能顺手删掉绑定或引用。
    await flowProps.onBeforeDelete!({ nodes: [{ id: "image-card",
      data: { item: linkedItems[0] } } as never], edges: [edge("input:")] });
    await waitFor(() => expect(canvasCommands).toHaveLength(1));
    expect(agentPatches).toHaveLength(0);
    expect(revisions).toHaveLength(0);
  });
});
