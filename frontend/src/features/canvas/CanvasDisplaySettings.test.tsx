import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactFlowProps } from "@xyflow/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasItem } from "../../shared/api/client";
import { server } from "../../test/server";
import { useCanvasStore } from "./canvasStore";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

let flowProps: ReactFlowProps = {};
// Mock layout only: drive React Flow's controlled selection notifications and inspect its projection.
vi.mock("@xyflow/react", async (importOriginal) => ({
  ...await importOriginal<typeof import("@xyflow/react")>(),
  ReactFlow: (props: ReactFlowProps) => { flowProps = props; return <div data-testid="flow" />; },
}));

const now = "2026-10-01T00:00:00Z";
const items: CanvasItem[] = ["parent", "node", "child", "other"].map((id, index) => ({
  id, title: id, subjectType: "ARTIFACT", subjectId: `artifact-${id}`, x: index * 300, y: 0,
  width: 280, height: 180, zIndex: index, groupId: null, locked: false, version: 1,
  selectedVersionId: null, selectedVersion: null, agent: null,
  artifact: { id: `artifact-${id}`, projectId: "project-1", kind: "TEXT", title: id,
    resourceDefaultVersionId: null, resourceDefaultVersion: null, version: 1, createdAt: now, updatedAt: now },
}));
const connections = [["parent", "node"], ["node", "child"], ["child", "other"]].map(([source, target], index) => ({
  id: `connection-${index}`, projectId: "project-1", sourceCanvasItemId: source, targetCanvasItemId: target,
  relationType: "MEDIA_DERIVATION", sourceArtifactVersionId: "version-1", version: 1, createdAt: now, updatedAt: now,
}));
const project = { id: "project-1", name: "连线项目", status: "ACTIVE", aspectRatio: "LANDSCAPE_16_9",
  version: 1, createdAt: now, updatedAt: now };

beforeEach(() => {
  localStorage.clear();
  useCanvasStore.setState({ drafts: {}, selectedIds: [], saveState: "saved" });
  flowProps = {};
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "owner", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/projects/:id", () => HttpResponse.json(project)),
    http.get("/api/v1/projects/:id/snapshot", () => HttpResponse.json({ project, canvas: { items }, connections,
      agents: [], activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 0 })),
    http.get("/api/v1/projects/:id/canvas/items", () => HttpResponse.json({ items })),
    http.get("/api/v1/projects/:id/canvas/connections", () => HttpResponse.json({ items: connections })),
  );
});

function renderWorkspace() {
  return render(<QueryClientProvider client={createQueryClient()}>
    <MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter>
  </QueryClientProvider>);
}
function visibleEdges() { return flowProps.edges?.filter((edge) => !edge.hidden).map((edge) => edge.id); }

it("wires both persisted switches to selection-driven visibility and restores them after reopening the project", async () => {
  const user = userEvent.setup();
  const workspace = renderWorkspace();
  await waitFor(() => expect(flowProps.edges).toHaveLength(3));
  expect(visibleEdges()).toHaveLength(3);
  expect(flowProps.edges?.every((edge) => edge.data?.connectionFlowEnabled)).toBe(true);
  await user.click(screen.getByRole("button", { name: "画布设置" }));
  await user.click(screen.getByRole("menuitemcheckbox", { name: /始终显示连线/ }));
  expect(visibleEdges()).toEqual([]);
  act(() => flowProps.onNodesChange?.([{ type: "select", id: "node", selected: true }]));
  expect(visibleEdges()).toEqual(["canvas-connection:connection-0", "canvas-connection:connection-1"]);
  await user.click(screen.getByRole("menuitemcheckbox", { name: /连线流光效果/ }));
  expect(flowProps.edges?.every((edge) => edge.data?.connectionFlowEnabled === false)).toBe(true);
  // Selecting a displayed relation clears the node but must still allow deleting that line.
  act(() => {
    flowProps.onEdgesChange?.([{ type: "select", id: "canvas-connection:connection-0", selected: true }]);
    flowProps.onNodesChange?.([{ type: "select", id: "node", selected: false }]);
  });
  expect(visibleEdges()).toEqual(["canvas-connection:connection-0"]);
  act(() => flowProps.onEdgesChange?.([{ type: "select", id: "canvas-connection:connection-0", selected: false }]));
  expect(visibleEdges()).toEqual([]);
  await user.keyboard("{Escape}");
  expect(screen.queryByRole("menu", { name: "画布设置" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "画布设置" })).toHaveFocus();
  workspace.unmount();
  renderWorkspace();
  await waitFor(() => expect(flowProps.edges).toHaveLength(3));
  expect(visibleEdges()).toEqual([]);
  expect(flowProps.edges?.every((edge) => edge.data?.connectionFlowEnabled === false)).toBe(true);
  await user.click(screen.getByRole("button", { name: "画布设置" }));
  expect(screen.getByRole("menuitemcheckbox", { name: /始终显示连线/ })).toHaveAttribute("aria-checked", "false");
  expect(screen.getByRole("menuitemcheckbox", { name: /连线流光效果/ })).toHaveAttribute("aria-checked", "false");
  await user.click(screen.getByRole("menuitemcheckbox", { name: /始终显示连线/ }));
  expect(visibleEdges()).toHaveLength(3);
});
