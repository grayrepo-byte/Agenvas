import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { selectValue } from "../../test/controls";
import { VideoReviewControls, videoTimeLabel } from "./VideoReviewControls";

function media(duration = 8) {
  const element = document.createElement("video");
  Object.defineProperty(element, "duration", { configurable: true, value: duration });
  return element;
}

beforeEach(() => {
  vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
});

describe("VideoReviewControls", () => {
  it("seeks both exact videos, steps using the chosen frame rate, and applies one playback rate", async () => {
    const left = media(), right = media(5);
    render(<VideoReviewControls video={left} partner={right} />);
    expect(screen.getByRole("slider", { name: "审片进度" })).toHaveAttribute("max", "5");
    fireEvent.change(screen.getByRole("slider"), { target: { value: "2" } });
    expect(left.currentTime).toBe(2); expect(right.currentTime).toBe(2);
    await selectValue(screen.getByRole("combobox", { name: "步进帧率" }), 25);
    fireEvent.click(screen.getByRole("button", { name: "前进一步" }));
    expect(left.currentTime).toBeCloseTo(2.04); expect(right.currentTime).toBeCloseTo(2.04);
    fireEvent.click(screen.getByRole("button", { name: "后退一步" }));
    expect(left.currentTime).toBeCloseTo(2);
    await selectValue(screen.getByRole("combobox", { name: "播放速度" }), 0.5);
    expect(left.playbackRate).toBe(0.5); expect(right.playbackRate).toBe(0.5);
    expect(screen.getByText(/不是素材帧率检测/)).toBeVisible();
  });

  it("corrects partner drift and pauses both videos at the shorter duration", () => {
    const left = media(), right = media(5);
    Object.defineProperty(left, "paused", { configurable: true, value: false });
    render(<VideoReviewControls video={left} partner={right} />);
    left.currentTime = 3; right.currentTime = 1;
    fireEvent.timeUpdate(left);
    expect(right.currentTime).toBe(3); expect(right.muted).toBe(true);
    left.currentTime = 5.1;
    fireEvent.timeUpdate(left);
    expect(left.currentTime).toBe(5); expect(right.currentTime).toBe(5);
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
  });

  it("holds time notes only in this review and supports jumping/removing", () => {
    const video = media();
    const { unmount } = render(<VideoReviewControls video={video} />);
    video.currentTime = 1.234;
    fireEvent.change(screen.getByRole("textbox", { name: "时间点备注" }), { target: { value: "检查口型" } });
    fireEvent.click(screen.getByRole("button", { name: "标记当前时间" }));
    video.currentTime = 4;
    fireEvent.click(screen.getByRole("button", { name: "00:01.234 检查口型" }));
    expect(video.currentTime).toBe(1.234);
    fireEvent.click(screen.getByRole("button", { name: "移除 00:01.234 的标记" }));
    expect(screen.queryByText("检查口型")).not.toBeInTheDocument();
    expect(screen.getByText("标记仅用于本次审片，关闭后清除。")).toBeVisible();
    unmount();
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
  });

  it("pauses both players and reports a rejected play attempt", async () => {
    const left = media(), right = media();
    vi.mocked(HTMLMediaElement.prototype.play).mockRejectedValue(new DOMException("Blocked", "NotAllowedError"));
    render(<VideoReviewControls video={left} partner={right} />);
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "播放" })); });
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("播放失败"));
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
  });

  it("formats millisecond review time across a minute boundary", () => {
    expect(videoTimeLabel(59.9996)).toBe("01:00.000");
    expect(videoTimeLabel(-1)).toBe("00:00.000");
  });
});
