import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent,render,screen,waitFor } from "@testing-library/react";
import { http,HttpResponse } from "msw";
import { beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { changeControl,clickControl } from "../../test/controls";
import { server } from "../../test/server";
import { BrushMarkupEditor } from "./BrushMarkupEditor";

const context = { clearRect: vi.fn(), save: vi.fn(), restore: vi.fn(), beginPath: vi.fn(),
  moveTo: vi.fn(), lineTo: vi.fn(), stroke: vi.fn(), fill: vi.fn(), arc: vi.fn(),
  closePath: vi.fn(), strokeRect: vi.fn(), fillText: vi.fn(), drawImage: vi.fn() };
const props = { projectId: "project-1", canvasItemId: "item-1", sourceVersionId: "version-1",
  expectedVersion: 2, sourceUrl: "/original.png", sourceTitle: "湖边", onClose: vi.fn() };

function show() {
  const client = createQueryClient();
  const view = render(<QueryClientProvider client={client}><BrushMarkupEditor {...props} /></QueryClientProvider>);
  const image = screen.getByRole("img", { name: "画笔标注原图" });
  Object.defineProperties(image, { naturalWidth: { value: 941 }, naturalHeight: { value: 1672 } });
  const canvas = screen.getByLabelText("图片标注画布");
  vi.spyOn(canvas, "getBoundingClientRect").mockReturnValue({ x: 0, y: 0, left: 0, top: 0,
    right: 200, bottom: 400, width: 200, height: 400, toJSON: () => ({}) });
  fireEvent.load(image);
  return { view, client, canvas };
}
function draw(canvas: HTMLElement) {
  fireEvent.pointerDown(canvas, { button: 0, clientX: 30, clientY: 60 });
  fireEvent.pointerMove(canvas, { clientX: 80, clientY: 120 });
  fireEvent.pointerUp(canvas, { clientX: 90, clientY: 140 });
}
function mockUpload(onUpload: () => void) {
  const fetch = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (input === "/api/v1/projects/project-1/assets") {
      expect(init?.body).toBeInstanceOf(FormData);
      onUpload(); return new Response(JSON.stringify({ id: "marked-asset" }), { status: 201 });
    }
    return fetch(input, init);
  });
}

describe("BrushMarkupEditor", () => {
  beforeEach(() => {
    vi.stubGlobal("PointerEvent", MouseEvent);
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(() =>
      context as unknown as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation((callback) =>
      callback(new window.Blob(["test image bytes"], { type: "image/png" })));
    HTMLCanvasElement.prototype.setPointerCapture = vi.fn();
    HTMLCanvasElement.prototype.releasePointerCapture = vi.fn();
    props.onClose.mockClear();
  });

  it("provides direct drawing tools, branching undo/redo and text without model inputs", async () => {
    const { canvas } = show();
    expect(screen.queryByLabelText("图片能力")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("标注说明")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();
    draw(canvas);
    await clickControl(screen.getByRole("button", { name: "撤销" }));
    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "重做" })).toBeEnabled();
    await clickControl(screen.getByRole("button", { name: "重做" }));
    expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
    await clickControl(screen.getByRole("button", { name: "撤销" }));
    await clickControl(screen.getByRole("button", { name: "矩形" }));
    draw(canvas);
    expect(context.strokeRect).toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "重做" })).toBeDisabled();
    await clickControl(screen.getByRole("button", { name: /^文字$/ }));
    fireEvent.pointerDown(canvas, { button: 0, clientX: 40, clientY: 60 });
    await changeControl(screen.getByRole("textbox", { name: "标注文字" }), { target: { value: "这里" } });
    fireEvent.keyDown(screen.getByRole("textbox", { name: "标注文字" }), { key: "Enter" });
    expect(context.fillText).toHaveBeenCalledWith("这里", expect.any(Number), expect.any(Number));
    expect(screen.getByRole("dialog", { name: "画笔标注" })).toHaveFocus();
    await clickControl(screen.getByRole("button", { name: /^画笔$/ }));
    await clickControl(screen.getByRole("button", { name: /^画笔$/ }));
    expect(screen.getByRole("slider", { name: "笔刷大小" })).toBeInTheDocument();
  });

  it("retries the same archived file and target after a lost response, without an AI request", async () => {
    const requests: unknown[] = []; let uploads = 0;
    mockUpload(() => uploads++);
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/projects/project-1/canvas-items/item-1/upload-version", async ({ request }) => {
        requests.push(await request.json());
        return requests.length === 1 ? HttpResponse.json({ detail: "响应丢失" }, { status: 503 })
          : HttpResponse.json({ id: "derived-node" });
      }),
    );
    const { canvas } = show(); draw(canvas);
    await clickControl(screen.getByRole("button", { name: "保存" }));
    await screen.findByRole("button", { name: "重试保存" });
    expect(screen.getByRole("alert")).toBeInTheDocument();
    await clickControl(screen.getByRole("button", { name: "重试保存" }));
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
    expect(uploads).toBe(1);
    expect(requests).toHaveLength(2);
    expect(requests[0]).toEqual(requests[1]);
    expect(requests[0]).toEqual(expect.objectContaining({ expectedVersion: 2,
      sourceVersionId: "version-1", purpose: "BRUSH_MARKUP",
      content: { sourceType: "UPLOAD", assetId: "marked-asset" } }));
    expect(context.drawImage).toHaveBeenCalledWith(expect.any(HTMLImageElement), 0, 0);
  });

  it("keeps the original editing source through refresh and retains marks on conflict", async () => {
    let requestBody: unknown;
    mockUpload(() => {});
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/projects/project-1/canvas-items/item-1/upload-version", async ({ request }) => {
        requestBody = await request.json(); return HttpResponse.json({ detail: "版本冲突" }, { status: 409 });
      }),
    );
    const { canvas, view, client } = show(); draw(canvas);
    view.rerender(<QueryClientProvider client={client}><BrushMarkupEditor {...props}
      sourceVersionId="version-2" expectedVersion={3} sourceUrl="/new.png" /></QueryClientProvider>);
    expect(screen.getByRole("img", { name: "画笔标注原图" })).toHaveAttribute("src", "/original.png");
    await clickControl(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("来源节点已有更新，标注已保留");
    expect(requestBody).toEqual(expect.objectContaining({ sourceVersionId: "version-1", expectedVersion: 2 }));
    expect(screen.getByRole("button", { name: "撤销" })).toBeEnabled();
    expect(props.onClose).not.toHaveBeenCalled();
  });

  it("supports retrying a failed source load and cancellation without saving", async () => {
    const { canvas } = show(); draw(canvas);
    fireEvent.error(screen.getByRole("img", { name: "画笔标注原图" }));
    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();
    await clickControl(screen.getByRole("button", { name: "重试载入" }));
    expect(screen.getByRole("status")).toHaveTextContent("正在载入原图");
    await clickControl(screen.getByRole("button", { name: "关闭画笔标注" }));
    expect(props.onClose).toHaveBeenCalledOnce();
  });
});
