import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import * as api from "../../shared/api/client";
import type { MediaCapability } from "../../shared/api/client";
import { SmartEditDialog } from "./SmartEditDialog";

const capability: MediaCapability = {
  id: "model",
  name: "Image model",
  enabled: true,
  version: 0,
  capabilityVersion: 1,
  adapterId: "OPENAI_GPT_IMAGE_2",
  kind: "IMAGE_GENERATION",
  minimumSeconds: 0,
  maximumSeconds: 0,
  maxReferenceAudios: 0,
  maxReferenceVideos: 0,
  maxReferenceImages: 4,
  supportedVideoInputModes: [],
  defaultVideoInputMode: null,
  supportsEndFrame: false,
  supportedImageAspectRatios: ["AUTO"],
  supportedImageResolutions: ["1K"],
  supportedImageQualities: ["high"],
  supportsTransparentBackground: true,
  supportsImageMask: true,
  mappingSha256: "a".repeat(64),
  settings: {},
};
function mount() {
  const onSubmit = vi.fn();
  function Harness() {
    const [open, setOpen] = useState(false);
    return (
      <>
        <button onClick={() => setOpen(true)}>打开编辑</button>
        <button>背景操作</button>
        {open ? (
          <SmartEditDialog
            projectId="project"
            sourceVersionId="source"
            sourceTitle="Image"
            sourceUrl="/image.png"
            capabilities={[capability]}
            busy={false}
            error={null}
            onClose={() => setOpen(false)}
            onSubmit={onSubmit}
          />
        ) : null}
      </>
    );
  }
  render(
    <QueryClientProvider client={createQueryClient()}>
      <Harness />
    </QueryClientProvider>,
  );
}
it("isolates keyboard focus, closes only the model menu on Escape and retains the instruction", async () => {
  mount();
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "打开编辑" }));
  const dialog = screen.getByRole("dialog", { name: "智能编辑图片" });
  expect(dialog).toContainElement(document.activeElement as HTMLElement);
  await user.tab();
  expect(dialog).toContainElement(document.activeElement as HTMLElement);
  await user.type(screen.getByRole("textbox", { name: "智能编辑提示词" }), "保留输入");
  await user.click(screen.getByRole("combobox", { name: "图片能力" }));
  await user.keyboard("{Escape}");
  expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "智能编辑提示词" })).toHaveValue("保留输入");
  await user.click(screen.getByRole("button", { name: "退出智能编辑" }));
  const confirmation = await screen.findByRole("dialog", { name: "有未保存的修改" });
  await user.click(within(confirmation).getByRole("button", { name: "继续编辑" }));
  expect(screen.getByRole("textbox", { name: "智能编辑提示词" })).toHaveValue("保留输入");
  await user.click(screen.getByRole("button", { name: "退出智能编辑" }));
  await user.click(screen.getByRole("button", { name: "放弃修改" }));
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "打开编辑" })).toHaveFocus();
});
it("restores focus after closing a clean editor with Escape", async () => {
  mount();
  const user = userEvent.setup();
  const trigger = screen.getByRole("button", { name: "打开编辑" });
  await user.click(trigger);
  await user.keyboard("{Escape}");
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(trigger).toHaveFocus();
});

it("offers native markup only for a supported configured capability", async () => {
  const client = createQueryClient();
  const { rerender } = render(
    <QueryClientProvider client={client}>
      <SmartEditDialog
        projectId="project"
        sourceVersionId="source"
        sourceTitle="Image"
        sourceUrl="/image.png"
        capabilities={[{ ...capability, supportsImageMarkup: true }]}
        busy={false}
        error={null}
        onClose={vi.fn()}
        onSubmit={vi.fn()}
      />
    </QueryClientProvider>,
  );
  expect(screen.getByRole("button", { name: "标注引导" })).toBeEnabled();
  await userEvent.click(screen.getByRole("button", { name: "标注引导" }));
  expect(screen.getByText(/标注作为独立透明图层/)).toBeInTheDocument();
  rerender(
    <QueryClientProvider client={client}>
      <SmartEditDialog
        projectId="project"
        sourceVersionId="source"
        sourceTitle="Image"
        sourceUrl="/image.png"
        capabilities={[capability]}
        busy={false}
        error={null}
        onClose={vi.fn()}
        onSubmit={vi.fn()}
      />
    </QueryClientProvider>,
  );
  expect(screen.getByRole("button", { name: "开始智能编辑" })).toBeDisabled();
});

afterEach(() => vi.restoreAllMocks());

