import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent,render,screen,waitFor } from "@testing-library/react";
import { http,HttpResponse } from "msw";
import { useEffect } from "react";
import { beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { clickControl,changeControl } from "../../test/controls";
import { imageFunctionsFixture,imageFunctionSettings } from "../../test/imageFunctionsFixture";
import { server } from "../../test/server";
import { DirectorWorkbench } from "./DirectorWorkbench";
import { createDirectorScene,type DirectorScene } from "./directorScene";

const viewport = vi.hoisted(() => ({ captures: vi.fn(), fail: false }));
// jsdom tests cover commands and draft retention; a separate browser check exercises actual WebGL.
vi.mock("./DirectorViewport", () => ({ DirectorViewport: ({ scene,onCaptureReady,onError }: {
  scene: DirectorScene; onCaptureReady: (fn: (() => Promise<File>) | null) => void; onError: (message: string | null) => void;
}) => {
  useEffect(() => {
    if (viewport.fail) { onError("WebGL unavailable"); onCaptureReady(null); return; }
    onCaptureReady(async () => { viewport.captures(); return new File(["synthetic PNG bytes"], "capture.png", { type: "image/png" }); });
    return () => onCaptureReady(null);
  }, [onCaptureReady,onError]);
  return <output aria-label="scene-state">{JSON.stringify(scene)}</output>;
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
    viewport.captures.mockClear(); viewport.fail = false; props.onClose.mockClear();
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
    await waitFor(() => expect(screen.getByRole("button", { name: "拍摄到画布" })).toBeEnabled());
    await clickControl(screen.getByRole("button", { name: "拍摄到画布" }));
    await screen.findByRole("button", { name: "重试保存" });
    expect(screen.getByRole("alert")).toHaveTextContent("请求未完成");
    await clickControl(screen.getByRole("button", { name: "重试保存" }));
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
    expect(requests[0]).toEqual(requests[1]);
    expect(requests[0]).toMatchObject({ purpose: "DIRECTOR_3D", expectedVersion: 2, sourceVersionId: "version-1",
      directorScene: initial, content: { sourceType: "UPLOAD", assetId: "capture-asset" } });
    expect(uploads()).toBe(1); expect(viewport.captures).toHaveBeenCalledOnce();
  });

  it("pins the source across refresh and retains scene changes on CAS conflict", async () => {
    let requestBody: unknown; mockUpload();
    server.use(http.post("/api/v1/projects/project-1/canvas-items/item-1/upload-version", async ({ request }) => {
      requestBody = await request.json(); return HttpResponse.json({ detail: "conflict" }, { status: 409 });
    }));
    const { view,client } = show();
    await changeControl(screen.getByRole("spinbutton", { name: "水平角度 (°)" }), { target: { value: "90" } });
    view.rerender(<QueryClientProvider client={client}><DirectorWorkbench {...props} expectedVersion={3} sourceVersionId="version-2" /></QueryClientProvider>);
    await waitFor(() => expect(screen.getByRole("button", { name: "拍摄到画布" })).toBeEnabled());
    await clickControl(screen.getByRole("button", { name: "拍摄到画布" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("来源节点已有更新，当前场景已保留");
    expect(screen.getByRole("spinbutton", { name: "水平角度 (°)" })).toHaveValue(90);
    expect(requestBody).toMatchObject({ sourceVersionId: "version-1", expectedVersion: 2, directorScene: { camera: { azimuth: 90 } } });
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
    await changeControl(screen.getByRole("spinbutton", { name: "位置 X" }), { target: { value: "2" } });
    await clickControl(screen.getByRole("button", { name: "关闭" }));
    expect(screen.getByRole("alert")).toHaveTextContent("场景改动尚未保存");
    await clickControl(screen.getByRole("button", { name: "继续编辑" }));
    expect(props.onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(screen.getByRole("dialog", { name: "3D 导演台" }), { key: "Escape" });
    await clickControl(await screen.findByRole("button", { name: "放弃并关闭" }));
    expect(props.onClose).toHaveBeenCalledOnce(); expect(viewport.captures).not.toHaveBeenCalled();
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
    await clickControl(await screen.findByRole("button", { name: "生成换视角图片" }));
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
    expect(requests[0]).toMatchObject({ operation: "VIEW_ANGLE", sourceVersionId: "version-1", canvasItemId: "item-1",
      expectedCanvasItemVersion: 2, parameters: { viewAngle: "RIGHT_PROFILE" } });
    expect(JSON.stringify(requests[0])).toContain("90.0 degrees");
    expect(viewport.captures).not.toHaveBeenCalled();
  });
});
