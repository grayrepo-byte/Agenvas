import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { VideoCameraMotionPreview } from "./VideoCameraMotionPreview";
import { disposeVideoCameraPreview, drawVideoCameraPreview } from "./videoCameraPreviewRenderer";
import { VIDEO_PROMPT_PRESETS } from "./videoPromptPresets";

vi.mock("./videoCameraPreviewRenderer", () => ({ drawVideoCameraPreview: vi.fn(), disposeVideoCameraPreview: vi.fn() }));

const preset = VIDEO_PROMPT_PRESETS.find((item) => item.id === "slow-dolly-in")!;
let queue: Map<number, FrameRequestCallback>;
let sequence: number;
let reduced = false;
let preferenceChanged: ((event: MediaQueryListEvent) => void) | undefined;
let visibilityChanged: ((entries: Pick<IntersectionObserverEntry, "isIntersecting">[]) => void) | undefined;

function tick(time: number) {
  act(() => {
    const callbacks = [...queue.values()];
    queue.clear();
    for (const callback of callbacks) callback(time);
  });
}

beforeEach(() => {
  queue = new Map(); sequence = 0; reduced = false;
  preferenceChanged = undefined; visibilityChanged = undefined;
  vi.mocked(drawVideoCameraPreview).mockClear();
  vi.mocked(disposeVideoCameraPreview).mockClear();
  vi.stubGlobal("requestAnimationFrame", vi.fn((callback: FrameRequestCallback) => { const id = ++sequence; queue.set(id, callback); return id; }));
  vi.stubGlobal("cancelAnimationFrame", vi.fn((id: number) => queue.delete(id)));
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: reduced,
    addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => { preferenceChanged = listener; }, removeEventListener: vi.fn() })));
  vi.stubGlobal("IntersectionObserver", class {
    constructor(callback: (entries: Pick<IntersectionObserverEntry, "isIntersecting">[]) => void) { visibilityChanged = callback; }
    observe() {} disconnect() {}
  });
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({ setTransform: vi.fn() } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, "getBoundingClientRect").mockReturnValue({ width: 320, height: 180 } as DOMRect);
});

afterEach(() => { vi.unstubAllGlobals(); });

describe("VideoCameraMotionPreview", () => {
  it("animates synchronized camera and plan views, then pauses without losing progress", () => {
    render(<VideoCameraMotionPreview preset={preset} />);
    expect(screen.getAllByRole("img")).toHaveLength(2);
    expect(queue.size).toBe(1);
    tick(0); tick(100); tick(200);
    const slider = screen.getByRole("slider", { name: "预览进度" });
    const value = Number(slider.getAttribute("value"));
    expect((slider as HTMLInputElement).valueAsNumber).toBeGreaterThan(0);
    expect(vi.mocked(drawVideoCameraPreview).mock.calls.some((call) => call[7] === "frame")).toBe(true);
    expect(vi.mocked(drawVideoCameraPreview).mock.calls.some((call) => call[7] === "plan")).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "暂停预览" }));
    expect(queue.size).toBe(0);
    expect(screen.getByRole("button", { name: "播放预览" })).toBeInTheDocument();
    expect((slider as HTMLInputElement).valueAsNumber).toBeGreaterThan(value);
  });

  it("scrubs to an exact frame, replays from the beginning and ends after one pass", () => {
    render(<VideoCameraMotionPreview preset={preset} />);
    const slider = screen.getByRole("slider");
    fireEvent.change(slider, { target: { value: "750" } });
    expect(queue.size).toBe(0);
    expect(slider).toHaveAttribute("aria-valuetext", "75%");
    expect(vi.mocked(drawVideoCameraPreview).mock.calls.at(-1)?.[5]).toBe(0.75);
    fireEvent.click(screen.getByRole("button", { name: "从头重播" }));
    expect((slider as HTMLInputElement).value).toBe("0");
    for (let time = 0; time <= 9000; time += 100) tick(time);
    expect((slider as HTMLInputElement).value).toBe("1000");
    expect(queue.size).toBe(0);
    expect(screen.getByRole("button", { name: "播放预览" })).toBeInTheDocument();
  });

  it("respects reduced motion on open and on preference changes while allowing explicit playback", () => {
    reduced = true;
    render(<VideoCameraMotionPreview preset={preset} />);
    expect(queue.size).toBe(0);
    expect(screen.getByText("已按系统偏好暂停，可手动播放或拖动进度。")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "播放预览" }));
    expect(queue.size).toBe(1);
    act(() => preferenceChanged?.({ matches: true } as MediaQueryListEvent));
    expect(queue.size).toBe(0);
  });

  it("suspends off-screen or hidden previews and disposes its animation on unmount", () => {
    const view = render(<VideoCameraMotionPreview preset={preset} />);
    tick(0); tick(100);
    act(() => visibilityChanged?.([{ isIntersecting: false }]));
    expect(queue.size).toBe(0);
    act(() => visibilityChanged?.([{ isIntersecting: true }]));
    expect(queue.size).toBe(1);
    const hidden = vi.spyOn(document, "hidden", "get").mockReturnValue(true);
    fireEvent(document, new Event("visibilitychange"));
    expect(queue.size).toBe(0);
    hidden.mockReturnValue(false);
    fireEvent(document, new Event("visibilitychange"));
    expect(queue.size).toBe(1);
    view.unmount();
    expect(queue.size).toBe(0);
    expect(disposeVideoCameraPreview).toHaveBeenCalledTimes(2);
  });

  it("keeps guidance available when canvas is unavailable and retries the local renderer", () => {
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    render(<VideoCameraMotionPreview preset={preset} />);
    expect(screen.getByRole("status")).toHaveTextContent("当前浏览器无法显示动态示意");
    expect(screen.getByRole("button", { name: "播放预览" })).toBeDisabled();
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({ setTransform: vi.fn() } as unknown as CanvasRenderingContext2D);
    fireEvent.click(screen.getByRole("button", { name: "重试预览" }));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getAllByRole("img")).toHaveLength(2);
  });
});
