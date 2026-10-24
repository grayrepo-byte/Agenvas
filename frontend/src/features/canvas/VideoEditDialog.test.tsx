import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasItem, VideoCompositionRequest } from "../../shared/api/client";
import { selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { VideoEditDialog } from "./VideoEditDialog";

function item(id: string, kind: "VIDEO" | "AUDIO" = "VIDEO"): CanvasItem {
  return { id, subjectType: "ARTIFACT", subjectId: `artifact-${id}`, title: id, x: 0, y: 0, width: 640, height: 360,
    zIndex: 0, locked: false, version: 1, selectedVersionId: `version-${id}`, selectedVersion: {
      id: `version-${id}`, versionNo: 1, schemaVersion: 1, content: { sourceType: "UPLOAD", assetId: `asset-${id}` },
      createdByKind: "USER", createdAt: "2026-10-22T00:00:00Z", inputReferences: [],
    }, artifact: { id: `artifact-${id}`, projectId: "project-1", title: id, kind, resourceDefaultVersionId: null,
      resourceDefaultVersion: null, version: 1, createdAt: "2026-10-22T00:00:00Z", updatedAt: "2026-10-22T00:00:00Z" } };
}
const items = [item("video-1"), item("video-2"), item("video-unused"), item("audio-1", "AUDIO")];

beforeEach(() => {
  vi.spyOn(HTMLMediaElement.prototype, "load").mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
  server.use(http.post("/api/v1/projects/project-1/video-compositions/preview", () => HttpResponse.json({ durationMs: 6000, frames: 180, fontFamily: "Noto Sans CJK SC", shots: [] })));
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })));
});

function setup({ initialIds = ["video-1"], duration = 6000, metadataFailure = false } = {}) {
  const reads: string[] = [];
  let failures = metadataFailure ? 1 : 0;
  server.use(http.get("/api/v1/projects/project-1/assets/:assetId", ({ params }) => {
    const id = String(params.assetId); reads.push(id);
    if (failures-- > 0) return HttpResponse.json({ detail: "读取时长失败" }, { status: 503 });
    return HttpResponse.json({ id, projectId: "project-1", mediaKind: id.includes("audio") ? "AUDIO" : "VIDEO", contentType: "video/mp4",
      byteSize: 1024, sha256: "a".repeat(64), width: 1280, height: 720, durationMs: id.includes("audio") ? 10000 : id === "asset-video-2" ? 4000 : duration,
      createdAt: "2026-10-22T00:00:00Z" });
  }));
  const client = createQueryClient(); client.setDefaultOptions({ queries: { retry: false } });
  const close = vi.fn();
  render(<QueryClientProvider client={client}><VideoEditDialog projectId="project-1" items={items} initialIds={initialIds} onClose={close} /></QueryClientProvider>);
  return { close, reads };
}
function setNumber(name: string, value: number, root: HTMLElement = screen.getByRole("dialog")) {
  fireEvent.change(within(root).getByRole("spinbutton", { name }), { target: { value: String(value) } });
}

