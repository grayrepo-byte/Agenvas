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
  vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
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
  it("submits ordered exact clips with trims, speed, fades, voices, music, output and timed subtitles", async () => {
    const requests: VideoCompositionRequest[] = [];
    server.use(http.post("/api/v1/projects/project-1/video-compositions", async ({ request }) => {
      requests.push(await request.json() as VideoCompositionRequest); return HttpResponse.json({ id: "task-edit", status: "READY" });
    }));
    const { close } = setup({ initialIds: ["video-1", "video-2"] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "开始本地剪辑" })).toBeEnabled());
    const first = screen.getByRole("region", { name: "片段 1" });
    setNumber("入点（秒）", 1, first); setNumber("出点（秒）", 5, first);
    setNumber("视频速度（倍）", 2, first); setNumber("片段首尾淡黑（毫秒）", 100, first);
    await user.click(within(first).getByRole("button", { name: "添加配音（自动定位）" }));
    await user.click(within(screen.getByRole("region", { name: "片段 2" })).getByRole("button", { name: "上移" }));
    await selectValue(screen.getByRole("combobox", { name: "原片音轨" }), "BACKGROUND");
    await selectValue(screen.getByRole("combobox", { name: "输出分辨率" }), "1080P");
    await selectValue(screen.getByRole("combobox", { name: "输出画幅" }), "9:16");
    await selectValue(screen.getByRole("combobox", { name: "背景音乐" }), "version-audio-1");
    setNumber("音乐音量（0–1）", 0.4); setNumber("音乐取源起点（秒）", 1);
    await user.click(screen.getByRole("button", { name: "添加字幕" }));
    fireEvent.change(screen.getByRole("textbox", { name: "字幕正文" }), { target: { value: "这是一段字幕" } });
    setNumber("字幕开始（秒）", 0.5); setNumber("字幕结束（秒）", 2);
    await user.click(screen.getByRole("button", { name: "开始本地剪辑" }));
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
    await waitFor(() => expect(screen.getByRole("button", { name: "开始本地剪辑" })).toBeEnabled());
    expect(reads).toEqual(["asset-video-1"]);
    await selectValue(screen.getByRole("combobox", { name: "原片音轨" }), "MUTE");
    await user.click(screen.getByRole("button", { name: "开始本地剪辑" }));
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
    await waitFor(() => expect(screen.getByRole("button", { name: "开始本地剪辑" })).toBeEnabled());
    fireEvent.change(screen.getByRole("textbox", { name: "成片名称" }), { target: { value: "我的剪辑" } });
    await user.click(screen.getByRole("button", { name: "开始本地剪辑" }));
    await screen.findByRole("alert");
    expect(screen.getByRole("textbox", { name: "成片名称" })).toHaveValue("我的剪辑");
    await user.click(screen.getByRole("button", { name: "开始本地剪辑" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[0]?.key).toBeTruthy(); expect(requests[1]).toEqual(requests[0]);
    await waitFor(() => expect(screen.getByRole("button", { name: "开始本地剪辑" })).toBeEnabled());
    fireEvent.change(screen.getByRole("textbox", { name: "成片名称" }), { target: { value: "新的剪辑" } });
    await user.click(screen.getByRole("button", { name: "开始本地剪辑" }));
    await waitFor(() => expect(requests).toHaveLength(3));
    expect(requests[2]?.key).not.toBe(requests[0]?.key);
  });

  it("cuts a short interval from a long source and refuses invalid speed, subtitle and music ranges", async () => {
    setup({ duration: 120000 }); const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("spinbutton", { name: "出点（秒）" })).toHaveValue(120));
    const submit = screen.getByRole("button", { name: "开始本地剪辑" }); expect(submit).toBeDisabled();
    setNumber("入点（秒）", 90); setNumber("出点（秒）", 100);
    expect(submit).toBeEnabled();
    setNumber("视频速度（倍）", 0.4); expect(submit).toBeDisabled();
    setNumber("视频速度（倍）", 1);
    await user.click(screen.getByRole("button", { name: "添加字幕" }));
    fireEvent.change(screen.getByRole("textbox", { name: "字幕正文" }), { target: { value: "字幕" } });
    setNumber("字幕结束（秒）", 11); expect(submit).toBeDisabled();
    setNumber("字幕结束（秒）", 10);
    await selectValue(screen.getByRole("combobox", { name: "背景音乐" }), "version-audio-1");
    await waitFor(() => expect(submit).toBeEnabled());
    setNumber("音乐取源起点（秒）", 10); expect(submit).toBeDisabled();
    setNumber("音乐取源起点（秒）", 1); expect(submit).toBeEnabled();
  });

  it("retries source metadata without discarding clip edits", async () => {
    setup({ metadataFailure: true }); const user = userEvent.setup();
    await screen.findByRole("alert");
    fireEvent.change(screen.getByRole("textbox", { name: "成片名称" }), { target: { value: "保留的草稿" } });
    await user.click(screen.getByRole("button", { name: "重试读取" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "开始本地剪辑" })).toBeEnabled());
    expect(screen.getByRole("textbox", { name: "成片名称" })).toHaveValue("保留的草稿");
  });

  it("checks fades against the actual frame-aligned clip length", async () => {
    setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "开始本地剪辑" })).toBeEnabled());
    setNumber("出点（秒）", 0.11);
    setNumber("片段首尾淡黑（毫秒）", 50);
    expect(screen.getByRole("button", { name: "开始本地剪辑" })).toBeEnabled();
    setNumber("片段首尾淡黑（毫秒）", 55);
    expect(screen.getByRole("button", { name: "开始本地剪辑" })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "片段首尾淡黑（毫秒）" })).toHaveAttribute("aria-invalid", "true");
  });
});
