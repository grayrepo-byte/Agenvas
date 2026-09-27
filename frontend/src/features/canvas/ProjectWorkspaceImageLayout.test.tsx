import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { Node, ReactFlowProps, ResizeParams } from "@xyflow/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasCommand, CanvasItem } from "../../shared/api/client";
import { server } from "../../test/server";
import { useCanvasStore } from "./canvasStore";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

type TestNode = Node<{ onResizeEnd: (id: string, layout: Pick<ResizeParams, "x" | "y" | "width" | "height">) => void }>;

// Exercise the real workspace projection and HTTP save paths without emulating a browser's pointer geometry.
vi.mock("@xyflow/react", async (importOriginal) => ({
  ...await importOriginal<typeof import("@xyflow/react")>(),
  ReactFlow: ({ nodes = [], onNodeDragStop, onNodesChange }: ReactFlowProps<TestNode>) => <div>
    {nodes.map((node) => <div key={node.id} data-testid={node.id} style={node.style}>
      <button onClick={(event) => onNodeDragStop?.(event.nativeEvent,
        { ...node, position: { x: 140, y: 180 } }, [node])}>drag {node.id}</button>
      <button onClick={() => node.data.onResizeEnd(node.id,
        { x: 10, y: 30, width: 500, height: 25 })}>resize {node.id}</button>
      <button onClick={() => onNodesChange?.([{ type: "dimensions", id: node.id,
        dimensions: { width: 999, height: 999 } }])}>measure {node.id}</button>
    </div>)}
  </div>,
}));

const NOW = "2026-09-26T00:00:00Z";
function imageItem(id = "image-card", assetId = "landscape"): CanvasItem {
  const selectedVersion = { id: `${assetId}-version`, versionNo: 1, schemaVersion: 1 as const,
    content: { sourceType: "UPLOAD" as const, assetId }, inputReferences: [],
    createdByKind: "USER" as const, runId: null, createdAt: NOW };
  return { id, subjectType: "ARTIFACT", subjectId: id, x: 10, y: 30,
    title: "图片",
    width: 225, height: 300, version: 0, zIndex: 0, groupId: null, locked: false,
    selectedVersionId: selectedVersion.id, selectedVersion, agent: null,
    artifact: { id, projectId: "project-1", kind: "IMAGE", title: "图片", version: 0,
      resourceDefaultVersionId: `${assetId}-version`, createdAt: NOW, updatedAt: NOW,
      resourceDefaultVersion: selectedVersion } };
}

