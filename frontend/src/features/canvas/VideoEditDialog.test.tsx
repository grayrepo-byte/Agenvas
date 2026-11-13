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
import { t } from "../../shared/i18n";
import type { SubtitleDraft } from "./subtitles/subtitlesApi";
const { subtitleDownloads } = vi.hoisted(() => ({ subtitleDownloads: [] as { text: string; format: string }[] }));
vi.mock("./subtitles/subtitleExchange", async original => ({
  ...(await original<typeof import("./subtitles/subtitleExchange")>()),
  downloadSubtitleFile: (text: string, format: string) => subtitleDownloads.push({ text, format }),
}));
let subtitleDrafts: SubtitleDraft[] = [];
let subtitleSaveFailure = false;

function item(id: string, kind: "VIDEO" | "AUDIO" = "VIDEO"): CanvasItem {
  return {
    id,
    subjectType: "ARTIFACT",
    subjectId: `artifact-${id}`,
    title: id,
    x: 0,
    y: 0,
    width: 640,
    height: 360,
    zIndex: 0,
    locked: false,
    version: 1,
    selectedVersionId: `version-${id}`,
    selectedVersion: {
      id: `version-${id}`,
      versionNo: 1,
      schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: `asset-${id}` },
      createdByKind: "USER",
      createdAt: "2026-10-22T00:00:00Z",
      inputReferences: [],
    },
    artifact: {
      id: `artifact-${id}`,
      projectId: "project-1",
      title: id,
      kind,
      resourceDefaultVersionId: null,
      resourceDefaultVersion: null,
      version: 1,
      createdAt: "2026-10-22T00:00:00Z",
      updatedAt: "2026-10-22T00:00:00Z",
    },
  };
}
const items = [item("video-1"), item("video-2"), item("video-unused"), item("audio-1", "AUDIO")];

const capabilities = {
  maxDurationMs: 600000,
  maxFrames: 18000,
  maxShots: 60,
  maxLines: 300,
  maxSubtitles: 2000,
  maxSourceDurationMs: 600000,
  maxSourceBytes: 2147483648,
  maxOutputBytes: 524288000,
  frameRate: 30,
  timeoutSeconds: 900,
  resolutions: ["720P", "1080P", "2160P"],
};
beforeEach(() => {
  subtitleDrafts = [];
  subtitleSaveFailure = false;
  subtitleDownloads.length = 0;
  server.use(
    http.get("/api/v1/projects/project-1/subtitles", ({ request }) => {
      const ids = new URL(request.url).searchParams.getAll("sourceVersionId");
      return HttpResponse.json(subtitleDrafts.filter(draft => ids.includes(draft.sourceVersionId)));
    }),
    http.get("/api/v1/projects/project-1/subtitles/capabilities", () =>
      HttpResponse.json({
        availability: "NOT_CONFIGURED",
        maxCues: 5000,
        maxFileBytes: 2097152,
        maxSourceDurationMs: 600000,
      }),
    ),
    http.put("/api/v1/projects/project-1/subtitles/:id", async ({ params, request }) => {
      const draft = subtitleDrafts.find(value => value.id === params.id);
      const body = (await request.json()) as { expectedVersion: number; cues: SubtitleDraft["cues"] };
      if (subtitleSaveFailure)
        return HttpResponse.json(
          { code: "SAVE_FAILED", detail: "Synthetic save failure" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } },
        );
      if (!draft || body.expectedVersion !== draft.version)
        return HttpResponse.json({ code: "SUBTITLE_VERSION_CONFLICT", detail: "Conflict" }, { status: 409 });
      const saved = { ...draft, version: draft.version + 1, cues: body.cues };
      subtitleDrafts = subtitleDrafts.map(value => (value.id === draft.id ? saved : value));
      return HttpResponse.json(saved);
    }),
  );
  server.use(
    http.get("/api/v1/projects/project-1/video-compositions/capabilities", () => HttpResponse.json(capabilities)),
  );
  vi.spyOn(HTMLMediaElement.prototype, "load").mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
  server.use(
    http.post("/api/v1/projects/project-1/video-compositions/preview", () =>
      HttpResponse.json({ durationMs: 6000, frames: 180, fontFamily: "Noto Sans CJK SC", shots: [] }),
    ),
  );
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
  );
});

