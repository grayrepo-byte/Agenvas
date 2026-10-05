import { fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach,beforeEach,describe,expect,it,vi } from "vitest";
import { MediaPreviewDialog } from "./MediaPreviewDialog";

const IMAGE_URL = "/synthetic-preview.png";
const VIDEO_URL = "/synthetic-preview.webm";
const IMAGE_WIDTH = 2400;
const IMAGE_HEIGHT = 1600;
const VIEWPORT_WIDTH = 1024;
const VIEWPORT_HEIGHT = 640;

function PreviewHarness({ kind = "image",onCanvasEvent = () => {},onClose = () => {} }: {
  kind?: "image" | "video"; onCanvasEvent?: () => void; onClose?: () => void;
}) {
  const [open, setOpen] = useState(false);
  return <div onKeyDown={onCanvasEvent} onKeyUp={onCanvasEvent} onPointerDown={onCanvasEvent}
    onMouseDown={onCanvasEvent} onWheel={onCanvasEvent} onClick={onCanvasEvent} onDoubleClick={onCanvasEvent}>
    <button onClick={() => setOpen(true)}>打开预览</button>
    {open ? <MediaPreviewDialog kind={kind} title="合成素材" sourceUrl={kind === "image" ? IMAGE_URL : VIDEO_URL}
      posterSrc={kind === "video" ? IMAGE_URL : undefined} contentType={kind === "video" ? "video/webm" : undefined}
      width={IMAGE_WIDTH} height={IMAGE_HEIGHT} onClose={() => { onClose(); setOpen(false); }} /> : null}
  </div>;
}

const fullscreenDescriptor = Object.getOwnPropertyDescriptor(document, "fullscreenEnabled");

beforeEach(() => {
  // Exercise the real lightbox with a deterministic viewport; jsdom has no layout.
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(VIEWPORT_WIDTH);
  vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(VIEWPORT_HEIGHT);
  Object.defineProperty(document, "fullscreenEnabled", { configurable: true, value: true });
});

afterEach(() => {
  if (fullscreenDescriptor) Object.defineProperty(document, "fullscreenEnabled", fullscreenDescriptor);
  else Reflect.deleteProperty(document, "fullscreenEnabled");
  Reflect.deleteProperty(document, "fullscreenElement");
  Reflect.deleteProperty(HTMLElement.prototype, "requestFullscreen");
  Reflect.deleteProperty(document, "exitFullscreen");
});

async function openPreview() {
  const user = userEvent.setup();
  const trigger = screen.getByRole("button", { name: "打开预览" });
  await user.click(trigger);
  const dialog = await screen.findByRole("dialog");
  return { user,trigger,dialog };
}

