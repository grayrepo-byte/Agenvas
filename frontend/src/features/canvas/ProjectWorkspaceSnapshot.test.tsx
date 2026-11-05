import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import type { ReactFlowProps } from "@xyflow/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ProjectSnapshot } from "../../shared/api/client";
import { clickControl } from "../../test/controls";
import { server } from "../../test/server";
import { useCanvasStore } from "./canvasStore";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

// Layout is outside this seam; retain the actual workspace queries and their node projection.
vi.mock("@xyflow/react", async (importOriginal) => ({
  ...await importOriginal<typeof import("@xyflow/react")>(),
  ReactFlow: ({ nodes }: ReactFlowProps) => <div>{nodes?.map((node) =>
    <output key={node.id}>{String((node.data.item as { title: string }).title)}</output>)}</div>,
}));

const now = "2026-11-04T00:00:00Z";
const project = { id: "project-1", name: "快照项目", status: "ACTIVE" as const,
  aspectRatio: "LANDSCAPE_16_9" as const, version: 1, createdAt: now, updatedAt: now };
const artifact = { id: "artifact-1", projectId: project.id, kind: "TEXT" as const,
  title: "快照节点", resourceDefaultVersionId: null, resourceDefaultVersion: null,
  version: 1, createdAt: now, updatedAt: now };
const snapshot: ProjectSnapshot = { project, canvas: { items: [{ id: "item-1", title: artifact.title,
  subjectType: "ARTIFACT", subjectId: artifact.id, x: 0, y: 0, width: 420, height: 240,
  zIndex: 0, groupId: null, locked: false, selectedVersionId: null, selectedVersion: null,
  version: 1, artifact }] }, connections: [], director: null, activeRun: null,
  activeTasks: [], unknownTasks: [], snapshotSeq: 7 };

beforeEach(() => {
  useCanvasStore.setState({ drafts: {}, selectedIds: [], saveState: "saved" });
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "owner", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/projects/project-1/snapshot", () => HttpResponse.json(snapshot)),
  );
});

function showWorkspace(client = createQueryClient()) {
  return { client, ...render(<QueryClientProvider client={client}>
    <MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter>
  </QueryClientProvider>) };
}

function projectionReads(data = snapshot) {
  const reads = { project: vi.fn(() => HttpResponse.json(data.project)),
    canvas: vi.fn(() => HttpResponse.json(data.canvas)),
    connections: vi.fn(() => HttpResponse.json({ items: data.connections })) };
  server.use(
    http.get("/api/v1/projects/project-1", reads.project),
    http.get("/api/v1/projects/project-1/canvas/items", reads.canvas),
    http.get("/api/v1/projects/project-1/canvas/connections", reads.connections),
  );
  return reads;
}

it("uses the fresh snapshot without downloading its three projections again", async () => {
  const reads = projectionReads();
  const { client } = showWorkspace();
  expect(await screen.findByText("快照节点")).toBeVisible();
  expect(screen.getByRole("heading", { name: "快照项目" })).toBeVisible();
  await waitFor(() => expect(client.isFetching()).toBe(0));
  for (const read of Object.values(reads)) expect(read).not.toHaveBeenCalled();
  const capturedAt = client.getQueryState(["snapshot", project.id])?.dataUpdatedAt;
  for (const prefix of ["projects", "canvas", "canvas-connections"]) {
    expect(client.getQueryState([prefix, project.id])?.dataUpdatedAt).toBe(capturedAt);
  }
});

it("keeps existing projections instead of replacing them with snapshot initial data", async () => {
  const client = createQueryClient();
  const cachedAt = Date.now() - 1_000;
  client.setQueryData(["projects", project.id], { ...project, name: "较新项目", version: 4 }, { updatedAt: cachedAt });
  client.setQueryData(["canvas", project.id], { items: [{ ...snapshot.canvas.items[0]!, title: "较新节点", version: 4 }] }, { updatedAt: cachedAt });
  client.setQueryData(["canvas-connections", project.id], { items: [] }, { updatedAt: cachedAt });
  const reads = projectionReads();
  showWorkspace(client);
  expect(await screen.findByText("较新节点")).toBeVisible();
  await waitFor(() => expect(client.isFetching()).toBe(0));
  expect(screen.getByRole("heading", { name: "较新项目" })).toBeVisible();
  expect(screen.queryByText("快照节点")).not.toBeInTheDocument();
  for (const read of Object.values(reads)) expect(read).not.toHaveBeenCalled();
  for (const prefix of ["projects", "canvas", "canvas-connections"]) {
    expect(client.getQueryState([prefix, project.id])?.dataUpdatedAt).toBe(cachedAt);
  }
});

it("retains an old snapshot's age so stale projections still refetch", async () => {
  const client = createQueryClient();
  client.setQueryData(["snapshot", project.id], snapshot, { updatedAt: Date.now() - 31_000 });
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  server.use(http.get("/api/v1/projects/project-1/snapshot", async () => {
    await pending; return HttpResponse.json(snapshot);
  }));
  const reads = projectionReads({ ...snapshot, project: { ...project, name: "独立读取项目" },
    canvas: { items: [{ ...snapshot.canvas.items[0]!, title: "独立读取节点" }] } });
  try {
    showWorkspace(client);
    expect(await screen.findByText("独立读取节点")).toBeVisible();
    await waitFor(() => { for (const read of Object.values(reads)) expect(read).toHaveBeenCalledTimes(1); });
  } finally { release(); }
  await waitFor(() => expect(client.isFetching()).toBe(0));
  expect(screen.getByRole("heading", { name: "独立读取项目" })).toBeVisible();
  expect(screen.getByText("独立读取节点")).toBeVisible();
});

it("keeps the snapshot visible on an invalidated read failure and allows an explicit retry", async () => {
  const client = createQueryClient();
  client.setQueryDefaults(["canvas", project.id], { retry: false });
  const reads = projectionReads();
  reads.canvas.mockImplementationOnce(() => HttpResponse.json({ title: "Synthetic read failure",
    detail: "Synthetic read failure", status: 503, code: "SYNTHETIC_FAILURE" },
  { status: 503, headers: { "Content-Type": "application/problem+json" } }));
  reads.canvas.mockImplementation(() => HttpResponse.json({ items: [{ ...snapshot.canvas.items[0]!, title: "重试后节点" }] }));
  showWorkspace(client);
  expect(await screen.findByText("快照节点")).toBeVisible();
  await act(async () => { await client.invalidateQueries({ queryKey: ["canvas", project.id] }); });
  expect(await screen.findByText("Synthetic read failure")).toBeVisible();
  expect(screen.getByText("快照节点")).toBeVisible();
  await clickControl(screen.getByRole("button", { name: "重试读取" }));
  expect(await screen.findByText("重试后节点")).toBeVisible();
  expect(reads.canvas).toHaveBeenCalledTimes(2);
  expect(reads.project).not.toHaveBeenCalled();
  expect(reads.connections).not.toHaveBeenCalled();
});
