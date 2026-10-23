import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { AudioTrimDialog } from "./AudioTrimDialog";

const source = { canvasItemId: "source-item", sourceVersionId: "source-v1", expectedCanvasItemVersion: 7 };
const endpoint = "/api/v1/projects/project-1/artifacts/audio-1/audio-trims";
function show(durationMs = 25_000) {
  const client = createQueryClient();
  const close = vi.fn();
  const view = render(<QueryClientProvider client={client}><AudioTrimDialog projectId="project-1" artifactId="audio-1"
    source={source} src="/source.wav" title="参考声音" durationMs={durationMs} onClose={close} /></QueryClientProvider>);
  const audio = screen.getByLabelText("参考声音 的音频") as HTMLAudioElement;
  Object.defineProperty(audio, "duration", { configurable: true, value: durationMs / 1000 });
  fireEvent.loadedMetadata(audio);
  return { ...view, client, close, audio };
}
const start = () => screen.getByRole("spinbutton", { name: "入点（秒）" });
const end = () => screen.getByRole("spinbutton", { name: "出点（秒）" });
const submit = () => screen.getByRole("button", { name: "创建音频片段" });
const preview = () => screen.getByRole("button", { name: "试听所选片段" });
const change = (field: HTMLElement, value: string) => fireEvent.change(field, { target: { value } });