function setup({ initialIds = ["video-1"], duration = 6000, metadataFailure = false } = {}) {
  const reads: string[] = [];
  let failures = metadataFailure ? 1 : 0;
  server.use(
    http.get("/api/v1/projects/project-1/assets/:assetId", ({ params }) => {
      const id = String(params.assetId);
      reads.push(id);
      if (failures-- > 0) return HttpResponse.json({ detail: "读取时长失败" }, { status: 503 });
      return HttpResponse.json({
        id,
        projectId: "project-1",
        mediaKind: id.includes("audio") ? "AUDIO" : "VIDEO",
        contentType: "video/mp4",
        byteSize: 1024,
        sha256: "a".repeat(64),
        width: 1280,
        height: 720,
        durationMs: id.includes("audio") ? 10000 : id === "asset-video-2" ? 4000 : duration,
        createdAt: "2026-10-22T00:00:00Z",
      });
    }),
  );
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  const close = vi.fn();
  const rendered = render(
    <QueryClientProvider client={client}>
      <VideoEditDialog projectId="project-1" items={items} initialIds={initialIds} onClose={close} />
    </QueryClientProvider>,
  );
  return { close, reads, unmount: rendered.unmount };
}
function setNumber(name: string, value: number, root: HTMLElement = screen.getByRole("dialog")) {
  fireEvent.change(within(root).getByRole("spinbutton", { name }), { target: { value: String(value) } });
}