describe("MediaPreviewDialog", () => {
  it("renders the real YARL image viewer with zoom and no single-slide navigation", async () => {
    render(<PreviewHarness />);
    const { user,dialog } = await openPreview();
    expect(dialog).toHaveAttribute("aria-label", "合成素材 的图片预览");
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveClass("yarl__portal");
    expect(within(dialog).getByRole("img", { name: "合成素材" })).toHaveAttribute("src", IMAGE_URL);
    expect(within(dialog).getByRole("status", { name: "正在加载预览" })).toBeInTheDocument();
    expect(dialog.querySelector(".yarl__navigation_prev,.yarl__navigation_next")).toBeNull();
    const zoomIn = within(dialog).getByRole("button", { name: "放大" });
    expect(zoomIn).toBeEnabled();
    expect(within(dialog).getByRole("button", { name: "缩小" })).toBeDisabled();
    await user.click(zoomIn);
    expect(within(dialog).getByRole("img").closest(".yarl__slide_wrapper"))
      .toHaveStyle({ transform: "scale(2) translateX(0px) translateY(0px)" });
    expect(within(dialog).getByRole("button", { name: "缩小" })).toBeEnabled();
  });

  it("retries a failed image and keeps focus in the existing dialog", async () => {
    render(<PreviewHarness />);
    const { user,dialog } = await openPreview();
    const image = within(dialog).getByRole("img");
    fireEvent.error(image);
    expect(within(dialog).getByRole("alert")).toHaveTextContent("媒体加载失败，请重试");
    await user.click(within(dialog).getByRole("button", { name: "重试加载" }));
    expect(screen.getByRole("dialog")).toBe(dialog);
    expect(within(dialog).getByRole("button", { name: "关闭预览" })).toHaveFocus();
    const retriedImage = within(dialog).getByRole("img");
    expect(retriedImage).not.toBe(image);
    expect(retriedImage).toHaveAttribute("src", IMAGE_URL);
    fireEvent.load(retriedImage);
    await waitFor(() => expect(within(dialog).queryByRole("status")).not.toBeInTheDocument());
    expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
  });

  it("uses the Video plugin with native controls and metadata preload without autoplay", async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
    render(<PreviewHarness kind="video" />);
    const { dialog } = await openPreview();
    expect(dialog).toHaveAttribute("aria-label", "合成素材 的视频预览");
    const video = dialog.querySelector("video");
    expect(video).toHaveAttribute("controls");
    expect(video).toHaveAttribute("preload", "metadata");
    expect(video).toHaveAttribute("playsinline");
    expect(video).not.toHaveAttribute("autoplay");
    expect(video).toHaveAttribute("poster", IMAGE_URL);
    expect(video?.querySelector("source")).toHaveAttribute("src", VIDEO_URL);
    expect(video?.querySelector("source")).toHaveAttribute("type", "video/webm");
    expect(within(dialog).queryByRole("button", { name: "放大" })).not.toBeInTheDocument();
    expect(play).not.toHaveBeenCalled();
    fireEvent.loadedMetadata(video!);
    expect(within(dialog).queryByRole("status")).not.toBeInTheDocument();
    expect(Number(video?.getAttribute("width")) / Number(video?.getAttribute("height")))
      .toBeCloseTo(IMAGE_WIDTH / IMAGE_HEIGHT);
  });

  it("retries native video failures with a new player and the same precise source", async () => {
    render(<PreviewHarness kind="video" />);
    const { user,dialog } = await openPreview();
    const video = dialog.querySelector("video");
    fireEvent.error(video!);
    expect(within(dialog).getByRole("alert")).toHaveTextContent("媒体加载失败，请重试");
    await user.click(within(dialog).getByRole("button", { name: "重试加载" }));
    const retriedVideo = dialog.querySelector("video");
    expect(retriedVideo).not.toBe(video);
    expect(retriedVideo?.querySelector("source")).toHaveAttribute("src", VIDEO_URL);
    expect(retriedVideo).not.toHaveAttribute("autoplay");
    fireEvent.loadedMetadata(retriedVideo!);
    expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("status")).not.toBeInTheDocument();
  });

  it("stops active video playback as closing starts", async () => {
    const pause = vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
    const onClose = vi.fn();
    render(<PreviewHarness kind="video" onClose={onClose} />);
    const { user,dialog } = await openPreview();
    const video = dialog.querySelector("video")!;
    Object.defineProperty(video, "paused", { configurable: true, value: false });
    fireEvent.play(video);
    expect(pause).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole("button", { name: "关闭预览" }));
    expect(pause).toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("contains keyboard and pointer events in the portal, cycles focus and restores the trigger", async () => {
    const onCanvasEvent = vi.fn();
    const documentKey = vi.fn();
    render(<PreviewHarness onCanvasEvent={onCanvasEvent} />);
    const { user,trigger,dialog } = await openPreview();
    onCanvasEvent.mockClear();
    const close = within(dialog).getByRole("button", { name: "关闭预览" });
    await waitFor(() => expect(close).toHaveFocus());
    document.addEventListener("keydown", documentKey);
    try {
      for (const key of ["Delete", "Backspace", " ", "v"]) fireEvent.keyDown(close, { key });
      fireEvent.keyUp(close, { key: "Delete" });
      const image = within(dialog).getByRole("img");
      fireEvent.pointerDown(image, { clientX: 0,clientY: 0 });
      fireEvent.mouseDown(image);
      fireEvent.wheel(image, { deltaY: 1 });
      fireEvent.click(image);
      fireEvent.doubleClick(image);
      expect(onCanvasEvent).not.toHaveBeenCalled();
      expect(documentKey).not.toHaveBeenCalled();
      await user.tab();
      expect(dialog.contains(document.activeElement)).toBe(true);
      await user.tab({ shift: true });
      expect(close).toHaveFocus();
      await user.keyboard("{Escape}");
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(trigger).toHaveFocus();
    } finally {
      document.removeEventListener("keydown", documentKey);
    }
  });

  it("uses the Fullscreen plugin on demand and offers an exit action", async () => {
    const request = vi.fn(function (this: HTMLElement) {
      Object.defineProperty(document, "fullscreenElement", { configurable: true, value: this });
      document.dispatchEvent(new Event("fullscreenchange"));
      return Promise.resolve();
    });
    const exit = vi.fn(() => {
      Object.defineProperty(document, "fullscreenElement", { configurable: true, value: null });
      document.dispatchEvent(new Event("fullscreenchange"));
      return Promise.resolve();
    });
    Object.defineProperty(HTMLElement.prototype, "requestFullscreen", { configurable: true, value: request });
    Object.defineProperty(document, "exitFullscreen", { configurable: true, value: exit });
    render(<PreviewHarness />);
    const { user,dialog } = await openPreview();
    expect(request).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole("button", { name: "进入全屏" }));
    expect(request).toHaveBeenCalledOnce();
    await user.click(within(dialog).getByRole("button", { name: "退出全屏" }));
    expect(exit).toHaveBeenCalledOnce();
    expect(within(dialog).getByRole("button", { name: "进入全屏" })).toBeInTheDocument();
  });
});
