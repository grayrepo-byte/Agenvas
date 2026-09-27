import { QueryClientProvider } from "@tanstack/react-query";
import { render, waitFor } from "@testing-library/react";
import type { Edge } from "@xyflow/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasItem, ProjectSnapshot } from "../../shared/api/client";
import { server } from "../../test/server";
import { useCanvasStore } from "./canvasStore";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

type SelectionChange = { id: string; type: string; selected?: boolean };
type FlowProps = {
  nodes?: { id: string }[];
  edges?: Edge[];
  onNodesChange?: (changes: SelectionChange[]) => void;
  onEdgesChange?: (changes: SelectionChange[]) => void;
  onMoveStart?: (event: unknown, viewport: { x: number; y: number; zoom: number }) => void;
};

let flowProps: FlowProps = {};

// 选中态的同步依赖 React Flow 的变更通知，这里直接驱动页面对应的回调。
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

const items: CanvasItem[] = [
  { id: "image-card", subjectType: "ARTIFACT", subjectId: "image-id", x: 0, y: 0,
    width: 260, height: 150, zIndex: 0, groupId: null, locked: false, version: 3, agent: null,
    artifact: { id: "image-id", projectId: "project-1", kind: "IMAGE", title: "参考图",
      currentVersionId: imageVersionId, version: 3, createdAt: now, updatedAt: now,
      currentVersion: { id: imageVersionId, versionNo: 1, schemaVersion: 1,
        content: { sourceType: "UPLOAD", assetId: "asset-id" }, inputReferences: [],
        createdByKind: "USER", runId: null, createdAt: now } } },
  { id: "agent-card", subjectType: "AGENT", subjectId: "agent-id", x: 400, y: 0,
    width: 460, height: 600, zIndex: 1, groupId: null, locked: false, version: 4, artifact: null,
    agent: { id: "agent-id", projectId: "project-1", profileKey: "creator", profileVersion: 1,
      name: "Creator", instruction: "Create", outputGroupId: "group-1", version: 4,
      createdAt: now, updatedAt: now,
      bindings: [{ id: "binding-id", artifactId: "image-id",
        selectedVersionId: imageVersionId, bindingType: "INPUT" }] } },
  { id: "character-card", subjectType: "ARTIFACT", subjectId: "character-id", x: 800, y: 0,
    width: 260, height: 150, zIndex: 2, groupId: null, locked: false, version: 5, agent: null,
    artifact: { id: "character-id", projectId: "project-1", kind: "CHARACTER", title: "角色",
      currentVersionId: "22222222-2222-4222-8222-222222222222", version: 5,
      createdAt: now, updatedAt: now,
      currentVersion: { id: "22222222-2222-4222-8222-222222222222", versionNo: 1, schemaVersion: 1,
        content: { name: "Hero", description: "Lead", appearance: "Blue coat",
          referenceVersionIds: [] }, inputReferences: [],
        createdByKind: "USER", runId: null, createdAt: now } } },
];

function snapshot(): ProjectSnapshot {
  return { project: { id: "project-1", name: "选中项目", status: "ACTIVE",
    aspectRatio: "LANDSCAPE_16_9", version: 1, createdAt: now, updatedAt: now },
    canvas: { items }, agents: [], activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 0 };
}

beforeEach(() => {
  flowProps = {};
  useCanvasStore.setState({ selectedIds: [] });
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
    http.get("/api/v1/projects/:projectId/artifacts/:artifactId/draft", ({ params }) =>
      HttpResponse.json({ projectId: "project-1", artifactId: params.artifactId, prompt: "",
        inputImageVersionId: null, durationSeconds: null, capabilityId: null, version: 0,
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
    flowProps.onMoveStart?.({ type: "mousedown" }, { x: 0, y: 0, zoom: 1 });
    expect(selectedIds()).toEqual([]);
    await waitFor(() => expect(flowProps.edges?.[0]?.selected).toBeFalsy());
  });

  it("keeps the selection for a programmatic viewport move", async () => {
    await renderFlow();
    useCanvasStore.setState({ selectedIds: ["image-card"] });
    flowProps.onMoveStart?.(null, { x: 0, y: 0, zoom: 1 });
    expect(selectedIds()).toEqual(["image-card"]);
  });
});