describe("VideoEditDialog", () => {
  it("reads server budgets and permits a longer edit and 4K output", async () => {
    setup({ duration: 90000 });
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    await userEvent.setup().click(screen.getByRole("tab", { name: "导出" }));
    await selectValue(screen.getByRole("combobox", { name: "输出分辨率" }), "2160P");
    expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled();
    expect(screen.getByText(/2000.*2048.*500/)).toBeVisible();
  });
  it("blocks export when capabilities fail and retries without losing the draft", async () => {
    let calls = 0;
    server.use(
      http.get("/api/v1/projects/project-1/video-compositions/capabilities", () =>
        ++calls === 1 ? HttpResponse.json({ detail: "Unavailable" }, { status: 503 }) : HttpResponse.json(capabilities),
      ),
    );
    setup();
    await screen.findByRole("alert");
    expect(screen.getByRole("button", { name: "导出 MP4" })).toBeDisabled();
    await userEvent.setup().click(screen.getByRole("tab", { name: "导出" }));
    fireEvent.change(screen.getByRole("textbox", { name: "成片名称" }), { target: { value: "保留长片草稿" } });
    await userEvent.setup().click(screen.getByRole("button", { name: "重试读取" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    expect(screen.getByRole("textbox", { name: "成片名称" })).toHaveValue("保留长片草稿");
  });
  it("keeps a bounded caption editor and retains edits across caption pages", async () => {
    setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    await userEvent.setup().click(screen.getByRole("tab", { name: "字幕" }));
    fireEvent.click(screen.getByRole("button", { name: "添加字幕" }));
    fireEvent.change(screen.getByRole("textbox", { name: "字幕正文" }), { target: { value: "保留的第一句" } });
    for (let i = 0; i < 50; i++) fireEvent.click(screen.getByRole("button", { name: "添加字幕" }));
    expect(screen.getAllByRole("textbox", { name: "字幕正文" })).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "上一页字幕" }));
    expect(screen.getAllByRole("textbox", { name: "字幕正文" })).toHaveLength(50);
    expect(screen.getByDisplayValue("保留的第一句")).toBeVisible();
  });
  it("moves initial keyboard focus into the editor and keeps Tab navigation inside it", async () => {
    setup();
    const user = userEvent.setup();
    const dialog = screen.getByRole("dialog");
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "关闭" })).toHaveFocus());
    await user.tab();
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    await user.tab({ shift: true });
    expect(within(dialog).getByRole("button", { name: "关闭" })).toHaveFocus();
  });
  it("submits ordered exact clips with trims, speed, fades, voices, music, output and timed subtitles", async () => {
    const requests: VideoCompositionRequest[] = [];
    server.use(
      http.post("/api/v1/projects/project-1/video-compositions", async ({ request }) => {
        requests.push((await request.json()) as VideoCompositionRequest);
        return HttpResponse.json({ id: "task-edit", status: "READY" });
      }),
    );
    const { close } = setup({ initialIds: ["video-1", "video-2"] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    const first = screen.getByRole("region", { name: "片段 1" });
    setNumber("入点（秒）", 1, first);
    setNumber("出点（秒）", 5, first);
    setNumber("视频速度（倍）", 2, first);
    setNumber("片段首尾淡黑（毫秒）", 100, first);
    await user.click(screen.getByRole("tab", { name: "音频" }));
    await user.click(screen.getByRole("button", { name: "添加配音（自动定位）" }));
    await user.click(within(screen.getByRole("listitem", { name: "片段 2" })).getByRole("button", { name: "上移" }));
    await selectValue(screen.getByRole("combobox", { name: "原片音轨" }), "BACKGROUND");

    await selectValue(screen.getByRole("combobox", { name: "背景音乐" }), "version-audio-1");
    setNumber("音乐音量（0–1）", 0.4);
    setNumber("音乐取源起点（秒）", 1);
    await user.click(screen.getByRole("tab", { name: "导出" }));
    await selectValue(screen.getByRole("combobox", { name: "输出分辨率" }), "1080P");
    await selectValue(screen.getByRole("combobox", { name: "输出画幅" }), "9:16");
    await user.click(screen.getByRole("tab", { name: "字幕" }));
    await user.click(screen.getByRole("button", { name: "添加字幕" }));
    fireEvent.change(screen.getByRole("textbox", { name: "字幕正文" }), { target: { value: "这是一段字幕" } });
    setNumber("字幕开始（秒）", 0.5);
    setNumber("字幕结束（秒）", 2);
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    await waitFor(() => expect(close).toHaveBeenCalledOnce());
    expect(requests).toEqual([
      {
        title: "剪辑成片",
        originalAudioMode: "BACKGROUND",
        output: { resolution: "1080P", aspectRatio: "9:16" },
        shots: [
          { videoVersionId: "version-video-2", sourceStartMs: 0, durationMs: 4000, speed: 1, fadeMs: 0, lines: [] },
          {
            videoVersionId: "version-video-1",
            sourceStartMs: 1000,
            durationMs: 4000,
            speed: 2,
            fadeMs: 100,
            lines: [{ audioVersionId: "version-audio-1" }],
          },
        ],
        music: { audioVersionId: "version-audio-1", sourceStartMs: 1000, volume: 0.4 },
        subtitles: [{ text: "这是一段字幕", startMs: 500, endMs: 2000 }],
      },
    ]);
  });

  it("allows a silent editing workflow and reads only the clips in use", async () => {
    let request: VideoCompositionRequest | undefined;
    server.use(
      http.post("/api/v1/projects/project-1/video-compositions", async ({ request: incoming }) => {
        request = (await incoming.json()) as VideoCompositionRequest;
        return HttpResponse.json({ id: "silent", status: "READY" });
      }),
    );
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
    server.use(
      http.post("/api/v1/projects/project-1/video-compositions", async ({ request }) => {
        requests.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
        return HttpResponse.json(
          { title: "响应暂时丢失", detail: "响应暂时丢失" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } },
        );
      }),
    );
    setup();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    await user.click(screen.getByRole("tab", { name: "导出" }));
    fireEvent.change(screen.getByRole("textbox", { name: "成片名称" }), { target: { value: "我的剪辑" } });
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    await screen.findByRole("alert");
    expect(screen.getByRole("textbox", { name: "成片名称" })).toHaveValue("我的剪辑");
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[0]?.key).toBeTruthy();
    expect(requests[1]).toEqual(requests[0]);
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    fireEvent.change(screen.getByRole("textbox", { name: "成片名称" }), { target: { value: "新的剪辑" } });
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    await waitFor(() => expect(requests).toHaveLength(3));
    expect(requests[2]?.key).not.toBe(requests[0]?.key);
  });

  it("cuts a short interval from a long source and refuses invalid speed, subtitle and music ranges", async () => {
    server.use(
      http.get("/api/v1/projects/project-1/video-compositions/capabilities", () =>
        HttpResponse.json({ ...capabilities, maxDurationMs: 60000, maxFrames: 1800 }),
      ),
    );
    setup({ duration: 120000 });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("spinbutton", { name: "出点（秒）" })).toHaveValue(120));
    const submit = screen.getByRole("button", { name: "导出 MP4" });
    expect(submit).toBeDisabled();
    setNumber("入点（秒）", 90);
    setNumber("出点（秒）", 100);
    expect(submit).toBeEnabled();
    setNumber("视频速度（倍）", 0.4);
    expect(submit).toBeDisabled();
    setNumber("视频速度（倍）", 1);
    await user.click(screen.getByRole("tab", { name: "字幕" }));
    await user.click(screen.getByRole("button", { name: "添加字幕" }));
    fireEvent.change(screen.getByRole("textbox", { name: "字幕正文" }), { target: { value: "字幕" } });
    setNumber("字幕结束（秒）", 11);
    expect(submit).toBeDisabled();
    setNumber("字幕结束（秒）", 10);
    await user.click(screen.getByRole("tab", { name: "音频" }));
    await selectValue(screen.getByRole("combobox", { name: "背景音乐" }), "version-audio-1");
    await waitFor(() => expect(submit).toBeEnabled());
    setNumber("音乐取源起点（秒）", 10);
    expect(submit).toBeDisabled();
    setNumber("音乐取源起点（秒）", 1);
    expect(submit).toBeEnabled();
  });

  it("retries source metadata without discarding clip edits", async () => {
    setup({ metadataFailure: true });
    const user = userEvent.setup();
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
    setup({ initialIds: ["video-1", "video-2"] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    expect(document.querySelectorAll("video")).toHaveLength(1);
    setNumber("入点（秒）", 1);
    setNumber("出点（秒）", 5);
    setNumber("视频速度（倍）", 2);
    const firstPlayer = screen.getByLabelText("完整成片预览");
    await user.click(screen.getByRole("tab", { name: "音频" }));
    expect(screen.getByLabelText("完整成片预览")).toBe(firstPlayer);
    await user.click(screen.getByRole("button", { name: "添加配音（自动定位）" }));
    await user.click(screen.getByRole("button", { name: "时间线片段 2：video-2" }));
    expect(document.querySelectorAll("video")).toHaveLength(1);
    expect(screen.getByLabelText("完整成片预览")).toHaveAttribute(
      "src",
      "/api/v1/projects/project-1/assets/asset-video-2/content",
    );
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
    server.use(
      http.post("/api/v1/projects/project-1/video-compositions", async ({ request: incoming }) => {
        request = (await incoming.json()) as VideoCompositionRequest;
        return HttpResponse.json({ id: "ordered", status: "READY" });
      }),
    );
    setup({ initialIds: ["video-1", "video-2"] });
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    setNumber("入点（秒）", 1);
    const first = screen.getByRole("button", { name: "时间线片段 1：video-1" });
    const second = screen.getByRole("button", { name: "时间线片段 2：video-2" });
    const dataTransfer = { setData: vi.fn(), effectAllowed: "", dropEffect: "" };
    fireEvent.dragStart(first, { dataTransfer });
    fireEvent.dragOver(second, { dataTransfer });
    fireEvent.drop(second, { dataTransfer });
    expect(screen.getByRole("button", { name: "时间线片段 2：video-1" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("spinbutton", { name: "入点（秒）" })).toHaveValue(1);
    fireEvent.click(screen.getByRole("button", { name: "导出 MP4" }));
    await waitFor(() =>
      expect(request?.shots.map(shot => [shot.videoVersionId, shot.sourceStartMs])).toEqual([
        ["version-video-2", 0],
        ["version-video-1", 1000],
      ]),
    );
  });

  it("selects the next clip on removal and recovers from an empty sequence by adding a clip", async () => {
    setup({ initialIds: ["video-1", "video-2"] });
    const user = userEvent.setup();
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
    server.use(
      http.post("/api/v1/projects/project-1/video-compositions", async () => {
        await new Promise<void>(resolve => {
          finish = resolve;
        });
        return HttpResponse.json({ id: "pending", status: "READY" });
      }),
    );
    const { close } = setup();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "导出 MP4" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "导出 MP4" }));
    expect(screen.getByRole("button", { name: "正在受理…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "关闭" })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "入点（秒）" })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "视频速度（倍）" })).toBeDisabled();
    await user.keyboard("{Escape}");
    expect(close).not.toHaveBeenCalled();
    await waitFor(() => expect(finish).toBeTypeOf("function"));
    finish?.();
    await waitFor(() => expect(close).toHaveBeenCalledOnce());
  });
  it("cancels an active trim with Escape without closing the editor", async () => {
    const { close } = setup();
    const user = userEvent.setup();
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

function sourceSubtitle(versionId: string, durationMs: number): SubtitleDraft {
  return {
    id: `subtitle-${versionId}`,
    projectId: "project-1",
    sourceVersionId: versionId,
    sourceDurationMs: durationMs,
    language: "zh",
    cues: [{ text: "Synthetic recognized 中文", startMs: 1000, endMs: 3000 }],
    version: 0,
    taskId: null,
    updatedAt: "2026-11-01T00:00:00Z",
  };
}

describe("persistent source subtitles in the composition editor", () => {
  it("saves corrected source text and exports the same late timeline positions to SRT, VTT and the composition request", async () => {
    subtitleDrafts = [sourceSubtitle("version-video-2", 4000)];
    const accepted: VideoCompositionRequest[] = [];
    server.use(
      http.post("/api/v1/projects/project-1/video-compositions", async ({ request }) => {
        accepted.push((await request.json()) as VideoCompositionRequest);
        return HttpResponse.json({ detail: "Synthetic acceptance failure" }, { status: 503 });
      }),
    );
    setup({ initialIds: ["video-1", "video-2"], duration: 90000 });
    await waitFor(() => expect(screen.getByRole("button", { name: t("video.edit.submit") })).toBeEnabled());
    await userEvent.setup().click(screen.getByRole("tab", { name: t("video.edit.captionsTab") }));
    await selectValue(screen.getByRole("combobox", { name: t("subtitle.source") }), "version-video-2");
    await screen.findByDisplayValue("Synthetic recognized 中文");
    fireEvent.change(screen.getByRole("textbox", { name: t("subtitle.ctext", { index: 1 }) }), {
      target: { value: "Corrected 中文" },
    });
    fireEvent.change(screen.getByRole("spinbutton", { name: t("subtitle.start", { index: 1 }) }), {
      target: { value: "1500" },
    });
    fireEvent.change(screen.getByRole("spinbutton", { name: t("subtitle.end", { index: 1 }) }), {
      target: { value: "3500" },
    });
    await userEvent.setup().click(screen.getByRole("button", { name: t("subtitle.exportTimelineSrt") }));
    await waitFor(() => expect(subtitleDownloads).toHaveLength(1));
    expect(subtitleDownloads[0]).toEqual({
      format: "SRT",
      text: "1\n00:01:31,500 --> 00:01:33,500\nCorrected 中文\n\n",
    });
    await userEvent.setup().click(screen.getByRole("button", { name: t("subtitle.exportTimelineVtt") }));
    await waitFor(() => expect(subtitleDownloads).toHaveLength(2));
    expect(subtitleDownloads[1]?.text).toBe("WEBVTT\n\n00:01:31.500 --> 00:01:33.500\nCorrected 中文\n\n");
    await userEvent.setup().click(screen.getByRole("button", { name: t("video.edit.submit") }));
    await waitFor(() => expect(accepted).toHaveLength(1));
    expect(accepted[0]?.subtitles).toEqual([{ text: "Corrected 中文", startMs: 91500, endMs: 93500 }]);
    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: t("video.edit.selectClip", { "0": 2, "1": "video-2" }) }));
    setNumber(t("video.edit.speed"), 2);
    const second = screen.getByRole("listitem", { name: `${t("video.edit.clip")} 2` });
    await userEvent.setup().click(within(second).getByRole("button", { name: t("video.edit.moveUp") }));
    await userEvent.setup().click(screen.getByRole("button", { name: t("video.edit.submit") }));
    await waitFor(() => expect(accepted).toHaveLength(2));
    expect(accepted[1]?.shots[0]?.videoVersionId).toBe("version-video-2");
    expect(accepted[1]?.subtitles).toEqual([{ text: "Corrected 中文", startMs: 750, endMs: 1750 }]);
  });

  it("blocks 2001 projected captions without discarding corrections and submits all 2000 at the budget", async () => {
    subtitleDrafts = [
      {
        ...sourceSubtitle("version-video-1", 6000),
        cues: Array.from({ length: 2000 }, (_, index) => ({
          text: `Caption ${index + 1}`,
          startMs: 1000,
          endMs: 3000,
        })),
      },
    ];
    const accepted: VideoCompositionRequest[] = [];
    server.use(
      http.post("/api/v1/projects/project-1/video-compositions", async ({ request }) => {
        accepted.push((await request.json()) as VideoCompositionRequest);
        return HttpResponse.json({ id: "fixture-task", status: "READY" });
      }),
    );
    setup();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: t("video.edit.submit") })).toBeEnabled());
    await user.click(screen.getByRole("tab", { name: t("video.edit.captionsTab") }));
    fireEvent.change(screen.getByRole("textbox", { name: t("subtitle.ctext", { index: 1 }) }), {
      target: { value: "Keep corrected boundary" },
    });
    await user.click(screen.getByRole("button", { name: t("subtitle.add") }));
    fireEvent.change(screen.getByRole("textbox", { name: t("subtitle.ctext", { index: 2001 }) }), {
      target: { value: "One beyond the budget" },
    });
    const submit = screen.getByRole("button", { name: t("video.edit.submit") });
    expect(submit).toBeDisabled();
    expect(screen.getByDisplayValue("One beyond the budget")).toBeVisible();
    expect(accepted).toEqual([]);
    await user.click(screen.getByRole("button", { name: t("subtitle.remove", { index: 2001 }) }));
    expect(submit).toBeEnabled();
    await user.click(submit);
    await waitFor(() => expect(accepted).toHaveLength(1));
    expect(accepted[0]?.subtitles).toHaveLength(2000);
    expect(accepted[0]?.subtitles?.[0]).toEqual({ text: "Keep corrected boundary", startMs: 1000, endMs: 3000 });
    expect(accepted[0]?.subtitles?.[1999]).toEqual({ text: "Caption 2000", startMs: 1000, endMs: 3000 });
  });

  it("keeps manual blank-line captions editable but blocks every export until the exchange text is valid", async () => {
    setup();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: t("video.edit.submit") })).toBeEnabled());
    await user.click(screen.getByRole("tab", { name: t("video.edit.captionsTab") }));
    await user.click(screen.getByRole("button", { name: "添加字幕" }));
    const text = screen.getByRole("textbox", { name: "字幕正文" });
    fireEvent.change(text, { target: { value: "甲\n\n乙" } });
    expect(text).toHaveValue("甲\n\n乙");
    for (const label of [t("video.edit.submit"), t("subtitle.exportTimelineSrt"), t("subtitle.exportTimelineVtt")]) {
      expect(screen.getByRole("button", { name: label })).toBeDisabled();
    }
    fireEvent.change(text, { target: { value: "甲\n乙" } });
    for (const label of [t("video.edit.submit"), t("subtitle.exportTimelineSrt"), t("subtitle.exportTimelineVtt")]) {
      expect(screen.getByRole("button", { name: label })).toBeEnabled();
    }
  });

  it("projects source tabs to the same normalized text in SRT, VTT and MP4 without altering the source", async () => {
    subtitleDrafts = [
      { ...sourceSubtitle("version-video-1", 6000), cues: [{ text: "甲\t乙", startMs: 1000, endMs: 3000 }] },
    ];
    const accepted: VideoCompositionRequest[] = [];
    server.use(
      http.post("/api/v1/projects/project-1/video-compositions", async ({ request }) => {
        accepted.push((await request.json()) as VideoCompositionRequest);
        return HttpResponse.json({ id: "fixture-task", status: "READY" });
      }),
    );
    setup();
    const user = userEvent.setup();
    await user.click(screen.getByRole("tab", { name: t("video.edit.captionsTab") }));
    await waitFor(() => expect(screen.getByRole("button", { name: t("video.edit.submit") })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: t("subtitle.exportTimelineSrt") }));
    await user.click(screen.getByRole("button", { name: t("subtitle.exportTimelineVtt") }));
    await waitFor(() => expect(subtitleDownloads).toHaveLength(2));
    expect(subtitleDownloads[0]?.text).toBe("1\n00:00:01,000 --> 00:00:03,000\n甲 乙\n\n");
    expect(subtitleDownloads[1]?.text).toBe("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n甲 乙\n\n");
    await user.click(screen.getByRole("button", { name: t("video.edit.submit") }));
    await waitFor(() => expect(accepted).toHaveLength(1));
    expect(accepted[0]?.subtitles).toEqual([{ text: "甲 乙", startMs: 1000, endMs: 3000 }]);
    expect(subtitleDrafts[0]?.cues[0]?.text).toBe("甲\t乙");
  });

  it("uses actual planned voice trim and speed for recognized audio subtitles", async () => {
    subtitleDrafts = [
      {
        ...sourceSubtitle("version-audio-1", 10000),
        cues: [{ text: "Controlled voice caption", startMs: 250, endMs: 750 }],
      },
    ];
    const accepted: VideoCompositionRequest[] = [];
    server.use(
      http.post("/api/v1/projects/project-1/video-compositions/preview", async ({ request }) => {
        const body = (await request.json()) as VideoCompositionRequest;
        return HttpResponse.json({
          durationMs: 6000,
          frames: 180,
          fontFamily: "Noto Sans CJK SC",
          shots: [
            {
              videoVersionId: "version-video-1",
              timelineStartMs: 0,
              durationMs: 6000,
              lines: body.shots[0]?.lines.length
                ? [
                    {
                      audioVersionId: "version-audio-1",
                      startMs: 2000,
                      endMs: 3000,
                      trimStartMs: 100,
                      trimEndMs: 1200,
                      speed: 1.1,
                    },
                  ]
                : [],
            },
          ],
        });
      }),
      http.post("/api/v1/projects/project-1/video-compositions", async ({ request }) => {
        accepted.push((await request.json()) as VideoCompositionRequest);
        return HttpResponse.json({ id: "fixture-task", status: "READY" });
      }),
    );
    setup();
    await userEvent.setup().click(screen.getByRole("tab", { name: t("video.edit.audioTab") }));
    await userEvent.setup().click(screen.getByRole("button", { name: t("video.edit.addVoice") }));
    await waitFor(() => expect(screen.getByRole("button", { name: t("video.edit.submit") })).toBeEnabled());
    await userEvent.setup().click(screen.getByRole("button", { name: t("video.edit.submit") }));
    await waitFor(() => expect(accepted).toHaveLength(1));
    expect(accepted[0]?.subtitles).toEqual([{ text: "Controlled voice caption", startMs: 2136, endMs: 2590 }]);
  });
  it("keeps corrected subtitles open on save failure and reloads them after a successful close", async () => {
    subtitleDrafts = [sourceSubtitle("version-video-1", 6000)];
    const first = setup();
    await userEvent.setup().click(screen.getByRole("tab", { name: t("video.edit.captionsTab") }));
    await screen.findByDisplayValue("Synthetic recognized 中文");
    fireEvent.change(screen.getByRole("textbox", { name: t("subtitle.ctext", { index: 1 }) }), {
      target: { value: "Keep on close" },
    });
    subtitleSaveFailure = true;
    await userEvent.setup().click(screen.getByRole("button", { name: t("common.close") }));
    await screen.findAllByText("Synthetic save failure");
    expect(first.close).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue("Keep on close")).toBeVisible();
    subtitleSaveFailure = false;
    await userEvent.setup().click(screen.getByRole("button", { name: t("common.close") }));
    await waitFor(() => expect(first.close).toHaveBeenCalledOnce());
    first.unmount();
    setup();
    await userEvent.setup().click(screen.getByRole("tab", { name: t("video.edit.captionsTab") }));
    await screen.findByDisplayValue("Keep on close");
  });
});
