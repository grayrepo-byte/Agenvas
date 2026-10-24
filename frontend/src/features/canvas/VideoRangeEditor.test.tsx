import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { VideoRangeEditor } from "./VideoRangeEditor";

beforeEach(() => {
  vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
});
function Harness() {
  const [range, setRange] = useState({ startMs: 1000, endMs: 4000 });
  return <VideoRangeEditor src="/precise-source.mp4" title="片段" {...range} onChange={setRange} />;
}
function loaded() {
  const video = screen.getByLabelText("片段") as HTMLVideoElement;
  Object.defineProperty(video, "duration", { configurable: true, value: 8 });
  fireEvent.loadedMetadata(video);
  return video;
}

describe("VideoRangeEditor", () => {
  it("prepares millisecond in/out points from the actual playback position", () => {
    render(<Harness />);
    const video = loaded();
    video.currentTime = 1.234;
    fireEvent.click(screen.getByRole("button", { name: "将当前位置设为入点" }));
    expect(screen.getByRole("spinbutton", { name: "入点（秒）" })).toHaveValue(1.234);
    video.currentTime = 5.678;
    fireEvent.click(screen.getByRole("button", { name: "将当前位置设为出点" }));
    expect(screen.getByRole("spinbutton", { name: "出点（秒）" })).toHaveValue(5.678);
    expect(video).toHaveAttribute("src", "/precise-source.mp4");
  });
  it("previews only the selected interval and blocks invalid or out-of-source ranges", async () => {
    render(<Harness />);
    const video = loaded();
    fireEvent.click(screen.getByRole("button", { name: "预览所选片段" }));
    expect(video.currentTime).toBe(1);
    await waitFor(() => expect(HTMLMediaElement.prototype.play).toHaveBeenCalled());
    video.currentTime = 4.2;
    fireEvent.timeUpdate(video);
    expect(video.currentTime).toBe(4);
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
    fireEvent.change(screen.getByRole("spinbutton", { name: "出点（秒）" }), { target: { value: "9" } });
    expect(screen.getByRole("button", { name: "预览所选片段" })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "出点（秒）" })).toHaveAttribute("aria-invalid", "true");
  });
  it("retries a failed source without accepting a new upload", () => {
    render(<Harness />);
    const video = loaded();
    fireEvent.error(video);
    fireEvent.click(screen.getByRole("button", { name: "重试加载" }));
    const next = screen.getByLabelText("片段");
    expect(next).not.toBe(video);
    expect(next).toHaveAttribute("src", "/precise-source.mp4");
  });

  it("keeps the studio player mounted while the inspector mounts and unmounts its trim controls", () => {
    const container = document.createElement("div"); document.body.append(container);
    const onChange = vi.fn();
    const props = { src: "/precise-source.mp4", title: "片段", startMs: 1000, endMs: 4000, onChange, studio: true };
    const view = render(<VideoRangeEditor {...props} controlsContainer={container} />);
    const video = loaded();
    expect(video).not.toHaveAttribute("controls");
    expect(screen.queryByRole("combobox", { name: "播放速度" })).not.toBeInTheDocument();
    expect(container.querySelector("fieldset")).toBeInTheDocument();
    video.currentTime = 2.125;
    fireEvent.click(screen.getByRole("button", { name: "将当前位置设为入点" }));
    expect(onChange).toHaveBeenCalledWith({ startMs: 2125, endMs: 4000 });
    view.rerender(<VideoRangeEditor {...props} controlsContainer={null} />);
    expect(container.querySelector("fieldset")).not.toBeInTheDocument();
    expect(screen.getByLabelText("片段")).toBe(video);
    view.rerender(<VideoRangeEditor {...props} controlsContainer={container} />);
    expect(screen.getByRole("spinbutton", { name: "入点（秒）" })).toHaveValue(1);
    expect(screen.getByRole("button", { name: "预览所选片段" })).toBeEnabled();
    view.unmount(); container.remove();
  });
});