describe("workspace image dimensions", () => {
  let items: CanvasItem[];
  let commands: CanvasCommand[];
  let conflict: boolean;
  let draftMode: "RESULT" | "DRAFT";
  let metadataReads: number;
  let saveGate: Promise<void> | null;

  beforeEach(() => {
    items = [imageItem()]; commands = []; conflict = false; draftMode = "RESULT"; metadataReads = 0;
    saveGate = null;
    useCanvasStore.setState({ drafts: {}, selectedIds: [], saveState: "saved" });
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.get("/api/v1/projects/project-1", () => HttpResponse.json({ id: "project-1", name: "Images", status: "ACTIVE" })),
      http.get("/api/v1/projects/project-1/snapshot", () => HttpResponse.json({
        project: { id: "project-1", name: "Images", status: "ACTIVE" }, canvas: { items },
        agents: [], activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 0,
      })),
      http.get("/api/v1/projects/project-1/canvas/items", () => HttpResponse.json({ items })),
      http.get("/api/v1/projects/project-1/canvas-items/:canvasItemId/media-draft", ({ params }) => HttpResponse.json({
        canvasItemId: params.canvasItemId, projectId: "project-1", displayMode: draftMode, prompt: "",
        version: 0, parameters: {}, durationSeconds: null, capabilityId: null,
        videoInputMode: null, imageInputs: [], mentions: [],
        createdAt: NOW, updatedAt: NOW,
      })),
      http.get("/api/v1/projects/project-1/assets/:assetId", ({ params }) => {
        metadataReads++;
        return HttpResponse.json({ id: params.assetId, width: params.assetId === "portrait" ? 100 : 2000,
          height: params.assetId === "portrait" ? 2000 : 100 });
      }),
      http.post("/api/v1/projects/project-1/canvas/commands", async ({ request }) => {
        commands = (await request.json() as { commands: CanvasCommand[] }).commands;
        if (saveGate) await saveGate;
        if (conflict) return HttpResponse.json({ status: 409, code: "VERSION_CONFLICT", detail: "布局冲突" }, { status: 409 });
        items = items.map((item) => {
          const command = commands.find((entry) => entry.itemId === item.id);
          return command?.type === "UPDATE_LAYOUT" ? { ...item, ...command, version: item.version + 1 } : item;
        });
        return HttpResponse.json({ items });
      }),
    );
  });

  function showWorkspace() {
    const client = createQueryClient();
    client.setDefaultOptions({ queries: { retry: false, staleTime: Infinity } });
    const view = render(<QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/projects/project-1"]}>
        <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>);
    return { client, ...view };
  }

  it("fits original image dimensions, ignores DOM measurements, and preserves size through drag/save/reload", async () => {
    const view = showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "15px" }));
    expect(commands).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: "measure image-card" }));
    expect(useCanvasStore.getState().drafts).toEqual({});
    fireEvent.click(screen.getByRole("button", { name: "drag image-card" }));
    await waitFor(() => expect(commands[0]).toMatchObject({ type: "UPDATE_LAYOUT", x: 140, y: 180,
      width: 300, height: 80, expectedVersion: 0 }));
    await waitFor(() => expect(useCanvasStore.getState().saveState).toBe("saved"));
    expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "15px" });
    view.unmount();
    showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "15px" }));
  });

  it("keeps a failed resize draft and its aspect ratio when CAS rejects the layout", async () => {
    conflict = true;
    showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "15px" }));
    fireEvent.click(screen.getByRole("button", { name: "resize image-card" }));
    await waitFor(() => expect(screen.getByText("内容有冲突，当前修改未保存")).toBeInTheDocument());
    expect(commands[0]).toMatchObject({ width: 500, height: 80 });
    expect(useCanvasStore.getState().drafts["image-card"]).toMatchObject({ width: 500, height: 25 });
    expect(screen.getByTestId("image-card")).toHaveStyle({ width: "500px", height: "25px" });
  });

  it("preserves a successful proportional resize after the server replaces the draft", async () => {
    showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "15px" }));
    fireEvent.click(screen.getByRole("button", { name: "resize image-card" }));
    await waitFor(() => expect(commands[0]).toMatchObject({ width: 500, height: 80 }));
    await waitFor(() => expect(useCanvasStore.getState().drafts["image-card"]).toBeUndefined());
    expect(screen.getByTestId("image-card")).toHaveStyle({ width: "500px", height: "25px" });
  });

  it("retains stored dimensions when no image exists or metadata is unavailable", async () => {
    const empty = imageItem("empty-card");
    empty.artifact = empty.artifact ? { ...empty.artifact, resourceDefaultVersionId: null, resourceDefaultVersion: null } : null;
    items = [imageItem(), empty];
    server.use(http.get("/api/v1/projects/project-1/assets/:assetId", () => HttpResponse.json({}, { status: 503 })));
    showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "225px", height: "300px" }));
    expect(screen.getByTestId("empty-card")).toHaveStyle({ width: "225px", height: "300px" });
    expect(commands).toEqual([]);
  });

  it("follows result replacement and draft mode without automatic layout saves", async () => {
    const { client } = showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "15px" }));
    items = [imageItem("image-card", "portrait")];
    await act(async () => client.invalidateQueries({ queryKey: ["canvas", "project-1"] }));
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "15px", height: "300px" }));
    draftMode = "DRAFT";
    await act(async () => client.invalidateQueries({ queryKey: ["media-draft", "project-1"] }));
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "225px", height: "300px" }));
    expect(commands).toEqual([]);
  });

  it("deduplicates shared assets and saves projected dimensions when aligning images", async () => {
    items = [imageItem(), { ...imageItem("second-card"), x: 600 }];
    showWorkspace();
    await waitFor(() => expect(screen.getByTestId("second-card")).toHaveStyle({ width: "300px", height: "15px" }));
    expect(metadataReads).toBe(1);
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "second-card"]));
    fireEvent.click(screen.getByRole("button", { name: "左对齐" }));
    await waitFor(() => expect(commands).toHaveLength(2));
    for (const command of commands) expect(command).toMatchObject({ x: 10, width: 300, height: 80 });
    await waitFor(() => expect(useCanvasStore.getState().saveState).toBe("saved"));
    expect(screen.getByTestId("second-card")).toHaveStyle({ width: "300px", height: "15px" });
  });

  it("retains alignment positions and projected size if the server rejects the batch", async () => {
    conflict = true;
    items = [imageItem(), { ...imageItem("second-card"), x: 600 }];
    showWorkspace();
    await waitFor(() => expect(screen.getByTestId("second-card")).toHaveStyle({ width: "300px", height: "15px" }));
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "second-card"]));
    fireEvent.click(screen.getByRole("button", { name: "左对齐" }));
    await waitFor(() => expect(useCanvasStore.getState().saveState).toBe("conflict"));
    expect(useCanvasStore.getState().drafts["second-card"]).toEqual({ x: 10, y: 30, width: 300, height: 15 });
  });

  it("clears only the submitted alignment drafts when selection changes before saving finishes", async () => {
    let finishSave: (() => void) | undefined;
    saveGate = new Promise<void>((resolve) => { finishSave = resolve; });
    items = [imageItem(), { ...imageItem("second-card"), x: 600 }];
    showWorkspace();
    await waitFor(() => expect(screen.getByTestId("second-card")).toHaveStyle({ width: "300px", height: "15px" }));
    act(() => useCanvasStore.getState().setSelectedIds(["image-card", "second-card"]));
    fireEvent.click(screen.getByRole("button", { name: "左对齐" }));
    await waitFor(() => expect(commands).toHaveLength(2));
    act(() => {
      useCanvasStore.getState().setSelectedIds(["unrelated-card"]);
      useCanvasStore.getState().updateDraft("unrelated-card", { x: 99 });
    });
    finishSave?.();
    await waitFor(() => expect(useCanvasStore.getState().saveState).toBe("saved"));
    expect(useCanvasStore.getState().drafts).toEqual({ "unrelated-card": { x: 99 } });
  });
});
