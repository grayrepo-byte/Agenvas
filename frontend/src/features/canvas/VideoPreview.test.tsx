import { act,fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import { afterEach,beforeEach,describe,expect,it,vi } from "vitest";
import { VideoPreview } from "./VideoPreview";

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
});

afterEach(() => vi.useRealTimers());

function showPreview() {
  const onClick = vi.fn();
  const onMouseDown = vi.fn();
  const onPointerDown = vi.fn();
  const onKeyDown = vi.fn();
  const props = { src: "/synthetic-video.mp4", posterSrc: "/synthetic-poster.png", title: "预览", demo: false };
  const view = render(<div onClick={onClick} onMouseDown={onMouseDown} onPointerDown={onPointerDown} onKeyDown={onKeyDown}>
    <VideoPreview {...props} />
  </div>);
  const preview = screen.getByRole("img").parentElement!;
  return { ...view, preview, onClick, onMouseDown, onPointerDown, onKeyDown };
}

function hover(preview: HTMLElement) {
  fireEvent.mouseEnter(preview);
  act(() => vi.advanceTimersByTime(1000));
  return screen.getByLabelText("预览 的视频") as HTMLVideoElement;
}

describe("VideoPreview", () => {
  it("keeps the poster and node-selection clicks until a continuous hover reaches one second", () => {
    const { preview, onClick } = showPreview();
    fireEvent.mouseEnter(preview);
    act(() => vi.advanceTimersByTime(999));
    fireEvent.click(screen.getByRole("img"));
    expect(onClick).toHaveBeenCalledOnce();
    expect(screen.queryByLabelText("预览 的视频")).not.toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.getByLabelText("预览 的视频")).toHaveProperty("muted", true);
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledOnce();
  });

  it("cancels brief hovers and starts a fresh one-second wait on every entry", () => {
    const { preview } = showPreview();
    fireEvent.mouseEnter(preview);
    act(() => vi.advanceTimersByTime(600));
    fireEvent.mouseLeave(preview);
    act(() => vi.advanceTimersByTime(1000));
    expect(screen.queryByLabelText("预览 的视频")).not.toBeInTheDocument();
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
    fireEvent.mouseEnter(preview);
    act(() => vi.advanceTimersByTime(999));
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledOnce();
    const video = screen.getByLabelText("预览 的视频") as HTMLVideoElement;
    video.currentTime = 3;
    fireEvent.mouseLeave(preview);
    fireEvent.mouseEnter(preview);
    act(() => vi.advanceTimersByTime(999));
    expect(video.currentTime).toBe(3);
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledOnce();
    act(() => vi.advanceTimersByTime(1));
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(2);
  });

  it("clears pending hover activation when the node unmounts", () => {
    const { preview, unmount } = showPreview();
    fireEvent.mouseEnter(preview);
    act(() => vi.advanceTimersByTime(600));
    unmount();
    expect(vi.getTimerCount()).toBe(0);
    act(() => vi.advanceTimersByTime(1000));
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
  });

  it("starts muted on hover without a user gesture, pauses on leave and resumes the same position", async () => {
    // Match the native autoplay policy: hover cannot authorize audible playback.
    vi.mocked(HTMLMediaElement.prototype.play).mockImplementation(function (this: HTMLMediaElement) {
      return this.muted ? Promise.resolve() : Promise.reject(new DOMException("Blocked", "NotAllowedError"));
    });
    const { preview, onClick, unmount } = showPreview();
    expect(screen.queryByLabelText("预览 的视频")).not.toBeInTheDocument();
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
    await act(async () => {
      fireEvent.mouseEnter(preview);
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    const video = screen.getByLabelText("预览 的视频") as HTMLVideoElement;
    expect(video.muted).toBe(true);
    expect(screen.getByRole("button", { name: "开启视频声音" })).toBeInTheDocument();
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledOnce();
    expect(onClick).not.toHaveBeenCalled();
    video.currentTime = 3;
    fireEvent.mouseLeave(preview);
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
    expect(hover(preview)).toBe(video);
    expect(video.currentTime).toBe(3);
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(2);
    vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
    unmount();
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalledOnce();
  });

  it("isolates seek and sound controls while picture clicks reach node selection", () => {
    const { preview, onClick, onMouseDown, onPointerDown, onKeyDown } = showPreview();
    fireEvent.click(screen.getByRole("img"));
    expect(onClick).toHaveBeenCalledOnce();
    onClick.mockClear();
    const video = hover(preview);
    const seek = screen.getByRole("slider", { name: "视频播放进度" });
    expect(seek).toBeDisabled();
    Object.defineProperty(video, "duration", { configurable: true, value: 10 });
    fireEvent.loadedMetadata(video);
    expect(seek).toBeEnabled();
    fireEvent.pointerDown(seek);
    fireEvent.mouseDown(seek);
    fireEvent.change(seek, { target: { value: "4.5" } });
    fireEvent.click(seek);
    fireEvent.keyDown(seek, { key: "ArrowRight" });
    expect(video.currentTime).toBe(4.5);
    expect(seek).toHaveAttribute("aria-valuetext", "00:04 / 00:10");
    expect(onClick).not.toHaveBeenCalled();
    expect(onMouseDown).not.toHaveBeenCalled();
    expect(onPointerDown).not.toHaveBeenCalled();
    expect(onKeyDown).not.toHaveBeenCalled();
    video.currentTime = 6;
    fireEvent.timeUpdate(video);
    expect(seek).toHaveValue("6");
    fireEvent.click(screen.getByRole("button", { name: "开启视频声音" }));
    expect(video.muted).toBe(false);
    fireEvent.mouseLeave(preview);
    expect(hover(preview)).toBe(video);
    expect(video.muted).toBe(false);
    expect(video.currentTime).toBe(6);
    fireEvent.click(screen.getByRole("button", { name: "关闭视频声音" }));
    expect(video.muted).toBe(true);
    expect(onClick).not.toHaveBeenCalled();
    fireEvent.click(video);
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("handles invalid metadata and offers an isolated retry after playback fails", () => {
    const { preview, onClick } = showPreview();
    const video = hover(preview);
    Object.defineProperty(video, "duration", { configurable: true, value: Infinity });
    fireEvent.loadedMetadata(video);
    expect(screen.getByRole("slider")).toBeDisabled();
    fireEvent.error(video);
    expect(screen.getByRole("alert")).toHaveTextContent("视频播放失败");
    fireEvent.click(screen.getByRole("button", { name: "重试播放" }));
    expect(screen.getByLabelText("预览 的视频")).not.toBe(video);
    expect(screen.getByLabelText("预览 的视频")).toHaveProperty("muted", true);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("retains explicitly enabled sound when a later playback attempt is rejected and retried", async () => {
    const { preview } = showPreview();
    hover(preview);
    fireEvent.click(screen.getByRole("button", { name: "开启视频声音" }));
    fireEvent.mouseLeave(preview);
    vi.mocked(HTMLMediaElement.prototype.play).mockRejectedValueOnce(new DOMException("Blocked", "NotAllowedError"));
    hover(preview);
    await act(async () => {});
    expect(screen.getByRole("alert")).toHaveTextContent("视频播放失败");
    fireEvent.click(screen.getByRole("button", { name: "重试播放" }));
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(3);
    expect(screen.getByLabelText("预览 的视频")).toHaveProperty("muted", false);
  });

  it("ignores a stale play rejection after the pointer leaves", async () => {
    let rejectPlay: (reason: unknown) => void = () => {};
    vi.mocked(HTMLMediaElement.prototype.play).mockImplementationOnce(() => new Promise((_, reject) => { rejectPlay = reject; }));
    const { preview } = showPreview();
    hover(preview);
    fireEvent.mouseLeave(preview);
    await act(async () => rejectPlay(new DOMException("Interrupted", "AbortError")));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("opens an in-app video viewer without activating the hover player", async () => {
    // The viewer has its own animation timers; exercise this path with real time.
    vi.useRealTimers();
    const { preview, onClick, onMouseDown, onPointerDown, onKeyDown } = showPreview();
    fireEvent.mouseEnter(preview);
    const expand = screen.getByRole("button", { name: "放大视频" });
    expand.focus();
    fireEvent.pointerDown(expand);
    fireEvent.mouseDown(expand);
    fireEvent.click(expand);
    await act(async () => { await new Promise((resolve) => window.setTimeout(resolve, 1100)); });
    const dialog = await screen.findByRole("dialog", { name: "预览 的视频预览" });
    const player = dialog.querySelector("video")!;
    expect(player).toHaveAttribute("controls");
    expect(player).not.toHaveAttribute("autoplay");
    expect(player).toHaveAttribute("poster", "/synthetic-poster.png");
    expect(player.querySelector("source")).toHaveAttribute("src", "/synthetic-video.mp4");
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
    expect(document.querySelector(".video-card-preview > video")).toBeNull();
    fireEvent.pointerDown(player);
    fireEvent.mouseDown(player);
    fireEvent.click(player);
    fireEvent.keyDown(player, { key: "Delete" });
    expect(onClick).not.toHaveBeenCalled();
    expect(onMouseDown).not.toHaveBeenCalled();
    expect(onPointerDown).not.toHaveBeenCalled();
    expect(onKeyDown).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "关闭预览" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(expand).toHaveFocus());
  });

  it("pauses the hover video during enlargement and retains its position after closing", async () => {
    vi.useRealTimers();
    const { preview } = showPreview();
    fireEvent.mouseEnter(preview);
    const cardVideo = await screen.findByLabelText("预览 的视频", {}, { timeout: 2000 }) as HTMLVideoElement;
    cardVideo.currentTime = 4;
    vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
    fireEvent.click(screen.getByRole("button", { name: "放大视频" }));
    const dialog = await screen.findByRole("dialog", { name: "预览 的视频预览" });
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledOnce();
    fireEvent.click(within(dialog).getByRole("button", { name: "关闭预览" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledOnce();
    fireEvent.mouseEnter(preview);
    expect(screen.getByLabelText("预览 的视频")).toBe(cardVideo);
    expect(cardVideo.currentTime).toBe(4);
    await waitFor(() => expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(2), { timeout: 2000 });
  });
});