function drawLayer(onSubmit: (input: unknown) => void, markup = false) {
  let hasInk = false;
  const pixels = () => ({
    data: new Uint8ClampedArray(hasInk ? [255, 0, 0, 255, 0, 0, 0, 0] : [0, 0, 0, 0, 0, 0, 0, 0]),
    width: 2,
    height: 1,
    colorSpace: "srgb" as const,
  });
  const context = {
    clearRect: () => {
      hasInk = false;
    },
    getImageData: pixels,
    putImageData: vi.fn(),
    beginPath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    stroke: () => {
      hasInk = true;
    },
    createImageData: pixels,
    drawImage: vi.fn(),
  };
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(context as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, "getBoundingClientRect").mockReturnValue({
    x: 0,
    y: 0,
    left: 0,
    top: 0,
    width: 2,
    height: 1,
    right: 2,
    bottom: 1,
    toJSON: () => ({}),
  });
  HTMLCanvasElement.prototype.setPointerCapture = vi.fn();
  HTMLCanvasElement.prototype.hasPointerCapture = () => false;
  render(
    <QueryClientProvider client={createQueryClient()}>
      <SmartEditDialog
        projectId="project"
        sourceVersionId="source"
        sourceTitle="Image"
        sourceUrl="/image.png"
        capabilities={[{ ...capability, supportsImageMarkup: true }]}
        busy={false}
        error={null}
        onClose={vi.fn()}
        onSubmit={onSubmit}
      />
    </QueryClientProvider>,
  );
  const image = screen.getByAltText("Image 的智能编辑预览");
  Object.defineProperties(image, { naturalWidth: { value: 2 }, naturalHeight: { value: 1 } });
  fireEvent.load(image);
  return { canvas: screen.getByLabelText("智能编辑蒙版画布"), markup };
}

it("stops when a drawn mask cannot export instead of submitting an unmasked edit", async () => {
  const onSubmit = vi.fn();
  const { canvas } = drawLayer(onSubmit);
  vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(callback => callback(null));
  fireEvent.pointerDown(canvas, { clientX: 0, clientY: 0 });
  fireEvent.pointerUp(canvas);
  await userEvent.type(screen.getByRole("textbox", { name: "智能编辑提示词" }), "blue vase");
  await userEvent.click(screen.getByRole("button", { name: "开始智能编辑" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("导出");
  expect(onSubmit).not.toHaveBeenCalled();
});

it.each([false, true])("reuses the uploaded layer after a lost submission response (markup=%s)", async markup => {
  const onSubmit = vi.fn();
  const { canvas } = drawLayer(onSubmit, markup);
  if (markup) await userEvent.click(screen.getByRole("button", { name: "标注引导" }));
  vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(callback =>
    callback(new Blob(["synthetic"], { type: "image/png" })),
  );
  const upload = vi
    .spyOn(api, "uploadImageAsset")
    .mockResolvedValue({ id: "layer" } as Awaited<ReturnType<typeof api.uploadImageAsset>>);
  fireEvent.pointerDown(canvas, { clientX: 0, clientY: 0 });
  fireEvent.pointerUp(canvas);
  await userEvent.type(screen.getByRole("textbox", { name: "智能编辑提示词" }), "blue vase");
  await userEvent.click(screen.getByRole("button", { name: "开始智能编辑" }));
  await userEvent.click(screen.getByRole("button", { name: "开始智能编辑" }));
  expect(onSubmit).toHaveBeenCalledTimes(2);
  expect(onSubmit.mock.calls[1]).toEqual(onSubmit.mock.calls[0]);
  expect(upload).toHaveBeenCalledTimes(1);
  expect(onSubmit.mock.calls[0]![0]).toMatchObject(markup ? { markupAssetId: "layer" } : { maskAssetId: "layer" });
});

it("locks the drawing and history while the upload is in flight", async () => {
  const onSubmit = vi.fn();
  const { canvas } = drawLayer(onSubmit);
  vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(callback =>
    callback(new Blob(["synthetic"], { type: "image/png" })),
  );
  let finish: ((value: Awaited<ReturnType<typeof api.uploadImageAsset>>) => void) | undefined;
  vi.spyOn(api, "uploadImageAsset").mockImplementation(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  fireEvent.pointerDown(canvas, { clientX: 0, clientY: 0 });
  fireEvent.pointerUp(canvas);
  await userEvent.type(screen.getByRole("textbox", { name: "智能编辑提示词" }), "blue vase");
  await userEvent.click(screen.getByRole("button", { name: "开始智能编辑" }));
  expect(screen.getByRole("button", { name: "撤销蒙版" })).toBeDisabled();
  expect(screen.getByRole("textbox", { name: "智能编辑提示词" })).toBeDisabled();
  finish?.({ id: "layer" } as Awaited<ReturnType<typeof api.uploadImageAsset>>);
  await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
});