beforeEach(() => {
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
  vi.stubGlobal("AudioContext", class {
    decodeAudioData() { return Promise.resolve({ numberOfChannels: 1, getChannelData: () => new Float32Array([0, 0.2, 0.9, 0]) }); }
    close() { return Promise.resolve(); }
  });
  server.use(
    http.get("/source.wav", () => new HttpResponse(new Uint8Array([1, 2]).buffer)),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
  );
});
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe("AudioTrimDialog", () => {
  it("uses decoded waveform and defaults to the first fifteen seconds or the shorter source", async () => {
    const view = show(6_432);
    expect(start()).toHaveValue(0);
    expect(end()).toHaveValue(6.432);
    expect(screen.getByRole("radio", { name: "WAV" })).toHaveAttribute("data-state", "on");
    await waitFor(() => expect(view.container.ownerDocument.querySelectorAll(".audio-player-waveform span")).toHaveLength(48));
    expect(screen.getByText(/单人、清晰/)).toBeVisible();
    expect(submit()).toBeEnabled();
  });

  it("sets precise playback in/out points, pauses at the out point, and stops after range edits", async () => {
    const { audio } = show();
    expect(end()).toHaveValue(15);
    audio.currentTime = 2.367;
    fireEvent.click(screen.getByRole("button", { name: "将当前位置设为入点" }));
    audio.currentTime = 5.678;
    fireEvent.click(screen.getByRole("button", { name: "将当前位置设为出点" }));
    expect(start()).toHaveValue(2.367); expect(end()).toHaveValue(5.678);
    fireEvent.click(preview());
    expect(audio.currentTime).toBe(2.367);
    audio.currentTime = 6;
    fireEvent.timeUpdate(audio);
    expect(audio.currentTime).toBe(5.678);
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
    fireEvent.click(preview());
    vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
    change(end(), "7.321");
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
    await act(async () => {});
  });

  it.each([
    ["", "5"], ["0.0001", "5"], ["-1", "5"], ["1", "1.099"], ["0", "26"], ["4", "3"],
  ])("rejects invalid range %s–%s without rounding or sending a request", (from, to) => {
    show(); change(start(), from); change(end(), to);
    expect(submit()).toBeDisabled(); expect(preview()).toBeDisabled();
    expect(start()).toHaveAttribute("aria-invalid", "true");
  });

  it("blocks oversized WAV while allowing the same range in MP3", () => {
    show(300_000); change(end(), "300");
    expect(submit()).toBeDisabled();
    expect(screen.getByText(/超过 50 MiB/)).toBeVisible();
    fireEvent.click(screen.getByRole("radio", { name: "MP3" }));
    expect(submit()).toBeEnabled();
  });

  it("preserves the frozen source, range, and command key after a lost response", async () => {
    const requests: unknown[] = []; const keys: (string | null)[] = [];
    server.use(http.post(endpoint, async ({ request }) => {
      requests.push(await request.json()); keys.push(request.headers.get("Idempotency-Key"));
      return requests.length === 1 ? HttpResponse.error() : HttpResponse.json({ id: "trim-task", status: "READY" });
    }));
    const view = show();
    change(start(), "1.234"); change(end(), "5.678");
    fireEvent.click(submit());
    expect(await screen.findByText(/所选范围已保留/)).toBeVisible();
    view.rerender(<QueryClientProvider client={view.client}><AudioTrimDialog projectId="project-2" artifactId="audio-2"
      source={{ canvasItemId: "other-item", sourceVersionId: "other-version", expectedCanvasItemVersion: 9 }}
      src="/other.wav" title="其他声音" durationMs={1_000} onClose={view.close} /></QueryClientProvider>);
    expect(view.audio).toHaveAttribute("src", "/source.wav");
    expect(start()).toHaveValue(1.234); expect(end()).toHaveValue(5.678);
    fireEvent.click(submit());
    await waitFor(() => expect(view.close).toHaveBeenCalledOnce());
    expect(requests).toEqual([{ ...source, startMs: 1234, endMs: 5678, format: "WAV" }, { ...source, startMs: 1234, endMs: 5678, format: "WAV" }]);
    expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]);
  });

  it("blocks duplicate submissions while pending and refreshes the created node on acceptance", async () => {
    let release!: () => void;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    const requests = vi.fn();
    server.use(http.post(endpoint, async () => { requests(); await pending; return HttpResponse.json({ id: "trim-task", status: "READY" }); }));
    const view = show(); const invalidate = vi.spyOn(view.client, "invalidateQueries");
    fireEvent.click(submit());
    fireEvent.submit(screen.getByRole("dialog").querySelector("form")!);
    await waitFor(() => expect(requests).toHaveBeenCalledOnce());
    expect(screen.getByRole("button", { name: "正在创建片段…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "关闭窗口" })).toBeDisabled();
    expect(start()).toBeDisabled();
    release();
    await waitFor(() => expect(view.close).toHaveBeenCalledOnce());
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["canvas", "project-1"] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["direct-media-tasks", "project-1"] });
  });

  it("keeps the range but blocks unauthorized submission", async () => {
    server.use(http.post(endpoint, () => HttpResponse.json({ detail: "Forbidden", code: "FORBIDDEN" }, { status: 403 })));
    show(); change(start(), "1.234"); fireEvent.click(submit());
    expect(await screen.findByText(/当前会话无权裁剪/)).toBeVisible();
    expect(start()).toHaveValue(1.234); expect(submit()).toBeDisabled();
  });

  it("explains a source conflict and preserves the chosen range", async () => {
    server.use(http.post(endpoint, () => HttpResponse.json({ detail: "Changed", code: "CANVAS_ITEM_VERSION_CONFLICT" }, { status: 409 })));
    show(); change(end(), "4.567"); fireEvent.click(submit());
    expect(await screen.findByText(/来源节点已发生变化/)).toBeVisible();
    expect(end()).toHaveValue(4.567);
  });

  it("allows playback failure retry and ignores late playback after unmount", async () => {
    vi.mocked(HTMLMediaElement.prototype.play).mockRejectedValueOnce(new Error("Unavailable"));
    const view = show(); fireEvent.click(preview());
    expect(await screen.findByText("音频播放失败，请重试。")).toBeVisible();
    let resolve!: () => void;
    vi.mocked(HTMLMediaElement.prototype.play).mockImplementationOnce(() => new Promise<void>((done) => { resolve = done; }));
    fireEvent.click(preview());
    expect(screen.queryByText("音频播放失败，请重试。")).not.toBeInTheDocument();
    view.unmount();
    vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
    await act(async () => resolve());
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
  });

  it("stops a 100 ms preview without timeupdate and reschedules after seeking", async () => {
    const { audio } = show();
    await waitFor(() => expect(screen.queryByText("正在加载波形…")).not.toBeInTheDocument());
    vi.useFakeTimers();
    change(start(), "1"); change(end(), "1.1");
    Object.defineProperty(audio, "paused", { configurable: true, value: false });
    await act(async () => fireEvent.click(preview()));
    vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
    act(() => vi.advanceTimersByTime(99));
    expect(HTMLMediaElement.prototype.pause).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalledOnce();
    expect(audio.currentTime).toBe(1.1);

    change(end(), "2");
    await act(async () => fireEvent.click(preview()));
    act(() => vi.advanceTimersByTime(500));
    audio.currentTime = 1.9; fireEvent.seeking(audio); fireEvent.seeked(audio);
    vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
    act(() => vi.advanceTimersByTime(100));
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalledOnce();
    expect(audio.currentTime).toBe(2);
  });

  it("clears the preview deadline on pause and ignores a late play result after changing range", async () => {
    const { audio } = show();
    vi.useFakeTimers();
    change(end(), "0.1");
    await act(async () => fireEvent.click(preview()));
    fireEvent.pause(audio);
    vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
    act(() => vi.advanceTimersByTime(200));
    expect(HTMLMediaElement.prototype.pause).not.toHaveBeenCalled();
    let resolve!: () => void;
    vi.mocked(HTMLMediaElement.prototype.play).mockImplementationOnce(() => new Promise<void>((done) => { resolve = done; }));
    fireEvent.click(preview()); change(end(), "0.2");
    vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
    await act(async () => resolve());
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
  });
});
