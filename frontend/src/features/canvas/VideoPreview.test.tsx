import { act,fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import { beforeEach,describe,expect,it,vi } from "vitest";
import { VideoPreview } from "./VideoPreview";

beforeEach(() => {
  vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
});

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
  return screen.getByLabelText("预览 的视频") as HTMLVideoElement;
}

describe("VideoPreview", () => {
  it("plays with sound on hover, pauses on leave and resumes the same position", () => {
    const { preview, onClick, unmount } = showPreview();
    expect(screen.queryByLabelText("预览 的视频")).not.toBeInTheDocument();
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
    const video = hover(preview);
    expect(video.muted).toBe(false);
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
    fireEvent.click(screen.getByRole("button", { name: "关闭视频声音" }));
    expect(video.muted).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "开启视频声音" }));
    expect(video.muted).toBe(false);
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
    expect(screen.getByLabelText("预览 的视频")).toHaveProperty("muted", false);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("reports rejected sound playback without silently switching to mute", async () => {
    vi.mocked(HTMLMediaElement.prototype.play).mockRejectedValueOnce(new DOMException("Blocked", "NotAllowedError"));
    const { preview } = showPreview();
    hover(preview);
    expect(await screen.findByRole("alert")).toHaveTextContent("视频播放失败");
    fireEvent.click(screen.getByRole("button", { name: "重试播放" }));
    await waitFor(() => expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(2));
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
    const { onClick, onMouseDown, onPointerDown, onKeyDown } = showPreview();
    const expand = screen.getByRole("button", { name: "放大视频" });
    expand.focus();
    fireEvent.pointerDown(expand);
    fireEvent.mouseDown(expand);
    fireEvent.click(expand);
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
    const { preview } = showPreview();
    const cardVideo = hover(preview);
    cardVideo.currentTime = 4;
    vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
    fireEvent.click(screen.getByRole("button", { name: "放大视频" }));
    const dialog = await screen.findByRole("dialog", { name: "预览 的视频预览" });
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledOnce();
    fireEvent.click(within(dialog).getByRole("button", { name: "关闭预览" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledOnce();
    expect(hover(preview)).toBe(cardVideo);
    expect(cardVideo.currentTime).toBe(4);
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(2);
  });
});
