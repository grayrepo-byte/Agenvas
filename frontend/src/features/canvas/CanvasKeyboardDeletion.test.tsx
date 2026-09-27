import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Agent, Artifact, CanvasItem, ProjectSnapshot } from "../../shared/api/client";
import { server } from "../../test/server";
import { useCanvasStore } from "./canvasStore";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

const now = "2026-09-27T00:00:00Z";
const imageVersionId = "11111111-1111-4111-8111-111111111111";

function artifact(id: string, kind: Artifact["kind"], title: string, currentVersionId: string | null,
  currentVersion: Artifact["currentVersion"]): Artifact {
  return { id, projectId: "project-1", kind, title, currentVersionId, currentVersion,
    version: 2, createdAt: now, updatedAt: now };
}

/** One image card, one Agent card bound to it, and one video holding an exact-version input. */
function items(linked: boolean): CanvasItem[] {
  const image = artifact("image-id", "IMAGE", "参考图", imageVersionId, {
    id: imageVersionId, versionNo: 1, schemaVersion: 1,
    content: { sourceType: "UPLOAD", assetId: "asset-id" }, inputReferences: [],
    createdByKind: "USER", runId: null, createdAt: now });
  const videoVersionId = "22222222-2222-4222-8222-222222222222";
  return [
    { id: "image-card", subjectType: "ARTIFACT", subjectId: "image-id", x: 0, y: 0,
      title: "图片",
      width: 280, height: 180, zIndex: 0, groupId: null, locked: false, version: 3, agent: null,
      artifact: image },
    { id: "agent-card", subjectType: "AGENT", subjectId: "agent-id", x: 400, y: 0,
      title: "Agent",
      width: 460, height: 600, zIndex: 1, groupId: null, locked: false, version: 4,
      artifact: null,
      agent: { id: "agent-id", projectId: "project-1", profileKey: "creator", profileVersion: 1,
        name: "Creator", instruction: "Create", outputGroupId: "group-1", version: 4,
        createdAt: now, updatedAt: now,
        bindings: linked ? [{ id: "binding-id", artifactId: "image-id",
          selectedVersionId: imageVersionId, bindingType: "INPUT" }] : [] } },
    { id: "video-card", subjectType: "ARTIFACT", subjectId: "video-id", x: 800, y: 0,
      title: "视频",
      width: 280, height: 180, zIndex: 2, groupId: null, locked: false, version: 5, agent: null,
      artifact: artifact("video-id", "VIDEO", "视频", videoVersionId, {
        id: videoVersionId, versionNo: 1, schemaVersion: 1,
        content: { assetId: "asset-id", prompt: "缓慢推近", providerConfigVersion: 1,
          workflowVersion: "mock-video-v1", parameters: {}, sourceTaskId: "task-id" },
        inputReferences: linked ? [{ versionId: imageVersionId, role: "sourceImage",
          order: 0, kind: "IMAGE" }] : [],
        createdByKind: "USER", runId: null, createdAt: now }) },
  ];
}

function snapshot(getItems: () => CanvasItem[]): ProjectSnapshot {
  return { project: { id: "project-1", name: "删除项目", status: "ACTIVE",
    aspectRatio: "LANDSCAPE_16_9", version: 1, createdAt: now, updatedAt: now },
    canvas: { items: getItems() }, agents: [], activeRun: null, activeTasks: [],
    unknownTasks: [], snapshotSeq: 0 };
}

const canvasCommands: Array<Record<string, unknown>> = [];
const agentPatches: Array<Record<string, unknown>> = [];
const revisions: Array<{ artifactId: string; body: Record<string, unknown> }> = [];

function stubProject(getItems: () => CanvasItem[]) {
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({
      id: "owner-id", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({
      headerName: "X-XSRF-TOKEN", token: "test-token" })),
    http.get("/api/v1/projects/:projectId", () => HttpResponse.json(snapshot(getItems).project)),
    http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json(snapshot(getItems))),
    http.get("/api/v1/projects/:projectId/canvas/items", () =>
      HttpResponse.json({ items: getItems() })),
    http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/export-proposals", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/usage", () => HttpResponse.json([])),
    http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
      const body = await request.json() as { commands: Array<Record<string, unknown>> };
      canvasCommands.push(...body.commands);
      return HttpResponse.json({ items: items(false).filter((item) =>
        !body.commands.some((command) => command.itemId === item.id)) });
    }),
    http.patch("/api/v1/projects/:projectId/agents/:agentId", async ({ request }) => {
      const body = await request.json() as Record<string, unknown>;
      agentPatches.push(body);
      return HttpResponse.json({ ...items(true)[1]!.agent, bindings: body.bindings } as Agent);
    }),
    http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions",
      async ({ request, params }) => {
        const body = await request.json() as Record<string, unknown>;
        revisions.push({ artifactId: String(params.artifactId), body });
        return HttpResponse.json(items(false)[2]!.artifact, { status: 201 });
      }),
  );
}

function renderWorkspace() {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(<QueryClientProvider client={client}>
    <MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter>
  </QueryClientProvider>);
}

async function selectNode(id: string) {
  await waitFor(() => expect(document.querySelector(`.react-flow__node[data-id='${id}']`)).not.toBeNull());
  useCanvasStore.setState({ selectedIds: [id] });
  await waitFor(() => expect(document.querySelector(`.react-flow__node[data-id='${id}']`))
    .toHaveClass("selected"));
}

function press(key: string) {
  fireEvent.keyDown(document, { key, code: key });
}

describe("canvas keyboard deletion", () => {
  beforeEach(() => {
    canvasCommands.length = 0;
    agentPatches.length = 0;
    revisions.length = 0;
    useCanvasStore.setState({ selectedIds: [] });
  });

  it("removes the selected card through the canvas command API on Backspace", async () => {
    stubProject(() => items(true));
    renderWorkspace();
    await selectNode("image-card");
    press("Backspace");
    await waitFor(() => expect(canvasCommands).toHaveLength(1));
    expect(canvasCommands[0]).toEqual({ type: "REMOVE", itemId: "image-card", expectedVersion: 3 });
  });

  it("removes the selected card on Delete as well", async () => {
    stubProject(() => items(true));
    renderWorkspace();
    await selectNode("image-card");
    press("Delete");
    await waitFor(() => expect(canvasCommands).toHaveLength(1));
    expect(canvasCommands[0]).toEqual({ type: "REMOVE", itemId: "image-card", expectedVersion: 3 });
  });

  it("removes only the card, never the bindings and references hanging off it", async () => {
    stubProject(() => items(true));
    renderWorkspace();
    await selectNode("image-card");
    press("Backspace");
    await waitFor(() => expect(canvasCommands).toHaveLength(1));
    // 移除卡片保留 Artifact，绑定与引用仍然有效：级联与被选中的关系线都不能写业务关系。
    expect(agentPatches).toHaveLength(0);
    expect(revisions).toHaveLength(0);
  });
});
