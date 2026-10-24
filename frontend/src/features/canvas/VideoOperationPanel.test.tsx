import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { VideoOperation } from "../../shared/api/client";
import { clickControl } from "../../test/controls";
import { server } from "../../test/server";
import { videoFunctionsFixture } from "../../test/videoFunctionsFixture";
import { VideoOperationPanel } from "./VideoOperationPanel";

function setup(operation: VideoOperation = "EXTRACT_AUDIO") {
  server.use(http.get("/api/v1/settings/media-functions", () => HttpResponse.json([
    { operation: "VIDEO_EXTRACT_AUDIO", capabilityId: "audio-cap", version: 3 },
    { operation: "VIDEO_UPSCALE", capabilityId: "upscale-cap", version: 2 },
  ])), http.get("/api/v1/settings/media-connections", () => HttpResponse.json(videoFunctionsFixture())));
  const client = createQueryClient();
  const submit = vi.fn(); const close = vi.fn();
  const component = (busy = false, error: Error | null = null) => <QueryClientProvider client={client}>
    <VideoOperationPanel operation={operation} sourceVersionId="precise-video-v1" sourceTitle="原片" busy={busy} error={error} onClose={close} onSubmit={submit} />
  </QueryClientProvider>;
  const view = render(component());
  return { submit, close, rerender: (busy: boolean, error: Error | null = null) => view.rerender(component(busy, error)) };
}

describe("VideoOperationPanel audio extraction", () => {
  it("submits the explicit MP3 format and selected track with pinned method versions", async () => {
    const { submit } = setup();
    await clickControl(await screen.findByRole("radio", { name: /MP3/ }));
    fireEvent.change(screen.getByRole("spinbutton", { name: "音轨索引（从0开始）" }), { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: "开始处理" }));
    expect(submit).toHaveBeenCalledWith({ expectedFunctionVersion: 3, expectedCapabilityVersion: 1, prompt: "", parameters: { format: "MP3", trackIndex: 2 } });
  });

  it.each(["-1", "32", "0.5", ""])("blocks invalid track index %s and preserves the format through errors", async (value) => {
    const view = setup();
    await clickControl(await screen.findByRole("radio", { name: /MP3/ }));
    const track = screen.getByRole("spinbutton", { name: "音轨索引（从0开始）" });
    fireEvent.change(track, { target: { value } });
    expect(screen.getByRole("button", { name: "开始处理" })).toBeDisabled();
    expect(track).toHaveAttribute("aria-invalid", "true");
    view.rerender(false, new Error("音轨不存在，输入已保留"));
    expect(screen.getByText("音轨不存在，输入已保留")).toBeVisible();
    expect(screen.getByRole("radio", { name: /MP3/ })).toBeChecked();
    fireEvent.change(track, { target: { value: "31" } });
    expect(screen.getByRole("button", { name: "开始处理" })).toBeEnabled();
  });

  it("locks both local controls while a command is being accepted", async () => {
    const view = setup();
    await screen.findByRole("radio", { name: /MP3/ });
    view.rerender(true);
    expect(screen.getByRole("radio", { name: /MP3/ })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "音轨索引（从0开始）" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /正在/ })).toBeDisabled();
  });

  it("keeps cloud video processing on its published workflow parameters", async () => {
    const { submit } = setup("UPSCALE");
    await screen.findByRole("combobox", { name: /放大倍数/ });
    expect(screen.queryByRole("combobox", { name: "音频输出格式" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "开始处理" }));
    await waitFor(() => expect(submit).toHaveBeenCalledWith({ expectedFunctionVersion: 2, expectedCapabilityVersion: 1, prompt: "",
      parameters: { dynamicValues: { video: "precise-video-v1" } } }));
  });
});
