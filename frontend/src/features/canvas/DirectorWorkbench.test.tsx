import { QueryClientProvider } from "@tanstack/react-query";
import { act,fireEvent,render,screen,waitFor } from "@testing-library/react";
import { http,HttpResponse } from "msw";
import { useEffect } from "react";
import { beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { clickControl,changeControl } from "../../test/controls";
import { imageFunctionsFixture,imageFunctionSettings } from "../../test/imageFunctionsFixture";
import { server } from "../../test/server";
import { DirectorWorkbench } from "./DirectorWorkbench";
import { createDirectorScene,type DirectorObject,type DirectorScene } from "./directorScene";

const viewport = vi.hoisted(() => ({ captures: vi.fn(), fail: false,
  objectChange: null as ((id: string, object: DirectorObject) => void) | null,
  selectCamera: null as (() => void) | null }));
// jsdom tests cover commands and draft retention; a separate browser check exercises actual WebGL.
vi.mock("./DirectorViewport", () => ({ DirectorViewport: ({ scene,viewMode,objectTool,onCaptureReady,onError,onObjectChange,onSelectCamera }: {
  scene: DirectorScene; viewMode?: "director" | "camera";
  objectTool?: "move" | "rotate" | "resize";
  onCaptureReady: (fn: (() => Promise<File>) | null) => void; onError: (message: string | null) => void;
  onObjectChange?: (id: string, object: DirectorObject) => void;
  onSelectCamera?: () => void;
}) => {
  viewport.objectChange = onObjectChange ?? null;
  viewport.selectCamera = onSelectCamera ?? null;
  useEffect(() => {
    if (viewport.fail) { onError("WebGL unavailable"); onCaptureReady(null); return; }
    onCaptureReady(async () => { viewport.captures(); return new File(["synthetic PNG bytes"], "capture.png", { type: "image/png" }); });
    return () => onCaptureReady(null);
  }, [onCaptureReady,onError]);
  return <><output aria-label="scene-state" data-view-mode={viewMode} data-object-tool={objectTool}>{JSON.stringify(scene)}</output>
    <canvas aria-label="resize-test-canvas" /></>;
} }));

const props = { projectId: "project-1", artifactId: "artifact-1", canvasItemId: "item-1", expectedVersion: 2,
  sourceVersionId: "version-1", sourceTitle: "Synthetic reference", sourceUrl: "/synthetic.png", onClose: vi.fn() };
function show(overrides: Omit<Partial<typeof props>, "sourceVersionId"> & { sourceVersionId?: string | null; initialScene?: unknown } = {}) {
  const client = createQueryClient();
  const view = render(<QueryClientProvider client={client}><DirectorWorkbench {...props} {...overrides} /></QueryClientProvider>);
  return { view,client };
}
function mockUpload() {
  let uploads = 0;
  const original = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (input === "/api/v1/projects/project-1/assets") {
      uploads++; expect(init?.body).toBeInstanceOf(FormData);
      return new Response(JSON.stringify({ id: "capture-asset" }), { status: 201 });
    }
    return original(input, init);
  });
  return () => uploads;
}