describe("VideoEditDialog", () => {
  it("moves initial keyboard focus into the editor and keeps Tab navigation inside it", async () => {
    setup();
    const user = userEvent.setup();
    const dialog = screen.getByRole("dialog");
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "关闭" })).toHaveFocus());
    await user.tab(); expect(dialog).toContainElement(document.activeElement as HTMLElement);
    await user.tab({ shift: true }); expect(within(dialog).getByRole("button", { name: "关闭" })).toHaveFocus();
  });
  it("submits ordered exact clips with trims, speed, fades, voices, music, output and timed subtitles", async () => {
    const requests: VideoCompositionRequest[] = [];
    server.use(http.post("/api/v1/projects/project-1/video-compositions", async ({ request }) => {
      requests.push(await request.json() as VideoCompositionRequest); return HttpResponse.json({ id: "task-edit", status: "READY" });
    }));
    const { close } = setup({ initialIds: ["video-1", "video-2"] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    const first = screen.getByRole("region", { name: "片段 1" });
    setNumber("入点（秒）", 1, first); setNumber("出点（秒）", 5, first);
    setNumber("视频速度（倍）", 2, first); setNumber("片段首尾淡黑（毫秒）", 100, first);
    await user.click(screen.getByRole("tab", { name: "音频" }));
    await user.click(screen.getByRole("button", { name: "添加配音（自动定位）" }));
    await user.click(within(screen.getByRole("listitem", { name: "片段 2" })).getByRole("button", { name: "上移" }));
    await selectValue(screen.getByRole("combobox", { name: "原片音轨" }), "BACKGROUND");

    await selectValue(screen.getByRole("combobox", { name: "背景音乐" }), "version-audio-1");
    setNumber("音乐音量（0–1）", 0.4); setNumber("音乐取源起点（秒）", 1);
    await user.click(screen.getByRole("tab", { name: "导出" }));
    await selectValue(screen.getByRole("combobox", { name: "输出分辨率" }), "1080P");
    await selectValue(screen.getByRole("combobox", { name: "输出画幅" }), "9:16");
    await user.click(screen.getByRole("tab", { name: "字幕" }));
    await user.click(screen.getByRole("button", { name: "添加字幕" }));
    fireEvent.change(screen.getByRole("textbox", { name: "字幕正文" }), { target: { value: "这是一段字幕" } });
    setNumber("字幕开始（秒）", 0.5); setNumber("字幕结束（秒）", 2);
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    await waitFor(() => expect(close).toHaveBeenCalledOnce());
    expect(requests).toEqual([{ title: "剪辑成片", originalAudioMode: "BACKGROUND", output: { resolution: "1080P", aspectRatio: "9:16" },
      shots: [
        { videoVersionId: "version-video-2", sourceStartMs: 0, durationMs: 4000, speed: 1, fadeMs: 0, lines: [] },
        { videoVersionId: "version-video-1", sourceStartMs: 1000, durationMs: 4000, speed: 2, fadeMs: 100, lines: [{ audioVersionId: "version-audio-1" }] },
      ], music: { audioVersionId: "version-audio-1", sourceStartMs: 1000, volume: 0.4 }, subtitles: [{ text: "这是一段字幕", startMs: 500, endMs: 2000 }] }]);
  });

  it("allows a silent editing workflow and reads only the clips in use", async () => {
    let request: VideoCompositionRequest | undefined;
    server.use(http.post("/api/v1/projects/project-1/video-compositions", async ({ request: incoming }) => {
      request = await incoming.json() as VideoCompositionRequest; return HttpResponse.json({ id: "silent", status: "READY" });
    }));
    const { reads } = setup();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    expect(reads).toEqual(["asset-video-1"]);
    await user.click(screen.getByRole("tab", { name: "音频" }));
    await selectValue(screen.getByRole("combobox", { name: "原片音轨" }), "MUTE");
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    await waitFor(() => expect(request?.shots[0]?.lines).toEqual([]));
    expect(request?.originalAudioMode).toBe("MUTE");
    expect(request?.music).toBeUndefined();
  });

  it("keeps the draft and identical command key after an uncertain response, but changes it after an explicit edit", async () => {
    const requests: { key: string | null; body: unknown }[] = [];
    server.use(http.post("/api/v1/projects/project-1/video-compositions", async ({ request }) => {
      requests.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
      return HttpResponse.json({ title: "响应暂时丢失", detail: "响应暂时丢失" }, { status: 503, headers: { "Content-Type": "application/problem+json" } });
    }));
    setup(); const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    await user.click(screen.getByRole("tab", { name: "导出" }));
    fireEvent.change(screen.getByRole("textbox", { name: "成片名称" }), { target: { value: "我的剪辑" } });
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    await screen.findByRole("alert");
    expect(screen.getByRole("textbox", { name: "成片名称" })).toHaveValue("我的剪辑");
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[0]?.key).toBeTruthy(); expect(requests[1]).toEqual(requests[0]);
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    fireEvent.change(screen.getByRole("textbox", { name: "成片名称" }), { target: { value: "新的剪辑" } });
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    await waitFor(() => expect(requests).toHaveLength(3));
    expect(requests[2]?.key).not.toBe(requests[0]?.key);
  });

  it("cuts a short interval from a long source and refuses invalid speed, subtitle and music ranges", async () => {
    setup({ duration: 120000 }); const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("spinbutton", { name: "出点（秒）" })).toHaveValue(120));
    const submit = screen.getByRole("button", { name: "导出 MP4" }); expect(submit).toBeDisabled();
    setNumber("入点（秒）", 90); setNumber("出点（秒）", 100);
    expect(submit).toBeEnabled();
    setNumber("视频速度（倍）", 0.4); expect(submit).toBeDisabled();
    setNumber("视频速度（倍）", 1);
    await user.click(screen.getByRole("tab", { name: "字幕" }));
    await user.click(screen.getByRole("button", { name: "添加字幕" }));
    fireEvent.change(screen.getByRole("textbox", { name: "字幕正文" }), { target: { value: "字幕" } });
    setNumber("字幕结束（秒）", 11); expect(submit).toBeDisabled();
    setNumber("字幕结束（秒）", 10);
    await user.click(screen.getByRole("tab", { name: "音频" }));
    await selectValue(screen.getByRole("combobox", { name: "背景音乐" }), "version-audio-1");
    await waitFor(() => expect(submit).toBeEnabled());
    setNumber("音乐取源起点（秒）", 10); expect(submit).toBeDisabled();
    setNumber("音乐取源起点（秒）", 1); expect(submit).toBeEnabled();
  });

  it("retries source metadata without discarding clip edits", async () => {
    setup({ metadataFailure: true }); const user = userEvent.setup();
    await screen.findByRole("alert");
    await user.click(screen.getByRole("tab", { name: "导出" }));
    fireEvent.change(screen.getByRole("textbox", { name: "成片名称" }), { target: { value: "保留的草稿" } });
    await user.click(screen.getByRole("tab", { name: "片段" }));
    await user.click(screen.getByRole("button", { name: "重试读取" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    await user.click(screen.getByRole("tab", { name: "导出" }));
    expect(screen.getByRole("textbox", { name: "成片名称" })).toHaveValue("保留的草稿");
  });

  it("checks fades against the actual frame-aligned clip length", async () => {
    setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    setNumber("出点（秒）", 0.11);
    setNumber("片段首尾淡黑（毫秒）", 50);
    expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled();
    setNumber("片段首尾淡黑（毫秒）", 55);
    expect(screen.getByRole("button", { name: "导出 MP4" })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "片段首尾淡黑（毫秒）" })).toHaveAttribute("aria-invalid", "true");
  });

  it("mounts one player and keeps edits attached to each clip across inspector tabs and selection", async () => {
    setup({ initialIds: ["video-1", "video-2"] }); const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    expect(document.querySelectorAll("video")).toHaveLength(1);
    setNumber("入点（秒）", 1); setNumber("出点（秒）", 5); setNumber("视频速度（倍）", 2);
    const firstPlayer = screen.getByLabelText("完整成片预览");
    await user.click(screen.getByRole("tab", { name: "音频" }));
    expect(screen.getByLabelText("完整成片预览")).toBe(firstPlayer);
    await user.click(screen.getByRole("button", { name: "添加配音（自动定位）" }));
    await user.click(screen.getByRole("button", { name: "时间线片段 2：video-2" }));
    expect(document.querySelectorAll("video")).toHaveLength(1);
    expect(screen.getByLabelText("完整成片预览")).toHaveAttribute("src", "/api/v1/projects/project-1/assets/asset-video-2/content");
    expect(screen.getByRole("spinbutton", { name: "入点（秒）" })).toHaveValue(0);
    await user.click(screen.getByRole("button", { name: "编辑片段 1：video-1" }));
    expect(screen.getByRole("spinbutton", { name: "入点（秒）" })).toHaveValue(1);
    expect(screen.getByRole("spinbutton", { name: "出点（秒）" })).toHaveValue(5);
    expect(screen.getByRole("spinbutton", { name: "视频速度（倍）" })).toHaveValue(2);
    await user.click(screen.getByRole("tab", { name: "音频" }));
    expect(screen.getByRole("combobox", { name: "配音素材" })).toHaveTextContent("audio-1");
  });

  it("reorders from the timeline while retaining selection and submits the visible order", async () => {
    let request: VideoCompositionRequest | undefined;
    server.use(http.post("/api/v1/projects/project-1/video-compositions", async ({ request: incoming }) => {
      request = await incoming.json() as VideoCompositionRequest; return HttpResponse.json({ id: "ordered", status: "READY" });
    }));
    setup({ initialIds: ["video-1", "video-2"] });
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    setNumber("入点（秒）", 1);
    const first = screen.getByRole("button", { name: "时间线片段 1：video-1" });
    const second = screen.getByRole("button", { name: "时间线片段 2：video-2" });
    const dataTransfer = { setData: vi.fn(), effectAllowed: "", dropEffect: "" };
    fireEvent.dragStart(first, { dataTransfer }); fireEvent.dragOver(second, { dataTransfer }); fireEvent.drop(second, { dataTransfer });
    expect(screen.getByRole("button", { name: "时间线片段 2：video-1" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("spinbutton", { name: "入点（秒）" })).toHaveValue(1);
    fireEvent.click(screen.getByRole("button", { name: "导出 MP4" }));
    await waitFor(() => expect(request?.shots.map((shot) => [shot.videoVersionId, shot.sourceStartMs])).toEqual([
      ["version-video-2", 0], ["version-video-1", 1000],
    ]));
  });

  it("selects the next clip on removal and recovers from an empty sequence by adding a clip", async () => {
    setup({ initialIds: ["video-1", "video-2"] }); const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    await user.click(within(screen.getByRole("listitem", { name: "片段 1" })).getByRole("button", { name: "移除" }));
    expect(screen.getByLabelText("完整成片预览")).toBeInTheDocument();
    await user.click(within(screen.getByRole("listitem", { name: "片段 1" })).getByRole("button", { name: "移除" }));
    expect(screen.getByText("从一个片段开始")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "导出 MP4" })).toBeDisabled();
    expect(document.querySelectorAll("video")).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "添加片段" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    expect(document.querySelectorAll("video")).toHaveLength(1);
  });

  it("blocks export while a numeric field is empty instead of silently changing it to zero", async () => {
    setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    fireEvent.change(screen.getByRole("spinbutton", { name: "入点（秒）" }), { target: { value: "" } });
    expect(screen.getByRole("spinbutton", { name: "入点（秒）" })).toHaveValue(null);
    expect(screen.getByRole("button", { name: "导出 MP4" })).toBeDisabled();
    setNumber("入点（秒）", 0);
    expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled();
  });

  it("locks editing and closing while an export command is pending", async () => {
    let finish: (() => void) | undefined;
    server.use(http.post("/api/v1/projects/project-1/video-compositions", async () => {
      await new Promise<void>((resolve) => { finish = resolve; });
      return HttpResponse.json({ id: "pending", status: "READY" });
    }));
    const { close } = setup(); const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    expect(screen.getByRole("button", { name: "正在受理…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "关闭" })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "入点（秒）" })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "视频速度（倍）" })).toBeDisabled();
    await user.keyboard("{Escape}"); expect(close).not.toHaveBeenCalled();
    await waitFor(() => expect(finish).toBeTypeOf("function")); finish?.();
    await waitFor(() => expect(close).toHaveBeenCalledOnce());
  });
  it("cancels an active trim with Escape without closing the editor", async () => {
    const { close } = setup(); const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    const handle = screen.getByRole("slider", { name: "调整片段 1 出点" });
    fireEvent.pointerDown(handle, { button: 0, pointerId: 1, clientX: 300 });
    fireEvent.pointerMove(handle, { pointerId: 1, clientX: 230 });
    expect(screen.getByRole("spinbutton", { name: "出点（秒）" })).toHaveValue(5);
    await user.keyboard("{Escape}");
    expect(close).not.toHaveBeenCalled();
    expect(screen.getByRole("spinbutton", { name: "出点（秒）" })).toHaveValue(6);
    fireEvent.pointerUp(handle, { pointerId: 1 });
  });

});
