import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { clickControl } from "../../test/controls";
import { AudioPlayer } from "./AudioPlayer";

function showPlayer(description?: string) {
  return render(<QueryClientProvider client={createQueryClient()}>
    <AudioPlayer src="/test-audio.wav" title="秋日旁白" description={description} selected={false} />
  </QueryClientProvider>);
}

describe("AudioPlayer", () => {
  beforeEach(() => {
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
  });

  it("keeps the waveform seek control synchronized with audio metadata and playback", () => {
    showPlayer("把时间，留在这一刻");
    const audio = screen.getByLabelText("秋日旁白 的音频");
    const seek = screen.getByRole("slider", { name: "音频播放进度" });
    expect(seek).toBeDisabled();
    expect(screen.getByText("把时间，留在这一刻")).toBeVisible();
    Object.defineProperty(audio, "duration", { configurable: true, value: 12 });
    fireEvent.loadedMetadata(audio);
    expect(seek).toBeEnabled();
    fireEvent.timeUpdate(audio, { target: { currentTime: 4 } });
    expect(screen.getByText("00:04 / 00:12")).toBeVisible();
    fireEvent.change(seek, { target: { value: "6" } });
    expect(audio).toHaveProperty("currentTime", 6);
    expect(screen.getByText("00:06 / 00:12")).toBeVisible();
  });

  it("falls back to the title and keeps playback failures retryable", async () => {
    vi.spyOn(HTMLMediaElement.prototype, "play").mockRejectedValue(new Error("Playback unavailable"));
    const load = vi.spyOn(HTMLMediaElement.prototype, "load").mockImplementation(() => {});
    showPlayer();
    expect(screen.getByText("秋日旁白")).toBeVisible();
    await clickControl(screen.getByRole("button", { name: "播放音频" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("音频播放失败，请重试。");
    await clickControl(screen.getByRole("button", { name: "重试播放" }));
    expect(load).toHaveBeenCalledOnce();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