describe("DirectorWorkbench", () => {
  beforeEach(() => {
    viewport.captures.mockClear(); viewport.fail = false; viewport.objectChange = null; viewport.selectCamera = null; props.onClose.mockClear();
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })));
  });

  it("saves a PNG and scene with a stable target and retries a lost response without capturing or uploading again", async () => {
    const requests: unknown[] = []; const uploads = mockUpload();
    server.use(http.post("/api/v1/projects/project-1/canvas-items/item-1/upload-version", async ({ request }) => {
      requests.push(await request.json());
      return requests.length === 1 ? HttpResponse.json({ detail: "Synthetic response loss" }, { status: 503 }) : HttpResponse.json({ id: "capture-node" });
    }));
    const initial = createDirectorScene();
    show({ initialScene: initial });
    await clickControl(screen.getByRole("tab", { name: "物体" }));
    await changeControl(screen.getByRole("spinbutton", { name: "宽 X (倍)" }), { target: { value: "2" } });
    const readScale = (): DirectorObject["scale"] => JSON.parse(screen.getByLabelText("scene-state").textContent ?? "null").objects[0].scale;
    expect(readScale()).toEqual({ x: 2, y: 1, z: 1 });
    await changeControl(screen.getByRole("spinbutton", { name: "高 Y (倍)" }), { target: { value: "3" } });
    expect(readScale()).toEqual({ x: 2, y: 3, z: 1 });
    await changeControl(screen.getByRole("spinbutton", { name: "深 Z (倍)" }), { target: { value: "0.5" } });
    expect(readScale()).toEqual({ x: 2, y: 3, z: 0.5 });
    const transformed = { ...initial.objects[0]!, scale: { x: 2, y: 3, z: 0.5 }, position: { x: 1, y: 2, z: -3 }, rotation: 45 };
    act(() => viewport.objectChange?.(transformed.id, transformed));
    const expectedScene = { ...initial, objects: [transformed] };
    await waitFor(() => expect(screen.getByRole("button", { name: "拍摄到画布" })).toBeEnabled());
    await clickControl(screen.getByRole("button", { name: "拍摄到画布" }));
    await screen.findByRole("button", { name: "重试保存" });
    expect(screen.getByRole("alert")).toHaveTextContent("请求未完成");
    await clickControl(screen.getByRole("button", { name: "重试保存" }));
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
    expect(requests[0]).toEqual(requests[1]);
    expect(requests[0]).toMatchObject({ purpose: "DIRECTOR_3D", expectedVersion: 2, sourceVersionId: "version-1",
      directorScene: expectedScene, content: { sourceType: "UPLOAD", assetId: "capture-asset" } });
    expect(uploads()).toBe(1); expect(viewport.captures).toHaveBeenCalledOnce();
  });

  it("pins the source across refresh and retains scene changes on CAS conflict", async () => {
    let requestBody: unknown; mockUpload();
    server.use(http.post("/api/v1/projects/project-1/canvas-items/item-1/upload-version", async ({ request }) => {
      requestBody = await request.json(); return HttpResponse.json({ detail: "conflict" }, { status: 409 });
    }));
    const { view,client } = show();
    await changeControl(screen.getByRole("spinbutton", { name: "水平角度 (°)" }), { target: { value: "90" } });
    const scene: DirectorScene = JSON.parse(screen.getByLabelText("scene-state").textContent ?? "null");
    const transformed = { ...scene.objects[0]!, position: { x: 3, y: 1, z: -2 }, rotation: 75 };
    act(() => viewport.objectChange?.(transformed.id, transformed));
    view.rerender(<QueryClientProvider client={client}><DirectorWorkbench {...props} expectedVersion={3} sourceVersionId="version-2" /></QueryClientProvider>);
    await waitFor(() => expect(screen.getByRole("button", { name: "拍摄到画布" })).toBeEnabled());
    await clickControl(screen.getByRole("button", { name: "拍摄到画布" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("来源节点已有更新，当前场景已保留");
    expect(screen.getByRole("spinbutton", { name: "水平角度 (°)" })).toHaveValue(90);
    expect(requestBody).toMatchObject({ sourceVersionId: "version-1", expectedVersion: 2,
      directorScene: { camera: { azimuth: 90 }, objects: [{ position: transformed.position, rotation: 75 }] } });
    await clickControl(screen.getByRole("tab", { name: "物体" }));
    expect(screen.getByRole("spinbutton", { name: "位置 X" })).toHaveValue(3);
    expect(screen.getByRole("spinbutton", { name: "朝向 (°)" })).toHaveValue(75);
    expect(props.onClose).not.toHaveBeenCalled();
  });

  it("fills the same empty image node on its first capture without inventing a parent version", async () => {
    let requestBody: unknown; mockUpload();
    server.use(http.post("/api/v1/projects/project-1/canvas-items/item-1/upload-version", async ({ request }) => {
      requestBody = await request.json(); return HttpResponse.json({ id: "item-1" });
    }));
    show({ sourceVersionId: null, sourceUrl: undefined });
    expect(screen.queryByRole("radio", { name: "图片换视角" })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "拍摄到画布" })).toBeEnabled());
    await clickControl(screen.getByRole("button", { name: "拍摄到画布" }));
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
    expect(requestBody).toMatchObject({ purpose: "DIRECTOR_3D", sourceVersionId: null, targetItemId: "item-1" });
  });

  it("requires explicit discard of unsaved edits and never sends a request when closed", async () => {
    show();
    await clickControl(screen.getByRole("tab", { name: "物体" }));
    await changeControl(screen.getByRole("spinbutton", { name: "位置 X" }), { target: { value: "2" } });
    await clickControl(screen.getByRole("button", { name: "关闭" }));
    expect(screen.getByRole("alert")).toHaveTextContent("场景改动尚未保存");
    await clickControl(screen.getByRole("button", { name: "继续编辑" }));
    expect(props.onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(screen.getByRole("dialog", { name: "3D 导演台" }), { key: "Escape" });
    await clickControl(await screen.findByRole("button", { name: "放弃并关闭" }));
    expect(props.onClose).toHaveBeenCalledOnce(); expect(viewport.captures).not.toHaveBeenCalled();
  });

  it.each(["directorTransforming", "directorResizing"])("lets an active canvas gesture marked %s consume Escape before offering to close", async (marker) => {
    show();
    const canvas = screen.getByLabelText("resize-test-canvas");
    canvas.dataset[marker] = "true";
    fireEvent.keyDown(canvas, { key: "Escape" });
    expect(props.onClose).not.toHaveBeenCalled();
    expect(screen.queryByText("场景改动尚未保存")).not.toBeInTheDocument();
    delete canvas.dataset[marker];
    fireEvent.keyDown(canvas, { key: "Escape" });
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
  });

  it("retains a persisted scene, rejects malformed imports, and blocks capture when WebGL is unavailable", async () => {
    viewport.fail = true;
    const scene = createDirectorScene(); scene.camera.focalLength = 85;
    show({ initialScene: scene });
    await waitFor(() => expect(screen.getByRole("button", { name: "拍摄到画布" })).toBeDisabled());
    expect(screen.getByRole("button", { name: "拍摄到画布" })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "焦距 (mm)" })).toHaveValue(85);
    const input = screen.getByLabelText("导入场景", { selector: "input" });
    const invalid = new File(["{}"], "invalid.json", { type: "application/json" });
    Object.defineProperty(invalid, "text", { value: async () => "{}" });
    fireEvent.change(input, { target: { files: [invalid] } });
    expect(await screen.findByText(/场景文件无效/)).toBeVisible();
    expect(screen.getByRole("spinbutton", { name: "焦距 (mm)" })).toHaveValue(85);
  });

  it("selects objects from the scene list and retains their edits when switching inspector tabs", async () => {
    const initial = createDirectorScene();
    initial.objects[0]!.name = "主角";
    show({ initialScene: initial });
    expect(screen.getByRole("tab", { name: "摄影机" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByRole("spinbutton", { name: "位置 X" })).not.toBeInTheDocument();
    await clickControl(screen.getByRole("button", { name: /主角/ }));
    expect(screen.getByRole("tab", { name: "物体" })).toHaveAttribute("aria-selected", "true");
    await changeControl(screen.getByRole("spinbutton", { name: "位置 X" }), { target: { value: "2" } });
    await clickControl(screen.getByRole("tab", { name: "摄影机" }));
    await clickControl(screen.getByRole("tab", { name: "物体" }));
    expect(screen.getByRole("spinbutton", { name: "位置 X" })).toHaveValue(2);
    expect(JSON.parse(screen.getByLabelText("scene-state").textContent ?? "null")).toMatchObject({
      objects: [{ id: initial.objects[0]!.id, position: { x: 2 } }],
    });
    expect(viewport.captures).not.toHaveBeenCalled();
  });

  it("adds geometry through the object menu and selects its properties without a provider request", async () => {
    show();
    await clickControl(screen.getByRole("button", { name: "添加物体" }));
    await clickControl(await screen.findByRole("menuitem", { name: "方体" }));
    expect(screen.getByRole("tab", { name: "物体" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("textbox", { name: "物体名称" })).toHaveValue("方体 2");
    const state: DirectorScene = JSON.parse(screen.getByLabelText("scene-state").textContent ?? "null");
    expect(state.objects).toHaveLength(2);
    expect(state.objects[1]).toMatchObject({ kind: "BOX", name: "方体 2" });
    expect(state.objects[1]?.scale).toEqual({ x: 1, y: 1, z: 1 });
    expect(viewport.captures).not.toHaveBeenCalled();
    expect(props.onClose).not.toHaveBeenCalled();
  });

  it("switches object tools without changing or submitting the scene and keeps move available without a selection", async () => {
    const submissions = vi.fn();
    server.use(http.post("*", () => { submissions(); return HttpResponse.json({}); }));
    const initial = createDirectorScene();
    show({ initialScene: initial });
    const state = screen.getByLabelText("scene-state");
    expect(state).toHaveAttribute("data-object-tool", "move");
    await clickControl(screen.getByRole("radio", { name: "旋转" }));
    expect(state).toHaveAttribute("data-object-tool", "rotate");
    await clickControl(screen.getByRole("radio", { name: "尺寸" }));
    expect(state).toHaveAttribute("data-object-tool", "resize");
    act(() => viewport.selectCamera?.());
    expect(screen.getByRole("radio", { name: "移动" })).toBeEnabled();
    expect(screen.getByRole("radio", { name: "旋转" })).toBeDisabled();
    expect(screen.getByRole("radio", { name: "尺寸" })).toBeDisabled();
    expect(state).toHaveAttribute("data-object-tool", "move");
    expect(JSON.parse(state.textContent ?? "null")).toEqual(initial);
    expect(submissions).not.toHaveBeenCalled();
    expect(viewport.captures).not.toHaveBeenCalled();
    await clickControl(screen.getByRole("button", { name: "关闭" }));
    expect(props.onClose).toHaveBeenCalledOnce();
  });

  it("applies renderer transforms to their object ID and blocks further edits while the scene is saving", async () => {
    let completeSave: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => { completeSave = resolve; });
    let requestBody: unknown; mockUpload();
    server.use(http.post("/api/v1/projects/project-1/canvas-items/item-1/upload-version", async ({ request }) => {
      requestBody = await request.json(); await gate;
      return HttpResponse.json({ id: "resized-scene" });
    }));
    const initial = createDirectorScene();
    const first = initial.objects[0]!;
    const second: DirectorObject = { ...first, id: "second-object", name: "Second object",
      position: { x: 2, y: 0, z: 0 }, scale: { x: 1, y: 1, z: 1 } };
    initial.objects.push(second);
    show({ initialScene: initial });
    expect(viewport.objectChange).not.toBeNull();
    const resized = { ...second, position: { x: 3, y: 2, z: -1 }, rotation: 45, scale: { x: 2, y: 1.5, z: 0.75 } };
    act(() => viewport.objectChange?.(second.id, resized));
    const expected = { ...initial, objects: [first, structuredClone(resized)] };
    resized.scale.x = 4;
    const readScene = (): DirectorScene => JSON.parse(screen.getByLabelText("scene-state").textContent ?? "null");
    expect(readScene()).toEqual(expected);
    // A stale callback cannot replace another identity under its original ID.
    act(() => viewport.objectChange?.(second.id, first));
    expect(readScene()).toEqual(expected);
    // Invalid pointer math must not poison the draft before capture can validate it.
    for (const invalidScale of [Number.NaN, Number.POSITIVE_INFINITY, 5.01]) {
      act(() => viewport.objectChange?.(second.id, { ...second, scale: { ...second.scale, x: invalidScale } }));
      expect(readScene()).toEqual(expected);
    }
    await clickControl(screen.getByRole("tab", { name: "物体" }));
    expect(screen.getByRole("textbox", { name: "物体名称" })).toHaveValue(first.name);
    expect(screen.getByRole("spinbutton", { name: "宽 X (倍)" })).toHaveValue(1);
    await clickControl(screen.getByRole("button", { name: /Second object/ }));
    expect(screen.getByRole("spinbutton", { name: "位置 Y" })).toHaveValue(2);
    expect(screen.getByRole("spinbutton", { name: "位置 Z" })).toHaveValue(-1);
    expect(screen.getByRole("spinbutton", { name: "朝向 (°)" })).toHaveValue(45);
    await clickControl(screen.getByRole("button", { name: "拍摄到画布" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "关闭" })).toBeDisabled());
    expect(screen.getByRole("spinbutton", { name: "宽 X (倍)" })).toBeDisabled();
    expect(screen.getByRole("radio", { name: "移动" })).toBeDisabled();
    act(() => viewport.objectChange?.(second.id, resized));
    expect(readScene()).toEqual(expected);
    await waitFor(() => expect(requestBody).toMatchObject({ directorScene: expected }));
    completeSave?.();
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
  });

  it("opens a legacy uniform scene with equivalent axes and closes without inventing unsaved changes", async () => {
    const initial = createDirectorScene();
    const legacy = { ...initial, schemaVersion: 1, objects: initial.objects.map((object) => ({ ...object, scale: 1.6 })) };
    show({ initialScene: legacy });
    await clickControl(screen.getByRole("tab", { name: "物体" }));
    for (const label of ["宽 X (倍)", "高 Y (倍)", "深 Z (倍)"]) expect(screen.getByRole("spinbutton", { name: label })).toHaveValue(1.6);
    await clickControl(screen.getByRole("button", { name: "关闭" }));
    expect(props.onClose).toHaveBeenCalledOnce();
    expect(screen.queryByText("场景改动尚未保存")).not.toBeInTheDocument();
    expect(viewport.captures).not.toHaveBeenCalled();
  });

  it("applies camera and lens presets, focuses the selected actor, and keeps guides as a local display preference", async () => {
    const submissions = vi.fn();
    server.use(http.post("*", () => { submissions(); return HttpResponse.json({}); }));
    const initial = createDirectorScene();
    initial.objects[0]!.position = { x: 3, y: 1, z: -2 };
    initial.objects[0]!.scale = { x: 3, y: 2, z: 0.5 };
    show({ initialScene: initial });
    const readScene = (): DirectorScene => JSON.parse(screen.getByLabelText("scene-state").textContent ?? "null");
    expect(screen.getByLabelText("scene-state")).toHaveAttribute("data-view-mode", "director");
    expect(screen.getByRole("button", { name: "构图辅助线" })).toBeDisabled();
    await clickControl(screen.getByRole("radio", { name: "摄影机视图" }));
    expect(screen.getByLabelText("scene-state")).toHaveAttribute("data-view-mode", "camera");
    expect(readScene()).toEqual(initial);
    await clickControl(screen.getByRole("radio", { name: "导演视图" }));
    expect(screen.getByLabelText("scene-state")).toHaveAttribute("data-view-mode", "director");
    expect(readScene()).toEqual(initial);
    expect(submissions).not.toHaveBeenCalled();
    expect(viewport.captures).not.toHaveBeenCalled();

    await clickControl(screen.getByRole("radio", { name: "右侧面" }));
    expect(readScene()).toEqual({ ...initial, camera: { ...initial.camera, azimuth: 90 } });
    await clickControl(screen.getByRole("radio", { name: /85\s*mm/ }));
    expect(readScene()).toEqual({ ...initial, camera: { ...initial.camera, azimuth: 90, focalLength: 85 } });
    await clickControl(screen.getByRole("button", { name: "聚焦选中物体" }));
    const focused = { ...initial, camera: { ...initial.camera, azimuth: 90, focalLength: 85,
      target: { x: 3, y: 2.8, z: -2 } } };
    expect(readScene()).toEqual(focused);

    await clickControl(screen.getByRole("radio", { name: "摄影机视图" }));
    const guides = screen.getByRole("button", { name: "构图辅助线" });
    expect(guides).toBeEnabled();
    expect(guides).toHaveAttribute("aria-pressed", "true");
    await clickControl(guides);
    expect(guides).toHaveAttribute("aria-pressed", "false");
    expect(readScene()).toEqual(focused);
    expect(submissions).not.toHaveBeenCalled();
    expect(viewport.captures).not.toHaveBeenCalled();
  });

  it("uses the existing configured VIEW_ANGLE operation only after explicit generation, with exact source and camera guidance", async () => {
    const requests: unknown[] = [];
    server.use(
      http.get("/api/v1/settings/media-functions", () => HttpResponse.json(imageFunctionSettings())),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(imageFunctionsFixture())),
      http.post("/api/v1/projects/project-1/artifacts/artifact-1/image-operations", async ({ request }) => {
        requests.push(await request.json()); return HttpResponse.json({ id: "angle-task", status: "READY" });
      }),
    );
    show();
    await changeControl(screen.getByRole("spinbutton", { name: "水平角度 (°)" }), { target: { value: "90" } });
    await clickControl(screen.getByRole("radio", { name: "图片换视角" }));
    expect(requests).toHaveLength(0);
    expect(screen.queryByRole("button", { name: "拍摄到画布" })).not.toBeInTheDocument();
    await clickControl(await screen.findByRole("button", { name: "生成换视角图片" }));
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
    expect(requests[0]).toMatchObject({ operation: "VIEW_ANGLE", sourceVersionId: "version-1", canvasItemId: "item-1",
      expectedCanvasItemVersion: 2, parameters: { viewAngle: "RIGHT_PROFILE" } });
    expect(JSON.stringify(requests[0])).toContain("90.0 degrees");
    expect(viewport.captures).not.toHaveBeenCalled();
  });

  it("reuses the pinned angle command and idempotency key after an uncertain response only when explicitly retried", async () => {
    const requests: { key: string | null; body: unknown }[] = [];
    server.use(
      http.get("/api/v1/settings/media-functions", () => HttpResponse.json(imageFunctionSettings())),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(imageFunctionsFixture())),
      http.post("/api/v1/projects/project-1/artifacts/artifact-1/image-operations", async ({ request }) => {
        requests.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
        return requests.length === 1 ? HttpResponse.json({ detail: "Synthetic response loss" }, { status: 503 })
          : HttpResponse.json({ id: "accepted-angle-task", status: "READY" });
      }),
    );
    show();
    await clickControl(screen.getByRole("radio", { name: "图片换视角" }));
    await clickControl(await screen.findByRole("button", { name: "生成换视角图片" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("请求未完成");
    expect(requests).toHaveLength(1);
    expect(props.onClose).not.toHaveBeenCalled();
    await clickControl(screen.getByRole("button", { name: "生成换视角图片" }));
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
    expect(requests).toHaveLength(2);
    expect(requests[0]?.key).toBeTruthy();
    expect(requests[0]).toEqual(requests[1]);
    expect(viewport.captures).not.toHaveBeenCalled();
  });
});
