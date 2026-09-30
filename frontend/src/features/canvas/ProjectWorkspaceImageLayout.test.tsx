import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { Node, ReactFlowProps, ResizeParams } from "@xyflow/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasCommand, CanvasItem, ImageGenerationParameters, SaveMediaDraftRequest } from "../../shared/api/client";
import { server } from "../../test/server";
import { useCanvasStore } from "./canvasStore";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";
import { MediaDraftEditor } from "./MediaDraftEditor";

type TestNode = Node<{ onResizeEnd: (id: string, layout: Pick<ResizeParams, "x" | "y" | "width" | "height">) => void }>;

// Exercise the real workspace projection and HTTP save paths without emulating a browser's pointer geometry.
vi.mock("@xyflow/react", async (importOriginal) => ({
  ...await importOriginal<typeof import("@xyflow/react")>(),
  Background: () => null,
  MiniMap: () => null,
  Controls: () => null,
  NodeToolbar: ({ children, isVisible }: { children: React.ReactNode; isVisible: boolean }) => isVisible ? children : null,
  ReactFlow: ({ nodes = [], onNodeDragStop, onNodesChange, onNodeClick, children }: ReactFlowProps<TestNode>) => <div>
    <button onClick={() => onNodesChange?.(nodes.map((node) =>
      ({ id: node.id, type: "select", selected: false })))}>canvas background</button>
    {nodes.map((node) => <div key={node.id} data-testid={node.id} style={node.style}>
      <button onClick={(event) => {
        onNodesChange?.([{ id: node.id, type: "select", selected: true }]);
        onNodeClick?.(event, node);
      }}>select {node.id}</button>
      <button onClick={(event) => onNodeDragStop?.(event.nativeEvent,
        { ...node, position: { x: 140, y: 180 } },
        [{ ...node, position: { x: 140, y: 180 } }])}>drag {node.id}</button>
      <button onClick={() => node.data.onResizeEnd(node.id,
        { x: 10, y: 30, width: 500, height: 25 })}>resize {node.id}</button>
      <button onClick={() => onNodesChange?.([{ type: "dimensions", id: node.id,
        dimensions: { width: 999, height: 999 } }])}>measure {node.id}</button>
    </div>)}
    {children}
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
  let parameters: ImageGenerationParameters;
  let draftSaves: SaveMediaDraftRequest[];

  beforeEach(() => {
    items = [imageItem()]; commands = []; conflict = false; draftMode = "RESULT"; metadataReads = 0;
    saveGate = null;
    parameters = {}; draftSaves = [];
    useCanvasStore.setState({ drafts: {}, imageRatioDrafts: {}, mediaDraftRecoveries: {}, selectedIds: [], saveState: "saved" });
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
        version: 0, parameters: params.canvasItemId === "image-card" ? parameters : {}, durationSeconds: null, capabilityId: null,
        videoInputMode: null, mediaInputs: [], mentions: [],
        createdAt: NOW, updatedAt: NOW,
      })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        connections: [{ id: "mock", name: "Mock", platform: "MOCK", enabled: true,
          capabilities: [{ id: "mock-image", name: "Mock 图片", adapterId: "MOCK_IMAGE",
            enabled: true, kind: "IMAGE_GENERATION", settings: {}, maxReferenceImages: 0,
            supportedImageAspectRatios: ["AUTO", "1:1", "9:16", "16:9"],
            supportedImageResolutions: ["1K"], supportedImageQualities: [],
            supportsTransparentBackground: false }] }],
        defaults: [{ kind: "IMAGE_GENERATION", capabilityId: "mock-image" }],
      })),
      http.get("/api/v1/projects/project-1/artifacts", () => HttpResponse.json({ items: [] })),
      http.get("/api/v1/projects/project-1/artifacts/:artifactId/run", () => HttpResponse.json([])),
      http.put("/api/v1/projects/project-1/canvas-items/:canvasItemId/media-draft", async ({ request, params }) => {
        const input = await request.json() as SaveMediaDraftRequest;
        draftSaves.push(input);
        if (saveGate) await saveGate;
        if (conflict) return HttpResponse.json({ code: "VERSION_CONFLICT", detail: "草稿冲突" }, { status: 409 });
        parameters = input.parameters;
        return HttpResponse.json({ ...input, canvasItemId: params.canvasItemId, projectId: "project-1",
          displayMode: draftMode, version: input.expectedVersion + 1, createdAt: NOW, updatedAt: NOW });
      }),
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

  function showWorkspace(withEditor = false) {
    const client = createQueryClient();
    client.setDefaultOptions({ queries: { retry: false, staleTime: Infinity } });
    const view = render(<QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/projects/project-1"]}>
        <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
      </MemoryRouter>
      {withEditor && items[0]?.artifact ? <MediaDraftEditor artifact={items[0].artifact} canvasItemId={items[0].id} /> : null}
    </QueryClientProvider>);
    return { client, ...view };
  }

  it.each(["canvas background", "关闭编辑区"])("saves a ratio when %s closes the editor before the autosave delay", async (closeButton) => {
    const view = showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "15px" }));
    fireEvent.click(screen.getByRole("button", { name: "select image-card" }));
    fireEvent.click(await screen.findByRole("button", { name: "尺寸与画质" }));
    fireEvent.click(within(screen.getByRole("dialog", { name: "尺寸与画质设置" }))
      .getByRole("button", { name: "9:16" }));
    expect(draftSaves).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: closeButton }));
    expect(screen.queryByRole("dialog", { name: "尺寸与画质设置" })).not.toBeInTheDocument();
    expect(screen.getByTestId("image-card")).toHaveStyle({ width: "168.75px", height: "300px" });
    await waitFor(() => expect(parameters.aspectRatio).toBe("9:16"));
    expect(draftSaves).toHaveLength(1);
    view.unmount();
    showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "168.75px", height: "300px" }));
  });

  it.each(["9:16", "1:1"])("closes an in-flight save with final ratio %s without duplicate or stale writes", async (finalRatio) => {
    let finishSave: (() => void) | undefined;
    saveGate = new Promise<void>((resolve) => { finishSave = resolve; });
    showWorkspace();
    await screen.findByTestId("image-card");
    fireEvent.click(screen.getByRole("button", { name: "select image-card" }));
    fireEvent.click(await screen.findByRole("button", { name: "尺寸与画质" }));
    const choices = screen.getByRole("dialog", { name: "尺寸与画质设置" });
    fireEvent.click(within(choices).getByRole("button", { name: "9:16" }));
    await waitFor(() => expect(draftSaves).toHaveLength(1));
    fireEvent.click(within(choices).getByRole("button", { name: finalRatio }));
    fireEvent.click(screen.getByRole("button", { name: "canvas background" }));
    const finalSize = finalRatio === "1:1" ? { width: "300px", height: "300px" }
      : { width: "168.75px", height: "300px" };
    expect(screen.getByTestId("image-card")).toHaveStyle(finalSize);
    fireEvent.click(screen.getByRole("button", { name: "select image-card" }));
    await screen.findByText("正在保存工作草稿");
    expect(draftSaves).toHaveLength(1);
    finishSave?.();
    await screen.findByRole("button", { name: "尺寸与画质" });
    await waitFor(() => expect(parameters.aspectRatio).toBe(finalRatio));
    expect(draftSaves.map((save) => save.expectedVersion)).toEqual(finalRatio === "1:1" ? [0, 1] : [0]);
    expect(screen.getByTestId("image-card")).toHaveStyle(finalSize);
    expect(useCanvasStore.getState().mediaDraftRecoveries).toEqual({});
  });

  it("keeps a failed close-save ratio and restores the complete draft on reopening", async () => {
    conflict = true;
    showWorkspace();
    await screen.findByTestId("image-card");
    fireEvent.click(screen.getByRole("button", { name: "select image-card" }));
    const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
    prompt.textContent = "保留提示词";
    fireEvent.input(prompt);
    fireEvent.click(screen.getByRole("button", { name: "尺寸与画质" }));
    fireEvent.click(within(screen.getByRole("dialog", { name: "尺寸与画质设置" }))
      .getByRole("button", { name: "9:16" }));
    fireEvent.click(screen.getByRole("button", { name: "canvas background" }));
    await waitFor(() => expect(useCanvasStore.getState().mediaDraftRecoveries["project-1:image-card"]?.saving).toBe(false));
    expect(screen.getByTestId("image-card")).toHaveStyle({ width: "168.75px", height: "300px" });
    fireEvent.click(screen.getByRole("button", { name: "select image-card" }));
    await screen.findByText("保存失败，本地输入已保留");
    expect(screen.getByRole("textbox", { name: "图片提示词" })).toHaveTextContent("保留提示词");
    expect(draftSaves).toHaveLength(1);
    conflict = false;
    fireEvent.click(screen.getByRole("button", { name: "重新读取版本" }));
    await waitFor(() => expect(parameters.aspectRatio).toBe("9:16"));
    expect(draftSaves.at(-1)?.prompt).toBe("保留提示词");
  });

  it("immediately projects ratio edits before saving, retains failed edits, and restores AUTO to the original", async () => {
    conflict = true;
    showWorkspace(true);
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "15px" }));
    fireEvent.click(await screen.findByRole("button", { name: "尺寸与画质" }));
    const choices = screen.getByRole("dialog", { name: "尺寸与画质设置" });
    fireEvent.click(within(choices).getByRole("button", { name: "9:16" }));
    expect(draftSaves).toEqual([]);
    expect(screen.getByTestId("image-card")).toHaveStyle({ width: "168.75px", height: "300px" });
    await screen.findByText("保存失败，本地输入已保留");
    expect(screen.getByTestId("image-card")).toHaveStyle({ width: "168.75px", height: "300px" });
    fireEvent.click(within(choices).getByRole("button", { name: "自动" }));
    expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "15px" });
    expect(commands).toEqual([]);
  });

  it("uses saved ratios on draft placeholders after reload without changing other nodes", async () => {
    parameters = { aspectRatio: "16:9" }; draftMode = "DRAFT";
    items.push(imageItem("second-card"));
    const view = showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "168.75px" }));
    expect(screen.getByTestId("second-card")).toHaveStyle({ width: "225px", height: "300px" });
    expect(commands).toEqual([]);
    view.unmount();
    showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "168.75px" }));
  });

  it("previews an empty node immediately, saves its ratio, and keeps it after the editor unmounts", async () => {
    items = [{ ...imageItem(), selectedVersionId: null, selectedVersion: null }];
    let finishSave: (() => void) | undefined;
    saveGate = new Promise<void>((resolve) => { finishSave = resolve; });
    const view = showWorkspace(true);
    fireEvent.click(await screen.findByRole("button", { name: "尺寸与画质" }));
    fireEvent.click(within(screen.getByRole("dialog", { name: "尺寸与画质设置" }))
      .getByRole("button", { name: "1:1" }));
    expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "300px" });
    await waitFor(() => expect(draftSaves).toHaveLength(1));
    expect(draftSaves[0]?.parameters.aspectRatio).toBe("1:1");
    finishSave?.();
    await waitFor(() => expect(screen.getByText("已保存", { selector: ".media-draft-save-state" })).toBeInTheDocument());
    view.unmount();
    expect(useCanvasStore.getState().imageRatioDrafts).toEqual({});
    showWorkspace();
    await waitFor(() => expect(screen.getByTestId("image-card")).toHaveStyle({ width: "300px", height: "300px" }));
    expect(metadataReads).toBe(0);
    expect(commands).toEqual([]);
  });

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
